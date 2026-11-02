// 修掉 colors_window.xml 两处 XML 注释里的连续减号（XML 不允许 "--"）
const fs = require('fs');
const files = [
  'E:/anquan/app/src/main/res/values/colors_window.xml',
  'E:/anquan/app/src/main/res/values-night/colors_window.xml',
];
for (const f of files) {
  let t = fs.readFileSync(f, 'utf8');
  const before = (t.match(/-{2,}/g) || []).length;
  // 把「整行的连续减号」换成等号；并保证不会误伤 <!-- 与 -->
  t = t.replace(/^(\s*)-{2,}(\s*)$/gm, (m, a, b) => a + '='.repeat(74) + b);
  // 兜底：任何残留的 2 个以上连续减号（排除 <!-- 和 -->）替换为等号
  t = t.replace(/(?<!<!)--+(?!>)/g, m => '='.repeat(m.length));
  fs.writeFileSync(f, t, 'utf8');
  const after = (t.match(/(?<!<!)--+(?!>)/g) || []).length;
  console.log(f.replace('E:/anquan/app/src/main/res/', '') + ': 修前 ' + before + ' 处 -> 修后 ' + after + ' 处');
}

// 校验
for (const f of files) {
  const t = fs.readFileSync(f, 'utf8');
  const bad = /<!--(?:(?!-->)[\s\S])*?--(?:(?!-->)[\s\S])*?-->/.test(t);
  console.log('校验 ' + f.split('/').pop() + ': ' + (bad ? '仍有非法注释' : 'OK'));
}
