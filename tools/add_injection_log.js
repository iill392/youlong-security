// ==========================================================================
// 加固增强：
//   1) provider 里的拦截记录改成**可查询**（release 会把 Log 裁掉，看不到证据）
//   2) 记录内容：拒绝次数、最近一次被拒的 callingUid、时间
//   3) 通过一个静态可读的字符串暴露，供说明与排错
// ==========================================================================
const fs = require('fs');

const HELPER = [
  '',
  '        /**',
  '         * 2026-10 新增：被拒绝的 Binder 注入记录（排错/实证用）。',
  '         *',
  '         * <p>release 构建里 android.util.Log 会被 R8 裁掉，光靠 logcat 看不到',
  '         * "拒绝外部注入"这件事，所以在这里留一份可读记录。',
  '         */',
  '        @JvmStatic',
  '        val rejectedInjectionInfo: String',
  '            get() = rejectedInfo',
  '',
  '        private var rejectedCount = 0',
  '        private var rejectedInfo = "无"',
  '',
  '        private fun recordRejectedInjection(uid: Int) {',
  '            rejectedCount++',
  '            rejectedInfo = "已拒绝 " + rejectedCount + " 次，最近一次来源 uid=" + uid',
  '        }',
].join('\n');

function patch(path, label) {
  let t = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');
  const orig = t.length;

  // 1) 把校验块里的日志替换成「记录 + 日志」
  const from = [
    '        if (!isTrustedBinderSender()) {',
    '            android.util.Log.e(',
    '                TAG,',
    '                "拒绝来自不可信来源的 Binder 注入：callingUid=" +',
    '                    android.os.Binder.getCallingUid()',
    '            )',
    '            return',
    '        }',
  ].join('\n');

  const to = [
    '        if (!isTrustedBinderSender()) {',
    '            val badUid = android.os.Binder.getCallingUid()',
    '            recordRejectedInjection(badUid)',
    '            android.util.Log.e(',
    '                TAG,',
    '                "拒绝来自不可信来源的 Binder 注入：callingUid=" + badUid',
    '            )',
    '            return',
    '        }',
  ].join('\n');

  if (t.includes(from)) {
    t = t.replace(from, to);
    console.log('OK ' + label + ' 校验块改为记录 + 日志');
  } else {
    console.log('!! ' + label + ' 校验块未匹配（可能已改过），跳过');
  }

  // 2) 在 companion object 里加记录字段
  const compRe = /(\n    companion object \{\n)(\s*private const val TAG = "[^"]+"\n)/;
  if (compRe.test(t) && !t.includes('rejectedInjectionInfo')) {
    t = t.replace(compRe, `$1$2${HELPER}\n`);
    console.log('OK ' + label + ' 新增 rejectedInjectionInfo 记录');
  } else {
    console.log('!! ' + label + ' companion/TAG 未匹配或已有记录');
  }

  fs.writeFileSync(path, t, 'utf8');
  console.log('   ' + orig + ' -> ' + t.length + ' bytes');
}

patch('E:/anquan/embedded/provider/src/main/kotlin/roro/stellar/StellarProvider.kt', 'StellarProvider');
console.log('');
patch('E:/anquan/embedded/shizuku-api/src/main/kotlin/roro/stellar/shizuku/ShizukuProvider.kt', 'ShizukuProvider');
console.log('');

// 复查
for (const f of [
  'E:/anquan/embedded/provider/src/main/kotlin/roro/stellar/StellarProvider.kt',
  'E:/anquan/embedded/shizuku-api/src/main/kotlin/roro/stellar/shizuku/ShizukuProvider.kt',
]) {
  const t = fs.readFileSync(f, 'utf8');
  console.log(f.split('/').pop() + ': recordRejectedInjection=' + (t.split('recordRejectedInjection').length - 1)
    + ' rejectedInjectionInfo=' + (t.split('rejectedInjectionInfo').length - 1));
}
