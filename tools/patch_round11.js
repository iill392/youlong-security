// ==========================================================================
// 第 11 轮改动
//   1. 去掉「环境良好」提示（网页卡片 + JS 调用；原生 getEnvGoodHint 保留但不再被调用）
//   2. 版本号 9.0.1 -> 9.1.0（root build.gradle + VirusDb User-Agent）
// ==========================================================================
const fs = require('fs');

// ---------------------------------------------------------------- 1) 去掉绿色提示
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

// 1a) 删除卡片 HTML
rep(
`  <!-- ==================================================================
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

`,
`  <!-- 2026-10 改动（用户需求）：此处原为「环境良好」绿色描边提示卡
       （#envGoodCard，无 root 且无 Dhizuku 时显示、可关闭），已整块删除。 -->

`,
'1a) 删除「环境良好」卡片 HTML');

// 1b) 删除 JS 函数
rep(
`// ======================================================================
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

`,
`// 2026-10 改动（用户需求）：refreshEnvGoodHint() 已随「环境良好」提示卡一并删除。
// 保留说明：原生侧的 getEnvGoodHint() 仍在（未被调用，不影响功能），
// 如日后需要恢复该提示，把上面这段函数与卡片 HTML 恢复即可。

`,
'1b) 删除 refreshEnvGoodHint 函数');

// 1c) 去掉 init 里的调用
rep(
`  // ===== 环境良好提示（绿色描边，可关闭）=====
  refreshEnvGoodHint();`,
`  // 2026-10 改动：环境良好提示已删除，此处不再调用 refreshEnvGoodHint()。`,
'1c) 去掉 init 里的调用');

// 1d) 顺手清掉不再使用的 localStorage key 常量
rep(
`var entryReq = null;              // {notification, applist, ok, missing[]}
var ENTRY_GREEN_KEY = 'env_good_dismissed_v1';`,
`var entryReq = null;              // {notification, applist, ok, missing[]}
// 2026-10 改动：ENV_GREEN_KEY（环境良好提示的"不再提示"记忆键）已随该提示删除。`,
'1d) 清掉不再使用的 key 常量');

fs.writeFileSync(P, t, 'utf8');
console.log('\nindex.html: ' + orig + ' -> ' + t.length + ' bytes');

// ---------------------------------------------------------------- 2) 版本号
const V = 'E:/anquan/build.gradle';
let g = fs.readFileSync(V, 'utf8');
const before = g.length;
if (g.includes('versionName = "9.0.1"')) {
  g = g.replace('versionName = "9.0.1"', 'versionName = "9.1.0"');
  console.log('OK build.gradle: versionName -> 9.1.0');
} else {
  console.log('!! build.gradle 里未找到 versionName = "9.0.1"');
}
// versionCode 从 1234567899 提到 1234567900
if (g.includes('versionCode = 1234567899')) {
  g = g.replace('versionCode = 1234567899', 'versionCode = 1234567900');
  console.log('OK build.gradle: versionCode 1234567899 -> 1234567900');
} else {
  console.log('!! build.gradle 里未找到 versionCode = 1234567899');
}
fs.writeFileSync(V, g, 'utf8');
console.log('build.gradle: ' + before + ' -> ' + g.length + ' bytes');

// VirusDb 的 User-Agent
const D = 'E:/anquan/app/src/main/java/com/youlong/hd/VirusDb.java';
let d = fs.readFileSync(D, 'utf8');
if (d.includes('YulongShield/9.0.1')) {
  d = d.replace('YulongShield/9.0.1', 'YulongShield/9.1.0');
  fs.writeFileSync(D, d, 'utf8');
  console.log('OK VirusDb.java: User-Agent -> YulongShield/9.1.0');
} else {
  console.log('!! VirusDb.java 里未找到 YulongShield/9.0.1');
}

// 复查
console.log('');
console.log('复查 index.html：');
const t2 = fs.readFileSync(P, 'utf8');
console.log('  envGoodCard 残留: ' + (t2.split('envGoodCard').length - 1));
console.log('  refreshEnvGoodHint 残留: ' + (t2.split('refreshEnvGoodHint').length - 1));
console.log('  环境良好 残留: ' + (t2.split('环境良好').length - 1));
console.log('  getEnvGoodHint 残留（应为注释里的说明）: ' + (t2.split('getEnvGoodHint').length - 1));
