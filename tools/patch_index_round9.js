// ==========================================================================
// 本轮（第 9 轮）index.html 改动
//   1. 环境异常提示改为**不可关闭**（去掉「点此不再提示」与 localStorage 记忆）
//   2. 删除「开启实时守护」时每次都弹的 showGuardWarning 弹窗（及其调用）
//   3. 「日常模式」改名为「日常模式2.0（100%拦截）」
//   4. 日常模式说明补一句「误伤率基本没有」
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

// ---------------------------------------------------------------- 1) 提示不可关闭
rep(
`// 有异常则在「开启实时守护」上方展示一条提示；正常则整卡隐藏。
// ======================================================================
function refreshEnvWarning(){
  var card=$('envWarnCard'), titleEl=$('envWarnTitle'), msgEl=$('envWarnMsg');
  if(!card) return;

  var info=null;
  try{
    if(typeof Android!=='undefined' && Android.getEnvironmentWarning){
      info=JSON.parse(Android.getEnvironmentWarning());
    }
  }catch(e){ info=null; }

  if(!info || !info.level || info.level==='none'){
    card.style.display='none';
    return;
  }

  // 已提示过就不再重复打扰（key 里带上 level，环境变化后仍会再提示一次）
  try{
    var ackKey='env_warn_ack_'+info.level;
    if(localStorage.getItem(ackKey)==='1'){
      card.style.display='none';
      return;
    }
  }catch(e){}

  if(titleEl) titleEl.textContent=info.title||'当前系统环境异常';
  if(msgEl) msgEl.innerHTML=(info.message||'')
      + '<br><span style="color:#8e8e93;font-size:0.75rem">（点此不再提示）</span>';
  card.style.display='block';
  card.onclick=function(){
    try{ localStorage.setItem('env_warn_ack_'+info.level,'1'); }catch(e){}
    card.style.display='none';
  };
}`,
`// 有异常则在「开启实时守护」上方展示一条提示；正常则整卡隐藏。
//
// ⚠️ 2026-10 改动（用户需求）：这条提示**不可关闭**。
//    原实现带了「点此不再提示」+ localStorage 记忆，用户点一下就能永久隐藏 ——
//    但环境异常（装了 Dhizuku / 手机有 root）意味着日常模式拿不到 100% 拦截，
//    属于必须让用户持续看到的信息，因此：
//      · 去掉点击关闭与 localStorage 记忆；
//      · 卡片固定常驻，只在环境恢复正常（level === 'none'）时自动消失。
// ======================================================================
function refreshEnvWarning(){
  var card=$('envWarnCard'), titleEl=$('envWarnTitle'), msgEl=$('envWarnMsg');
  if(!card) return;

  var info=null;
  try{
    if(typeof Android!=='undefined' && Android.getEnvironmentWarning){
      info=JSON.parse(Android.getEnvironmentWarning());
    }
  }catch(e){ info=null; }

  if(!info || !info.level || info.level==='none'){
    // 环境正常 → 隐藏；异常 → 常驻
    card.style.display='none';
    card.onclick=null;
    return;
  }

  if(titleEl) titleEl.textContent=info.title||'当前系统环境异常';
  if(msgEl) msgEl.textContent=info.message||'';
  card.style.display='block';
  // 明确不提供关闭入口（用户需求：一直无法关闭）
  card.onclick=null;
}`,
'1) 环境提示改为不可关闭');

// ---------------------------------------------------------------- 2) 删开启弹窗
rep(
`    if(this.checked){
      showGuardWarning();
      try{`,
`    if(this.checked){
      // 2026-10 改动（用户需求）：删除「建议日常尽量不要开启，可能会误伤」这个
      // 每次开启守护都弹的温馨提示弹窗（showGuardWarning）。用户明确要求去掉它，
      // 开启实时守护时不再弹任何提示。
      try{`,
'2a) 删除 showGuardWarning 的调用');

rep(
`// 开启实时守护时的温馨提示弹窗（每次开启都会弹出）
function showGuardWarning(){
  var overlay=document.createElement('div');
  overlay.className='modal-overlay';
  overlay.innerHTML=
    '<div class="modal-box">'+
      '<div class="modal-title">温馨提示</div>'+
      '<div class="modal-body">建议<strong>日常尽量不要开启</strong>，可能会<strong>误伤</strong>。</div>'+
      '<div class="modal-footer"><button class="modal-btn" id="guardWarnBtn">我知道了</button></div>'+
    '</div>';
  document.body.appendChild(overlay);
  document.getElementById('guardWarnBtn').onclick=function(){
    overlay.remove();
  };
}`,
`// 2026-10 改动（用户需求）：此处原本是 showGuardWarning()
// —— 开启实时守护时弹的「建议日常尽量不要开启，可能会误伤」温馨提示。
// 用户明确要求删除该弹窗，故整块移除。若日后需要恢复，注意同时恢复
// toggle.onchange 里的调用点。`,
'2b) 删除 showGuardWarning 函数体');

// ---------------------------------------------------------------- 3) 改名 日常模式2.0
rep(
`<div class="mode-name">日常模式 <span class="recommend-badge">推荐</span></div>`,
`<div class="mode-name">日常模式2.0（100%拦截） <span class="recommend-badge">推荐</span></div>`,
'3) 模式名改为 日常模式2.0（100%拦截）');

// ---------------------------------------------------------------- 4) 说明补一句
rep(
`<div class="mode-desc">全网首发，病毒拦截率为<strong class="red-txt">100%</strong></div>`,
`<div class="mode-desc">全网首发，病毒拦截率为<strong class="red-txt">100%</strong>，误伤率<strong class="red-txt">基本没有</strong></div>`,
'4) 日常模式说明补「误伤率基本没有」');

// ---------------------------------------------------------------- 5) 其余用户可见文案里的「日常模式」
let renamed = 0;
const targets = [
  ['日常模式已开启', '日常模式2.0（100%拦截）已开启'],
  ['已切换为日常模式', '已切换为日常模式2.0（100%拦截）'],
  ['切换到日常模式', '切换到日常模式2.0（100%拦截）'],
  ['日常模式需要', '日常模式2.0（100%拦截）需要'],
  ['日常模式下', '日常模式2.0（100%拦截）下'],
  ['日常模式会', '日常模式2.0（100%拦截）会'],
  ['日常模式将', '日常模式2.0（100%拦截）将'],
];
for (const [a, b] of targets) {
  const n = t.split(a).length - 1;
  if (n > 0) { t = t.split(a).join(b); renamed += n; console.log('  改名 ' + n + ' 处: ' + a); }
}
console.log('OK 5) 其它可见文案改名 ' + renamed + ' 处');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');

// 复查
const t2 = fs.readFileSync(P, 'utf8');
console.log('');
console.log('复查：');
console.log('  showGuardWarning 调用残留: ' + (t2.split('showGuardWarning()').length - 1));
console.log('  env_warn_ack 残留: ' + (t2.split('env_warn_ack').length - 1));
console.log('  点此不再提示 残留: ' + (t2.split('点此不再提示').length - 1));
console.log('  日常模式2.0 出现次数: ' + (t2.split('日常模式2.0').length - 1));
console.log('  误伤率 出现次数: ' + (t2.split('误伤率').length - 1));
