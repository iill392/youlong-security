package roro.stellar.manager.diagnostics

import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2026-10-31（用户要求）：完整启动/配对诊断日志。
 *
 * 文件：/sdcard/Android/data/com.youlong.hd/files/full_startup.log
 * 内容：配对、端口发现、端口切换、ADB 命令、Binder 等待、服务端启动、重试等全过程。
 *
 * 注意：这是用户主动要求新增的诊断日志，不是之前被移除的普通日志入口。
 * 用户可在 App「高级设置 -> 完整日志」中一键复制发送。
 */
object FullDiagnostics {
    const val FILE_PATH = "/sdcard/Android/data/com.youlong.hd/files/full_startup.log"

    private const val MAX_BYTES = 4L * 1024L * 1024L
    private const val TAG = "FullDiag"
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @JvmStatic
    fun log(tag: String, message: String) {
        synchronized(lock) {
            try {
                val f = File(FILE_PATH)
                f.parentFile?.mkdirs()
                if (f.isFile && f.length() > MAX_BYTES) {
                    val old = File(FILE_PATH + ".1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                val thread = Thread.currentThread()
                val line = "[" + timeFormat.format(Date()) + "] [" +
                        Process.myPid() + "/" + thread.id + "/" + thread.name + "] [" +
                        tag + "] " + message + "\n"
                f.appendText(line)
            } catch (t: Throwable) {
                Log.w(TAG, "写完整日志失败", t)
            }
        }
    }

    @JvmStatic
    fun read(): String {
        return try {
            val f = File(FILE_PATH)
            if (!f.isFile) "完整日志文件不存在：$FILE_PATH"
            else f.readText()
        } catch (t: Throwable) {
            "读取完整日志失败：$t"
        }
    }

    @JvmStatic
    fun clear() {
        try {
            File(FILE_PATH).delete()
        } catch (_: Throwable) {
        }
    }
}
