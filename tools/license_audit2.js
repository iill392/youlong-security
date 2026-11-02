// 合规自查 v2：按「上游文件是否被改过」逐个核对声明
// 判定指纹：品牌名、包名、资源 ID、我们删/加过的东西
const fs = require('fs');
const path = require('path');

const DECL = /本工程(修改声明|改动|新增|删除)/;
const MARK = [
  'YouLong SafeADB', '遊龍安全ADB', '游龙安全ADB', '游龍安全ADB', 'ADB护盾', 'ADB 護盾',
  'com.youlong.hd', '游龙', '游龍', '护盾',
  'adb_shield_apps', 'permission_use_adb_shield', 'starting_adb_shield_service',
  'COLOR_MODE', 'CommandGuard', 'ic_notification_shield', 'ShieldMark',
  'color_mode_brand', 'open_source_licenses', 'command_guard',
];

function walk(dir, out, exts) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory() && e.name === 'build') continue;   // 跳过构建中间件
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out, exts);
    else if (exts.some(x => e.name.endsWith(x))) out.push(p);
  }
  return out;
}

const files = walk('E:/anquan/embedded', [], ['.kt', '.xml', '.aidl']);
const missing = [];
const ok = [];
let scanned = 0;

for (const f of files) {
  if (!f.includes(path.sep + 'src' + path.sep)) continue;   // 只看 src 下的真实源文件
  scanned++;
  const t = fs.readFileSync(f, 'utf8');
  const marks = MARK.filter(m => t.includes(m));
  if (marks.length === 0) continue;
  const rel = f.replace(/\\/g, '/').replace('E:/anquan/embedded/', '');
  if (DECL.test(t)) ok.push(rel);
  else missing.push(rel + '   [' + marks.slice(0, 4).join(',') + ']');
}

console.log('src 下扫描: ' + scanned + ' 个文件');
console.log('');
console.log('=== 缺 MPL 声明的文件 (' + missing.length + ') ===');
missing.forEach(x => console.log('  !! ' + x));
console.log('');
console.log('=== 已带声明 (' + ok.length + ') ===');
ok.forEach(x => console.log('  OK ' + x));

// 单独列出所有 strings.xml / themes.xml 的状态（品牌名改动集中在这里）
console.log('');
console.log('=== 各语言 strings.xml / themes.xml 状态 ===');
const resFiles = walk('E:/anquan/embedded/manager/src/main/res', [], ['.xml']);
for (const f of resFiles) {
  const t = fs.readFileSync(f, 'utf8');
  const hasBrand = MARK.some(m => t.includes(m));
  if (!hasBrand) continue;
  const rel = f.replace(/\\/g, '/').replace('E:/anquan/embedded/manager/src/main/res/', '');
  console.log('  ' + (DECL.test(t) ? 'OK  ' : '!!  ') + rel);
}
