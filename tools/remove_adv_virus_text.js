// 去掉日常模式说明里的「【高级病毒】」4 个字
const fs = require('fs');
const P = 'E:/anquan/app/src/main/assets/index.html';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

const cases = [
  ['经测试<strong class="red-txt">600款</strong>【高级病毒】拦截率为<strong class="red-txt">99.6%</strong>',
   '经测试<strong class="red-txt">600款</strong>拦截率为<strong class="red-txt">99.6%</strong>'],
];

let done = 0;
for (const [a, b] of cases) {
  const n = t.split(a).length - 1;
  if (n === 1) { t = t.replace(a, b); done++; console.log('OK 已去掉【高级病毒】'); }
  else console.log('!! 未精确匹配（' + n + ' 次）');
}

// 兜底：若上面没匹配到，直接全局移除该片段
if (done === 0) {
  const n = t.split('【高级病毒】').length - 1;
  if (n > 0) { t = t.split('【高级病毒】').join(''); console.log('OK 兜底移除 ' + n + ' 处【高级病毒】'); }
  else console.log('未找到【高级病毒】');
}

fs.writeFileSync(P, t, 'utf8');
console.log(orig + ' -> ' + t.length + ' bytes');
console.log('');
console.log('复查：');
console.log('  【高级病毒】 残留: ' + (t.split('【高级病毒】').length - 1));
console.log('  高级病毒 残留: ' + (t.split('高级病毒').length - 1));
const m = t.match(/<div class="mode-desc">[^<]*(<strong[^>]*>[^<]*<\/strong>[^<]*)*<\/div>/g);
if (m) m.slice(0, 4).forEach(x => console.log('  ' + x));
