// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：命令安全拦截的用户界面。
//
//   服务端 ProcessManager 在放行一条来自第三方应用的 shell 命令之前，
//   如果判定该命令危险，会拉起本 Activity：
//     · kind=block    → 只告知「某应用尝试某操作，已被拦截」，一个「好的」按钮；
//     · kind=confirm  → 询问用户是否允许（卸载 / 冻结 / 清数据类命令），
//                       用户选择后写答复文件，服务端据此决定放行或拒绝；
//     · kind=policy   → 2026-10-24 新增：内置 Stellar 的 stdin 脚本代理网关
//                       在放行整段脚本前先做脚本级分析，脚本触碰受保护能力时
//                       由本弹窗就「某应用请求使用某能力」征求授权，用户选择
//                       拒绝 / 允许一次 / 始终允许该能力后回写答复。
//
//   答复通过文件回传（/data/local/tmp/stellar_guard/<id>.resp）：
//   服务端与管理器约定用文件握手，避免改动上游 AIDL 接口。
//
// ==========================================================================

package roro.stellar.manager.authorization

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
// ⚠️ 本工程新增导入（2026-10）：告警框顶部改为应用封面所需。
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
// ⚠️ 本工程新增导入（2026-10）：Drawable -> ImageBitmap。
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import roro.stellar.Stellar
import roro.stellar.manager.R
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.manager.util.Logger.Companion.LOGGER
import java.io.File

class CommandGuardActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CommandGuardActivity"

        /** 与服务端 CommandGuard 约定的握手目录。 */
        private const val HANDSHAKE_DIR = "/data/local/tmp/stellar_guard"

        private const val REPLY_ALLOW = "ALLOW"
        private const val REPLY_DENY = "DENY"
        private const val REPLY_ACK = "ACKNOWLEDGED"

        /**
         * 2026-10-23（用户需求）：「始终允许」。
         * 服务端收到后会把「本应用 + 这条指令」记下来，下次同一应用执行
         * 完全相同的这条指令时不再弹窗；换一条指令仍会询问。
         */
        private const val REPLY_ALWAYS = "ALWAYS_ALLOW"

        /**
         * 2026-10-24 新增：内置特权「脚本级审批」（kind=policy）。
         *
         * 服务端 stdin 脚本代理网关在放行整段脚本之前，如果脚本触碰了受保护能力
         * （包管理 / 系统设置 / 进程控制 / 持久化…），会以 kind=policy 拉起本
         * Activity。答复 token 由服务端定义，与本文件上面的指令级 token 相互独立：
         *   · DENY         —— 拒绝（安全默认；30 秒无操作也按它回写）；
         *   · ALLOW        —— 仅本次允许；
         *   · ALLOW_ALWAYS —— 始终允许「调用方 + 该能力」，由服务端写入
         *                     /data/local/tmp/stellar_guard/scope_grants.txt。
         *
         * ⚠️ 注意别和上面对指令级「始终允许」的 [REPLY_ALWAYS] 混用：
         *    指令级是 "ALWAYS_ALLOW"（前缀是 ALWAYS），能力级是 "ALLOW_ALWAYS"
         *    （前缀是 ALLOW），两者字面相反，写错会被服务端当成「允许一次」。
         */
        private const val KIND_POLICY = "policy"
        private const val POLICY_REPLY_ALLOW = "ALLOW"
        private const val POLICY_REPLY_DENY = "DENY"
        private const val POLICY_REPLY_ALWAYS = "ALLOW_ALWAYS"

        /** 兼容服务端"确认超时"的时长：即使用户不理会，也不无限占用服务端线程。 */
        private const val AUTO_DISMISS_MS = 30_000L
    }

    private var replyId: Long = -1
    private var replied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getLongExtra("id", -1L)
        val kind = intent.getStringExtra("kind") ?: "block"
        val packageName = intent.getStringExtra("packageName")
        val command = intent.getStringExtra("command") ?: ""
        val reason = intent.getStringExtra("reason") ?: ""
        // 2026-10 新增：服务端命令分析得出的风险类型
        // system=动系统应用 / bulk=批量操作 / admin=设备管理员·策略 / normal=普通
        val riskKind = intent.getStringExtra("riskKind") ?: "normal"
        // 2026-10 第 19 轮：服务端分析出的危险程度与说明
        val riskLevel = intent.getStringExtra("riskLevel") ?: "medium"
        val analysis = intent.getStringExtra("analysis") ?: ""
        // 2026-10-18：调用方是否在指令拦截白名单中
        val whitelisted = intent.getBooleanExtra("whitelisted", false)
        // 2026-10-24 新增：内置特权「脚本级审批」（kind=policy）的 extras。
        // 只有 stdin 代理网关会下发这些字段；旧服务端不带时用安全默认值：
        // scopeLabel 退回能力 id，score=0（不显示评分），script 为空（不显示脚本框）。
        val scope = intent.getStringExtra("scope") ?: ""
        val scopeLabel = intent.getStringExtra("scopeLabel") ?: ""
        val score = intent.getIntExtra("score", 0)
        val script = intent.getStringExtra("script") ?: ""
        replyId = id

        if (id == -1L) {
            LOGGER.e("$TAG: 缺少请求 id，直接结束")
            closeSelf()
            return
        }

        // 服务端在等待答复时会超时兜底；这里到点自动关闭，
        // 避免对话框长期驻留（用户没看到时也等同于拒绝）。
        window.decorView.postDelayed({
            if (!isFinishing && !replied) {
                LOGGER.w("$TAG: 用户未在 ${AUTO_DISMISS_MS}ms 内操作，按拒绝处理")
                reply(REPLY_DENY)
                closeSelf()
            }
        }, AUTO_DISMISS_MS)

        val appLabel = resolveAppLabel(packageName)
        // 2026-10 第 19 轮（用户澄清）：弹窗顶部固定使用**本应用（游龙安全护盾）**
        // 的软件图标，而不是被授权/调用方应用的图标。调用方信息只以文字展示。
        val appIcon = resolveSelfIcon()

        setContent {
            StellarTheme {
                // 2026-10-24 新增：kind=policy —— 内置特权脚本级审批。
                // 放在最前面，原有的 block / confirm 两个分支保持原样。
                if (kind == KIND_POLICY) {
                    PolicyGuardDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,
                        riskLevel = riskLevel,
                        analysis = analysis,
                        scope = scope,
                        scopeLabel = scopeLabel,
                        score = score,
                        script = script,
                        appIcon = appIcon,
                        onAllow = {
                            reply(POLICY_REPLY_ALLOW)
                            closeSelf()
                        },
                        onAllowAlways = {
                            // 2026-10-24：记住「这个应用 + 这个能力」，
                            // 服务端写入 scope_grants.txt，下次不再询问。
                            reply(POLICY_REPLY_ALWAYS)
                            closeSelf()
                        },
                        onDeny = {
                            reply(POLICY_REPLY_DENY)
                            closeSelf()
                        }
                    )
                } else if (kind == "confirm") {
                    CommandGuardConfirmDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,
                        riskKind = riskKind,
                        riskLevel = riskLevel,
                        analysis = analysis,
                        whitelisted = whitelisted,
                        appIcon = appIcon,
                        onAllow = {
                            reply(REPLY_ALLOW)
                            closeSelf()
                        },
                        onAllowAlways = {
                            // 2026-10-23：记住「应用 + 这条指令」，下次不再询问
                            reply(REPLY_ALWAYS)
                            closeSelf()
                        },
                        onDeny = {
                            reply(REPLY_DENY)
                            closeSelf()
                        }
                    )
                } else {
                    CommandGuardAlertDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,
                        appIcon = appIcon,
                        riskLevel = riskLevel,
                        analysis = analysis,
                        onDismiss = {
                            reply(REPLY_ACK)
                            closeSelf()
                        }
                    )
                }
            }
        }
    }

    /**
     * 关闭弹窗。正常情况下它在自己的独立任务里（isTaskRoot=true），
     * finishAndRemoveTask() 会把空任务一起移除，回到调用方应用；
     * 极端情况下若被系统塞进别的任务，则只 finish，避免误删宿主任务。
     */
    private fun closeSelf() {
        if (isTaskRoot) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        // 若因系统原因（返回键、被回收）离开而没有答复，按拒绝处理，
        // 保证服务端不会一直等到超时。
        if (!replied && replyId != -1L) {
            reply(REPLY_DENY)
        }
        super.onDestroy()
    }

    /**
     * 本应用（游龙安全护盾）的启动图标，用作弹窗顶部标识。
     *
     * 不能用 R.mipmap.ic_launcher：启动图标在 :app 模块里，:manager 库模块
     * 编译期看不到宿主的 mipmap 资源表。改为运行时通过 PackageManager
     * 读取本应用自己的 applicationInfo.icon。
     */
    private fun resolveSelfIcon(): ImageBitmap? {
        return try {
            val drawable = applicationInfo?.loadIcon(packageManager) ?: return null
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (t: Throwable) {
            LOGGER.w(t, "$TAG: 读取本应用图标失败（将退回默认标识）")
            null
        }
    }

    /**
     * 尽力把包名换成应用显示名，换不到就用包名本身。
     */
    private fun resolveAppLabel(packageName: String?): String {
        if (packageName.isNullOrEmpty()) return "未知应用"
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Throwable) {
            packageName
        }
    }

    /**
     * 把答复写回握手目录。
     *
     * 2026-10 第 19 轮修复：握手目录是 shell 创建的 0711 目录，普通 app UID
     * **没有写权限**，直接 writeText 会 EACCES，导致用户点「允许」后服务端
     * 收不到答复、只能超时拒绝。这里先尝试直接写；失败则用内置特权 shell
     * （manager 身份不受命令拦截约束）写入答复。
     */
    private fun reply(text: String) {
        if (replied || replyId == -1L) return
        replied = true

        val path = "$HANDSHAKE_DIR/$replyId.resp"

        // ① 目录可写时直接写（最快路径）
        try {
            val dir = File(HANDSHAKE_DIR)
            if (dir.exists() || dir.mkdirs()) {
                File(dir, "$replyId.resp").writeText(text)
                return
            }
        } catch (_: Throwable) {
            // 继续走 shell 路径
        }

        // ② app 无写权限 → 用内置特权 shell 写
        try {
            val quotedText = "'" + text.replace("'", "'\\''") + "'"
            val quotedPath = "'" + path.replace("'", "'\\''") + "'"
            val process = Stellar.newProcess(
                arrayOf("sh", "-c", "printf %s $quotedText > $quotedPath"),
                null,
                null
            )
            process.waitFor()
            if (!File(path).exists()) {
                LOGGER.w("$TAG: 答复文件写入后仍不存在: $path")
            }
        } catch (t: Throwable) {
            LOGGER.w(t, "$TAG: 写答复失败（shell 回写）")
        }
    }
}

