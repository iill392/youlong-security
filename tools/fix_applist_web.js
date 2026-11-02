// 网页侧配套：去掉"检查读取应用权限"（跳设置）按钮
// 理由：应用列表用 launcher 查询即可，无需权限、无需用户操作；
//       QUERY_ALL_PACKAGES 是安装期授予的 normal 权限，requestPermissions 调它
//       也不会弹框（会立刻返回已授予）。所以这个按钮没有存在意义，删掉。
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

// 1) 删掉卡片里的按钮
rep(
`        <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">
          <button type="button" class="btn btn-primary" id="entryPermNotifBtn" style="display:none;flex:1;min-width:140px">授予通知权限</button>
          <button type="button" class="btn btn-secondary" id="entryPermAppsBtn" style="display:none;flex:1;min-width:140px">检查读取应用权限</button>
          <button type="button" class="btn btn-primary" id="entryPermRetryBtn" style="flex:1;min-width:120px">我已授权，重新检查</button>
        </div>`,
`        <!-- 2026-10 改动（用户反馈）：删掉「检查读取应用权限」按钮。
             读取已安装应用列表用 launcher 查询即可（无需权限、无需用户操作），
             不该把用户丢去系统设置。 -->
        <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">
          <button type="button" class="btn btn-primary" id="entryPermNotifBtn" style="display:none;flex:1;min-width:140px">授予通知权限</button>
          <button type="button" class="btn btn-primary" id="entryPermRetryBtn" style="flex:1;min-width:120px">我已授权，重新检查</button>
        </div>`,
'1) 删除跳设置按钮');

// 2) 删掉按钮的绑定逻辑
rep(
`  var appsBtn = $('entryPermAppsBtn');
  if(appsBtn){
    appsBtn.onclick = function(){
      try{
        if(typeof Android !== 'undefined' && Android.openAppListPermissionSettings){
          Android.openAppListPermissionSettings();
        }
      }catch(e){}
    };
  }
`,
`  // 2026-10 改动：原来这里有「检查读取应用权限」按钮的绑定（跳系统设置），已删除。
`,
'2) 删除按钮绑定逻辑');

// 3) refreshEntryRequirements 里不再控制 appsBtn
rep(
`  var notifBtn = $('entryPermNotifBtn');
  var appsBtn = $('entryPermAppsBtn');
  if(!card) return;`,
`  var notifBtn = $('entryPermNotifBtn');
  if(!card) return;`,
'3a) 去掉 appsBtn 变量');

rep(
`  if(notifBtn) notifBtn.style.display = entryReq.notification ? 'none' : '';
  if(appsBtn)  appsBtn.style.display  = entryReq.applist ? 'none' : '';
  card.style.display = 'block';`,
`  if(notifBtn) notifBtn.style.display = entryReq.notification ? 'none' : '';
  card.style.display = 'block';`,
'3b) 去掉 appsBtn 显示控制');

// 4) 缺少项的提示文案：应用列表已不需要用户操作，措辞改一下
rep(
`  var lacks = [];
  if(!entryReq.applist) lacks.push('读取已安装应用');
  if(!entryReq.notification) lacks.push('通知');
  if(msgEl){
    msgEl.textContent = '缺少：' + lacks.join('、')
        + '。未授予这两项权限时，无法开启实时守护。';
  }`,
`  var lacks = [];
  if(!entryReq.notification) lacks.push('通知');
  if(!entryReq.applist) lacks.push('读取已安装应用（点下方按钮或重启应用即可）');
  if(msgEl){
    msgEl.textContent = lacks.length > 0
        ? ('还需要：' + lacks.join('、') + '。未就绪时无法开启实时守护。')
        : '';
  }`,
'4) 提示文案调整');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');

const t2 = fs.readFileSync(P, 'utf8');
console.log('复查 entryPermAppsBtn 残留: ' + (t2.split('entryPermAppsBtn').length - 1));
console.log('复查 openAppListPermissionSettings 残留: ' + (t2.split('openAppListPermissionSettings').length - 1));
