// ==========================================================================
// 游龙安全护盾 —— 内置特权管理器主题色
// --------------------------------------------------------------------------
// 2026-11-01：整体改为接近 Android 原生 Material 3 的视觉。
// 不再使用深色战术控制台、霓虹薄荷、等宽大写标签和霓虹渐变。
// 功能代码不依赖这里的色值，只影响界面观感。
// ==========================================================================

package roro.stellar.manager.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 兼容旧代码保留的品牌色；界面已统一使用 MaterialTheme.colorScheme。 */
object BrandPalette {
    val Mint = Color(0xFF0B57D0)
    val Cyan = Color(0xFF00639B)
    val Amber = Color(0xFF7A5900)
    val Red = Color(0xFFBA1A1A)
    val Void = Color(0xFFF8FAFD)
    val Panel = Color(0xFFFFFFFF)
    val Edge = Color(0xFFC4C6D0)
}

/** 原生 Material 3 浅色主题。 */
val ShieldLightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),

    secondary = Color(0xFF00639B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2E7FF),
    onSecondaryContainer = Color(0xFF001D35),

    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261A00),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF8FAFD),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E3E8),
    onSurfaceVariant = Color(0xFF44474E),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F8),
    surfaceContainer = Color(0xFFECEEF3),
    surfaceContainerHigh = Color(0xFFE6E8ED),
    surfaceContainerHighest = Color(0xFFE1E3E8),

    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),

    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFA8C7FA),
    scrim = Color(0xFF000000)
)

/** 原生 Material 3 深色主题。 */
val ShieldDarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF002B5E),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),

    secondary = Color(0xFF7FCFFF),
    onSecondary = Color(0xFF003353),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFC2E7FF),

    tertiary = Color(0xFFF5BD48),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5D4200),
    onTertiaryContainer = Color(0xFFFFDEA6),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),

    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),

    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),

    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF0B57D0),
    scrim = Color(0xFF000000)
)
