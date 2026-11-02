// 用行索引精确插入（避开锚点字符串匹配的缩进/行尾问题）
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
const lines = fs.readFileSync(P, 'utf8').split('\r\n');

// 找 getEnvGoodHint 那一行，再找它后面第一个 "@JavascriptInterface"
let i = lines.findIndex(l => l.includes('public String getEnvGoodHint() {'));
if (i < 0) { console.log('!! 找不到 getEnvGoodHint'); process.exit(1); }
console.log('getEnvGoodHint @ 行 ' + (i + 1));

// 顺手修正该方法签名行的缩进（原为 0 空格）
if (!lines[i].startsWith('        public String getEnvGoodHint() {')) {
  lines[i] = '        public String getEnvGoodHint() {';
  console.log('OK 修正 getEnvGoodHint 缩进');
}

// 找它后面第一个 @JavascriptInterface（即 getEnvironmentWarning 的注解）
let j = -1;
for (let k = i + 1; k < lines.length; k++) {
  if (lines[k].trim() === '@JavascriptInterface') { j = k; break; }
}
if (j < 0) { console.log('!! 找不到下一个 @JavascriptInterface'); process.exit(1); }
console.log('下一个 @JavascriptInterface @ 行 ' + (j + 1));

if (lines.slice(j, j + 6).join('\n').includes('getRootDiagnostics')) {
  console.log('已存在 getRootDiagnostics，无需插入');
} else {
  const block = [
    '',
    '        /**',
    '         * root 判定诊断（2026-10 新增，**仅供排错**，不参与界面判定）。',
    '         *',
    '         * <p>如果还有用户反馈"没 root 却被判定有 root"，在网页控制台执行',
    '         * {@code Android.getRootDiagnostics()}，把返回的 JSON 发回来即可定位：',
    '         * 最终结论、命中了哪个 root 管理器包、哪个 su 路径存在、su -c id 的真实输出。',
    '         */',
    '        @JavascriptInterface',
    '        public String getRootDiagnostics() {',
    '            return MainActivity.this.buildRootDiagnosticsJson();',
    '        }',
    '',
  ];
  lines.splice(j, 0, ...block);
  console.log('OK 已插入 getRootDiagnostics 桥接（' + block.length + ' 行）');
}

fs.writeFileSync(P, lines.join('\r\n'), 'utf8');

// 复查
const t2 = fs.readFileSync(P, 'utf8');
console.log('');
console.log('复查 getRootDiagnostics 出现次数: ' + (t2.split('getRootDiagnostics').length - 1) + '（应为 2：注解方法 + 委托注释）');
console.log('复查 裸LF: ' + (t2.match(/(?<!\r)\n/g) || []).length);
