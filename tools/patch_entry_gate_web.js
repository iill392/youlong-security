// ==========================================================================
// 进入应用权限门槛 + 绿色环境提示（网页侧，2026-10 用户需求）
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

// ---------------------------------------------------------------- 1) 插入两张卡片
rep(
`  <!-- 实时守护总开关（置于最上方） -->
  <div class="card" id="toggleCard">`,
`  <!-- ==================================================================
       进入应用权限门槛（2026-10 新增，用户需求）
       ------------------------------------------------------------------
       进入软件即检查「读取应用」与「通知」两项权限；任一缺失就显示本卡片，
       并在 JS 里拦截「开启实时守护」开关 —— 不授权无法开启安全守护。
       全部授权后本卡片自动隐藏。
       ================================================================== -->
  <div class="card" id="entryPermCard" style="display:none;border:1.5px solid #FF9500">
    <div style="display:flex;align-items:flex-start;gap:10px">
      <div style="font-size:1.3rem;line-height:1.2">&#128273;</div>
      <div style="flex:1">
        <div style="font-size:0.95rem;font-weight:700;color:#E65100">需要先授予以下权限</div>
        <div id="entryPermMsg" style="font-size:0.82rem;color:#3c3c43;margin-top:4px;line-height:1.5"></div>
        <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">
          <button type="button" class="btn btn-primary" id="entryPermNotifBtn" style="display:none;flex:1;min-width:140px">授予通知权限</button>
          <button type="button" class="btn btn-secondary" id="entryPermAppsBtn" style="display:none;flex:1;min-width:140px">检查读取应用权限</button>
          <button type="button" class="btn btn-primary" id="entryPermRetryBtn" style="flex:1;min-width:120px">我已授权，重新检查</button>
        </div>
      </div>
    </div>
  </div>

  <!-- ==================================================================
       环境良好提示（2026-10 新增，用户需求）
       ------------------------------------------------------------------
       无 root 且无 com.rosan.dhizuku 时显示：绿色描边、可关闭。
       与上面红色的「环境异常」卡互补 —— 这代表日常模式能拿到 100% 拦截。
       ================================================================== -->
  <div class="card" id="envGoodCard" style="display:none;border:1.5px solid #34C759">
    <div style="display:flex;align-items:flex-start;gap:10px">
      <div style="font-size:1.3rem;line-height:1.2">&#9989;</div>
      <div style="flex:1">
        <div style="font-size:0.95rem;font-weight:700;color:#28A745">环境良好</div>
        <div id="envGoodMsg" style="font-size:0.82rem;color:#3c3c43;margin-top:4px;line-height:1.5"></div>
        <div style="margin-top:8px">
          <span id="envGoodClose" style="color:#007AFF;font-size:0.8rem;text-decoration:underline;cursor:pointer">知道了，不再提示</span>
        </div>
      </div>
    </div>
  </div>

  <!-- 实时守护总开关（置于最上方） -->
  <div class="card" id="toggleCard">`,
'1) 插入权限门槛卡与绿色环境提示卡');

