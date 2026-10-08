package com.youlong.hd

import kotlin.jvm.Volatile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.adb.AdbClient
import roro.stellar.manager.adb.AdbKey
import roro.stellar.manager.adb.AdbMdns
import roro.stellar.manager.adb.AdbPairingClient
import roro.stellar.manager.adb.PreferenceAdbKeyStore
import roro.stellar.manager.compat.LocalNetwork
import roro.stellar.manager.startup.worker.AdbStarter
import roro.stellar.manager.util.EnvironmentUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class AdbPairActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var etPairPort: EditText
    private lateinit var etPairCode: EditText
    private lateinit var etConnectPort: EditText
    private lateinit var btnStart: Button
    private lateinit var btnSearchPairPort: Button
    private lateinit var btnTestOnly: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    // IO 协程 finally 写、主线程读：需要可见性保证
    @Volatile
    private var busy = false

    
    private var overlay: AdbPairOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        log("本机 Wi-Fi 地址：" + (EnvironmentUtils.getWifiIpAddress() ?: "（未连接 Wi-Fi）"))
        log("提示：无线调试必须在「已连接 Wi-Fi」的前提下才能开启。")

        
        
        ensureLocalNetworkPermission()

        
        autoSearchPairingPort()
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        scope.cancel()
        super.onDestroy()
    }

    // ======================================================================
    
    // ======================================================================

    private fun buildContentView(): ScrollView {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFFF2F2F7.toInt())

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val title = TextView(this)
        title.text = "无线调试配对"
        title.textSize = 24f
        title.setTextColor(0xFF1C1C1E.toInt())
        root.addView(title)

        val sub = TextView(this)
        sub.text = "免 root 启动特权服务（不需要电脑，也不需要安装任何第三方应用）"
        sub.textSize = 13f
        sub.setTextColor(0xFF8E8E93.toInt())
        sub.setPadding(0, dp(6), 0, dp(16))
        root.addView(sub)

        
        val guide = TextView(this)
        guide.text = """
            操作步骤：
            1. 打开「设置 → 系统管理/更多设置 → 开发者选项 → 无线调试」，并保持开启；
            2. 点「使用配对码配对设备」，屏幕会弹出 6 位配对码和一段端口号；
            3. 把配对码填到下面（端口一般已自动填好），点「开始配对并启动特权服务」；
            4. 看到「特权服务已启动」即成功，之后可回到特权面板跑自检。
        """.trimIndent()
        guide.textSize = 13f
        guide.setTextColor(0xFF3A3A3C.toInt())
        guide.setBackgroundColor(0xFFFFFFFF.toInt())
        guide.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.addView(guide, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(0xFFFFFFFF.toInt())
        card.setPadding(dp(14), dp(16), dp(14), dp(16))
        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = dp(16)
        root.addView(card, cardLp)

        etPairPort = addInput(card, "配对端口（系统弹窗里的端口）", InputType.TYPE_CLASS_NUMBER)
        etPairCode = addInput(card, "配对码（6 位数字）", InputType.TYPE_CLASS_NUMBER)
        etConnectPort = addInput(card, "连接端口（可留空，自动搜索）", InputType.TYPE_CLASS_NUMBER)

        
        
        
        addButton(card, "通知里输入配对码（推荐）", primary = true) { startNotifyPairing() }

        
        addButton(card, "悬浮窗配对（备用）", primary = false) { startOverlayPairing() }
        btnStart = addButton(card, "开始配对并启动特权服务", primary = true) { startFlow(pairOnly = false) }
        btnSearchPairPort = addButton(card, "自动搜索配对端口", primary = false) { autoSearchPairingPort() }
        btnTestOnly = addButton(card, "仅配对（不启动服务）", primary = false) { startFlow(pairOnly = true) }

        tvStatus = TextView(this)
        tvStatus.text = "状态：待开始"
        tvStatus.textSize = 14f
        tvStatus.setTextColor(0xFF1C1C1E.toInt())
        tvStatus.setPadding(0, dp(14), 0, 0)
        card.addView(tvStatus)

        
        val logLabel = TextView(this)
        logLabel.text = "运行日志"
        logLabel.textSize = 13f
        logLabel.setTextColor(0xFF6E6E73.toInt())
        logLabel.setPadding(0, dp(22), 0, dp(8))
        root.addView(logLabel)

        tvLog = TextView(this)
        tvLog.text = ""
        tvLog.textSize = 12f
        tvLog.setTextColor(0xFF3A3A3C.toInt())
        tvLog.typeface = android.graphics.Typeface.MONOSPACE
        tvLog.setTextIsSelectable(true)
        tvLog.setBackgroundColor(0xFFFFFFFF.toInt())
        tvLog.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.addView(tvLog, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tip = TextView(this)
        tip.text = "说明：配对只在系统「无线调试」开启期间有效；配对成功后，" +
"系统会记住本应用的调试密钥，以后可直接启动特权服务。"
        tip.textSize = 12f
        tip.setTextColor(0xFF8E8E93.toInt())
        tip.setPadding(0, dp(16), 0, 0)
        root.addView(tip)

        return scroll
    }

    private fun addInput(parent: LinearLayout, hint: String, inputType: Int): EditText {
        val label = TextView(this)
        label.text = hint
        label.textSize = 13f
        label.setTextColor(0xFF6E6E73.toInt())
        label.setPadding(0, dp(10), 0, dp(4))
        parent.addView(label)

        val et = EditText(this)
        et.inputType = inputType
        et.textSize = 16f
        et.hint = hint
        parent.addView(et, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return et
    }

    private fun addButton(parent: LinearLayout, text: String, primary: Boolean, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = text
        btn.textSize = 16f
        if (primary) {
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.setBackgroundColor(0xFF0A84FF.toInt())
        }
        btn.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        parent.addView(btn, lp)
        return btn
    }

    // ======================================================================
    
    // ======================================================================

    private fun ensureLocalNetworkPermission() {
        if (!LocalNetwork.isRequired()) return
        if (LocalNetwork.hasAccess(this)) return
        log("需要「附近的设备 / 本地网络」权限才能搜索无线调试服务，正在申请…")
        try {
            ActivityCompat.requestPermissions(
                this, arrayOf(LocalNetwork.PERMISSION), REQ_LOCAL_NETWORK)
        } catch (t: Throwable) {
            log("申请本地网络权限失败：" + t.message)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCAL_NETWORK) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        log(if (ok) "本地网络权限已授予" else "本地网络权限被拒绝：无法搜索无线调试服务，" +
"可手动填写端口继续")
        if (ok) autoSearchPairingPort()
    }

    // ======================================================================
    
    // ======================================================================

    private fun autoSearchPairingPort() {
        if (busy) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("系统版本低于 Android 11，不支持无线调试配对")
            return
        }
        scope.launch {
            setStatus("正在自动搜索配对端口…")
            log("开始搜索 `_adb-tls-pairing._tcp` 服务（8 秒超时）…")
            val port = discoverPort(AdbMdns.TLS_PAIRING, 8000L)
            if (port != null && port > 0) {
                runOnUiThread {
                    etPairPort.setText(port.toString())
                    setStatus("已找到配对端口：$port，请填写配对码后开始")
                }
                log("已找到配对端口：$port")
            } else {
                setStatus("未搜到配对端口：请先打开系统的「使用配对码配对设备」弹窗，或手动填写端口")
                log("未搜到配对端口。请确认：① 无线调试已开启；② 已点开「使用配对码配对设备」；" +
"③ Wi-Fi 已连接。仍不行可手动填写弹窗里的端口。")
            }
        }
    }

    private fun startFlow(pairOnly: Boolean) {
        if (busy) {
            log("上一次操作还在进行中，请稍候")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("系统版本低于 Android 11，不支持无线调试配对")
            return
        }
        val pairPort = etPairPort.text.toString().trim().toIntOrNull()
        val code = etPairCode.text.toString().trim()
        var connectPort = etConnectPort.text.toString().trim().toIntOrNull()

        if (pairPort == null || pairPort !in 1..65535) {
            setStatus("配对端口无效：请填系统弹窗里的端口号")
            return
        }
        if (!code.matches(Regex("\\d{6}"))) {
            setStatus("配对码无效：应为恰好 6 位数字")
            return
        }

        setBusy(true)
        scope.launch {
            try {
                
                setStatus("正在配对（SPAKE2）…")
                log("开始配对：host=127.0.0.1 port=$pairPort")
                val paired = pair(pairPort, code)
                if (!paired) {
                    setStatus("配对失败：请确认配对码与端口是当前弹窗里显示的（弹窗关闭即失效）")
                    log("配对失败：SPAKE2 未通过。常见原因：配对码错误、端口填成了连接端口、" +
"弹窗已关闭或超时。请重新打开「使用配对码配对设备」再试。")
                    return@launch
                }
                log("配对成功：本应用的 ADB 公钥已写入系统")
                if (pairOnly) {
                    setStatus("配对成功（仅配对，未启动服务）")
                    return@launch
                }

                
                
                
                val providedPort = connectPort
                if (providedPort == null || providedPort !in 1..65535) {
                    setStatus("正在搜索连接端口…")
                    val found = discoverPort(AdbMdns.TLS_CONNECT, 10000L)
                    if (found != null && found in 1..65535) {
                        connectPort = found
                    } else {
                        val sysPort = try {
                            EnvironmentUtils.getAdbTcpPort()
                        } catch (t: Throwable) {
                            -1
                        }
                        if (sysPort in 1..65535) {
                            connectPort = sysPort
                            log("mDNS 未搜到连接端口，改用系统端口：$sysPort")
                        }
                    }
                }
                val connectPortFinal: Int = connectPort ?: run {
                    setStatus("配对成功，但没找到连接端口：请在弹窗里记下端口后手动填入「连接端口」再重试")
                    log("未找到连接端口：mDNS 与系统属性都没有可用端口。" +
                            "请保持无线调试开启，或手动填写弹窗里的端口后重试。")
                    return@launch
                }
                log("连接端口：$connectPortFinal")

                
                try {
                    grantWriteSecureSettings(connectPortFinal)
                } catch (t: Throwable) {
                    log("顺带授权 WRITE_SECURE_SETTINGS 失败（不影响启动）：" + t.message)
                }

                
                setStatus("正在通过 ADB 启动特权服务…")
                log("执行启动器（shell 身份，等价于 adb shell libstellar.so --apk=…）…")
                val started = AdbStarter.startAdb("127.0.0.1", connectPortFinal)
                if (!started) {
                    setStatus("启动失败：ADB 连接被拒绝。请确认无线调试仍开启，并可尝试重新配对")
                    log("AdbStarter.startAdb 返回 false：连接/执行启动器失败")
                    return@launch
                }
                log("启动命令已下发，等待特权 Binder 就绪…")

                
                setStatus("正在等待特权服务就绪…")
                val ready = AdbStarter.waitForBinder(20000L)
                if (ready) {
                    setStatus("特权服务已启动 ✔ 可以回特权面板跑自检了")
                    log("特权 Binder 已就绪：uid 2000（shell）特权服务可用")
                    CrashLogger.event("[无线配对] 配对并启动特权服务成功（端口=$connectPortFinal）")
                    runOnUiThread {
                        Toast.makeText(this@AdbPairActivity,
                            "特权服务已启动", Toast.LENGTH_LONG).show()
                    }
                } else {
                    setStatus("启动命令已执行，但 20 秒内没等到特权服务：请回特权面板点「重新连接」或重试")
                    log("等待特权 Binder 超时。可能原因：系统拦截了后台启动、" +
"或服务端启动失败（可在特权面板「自检」里看输出）。")
                }
            } catch (t: Throwable) {
                setStatus("出错：" + (t.message ?: t.javaClass.simpleName))
                log("异常：" + t)
                CrashLogger.event("[无线配对] 流程异常", t)
            } finally {
                setBusy(false)
            }
        }
    }

    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    
    
    // ======================================================================
    private fun startNotifyPairing() {
        try {
            val inputPort = etPairPort.text.toString().trim().toIntOrNull() ?: -1
            val intent = AdbPairService.startIntent(this, if (inputPort in 1..65535) inputPort else -1)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            setStatus("已发出配对通知：请下拉通知栏，在「无线调试配对」通知里点「输入配对码」")
            log("已启动通知配对：请到「设置 → 开发者选项 → 无线调试 → 使用配对码配对设备」，" +
                    "然后下拉通知栏，在本应用的通知里点「输入配对码」并填 6 位配对码")
            log("端口可留空：服务会自己 mDNS 搜（填了就用你填的）")
        } catch (t: Throwable) {
            setStatus("启动通知配对失败：" + (t.message ?: t.javaClass.simpleName))
            CrashLogger.event("[无线配对] 启动通知配对失败", t)
        }
    }

    
    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    
    // ======================================================================
    private fun startOverlayPairing() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            setStatus("需要「显示在其他应用上层」权限：请先到特权面板/系统设置里开启悬浮窗")
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
            }
            return
        }
        if (overlay == null) {
            overlay = AdbPairOverlay(this, scope, { msg -> log(msg) }, { ok ->
                
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        setStatus(if (ok) "悬浮窗配对成功：特权服务已启动" else "悬浮窗配对结束")
                    }
                }
            })
        }
        overlay?.show()
        log("已打开悬浮窗：请到「设置 → 开发者选项 → 无线调试 → 使用配对码配对设备」，" +
                "把弹窗里的 6 位配对码填进悬浮窗（端口会自动搜到）")
        setStatus("请打开系统的「使用配对码配对设备」弹窗，并在悬浮窗里输入配对码")
    }

    // ======================================================================
    
    // ======================================================================
    private fun pair(port: Int, code: String): Boolean = AdbPairFlow.pair(port, code)

    private fun grantWriteSecureSettings(port: Int) {
        AdbPairFlow.grantSecureSettings(port, packageName) { out -> log("授权输出：" + out) }
        log("WRITE_SECURE_SETTINGS 已授权")
    }

    private suspend fun discoverPort(serviceType: String, timeoutMs: Long): Int? =
        AdbPairFlow.discover(this, serviceType, timeoutMs) { status -> log("  mDNS：$status") }

    // ======================================================================
    
    // ======================================================================

    private fun setBusy(b: Boolean) {
        busy = b
        runOnUiThread {
            btnStart.isEnabled = !b
            btnSearchPairPort.isEnabled = !b
            btnTestOnly.isEnabled = !b
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { tvStatus.text = "状态：$text" }
    }

    private var logCount = 0

    private fun log(msg: String) {
        val line = "[" + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "] " + msg
        logCount++
        CrashLogger.event("[无线配对] " + msg)
        runOnUiThread {
            tvLog.append(line + "\n")
            
            val text = tvLog.text.toString()
            if (logCount > 120) {
                val lines = text.split("\n")
                if (lines.size > 120) {
                    tvLog.text = lines.subList(lines.size - 120, lines.size).joinToString("\n")
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_LOCAL_NETWORK = 3001
    }
}
