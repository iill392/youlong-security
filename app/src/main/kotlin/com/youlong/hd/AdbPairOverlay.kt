package com.youlong.hd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.manager.adb.AdbMdns


class AdbPairOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
    private val onFinished: (Boolean) -> Unit
) {

    
    private val appContext: Context = context.applicationContext

    private val wm: WindowManager? =
        appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var root: LinearLayout? = null
    private var etPort: EditText? = null
    private var etCode: EditText? = null
    private var tvStatus: TextView? = null
    private var btnStart: Button? = null
    private var searchJob: Job? = null
    private var running = false

    val isShowing: Boolean get() = root != null

    @SuppressLint("SetTextI18n")
    fun show() {
        if (isShowing) return
        if (wm == null) {
            onLog("悬浮窗不可用（WindowManager 为空）")
            return
        }

        val pad = (appContext.resources.displayMetrics.density * 16).toInt()

        val card = LinearLayout(appContext)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(Color.WHITE)
        card.setPadding(pad, pad, pad, pad)

        val title = TextView(appContext)
        title.text = "无线调试配对"
        title.textSize = 17f
        title.setTextColor(Color.parseColor("#1C1C1E"))
        card.addView(title)

        val hintView = TextView(appContext)
        hintView.text = "在系统弹窗里看 6 位配对码，填到下面即可（端口会自动填）"
        hintView.textSize = 12f
        hintView.setTextColor(Color.parseColor("#8E8E93"))
        hintView.setPadding(0, pad / 4, 0, pad / 2)
        card.addView(hintView)

        etPort = EditText(appContext).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "配对端口"
            textSize = 15f
        }
        card.addView(etPort)

        etCode = EditText(appContext).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 位配对码"
            textSize = 18f
        }
        card.addView(etCode)

        val row = LinearLayout(appContext)
        row.orientation = LinearLayout.HORIZONTAL
        card.addView(row)

        btnStart = Button(appContext).apply {
            text = "配对并启动"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0A84FF"))
            setOnClickListener { startPair() }
        }
        row.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))

        val btnClose = Button(appContext).apply {
            text = "关闭"
            textSize = 15f
            setOnClickListener { hide(); onFinished(false) }
        }
        row.addView(btnClose, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        tvStatus = TextView(appContext).apply {
            text = "状态：正在搜索配对端口…（请先打开系统的「使用配对码配对设备」）"
            textSize = 12f
            setTextColor(Color.parseColor("#3A3A3C"))
            typeface = Typeface.MONOSPACE
            setPadding(0, pad / 2, 0, 0)
        }
        card.addView(tvStatus)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        lp.y = (appContext.resources.displayMetrics.density * 90).toInt()

        try {
            wm.addView(card, lp)
            root = card
            onLog("悬浮窗已显示（浮在系统配对弹窗之上）")
        } catch (t: Throwable) {
            onLog("悬浮窗添加失败：" + (t.message ?: t.javaClass.simpleName))
            root = null
            return
        }

        startPortSearch()
    }

    fun hide() {
        searchJob?.cancel()
        searchJob = null
        val v = root ?: return
        root = null
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    
    private fun startPortSearch() {
        searchJob?.cancel()
        searchJob = scope.launch {
            var tries = 0
            while (isShowing && !running && tries < 40) {
                tries++
                val port = AdbPairFlow.discover(appContext, AdbMdns.TLS_PAIRING, 2500L)
                if (port != null && port > 0) {
                    withContext(Dispatchers.Main) {
                        etPort?.setText(port.toString())
                        setStatus("已找到配对端口 $port：请填 6 位配对码后点「配对并启动」")
                    }
                    onLog("自动搜到配对端口：$port")
                    return@launch
                }
                delay(500L)
            }
            withContext(Dispatchers.Main) {
                setStatus("没搜到配对端口：请确认系统弹窗正开着，或手动填写弹窗里的端口")
            }
        }
    }

    private fun startPair() {
        if (running) return
        val port = etPort?.text?.toString()?.trim()?.toIntOrNull()
        val code = etCode?.text?.toString()?.trim().orEmpty()
        if (port == null || port !in 1..65535) {
            setStatus("配对端口无效：请填系统弹窗里的端口")
            return
        }
        if (!code.matches(Regex("\\d{6}"))) {
            setStatus("配对码无效：应为恰好 6 位数字")
            return
        }

        running = true
        searchJob?.cancel()
        btnStart?.isEnabled = false
        scope.launch {
            var ok = false
            try {
                setStatus("正在配对…（请保持系统弹窗开着）")
                onLog("开始配对：127.0.0.1:$port")
                val paired = withContext(Dispatchers.IO) { AdbPairFlow.pair(port, code) }
                if (!paired) {
                    setStatus("配对失败：核对配对码/端口，且弹窗必须一直开着")
                    onLog("配对失败（SPAKE2 未通过）")
                    return@launch
                }
                onLog("配对成功，本应用 ADB 公钥已写入系统")
                CrashLogger.event("[无线配对] 配对成功")

                setStatus("正在搜索连接端口…")
                var connectPort = AdbPairFlow.discover(appContext, AdbMdns.TLS_CONNECT, 10000L)
                if (connectPort == null || connectPort <= 0) {
                    val sysPort = AdbPairFlow.systemPort()
                    if (sysPort in 1..65535) {
                        connectPort = sysPort
                        onLog("mDNS 未搜到连接端口，改用系统端口 $sysPort")
                    }
                }
                if (connectPort == null || connectPort <= 0) {
                    setStatus("配对成功，但没找到连接端口：请重试或改用页面里的手动方式")
                    return@launch
                }
                onLog("连接端口：$connectPort")

                try {
                    withContext(Dispatchers.IO) { AdbPairFlow.grantSecureSettings(connectPort, appContext.packageName) { } }
                } catch (t: Throwable) {
                    onLog("授权 WRITE_SECURE_SETTINGS 失败（不影响启动）：" + t.message)
                }

                
                
                setStatus("正在通过 ADB 启动特权服务…")
                val startJob = launch { runCatching { AdbPairFlow.startService(connectPort) } }

                setStatus("正在等待特权服务就绪…")
                ok = AdbPairFlow.waitBinder(30000L)
                if (!ok) startJob.cancel()
                if (ok) {
                    setStatus("特权服务已启动 ✔")
                    onLog("特权 Binder 已就绪：uid 2000（shell）特权服务可用")
                    CrashLogger.event("[无线配对] 已通过 ADB 启动特权服务（端口=$connectPort）")
                    delay(2500L)
                    withContext(Dispatchers.Main) { hide() }
                } else {
                    setStatus("启动命令已执行，但 30 秒内没等到服务：可回特权面板点「重新连接」")
                    onLog("等待特权 Binder 超时")
                }
            } catch (t: Throwable) {
                setStatus("出错：" + (t.message ?: t.javaClass.simpleName))
                onLog("异常：" + t)
                CrashLogger.event("[无线配对] 悬浮窗流程异常", t)
            } finally {
                running = false
                withContext(Dispatchers.Main) { btnStart?.isEnabled = true }
                onFinished(ok)
            }
        }
    }

    private fun setStatus(text: String) {
        tvStatus?.text = "状态：$text"
    }
}
