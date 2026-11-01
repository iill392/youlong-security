package roro.stellar.manager.startup.command

import roro.stellar.manager.StellarSettings
import roro.stellar.manager.application
import java.io.File

object Starter {

    /** /data/local/tmp 对 adb shell 一定可写；外部存储 Android/data 在部分 ROM 上 shell 不可读。 */
    const val LAUNCHER_LOG = "/data/local/tmp/youlong_stellar_launcher.log"

    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libstellar.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "adb shell $userCommand"

    /**
     * 2026-10-31：与手动成功执行的命令保持一致。
     *
     * 不再传 --apk，不再套 setsid，也不再依赖 /storage/emulated/0 下的启动脚本
     * （adb shell 对 Android/data 的访问在部分 ROM 上被限制，脚本会静默打不开）。
     * 直接执行 nativeLibraryDir 里的 libstellar.so，由它自己从 /lib/ 反推 base.apk。
     */
    val internalCommand: String
        get() {
            val dropPrivileges = StellarSettings.getPreferences()
                .getBoolean(StellarSettings.DROP_PRIVILEGES, false)
            return if (dropPrivileges) {
                "${Chid.path} 2000 $userCommand"
            } else {
                userCommand
            }
        }

    val wirelessDebuggingCommand: String
        get() {
            if (!StellarSettings.getPreferences().getBoolean(StellarSettings.WIRELESS_DEBUGGING_SU, false)) {
                return internalCommand
            }
            val quotedCommand = internalCommand.replace("'", "'\\\"'\\\"'")
            return "su -c '$quotedCommand' || $internalCommand"
        }

    /**
     * 经 ADB 启动：直接后台执行 native starter，stdout/stderr 写入 /data/local/tmp。
     * ADB 侧随后会 cat 这个文件，把 launcher/libstellar 的原始错误写进完整日志。
     */
    val detachedWirelessDebuggingCommand: String
        get() {
            val log = LAUNCHER_LOG
            // 最接近手动成功命令：单条后台 setsid，不在中间插入复杂 if/echo。
            // 启动后让 adb shell 保持 3 秒，等 libstellar 完成 exec/初始化再断开；
            // 否则无线 ADB 下子进程可能在 exec 完成前被 shell 会话清理掉。
            return "nohup setsid $wirelessDebuggingCommand >>\"$log\" 2>&1 & sleep 3"
        }
}
