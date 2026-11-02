// ==========================================================================
// 显式预留系统栏区域（用户反馈：Android 16 上状态栏遮挡内容）
//
// Android 15（API 35）起 targetSdk>=35 被强制 edge-to-edge，系统不再自动
// 预留状态栏/导航栏区域。这里改成**我们自己按 WindowInsets 预留**：
//   · statusBarSpacer 高度 = statusBars.top
//   · navBarSpacer 高度     = navigationBars.bottom（或 systemBars.bottom）
//   · mainContent 左右内边距 = systemBars.left / right（横屏挖孔屏）
//   · 全屏播放视频时把预留条归零，让视频真正铺满
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;
const crlf = s => s.replace(/\r?\n/g, '\r\n');

function rep(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

// ---------------------------------------------------------------- 1) 导入
rep(
`import android.view.Window;`,
`import android.view.Window;
// 2026-10 新增：按 WindowInsets 显式预留状态栏/导航栏区域（Android 15+ 强制 edge-to-edge）
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;`,
'1) 新增 WindowInsets 相关 import');

// ---------------------------------------------------------------- 2) 字段
rep(
`    // 2026-10 新增：通知权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;`,
`    // 2026-10 新增：通知权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;

    // 2026-10 新增：系统栏"预留条"相关 View 与最近一次读到的 inset 值
    // 见 applyWindowInsets() 与 setFullscreenInsets() 的说明。
    private View statusBarSpacer;
    private View navBarSpacer;
    private View mainContent;
    private int lastStatusBarInset = 0;
    private int lastNavBarInset = 0;
    private int lastSideInset = 0;`,
'2) 新增预留条字段');

// ---------------------------------------------------------------- 3) setContentView 之后初始化
rep(
`        // 2026-10 新增：按当前白天/夜间模式应用状态栏外观，
        // 避免部分机型状态栏透明导致时间与图标看不见（见 syncStatusBarAppearance 注释）
        syncStatusBarAppearance();`,
`        // 2026-10 新增：按当前白天/夜间模式应用状态栏外观，
        // 避免部分机型状态栏透明导致时间与图标看不见（见 syncStatusBarAppearance 注释）
        syncStatusBarAppearance();

        // 2026-10 新增：显式预留系统栏区域。Android 15+ 强制 edge-to-edge 后，
        // 不自己消费 WindowInsets 就会被状态栏/手势条压住内容。
        setupWindowInsets();`,
'3) onCreate 里调用 setupWindowInsets()');

// ---------------------------------------------------------------- 4) 进全屏：预留条归零
rep(
`                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    }`,
`                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    }
                    // 2026-10 新增：全屏播放视频时让内容真正铺满，去掉预留条
                    setFullscreenInsets(true);`,
'4a) 进全屏时归零预留条');

// ---------------------------------------------------------------- 5) 退全屏：恢复预留
rep(
`                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                // 2026-10 新增：退出全屏后重新同步状态栏外观，
                // 否则清掉 FLAG_FULLSCREEN 时状态栏可能停留在透明/错误图标状态
                syncStatusBarAppearance();`,
`                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                // 2026-10 新增：退出全屏后重新同步状态栏外观，
                // 否则清掉 FLAG_FULLSCREEN 时状态栏可能停留在透明/错误图标状态
                syncStatusBarAppearance();
                // 2026-10 新增：恢复预留条
                setFullscreenInsets(false);`,
'5) 退全屏时恢复预留条');

// ---------------------------------------------------------------- 6) 新增方法
rep(
`    // ======================================================================
    // 2026-10 新增：状态栏外观同步（时间/图标可读性）`,
`    // ======================================================================
    // 2026-10 新增：显式预留系统栏区域
    // ----------------------------------------------------------------------
    // 背景：Android 15（API 35）起，targetSdk >= 35 的应用被**强制 edge-to-edge**，
    //       系统不再自动给窗口留出状态栏/导航栏区域。不自己消费 WindowInsets 的
    //       应用，内容会被状态栏压住（用户反馈：Android 16 上被遮挡）。
    //
    // 做法：
    //   1. 用 ViewCompat.setOnApplyWindowInsetsListener 在根布局上监听 inset；
    //   2. 顶部 statusBarSpacer 的高度 = statusBars.top；
    //      底部 navBarSpacer 的高度 = navigationBars.bottom（取不到就用 systemBars.bottom）；
    //   3. mainContent 加左右内边距 = systemBars.left / right（横屏挖孔屏、手势区）；
    //   4. 同时用 WindowInsetsControllerCompat 让"手动控制 inset 派发"生效，
    //      避免个别 ROM 上 fitSystemWindows 与自己的 padding 叠加成双倍留白；
    //   5. 把最近一次读到的值缓存下来，供全屏切换（setFullscreenInsets）还原。
    //
    // 为什么用占位 View 而不是给根布局加 padding：
    //   根布局加 padding 会把 fullscreenContainer（视频全屏层）也一起缩进去，
    //   而全屏视频必须铺满整屏。用占位条可以只挤开"正常界面"。
    // ======================================================================
    private void setupWindowInsets() {
        try {
            final View root = findViewById(R.id.rootLayout);
            statusBarSpacer = findViewById(R.id.statusBarSpacer);
            navBarSpacer = findViewById(R.id.navBarSpacer);
            mainContent = findViewById(R.id.mainContent);
            if (root == null) {
                Log.w("MainActivity", "setupWindowInsets: 找不到 rootLayout，跳过");
                return;
            }

            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                try {
                    final int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    if (bottom <= 0) {
                        bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                    }
                    final int left = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left;
                    final int right = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right;

                    lastStatusBarInset = Math.max(0, top);
                    lastNavBarInset = Math.max(0, bottom);
                    lastSideInset = Math.max(0, Math.max(left, right));

                    applyInsetsToViews(isFullscreen);
                    Log.d("MainActivity", "insets: top=" + top + " bottom=" + bottom
                            + " side=" + lastSideInset);
                } catch (Throwable tr) {
                    Log.w("MainActivity", "处理 WindowInsets 失败（已忽略）", tr);
                }
                // 返回 insets 不消费，交给子 View（WebView / Compose）继续处理
                return insets;
            });

            // 让"手动控制派发"生效：部分 ROM 上会与自己的 padding 叠加出双倍留白
            try {
                WindowInsetsControllerCompat c =
                        ViewCompat.getWindowInsetsController(root);
                if (c != null) {
                    c.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
                }
            } catch (Throwable ignored) {}

            // 立即请求一次，避免首帧没有预留条
            ViewCompat.requestApplyInsets(root);
        } catch (Throwable tr) {
            Log.w("MainActivity", "setupWindowInsets 失败（已忽略）", tr);
        }
    }

    /** 按当前是否全屏，把预留条高度与左右内边距应用上去 */
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
    }

    /** 进入/退出全屏时切换预留条（由 onShowCustomView / onHideCustomView 调用） */
    private void setFullscreenInsets(boolean fullscreen) {
        try {
            applyInsetsToViews(fullscreen);
            Log.d("MainActivity", "setFullscreenInsets: fullscreen=" + fullscreen);
        } catch (Throwable tr) {
            Log.w("MainActivity", "setFullscreenInsets 失败（已忽略）", tr);
        }
    }

    // ======================================================================
    // 2026-10 新增：状态栏外观同步（时间/图标可读性）`,
'6) 新增 setupWindowInsets / applyInsetsToViews / setFullscreenInsets');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
