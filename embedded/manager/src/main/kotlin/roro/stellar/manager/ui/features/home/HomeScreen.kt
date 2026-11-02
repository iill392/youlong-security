package roro.stellar.manager.ui.features.home

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roro.stellar.Stellar
import roro.stellar.manager.R
import roro.stellar.manager.compat.BuildUtils.atLeast30
import roro.stellar.manager.compat.ClipboardUtils
import roro.stellar.manager.startup.command.Starter
import roro.stellar.manager.ui.components.BrandLargeTopAppBar
import roro.stellar.manager.ui.components.StellarDialog
import roro.stellar.manager.ui.navigation.components.createTopAppBarScrollBehavior
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.util.EnvironmentUtils
import roro.stellar.manager.util.UserHandleCompat

/**
 * 2026-10-17 第 18 轮：首页整体重写为「特权控制台」。
 *
 * 结构上不再是「顶部大标题 + 一排 Material 卡片」，而是：
 *   ① 渐变状态舱（ONLINE / OFFLINE、版本、运行方式、启动/停止）；
 *   ② 「启动方式」控制台列表（Root / 无线调试 / 有线 ADB）；
 *   ③ ADB 受限特性提示条。
 * 所有原有功能（启动、停止、重启、复制 ADB 命令、受限特性弹窗）保持不变。
 */
