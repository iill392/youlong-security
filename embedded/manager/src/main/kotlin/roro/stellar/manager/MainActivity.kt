package roro.stellar.manager

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import roro.stellar.Stellar
import roro.stellar.StellarApiConstants
import roro.stellar.manager.authorization.AuthorizationManager
import roro.stellar.manager.authorization.RequestPermissionActivity
import roro.stellar.manager.domain.apps.AppType
import roro.stellar.manager.domain.apps.AppsViewModel
import roro.stellar.manager.domain.apps.appsViewModel
import roro.stellar.manager.ui.components.AdaptiveLayoutProvider
import roro.stellar.manager.ui.features.apps.AppsScreen
import roro.stellar.manager.ui.features.home.HomeScreen
import roro.stellar.manager.ui.features.home.HomeViewModel
import roro.stellar.manager.ui.features.manager.ManagerActivity
import roro.stellar.manager.ui.features.settings.SettingsScreen
import roro.stellar.manager.ui.features.virusdb.VirusDbScreen
import roro.stellar.manager.ui.navigation.components.LocalNavigationState
import roro.stellar.manager.ui.navigation.components.LocalTopAppBarState
import roro.stellar.manager.ui.navigation.components.NavigationState
import roro.stellar.manager.ui.navigation.components.StandardBottomNavigation
import roro.stellar.manager.ui.navigation.components.StandardNavigationRail
import roro.stellar.manager.ui.navigation.components.TopAppBarProvider
import roro.stellar.manager.ui.navigation.routes.MainScreen
import roro.stellar.manager.ui.navigation.safePopBackStack
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.manager.ui.theme.ThemePreferences
import roro.stellar.manager.ui.theme.StartPage
import roro.stellar.manager.util.BackgroundVisibilityUtils
// ⚠️ 本工程新增导入：selfGrantPermission() 的失败日志。
import roro.stellar.manager.util.Logger.Companion.LOGGER

class MainActivity : ComponentActivity() {

    private companion object {
        const val STATE_SOURCE_PACKAGE = "source_package"
    }

    private var pendingSourcePackage: String? = null
    private var sourceAuthorizationStarted = false

