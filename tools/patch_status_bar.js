// ==========================================================================
// 状态栏可读性修复（2026-10，用户反馈：不同机型上状态栏透明导致看不到时间）
//
// 根因：
//   · themes.xml 把 android:statusBarColor 固定为纯白、windowLightStatusBar 固定 true；
//   · activity_main.xml 里根布局与 statusBarSpacer 背景也固定为纯白；
//   · 没有 values-night，夜间模式页面变深但图标仍是深色 → 时间/图标看不见；
//   · 进出全屏（WebView 视频）时只 set/clear FLAG_FULLSCREEN，
//     没有重新同步状态栏外观。
//
// 修复：统一走 syncStatusBarAppearance()，颜色与图标明暗都取自
//       values / values-night 的 colors_window.xml（白天浅底深图标、
//       夜间深底浅图标），并保证状态栏**不透明**，从而在任何机型上都有稳定对比度。
//   为兼容旧机型，同时用 setStatusBarColor + setSystemUiVisibility 与
//   WindowInsetsController 两套 API（后者仅 API 30+ 可用）。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;
const crlf = s => s.replace(/\r?\n/g, '\r\n');   // 这个文件也是 CRLF

function rep(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

// ---------------------------------------------------------- 1) onCreate 里应用
rep(
`        setContentView(R.layout.activity_main);

        // 游龙安全护盾 - 安全环境检测（反调试/反Root/反模拟器/签名校验）
        YouLongShield.init(this);`,
`        setContentView(R.layout.activity_main);

        // 2026-10 新增：按当前白天/夜间模式应用状态栏外观，
        // 避免部分机型状态栏透明导致时间与图标看不见（见 syncStatusBarAppearance 注释）
        syncStatusBarAppearance();

        // 游龙安全护盾 - 安全环境检测（反调试/反Root/反模拟器/签名校验）
        YouLongShield.init(this);`,
'1) onCreate 里应用状态栏外观');

// ---------------------------------------------------------- 2) 退出全屏后恢复
rep(
`                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
            }
        });`,
`                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                // 2026-10 新增：退出全屏后重新同步状态栏外观，
                // 否则清掉 FLAG_FULLSCREEN 时状态栏可能停留在透明/错误图标状态
                syncStatusBarAppearance();
            }
        });`,
'2) onHideCustomView 后重新同步');

// ---------------------------------------------------------- 3) 新增方法
rep(
`    /**
     * 向 WebView 注入 JS，将 blob: URL 对应的 Blob 对象读取为 base64 DataURL，`,
`    // ======================================================================
    // 2026-10 新增：状态栏外观同步（时间/图标可读性）
    // ----------------------------------------------------------------------
    // 背景：部分机型（以及本应用此前的写死配置）会出现状态栏透明，
    //       导致状态栏上的时间与系统图标压在浅色页面上看不见。
    //
    // 做法：
    //   1. 颜色与图标明暗都取自定义在
    //      res/values/colors_window.xml 与 res/values-night/colors_window.xml
    //      的 app_window_bg / app_light_status_bar —— 白天浅底深图标、
    //      夜间深底浅图标，随系统深浅色自动解析；
    //   2. 显式设置不透明的 statusBarColor，杜绝"透明状态栏"这条路径；
    //   3. 同时用旧 API（setSystemUiVisibility 的 LIGHT_STATUS_BAR）和
    //      新 API（WindowInsetsController.setSystemBarsAppearance）设置图标明暗，
    //      覆盖新老机型；两处都设不会冲突（都是幂等的状态设置）。
    // ======================================================================
    private void syncStatusBarAppearance() {
        try {
            final Window win = getWindow();
            if (win == null) return;

            final int bg = androidx.core.content.ContextCompat.getColor(this, R.color.app_window_bg);
            final boolean lightBar = getResources().getBoolean(R.bool.app_light_status_bar);

            // ① 状态栏底色：不透明，与页面背景一致
            win.setStatusBarColor(bg);
            // 导航栏同样处理，避免部分机型手势条区域出现同类问题
            win.setNavigationBarColor(bg);

            // ② 旧 API（API 23+）：控制状态栏图标明暗
            final View decor = win.getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int flags = decor.getSystemUiVisibility();
                if (lightBar) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                // 导航栏图标明暗（API 26+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (lightBar) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                decor.setSystemUiVisibility(flags);
            }

            // ③ 新 API（API 30+）：WindowInsetsController
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController c = win.getInsetsController();
                if (c != null) {
                    c.setSystemBarsAppearance(
                            lightBar
                                    ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    : 0,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    c.setSystemBarsAppearance(
                            lightBar
                                    ? android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                                    : 0,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            }

            Log.d("MainActivity", "syncStatusBarAppearance: bg=" + Integer.toHexString(bg)
                    + " lightBar=" + lightBar);
        } catch (Throwable tr) {
            // 状态栏只是外观问题，绝不能因此影响主流程
            Log.w("MainActivity", "syncStatusBarAppearance 失败（已忽略）", tr);
        }
    }

    /**
     * 向 WebView 注入 JS，将 blob: URL 对应的 Blob 对象读取为 base64 DataURL，`,
'3) 新增 syncStatusBarAppearance()');

// ---------------------------------------------------------- 4) 配置变化也同步
rep(
`    public void onConfigurationChanged(Configuration newConfig) {`,
`    public void onConfigurationChanged(Configuration newConfig) {
        // 2026-10 新增：深浅色切换后重新同步状态栏（否则图标明暗会滞后一拍）
        syncStatusBarAppearance();`,
'4) onConfigurationChanged 里同步');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
