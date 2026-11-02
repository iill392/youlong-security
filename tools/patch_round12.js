// ==========================================================================
// 去掉每次进入都弹的「提示」弹窗（showDisclaimer）
//
// 原内容：「目前**格机脚本 sh 文件**防不了，建议大家**日常尽量不要开**，
//          因为可能会**误伤**，开启后**一切由自己负责**，我们**概不负责**。」
// 用户反馈：每次进入都有这个「sh 文件不能防」的弹窗，要去掉。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/assets/index.html';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// 1) 删除函数体
rep(
`// 进入提示弹窗
function showDisclaimer(){
  var overlay=document.createElement('div');
  overlay.className='modal-overlay';
  overlay.innerHTML=
    '<div class="modal-box">'+
      '<div class="modal-title">提示</div>'+
      '<div class="modal-body">目前<strong>格机脚本 sh 文件</strong>防不了，建议大家<strong>日常尽量不要开</strong>，因为可能会<strong>误伤</strong>，开启后<strong>一切由自己负责</strong>，我们<strong>概不负责</strong>。</div>'+
      '<div class="modal-footer"><button class="modal-btn" id="disclaimerBtn">我知道了</button></div>'+
    '</div>';
  document.body.appendChild(overlay);
  document.getElementById('disclaimerBtn').onclick=function(){
    overlay.remove();
  };
}`,
`// 2026-10 改动（用户需求）：此处原本是 showDisclaimer() —— 每次进入应用都弹的
// 「提示」弹窗，内容为「目前格机脚本 sh 文件防不了，建议日常尽量不要开……概不负责」。
// 用户明确要求去掉，故整块删除（函数体 + init() 里的调用）。
// 若日后需要恢复，注意同时恢复 init() 里的调用点。`,
'1) 删除 showDisclaimer 函数体');

// 2) 去掉调用
rep(
`function init(){
  showDisclaimer();
  refreshPermBanners();
  refreshEnvWarning();`,
`function init(){
  // 2026-10 改动（用户需求）：原来这里会调 showDisclaimer() 弹「sh 文件防不了」的
  // 提示弹窗，已按用户要求删除，进入应用不再弹任何免责提示。
  refreshPermBanners();
  refreshEnvWarning();`,
'2) 去掉 init() 里的调用');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');

// 复查
const t2 = fs.readFileSync(P, 'utf8');
console.log('复查：');
console.log('  showDisclaimer 残留: ' + (t2.split('showDisclaimer').length - 1) + '（应只剩注释说明）');
console.log('  disclaimerBtn 残留: ' + (t2.split('disclaimerBtn').length - 1));
console.log('  格机脚本 sh 文件 残留: ' + (t2.split('格机脚本').length - 1));
console.log('  modal-title>提示 残留: ' + (t2.split('<div class="modal-title">提示</div>').length - 1));
