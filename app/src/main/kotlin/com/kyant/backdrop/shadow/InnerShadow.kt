package com.kyant.backdrop.shadow

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Immutable
data class InnerShadow(
    val radius: Dp = 24f.dp,
    val offset: DpOffset = DpOffset(0f.dp, radius),
    val color: Color = Color.Black.copy(alpha = 0.15f),
    @param:FloatRange(from = 0.0, to = 1.0) val alpha: Float = 1f,
    val blendMode: BlendMode = DrawScope.DefaultBlendMode
) {

    companion object {

        @Stable
        val Default: InnerShadow = InnerShadow()
    }
}

@Stable
fun lerp(start: InnerShadow, stop: InnerShadow, fraction: Float): InnerShadow {
    return InnerShadow(
        radius = androidx.compose.ui.unit.lerp(start.radius, stop.radius, fraction),
        offset = androidx.compose.ui.unit.lerp(start.offset, stop.offset, fraction),
        color = androidx.compose.ui.graphics.lerp(start.color, stop.color, fraction),
        alpha = androidx.compose.ui.util.lerp(start.alpha, stop.alpha, fraction),
        blendMode = if (fraction < 0.5f) start.blendMode else stop.blendMode
    )
}
