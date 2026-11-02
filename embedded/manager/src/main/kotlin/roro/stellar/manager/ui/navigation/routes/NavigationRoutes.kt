// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/ui/navigation/routes/NavigationRoutes.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：删除 MainScreen.Terminal 枚举项（上游有 Home / Apps / Terminal / Settings
//         四项）。
//   原因：本工程把内置管理器作为「游龙安全护盾」的一个子界面提供，
//         不需要把「命令」（Terminal）这套面向普通用户开放的 shell 执行入口
//         暴露出来 —— 它既是多余的功能面，也是一个明显的误操作风险点
//         （用户可以在里面直接执行任意 shell 命令）。删除后底部导航为
//         启动 / 授权应用 / 设置三项。
//   连带改动（均已同步）：
//     · MainActivity.kt 删除 TerminalScreen 的路由分支与 import；
//     · ui/features/terminal/ 整个目录删除（TerminalScreen.kt、TerminalViewModel.kt）；
//     · ThemePreferences.kt 删除 StartPage.TERMINAL（启动页不再能选「命令」）。
//   除删除 Terminal 项外，本文件其余内容与上游一致。
//
// 【本工程修改声明】—— 2026-10-24 追加（按 MPL-2.0 第 3.4 条标注）
//   改动：新增 MainScreen.VirusDb 枚举项，底部导航变为
//         启动 / 授权应用 / 病毒库 / 设置 四项。
//   原因：用户要求把「病毒库同步」从设置页的卡片改成独立的导航页面，
//         用于手动检查特征库更新与显示最近一次同步时间。
//   连带改动：MainActivity.kt 增加 VirusDb 路由；新增
//         ui/features/virusdb/VirusDbScreen.kt；SettingsScreen 移除原卡片。
// ==========================================================================

package roro.stellar.manager.ui.navigation.routes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import roro.stellar.manager.R

enum class MainScreen(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val iconFilled: ImageVector
) {
    Home(
        route = "home_graph",
        labelRes = R.string.nav_home,
        icon = Icons.Outlined.PlayArrow,
        iconFilled = Icons.Filled.PlayArrow
    ),

    Apps(
        route = "apps_graph",
        labelRes = R.string.nav_apps,
        icon = Icons.Outlined.Apps,
        iconFilled = Icons.Filled.Apps
    ),

    // ⚠️ 本工程删除：上游此处为 Terminal（命令），见文件头修改声明。

    // 2026-10-24 新增：病毒库独立页面（手动检查更新 / 显示同步时间）
    VirusDb(
        route = "virusdb_graph",
        labelRes = R.string.nav_virus_db,
        icon = Icons.Outlined.Security,
        iconFilled = Icons.Filled.Security
    ),

    Settings(
        route = "settings_graph",
        labelRes = R.string.nav_settings,
        icon = Icons.Outlined.Settings,
        iconFilled = Icons.Filled.Settings
    )
}