@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    topAppBarState: TopAppBarState,
    homeViewModel: HomeViewModel,
    onNavigateToStarter: (isRoot: Boolean, host: String?, port: Int, hasSecureSettings: Boolean) -> Unit = { _, _, _, _ -> }
) {
    val scrollBehavior = createTopAppBarScrollBehavior(topAppBarState)
    val context = LocalContext.current
    val serviceStatusResource by homeViewModel.serviceStatus.observeAsState()

    val serviceStatus = serviceStatusResource?.data

    val isRunning = serviceStatus?.isRunning ?: false
    val isRoot = serviceStatus?.uid == 0
    val isPrimaryUser = UserHandleCompat.myUserId() == 0
    val hasRoot = EnvironmentUtils.isRooted()
    val apiVersion = serviceStatus?.apiVersion ?: 0
    val apiText = if (isRunning) {
        "${apiVersion / 100}.${(apiVersion % 100) / 10}.${apiVersion % 10}"
    } else {
        "—"
    }

    var showPowerDialog by remember { mutableStateOf(false) }
    var showAdbCommandDialog by remember { mutableStateOf(false) }
    var showAdbRestrictedFeaturesDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            BrandLargeTopAppBar(
                title = "内置特权",
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ConsoleHero(
                isRunning = isRunning,
                isRoot = isRoot,
                apiText = apiText,
                statusText = if (isRunning) {
                    "ADB 特权服务已连接"
                } else {
                    "ADB 特权服务未启动"
                },
                onPrimaryClick = {
                    if (isRunning) {
                        showPowerDialog = true
                    } else if (atLeast30 || EnvironmentUtils.getAdbTcpPort() > 0) {
                        onNavigateToStarter(false, "127.0.0.1", 0, false)
                    } else {
                        showAdbCommandDialog = true
                    }
                },
                onRestartClick = { homeViewModel.restartService() }
            )

            if (isRunning && serviceStatus?.permission == false) {
                ConsoleNotice(
                    title = stringResource(R.string.adb_restricted_hint_title),
                    message = stringResource(R.string.adb_restricted_hint_message),
                    actionText = stringResource(R.string.view),
                    onAction = { showAdbRestrictedFeaturesDialog = true }
                )
            }

            ConsoleSectionLabel("启动方式")

            if (isPrimaryUser) {
                if (hasRoot) {
                    ConsoleActionRow(
                        icon = Icons.Default.Tag,
                        title = if (isRunning) stringResource(R.string.root_restart) else stringResource(R.string.root_start),
                        subtitle = stringResource(R.string.root_start_subtitle),
                        accent = MaterialTheme.colorScheme.tertiary,
                        onClick = { onNavigateToStarter(true, null, 0, false) }
                    )
                }

                if (atLeast30 || EnvironmentUtils.getAdbTcpPort() > 0) {
                    ConsoleActionRow(
                        icon = Icons.Default.Wifi,
                        title = stringResource(R.string.wireless_debugging),
                        subtitle = stringResource(R.string.wireless_debugging_subtitle),
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onNavigateToStarter(false, "127.0.0.1", 0, false) }
                    )
                }

                ConsoleActionRow(
                    icon = Icons.Default.Cable,
                    title = stringResource(R.string.wired_adb),
                    subtitle = stringResource(R.string.wired_adb_subtitle),
                    accent = MaterialTheme.colorScheme.secondary,
                    onClick = { showAdbCommandDialog = true }
                )

                if (!hasRoot) {
                    ConsoleActionRow(
                        icon = Icons.Default.Tag,
                        title = stringResource(R.string.root_start),
                        subtitle = "当前设备未检测到 Root 权限",
                        accent = MaterialTheme.colorScheme.outline,
                        onClick = {
                            Toast.makeText(
                                context,
                                context.getString(R.string.no_root_permission),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            } else {
                Text(
                    text = "仅主用户可启动特权服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showPowerDialog) {
        StellarDialog(
            onDismissRequest = { showPowerDialog = false },
            title = stringResource(R.string.stop_service),
            confirmText = stringResource(R.string.stop),
            dismissText = stringResource(R.string.restart),
            onConfirm = {
                if (Stellar.pingBinder()) {
                    try { Stellar.exit() } catch (_: Throwable) {}
                }
                showPowerDialog = false
            },
            onDismiss = {
                homeViewModel.restartService()
                showPowerDialog = false
            }
        ) {
            Text(
                text = stringResource(R.string.stop_service_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAdbCommandDialog) {
        StellarDialog(
            onDismissRequest = { showAdbCommandDialog = false },
            title = stringResource(R.string.view_command),
            confirmText = stringResource(R.string.copy),
            dismissText = stringResource(R.string.close),
            onConfirm = {
                ClipboardUtils.put(context, Starter.adbCommand)
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAdbCommandDialog = false }
        ) {
            Text(
                text = Starter.adbCommand,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAdbRestrictedFeaturesDialog) {
        StellarDialog(
            onDismissRequest = { showAdbRestrictedFeaturesDialog = false },
            title = stringResource(R.string.adb_restricted_features_title),
            confirmText = stringResource(R.string.close),
            onConfirm = { showAdbRestrictedFeaturesDialog = false },
            showDismissButton = false
        ) {
            RestrictedFeatureList(
                features = serviceStatus?.featureStates ?: emptyList()
            )
        }
    }
}

// ==========================================================================
// 控制台组件（第 18 轮新增）
// ==========================================================================

@Composable
private fun ConsoleHero(
    isRunning: Boolean,
    isRoot: Boolean,
    apiText: String,
    statusText: String,
    onPrimaryClick: () -> Unit,
    onRestartClick: () -> Unit
) {
    val accent = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.shapes.cardLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = AppShape.shapes.cardLarge
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(AppShape.shapes.iconSmall)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) "服务运行中" else "服务未运行",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            StatusPill(
                text = if (isRunning) "已连接" else "未连接",
                color = accent
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(label = "核心版本", value = apiText, modifier = Modifier.weight(1f))
            MetricTile(
                label = "运行方式",
                value = if (isRoot) "Root" else "ADB",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPrimaryClick,
                modifier = Modifier.weight(1f),
                shape = AppShape.shapes.buttonMedium,
                colors = if (isRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.PowerSettingsNew else Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) {
                        stringResource(R.string.stop_service)
                    } else {
                        "启动特权服务"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (isRunning) {
                OutlinedButton(
                    onClick = onRestartClick,
                    modifier = Modifier.weight(1f),
                    shape = AppShape.shapes.buttonMedium
                ) {
                    Text(
                        text = stringResource(R.string.restart),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(AppShape.shapes.cardMedium)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = AppShape.shapes.cardMedium
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(AppShape.shapes.tag)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), AppShape.shapes.tag)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(AppShape.shapes.tag)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun ConsoleSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
    )
}

@Composable
private fun ConsoleActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.shapes.cardMedium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = AppShape.shapes.cardMedium
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(AppShape.shapes.iconSmall)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled) accent else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ConsoleNotice(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShape.shapes.cardMedium)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                shape = AppShape.shapes.cardMedium
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
        }
        OutlinedButton(
            onClick = onAction,
            shape = AppShape.shapes.buttonSmall14,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            contentPadding = ButtonDefaults.ContentPadding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(text = actionText, style = MaterialTheme.typography.labelMedium)
        }
    }
}
