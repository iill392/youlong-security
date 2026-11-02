package roro.stellar.manager.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import roro.stellar.manager.ui.components.G2RoundedCorners.g2

/**
 * 2026-10-17 第 18 轮：控制台形状。
 *
 * 把原来的 24/28dp 大圆角压到 8~22dp，整体更接近仪表盘/终端面板；
 * 与等宽字体、深色玻璃背景一起构成全新视觉。
 */
@Immutable
data class AppShapes(
    val iconMedium18: ContinuousRoundedRectangle = ContinuousRoundedRectangle(12.dp, continuity = g2),
    val iconSmall: ContinuousRoundedRectangle = ContinuousRoundedRectangle(8.dp, continuity = g2),
    val cardLarge: ContinuousRoundedRectangle = ContinuousRoundedRectangle(18.dp, continuity = g2),
    val cardMedium24: ContinuousRoundedRectangle = ContinuousRoundedRectangle(18.dp, continuity = g2),
    val cardMedium: ContinuousRoundedRectangle = ContinuousRoundedRectangle(12.dp, continuity = g2),
    val buttonMedium: ContinuousRoundedRectangle = ContinuousRoundedRectangle(12.dp, continuity = g2),
    val buttonSmall14: ContinuousRoundedRectangle = ContinuousRoundedRectangle(10.dp, continuity = g2),
    val inputField: ContinuousRoundedRectangle = ContinuousRoundedRectangle(12.dp, continuity = g2),
    val dialog: ContinuousRoundedRectangle = ContinuousRoundedRectangle(22.dp, continuity = g2),
    val dialogContent: ContinuousRoundedRectangle = ContinuousRoundedRectangle(12.dp, continuity = g2),
    val tag: ContinuousRoundedRectangle = ContinuousRoundedRectangle(4.dp, continuity = g2),
    val fab: ContinuousRoundedRectangle = ContinuousRoundedRectangle(14.dp, continuity = g2),
)

object AppShape {
    val shapes = AppShapes()
}
