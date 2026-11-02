const fs = require('fs');
const P = 'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt';
let t = fs.readFileSync(P, 'utf8').replace(/\r\n/g, '\n');

const from = `    fun showBlockedAlert(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int
    ) {`;
const to = `    fun showBlockedAlert(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）
        riskKind: String = "normal"
    ) {`;

const n = t.split(from).length - 1;
if (n === 1) {
  t = t.replace(from, to);
  console.log('OK CG showBlockedAlert 签名加 riskKind');
} else {
  console.log('!! showBlockedAlert 签名匹配 ' + n + ' 次');
}

// 调用处带上 kind
const f2 = `        val request = writeRequest(id, KIND_ALERT, packageName, command, reason)`;
const t2 = `        val request = writeRequest(id, KIND_ALERT, packageName, command, reason, riskKind)`;
const n2 = t.split(f2).length - 1;
if (n2 === 1) { t = t.replace(f2, t2); console.log('OK CG ALERT 的 writeRequest 调用补 riskKind'); }
else console.log('!! ALERT writeRequest 调用匹配 ' + n2 + ' 次');

fs.writeFileSync(P, t, 'utf8');
console.log('riskKind 次数: ' + (t.split('riskKind').length - 1));
