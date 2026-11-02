// 合规自查：遍历 embedded 下所有上游派生文件，找出
//   ① 含「本项目指纹」但没有 MPL-2.0 修改声明 的文件
//   ② 列出所有已带声明的文件
const fs = require('fs');
const path = require('path');

const ROOT = 'E:/anquan/embedded';
const PATS = ['youlong', '游龙', '游龍', '护盾', 'command_guard',
  'ic_notification_shield', 'COLOR_MODE', 'CommandGuard', 'ShieldMark'];
const DECL = /本工程(修改声明|改动|新增|删除)/;

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (/\.(kt|xml|aidl)$/.test(e.name)) out.push(p);
  }
  return out;
}

const files = walk(ROOT, []);
const noDecl = [];
const withDecl = [];
let hits = 0;

for (const f of files) {
  const t = fs.readFileSync(f, 'utf8');
  const marks = PATS.filter(p => t.includes(p));
  if (marks.length === 0) continue;
  hits++;
  const rel = f.replace(/\\/g, '/').replace('E:/anquan/embedded/', '');
  if (DECL.test(t)) withDecl.push(rel + '  [' + marks.join(',') + ']');
  else noDecl.push(rel + '  [' + marks.join(',') + ']');
}

console.log('扫描文件总数: ' + files.length);
console.log('含指纹文件数: ' + hits);
console.log('');
console.log('=== 缺少 MPL 修改声明的文件 (' + noDecl.length + ') ===');
noDecl.forEach(x => console.log('  !! ' + x));
console.log('');
console.log('=== 已带声明的文件 (' + withDecl.length + ') ===');
withDecl.forEach(x => console.log('  OK ' + x));
