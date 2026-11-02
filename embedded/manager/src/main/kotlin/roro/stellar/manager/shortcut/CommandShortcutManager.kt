// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/shortcut/CommandShortcutManager.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动 1：参数由上游的 `command: CommandItem` 改为 `commandId: String` +
//          `title: String`；
//          import 由 roro.stellar.manager.ui.features.terminal.CommandItem
//          改为 roro.stellar.manager.db.CommandEntity。
//   改动 2：快捷方式图标由 R.drawable.ic_notification_shield 改为宿主自己的启动图标
//          R.drawable.ic_notification_shield。
//   原因：上游这个函数是给「命令」页（TerminalScreen）用的，
//         本工程已按需求把整个「命令」页从内置管理器移除
//         （见 NavigationRoutes.kt 的修改声明），CommandItem 类型随之不存在。
//         但「执行已保存命令」这条链路仍然保留：
//           · 开机跟随执行（StellarApplication.runStartupCommands）
//           · 桌面快捷方式（本文件 + CommandShortcutActivity）
//         它们只需要 id 与 title，因此直接用 Room 的 CommandEntity 字段传入即可，
//         不必为了一个图标按钮把 CommandItem 类型复活。
//         图标换成宿主自己的启动图标，是为了不再在系统任何位置显示 Stellar 图标。
//   除以上两处外，本文件其余内容与上游一致。
// ==========================================================================

package roro.stellar.manager.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast
import roro.stellar.manager.R
import roro.stellar.manager.compat.BuildUtils.atLeast26

object CommandShortcutManager {
    /**
     * 请求把一个「已保存命令」固定到桌面。
     *
     * @param commandId 命令在 Room 里的 id（见 CommandEntity.id）
     * @param title     快捷方式显示名（见 CommandEntity.title）
     */
    fun requestPin(context: Context, commandId: String, title: String) {
        if (!atLeast26) {
            Toast.makeText(context, R.string.shortcut_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) {
            Toast.makeText(context, R.string.shortcut_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, CommandShortcutActivity::class.java)
            .setAction(CommandShortcutActivity.ACTION_EXECUTE_COMMAND)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            .putExtra(CommandShortcutActivity.EXTRA_COMMAND_ID, commandId)
        val shortcut = ShortcutInfo.Builder(context, "command:$commandId")
            .setShortLabel(title)
            .setLongLabel(title)
            // ⚠️ 本工程改动：不再使用 Stellar 图标。
            .setIcon(Icon.createWithResource(context, R.drawable.ic_notification_shield))
            .setIntent(intent)
            .build()
        manager.requestPinShortcut(shortcut, null)
    }
}
