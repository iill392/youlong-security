// 用行索引把 isOwnServiceBinder 插到 StellarProvider 的 isTrustedBinderSender 之前
const fs = require('fs');
const P = 'E:/anquan/embedded/provider/src/main/kotlin/roro/stellar/StellarProvider.kt';
let lines = fs.readFileSync(P, 'utf8').replace(/\r\n/g, '\n').split('\n');

if (lines.some(l => l.includes('private fun isOwnServiceBinder'))) {
  console.log('已存在，无需插入');
} else {
  let idx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes('private fun isTrustedBinderSender')) { idx = i; break; }
  }
  if (idx < 0) { console.log('!! 找不到 isTrustedBinderSender'); process.exit(1); }

  const helper = [
    '    /**',
    '     * 2026-10 新增：判断一个 Binder 是不是**自家服务端**（IStellarService）。',
    '     *',
    '     * <p>依据是 Binder 的 interface descriptor —— 它由远端 Binder 自己声明，',
    '     * 外部应用无法伪造成我们 AIDL 的 descriptor。用来把「外部应用抢先注入的',
    '     * 冒充者」与「自家服务端」区分开：冒充者必须允许被自家服务端替换，',
    '     * 否则功能会瘫（界面显示「内置特权服务未启动」）。',
    '     */',
    '    private fun isOwnServiceBinder(binder: android.os.IBinder?): Boolean {',
    '        if (binder == null) return false',
    '        return try {',
    '            if (!binder.pingBinder()) return false',
    '            binder.interfaceDescriptor == "com.stellar.server.IStellarService"',
    '        } catch (tr: Throwable) {',
    '            false',
    '        }',
    '    }',
    '',
  ];
  lines.splice(idx, 0, ...helper);
  fs.writeFileSync(P, lines.join('\n'), 'utf8');
  console.log('OK 已插入（原 isTrustedBinderSender 在第 ' + (idx + 1) + ' 行之前）');
}

const t = fs.readFileSync(P, 'utf8');
console.log('isOwnServiceBinder 出现次数: ' + (t.split('isOwnServiceBinder').length - 1));
