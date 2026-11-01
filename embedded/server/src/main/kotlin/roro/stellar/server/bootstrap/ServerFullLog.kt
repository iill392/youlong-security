package roro.stellar.server.bootstrap

import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2026-10-31（用户要求）：服务端侧完整启动日志，与 App/管理器写同一个文件。
 */
object ServerFullLog {
    private const val FILE = "/sdcard/Android/data/com.youlong.hd/files/full_startup.log"
    private const val MAX_BYTES = 4L * 1024L * 1024L
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    fun log(tag: String, message: String) {
        synchronized(lock) {
            try {
                val f = File(FILE)
                f.parentFile?.mkdirs()
                if (f.isFile && f.length() > MAX_BYTES) {
                    val old = File(FILE + ".1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                val thread = Thread.currentThread()
                f.appendText(
                    "[" + timeFormat.format(Date()) + "] [" + Process.myPid() + "/" +
                            thread.id + "/" + thread.name + "] [SERVER-" + tag + "] " + message + "\n"
                )
            } catch (_: Throwable) {
            }
        }
    }
}
