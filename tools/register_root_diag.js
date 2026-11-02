// 注册 getRootDiagnostics 桥接 + 修 getEnvGoodHint 的缩进（之前补丁留下的）
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;
const crlf = s => s.replace(/\r?\n/g, '\r\n');

function rep(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

// 1) 修缩进 + 追加诊断桥接（用 getEnvGoodHint 那段做锚点，注意它缩进不整齐）
rep(
`        @JavascriptInterface
public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }

        @JavascriptInterface
        public String getEnvironmentWarning() {`,
`        @JavascriptInterface
        public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }

        /**
         * root 判定诊断（2026-10 新增，**仅供排错**，不参与界面判定）。
         *
         * <p>如果还有用户反馈"没 root 却被判定有 root"，在网页控制台执行
         * {@code Android.getRootDiagnostics()}，把返回的 JSON 发回来即可定位：
         * 最终结论、命中了哪个 root 管理器包、哪个 su 路径存在、su -c id 的真实输出。
         */
        @JavascriptInterface
        public String getRootDiagnostics() {
            return MainActivity.this.buildRootDiagnosticsJson();
        }

        @JavascriptInterface
        public String getEnvironmentWarning() {`,
'1) 注册 getRootDiagnostics + 修缩进');

// 2) 清掉之前误留的占位方法
rep(
`        @SuppressWarnings("unused")
        private boolean isPackageInstalledUnusedPlaceholder() {
            return false;
        }

`,
`        // 2026-10 清掉：之前补丁留下的占位方法已删除，root 判定统一在外层。
`,
'2) 删除误加的占位方法');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');

// 复查
const t2 = fs.readFileSync(P, 'utf8');
console.log('复查：');
console.log('  getRootDiagnostics 桥接数: ' + (t2.split('getRootDiagnostics').length - 1));
console.log('  buildRootDiagnosticsJson 数: ' + (t2.split('buildRootDiagnosticsJson').length - 1));
console.log('  占位方法残留: ' + (t2.split('isPackageInstalledUnusedPlaceholder').length - 1));
