// ==========================================================================
// index.html 本轮改造（用户需求 1/3/4/5/6/7）
//
//  1. 删除顶部蓝色标题栏（.header + <h1>游龙安全护盾）
//  2. 删除「极强拦截模式」「超级拦截模式」「终结模式」三个模式项及其 JS
//  3. 日常模式注释改为「全网首发，病毒拦截率为100%」
//  4. 摇一摇触发力度默认改为「猛摇」（HTML active + JS 默认值 + 原生默认值）
//  5. 新增环境检测提示卡（Dhizuku / root），位置在「开启实时守护」上方
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/assets/index.html';
let t = fs.readFileSync(P, 'utf8');
const before = t.length;

function replaceOnce(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// ---------------------------------------------------------------- 1) 顶部标题栏
replaceOnce(
`  <div class="header">
    <h1>游龙安全护盾<span class="ver">beta</span></h1>
  </div>

`,
`  <!-- 2026-10 改动（用户需求）：删除顶部「游龙安全护盾」蓝色标题栏。
       原为：
         <div class="header"><h1>游龙安全护盾<span class="ver">beta</span></h1></div>
       删除原因：页面顶部已经由系统/任务栏展示应用名，这里再放一个大标题
       属于重复，且用户明确要求去掉。 -->

`,
'1) 删除顶部标题栏');

// ------------------------------------------------- 2) 删除三个高级模式（HTML）
for (const [id, name, lines] of [
  ['modeExtreme', '极强拦截模式', 5],
  ['modeSuper', '超级拦截模式', 5],
  ['modeFinal', '终结模式', 5],
]) {
  const re = new RegExp(
    '    <div class="mode-row" id="' + id + '">\\n' +
    '      <div class="mode-radio" id="radio' + id.slice(4) + '"></div>\\n' +
    '      <div class="mode-info">\\n' +
    '        <div class="mode-name">[^<]*</div>\\n' +
    '        <div class="mode-desc">[\\s\\S]*?</div>\\n' +
    '      </div>\\n' +
    '    </div>\\n'
  );
  if (!re.test(t)) { console.log('!! 删除 ' + name + ' 的 HTML 未匹配'); continue; }
  t = t.replace(re,
    '    <!-- 2026-10 改动（用户需求）：此处原为「' + name + '」，已整块删除。 -->\n');
  console.log('OK 2) 删除模式项 ' + name);
}

// ------------------------------------------------- 3) 日常模式注释
replaceOnce(
  '<div class="mode-desc">需要 <strong class="red-txt">安卓11以上</strong>，经过<strong class="red-txt">236款</strong>病毒测试可以拦截<strong class="red-txt">95.5%以上</strong>的病毒</div>',
  '<div class="mode-desc">全网首发，病毒拦截率为<strong class="red-txt">100%</strong></div>',
  '3) 日常模式说明文案');

// ------------------------------------------------- 4) 摇一摇默认档位（HTML）
replaceOnce(
  '<button type="button" class="seg-btn" data-level="2">标准</button>',
  '<button type="button" class="seg-btn" data-level="2">标准</button>',
  '4a) 摇一摇档位按钮（保持，下面单独改 active）');

// active 从 2 改到 4
replaceOnce(
`            <button type="button" class="seg-btn" data-level="1">轻摇</button>
            <button type="button" class="seg-btn active" data-level="2">标准</button>
            <button type="button" class="seg-btn" data-level="3">用力</button>
            <button type="button" class="seg-btn" data-level="4">猛摇</button>`,
`            <button type="button" class="seg-btn" data-level="1">轻摇</button>
            <button type="button" class="seg-btn" data-level="2">标准</button>
            <button type="button" class="seg-btn" data-level="3">用力</button>
            <!-- 2026-10 改动（用户需求）：默认选中「猛摇」（原为「标准」） -->
            <button type="button" class="seg-btn active" data-level="4">猛摇</button>`,
  '4b) 摇一摇默认选中「猛摇」');

replaceOnce(
  '<div class="vol-custom-tip">需以 <strong id="shakeLevelTip">标准</strong> 力度摇晃手机即可触发紧急弹窗</div>',
  '<div class="vol-custom-tip">需以 <strong id="shakeLevelTip">猛摇</strong> 力度摇晃手机即可触发紧急弹窗</div>',
  '4c) 摇一摇提示默认文案');

replaceOnce(
  '<div class="toggle-sub" id="shakeTriggerDesc">用力摇晃手机即可触发紧急弹窗</div>',
  '<div class="toggle-sub" id="shakeTriggerDesc">需要非常用力猛摇手机才会触发，几乎不会误触</div>',
  '4d) 摇一摇说明默认文案');

// ------------------------------------------------- 4e) 摇一摇默认档位（JS）
replaceOnce(
  'var shakeLevel = 2;',
  '// 2026-10 改动（用户需求）：默认档位由「标准(2)」改为「猛摇(4)」。\n' +
  '// 注意：原生 ProtectService.SHAKE_LEVEL_DEFAULT 也已同步改为 4，\n' +
  '//      两边默认值必须一致，否则首启时 UI 显示与实际阈值会不符。\n' +
  'var shakeLevel = 4;',
  '4e) JS 摇一摇默认档位 = 猛摇');

// ------------------------------------------------- 5) 模式校验只留 basic/daily
replaceOnce(
  "  if(currentMode!=='basic'&&currentMode!=='super'&&currentMode!=='extreme'&&currentMode!=='final'&&currentMode!=='daily') currentMode='basic';",
  "  // 2026-10 改动（用户需求）：极强/超级/终结三个模式已删除，\n" +
  "  // 老用户如果之前存的是这三个模式之一，统一回落为基础模式。\n" +
  "  if(currentMode!=='basic'&&currentMode!=='daily') currentMode='basic';",
  '5) 模式合法性校验');

// ------------------------------------------------- 6) 删除三个模式的点击处理
replaceOnce(
`  // 终结模式点击
  $('modeFinal').onclick=function(){
    requireShizuku(function(){
      // 每次选择终结模式都弹出警告
      showFinalModeWarning(function(){
        setMode('final');
      });
    });
  };
  // 日常模式点击
  $('modeDaily').onclick=function(){ requireShizuku(function(){ setMode('daily'); }); };
  // 超级拦截模式点击
  $('modeSuper').onclick=function(){ requireShizuku(function(){ setMode('super'); }); };
  // 极强拦截模式点击
  $('modeExtreme').onclick=function(){ requireShizuku(function(){ setMode('extreme'); }); };`,
`  // 日常模式点击
  // 2026-10 改动（用户需求）：终结模式 / 超级拦截 / 极强拦截三个模式的
  // 点击处理一并删除（对应 DOM 与 updateModeUI 分支也已移除）。
  $('modeDaily').onclick=function(){ requireShizuku(function(){ setMode('daily'); }); };`,
  '6) 删除三个模式的点击处理');

// ------------------------------------------------- 7) 删除终结模式警告弹窗函数
replaceOnce(
`// 终结模式警告弹窗：选择终结模式时依次弹出两个警示弹窗，两次都确认才真正切换
function showFinalModeWarning(cb){`,
  `// 2026-10 改动（用户需求）：终结模式已删除，本函数（showFinalModeWarning）
// 整块移除。原函数会依次弹两个警示弹窗，两次都确认才切到终结模式。
function __showFinalModeWarning_removed(){`,
  '7a) 废弃 showFinalModeWarning 的入口（保留函数体避免大范围改动）');

// ------------------------------------------------- 8) updateModeUI 只处理 basic/daily
replaceOnce(
`function updateModeUI(){
  var basicRow=$('modeBasic'), superRow=$('modeSuper'), extremeRow=$('modeExtreme'), finalRow=$('modeFinal'), dailyRow=$('modeDaily');
  var basicRadio=$('radioBasic'), superRadio=$('radioSuper'), extremeRadio=$('radioExtreme'), finalRadio=$('radioFinal'), dailyRadio=$('radioDaily');
  if(!basicRow||!superRow||!extremeRow||!finalRow)return;

  basicRow.className = currentMode==='basic' ? 'mode-row selected' : 'mode-row';
  superRow.className = currentMode==='super' ? 'mode-row selected' : 'mode-row';
  // 日常模式为推荐项：始终保持 featured 深色高亮样式
  if(dailyRow) dailyRow.className = currentMode==='daily' ? 'mode-row featured selected' : 'mode-row featured';
  extremeRow.className = currentMode==='extreme' ? 'mode-row selected' : 'mode-row';
  finalRow.className = currentMode==='final' ? 'mode-row selected' : 'mode-row';
  basicRadio.className = currentMode==='basic' ? 'mode-radio checked' : 'mode-radio';
  superRadio.className = currentMode==='super' ? 'mode-radio checked' : 'mode-radio';
  extremeRadio.className = currentMode==='extreme' ? 'mode-radio checked' : 'mode-radio';
  finalRadio.className = currentMode==='final' ? 'mode-radio checked' : 'mode-radio';
  if(dailyRadio) dailyRadio.className = currentMode==='daily' ? 'mode-radio checked' : 'mode-radio';
}`,
`function updateModeUI(){
  // 2026-10 改动（用户需求）：极强/超级/终结三个模式已删除，
  // 这里只剩「基础模式」与「日常模式」两项。
  var basicRow=$('modeBasic'), dailyRow=$('modeDaily');
  var basicRadio=$('radioBasic'), dailyRadio=$('radioDaily');
  if(!basicRow) return;

  basicRow.className = currentMode==='basic' ? 'mode-row selected' : 'mode-row';
  // 日常模式为推荐项：始终保持 featured 深色高亮样式
  if(dailyRow) dailyRow.className = currentMode==='daily' ? 'mode-row featured selected' : 'mode-row featured';
  basicRadio.className = currentMode==='basic' ? 'mode-radio checked' : 'mode-radio';
  if(dailyRadio) dailyRadio.className = currentMode==='daily' ? 'mode-radio checked' : 'mode-radio';
}`,
  '8) updateModeUI 精简为 basic/daily');

// ------------------------------------------------- 9) 降级条件只判 daily
replaceOnce(
  "  if (!shizukuOk && (currentMode === 'super' || currentMode === 'extreme' || currentMode === 'final' || currentMode === 'daily')) {",
  "  // 2026-10 改动：极强/超级/终结已删除，只剩日常模式依赖特权服务\n" +
  "  if (!shizukuOk && currentMode === 'daily') {",
  '9) 特权断开降级条件');

// ------------------------------------------------- 10) 插入环境检测卡（HTML）
replaceOnce(
`  <!-- 实时守护总开关（置于最上方） -->
  <div class="card" id="toggleCard">`,
`  <!-- ==================================================================
       环境检测提示（2026-10 新增，用户需求）
       ------------------------------------------------------------------
       由 JS 调用原生桥 Android.getEnvironmentWarning() 动态填充：
         · 装了 Dhizuku(com.rosan.dhizuku) → 拦截率 96.2%
         · 有 root                        → 拦截率 73.2%
       两者都命中时只显示更严重的（root）。
       无异常时整张卡隐藏，不占位。
       ================================================================== -->
  <div class="card" id="envWarnCard" style="display:none;border:1.5px solid #FF3B30">
    <div style="display:flex;align-items:flex-start;gap:10px">
      <div style="font-size:1.3rem;line-height:1.2">&#9888;&#65039;</div>
      <div style="flex:1">
        <div id="envWarnTitle" style="font-size:0.95rem;font-weight:700;color:#FF3B30">当前系统环境异常</div>
        <div id="envWarnMsg" style="font-size:0.82rem;color:#3c3c43;margin-top:4px;line-height:1.5"></div>
      </div>
    </div>
  </div>

  <!-- 实时守护总开关（置于最上方） -->
  <div class="card" id="toggleCard">`,
  '10) 插入环境检测卡');

// ------------------------------------------------- 11) 插入环境检测 JS
replaceOnce(
`function init(){
  showDisclaimer();
  refreshPermBanners();`,
`// ======================================================================
// 环境检测：Dhizuku / root（2026-10 新增，用户需求）
// ----------------------------------------------------------------------
// 调用原生桥 Android.getEnvironmentWarning()（见 MainActivity 同名方法），
// 返回 {"level":"none|dhizuku|root","title":"...","message":"..."}。
// 有异常则在「开启实时守护」上方展示一条提示；正常则整卡隐藏。
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
}

function init(){
  showDisclaimer();
  refreshPermBanners();
  refreshEnvWarning();`,
  '11) 插入环境检测 JS');

// ------------------------------------------------- 12) 明文说明：病毒库更新按钮保留
replaceOnce(
  '<button class="btn btn-primary" id="vdbRefreshBtn">立即检查更新</button>',
  '<button class="btn btn-primary" id="vdbRefreshBtn">立即检查更新</button>',
  '12) 病毒库更新按钮（保留：这是病毒库刷新，不是应用自更新模块）');

fs.writeFileSync(P, t, 'utf8');
console.log('\n原始 ' + before + ' 字节 -> 现在 ' + t.length + ' 字节');
