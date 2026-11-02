// ==========================================================================
// 修：通知权限弹框不是"一打开就弹"，而是过一会（点开关时）才弹
//
// 原因：init() 可能在原生桥接（Android.getEntryRequirements）就绪之前就执行了，
//       那一次请求权限的分支被跳过（entryReq 为 null），
//       直到用户开守护触发重查时才补上 → 表现为"过一会才弹"。
//
// 修法：
//   1. 抽出 requestEntryPermissions()，做成可重入、幂等；
//   2. init() 调一次，并用短间隔重试若干次，直到桥接可用且请求发出；
//   3. 页面 load 完成后再补一次（onload），确保"打开就弹"；
//   4. 桥接就绪后的回调 __dshOnPermResult 里如果仍缺通知权限，再补请求一次。
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

// 1) 抽出可重入的请求函数 + 重试逻辑
rep(
`  // ===== 进入应用即检查/申请两项权限（用户需求）=====
  refreshEntryRequirements();
  try{
    if(typeof Android !== 'undefined' && Android.requestNotificationPermission
       && entryReq && !entryReq.notification){
      // 进页面就申请一次；结果通过 __dshOnPermResult 回灌
      Android.requestNotificationPermission();
    }
  }catch(e){}
`,
`  // ===== 进入应用即检查/申请权限（用户需求：打开就弹，不要等开了守护才弹）=====
  refreshEntryRequirements();
  requestEntryPermissions();
`,
'1) init 里改为调用 requestEntryPermissions()');

// 2) 插入 requestEntryPermissions + 重试 + onload 补请求
rep(
`// 原生申请完通知权限后会回调这里，立即刷新卡片
window.__dshOnPermResult = function(req){
  try{
    entryReq = req;
    refreshEntryRequirements();
    if(req && req.ok) showToast('权限已就绪，可以开启实时守护了');
  }catch(e){}
};`,
`// ======================================================================
// 请求进入应用所需的权限（2026-10 改动：要"打开就弹"，不能等开守护才弹）
// ----------------------------------------------------------------------
// 早期实现只在 init() 里请求一次，但 init() 有可能跑在原生桥接就绪**之前**，
// 那一次会因为拿不到 getEntryRequirements() 而跳过请求，
// 直到用户开守护触发重查时才补上 —— 表现就是"过一会才弹"。
//
// 现在：
//   · requestEntryPermissions() 可重入、幂等（记 permRequested 防止重复弹）；
//   · 桥接没就绪就 600ms 后重试，最多 10 次；
//   · 页面 load 完成后再补一次；
//   · 权限回调里若发现还缺通知权限，再补一次。
// ======================================================================
var permRequested = false;

function requestEntryPermissions(){
  try{
    // 桥接还没就绪 → 稍后重试（这是"过一会才弹"的根因）
    if(typeof Android === 'undefined' || !Android.getEntryRequirements){
      if((requestEntryPermissions._n = (requestEntryPermissions._n || 0) + 1) <= 10){
        setTimeout(requestEntryPermissions, 600);
      }
      return;
    }
    refreshEntryRequirements();
    if(!entryReq) return;
    // 通知权限只有 Android 13+ 才需要申请；已授予时不弹
    if(!entryReq.notification
       && Android.requestNotificationPermission
       && !permRequested){
      permRequested = true;
      Android.requestNotificationPermission();
    }
  }catch(e){}
}

// 原生申请完通知权限后会回调这里，立即刷新卡片
window.__dshOnPermResult = function(req){
  try{
    entryReq = req;
    refreshEntryRequirements();
    if(req && req.ok){
      showToast('权限已就绪，可以开启实时守护了');
    }else if(req && !req.notification){
      // 用户这次没给（或系统没弹出来）→ 允许再请求一次
      permRequested = false;
    }
  }catch(e){}
};`,
'2) 新增 requestEntryPermissions 与重试');

// 3) 页面加载完成后再补一次
rep(
`document.addEventListener('DOMContentLoaded',init);`,
`document.addEventListener('DOMContentLoaded',init);

// 2026-10 改动：页面 load 完成后再补请求一次权限。
// 有些机型上 init() 跑得比原生桥接注入早，这里兜住"打开就弹"的体验。
window.addEventListener('load', function(){
  try{ requestEntryPermissions(); }catch(e){}
});`,
'3) 页面 load 后补请求');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');

const t2 = fs.readFileSync(P, 'utf8');
console.log('复查 requestEntryPermissions 出现次数: ' + (t2.split('requestEntryPermissions').length - 1));
