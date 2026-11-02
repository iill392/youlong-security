// 品牌改名（Kotlin 侧）：ADB护盾版 -> 游龙安全ADB
const fs = require('fs');
const path = require('path');

const KT = 'E:/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager';
const ktFiles = [
  'ui/features/home/HomeScreen.kt',
  'ui/features/settings/SettingsScreen.kt',
  'ui/features/manager/StarterScreen.kt',
];

let total = 0;
for (const rel of ktFiles) {
  const f = path.join(KT, rel);
  if (!fs.existsSync(f)) { console.log('MISSING ' + f); continue; }
  let t = fs.readFileSync(f, 'utf8');
  const c = t.split('ADB护盾版').length - 1;
  if (c > 0) { t = t.split('ADB护盾版').join('游龙安全ADB'); fs.writeFileSync(f, t, 'utf8'); }
  total += c;
  const after = fs.readFileSync(f, 'utf8').split('游龙安全ADB').length - 1;
  console.log(rel + ': replaced ' + c + ', now has ' + after);
}
console.log('TOTAL: ' + total);
