// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/ui/theme/ThemePreferences.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：新增 ColorMode 枚举（BRAND / DYNAMIC）以及对应的 _colorMode 状态、
//         colorMode 取值器、setColorMode()、getColorModeDisplayNameRes()。
//   原因：本工程把内置管理器的默认配色改为「护盾品牌配色」，同时保留上游的
//         「跟随壁纸动态取色」能力，因此在设置页提供了一个二选一开关，
//         需要一个持久化偏好项来记住用户选择。
//   除新增部分外，本文件其余内容与上游一致。
// ==========================================================================

package roro.stellar.manager.ui.theme

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import roro.stellar.manager.R
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.StellarSettings.COLOR_MODE
import roro.stellar.manager.StellarSettings.THEME_MODE

enum class ThemeMode(val value: String) {
    LIGHT("light"),
    DARK("dark"),
    AUTO("auto");

    companion object {
        fun fromValue(value: String): ThemeMode = entries.find { it.value == value } ?: AUTO
    }
}

// ⚠️ 本工程新增：配色来源二选一。
//    BRAND   —— 使用 BrandColors.kt 定义的护盾品牌配色（默认）；
//    DYNAMIC —— 跟随壁纸动态取色（上游行为）。
enum class ColorMode(val value: String) {
    BRAND("brand"),
    DYNAMIC("dynamic");

    companion object {
        fun fromValue(value: String): ColorMode = entries.find { it.value == value } ?: BRAND
    }
}

enum class StartPage(val value: String) {
    HOME("home"),
    APPS("apps");

    // ⚠️ 本工程改动（第五轮）：删除上游的 TERMINAL("terminal") 项，
    //    因为「命令」页已从内置管理器移除（见 NavigationRoutes.kt 的修改声明）。
    //    老用户若曾把它设为默认启动页，fromValue() 会回落到 HOME。

    companion object {
        fun fromValue(value: String): StartPage = entries.find { it.value == value } ?: HOME
    }
}

object ThemePreferences {

    private var _themeMode: MutableState<ThemeMode>? = null
    private var _startPage: MutableState<StartPage>? = null
    // ⚠️ 本工程新增：配色来源偏好。
    private var _colorMode: MutableState<ColorMode>? = null

    val themeMode: MutableState<ThemeMode>
        get() {
            if (_themeMode == null) {
                // 2026-11-01：个性化入口已移除，主题固定为浅色。
                _themeMode = mutableStateOf(ThemeMode.LIGHT)
            }
            return _themeMode!!
        }

    // 2026-11-01：个性化入口已移除，配色固定为护盾品牌色。
    val colorMode: MutableState<ColorMode>
        get() {
            if (_colorMode == null) {
                _colorMode = mutableStateOf(ColorMode.BRAND)
            }
            return _colorMode!!
        }

    val startPage: MutableState<StartPage>
        get() {
            if (_startPage == null) {
                // 2026-11-01：个性化入口已移除，默认启动页固定为「启动」。
                _startPage = mutableStateOf(StartPage.HOME)
            }
            return _startPage!!
        }

    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        StellarSettings.getPreferences().edit {
            putString(THEME_MODE, mode.value)
        }
    }

    // ⚠️ 本工程新增。
    fun setColorMode(mode: ColorMode) {
        colorMode.value = mode
        StellarSettings.getPreferences().edit {
            putString(COLOR_MODE, mode.value)
        }
    }

    fun setStartPage(page: StartPage) {
        startPage.value = page
        StellarSettings.getPreferences().edit {
            putString(StellarSettings.START_PAGE, page.value)
        }
    }

    fun getThemeModeDisplayNameRes(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
        ThemeMode.AUTO -> R.string.theme_auto
    }

    // ⚠️ 本工程新增。
    fun getColorModeDisplayNameRes(mode: ColorMode): Int = when (mode) {
        ColorMode.BRAND -> R.string.color_mode_brand
        ColorMode.DYNAMIC -> R.string.color_mode_dynamic
    }

    // ⚠️ 本工程改动（第六轮）：删除 StartPage.TERMINAL 分支（命令页已移除）。
    fun getStartPageDisplayNameRes(page: StartPage): Int = when (page) {
        StartPage.HOME -> R.string.nav_home
        StartPage.APPS -> R.string.nav_apps
    }
}
