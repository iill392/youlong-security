// ==========================================================================
// 白屏根治：放弃 spacer 方案，改成「mainContent 的内边距 = 系统栏 inset」
//
// 之前用两个占位 View 预留状态栏/导航栏，结果 ConstraintLayout 把
// layout_height="0dp" + 只有 bottom 约束的 navBarSpacer 拉伸填满整屏，
// mainContent 被挤成 0 高 → 白屏（实测 navBarSpacer=0,0-1080,2280）。
// 加 layout_constrainedHeight="true" 也没用（仍是 2280）。
//
// 新方案：不引入任何新 View，直接把 inset 作为 mainContent 的 padding：
//   paddingTop    = statusBars.top
//   paddingBottom = navigationBars.bottom
//   paddingLeft/Right = systemBars.left / right
// 全屏播放视频时把 padding 归零。布局改动最小、语义最确定。
// ==========================================================================
const fs = require('fs');

// ---------------------------------------------------------------- 1) 布局：删掉两个 spacer
const L = 'E:/anquan/app/src/main/res/layout/activity_main.xml';
let x = fs.readFileSync(L, 'utf8');
const origL = x.length;

function repXml(from, to, label) {
  const n = x.split(from).length - 1;
  if (n !== 1) { console.log('!! XML ' + label + ' 匹配 ' + n + ' 次'); return false; }
  x = x.replace(from, to);
  console.log('OK XML ' + label);
  return true;
}

// 顶部 spacer -> 删除
repXml(
`    <!-- 顶部状态栏预留条：minHeight 是兜底，真实高度由代码按 WindowInsets 设置 -->
    <View
        android:id="@+id/statusBarSpacer"
        android:layout_width="match_parent"
        android:layout_height="24dp"
        android:background="@color/app_window_bg"
        app:layout_constraintTop_toTopOf="parent" />

`,
`    <!-- 2026-10 改动：原来这里有一个顶部的 statusBarSpacer 占位 View，
         现在改为直接把状态栏 inset 作为 mainContent 的 paddingTop
         （见 MainActivity.applyInsetsToViews）。占位 View 方案在
         ConstraintLayout 里容易被拉伸，实测会导致 mainContent 高度为 0（白屏）。 -->

`,
'1) 删除 statusBarSpacer');

// 底部 spacer -> 删除
const navBlock = x.match(/    <!--\n      底部导航栏\/手势条预留条[\s\S]*?app:layout_constraintEnd_toEndOf="parent" \/>\n\n/);
if (navBlock) {
  x = x.replace(navBlock[0],
`    <!-- 2026-10 改动：原来这里有一个底部的 navBarSpacer 占位 View，
         现在改为直接把导航栏 inset 作为 mainContent 的 paddingBottom
         （见 MainActivity.applyInsetsToViews）。 -->

`);
  console.log('OK XML 2) 删除 navBarSpacer');
} else {
  console.log('!! XML 2) 未匹配到 navBarSpacer 块');
}

// mainContent 的 bottom 约束改回 parent
repXml(
`        app:layout_constraintTop_toBottomOf="@id/permissionBanner"
        app:layout_constraintBottom_toTopOf="@id/navBarSpacer"`,
`        app:layout_constraintTop_toBottomOf="@id/permissionBanner"
        app:layout_constraintBottom_toBottomOf="parent"`,
'3) mainContent 底部约束改回 parent');

fs.writeFileSync(L, x, 'utf8');
console.log('layout: ' + origL + ' -> ' + x.length + ' bytes');

// ---------------------------------------------------------------- 2) 代码：改成设置 padding
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

function rep(from, to, label) {
  const f = from.replace(/\r?\n/g, '\r\n');
  const g = to.replace(/\r?\n/g, '\r\n');
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

// 2a) 字段：去掉两个 spacer 字段
rep(
`    private View statusBarSpacer;
    private View navBarSpacer;
    private View mainContent;`,
`    // 2026-10 白屏修复后：不再用占位 View，inset 直接做成 mainContent 的 padding。
    private View mainContent;`,
'2a) 去掉 spacer 字段');

// 2b) setupWindowInsets 里不再查找 spacer
rep(
`            final View root = findViewById(R.id.rootLayout);
            statusBarSpacer = findViewById(R.id.statusBarSpacer);
            navBarSpacer = findViewById(R.id.navBarSpacer);
            mainContent = findViewById(R.id.mainContent);`,
`            final View root = findViewById(R.id.rootLayout);
            mainContent = findViewById(R.id.mainContent);`,
'2b) setupWindowInsets 不再查找 spacer');

// 2c) applyInsetsToViews 改为设置 padding
rep(
`    /** 按当前是否全屏，把预留条高度与左右内边距应用上去 */
    private void applyInsetsToViews(boolean fullscreen) {
        if (statusBarSpacer != null) {
            android.view.ViewGroup.LayoutParams lp = statusBarSpacer.getLayoutParams();
            lp.height = fullscreen ? 0 : lastStatusBarInset;
            statusBarSpacer.setLayoutParams(lp);
        }
        if (navBarSpacer != null) {
            android.view.ViewGroup.LayoutParams lp = navBarSpacer.getLayoutParams();
            lp.height = fullscreen ? 0 : lastNavBarInset;
            navBarSpacer.setLayoutParams(lp);
        }
        if (mainContent != null) {
            final int side = fullscreen ? 0 : lastSideInset;
            mainContent.setPadding(side, 0, side, 0);
        }
    }`,
`    /**
     * 按当前是否全屏，把系统栏 inset 应用成 mainContent 的内边距。
     *
     * <p>2026-10 白屏修复：这里原来是把 inset 写成两个占位 View 的高度，
     * 但 ConstraintLayout 会把「layout_height=0dp 且只有 bottom 约束」的
     * navBarSpacer 拉伸到填满剩余空间，把 mainContent 挤成 0 高 → 白屏。
     * 现在改为直接给 mainContent 加内边距：语义确定，且不依赖约束求解。
     */
    private void applyInsetsToViews(boolean fullscreen) {
        if (mainContent == null) return;
        if (fullscreen) {
            mainContent.setPadding(0, 0, 0, 0);
        } else {
            mainContent.setPadding(
                    lastSideInset,          // 左（横屏挖孔屏/手势区）
                    lastStatusBarInset,     // 上（状态栏）
                    lastSideInset,          // 右
                    lastNavBarInset);       // 下（导航栏/手势条）
        }
    }`,
'2c) applyInsetsToViews 改为设置 padding');

fs.writeFileSync(P, t, 'utf8');
console.log('MainActivity: ' + orig + ' -> ' + t.length + ' bytes');

// 复查
const t2 = fs.readFileSync(P, 'utf8');
const x2 = fs.readFileSync(L, 'utf8');
console.log('');
console.log('复查：');
console.log('  statusBarSpacer 残留(代码): ' + (t2.split('statusBarSpacer').length - 1));
console.log('  navBarSpacer 残留(代码): ' + (t2.split('navBarSpacer').length - 1));
console.log('  布局里 navBarSpacer: ' + (x2.split('navBarSpacer').length - 1));
console.log('  布局里 statusBarSpacer: ' + (x2.split('statusBarSpacer').length - 1));
