// 品牌改名：ADB护盾版 / ADB Shield / ADB 護盾版  ->  游龙安全ADB / YouLong SafeADB / 遊龍安全ADB
// 用法: node rename_brand.js
const fs = require('fs');
const path = require('path');

const RES = 'E:/anquan/embedded/manager/src/main/res';
const KT  = 'E:/anquan/embedded/manager/src/main/kotlin';

// 每个语言文件对应的替换：[旧, 新]
const perLocale = {
  'values':          [['ADB Shield', 'YouLong SafeADB']],
  'values-ar':       [['ADB Shield', 'YouLong SafeADB']],
  'values-es':       [['ADB Shield', 'YouLong SafeADB']],
  'values-fr':       [['ADB Shield', 'YouLong SafeADB']],
  'values-ja':       [['ADB Shield', 'YouLong SafeADB']],
  'values-pt-rBR':   [['ADB Shield', 'YouLong SafeADB']],
  'values-ru':       [['ADB Shield', 'YouLong SafeADB']],
  'values-zh-rCN':   [['ADB护盾版', '游龙安全ADB']],
  'values-zh-rHK':   [['ADB 護盾版', '遊龍安全ADB']],
  'values-zh-rTW':   [['ADB 護盾版', '遊龍安全ADB']],
};

let total = 0;
for (const [locale, pairs] of Object.entries(perLocale)) {
  const f = path.join(RES, locale, 'strings.xml');
  if (!fs.existsSync(f)) { console.log('SKIP ' + locale); continue; }
  let t = fs.readFileSync(f, 'utf8');
  let n = 0;
  for (const [from, to] of pairs) {
    const c = t.split(from).length - 1;
    if (c > 0) { t = t.split(from).join(to); n += c; }
  }
  fs.writeFileSync(f, t, 'utf8');
  total += n;
  console.log(locale + ': ' + n + ' occurrence(s)');
}

// Kotlin 里硬编码的品牌名
const ktFiles = [
  path.join(KT, 'ui/features/home/HomeScreen.kt'),
  path.join(KT, 'ui/features/settings/SettingsScreen.kt'),
  path.join(KT, 'ui/features/manager/StarterScreen.kt'),
];
for (const f of ktFiles) {
  if (!fs.existsSync(f)) { console.log('SKIP ' + f); continue; }
  let t = fs.readFileSync(f, 'utf8');
  const c = t.split('ADB护盾版').length - 1;
  if (c > 0) { t = t.split('ADB护盾版').join('游龙安全ADB'); fs.writeFileSync(f, t, 'utf8'); }
  total += c;
  console.log(path.basename(f) + ': ' + c + ' occurrence(s)');
}
console.log('TOTAL: ' + total);
