// ==========================================================================
// 安全加固：禁止**任何外部应用**通过 provider 向我们注入 Binder
//
// 漏洞：StellarProvider / ShizukuProvider 都是 exported 的，且都实现了
//       call("sendBinder", ...) —— 任何应用都能调它，把我们 App 的
//       Stellar.binder 换成**它自己的** binder。于是外部 Stellar / Shizuku
//       就能"授权"我们（用户看到的现象）。
//
// 判别点：我们的服务端是通过
//       ActivityManagerApis.getContentProviderExternal(...)
//       + IContentProvider.call(...) 投递 Binder 的，
//     它在 ContentProvider.call() 里表现为**服务端进程的 uid**：
//       · 非 root 激活（ADB / 无线调试）→ uid = 2000 (shell)
//       · Root 激活                   → uid = 0    (root)
//     而普通第三方应用是 10xxx。所以在 handleSendBinder 里校验
//     Binder.getCallingUid() 就能把它们挡掉。
//
// 例外：无线调试配对时，ADB 路径下也可能由 adbd 之外的少量系统身份参与，
//       所以白名单放宽到 {0, 1000(system), 2000(shell)}，仍能挡掉所有 10xxx 应用。
// ==========================================================================
const fs = require('fs');

function patch(path, label) {
  let t = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  const orig = t.length;

  // 1) 在 handleSendBinder 开头插入调用方校验
  const anchors = [
    // StellarProvider
    `    private fun handleSendBinder(extras: Bundle) {
        Log.i(TAG, "收到 Stellar Binder：current=${Stellar.binder}, alive=${pingBinder()}")`,
    // ShizukuProvider（缩进可能不同，下面用宽松匹配兜底）
  ];

  let done = false;
  for (const a of anchors) {
    if (t.includes(a)) {
      t = t.replace(a,
`    private fun handleSendBinder(extras: Bundle) {
        // ==================================================================
        // 2026-10 安全加固（用户需求）：只接受**内置特权服务端**投递的 Binder。
        // ------------------------------------------------------------------
        // 本 provider 是 exported 的，任何应用都能调 sendBinder。若不校验，
        // 外部 Stellar / Shizuku 应用就能把 Stellar.binder 换成它自己的 binder，
        // 从而"看起来授权成功了"。
        //
        // 我们的服务端用 getContentProviderExternal + IContentProvider.call 投递，
        // 在这里表现为服务端进程的 uid（shell=2000 / root=0 / system=1000）；
        // 普通第三方应用是 10xxx，一律拒绝。
        // ==================================================================
        if (!isTrustedBinderSender()) {
            Log.e(TAG, "拒绝来自不可信来源的 Binder 注入：callingUid=" +
                android.os.Binder.getCallingUid())
            return
        }

        Log.i(TAG, "收到 Stellar Binder：current=\${Stellar.binder}, alive=\${pingBinder()}")`);
      done = true;
      console.log('OK ' + label + ' 插入调用方校验');
      break;
    }
  }
  if (!done) {
    // 宽松兜底：匹配 handleSendBinder 函数头
    const re = /(private fun handleSendBinder\(extras: Bundle\) \{\n)/;
    const m = t.match(re);
    if (m) {
      t = t.replace(re,
`$1        // 2026-10 安全加固：只接受内置特权服务端投递的 Binder（见文件内说明）。
        // 本 provider 是 exported 的，不校验的话任何应用都能注入自己的 binder，
        // 表现为"外部 Stellar / Shizuku 也能授权"。服务端 uid 为 shell(2000)/root(0)，
        // 普通应用是 10xxx，一律拒绝。
        if (!isTrustedBinderSender()) {
            Log.e(TAG, "拒绝来自不可信来源的 Binder 注入：callingUid=" +
                android.os.Binder.getCallingUid())
            return
        }

`);
      console.log('OK ' + label + ' 插入调用方校验（宽松匹配）');
      done = true;
    }
  }
  if (!done) { console.log('!! ' + label + ' 未找到 handleSendBinder'); return; }

  // 2) 在 companion object 里加 isTrustedBinderSender()
  const compRe = /(    companion object \{\n)(        private const val TAG = "[^"]+"\n)/;
  if (compRe.test(t)) {
    t = t.replace(compRe, `$1$2
        /**
         * 2026-10 新增：Binder 注入来源校验。
         *
         * <p>只允许内置特权服务端（root / system / shell）投递 Binder，
         * 其余 uid（普通应用 10xxx）一律拒绝 —— 防止外部 Stellar / Shizuku
         * 应用通过 exported 的 provider 把我们的 Stellar.binder 换成它自己的。
         */
        private fun isTrustedBinderSender(): Boolean {
            return try {
                val uid = android.os.Binder.getCallingUid()
                uid == 0 || uid == 1000 || uid == 2000
            } catch (tr: Throwable) {
                false
            }
        }
`);
    console.log('OK ' + label + ' 新增 isTrustedBinderSender()');
  } else {
    // 兜底：插到 handleSendBinder 之前（顶层 private fun）
    t = t.replace(/(    private fun handleSendBinder\()/,
`    /**
     * 2026-10 新增：Binder 注入来源校验。
     * 只允许内置特权服务端（root=0 / system=1000 / shell=2000）投递 Binder。
     */
    private fun isTrustedBinderSender(): Boolean {
        return try {
            val uid = android.os.Binder.getCallingUid()
            uid == 0 || uid == 1000 || uid == 2000
        } catch (tr: Throwable) {
            false
        }
    }

$1`);
    console.log('OK ' + label + ' 新增 isTrustedBinderSender()（兜底位置）');
  }

  fs.writeFileSync(path, t, 'utf8');
  console.log('   ' + orig + ' -> ' + t.length + ' bytes');
}

patch('E:/anquan/embedded/provider/src/main/kotlin/roro/stellar/StellarProvider.kt', 'StellarProvider');
console.log('');
patch('E:/anquan/embedded/shizuku-api/src/main/kotlin/roro/stellar/shizuku/ShizukuProvider.kt', 'ShizukuProvider');