    private val binderReceivedListener = Stellar.OnBinderReceivedListener {
        checkServerStatus()
        selfGrantPermission()
        handlePendingSourceApp()
        try {
            appsModel.load()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val binderDeadListener = Stellar.OnBinderDeadListener {
        checkServerStatus()
    }

    private val homeModel by viewModels<HomeViewModel>()
    private val appsModel by appsViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        pendingSourcePackage = savedInstanceState?.getString(STATE_SOURCE_PACKAGE)
        rememberSourceApp()
        // 2026-11-01（用户需求）：隐藏后台改为自动生效，不再提供用户开关。
        StellarSettings.getPreferences().edit()
            .putBoolean(StellarSettings.HIDE_BACKGROUND, true)
            .apply()
        BackgroundVisibilityUtils.setHidden(this, true)
        
        enableEdgeToEdge()
        
        setContent {
            val themeMode = ThemePreferences.themeMode.value

            StellarTheme(themeMode = themeMode) {
                TopAppBarProvider {
                    MainScreenContent(
                        homeViewModel = homeModel,
                        appsViewModel = appsModel
                    )
                }
            }
        }

        Stellar.addBinderReceivedListenerSticky(binderReceivedListener)
        Stellar.addBinderDeadListener(binderDeadListener)
        
        checkServerStatus()
        
        if (Stellar.pingBinder() && appsModel.stellarApps.value == null) {
            appsModel.load()
        }
        handlePendingSourceApp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        rememberSourceApp()
        handlePendingSourceApp()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingSourcePackage?.let { outState.putString(STATE_SOURCE_PACKAGE, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
        if (Stellar.pingBinder()) {
            appsModel.load(true)
        }
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    // ======================================================================
    // 自身免询问授权（2026-10 新增，用户需求）
    // ----------------------------------------------------------------------
    // 需求：「只要激活了内置特权就自动授权 com.youlong.hd，无需询问」。
    //
    // 服务端本来就把本应用当作**管理器**（ServerConstants.MANAGER_APPLICATION_ID
    // == 本应用包名），PermissionEnforcer 对管理器的判定是
    //     if (isSelf(caller) || isManager(caller)) return      // 直接放行
    // 所以功能上从来不会被拦。
    //
    // 之所以还要显式写一次配置，是因为**授权状态是持久化在配置里的**：
    // 如果历史上这条 uid 的记录被写成 FLAG_DENIED（比如早前版本包名不同、
    // 或用户误点过「拒绝」），「授权应用」列表里就会显示成「拒绝」，
    // 既误导用户，也可能让别处基于 flag 的判断出错。
    //
    // 这里在每次 Binder 连上时把自身的 stellar 权限强制写为 GRANTED：
    //   · 幂等，重复执行无副作用；
    //   · 服务端对管理器的调用是放行的，所以这个写入一定成功；
    //   · 失败只记日志，绝不影响界面。
    // ======================================================================
    private fun selfGrantPermission() {
        try {
            if (!Stellar.pingBinder()) return
            val uid = applicationInfo?.uid ?: return
            Stellar.updateFlagForUid(uid, StellarApiConstants.PERMISSION_STELLAR, 1)
        } catch (t: Throwable) {
            LOGGER.w("自身免询问授权失败（已忽略）", t)
        }
    }

    private fun rememberSourceApp() {
        val sourcePackage = referrer?.host?.takeIf {
            it.isNotBlank() && it != packageName
        } ?: return

        pendingSourcePackage = sourcePackage
        sourceAuthorizationStarted = false
    }

    private fun handlePendingSourceApp() {
        val sourcePackage = pendingSourcePackage ?: return
        if (!Stellar.pingBinder() || sourceAuthorizationStarted) return

        val packages = runCatching { AuthorizationManager.getPackages() }.getOrElse { return }
        val packageInfo = packages.firstOrNull { it.packageName == sourcePackage }
        if (packageInfo == null) {
            clearSourceApp()
            return
        }
        val appType = AuthorizationManager.getAppType(packageInfo)
        val permission =
            if (appType == AppType.SHIZUKU) "shizuku" else StellarApiConstants.PERMISSION_STELLAR
        val grantedFlag = if (appType == AppType.SHIZUKU) 2 else AuthorizationManager.FLAG_GRANTED
        val uid = packageInfo.applicationInfo?.uid ?: run {
            clearSourceApp()
            return
        }
        val currentFlag = runCatching { Stellar.getFlagForUid(uid, permission) }.getOrElse { return }
        val isGranted = currentFlag == grantedFlag

        if (isGranted) {
            clearSourceApp()
        } else {
            sourceAuthorizationStarted = true
            runCatching {
                startActivity(
                    RequestPermissionActivity.createSourceAuthorizationIntent(
                        this,
                        packageInfo,
                        permission
                    )
                )
            }.onFailure {
                sourceAuthorizationStarted = false
            }
        }
    }

    private fun clearSourceApp() {
        pendingSourcePackage = null
        sourceAuthorizationStarted = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Stellar.removeBinderReceivedListener(binderReceivedListener)
        Stellar.removeBinderDeadListener(binderDeadListener)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    homeViewModel: HomeViewModel,
    appsViewModel: AppsViewModel
) {
    val topAppBarState = LocalTopAppBarState.current!!
    val navController = rememberNavController()

    // ⚠️ 本工程改动：StartPage.TERMINAL 已删除，启动页分支只剩 首页 / 授权应用。
    //    老用户如果之前把「命令」设为默认启动页，ThemePreferences 会回落到首页
    //    （见 ThemePreferences.StartPage.fromValue 的默认值）。
    val startPage = remember { ThemePreferences.startPage.value }
    val initialIndex = when (startPage) {
        StartPage.HOME -> 0
        StartPage.APPS -> 1
    }
    val startRoute = when (startPage) {
        StartPage.HOME -> MainScreen.Home.route
        StartPage.APPS -> MainScreen.Apps.route
    }

    var selectedIndex by remember { androidx.compose.runtime.mutableIntStateOf(initialIndex) }

    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    val context = navController.context

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler {
        if (navController.previousBackStackEntry == null) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressTime < 2000) {
                (context as? ComponentActivity)?.finish()
            } else {
                lastBackPressTime = currentTime
                Toast.makeText(context, context.getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
            }
        } else {
            navController.safePopBackStack()
        }
    }

    val onNavigationItemClick: (Int) -> Unit = { index ->
        if (selectedIndex != index) {
            selectedIndex = index
            val route = MainScreen.entries[index].route
            navController.navigate(route) {
                popUpTo(0) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }
    }

    val navigationState = NavigationState(
        selectedIndex = selectedIndex,
        onItemClick = onNavigationItemClick
    )

    val navHostContent: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = modifier,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { fadeIn(animationSpec = tween(300)) },
            popExitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            navigation(
                startDestination = "home",
                route = MainScreen.Home.route
            ) {
                composable("home") {
                    HomeScreen(
                        topAppBarState = topAppBarState,
                        homeViewModel = homeViewModel,
                        onNavigateToStarter = { isRoot, host, port, hasSecureSettings ->
                            context.startActivity(ManagerActivity.createStarterIntent(context, isRoot, host, port, hasSecureSettings))
                        }
                    )
                }
            }

            navigation(
                startDestination = "apps",
                route = MainScreen.Apps.route
            ) {
                composable("apps") {
                    AppsScreen(
                        topAppBarState = topAppBarState,
                        appsViewModel = appsViewModel
                    )
                }
            }

            // 2026-10-24 新增：底部导航「病毒库」独立页面
            navigation(
                startDestination = "virusdb",
                route = MainScreen.VirusDb.route
            ) {
                composable("virusdb") {
                    VirusDbScreen(topAppBarState = topAppBarState)
                }
            }

            navigation(
                startDestination = "settings",
                route = MainScreen.Settings.route
            ) {
                composable("settings") {
                    SettingsScreen(
                        topAppBarState = topAppBarState
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalNavigationState provides navigationState) {
        AdaptiveLayoutProvider {
            // 2026-10-17 第 18 轮：整页统一深色渐变底。
            // 各页面 Scaffold 已改为透明，让这层渐变透出来，不再是一块块纯色背景。
            val backdrop = Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.surfaceContainerLowest
                )
            )

            if (isLandscape) {
                Row(modifier = Modifier
                    .fillMaxSize()
                    .background(backdrop)
                ) {
                    StandardNavigationRail(
                        selectedIndex = selectedIndex,
                        onItemClick = onNavigationItemClick
                    )
                    navHostContent(Modifier
                        .weight(1f)
                        .fillMaxSize()
                    )
                }
            } else {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(backdrop)
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        bottomBar = {
                            StandardBottomNavigation(
                                selectedIndex = selectedIndex,
                                onItemClick = onNavigationItemClick
                            )
                        },
                        contentWindowInsets = WindowInsets.navigationBars
                    ) {
                        navHostContent(Modifier.fillMaxSize().padding(it))
                    }
                }
            }
        }
    }
}
