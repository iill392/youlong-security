package roro.stellar.manager.ui.features.settings

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import roro.stellar.manager.compat.BuildUtils.atLeast30
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Subject
// ⚠️ 本工程新增导入（第六轮）：开源许可卡片的图标。
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.topjohnwu.superuser.Shell
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import roro.stellar.Stellar
import roro.stellar.manager.BuildConfig
import roro.stellar.manager.R
import roro.stellar.manager.StellarManagerProvider.Companion.KEY_DAEMON_ENABLED
import roro.stellar.manager.StellarManagerProvider.Companion.KEY_SHIZUKU_COMPAT
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.StellarSettings.DROP_PRIVILEGES
import roro.stellar.manager.StellarSettings.SHIZUKU_COMPAT_ENABLED
import roro.stellar.manager.StellarSettings.TCPIP_PORT
import roro.stellar.manager.StellarSettings.TCPIP_PORT_ENABLED
import roro.stellar.manager.StellarSettings.WIRELESS_DEBUGGING_SU
import roro.stellar.manager.compat.ClipboardUtils
import roro.stellar.manager.db.AppDatabase
import roro.stellar.manager.db.ConfigEntity
import roro.stellar.manager.ktx.setComponentEnabled
import roro.stellar.manager.receiver.BootCompleteReceiver
import roro.stellar.manager.startup.boot.BootScriptManager
import roro.stellar.manager.ui.components.IconContainer
import roro.stellar.manager.ui.components.LocalScreenConfig
import roro.stellar.manager.ui.components.SettingsClickableCard
import roro.stellar.manager.ui.components.SettingsExpandableCard
import roro.stellar.manager.ui.components.SettingsInnerSwitchRow
import roro.stellar.manager.ui.components.SettingsSwitchCard
import roro.stellar.manager.ui.components.StellarSegmentedSelector
// ⚠️ 本工程新增导入：品牌顶栏（带盾牌标识）。
import roro.stellar.manager.ui.components.BrandLargeTopAppBar
import roro.stellar.manager.ui.navigation.components.createTopAppBarScrollBehavior
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.AppSpacing
// ⚠️ 本工程新增导入：ColorMode（配色来源：护盾品牌色 / 跟随壁纸）。
import roro.stellar.manager.ui.theme.ColorMode
import roro.stellar.manager.ui.theme.StartPage
import roro.stellar.manager.ui.theme.ThemeMode
import roro.stellar.manager.ui.theme.ThemePreferences
import roro.stellar.manager.util.EnvironmentUtils
import roro.stellar.manager.util.BackgroundVisibilityUtils
import roro.stellar.manager.util.PortBlacklistUtils
import roro.stellar.manager.util.UserHandleCompat
// ⚠️ 本工程改动（第六轮）：删除 util/update/* 的 5 个 import（更新模块已整体移除）。
import java.util.concurrent.TimeUnit

