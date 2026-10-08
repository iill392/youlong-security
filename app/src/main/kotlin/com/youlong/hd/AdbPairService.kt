package com.youlong.hd

import kotlin.jvm.Volatile
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import roro.stellar.manager.adb.AdbMdns


class AdbPairService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var portJob: Job? = null
    private var pipelineJob: Job? = null

    // 跨线程（主线程写 / IO 协程读写）访问，需可见性保证
    @Volatile
    private var port = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        
        
        try {
            startForegroundSafely(buildNotification("正在准备…（请打开系统的「使用配对码配对设备」）", null))
        } catch (t: Throwable) {
            CrashLogger.event("[无线配对] 前台化失败", t)
        }

        when (action) {
            ACTION_STOP -> {
                CrashLogger.event("[无线配对] 用户取消（通知）")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CODE -> {
                val code = readRemoteInput(intent) ?: intent?.getStringExtra(EXTRA_CODE)
                if (code.isNullOrEmpty()) {
                    updateNotification("没读到配对码，请重新在通知里输入")
                    return START_NOT_STICKY
                }
                val manualPort = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
                if (manualPort in 1..65535) port = manualPort
                runPipeline(code)
                return START_NOT_STICKY
            }
            else -> {
                // 修复：ACTION_START 分支此前丢弃了用户手填的配对端口
                val startPort = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
                if (startPort in 1..65535) port = startPort
                CrashLogger.event("[无线配对] 通知配对已启动（等待用户输入配对码）")
                startPortSearch()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        portJob?.cancel()
        pipelineJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ======================================================================
    
    // ======================================================================
    private fun startPortSearch() {
        portJob?.cancel()
        portJob = scope.launch {
            var tries = 0
            while (tries < 60) {
                tries++
                val found = AdbPairFlow.discover(applicationContext, AdbMdns.TLS_PAIRING, 2500L)
                if (found != null && found > 0) {
                    port = found
                    updateNotification("已找到配对端口 $found；请下拉通知栏，在本条通知里输入 6 位配对码", found)
                    return@launch
                }
                delay(500L)
            }
            updateNotification("没搜到配对端口：请确认系统「使用配对码配对设备」弹窗正开着")
        }
    }

    // ======================================================================
    
    // ======================================================================
    private fun runPipeline(code: String) {
        if (pipelineJob?.isActive == true) return
        portJob?.cancel()
        pipelineJob = scope.launch {
            var ok = false
            try {
                var pairPort = port
                if (pairPort !in 1..65535) {
                    updateNotification("正在重新搜索配对端口…")
                    pairPort = AdbPairFlow.discover(applicationContext, AdbMdns.TLS_PAIRING, 6000L) ?: -1
                }
                if (pairPort !in 1..65535) {
                    updateNotification("配对失败：没找到配对端口，请确认弹窗还开着后重试")
                    return@launch
                }

                updateNotification("正在配对（端口 $pairPort）…", pairPort)
                val paired = AdbPairFlow.pair(pairPort, code)
                if (!paired) {
                    updateNotification("配对失败：核对配对码；弹窗必须一直开着（关闭即失效）", pairPort)
                    CrashLogger.event("[无线配对] 通知配对失败（SPAKE2 未通过）")
                    return@launch
                }
                CrashLogger.event("[无线配对] 通知配对成功（端口=$pairPort）")
                updateNotification("配对成功，正在搜索连接端口…", pairPort)

                var connectPort = AdbPairFlow.discover(applicationContext, AdbMdns.TLS_CONNECT, 10000L)
                if (connectPort == null || connectPort <= 0) {
                    val sysPort = AdbPairFlow.systemPort()
                    if (sysPort in 1..65535) connectPort = sysPort
                }
                if (connectPort == null || connectPort <= 0) {
                    updateNotification("配对成功，但没找到连接端口；请保持无线调试开启后重试", pairPort)
                    return@launch
                }
                updateNotification("正在通过 ADB 启动特权服务（端口 $connectPort）…", pairPort)

                try {
                    AdbPairFlow.grantSecureSettings(connectPort, packageName) { }
                } catch (_: Throwable) {
                }

                
                
                
                
                val startJob = launch {
                    runCatching { AdbPairFlow.startService(connectPort) }
                }

                updateNotification("启动命令已下发，正在等待特权服务就绪…", pairPort)
                ok = AdbPairFlow.waitBinder(30_000L)
                if (!ok) startJob.cancel()
                if (ok) {
                    updateNotification("特权服务已启动 ✔（uid 2000 / shell）。可回特权面板跑自检", pairPort)
                    CrashLogger.event("[无线配对] 已通过通知配对启动特权服务（连接端口=$connectPort）")
                } else {
                    updateNotification("启动命令已执行，但 30 秒内没等到服务；可回特权面板点「重新连接」", pairPort)
                }
            } catch (t: Throwable) {
                updateNotification("出错：" + (t.message ?: t.javaClass.simpleName), port)
                CrashLogger.event("[无线配对] 通知配对流程异常", t)
            } finally {
                
                try {
                    stopForeground(if (ok) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                } catch (_: Throwable) {
                }
                if (!ok) {
                    stopSelf()
                } else {
                    // 成功后延迟数秒自停，避免服务空跑常驻
                    scope.launch {
                        delay(2_000L)
                        stopSelf()
                    }
                }
            }
        }
    }

    // ======================================================================
    
    // ======================================================================
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "无线调试配对", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "免 root 启动特权服务：在通知里输入系统弹窗的配对码"
        ch.setShowBadge(true)
        nm.createNotificationChannel(ch)
    }

    private fun startForegroundSafely(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    
    private fun buildNotification(text: String, portForReply: Int?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("无线调试配对")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(portForReply == null && !text.contains("✔"))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        
        try {
            val open = Intent(this, AdbPairActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            b.setContentIntent(
                PendingIntent.getActivity(this, 0x7A10, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        } catch (_: Throwable) {
        }

        
        try {
            val reply = Intent(this, AdbPairService::class.java).setAction(ACTION_CODE)
            if (portForReply != null && portForReply in 1..65535) reply.putExtra(EXTRA_PORT, portForReply)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getService(this, 0x7A11, reply, flags)
            val remoteInput = RemoteInput.Builder(KEY_CODE)
                .setLabel("输入弹窗里的 6 位配对码")
                .build()
            val action = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_manage,
                "输入配对码",
                pi
            ).addRemoteInput(remoteInput).build()
            b.addAction(action)
        } catch (t: Throwable) {
            CrashLogger.event("[无线配对] 构建通知输入动作失败", t)
        }

        
        try {
            val stop = Intent(this, AdbPairService::class.java).setAction(ACTION_STOP)
            val pi = PendingIntent.getService(this, 0x7A12, stop,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "取消", pi)
        } catch (_: Throwable) {
        }

        return b.build()
    }

    private fun updateNotification(text: String, portForReply: Int? = null) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTI_ID, buildNotification(text, portForReply))
        } catch (_: Throwable) {
        }
    }

    private fun readRemoteInput(intent: Intent?): String? {
        if (intent == null) return null
        return try {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val ACTION_START = "com.youlong.hd.adbpair.START"
        const val ACTION_CODE = "com.youlong.hd.adbpair.CODE"
        const val ACTION_STOP = "com.youlong.hd.adbpair.STOP"
        const val KEY_CODE = "pair_code"
        const val EXTRA_CODE = "pair_code_extra"
        const val EXTRA_PORT = "pair_port"
        private const val CHANNEL = "adb_pair"
        private const val NOTI_ID = 0x7A11

        fun startIntent(ctx: Context, port: Int = -1): Intent =
            Intent(ctx, AdbPairService::class.java).setAction(ACTION_START).putExtra(EXTRA_PORT, port)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, AdbPairService::class.java).setAction(ACTION_STOP)
    }
}
