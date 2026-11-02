// 网页侧：恢复「允许读取应用列表」按钮，并让它在缺权限时显示、点击直接弹系统授权框
const fs = require('fs');
const P = 'E:/anquan/app/src/main/assets/index.html';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// 1) 按钮恢复
rep(
`        <!-- 2026-10 改动（用户反馈）：删掉「检查读取应用权限」按钮。
             读取已安装应用列表用 launcher 查询即可（无需权限、无需用户操作），
             不该把用户丢去系统设置。 -->
        <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">
          <button type="button" class="btn btn-primary" id="entryPermNotifBtn" style="display:none;flex:1;min-width:140px">授予通知权限</button>
          <button type="button" class="btn btn-primary" id="entryPermRetryBtn" style="flex:1;min-width:120px">我已授权，重新检查</button>
        </div>`,
`        <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">
          <button type="button" class="btn btn-primary" id="entryPermNotifBtn" style="display:none;flex:1;min-width:140px">允许通知</button>
          <!-- 2026-10 恢复：读取应用列表按钮。
               国内 ROM（华为/HyperOS/ColorOS 等）会为它弹系统授权框；
               标准 Android 上它安装即授予、点击会立刻返回已授权，不会卡住。 -->
          <button type="button" class="btn btn-primary" id="entryPermAppsBtn" style="display:none;flex:1;min-width:140px">允许读取应用列表</button>
          <button type="button" class="btn btn-primary" id="entryPermRetryBtn" style="flex:1;min-width:120px">我已授权，重新检查</button>
        </div>`,
'1) 恢复「允许读取应用列表」按钮');

// 2) refreshEntryRequirements 里控制两个按钮
rep(
`  var card = $('entryPermCard');
  var msgEl = $('entryPermMsg');
  var notifBtn = $('entryPermNotifBtn');
  if(!card) return;`,
`  var card = $('entryPermCard');
  var msgEl = $('entryPermMsg');
  var notifBtn = $('entryPermNotifBtn');
  var appsBtn = $('entryPermAppsBtn');
  if(!card) return;`,
'2a) 取 appsBtn');

rep(
`  var lacks = [];
  if(!entryReq.notification) lacks.push('通知');
  if(!entryReq.applist) lacks.push('读取已安装应用（点下方按钮或重启应用即可）');
  if(msgEl){
    msgEl.textContent = lacks.length > 0
        ? ('还需要：' + lacks.join('、') + '。未就绪时无法开启实时守护。')
        : '';
  }
  if(notifBtn) notifBtn.style.display = entryReq.notification ? 'none' : '';
  card.style.display = 'block';`,
`  var lacks = [];
  if(!entryReq.notification) lacks.push('通知');
  if(!entryReq.applist) lacks.push('读取已安装应用列表');
  if(msgEl){
    msgEl.textContent = lacks.length > 0
        ? ('还需要授权：' + lacks.join('、') + '。未授权时无法开启实时守护。')
        : '';
  }
  if(notifBtn) notifBtn.style.display = entryReq.notification ? 'none' : '';
  if(appsBtn)  appsBtn.style.display  = entryReq.applist ? 'none' : '';
  card.style.display = 'block';`,
'2b) 显示控制与文案');

// 3) 启动时若缺应用列表权限，也自动请求一次（先通知、后应用列表）
rep(
`    refreshEntryRequirements();
    if(!entryReq) return;
    // 通知权限只有 Android 13+ 才需要申请；已授予时不弹
    if(!entryReq.notification
       && Android.requestNotificationPermission
       && !permRequested){
      permRequested = true;
      Android.requestNotificationPermission();
    }
  }catch(e){}`,
`    refreshEntryRequirements();
    if(!entryReq) return;
    // ① 先弹通知（Android 13+ 才是运行时权限）
    if(!entryReq.notification
       && Android.requestNotificationPermission
       && !permRequested){
      permRequested = true;
      Android.requestNotificationPermission();
      return;   // 等通知弹完的回调里再处理下一个
    }
    // ② 通知已就绪 → 再弹「读取应用列表」
    if(!entryReq.applist
       && Android.requestAppListPermission
       && !appListRequested){
      appListRequested = true;
      Android.requestAppListPermission();
    }
  }catch(e){}`,
'3) 启动时顺序请求（通知 → 应用列表）');

rep(
`var permRequested = false;`,
`var permRequested = false;
// 应用列表权限只自动请求一次（避免反复弹）
var appListRequested = false;`,
'3b) 新增 appListRequested');

// 4) 回调里继续申请应用列表
rep(
`    if(req && req.ok){
      showToast('权限已就绪，可以开启实时守护了');
    }else if(req && !req.notification){
      // 用户这次没给（或系统没弹出来）→ 允许再请求一次
      permRequested = false;
    }`,
`    if(req && req.ok){
      showToast('权限已就绪，可以开启实时守护了');
      return;
    }
    if(req && !req.notification){
      // 用户这次没给（或系统没弹出来）→ 允许再请求一次
      permRequested = false;
      return;
    }
    // 通知已就绪但还缺应用列表 → 紧接着弹「允许读取应用列表」
    if(req && req.notification && !req.applist
       && typeof Android !== 'undefined' && Android.requestAppListPermission
       && !appListRequested){
      appListRequested = true;
      Android.requestAppListPermission();
    }`,
'4) 回调里接着弹应用列表');

// 5) 绑定按钮点击
rep(
`  var notifBtn = $('entryPermNotifBtn');
  if(notifBtn){`,
`  var appsBtn = $('entryPermAppsBtn');
  if(appsBtn){
    appsBtn.onclick = function(){
      try{
        if(typeof Android !== 'undefined' && Android.requestAppListPermission){
          Android.requestAppListPermission();     // 直接弹系统授权框
          showToast('请在系统弹窗中点击「允许」');
        }else{
          showToast('原生桥接未就绪');
        }
      }catch(e){ showToast('申请失败，请稍后重试'); }
    };
  }
  var notifBtn = $('entryPermNotifBtn');
  if(notifBtn){`,
'5) 绑定应用列表按钮');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
const t2 = fs.readFileSync(P, 'utf8');
console.log('复查 entryPermAppsBtn: ' + (t2.split('entryPermAppsBtn').length - 1) + ' 处');
console.log('复查 requestAppListPermission: ' + (t2.split('requestAppListPermission').length - 1) + ' 处');
