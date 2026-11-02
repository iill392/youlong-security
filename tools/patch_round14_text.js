const fs = require('fs');

// ==========================================================================
// 需求 3：去掉「100%拦截」，注释改为 经测试600款【高级病毒】拦截率为99.6%
// ==========================================================================
const H = 'E:/anquan/app/src/main/assets/index.html';
let h = fs.readFileSync(H, 'utf8');
const origH = h.length;

function repH(from, to, label) {
  const n = h.split(from).length - 1;
  if (n !== 1) { console.log('!! HTML ' + label + ' 匹配 ' + n + ' 次'); return false; }
  h = h.replace(from, to);
  console.log('OK HTML ' + label);
  return true;
}

repH(
  '<div class="mode-name">日常模式2.0（100%拦截） <span class="recommend-badge">推荐</span></div>',
  '<div class="mode-name">日常模式2.0 <span class="recommend-badge">推荐</span></div>',
  '3a) 模式名去掉「（100%拦截）」');

repH(
  '<div class="mode-desc">全网首发，病毒拦截率为<strong class="red-txt">100%</strong>，误伤率<strong class="red-txt">基本没有</strong></div>',
  '<div class="mode-desc">经测试<strong class="red-txt">600款</strong>【高级病毒】拦截率为<strong class="red-txt">99.6%</strong></div>',
  '3b) 模式说明改为 600款高级病毒 99.6%');

// 其它地方还残留的「100%拦截」字样一并清掉（按需）
let extra = 0;
const leftovers = ['日常模式2.0（100%拦截）', '100%拦截'];
for (const s of leftovers) {
  const n = h.split(s).length - 1;
  if (n > 0) { h = h.split(s).join(s === '日常模式2.0（100%拦截）' ? '日常模式2.0' : '高拦截'); extra += n; console.log('  清理残留 ' + n + ' 处: ' + s); }
}
console.log('HTML 残留清理 ' + extra + ' 处');

fs.writeFileSync(H, h, 'utf8');
console.log('index.html: ' + origH + ' -> ' + h.length + ' bytes');
console.log('');
console.log('复查：');
console.log('  100%拦截 残留: ' + (h.split('100%拦截').length - 1));
console.log('  600款 出现: ' + (h.split('600款').length - 1));
console.log('  99.6% 出现: ' + (h.split('99.6%').length - 1));
console.log('  日常模式2.0 出现: ' + (h.split('日常模式2.0').length - 1));
