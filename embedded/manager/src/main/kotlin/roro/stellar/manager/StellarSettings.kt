package roro.stellar.manager

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import roro.stellar.manager.util.EmptySharedPreferencesImpl
import roro.stellar.manager.util.PortBlacklistUtils

// ⚠️ 本工程修改声明（MPL-2.0 第 3.4 条）：
//   本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/StellarSettings.kt。
//   改动：新增常量 COLOR_MODE —— 记录内置管理器的配色来源偏好
//         （brand = 护盾品牌配色 / dynamic = 跟随壁纸）。
//   原因：本工程把内置管理器的默认配色改为护盾品牌配色，同时保留上游的
//         动态取色能力，需要在设置里二选一并持久化。
//   除这一行新增外，本文件其余内容与上游一致。
object StellarSettings {
    const val NAME = "settings"
    const val BOOT_MODE = "boot_mode"
    const val TCPIP_PORT = "tcpip_port"
    const val TCPIP_PORT_ENABLED = "tcpip_port_enabled"
    const val BOOT_BROADCAST_ACCESSIBILITY_ENABLED = "boot_broadcast_accessibility_enabled"
    const val THEME_MODE = "theme_mode"
    // ⚠️ 本工程新增（上游没有）：配色来源偏好。
    const val COLOR_MODE = "color_mode"
    const val START_PAGE = "start_page"
    const val DROP_PRIVILEGES = "drop_privileges"
    const val WIRELESS_DEBUGGING_SU = "wireless_debugging_su"
    const val SHIZUKU_COMPAT_ENABLED = "shizuku_compat_enabled"
    const val ACCESSIBILITY_AUTO_START_PROMPTED = "accessibility_auto_start_prompted"
    const val LAST_VERSION_CODE = "last_version_code"
    const val DAEMON_ENABLED = "daemon_enabled"
    const val HIDE_BACKGROUND = "hide_background"

    enum class BootMode { NONE, BROADCAST, TCPIP_PREWARM, SCRIPT }

    enum class LaunchMethod { UNKNOWN, ROOT, ADB }
    const val LAST_LAUNCH_METHOD = "last_launch_method"

    fun getLastLaunchMethod(): LaunchMethod {
        val name = getPreferences().getString(LAST_LAUNCH_METHOD, LaunchMethod.UNKNOWN.name)
            ?: LaunchMethod.UNKNOWN.name
        return runCatching { LaunchMethod.valueOf(name) }.getOrDefault(LaunchMethod.UNKNOWN)
    }

    fun setLastLaunchMethod(method: LaunchMethod) {
        getPreferences().edit().putString(LAST_LAUNCH_METHOD, method.name).apply()
    }

    fun getBootMode(): BootMode {
        val name = getPreferences().getString(BOOT_MODE, BootMode.NONE.name) ?: BootMode.NONE.name
        return runCatching { BootMode.valueOf(name) }.getOrDefault(BootMode.NONE)
    }

    fun setBootMode(mode: BootMode) {
        getPreferences().edit().putString(BOOT_MODE, mode.name).apply()
    }

    private var preferences: SharedPreferences? = null

    fun getPreferences(): SharedPreferences = preferences ?: EmptySharedPreferencesImpl()

    private fun getSettingsStorageContext(context: Context): Context {
        val storageContext = context.createDeviceProtectedStorageContext()
        return object : ContextWrapper(storageContext) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return try {
                    super.getSharedPreferences(name, mode)
                } catch (_: IllegalStateException) {
                    EmptySharedPreferencesImpl()
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (preferences == null) {
            preferences = getSettingsStorageContext(context)
                .getSharedPreferences(NAME, Context.MODE_PRIVATE)

            preferences?.let { prefs ->
                if (prefs.getString(BOOT_MODE, null) == "ACCESSIBILITY") {
                    prefs.edit()
                        .putString(BOOT_MODE, BootMode.BROADCAST.name)
                        .putBoolean(BOOT_BROADCAST_ACCESSIBILITY_ENABLED, true)
                        .apply()
                }
                if (!prefs.contains(TCPIP_PORT_ENABLED)) {
                    prefs.edit().putBoolean(TCPIP_PORT_ENABLED, true).apply()
                }
                if (!prefs.contains(TCPIP_PORT)) {
                    var randomPort = PortBlacklistUtils.generateSafeRandomPort(1000, 9999, 100)
                    if (randomPort == -1) {
                        randomPort = 8765
                    }
                    prefs.edit().putString(TCPIP_PORT, randomPort.toString()).apply()
                }
            }
        }
    }
}
