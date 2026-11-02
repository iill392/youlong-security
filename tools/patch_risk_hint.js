// 确认弹窗按 riskKind 给出针对性风险说明
const fs = require('fs');
const P = 'E:/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/authorization/CommandGuardActivity.kt';
let t = fs.readFileSync(P, 'utf8').replace(/\r\n/g, '\n');
const orig = t.length;

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// 1) 读取 riskKind
rep(
  `        val reason = intent.getStringExtra("reason") ?: ""
        replyId = id`,
  `        val reason = intent.getStringExtra("reason") ?: ""
        // 2026-10 新增：服务端命令分析得出的风险类型
        // system=动系统应用 / bulk=批量操作 / admin=设备管理员·策略 / normal=普通
        val riskKind = intent.getStringExtra("riskKind") ?: "normal"
        replyId = id`,
  '1) 读取 riskKind');

// 2) 传给确认弹窗
rep(
  `                    CommandGuardConfirmDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,`,
  `                    CommandGuardConfirmDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,
                        riskKind = riskKind,`,
  '2) 传给确认弹窗');

// 3) 弹窗签名加 riskKind
rep(
  `fun CommandGuardConfirmDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {`,
  `fun CommandGuardConfirmDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    riskKind: String = "normal"
) {`,
  '3) 弹窗签名加 riskKind');

// 4) 插入风险提示块（放在「谁尝试了什么」下面、包名上面）
rep(
  `                Text(
                    text = stringResource(
                        R.string.command_guard_confirm_body,
                        appLabel,
                        reason
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )`,
  `                Text(
                    text = stringResource(
                        R.string.command_guard_confirm_body,
                        appLabel,
                        reason
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 2026-10 新增（用户需求）：按命令分析结果给出针对性的风险说明，
                // 让用户明白"这次到底危险在哪"，而不是干巴巴问一句是否允许。
                RiskHint(riskKind)`,
  '4) 插入风险提示');

// 5) 新增 RiskHint 组件
rep(
  `/**
 * 「是否允许」确认框：允许一次 / 拒绝。
 */`,
  `/**
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
 */`,
  '5) 新增 RiskHint 组件');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
