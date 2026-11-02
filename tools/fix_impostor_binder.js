// ==========================================================================
// 修复加固带来的副作用：
//   外部管理器抢先注入后，Stellar.binder 是**冒充者**；
//   此时自家服务端的合法 binder 投递进来，却被
//     if (pingBinder()) { 忽略重复发送; return }
//   当成"重复"挡掉了 → 界面显示「内置特权服务未启动」，功能瘫掉。
//
// 修法：判断"已有的 binder 到底是不是自家服务端"，
//   不是 → 允许被可信来源替换（钉掉冒充者）
//   是   → 维持原来的"忽略重复"语义
//
// 自家服务端的识别：Binder 的 interface descriptor 必须是我们自己的 AIDL
//   com.stellar.server.IStellarService  —— 别的 App 无法伪造这个 descriptor，
//   因为 descriptor 来自 Stellar.binder 这个 IStellarService 代理本身。
// ==========================================================================
const fs = require('fs');

function patch(path, label, binderVar, pingExpr) {
  let t = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  const orig = t.length;

  // 1) 找到 "已有可用 binder 就忽略" 的块并改写
  const oldBlock = `        if (pingBinder()) {
            Log.w(TAG, "已有可用 Stellar Binder，忽略重复发送")
            return
        }`;
  const oldBlockShizuku = `        if (ShizukuCompat.pingBinder()) {
            Log.w(TAG, "已有可用 Shizuku Binder，忽略重复发送")
            return
        }`;

  if (t.includes(oldBlock) || t.includes(oldBlockShizuku)) {
    const isStellar = t.includes(oldBlock);
    const block = isStellar ? oldBlock : oldBlockShizuku;
    const newBlock = `        // ==================================================================
        // 2026-10 修正：已有 binder 时不能无条件"忽略重复发送"。
        // ------------------------------------------------------------------
        // 若当前 binder 是**外部应用抢先注入的冒充者**，必须允许自家服务端
        // 把它换掉，否则功能会瘫（界面显示"内置特权服务未启动"）。
        // 只有确认当前 binder **就是自家服务端**时才忽略重复。
        // ==================================================================
        if (${isStellar ? 'pingBinder()' : 'ShizukuCompat.pingBinder()'}) {
            if (isOwnServiceBinder(${isStellar ? 'Stellar.binder' : 'ShizukuCompat.binder'})) {
                Log.w(TAG, "已有自家服务端 Binder，忽略重复发送")
                return
            }
            Log.w(TAG, "当前 Binder 非自家服务端（疑似外部注入），将用可信来源替换")
        }`;
    t = t.replace(block, newBlock);
    console.log('OK ' + label + ' 重复发送逻辑改为"识别冒充者"');
  } else {
    console.log('!! ' + label + ' 未找到"忽略重复发送"块');
  }

  // 2) 加 isOwnServiceBinder 辅助函数（放在 isTrustedBinderSender 之前）
  const helper = `    /**
     * 2026-10 新增：判断一个 Binder 是不是**自家服务端**（IStellarService）。
     *
     * <p>依据是 Binder 的 interface descriptor —— 它由远端 Binder 自己声明，
     * 外部应用无法伪造成我们 AIDL 的 descriptor（那需要真的实现同一接口）。
     * 用来把"外部应用抢先注入的冒充者"和"自家服务端"区分开。
     */
    private fun isOwnServiceBinder(binder: android.os.IBinder?): Boolean {
        if (binder == null) return false
        return try {
            if (!binder.pingBinder()) return false
            binder.interfaceDescriptor == "com.stellar.server.IStellarService"
        } catch (tr: Throwable) {
            false
        }
    }

`;
  if (!t.includes('isOwnServiceBinder') && t.includes('    private fun isTrustedBinderSender')) {
    t = t.replace('    private fun isTrustedBinderSender', helper + '    private fun isTrustedBinderSender');
    console.log('OK ' + label + ' 新增 isOwnServiceBinder()');
  } else if (!t.includes('isOwnServiceBinder')) {
    console.log('!! ' + label + ' 找不到 isTrustedBinderSender 作为插入锚点');
  } else {
    console.log('SKIP ' + label + ' 已有 isOwnServiceBinder');
  }

  fs.writeFileSync(path, t, 'utf8');
  console.log('   ' + orig + ' -> ' + t.length + ' bytes');
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
  console.log(f.split('/').pop() + ': isOwnServiceBinder=' + (t.split('isOwnServiceBinder').length - 1));
}
