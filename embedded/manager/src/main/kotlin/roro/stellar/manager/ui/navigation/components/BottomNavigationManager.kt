// ==========================================================================
// 内置特权管理器底部导航 / 侧边导航
// 2026-11-01：改为 Material 3 原生 NavigationBar / NavigationRail。
// 保留原有选中索引与点击回调，仅替换视觉实现。
// ==========================================================================

package roro.stellar.manager.ui.navigation.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import roro.stellar.manager.ui.navigation.routes.MainScreen

@Composable
fun StandardBottomNavigation(
    selectedIndex: Int,
    onItemClick: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        MainScreen.entries.forEachIndexed { index, screen ->
            val selected = selectedIndex == index
            NavigationBarItem(
                selected = selected,
                onClick = { onItemClick(index) },
                icon = {
                    Icon(
                        imageVector = if (selected) screen.iconFilled else screen.icon,
                        contentDescription = stringResource(screen.labelRes)
                    )
                },
                label = {
                    Text(
                        text = stringResource(screen.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
fun StandardNavigationRail(
    selectedIndex: Int,
    onItemClick: (Int) -> Unit
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        header = {}
    ) {
        MainScreen.entries.forEachIndexed { index, screen ->
            val selected = selectedIndex == index
            NavigationRailItem(
                selected = selected,
                onClick = { onItemClick(index) },
                icon = {
                    Icon(
                        imageVector = if (selected) screen.iconFilled else screen.icon,
                        contentDescription = stringResource(screen.labelRes)
                    )
                },
                label = { Text(stringResource(screen.labelRes), maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
