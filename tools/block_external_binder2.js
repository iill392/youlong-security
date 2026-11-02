// 行索引插入：给两个 provider 的 handleSendBinder 加调用方校验
// （绕开 JS 模板对 Kotlin ${...} 的插值问题）
const fs = require('fs');

const QUARD_LINES = [
  '        // ==================================================================',
  '        // 2026-10 安全加固（用户需求）：只接受**内置特权服务端**投递的 Binder。',
  '        // ------------------------------------------------------------------',
  '        // 本 provider 是 exported 的，任何应用都能调 sendBinder。若不校验，',
  '        // 外部 Stellar / Shizuku 应用就能把我们的 binder 换成它自己的 binder，',
  '        // 表现为"外部软件也能授权"。',
  '        //',
  '        // 我们的服务端用 getContentProviderExternal + IContentProvider.call',
  '        // 投递，在这里表现为服务端进程的 uid（root=0 / system=1000 / shell=2000）；',
  '        // 普通第三方应用是 10xxx，一律拒绝。',
  '        // ==================================================================',
  '        if (!isTrustedBinderSender()) {',
  '            android.util.Log.e(',
  '                tag,',
  '                "拒绝来自不可信来源的 Binder 注入：callingUid=" +',
  '                    android.os.Binder.getCallingUid()',
  '            )',
  '            return',
  '        }',
  '',
];

const HELPER_LINES = [
  '    /**',
  '     * 2026-10 新增：Binder 注入来源校验。',
  '     *',
  '     * <p>只允许内置特权服务端（root=0 / system=1000 / shell=2000）投递 Binder。',
  '     * 普通应用（uid 10xxx）一律拒绝 —— 防止外部 Stellar / Shizuku 应用通过',
  '     * exported 的 provider 把我们的 binder 换成它自己的，从而"看起来授权成功"。',
  '     */',
  '    private fun isTrustedBinderSender(): Boolean {',
  '        return try {',
  '            val uid = android.os.Binder.getCallingUid()',
  '            uid == 0 || uid == 1000 || uid == 2000',
  '        } catch (tr: Throwable) {',
  '            false',
  '        }',
  '    }',
  '',
];

function patch(path, tagName) {
  let lines = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n').split('\n');

  const findLine = (re, from = 0) => {
    for (let i = from; i < lines.length; i++) if (re.test(lines[i])) return i;
    return -1;
  };

  // 1) handleSendBinder 函数体第一行之前插入校验
  const h = findLine(/private fun handleSendBinder\(extras: Bundle\) \{/);
  if (h < 0) { console.log('!! ' + tagName + ' 找不到 handleSendBinder'); return; }
  // 替换 tag 变量名：ShizukuProvider/StellarProvider 里常量都叫 TAG，直接用 TAG
  const guard = QUARD_LINES.map(l => l.replace(/\btag\b/g, 'TAG'));
  lines.splice(h + 1, 0, ...guard);
  console.log('OK ' + tagName + ' handleSendBinder 插入调用方校验');

  // 2) 新增 isTrustedBinderSender（放在 companion object 之前）
  const c = findLine(/^\s*companion object \{/);
  if (c < 0) { console.log('!! ' + tagName + ' 找不到 companion object'); return; }
  lines.splice(c, 0, ...HELPER_LINES);
  console.log('OK ' + tagName + ' 新增 isTrustedBinderSender()');

  fs.writeFileSync(path, lines.join('\n'), 'utf8');
  console.log('   ' + lines.length + ' 行');
}

patch('E:/anquan/embedded/provider/src/main/kotlin/roro/stellar/StellarProvider.kt', 'StellarProvider');
console.log('');
patch('E:/anquan/embedded/shizuku-api/src/main/kotlin/roro/stellar/shizuku/ShizukuProvider.kt', 'ShizukuProvider');
console.log('');
for (const f of [
  'E:/anquan/embedded/provider/src/main/kotlin/roro/stellar/StellarProvider.kt',
  'E:/anquan/embedded/shizuku-api/src/main/kotlin/roro/stellar/shizuku/ShizukuProvider.kt',
]) {
  const t = fs.readFileSync(f, 'utf8');
  console.log(f.split('/').pop() + ': isTrustedBinderSender=' + (t.split('isTrustedBinderSender').length - 1) + ' 处');
}
