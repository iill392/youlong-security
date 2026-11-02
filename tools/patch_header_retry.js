// 补丁：确保顶部标题栏被删除（幂等，可重复执行）
const fs = require('fs');
const P = 'E:/anquan/app/src/main/assets/index.html';
let t = fs.readFileSync(P, 'utf8');

const RE = /[ \t]*<div class="header">\s*<h1>游龙安全护盾<span class="ver">beta<\/span><\/h1>\s*<\/div>\s*/;

const n = (t.match(new RegExp(RE.source, 'g')) || []).length;
console.log('匹配到标题栏 ' + n + ' 处');

if (n > 0) {
  t = t.replace(RE,
`  <!-- 2026-10 改动（用户需求）：删除顶部「游龙安全护盾」标题栏。
       原为：<div class="header"><h1>游龙安全护盾<span class="ver">beta</span></h1></div>
       删除原因：页面顶部已由系统任务栏展示应用名，这里再放一个大标题属重复，
       且用户明确要求去掉。 -->
`);
  fs.writeFileSync(P, t, 'utf8');
  console.log('已删除并写入');
} else {
  console.log('已无标题栏，无需处理');
}

// 复查
const t2 = fs.readFileSync(P, 'utf8');
console.log('复查 <h1>游龙安全护盾 出现次数: ' + ((t2.match(/<h1>游龙安全护盾/g) || []).length));
