package roro.stellar.server.bootstrap

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2026-10-30：服务端事件日志。
 *
 * 与 App 的 RuntimeEventLog 写同一个外部文件，方便用户在 App 内统一查看：
 *   /sdcard/Android/data/com.youlong.hd/files/runtime_events.log
 *
 * 只记录服务端启动/版本变化/自动重启等关键事件，不记录每条 shell 命令。
 */
object ServerEventLog {
    private const val FILE = "/sdcard/Android/data/com.youlong.hd/files/runtime_events.log"
    private const val MAX_BYTES = 512L * 1024L
    private const val TAG = "SERVER"

    private val lock = Any()

    fun log(message: String) {
        synchronized(lock) {
            try {
                val f = File(FILE)
                f.parentFile?.mkdirs()
                if (f.isFile && f.length() > MAX_BYTES) {
                    val old = File(FILE + ".1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                val line = "[" + SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                    .format(Date()) + "] [" + TAG + "] " + message
                f.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }
}