// ======================================================================
// 对话框 UI
// ======================================================================

/**
 * 一个不带图标资源的「盾牌」标识，用字符拼出来，避免依赖具体 drawable。
 */
@Composable
private fun GuardBadge(container: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(AppShape.shapes.iconMedium18)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "盾",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 命令内容预览框：等宽字体、最多 4 行、可滚动。
 */
@Composable
private fun CommandPreview(command: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.command_guard_command_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = AppShape.shapes.buttonSmall14,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = command,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 单行按钮（与权限确认弹窗保持一致的视觉语言）。
 */
@Composable
private fun DialogButton(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.buttonSmall14,
        colors = CardDefaults.elevatedCardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 「已被拦截」告警框：只有一个「好的」按钮，不阻塞服务端。
 *
 * 2026-10 改动（用户需求）：
 *   · 标题固定为「游龙工具已阻止此威胁」；
 *   · 顶部标识改为本应用自己的封面（启动图标），读不到时退回内置盾牌标识；
 *   · 正文改为「谁尝试了什么」，不再把「拦截原因」和「尝试」拼成一句话
 *     （原先是「X 尝试 Y，已被拦截」，标题已经是「已阻止」了，重复）。
 */
@Composable
fun CommandGuardAlertDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    onDismiss: () -> Unit,
    appIcon: ImageBitmap? = null,
    riskLevel: String = "critical",
    analysis: String = "极高风险：该命令已被安全策略直接阻止。"
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = AppShape.shapes.cardMedium24,
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标识：优先用应用封面
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(AppShape.shapes.iconMedium18)
                    )
                } else {
                    GuardBadge(MaterialTheme.colorScheme.errorContainer)
                }

                Text(
                    // 2026-10 第 19 轮：统一为「游龙安全护盾已阻止此威胁」，
                    // 不再使用「游龙工具」这一旧称呼。
                    text = "游龙安全护盾已阻止此威胁",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    // 「谁尝试了什么」
                    text = "$appLabel 尝试 $reason",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 2026-10 第 19 轮：拦截弹窗也告诉用户危险程度与分析
                RiskCard(riskLevel, analysis)

                if (!packageName.isNullOrEmpty()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (command.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    CommandPreview(command)
                }

                DialogButton(
                    text = stringResource(R.string.command_guard_ok),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun RiskCard(level: String, analysis: String) {
    val (label, color) = when (level) {
        "critical" -> "极高" to MaterialTheme.colorScheme.error
        "high" -> "高" to MaterialTheme.colorScheme.tertiary
        "medium" -> "中" to MaterialTheme.colorScheme.secondary
        else -> "低" to MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.buttonSmall14,
        color = color.copy(alpha = 0.12f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "危险程度：$label",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Text(
                text = analysis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 按风险类型给出针对性的风险说明（2026-10 新增，用户需求）。
 *
 * <p>风险类型由服务端 CommandInterceptor.analyzeRiskKind() 通过命令分析得出：
 *   · system —— 目标是系统应用（设置、系统界面、包安装器…）
 *   · bulk   —— 一条命令里批量操作多个应用
 *   · admin  —— 动设备管理员 / 用户 / 设备策略
 *   · normal —— 普通卸载 / 冻结
 */
@Composable
private fun RiskHint(riskKind: String) {
    val text = when (riskKind) {
        "system" -> "⚠️ 目标是系统应用。冻结或卸载「设置」「系统界面」等组件，" +
                "可能导致系统无法正常使用（设置进不去、装不了应用），请谨慎允许。"
        "bulk" -> "⚠️ 这条命令会一次性影响多个应用，属于批量卸载/冻结操作，" +
                "请确认你确实要这样做。"
        "admin" -> "⚠️ 这条命令会修改设备管理员或用户策略，" +
                "属于系统级高敏感操作，可能影响设备安全策略。"
        else -> null
    }
    if (text == null) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.buttonSmall14,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * 「是否允许」确认框：允许一次 / 拒绝。
 */
@Composable
fun CommandGuardConfirmDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    onAllow: () -> Unit,
    // 2026-10-23（用户需求）：「始终允许」——只对「本应用 + 这条指令」生效
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit,
    riskKind: String = "normal",
    riskLevel: String = "medium",
    analysis: String = "中风险：请确认命令来源可信。",
    whitelisted: Boolean = false,
    appIcon: ImageBitmap? = null
) {
    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = AppShape.shapes.cardMedium24,
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标识：本应用（游龙安全护盾）图标
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(AppShape.shapes.iconMedium18)
                    )
                } else {
                    GuardBadge(MaterialTheme.colorScheme.tertiaryContainer)
                }

                Text(
                    text = if (whitelisted) {
                        "指令白名单放行确认"
                    } else {
                        stringResource(R.string.command_guard_confirm_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (whitelisted) {
                        // 用户要求：白名单应用只问「应用名要执行指令是否放行」
                        "白名单应用「$appLabel」要执行指令，是否放行？"
                    } else {
                        stringResource(
                            R.string.command_guard_confirm_body,
                            appLabel,
                            reason
                        )
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 2026-10 第 19 轮（用户需求）：先分析危险程度，再询问用户。
                RiskCard(riskLevel, analysis)

                if (!packageName.isNullOrEmpty()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (command.isNotBlank()) {
                    CommandPreview(command)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DialogButton(
                        text = stringResource(R.string.command_guard_allow_once),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onAllow
                    )
                    // 2026-10-23（用户需求）：新增「始终允许」。
                    // 只记住「本应用 + 这条指令」这一对；同一应用换一条指令仍会询问。
                    // 针对本产品自身/配套应用的冻结、卸载命令由服务端直接拦截，
                    // 不会走到这个弹窗，因此不可能被「始终允许」放行。
                    DialogButton(
                        text = stringResource(R.string.command_guard_allow_always),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = onAllowAlways
                    )
                    DialogButton(
                        text = stringResource(R.string.command_guard_deny),
                        container = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = onDeny
                    )
                }
            }
        }
    }
}

// ======================================================================
// 内置特权「脚本级审批」弹窗（2026-10-24 新增）
// ======================================================================

/**
 * kind=policy 的审批弹窗：内置 Stellar 的 stdin 脚本代理网关在放行整段脚本前，
 * 就「某应用请求使用某受保护能力」征求用户授权。
 *
 * <p>字段来源（服务端 Intent extras）：packageName / command / reason /
 * riskLevel / analysis / scope / scopeLabel / score / script。命令行走
 * [CommandPreview]，stdin 脚本走 [ScriptPreview]（等宽、固定高度、可滚动），
 * 两者都可能为空（例如纯能力申请没有命令行）。
 *
 * <p>三个按钮的答复 token 由服务端定义：拒绝 = "DENY"，允许一次 = "ALLOW"，
 * 始终允许该能力 = "ALLOW_ALWAYS"（见 companion object 中的说明）。
 */
@Composable
private fun PolicyGuardDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    riskLevel: String,
    analysis: String,
    scope: String,
    scopeLabel: String,
    score: Int,
    script: String,
    appIcon: ImageBitmap?,
    onAllow: () -> Unit,
    onAllowAlways: () -> Unit,
    onDeny: () -> Unit
) {
    // 能力中文名以服务端下发的 scopeLabel 为准；缺失时用本地映射兜底，
    // 保证旧服务端 / 字段缺失时也不会显示空白。
    val scopeName = scopeLabel.ifBlank { scopeDisplayName(scope) }

    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = AppShape.shapes.cardMedium24,
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标识：与本应用其它审批弹窗保持一致，用本应用图标；
                // 读不到图标时退回内置盾牌标识。
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(AppShape.shapes.iconMedium18)
                    )
                } else {
                    GuardBadge(MaterialTheme.colorScheme.tertiaryContainer)
                }

                Text(
                    text = stringResource(R.string.command_guard_policy_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                // 「「应用名」请求使用【能力名】能力」
                Text(
                    text = stringResource(
                        R.string.command_guard_policy_request,
                        appLabel,
                        scopeName
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 服务端给出的一句人话（如「应用 X 请求使用【包管理】能力：禁用应用」）。
                if (reason.isNotBlank()) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 风险等级 + 风险说明，沿用现有 RiskCard 视觉。
                RiskCard(riskLevel, analysis)

                // 行为链评分；服务端没给（旧服务端 / 字段缺失）时不显示，避免「评分：0」。
                if (score > 0) {
                    Text(
                        text = stringResource(R.string.command_guard_policy_score, score),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (!packageName.isNullOrEmpty()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 命令行预览（argv 拼成的命令行可能为空）。
                if (command.isNotBlank()) {
                    CommandPreview(command)
                }

                // stdin 脚本预览（可能为空；≤4000 字符，固定高度 + 滚动）。
                if (script.isNotBlank()) {
                    ScriptPreview(script)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DialogButton(
                        text = stringResource(R.string.command_guard_policy_deny),
                        container = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = onDeny
                    )
                    DialogButton(
                        text = stringResource(R.string.command_guard_policy_allow_once),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onAllow
                    )
                    DialogButton(
                        text = stringResource(R.string.command_guard_policy_allow_always),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = onAllowAlways
                    )
                }
            }
        }
    }
}

/**
 * 2026-10-24 新增：stdin 脚本预览框。
 *
 * <p>与 [CommandPreview] 同一套视觉，但脚本可能长达 4000 字符，因此给固定高度
 * （约 8 行）并允许竖向滚动，避免把弹窗撑出屏幕。
 */
@Composable
private fun ScriptPreview(script: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.command_guard_policy_script_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            shape = AppShape.shapes.buttonSmall14,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = script,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