// ---------------------------------------------------------------- 2) 插入 JS
rep(
`function init(){
  showDisclaimer();
  refreshPermBanners();
  refreshEnvWarning();`,
`// ======================================================================
// 进入应用权限门槛（2026-10 新增，用户需求）
// ----------------------------------------------------------------------
// 需求：进入软件就要「读取应用权限」与「通知权限」，不开启则无法开启安全守护。
//
// · 读取应用：靠 QUERY_ALL_PACKAGES（安装期授予，无弹窗）。原生侧不是读 flag，
//   而是真的查一次已安装应用列表来判定是否真的可用（见 getEntryRequirements）。
// · 通知权限：Android 13+ 运行时权限，进页面就申请一次。
//
// 结果缓存在 entryReq 里，供「开启实时守护」开关做拦截判断。
// ======================================================================
var entryReq = null;              // {notification, applist, ok, missing[]}
var ENTRY_GREEN_KEY = 'env_good_dismissed_v1';

function refreshEntryRequirements(){
  var card = $('entryPermCard');
  var msgEl = $('entryPermMsg');
  var notifBtn = $('entryPermNotifBtn');
  var appsBtn = $('entryPermAppsBtn');
  if(!card) return;

  try{
    if(typeof Android !== 'undefined' && Android.getEntryRequirements){
      entryReq = JSON.parse(Android.getEntryRequirements());
    }
  }catch(e){ entryReq = null; }

  if(!entryReq){ card.style.display='none'; return; }

  if(entryReq.ok){
    card.style.display='none';
    return;
  }

  var lacks = [];
  if(!entryReq.applist) lacks.push('读取已安装应用');
  if(!entryReq.notification) lacks.push('通知');
  if(msgEl){
    msgEl.textContent = '缺少：' + lacks.join('、')
        + '。未授予这两项权限时，无法开启实时守护。';
  }
  if(notifBtn) notifBtn.style.display = entryReq.notification ? 'none' : '';
  if(appsBtn)  appsBtn.style.display  = entryReq.applist ? 'none' : '';
  card.style.display = 'block';
}

// 原生申请完通知权限后会回调这里，立即刷新卡片
window.__dshOnPermResult = function(req){
  try{
    entryReq = req;
    refreshEntryRequirements();
    if(req && req.ok) showToast('权限已就绪，可以开启实时守护了');
  }catch(e){}
};

// ======================================================================
// 环境良好（绿色描边）提示：无 root 且无 Dhizuku 时显示，可关闭
// ======================================================================
function refreshEnvGoodHint(){
  var card = $('envGoodCard'), msgEl = $('envGoodMsg'), closeEl = $('envGoodClose');
  if(!card) return;

  var info = null;
  try{
    if(typeof Android !== 'undefined' && Android.getEnvGoodHint){
      info = JSON.parse(Android.getEnvGoodHint());
    }
  }catch(e){ info = null; }

  if(!info || !info.show){ card.style.display='none'; return; }

  // 用户点过「不再提示」就记在本地，永久隐藏（需求明确：可以关闭）
  try{
    if(localStorage.getItem(ENTRY_GREEN_KEY) === '1'){ card.style.display='none'; return; }
  }catch(e){}

  if(msgEl){
    msgEl.textContent = '当前没有 root、也没有安装 Dhizuku，'
        + '日常模式2.0 可以发挥 100% 拦截能力，误伤率基本没有。';
  }
  card.style.display = 'block';
  if(closeEl){
    closeEl.onclick = function(){
      try{ localStorage.setItem(ENTRY_GREEN_KEY, '1'); }catch(e){}
      card.style.display = 'none';
      showToast('已关闭该提示');
    };
  }
}

function init(){
  showDisclaimer();
  refreshPermBanners();
  refreshEnvWarning();

  // ===== 进入应用即检查/申请两项权限（用户需求）=====
  refreshEntryRequirements();
  try{
    if(typeof Android !== 'undefined' && Android.requestNotificationPermission
       && entryReq && !entryReq.notification){
      // 进页面就申请一次；结果通过 __dshOnPermResult 回灌
      Android.requestNotificationPermission();
    }
  }catch(e){}

  // 权限卡片按钮
  var notifBtn = $('entryPermNotifBtn');
  if(notifBtn){
    notifBtn.onclick = function(){
      try{
        if(typeof Android !== 'undefined' && Android.requestNotificationPermission){
          Android.requestNotificationPermission();
        }else{
          showToast('原生桥接未就绪');
        }
      }catch(e){ showToast('申请失败，请在系统设置里手动开启'); }
    };
  }
  var appsBtn = $('entryPermAppsBtn');
  if(appsBtn){
    appsBtn.onclick = function(){
      try{
        if(typeof Android !== 'undefined' && Android.openAppListPermissionSettings){
          Android.openAppListPermissionSettings();
        }
      }catch(e){}
    };
  }
  var retryBtn = $('entryPermRetryBtn');
  if(retryBtn){
    retryBtn.onclick = function(){
      refreshEntryRequirements();
      if(entryReq && entryReq.ok) showToast('权限已就绪');
      else showToast('仍缺少权限，请先授予');
    };
  }

  // ===== 环境良好提示（绿色描边，可关闭）=====
  refreshEnvGoodHint();`,
'2) 插入权限门槛 JS 与绿色提示 JS');

// ---------------------------------------------------------------- 3) 开关拦截
rep(
`    if(this.checked){
      // 2026-10 改动（用户需求）：删除「建议日常尽量不要开启，可能会误伤」这个
      // 每次开启守护都弹的温馨提示弹窗（showGuardWarning）。用户明确要求去掉它，
      // 开启实时守护时不再弹任何提示。
      try{`,
`    if(this.checked){
      // 2026-10 新增（用户需求）：权限门槛 —— 没拿到「读取应用」+「通知」时
      // 不允许开启安全守护。这里再查一次，避免用户中途去设置里关掉了权限。
      refreshEntryRequirements();
      if(entryReq && !entryReq.ok){
        this.checked = false;
        shieldProtectOn = false;
        var lacks = [];
        if(!entryReq.applist) lacks.push('读取已安装应用');
        if(!entryReq.notification) lacks.push('通知');
        showToast('请先授予「' + lacks.join('」「') + '」权限，否则无法开启安全守护');
        var card = $('entryPermCard');
        if(card) card.style.display = 'block';
        return;
      }
      try{`,
'3) 开启开关前的权限拦截');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
