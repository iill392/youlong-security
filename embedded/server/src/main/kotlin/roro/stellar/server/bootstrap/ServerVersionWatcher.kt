package roro.stellar.server.bootstrap

import android.os.Build
import roro.stellar.server.ServerConstants.MANAGER_APPLICATION_ID
import roro.stellar.server.util.Logger
import roro.stellar.server.util.PackageManagerCompat
import java.io.File

/**
 * 2026-10-30：服务端自身的 APK 版本监视器。
 *
 * App 侧 ServerRestartManager 能处理“更新后 App 被启动/收到 MY_PACKAGE_REPLACED”
 * 的场景；但如果 ROM 不派发更新广播、App 也没被拉起，旧服务端会一直跑旧代码。
 *
 * 这里由服务端自己周期检查当前安装的 App versionCode。发现版本变了，就结束
 * 旧 stellar_server，并用新 APK 的 libstellar.so 拉起新服务端。
 *
 * 该逻辑只在服务端进程内运行，不需要 App 界面/Binder 回调。
 */
object ServerVersionWatcher {
    private val LOGGER = Logger("ServerVersionWatcher")
    private const val INTERVAL_MS = 5_000L

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        Thread({
            val initialVersion = installedVersionCode()
            LOGGER.i("服务端版本监视启动，初始 versionCode=%d", initialVersion)
            while (true) {
                sleepQuietly(INTERVAL_MS)
                val current = installedVersionCode()
                if (current <= 0L) continue
                if (initialVersion > 0L && current != initialVersion) {
                    LOGGER.i("检测到 APK 版本变化: %d -> %d，自动重启服务端", initialVersion, current)
                    ServerEventLog.log("检测到 APK 版本变化 ${initialVersion} -> ${current}，准备自动重启服务端")
                    if (restartWithInstalledApk()) {
                        // 新服务端会重新开始监视；旧线程结束即可。
                        break
                    }
                }
            }
        }, "Server-Version-Watcher").apply { isDaemon = true }.start()
    }

    private fun installedVersionCode(): Long {
        return try {
            val pi = PackageManagerCompat.getPackageInfo(MANAGER_APPLICATION_ID, 0, 0) ?: return -1L
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun restartWithInstalledApk(): Boolean {
        return try {
            val pi = PackageManagerCompat.getPackageInfo(MANAGER_APPLICATION_ID, 0, 0)
            val ai = pi?.applicationInfo
            if (ai == null) {
                LOGGER.w("拿不到应用 ApplicationInfo，无法自动重启")
                return false
            }
            val apk = ai.sourceDir
            val lib = File(ai.nativeLibraryDir, "libstellar.so").absolutePath
            if (!File(lib).isFile) {
                LOGGER.w("libstellar.so 不存在: %s", lib)
                return false
            }
            val cmd = "OLD=\$(pidof stellar_server 2>/dev/null); " +
                    "if [ -n \"\$OLD\" ]; then kill -9 \$OLD 2>/dev/null; fi; " +
                    "sleep 1; nohup setsid \"$lib\" --apk=\"$apk\" >/dev/null 2>&1 &"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            LOGGER.i("已发出服务端自动重启命令: %s", apk)
            ServerEventLog.log("已发出服务端自动重启命令，新 APK=$apk")
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "服务端自动重启失败")
            false
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
