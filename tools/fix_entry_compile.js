// 修 3 处：
//   1) 外层实现里 JSON 字段的转义写错：应写 \" 而我写成了 \\"（会生成非法的 Java 字面量）
//   2) ApplicationInfo 未 import（用全限定名最省事）
//   3) onRequestPermissionsResult 里仍调用内部类的 getEntryRequirements()
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

// ---- 1) 修 JSON 转义：Java 源码里应当是 \" 而不是 \\"
const bad = [
  'missing.append("\\\\"notification\\\\""); first = false;',
  'missing.append(first ? "\\\\"applist\\\\"" : ",\\\\"applist\\\\"");',
  'return "{\\\\"notification\\\\":" + notif',
  '+ ",\\\\"applist\\\\":" + apps',
  '+ ",\\\\"ok\\\\":" + (notif && apps)',
  '+ ",\\\\"missing\\\\":" + missing + "}";',
  'return "{\\\\"show\\\\":" + show + ",\\\\"root\\\\":" + root',
  '+ ",\\\\"dhizuku\\\\":" + dhizuku + "}";',
];
const good = [
  'missing.append("\\"notification\\""); first = false;',
  'missing.append(first ? "\\"applist\\"" : ",\\"applist\\"");',
  'return "{\\"notification\\":" + notif',
  '+ ",\\"applist\\":" + apps',
  '+ ",\\"ok\\":" + (notif && apps)',
  '+ ",\\"missing\\":" + missing + "}";',
  'return "{\\"show\\":" + show + ",\\"root\\":" + root',
  '+ ",\\"dhizuku\\":" + dhizuku + "}";',
];
let fixedEscape = 0;
for (let i = 0; i < bad.length; i++) {
  const b = bad[i].replace(/\n/g, '\r\n');
  const g = good[i].replace(/\n/g, '\r\n');
  const n = t.split(b).length - 1;
  if (n === 0) { console.log('  (未匹配) ' + bad[i].slice(0, 46)); continue; }
  t = t.split(b).join(g);
  fixedEscape += n;
  console.log('  OK 转义修正 x' + n + ': ' + bad[i].slice(0, 42));
}
console.log('1) JSON 转义共修 ' + fixedEscape + ' 处');

// ---- 2) ApplicationInfo 用全限定名
const b2 = 'List<ApplicationInfo> list = getPackageManager().getInstalledApplications(0);'
  .replace(/\n/g, '\r\n');
const g2 = 'java.util.List<android.content.pm.ApplicationInfo> list ='
  + '\r\n                    getPackageManager().getInstalledApplications(0);';
if (t.includes(b2)) { t = t.replace(b2, g2); console.log('2) ApplicationInfo 改为全限定名 OK'); }
else console.log('2) !! 未找到 ApplicationInfo 那一行');

// ---- 3) 调用点
const b3 = '+ getEntryRequirements() + ");";';
const g3 = '+ buildEntryRequirementsJson() + ");";';
if (t.includes(b3.replace(/\n/g, '\r\n'))) {
  t = t.replace(b3.replace(/\n/g, '\r\n'), g3);
  console.log('3) onRequestPermissionsResult 调用点已改为 buildEntryRequirementsJson() OK');
} else console.log('3) !! 未找到调用点');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
