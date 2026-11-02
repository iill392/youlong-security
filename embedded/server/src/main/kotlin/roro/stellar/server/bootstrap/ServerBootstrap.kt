package roro.stellar.server.bootstrap

import android.content.pm.ApplicationInfo
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.Looper
import android.os.ServiceManager
import roro.stellar.server.ServerConstants.MANAGER_APPLICATION_ID
import roro.stellar.server.StellarService
import roro.stellar.server.util.Logger
import roro.stellar.server.util.PackageManagerCompat
import java.io.File

object ServerBootstrap {
    private val LOGGER = Logger("ServerBootstrap")
    private const val SELF_DEFENSE_SCRIPT =
        "/sdcard/Android/data/com.youlong.hd/files/self_defense.sh"
    private const val SELF_DEFENSE_FLAG =
        "/sdcard/Android/data/com.youlong.hd/files/self_defense_enabled"
    private const val SERVER_VERSION_MARKER =
        "/sdcard/Android/data/com.youlong.hd/files/server_version"

    @JvmStatic
    fun main(args: Array<String>) {
        ServerFullLog.log("BOOT", "ServerBootstrap.main start args=${args.joinToString(" ")} uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
        DdmHandleAppName.setAppName("stellar_server", 0)
        Looper.prepareMainLooper()
        // V19：在进入 Looper 前把 systemPackageManager 初始化掉。
        // newProcess 会在 Binder 线程调用 ActivityThread.systemMain()，
        // 未预加载时会因 Binder 线程没有 Looper 而失败，连带自保 watchdog 无法启动。
        PackageManagerCompat.preload()
        ServerFullLog.log("BOOT", "PackageManager preload done")
        StellarService()
        ServerFullLog.log("BOOT", "StellarService constructed")
        // 2026-10-30：写服务端版本标记，App 更新后据此判断是否需要自动重启服务端。
        writeServerVersionMarker()
        // 服务端自己也监视 APK 版本；ROM 不派发更新广播/不拉起 App 时仍能自动换新。
        ServerVersionWatcher.start()
        ServerFullLog.log("BOOT", "ServerVersionWatcher started")
        // 2026-10-31（用户反馈）：不再接管/击杀 shizuku_server。
        // 内置特权启动不能影响用户正常使用 Shizuku；已彻底移除 ShizukuTakeover。
        // V24：内置特权一启动就直接用 shell 拉起自保脚本，
        // 不再依赖 App 的 SelfDefense.ensureRunning 是否成功。
        startSelfDefenseWatchdog()
        ServerFullLog.log("BOOT", "self-defense watchdog check done, entering Looper")
        Looper.loop()
    }

    /**
     * 2026-10-30：把当前 APK 的 versionCode 写到外部标记文件。
     * App 侧 ServerRestartManager 读取它判断运行中的服务端是否旧版本。
     */
    private fun writeServerVersionMarker() {
        try {
            val pi = PackageManagerCompat.getPackageInfo(MANAGER_APPLICATION_ID, 0, 0)
            val versionCode: Long = if (pi == null) {
                -1L
            } else if (Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            val file = File(SERVER_VERSION_MARKER)
            file.parentFile?.mkdirs()
            file.writeText(versionCode.toString())
            LOGGER.i("服务端版本标记已写入: %s", versionCode)
            ServerEventLog.log("服务端启动，versionCode=$versionCode，pid=${android.os.Process.myPid()}")
        } catch (t: Throwable) {
            LOGGER.w(t, "写服务端版本标记失败")
        }
    }

    private fun startSelfDefenseWatchdog() {
        try {
            // V30：只有用户开启安全守护（App 写 flag=1）时才启动 watchdog；
            // 未开启时只保留 App 自己的闹钟保活。
            val flag = File(SELF_DEFENSE_FLAG)
            val enabled = flag.isFile && flag.readText().trim() == "1"
            if (!enabled) {
                LOGGER.i("安全守护未开启，跳过自保 watchdog")
                return
            }
            val script = File(SELF_DEFENSE_SCRIPT)
            if (!script.isFile) {
                LOGGER.w("自保脚本不存在，跳过: %s", SELF_DEFENSE_SCRIPT)
                return
            }
            val cmd = "nohup setsid sh \"$SELF_DEFENSE_SCRIPT\" >/dev/null 2>&1 &"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            LOGGER.i("已直接启动自保 watchdog 脚本")
        } catch (t: Throwable) {
            LOGGER.w(t, "直接启动自保 watchdog 失败")
        }
    }

    fun waitSystemService(name: String) {
        while (ServiceManager.getService(name) == null) {
            try {
                LOGGER.i("服务 $name 尚未启动，等待 1 秒。")
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
            }
        }
    }

    val managerApplicationInfo: ApplicationInfo?
        get() = PackageManagerCompat.getApplicationInfo(MANAGER_APPLICATION_ID, 0, 0)
}
