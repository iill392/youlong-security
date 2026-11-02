package com.youlong.hd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs

/**
 * Java调用入口：在ComposeView中设置玻璃导航栏（使用白色Canvas背景）
 */
fun ComposeView.setGlassBottomBar(onTabSelected: (Int) -> Unit = {}) {
    setContent {
        GlassBottomBar(onTabSelected = onTabSelected)
    }
}

/**
 * 玻璃底部导航栏 - 使用 LayerBackdrop 捕获 WebView 内容作为背景
 * @param backdrop 从外部传入的 LayerBackdrop，已捕获 WebView 等底层内容
 * @param modifier 外部修饰符，用于控制导航栏在父容器中的位置
 */
@Composable
fun GlassBottomBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onTabSelected: (Int) -> Unit = {}
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(120.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        LiquidBottomTabs(
            selectedTabIndex = { selectedIndex },
            onTabSelected = { index ->
                selectedIndex = index
                onTabSelected(index)
            },
            backdrop = backdrop,
            tabsCount = 3
        ) {
            GlassTab(
                icon = Icons.Filled.Home,
                label = "\u9996\u9875",
                isSelected = selectedIndex == 0,
                onClick = { selectedIndex = 0 }
            )
            GlassTab(
                icon = Icons.Filled.Favorite,
                label = "\u6536\u85CF",
                isSelected = selectedIndex == 1,
                onClick = { selectedIndex = 1 }
            )
            GlassTab(
                icon = Icons.Filled.Person,
                label = "\u6211\u7684",
                isSelected = selectedIndex == 2,
                onClick = { selectedIndex = 2 }
            )
        }
    }
}

/**
 * 玻璃底部导航栏 - 使用 Canvas 白色背景（原版，用于独立 ComposeView 场景）
 */
@Composable
private fun GlassBottomBar(
    onTabSelected: (Int) -> Unit = {}
) {
    val backdrop = com.kyant.backdrop.backdrops.rememberCanvasBackdrop {
        drawRect(Color.White)
    }
    GlassBottomBar(backdrop = backdrop, onTabSelected = onTabSelected)
}

@Composable
fun RowScope.GlassTab(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    LiquidBottomTab(onClick = onClick) {
        Spacer(Modifier.height(6.dp))
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = if (isSelected) Color(0xFF0088FF) else Color(0xFF666666)
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color(0xFF0088FF) else Color(0xFF666666)
        )
        Spacer(Modifier.height(2.dp))
    }
}