private const val TAG = "SettingsScreen"

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    topAppBarState: TopAppBarState,
    onNavigateToLogs: () -> Unit = {}
) {
    val scrollBehavior = createTopAppBarScrollBehavior(topAppBarState)
    val context = LocalContext.current
    val componentName = ComponentName(context.packageName, BootCompleteReceiver::class.java.name)
    val screenConfig = LocalScreenConfig.current
    val isLandscape = screenConfig.isLandscape
    val gridColumns = screenConfig.gridColumns

    val preferences = StellarSettings.getPreferences()

    var hasRootPermission by remember { mutableStateOf<Boolean?>(null) }
    var bootMode by remember { mutableStateOf(StellarSettings.getBootMode()) }
    // ⚠️ 本工程改动（2026-10-01）：移除 bootBroadcastAccessibilityEnabled 与
    // showAccessibilityHintDialog 两个状态 —— 它们只服务于已删除的
    // 「无障碍自启」开关 / StellarAccessibilityService。
    var scriptActionInProgress by remember { mutableStateOf(false) }
    var showScriptInstallDialog by remember { mutableStateOf(false) }
    var showScriptRemoveDialog by remember { mutableStateOf(false) }
    var pendingBootModeAfterScriptRemoval by remember { mutableStateOf<StellarSettings.BootMode?>(null) }
    var showBootGuideDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // ⚠️ 本工程改动（第六轮）：删除 currentSource（更新源）状态，随更新模块移除。
    var isServiceRunning by remember { mutableStateOf(Stellar.pingBinder()) }
    var bootAdbStartAvailable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        isServiceRunning = withContext(Dispatchers.IO) { Stellar.pingBinder() }
        bootAdbStartAvailable = withContext(Dispatchers.IO) { isBootAdbStartAvailable() }
        if (bootAdbStartAvailable == false &&
            (bootMode == StellarSettings.BootMode.BROADCAST ||
                bootMode == StellarSettings.BootMode.TCPIP_PREWARM)
        ) {
            applyBootMode(
                context,
                componentName,
                StellarSettings.BootMode.NONE,
                bootMode,
                scope
            ) {
                bootMode = StellarSettings.BootMode.NONE
            }
        }
        val isRoot = withContext(Dispatchers.IO) {
            try {
                Shell.getShell().isRoot
            } catch (_: Exception) {
                false
            }
        }
        hasRootPermission = isRoot
        if (isRoot) {
            val scriptInstalled = withContext(Dispatchers.IO) { BootScriptManager.isScriptInstalled() }
            if (bootMode == StellarSettings.BootMode.SCRIPT && !scriptInstalled) {
                bootMode = StellarSettings.BootMode.NONE
                StellarSettings.setBootMode(StellarSettings.BootMode.NONE)
            }
        }
        // ⚠️ 本工程改动（第六轮）：删除 currentSource = UpdateUtils.getPreferredSource()。
    }

    var tcpipPort by remember {
        mutableStateOf(preferences.getString(TCPIP_PORT, "") ?: "")
    }

    var tcpipPortEnabled by remember {
        mutableStateOf(preferences.getBoolean(TCPIP_PORT_ENABLED, true))
    }

    var dropPrivileges by remember {
        mutableStateOf(preferences.getBoolean(DROP_PRIVILEGES, false))
    }

    var wirelessDebuggingSu by remember {
        mutableStateOf(preferences.getBoolean(WIRELESS_DEBUGGING_SU, false))
    }

    var daemonEnabled by remember {
        mutableStateOf(preferences.getBoolean(StellarSettings.DAEMON_ENABLED, false))
    }

    var hideBackground by remember {
        mutableStateOf(preferences.getBoolean(StellarSettings.HIDE_BACKGROUND, false))
    }

    var currentThemeMode by remember { mutableStateOf(ThemePreferences.themeMode.value) }
    var currentStartPage by remember { mutableStateOf(ThemePreferences.startPage.value) }
    // ⚠️ 本工程新增：配色来源状态。
    var currentColorMode by remember { mutableStateOf(ThemePreferences.colorMode.value) }

    var bootOptionsExpanded by remember { mutableStateOf(false) }
    var themeOptionsExpanded by remember { mutableStateOf(false) }

    fun selectBootMode(newMode: StellarSettings.BootMode) {
        if (newMode == bootMode) return

        if (newMode == StellarSettings.BootMode.SCRIPT) {
            showScriptInstallDialog = true
            return
        }

        if (bootMode == StellarSettings.BootMode.SCRIPT) {
            pendingBootModeAfterScriptRemoval = newMode
            showScriptRemoveDialog = true
            return
        }

        val previousMode = bootMode
        bootMode = newMode
        applyBootMode(context, componentName, newMode, previousMode, scope) {
            if (newMode == StellarSettings.BootMode.BROADCAST) {
                showBootGuideDialog = true
            }
        }
    }

    // ⚠️ 本工程改动（第六轮，2026-10）：
    // ==================================================================
    // 删除整个「检查更新」功能。上游此处是 7 个状态变量
    //   isCheckingUpdate / pendingUpdate / showUpdateDialog / showSourceDialog /
    //   isDownloading / downloadProgress / downloadError
    // 外加 performCheckUpdate 这个协程动作（调 UpdateUtils.checkUpdate）。
    //
    // 删除原因：
    //   1. 本工程把管理器内置进宿主 APK，管理器没有独立版本，也就没有独立
    //      自更新 —— 上游这个功能查的是 Stellar 官方仓库的 release，
    //      内置场景下点它只会提示「已是最新」或误导用户去下载别的 APK。
    //   2. 它依赖的 util/update/ApkDownloader.kt 需要 FileProvider，
    //      而管理器模块的 FileProvider 声明早已因与宿主 authority 冲突被删除
    //      （见 embedded/manager/src/main/AndroidManifest.xml 的修改声明），
    //      所以这条下载→安装链路本来就是断的。
    // 连带删除：util/update/ 三个源文件、设置页的 UpdateCard / NewVersionDialog /
    //          UpdateSourceDialog 三个 Composable 及其 import。
    // ==================================================================

    // ⚠️ 本工程改动（原默认 true）：与 StellarConfig 保持一致，兼容层默认关闭
    var shizukuCompatEnabled by remember { mutableStateOf(preferences.getBoolean(SHIZUKU_COMPAT_ENABLED, false)) }

    LaunchedEffect(Unit) {
        try {
            @SuppressLint("RestrictedApi")
            val remote = withContext(Dispatchers.IO) { Stellar.isShizukuCompatEnabled() }
            shizukuCompatEnabled = remote
            savePreference(SHIZUKU_COMPAT_ENABLED, remote)
        } catch (_: Exception) {
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // ⚠️ 本工程改动：改用带盾牌标识的品牌顶栏（原为 StandardLargeTopAppBar）。
            BrandLargeTopAppBar(
                title = "游龙安全ADB",
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = paddingValues.calculateTopPadding() + AppSpacing.topBarContentSpacing,
                start = AppSpacing.screenHorizontalPadding,
                end = AppSpacing.screenHorizontalPadding,
                bottom = AppSpacing.screenBottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.cardSpacing)
        ) {
            item(span = { GridItemSpan(gridColumns) }) {
                SettingsExpandableCard(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.personalization),
                    subtitle = stringResource(R.string.personalization_subtitle),
                    expanded = themeOptionsExpanded,
                    onExpandChange = { themeOptionsExpanded = it }
                ) {
                    Text(
                        text = stringResource(R.string.app_theme),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeLabels = ThemeMode.entries.associateWith { stringResource(ThemePreferences.getThemeModeDisplayNameRes(it)) }
                    StellarSegmentedSelector(
                        items = ThemeMode.entries.toList(),
                        selectedItem = currentThemeMode,
                        onItemSelected = { mode ->
                            currentThemeMode = mode
                            ThemePreferences.setThemeMode(mode)
                        },
                        itemLabel = { themeLabels[it] ?: "" }
                    )

                    // ⚠️ 本工程新增：配色来源（护盾品牌色 / 跟随壁纸）。
                    //    默认 BRAND，用户可切回上游的壁纸动态取色。
                    Text(
                        text = stringResource(R.string.color_source),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val colorLabels = ColorMode.entries.associateWith {
                        stringResource(ThemePreferences.getColorModeDisplayNameRes(it))
                    }
                    StellarSegmentedSelector(
                        items = ColorMode.entries.toList(),
                        selectedItem = currentColorMode,
                        onItemSelected = { mode ->
                            currentColorMode = mode
                            ThemePreferences.setColorMode(mode)
                        },
                        itemLabel = { colorLabels[it] ?: "" }
                    )

                    Text(
                        text = stringResource(R.string.default_start_page),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val pageLabels = StartPage.entries.associateWith { stringResource(ThemePreferences.getStartPageDisplayNameRes(it)) }
                    StellarSegmentedSelector(
                        items = StartPage.entries.toList(),
                        selectedItem = currentStartPage,
                        onItemSelected = { page ->
                            currentStartPage = page
                            ThemePreferences.setStartPage(page)
                        },
                        itemLabel = { pageLabels[it] ?: "" }
                    )
                }
            }

            item(span = { GridItemSpan(gridColumns) }) {
                SettingsExpandableCard(
                    icon = Icons.Default.FlashOn,
                    title = stringResource(R.string.boot_startup_options),
                    subtitle = stringResource(R.string.boot_startup_options_subtitle),
                    expanded = bootOptionsExpanded,
                    onExpandChange = { bootOptionsExpanded = it }
                ) {
                    Column(
                        modifier = Modifier.animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.cardSpacing)
                    ) {
                        Text(
                            text = stringResource(R.string.boot_start_mode),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val bootModes = listOf(
                            StellarSettings.BootMode.NONE,
                            StellarSettings.BootMode.BROADCAST,
                            StellarSettings.BootMode.TCPIP_PREWARM,
                            StellarSettings.BootMode.SCRIPT
                        )
                        val bootModeLabels = mapOf(
                            StellarSettings.BootMode.NONE to stringResource(R.string.boot_start_mode_off_label),
                            StellarSettings.BootMode.BROADCAST to stringResource(R.string.boot_start_mode_broadcast_label),
                            StellarSettings.BootMode.TCPIP_PREWARM to stringResource(R.string.boot_start_mode_prewarm_label),
                            StellarSettings.BootMode.SCRIPT to stringResource(R.string.boot_start_mode_script_label)
                        )
                        val isBootModeEnabled: (StellarSettings.BootMode) -> Boolean = { mode ->
                            when (mode) {
                                StellarSettings.BootMode.NONE -> true
                                StellarSettings.BootMode.BROADCAST,
                                StellarSettings.BootMode.TCPIP_PREWARM -> bootAdbStartAvailable != false
                                StellarSettings.BootMode.SCRIPT -> hasRootPermission == true && !scriptActionInProgress
                            }
                        }

                        StellarSegmentedSelector(
                            items = bootModes,
                            selectedItem = bootMode,
                            onItemSelected = { mode -> selectBootMode(mode) },
                            itemLabel = { bootModeLabels[it] ?: "" },
                            itemEnabled = isBootModeEnabled
                        )

                        val bootModeDescription = when (bootMode) {
                            StellarSettings.BootMode.NONE -> stringResource(R.string.boot_start_none_subtitle)
                            StellarSettings.BootMode.BROADCAST -> stringResource(R.string.boot_start_callback_mode_subtitle)
                            StellarSettings.BootMode.TCPIP_PREWARM -> stringResource(R.string.boot_start_tcpip_prewarm_subtitle)
                            StellarSettings.BootMode.SCRIPT -> if (hasRootPermission == true) {
                                stringResource(R.string.boot_start_script_mode_subtitle)
                            } else {
                                stringResource(R.string.boot_start_script_mode_subtitle_no_root)
                            }
                        }
                        Text(
                            text = bootModeDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val unavailableMessage = when {
                            (bootMode == StellarSettings.BootMode.BROADCAST ||
                                bootMode == StellarSettings.BootMode.TCPIP_PREWARM) &&
                                bootAdbStartAvailable == false -> stringResource(R.string.boot_start_adb_unavailable)
                            bootMode == StellarSettings.BootMode.SCRIPT &&
                                hasRootPermission != true -> stringResource(R.string.boot_start_script_mode_subtitle_no_root)
                            else -> null
                        }
                        if (unavailableMessage != null) {
                            Text(
                                text = unavailableMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        // ⚠️ 本工程改动（2026-10-01）：删除「无障碍自启」开关。
                        //
                        // 上游这里是一个 AnimatedVisibility + SettingsInnerSwitchRow，
                        // 用于开启 manager 自带的 StellarAccessibilityService
                        // （把 BootCompleteReceiver 置为 enabled，作为开机广播的跳板）。
                        //
                        // 本工程已把该无障碍服务从清单里删除：它在系统「无障碍」列表里
                        // 与宿主自己的 AdSkipService 同名（都显示「游龙安全护盾」），
                        // 实测出现两个条目，用户无法分辨该开哪个；而宿主开机自启本来就由
                        // 自己的 com.youlong.hd.BootReceiver 负责。
                        //
                        // 组件已不存在，开关留着就是骗用户，所以整块移除。
                        // 相关状态变量 bootBroadcastAccessibilityEnabled、
                        // showAccessibilityHintDialog 与提示弹窗也一并移除；
                        // 服务端 ConfigManager.isAccessibilityAutoStartEnabled()
                        // 同步恒返回 false。
                    }
                }
            }

            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.shizuku_compat_layer),
                    subtitle = stringResource(R.string.shizuku_compat_layer_subtitle),
                    checked = shizukuCompatEnabled,
                    onCheckedChange = { newValue ->
                        shizukuCompatEnabled = newValue
                        savePreference(SHIZUKU_COMPAT_ENABLED, newValue)
                        scope.launch {
                            try {
                                val db = AppDatabase.get(context)
                                withContext(Dispatchers.IO) {
                                    db.configDao().set(ConfigEntity(KEY_SHIZUKU_COMPAT, newValue.toString()))
                                }
                            } catch (_: Exception) {}
                            try {
                                @SuppressLint("RestrictedApi")
                                withContext(Dispatchers.IO) {
                                    Stellar.setShizukuCompatEnabled(newValue)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                )
            }

            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Tag,
                    title = stringResource(R.string.wireless_debugging_su),
                    subtitle = stringResource(R.string.wireless_debugging_su_subtitle),
                    checked = wirelessDebuggingSu,
                    onCheckedChange = { newValue ->
                        wirelessDebuggingSu = newValue
                        savePreference(WIRELESS_DEBUGGING_SU, newValue)
                    }
                )
            }

            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.drop_privileges),
                    subtitle = stringResource(R.string.drop_privileges_subtitle),
                    checked = dropPrivileges,
                    enabled = hasRootPermission == true,
                    onCheckedChange = { newValue ->
                        dropPrivileges = newValue
                        savePreference(DROP_PRIVILEGES, newValue)
                    }
                )
            }

            item {
                SettingsSwitchCard(
                    icon = Icons.Default.Replay,
                    title = stringResource(R.string.daemon_enabled),
                    subtitle = stringResource(R.string.daemon_enabled_subtitle),
                    checked = daemonEnabled,
                    onCheckedChange = { newValue ->
                        daemonEnabled = newValue
                        savePreference(StellarSettings.DAEMON_ENABLED, newValue)
                        scope.launch {
                            try {
                                val db = AppDatabase.get(context)
                                withContext(Dispatchers.IO) {
                                    db.configDao().set(ConfigEntity(KEY_DAEMON_ENABLED, newValue.toString()))
                                }
                            } catch (_: Exception) {}
                            try {
                                @SuppressLint("RestrictedApi")
                                withContext(Dispatchers.IO) {
                                    Stellar.setDaemonEnabled(newValue)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                )
            }

            item {
                SettingsSwitchCard(
                    icon = Icons.Default.VisibilityOff,
                    title = stringResource(R.string.hide_background),
                    subtitle = stringResource(R.string.hide_background_subtitle),
                    checked = hideBackground,
                    onCheckedChange = { newValue ->
                        hideBackground = newValue
                        savePreference(StellarSettings.HIDE_BACKGROUND, newValue)
                        BackgroundVisibilityUtils.setHidden(context, newValue)
                    }
                )
            }

            item(span = { GridItemSpan(gridColumns) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = AppShape.shapes.cardMedium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconContainer(
                                icon = Icons.Default.SettingsEthernet,
                                modifier = Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        val ip = EnvironmentUtils.getWifiIpAddress()
                                        val port = tcpipPort.toIntOrNull()?.takeIf { tcpipPortEnabled && it in 1..65535 }
                                        when {
                                            ip == null -> Toast.makeText(context, context.getString(R.string.no_ip_available), Toast.LENGTH_SHORT).show()
                                            port == null -> Toast.makeText(context, context.getString(R.string.tcpip_port_not_configured), Toast.LENGTH_SHORT).show()
                                            else -> {
                                                val text = "adb connect $ip:$port"
                                                ClipboardUtils.put(context, text)
                                                Toast.makeText(context, context.getString(R.string.ip_port_copied, text), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.tcpip_port),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.tcpip_port_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Switch(
                                checked = tcpipPortEnabled,
                                onCheckedChange = { enabled ->
                                    tcpipPortEnabled = enabled

                                    if (enabled && tcpipPort.isEmpty()) {
                                        val randomPort = PortBlacklistUtils.generateSafeRandomPort(1000, 9999, 100)
                                        if (randomPort == -1) {
                                            Toast.makeText(context, context.getString(R.string.cannot_generate_safe_port), Toast.LENGTH_SHORT).show()
                                            tcpipPortEnabled = false
                                        } else {
                                            tcpipPort = randomPort.toString()
                                            preferences.edit {
                                                putBoolean(TCPIP_PORT_ENABLED, enabled)
                                                putString(TCPIP_PORT, tcpipPort)
                                            }
                                            Toast.makeText(context, context.getString(R.string.auto_generated_safe_port, tcpipPort), Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        preferences.edit {
                                            putBoolean(TCPIP_PORT_ENABLED, enabled)
                                        }
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(visible = tcpipPortEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                OutlinedTextField(
                                    value = tcpipPort,
                                    onValueChange = { newValue ->
                                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                            tcpipPort = newValue
                                        }
                                    },
                                    label = { Text(stringResource(R.string.port_number)) },
                                    placeholder = { Text(stringResource(R.string.port_example)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = AppShape.shapes.inputField
                                )

                                Button(
                                    onClick = {
                                        if (tcpipPort.isEmpty()) {
                                            val randomPort = PortBlacklistUtils.generateSafeRandomPort(1000, 9999, 100)
                                            if (randomPort == -1) {
                                                Toast.makeText(context, context.getString(R.string.cannot_generate_safe_port_manual), Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            tcpipPort = randomPort.toString()
                                        }

                                        val port = tcpipPort.toIntOrNull()
                                        if (port == null || port !in 1..65535) {
                                            Toast.makeText(context, context.getString(R.string.port_invalid), Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }

                                        if (PortBlacklistUtils.isPortBlacklisted(port)) {
                                            Toast.makeText(context, context.getString(R.string.port_blacklisted_warning, port), Toast.LENGTH_LONG).show()
                                        }

                                        preferences.edit {
                                            putString(TCPIP_PORT, tcpipPort)
                                        }
                                        Toast.makeText(context, context.getString(R.string.port_set_to, tcpipPort), Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .height(56.dp),
                                    shape = AppShape.shapes.buttonMedium
                                ) {
                                    Text(stringResource(R.string.confirm))
                                }
                            }
                        }
                    }
                }
            }

            item(span = { GridItemSpan(gridColumns) }) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.cardSpacing)
                    ) {
                        SettingsClickableCard(
                            icon = Icons.AutoMirrored.Filled.Subject,
                            title = stringResource(R.string.service_logs),
                            subtitle = stringResource(R.string.service_logs_subtitle),
                            onClick = onNavigateToLogs,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                } else {
                    SettingsClickableCard(
                        icon = Icons.AutoMirrored.Filled.Subject,
                        title = stringResource(R.string.service_logs),
                        subtitle = stringResource(R.string.service_logs_subtitle),
                        onClick = onNavigateToLogs
                    )
                }
            }

            // ⚠️ 本工程改动（2026-10，第六轮）：此处上游是一个 UpdateCard
            //    （「检查更新」卡片），已随整个更新功能一并删除。
            //    原因见下方删除说明与 NOTICE 第五/六轮改动清单。

            item(span = { GridItemSpan(gridColumns) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = AppShape.shapes.cardMedium
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                     Row(
                         verticalAlignment = Alignment.CenterVertically,
                         horizontalArrangement = Arrangement.spacedBy(12.dp)
                     ) {
                         Box(
                             modifier = Modifier
                                 .size(40.dp)
                                 .background(
                                     color = MaterialTheme.colorScheme.primaryContainer,
                                     shape = AppShape.shapes.iconSmall
                                 ),
                             contentAlignment = Alignment.Center
                         ) {
                             Icon(
                                 imageVector = Icons.Default.Info,
                                 contentDescription = null,
                                 tint = MaterialTheme.colorScheme.primary,
                                 modifier = Modifier.size(22.dp)
                             )
                         }
                         
                         Column(modifier = Modifier.weight(1f)) {
                             Text(
                                 text = stringResource(R.string.project_declaration),
                                 style = MaterialTheme.typography.titleMedium,
                                 fontWeight = FontWeight.Bold
                             )
                         }
                     }
                     
                     Spacer(modifier = Modifier.height(12.dp))
                     
                     Text(
                         text = stringResource(R.string.project_declaration_content),
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                     
                     Spacer(modifier = Modifier.height(12.dp))
                     
                     Row(
                         modifier = Modifier.fillMaxWidth(),
                         horizontalArrangement = Arrangement.spacedBy(8.dp)
                     ) {
                         Button(
                             onClick = {
                                 val intent = Intent(Intent.ACTION_VIEW, "https://github.com/RikkaApps/Shizuku".toUri())
                                 try {
                                     context.startActivity(intent)
                                 } catch (_: Exception) {
                                     Toast.makeText(context, context.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show()
                                 }
                             },
                             modifier = Modifier.weight(1f),
                             shape = AppShape.shapes.buttonMedium
                         ) {
                             Icon(
                                 painter = painterResource(R.drawable.ic_github),
                                 contentDescription = null,
                                 modifier = Modifier.size(18.dp)
                             )
                             Spacer(modifier = Modifier.width(8.dp))
                             Text("Shizuku", modifier = Modifier.padding(vertical = 4.dp))
                         }

                         Button(
                             onClick = {
                                 val intent = Intent(Intent.ACTION_VIEW, "https://github.com/roro2239/Stellar".toUri())
                                 try {
                                     context.startActivity(intent)
                                 } catch (_: Exception) {
                                     Toast.makeText(context, context.getString(R.string.cannot_open_browser), Toast.LENGTH_SHORT).show()
                                 }
                             },
                             modifier = Modifier.weight(1f),
                             shape = AppShape.shapes.buttonMedium
                         ) {
                             Icon(
                                 painter = painterResource(R.drawable.ic_github),
                                 contentDescription = null,
                                 modifier = Modifier.size(18.dp)
                             )
                             Spacer(modifier = Modifier.width(8.dp))
                             Text("Stellar", modifier = Modifier.padding(vertical = 4.dp))
                         }
                     // ⚠️ 本工程新增（第六轮）：「开源许可」入口。
                     //    合规依据：Apache-2.0 第 4(a) 条要求向接收者提供许可证
                     //    副本、第 4(b) 条要求保留 NOTICE；MPL-2.0 第 3.1/3.2 条
                     //    要求以 Source Code Form 提供被覆盖的源码。
                     //    用户拿到的是 APK，所以许可证全文必须能在应用内查看 ——
                     //    这里拉起 OpenSourceLicensesActivity，它直接读取
                     //    res/raw/open_source_licenses.txt
                     //    （Apache-2.0 全文 + MPL-2.0 全文 + NOTICE）。
                     Spacer(modifier = Modifier.height(12.dp))

                     SettingsClickableCard(
                         icon = Icons.Default.Description,
                         title = stringResource(R.string.open_source_licenses),
                         subtitle = stringResource(R.string.open_source_licenses_card_subtitle),
                         onClick = {
                             try {
                                 context.startActivity(
                                     Intent(context, OpenSourceLicensesActivity::class.java)
                                 )
                             } catch (_: Exception) {
                                 Toast.makeText(
                                     context,
                                     context.getString(R.string.licenses_read_failed),
                                     Toast.LENGTH_SHORT
                                 ).show()
                             }
                         }
                     )
                 }
                }
            }
            }
        }
    }


    // ⚠️ 本工程改动（2026-10，第六轮）：删除整个「检查更新」功能。
    // ------------------------------------------------------------------
    // 上游这里有两块 UI：
    //   · UpdateCard（设置页里的「检查更新」卡片 + 当前版本号）
    //   · showUpdateDialog / showSourceDialog 两个弹窗（新版本提示、更新源选择）
    // 本工程把管理器内置进宿主 APK，管理器版本与宿主一同分发，
    // 不存在独立自更新；而且管理器自带的 ApkDownloader 依赖的 FileProvider
    // 声明早已因清单冲突被删除（见 AndroidManifest.xml 的修改声明），
    // 这条链路本来就是坏的。
    // 因此整块删除：卡片、两个弹窗、以及配套状态变量
    // （currentSource / pendingUpdate / showUpdateDialog / showSourceDialog /
    //   isDownloading / downloadProgress / downloadError / isCheckingUpdate /
    //   performCheckUpdate）和相关 import。
    // 对应删除的源文件：util/update/（UpdateUtils.kt、AppUpdate.kt、ApkDownloader.kt）
    // 对应的字符串资源（new_version_title / check_update / ... 等 14 条）
    // 仍留在 strings.xml 中但已无引用；保留是为了减少与上游的差异面，
    // 资源压缩器会把它们裁掉。

    if (showBootGuideDialog) {
        BasicAlertDialog(onDismissRequest = { showBootGuideDialog = false }) {
            Surface(
                shape = AppShape.shapes.dialog,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.boot_start_guide_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.boot_start_guide_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showBootGuideDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(onClick = {
                            showBootGuideDialog = false
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:${context.packageName}".toUri()
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }) {
                            Text(stringResource(R.string.boot_start_guide_go_settings))
                        }
                    }
                }
            }
        }
    }

    if (showScriptInstallDialog) {
        BasicAlertDialog(onDismissRequest = { showScriptInstallDialog = false }) {
            Surface(
                shape = AppShape.shapes.dialog,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.boot_start_script_install_confirm_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.boot_start_script_install_confirm_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showScriptInstallDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(onClick = {
                            showScriptInstallDialog = false
                            scriptActionInProgress = true
                            scope.launch(Dispatchers.IO) {
                                val result = BootScriptManager.installScript()
                                withContext(Dispatchers.Main) {
                                    scriptActionInProgress = false
                                    if (result.success) {
                                        applyBootMode(
                                            context, componentName, StellarSettings.BootMode.SCRIPT,
                                            bootMode, scope
                                        ) { bootMode = StellarSettings.BootMode.SCRIPT }
                                    }
                                    Toast.makeText(
                                        context,
                                        if (result.success) context.getString(R.string.boot_script_install_success)
                                        else context.getString(R.string.boot_script_install_failed, result.message),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
    }

    if (showScriptRemoveDialog) {
        BasicAlertDialog(onDismissRequest = {
            showScriptRemoveDialog = false
            pendingBootModeAfterScriptRemoval = null
        }) {
            Surface(
                shape = AppShape.shapes.dialog,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.boot_start_script_remove_confirm_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.boot_start_script_remove_confirm_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            showScriptRemoveDialog = false
                            pendingBootModeAfterScriptRemoval = null
                        }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(onClick = {
                            showScriptRemoveDialog = false
                            scriptActionInProgress = true
                            scope.launch(Dispatchers.IO) {
                                val result = BootScriptManager.removeScript()
                                withContext(Dispatchers.Main) {
                                    scriptActionInProgress = false
                                    if (result.success) {
                                        val nextMode = pendingBootModeAfterScriptRemoval
                                            ?: StellarSettings.BootMode.NONE
                                        pendingBootModeAfterScriptRemoval = null
                                        if (nextMode == StellarSettings.BootMode.NONE) {
                                            applyBootMode(
                                                context,
                                                componentName,
                                                StellarSettings.BootMode.NONE,
                                                bootMode,
                                                scope
                                            ) { bootMode = StellarSettings.BootMode.NONE }
                                        } else {
                                            applyBootMode(
                                                context,
                                                componentName,
                                                nextMode,
                                                bootMode,
                                                scope
                                            ) {
                                                bootMode = nextMode
                                                if (nextMode == StellarSettings.BootMode.BROADCAST) {
                                                    showBootGuideDialog = true
                                                }
                                            }
                                        }
                                    }
                                    Toast.makeText(
                                        context,
                                        if (result.success) context.getString(R.string.boot_script_remove_success)
                                        else context.getString(R.string.boot_script_remove_failed, result.message),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
            }
        }
    }
}

private fun savePreference(key: String, value: Boolean) {
    StellarSettings.getPreferences().edit { putBoolean(key, value) }
}

private fun isBootAdbStartAvailable(): Boolean {
    if (!Stellar.pingBinder()) return true
    val writeSecureSettings = hasRemotePermission("android.permission.WRITE_SECURE_SETTINGS")
    val grantRuntimePermission = hasRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS")
    val bootAdbPortAvailable = atLeast30 ||
        EnvironmentUtils.getAdbTcpPort() > 0
    val commandAvailable = canExecuteCommand("id") &&
        canExecuteCommand("getprop ro.build.version.sdk")
    return writeSecureSettings &&
        grantRuntimePermission &&
        UserHandleCompat.myUserId() == 0 &&
        bootAdbPortAvailable &&
        commandAvailable
}

private fun hasRemotePermission(permission: String): Boolean =
    Stellar.checkRemotePermission(permission) == PackageManager.PERMISSION_GRANTED

private fun canExecuteCommand(command: String): Boolean {
    val process = try {
        Stellar.newProcess(arrayOf("sh", "-c", command), null, null)
    } catch (_: Throwable) {
        return false
    }

    return try {
        if (!process.waitForTimeout(1500, TimeUnit.MILLISECONDS)) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Throwable) {
        runCatching { process.destroy() }
        false
    }
}

private fun applyBootMode(
    context: Context,
    componentName: ComponentName,
    newMode: StellarSettings.BootMode,
    currentMode: StellarSettings.BootMode,
    scope: kotlinx.coroutines.CoroutineScope,
    onSuccess: () -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            when (currentMode) {
                StellarSettings.BootMode.BROADCAST,
                StellarSettings.BootMode.TCPIP_PREWARM -> {
                    context.packageManager.setComponentEnabled(componentName, false)
                }
                StellarSettings.BootMode.SCRIPT, StellarSettings.BootMode.NONE -> Unit
            }
            when (newMode) {
                StellarSettings.BootMode.BROADCAST,
                StellarSettings.BootMode.TCPIP_PREWARM -> {
                    context.packageManager.setComponentEnabled(componentName, true)
                }
                StellarSettings.BootMode.SCRIPT, StellarSettings.BootMode.NONE -> Unit
            }
            // ⚠️ 本工程改动（2026-10-01）：这里原本会写
            //     accessibilityAutoStart = (newMode == BROADCAST) && <无障碍自启开关>
            // 用于让服务端把 StellarAccessibilityService 写进
            // ENABLED_ACCESSIBILITY_SERVICES。该无障碍服务与开关均已删除，
            // 因此恒写 false —— 服务端 ConfigManager.isAccessibilityAutoStartEnabled()
            // 也已短路为 false，两处保持一致，不会再产生无效的系统无障碍条目。
            AppDatabase.get(context).configDao().set(
                ConfigEntity("accessibilityAutoStart", "false")
            )
            StellarSettings.setBootMode(newMode)
            withContext(Dispatchers.Main) { onSuccess() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply boot mode $newMode", e)
        }
    }
}

