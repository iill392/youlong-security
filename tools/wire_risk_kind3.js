// 统一做法：先归一化行尾为 LF 处理，最后写回 LF（Kotlin 文件用 LF 完全没问题）
// 这样彻底摆脱 CRLF/LF 混合带来的锚点匹配问题。
const fs = require('fs');

function patch(path, edits, label) {
  let src = fs.readFileSync(path, 'utf8');
  // 归一化：CRLF -> LF
  let t = src.replace(/\r\n/g, '\n');
  const hadCrlf = (src.match(/\r\n/g) || []).length;
  console.log('--- ' + label + ' (CRLF=' + hadCrlf + ') ---');

  for (const [from, to, name] of edits) {
    const n = t.split(from).length - 1;
    if (n !== 1) { console.log('  !! ' + name + ' 匹配 ' + n + ' 次'); continue; }
    t = t.replace(from, to);
    console.log('  OK ' + name);
  }
  fs.writeFileSync(path, t, 'utf8');
  console.log('  ' + src.length + ' -> ' + t.length + ' bytes（已统一为 LF）');
}

// ---------------------------------------------------------------- CommandGuard
patch(
  'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt',
  [
    [
      `    fun askForConfirmation(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int
    ): Boolean {`,
      `    fun askForConfirmation(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）
        riskKind: String = "normal"
    ): Boolean {`,
      'CG askForConfirmation 加 riskKind',
    ],
    [
      `        val request = writeRequest(id, KIND_CONFIRM, packageName, command, reason)`,
      `        val request = writeRequest(id, KIND_CONFIRM, packageName, command, reason, riskKind)`,
      'CG writeRequest 调用处补 riskKind',
    ],
    [
      `    private fun writeRequest(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String
    ): String? {`,
      `    private fun writeRequest(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        // 2026-10 新增：风险类型，写进请求文件供弹窗使用
        riskKind: String = "normal"
    ): String? {`,
      'CG writeRequest 签名加 riskKind',
    ],
    [
      `    private fun launch(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        requestPath: String,
        userId: Int
    ): Boolean {`,
      `    private fun launch(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        requestPath: String,
        userId: Int,
        // 2026-10 新增：风险类型，放进 Intent 给弹窗
        riskKind: String = "normal"
    ): Boolean {`,
      'CG launch 签名加 riskKind',
    ],
    [
      `                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)`,
      `                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)
                // 2026-10 新增：风险类型，弹窗据此给出针对性说明
                .putExtra("riskKind", riskKind)`,
      'CG Intent 加 riskKind',
    ],
  ],
  'CommandGuard'
);

// ---------------------------------------------------------------- ProcessManager
patch(
  'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/ProcessManager.kt',
  [
    [
      `                CommandGuard.showBlockedAlert(callerPackage, flat, decision.reason, userId)`,
      `                CommandGuard.showBlockedAlert(callerPackage, flat, decision.reason, userId, decision.kind)`,
      'PM Block 传入 kind',
    ],
    [
      `                val allowed = CommandGuard.askForConfirmation(
                    callerPackage, flat, decision.reason, userId
                )`,
      `                val allowed = CommandGuard.askForConfirmation(
                    callerPackage, flat, decision.reason, userId, decision.kind
                )`,
      'PM NeedConfirm 传入 kind',
    ],
  ],
  'ProcessManager'
);

// ---------------------------------------------------------------- 复查
const cg = fs.readFileSync('E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt', 'utf8');
const pm = fs.readFileSync('E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/ProcessManager.kt', 'utf8');
console.log('');
console.log('复查 CommandGuard riskKind 次数: ' + (cg.split('riskKind').length - 1) + '（期望 6）');
console.log('复查 ProcessManager decision.kind 次数: ' + (pm.split('decision.kind').length - 1) + '（期望 2）');
