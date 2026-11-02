package com.youlong.hd

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Java 调用入口：在 ComposeView 中设置包含 WebView 的全屏主界面
 */
fun ComposeView.setMainContent(
    webView: WebView,
    onTabSelected: (Int) -> Unit = {}
) {
    setContent {
        MainScreen(webView = webView)
    }
}

/**
 * 主界面 - 使用 Compose 包装 WebView，并通过 LayerBackdrop 捕获 WebView 内容
 * 作为底部玻璃导航栏的毛玻璃背景
 *
 * @param webView 已配置好的 WebView 实例（由 MainActivity 创建并设置）
 * @param onTabSelected 底部导航栏 Tab 选中回调
 */
@Composable
fun MainScreen(webView: WebView) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
