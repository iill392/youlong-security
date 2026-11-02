// ==========================================================================
// 把命令分析的 kind 一路传到弹窗，并按风险类型给出针对性说明
// ==========================================================================
const fs = require('fs');
const crlf = s => s.replace(/\r?\n/g, '\r\n');

// ---------------------------------------------------------------- 1) ProcessManager
const PM = 'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/ProcessManager.kt';
let pm = fs.readFileSync(PM, 'utf8');
function repPm(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = pm.split(f).length - 1;
  if (n !== 1) { console.log('!! PM ' + label + ' 匹配 ' + n + ' 次'); return false; }
  pm = pm.replace(f, g); console.log('OK PM ' + label); return true;
}

repPm(
`            is InterceptDecision.NeedConfirm -> {
                val allowed = CommandGuard.askForConfirmation(
                    callerPackage, flat, decision.reason, userId
                )`,
`            is InterceptDecision.NeedConfirm -> {
                val allowed = CommandGuard.askForConfirmation(
                    callerPackage, flat, decision.reason, userId, decision.kind
                )`,
'1) 传入 kind');

fs.writeFileSync(PM, pm, 'utf8');

// ---------------------------------------------------------------- 2) CommandGuard
const CG = 'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt';
let cg = fs.readFileSync(CG, 'utf8');
function repCg(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = cg.split(f).length - 1;
  if (n !== 1) { console.log('!! CG ' + label + ' 匹配 ' + n + ' 次'); return false; }
  cg = cg.replace(f, g); console.log('OK CG ' + label); return true;
}

// 2a) askForConfirmation 加 kind 参数
repCg(
`    fun askForConfirmation(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int
    ): Boolean {
        val id = nextId()
        val request = writeRequest(id, KIND_CONFIRM, packageName, command, reason)`,
`    fun askForConfirmation(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        riskKind: String = "normal"
    ): Boolean {
        val id = nextId()
        val request = writeRequest(id, KIND_CONFIRM, packageName, command, reason, riskKind)`,
'2a) askForConfirmation 加 riskKind');

// 2b) launch 调用带上 riskKind
repCg(
`        if (!launch(id, KIND_CONFIRM, packageName, command, reason, request, userId)) {`,
`        if (!launch(id, KIND_CONFIRM, packageName, command, reason, request, userId, riskKind)) {`,
'2b) confirm 的 launch 带 riskKind');

// 2c) writeRequest 加 riskKind 字段
repCg(
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
        riskKind: String = "normal"
    ): String? {`,
'2c) writeRequest 加 riskKind 形参');

repCg(
`                append("reason=").append(reason.replace('\\n', ' ')).append('\\n')`,
`                append("reason=").append(reason.replace('\\n', ' ')).append('\\n')
                // 2026-10 新增：命令分析得出的风险类型，供弹窗给出针对性说明
                append("riskKind=").append(riskKind).append('\\n')`,
'2d) 写入 riskKind 字段');

// 2e) launch 加 riskKind 形参并放进 Intent
repCg(
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
        riskKind: String = "normal"
    ): Boolean {`,
'2e) launch 加 riskKind 形参');

repCg(
`                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)`,
`                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)
                // 2026-10 新增：风险类型，弹窗据此给出针对性说明
                .putExtra("riskKind", riskKind)`,
'2f) Intent 带 riskKind');

fs.writeFileSync(CG, cg, 'utf8');
console.log('\nProcessManager ' + pm.length + ' bytes, CommandGuard ' + cg.length + ' bytes');
