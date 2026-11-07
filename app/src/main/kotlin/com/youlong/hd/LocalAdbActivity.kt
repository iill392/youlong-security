package com.youlong.hd

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.Stellar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 二改版新增：本地 ADB 终端。
 *
 * 无需电脑，直接在手机上执行特权 shell 命令（pm / am / dumpsys 等），
 * 后端走 Stellar 特权服务（shell uid），与无线调试配对共用同一套提权通道。
 *
 * 界面为代码构建（无 XML），风格与 AdbPairActivity 保持一致。
 */
class LocalAdbActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scroll: ScrollView

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        appendOutput("本地 ADB 就绪。直接输入 shell 命令执行（如 pm list packages）。\n")
        if (Stellar.getService() == null) {
            appendOutput("提示：特权服务未连接，请先完成配对/启动特权服务。\n")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF1C1C1E.toInt())
        root.setPadding(dp(16), dp(16), dp(16), dp(16))

        val title = TextView(this)
        title.text = "本地 ADB"
        title.textSize = 22f
        title.setTextColor(0xFFFFFFFF.toInt())
        root.addView(title)

        val sub = TextView(this)
        sub.text = "免电脑，特权 shell 直连本机"
        sub.textSize = 13f
        sub.setTextColor(0xFF8E8E93.toInt())
        root.addView(sub)

        scroll = ScrollView(this)
        val scrollParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        scrollParams.topMargin = dp(12)
        root.addView(scroll, scrollParams)

        tvOutput = TextView(this)
        tvOutput.setTextColor(0xFF00E676.toInt())
        tvOutput.textSize = 13f
        tvOutput.typeface = android.graphics.Typeface.MONOSPACE
        tvOutput.setTextIsSelectable(true)
        scroll.addView(tvOutput)

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowParams.topMargin = dp(12)
        root.addView(inputRow, rowParams)

        etInput = EditText(this)
        etInput.hint = "输入命令…"
        etInput.setHintTextColor(0xFF636366.toInt())
        etInput.setTextColor(0xFFFFFFFF.toInt())
        etInput.inputType = InputType.TYPE_CLASS_TEXT
        etInput.setSingleLine(true)
        val inputParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        inputRow.addView(etInput, inputParams)

        val btnRun = Button(this)
        btnRun.text = "执行"
        val btnParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        btnParams.leftMargin = dp(8)
        inputRow.addView(btnRun, btnParams)
        btnRun.setOnClickListener { runCommand() }

        etInput.setOnEditorActionListener { _, _, _ ->
            runCommand()
            true
        }

        return root
    }

    private fun runCommand() {
        val cmd = etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        etInput.text.clear()
        appendOutput("$ " + cmd + "\n")

        if (Stellar.getService() == null) {
            appendOutput("错误：特权服务未连接。\n")
            return
        }

        scope.launch {
            try {
                val proc = Stellar.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                val code = proc.waitFor()
                withContext(Dispatchers.Main) {
                    if (out.isNotBlank()) appendOutput(out + (if (out.endsWith("\n")) "" else "\n"))
                    if (err.isNotBlank()) appendOutput("[stderr] " + err + (if (err.endsWith("\n")) "" else "\n"))
                    if (code != 0) appendOutput("[exit $code]\n")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("执行失败：" + (e.message ?: e.javaClass.simpleName) + "\n")
                }
            }
        }
    }

    private fun appendOutput(text: String) {
        if (::tvOutput.isInitialized) {
            tvOutput.append(text)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
