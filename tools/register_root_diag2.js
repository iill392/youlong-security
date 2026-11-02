// 1) 把 MainActivity.java 统一成 CRLF（消除混合行尾，避免后续锚点匹配失败）
// 2) 注册 getRootDiagnostics 桥接 + 修 getEnvGoodHint 缩进
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

const crlfCount = (t.match(/\r\n/g) || []).length;
const loneLf = (t.match(/(?<!\r)\n/g) || []).length;
console.log('归一前: CRLF=' + crlfCount + ' 裸LF=' + loneLf);

// 先全部转成 LF，再统一转成 CRLF
t = t.replace(/\r\n/g, '\n').replace(/\n/g, '\r\n');
console.log('归一后: CRLF=' + (t.match(/\r\n/g) || []).length
  + ' 裸LF=' + (t.match(/(?<!\r)\n/g) || []).length);

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

const A = `        @JavascriptInterface\r
        public String getEnvGoodHint() {\r
            return MainActivity.this.buildEnvGoodHintJson();\r
        }\r
\r
        @JavascriptInterface\r
        public String getEnvironmentWarning() {`;

const B = `        @JavascriptInterface\r
        public String getEnvGoodHint() {\r
            return MainActivity.this.buildEnvGoodHintJson();\r
        }\r
\r
        /**\r
         * root 判定诊断（2026-10 新增，**仅供排错**，不参与界面判定）。\r
         *\r
         * <p>如果还有用户反馈"没 root 却被判定有 root"，在网页控制台执行\r
         * {@code Android.getRootDiagnostics()}，把返回的 JSON 发回来即可定位：\r
         * 最终结论、命中了哪个 root 管理器包、哪个 su 路径存在、su -c id 的真实输出。\r
         */\r
        @JavascriptInterface\r
        public String getRootDiagnostics() {\r
            return MainActivity.this.buildRootDiagnosticsJson();\r
        }\r
\r
        @JavascriptInterface\r
        public String getEnvironmentWarning() {`;

rep(A, B, '注册 getRootDiagnostics + 修缩进');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
const t2 = fs.readFileSync(P, 'utf8');
console.log('复查 getRootDiagnostics 数: ' + (t2.split('getRootDiagnostics').length - 1));
console.log('复查 裸LF 数: ' + (t2.match(/(?<!\r)\n/g) || []).length);
