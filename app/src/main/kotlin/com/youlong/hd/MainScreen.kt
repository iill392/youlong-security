package com.youlong.hd

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView


fun ComposeView.setMainContent(
    webView: WebView,
    onTabSelected: (Int) -> Unit = {}
) {
    setContent {
        MainScreen(webView = webView, onTabSelected = onTabSelected)
    }
}


@Composable
fun MainScreen(webView: WebView, onTabSelected: (Int) -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
        // 接通底部玻璃导航：点击回调此前被丢弃（2026-10 审查修复）
        val backdrop = com.kyant.backdrop.backdrops.rememberCanvasBackdrop {
            drawRect(androidx.compose.ui.graphics.Color.White)
        }
        GlassBottomBar(backdrop = backdrop, onTabSelected = onTabSelected)
    }
}
