package com.youlong.hd;

import com.youlong.hd.StrX;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.os.storage.StorageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import org.json.JSONObject;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
// 2026-10 新增：按 WindowInsets 显式预留状态栏/导航栏区域（Android 15+ 强制 edge-to-edge）
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import android.content.SharedPreferences;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.Gravity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;

import android.content.res.Configuration;

import android.util.Log;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;

import androidx.compose.ui.platform.ComposeView;
import com.youlong.hd.MainScreenKt;
import com.youlong.hd.YouLongShield;

import roro.stellar.Stellar;

// 【开源版已移除】
//   import com.youlong.hd.SignatureVerifier;      —— 内置官方签名指纹，随签名材料一并移除
//   import com.youlong.hd.PiracyWarningActivity;  —— 盗版提示页，依赖同上
// AssetsEncryptor 仍保留：它同时提供通用的字节读取工具（readAllBytes）。
import com.youlong.hd.AssetsEncryptor;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private WebView webView;
    private ImageButton backButton;
    private boolean doubleBackToExitPressedOnce = false;
    // 自启闹钟专用 requestCode（仅用于清理旧版本残留的"自动弹回主界面"闹钟）
    private static final int RESTART_ALARM_REQ = 0x5A1;
    // 通知栏"点击回到护盾"专用 requestCode（与自启闹钟分开，互不干扰）
    private static final int NOTIFY_OPEN_REQ = 0x5A2;
    // 上一次防终结保活时刻——冷却用，避免反复拉起服务
    static final String KEY_AUTO_RESTART_AT = "auto_restart_at";
    private int statusBarHeight = 0;
    private Handler transparencyHandler = new Handler();
    private Runnable transparencyRunnable;
    private ValueCallback<Uri[]> uploadCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1000;
    private SensorManager sensorManager;
    private Vibrator vibrator;
    private long lastSensorUpdateTime = 0;
    private PermissionRequest pendingWebPermissionRequest;
    private DownloadManager downloadManager;

    // 2026-10 新增：权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;
    private static final int REQ_APPLIST_PERMISSION = 0x7A22;
    // 一打开应用就顺序请求权限：共用一个请求码，靠队列区分是第几个
    private static final int REQ_ENTRY_PERMISSION = 0x7A23;
    // 国内 ROM「读取已安装应用列表」专用权限（com.android.permission.GET_INSTALLED_APPS）
    private static final int REQ_OEM_APPLIST_PERMISSION = 0x7A24;

    /** 待请求的权限队列（先通知、后应用列表） */
    private final java.util.ArrayDeque<String> entryPermQueue = new java.util.ArrayDeque<>();

    // 2026-10 新增：系统栏"预留条"相关 View 与最近一次读到的 inset 值
    // 见 applyWindowInsets() 与 setFullscreenInsets() 的说明。
    // 2026-10 白屏修复后：不再用占位 View，inset 直接做成 mainContent 的 padding。
    private View mainContent;
    private int lastStatusBarInset = 0;
    private int lastNavBarInset = 0;
    private int lastSideInset = 0;

    // 后台解密线程池
    private ExecutorService executor;
    // 加载遮罩
    private FrameLayout loadingOverlay;

    // ===== 按需解密模式：资源被请求时才解密，用后即毁，明文不驻留内存 =====
    private static final String LOCAL_SCHEME = StrX.d(StrX.LOCAL_SCHEME);

    // ===== 首页资源名（开源版：明文 assets，不再有 .html → .java 的加密改名）=====
    private static final String ASSET_INDEX = "index.html";

    // ===== SPA 性能优化：index.html 为单页应用，tab 切换不再重新 loadUrl =====
    // 首次加载后标记已就绪，后续切 tab 用 evaluateJavascript 调用页面内全局函数，
    // 避免每次重新解密 + 重新解析 279KB 混淆 JS（页面切换提速 1-2s）
    private volatile boolean indexPageReady = false;
    private int currentTabIndex = 0;

    // 全屏视频相关
    private FrameLayout fullscreenContainer;
    private WebView fullscreenWebView;
    private ImageButton exitFullscreenButton;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean isFullscreen = false;

    // Shizuku 相关
    private static final int REQUEST_CODE_STELLAR = 1001;

    private final ContentObserver rotationObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            checkAutoRotateAndUpdate();
        }
    };


    // ======================================================================
    // 2026-10 新增（用户需求）：进入应用权限门槛 —— 外层实现
    // ----------------------------------------------------------------------
    // 这些判定放在 MainActivity 外层而不是 JavaScriptInterface 内部，
    // 是因为 onRequestPermissionsResult()（Activity 回调）也要用它们；
    // 内部类里的同名方法只做委托，保证只有一份实现。
    // ======================================================================

    /** 组装权限门槛 JSON，供网页与权限回调共用 */
    private String buildEntryRequirementsJson() {
        boolean notif = hasNotificationPermissionOuter();
        boolean apps = hasAppListAccessOuter();
        StringBuilder missing = new StringBuilder("[");
        boolean first = true;
        if (!notif) { missing.append("\"notification\""); first = false; }
        if (!apps) { missing.append(first ? "\"applist\"" : ",\"applist\""); }
        missing.append("]");
        return "{\"notification\":" + notif
                + ",\"applist\":" + apps
                + ",\"ok\":" + (notif && apps)
                + ",\"missing\":" + missing + "}";
    }

    /** 通知权限是否可用（Android 13+ 需运行时授予；12L 及以下安装即授予） */
    private boolean hasNotificationPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 33) return true;
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return true;
        }
    }

    /**
     * 读取"已安装应用列表"的能力是否可用。
     *
     * <p>2026-10 修正（用户反馈）：**不要再让用户去设置里授权**。
     * 应用列表实际只需要"有启动图标的可见应用"，用
     * {@code queryIntentActivities(MAIN + LAUNCHER)} 就能拿到 ——
     * 这条路径在 Android 11+ **不需要任何权限、不需要用户操作**，
     * 也正是网页 getInstalledApps() 实际使用的方式。
     *
     * <p>原来用 getInstalledApplications(0) 判定，它受包可见性
     * （QUERY_ALL_PACKAGES）影响，很多 ROM 上会被过滤 → 误判成"缺权限" →
     * 界面上就冒出"检查读取应用权限"这种把用户丢去设置的按钮。
     * 现在判定与取数据走同一条路径，不会再误报。
     */
    private boolean hasAppListAccessOuter() {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN);
            it.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(it, 0);
            boolean ok = list != null && list.size() >= 3;
            Log.i("MainActivity", "应用列表可用性(launcher 查询)=" + ok
                    + " 数量=" + (list == null ? -1 : list.size()));
            return ok;
        } catch (Throwable tr) {
            Log.w("MainActivity", "queryIntentActivities 失败", tr);
            return false;
        }
    }

    /** 包是否已安装（外层版本，供环境判定复用） */
    private boolean isInstalledPkg(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * su 二进制的候选路径（仅作为"值得一试"的前置判断，**不作为 root 结论**）。
     *
     * ⚠️ 2026-10 修正：原实现用"文件存在"直接下 root 结论，导致**没 root 的用户
     *    被误判**（部分 ROM / 工程模式 / 残留文件会有同名文件，但设备并无 root）。
     *    现在这里只用来决定"要不要去试一次 su"，最终结论必须由
     *    {@link #suGrantsRoot()} 实证得出。
     */
    private boolean suBinaryPresent() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        };
        for (String p : paths) {
            try { if (new File(p).exists()) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * 实证：su 是否真的给到了 root。
     *
     * <p>执行 {@code su -c id}，只有输出里出现 {@code uid=0} 才算有 root。
     * 这是唯一可靠的判据 ——
     *   · Magisk / KernelSU / APatch 已授权 → 返回 uid=0 → 判有 root；
     *   · 没有 root（su 不存在）/ 用户拒绝授权 / 超时 → 判没有 root。
     *
     * <p>带 3 秒超时且只跑一次，避免卡住首页；对已 root 的设备最多弹一次
     * 系统的 su 授权框（用户拒绝后就按"没有 root"处理，且各家 su 会记住拒绝）。
     */
    private boolean suGrantsRoot() {
        // 先看包名：装了已知的 root 管理器才值得跑一次 su（减少无谓的尝试）
        boolean knownManager = isInstalledPkg("com.topjohnwu.magisk")
                || isInstalledPkg("io.github.huskydg.magisk")
                || isInstalledPkg("me.weishu.kernelsu")
                || isInstalledPkg("com.rifsxd.ksunext")
                || isInstalledPkg("me.bmax.apatch");
        if (!knownManager && !suBinaryPresent()) return false;

        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append('\n');
                } catch (Throwable ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                return false;
            }
            reader.join(500);
            String s = out.toString();
            boolean rooted = s.contains("uid=0");
            Log.i("MainActivity", "su -c id => " + s.replace('\n', ' ').trim() + "  rooted=" + rooted);
            return rooted;
        } catch (Throwable tr) {
            // su 不存在、权限被拒、进程异常 —— 一律按"没有 root"处理
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * root 判定诊断信息（**仅供排错**，不参与界面判定）。
     *
     * <p>如果仍有用户反馈"没 root 却被判定有 root"，把这段 JSON 发回来即可定位
     * 是哪一条命中：装了哪个包、哪个 su 路径存在、su 实际返回了什么。
     */
    private String buildRootDiagnosticsJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"rooted\":").append(deviceIsRooted());
        sb.append(",\"dhizuku\":").append(deviceHasDhizuku());

        // 命中/未命中的 root 管理器包
        sb.append(",\"packages\":{");
        String[] pkgs = {
                "com.topjohnwu.magisk", "io.github.huskydg.magisk",
                "me.weishu.kernelsu", "com.rifsxd.ksunext",
                "me.bmax.apatch", "eu.chainfire.supersu",
                "com.koushikdutta.superuser"
        };
        for (int i = 0; i < pkgs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(pkgs[i]).append("\":").append(isInstalledPkg(pkgs[i]));
        }
        sb.append("}");

        // 各 su 路径是否存在
        sb.append(",\"suPaths\":{");
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        };
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) sb.append(',');
            boolean exists = false;
            try { exists = new File(paths[i]).exists(); } catch (Throwable ignored) {}
            sb.append('\"').append(paths[i]).append("\":").append(exists);
        }
        sb.append("}");

        // su -c id 的实际输出
        String suOut = "";
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            final StringBuilder o = new StringBuilder();
            Thread r = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = br.readLine()) != null) o.append(l).append(' ');
                } catch (Throwable ignored) {}
            });
            r.setDaemon(true);
            r.start();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                suOut = "(timeout)";
            } else {
                r.join(500);
                suOut = o.toString().trim();
            }
        } catch (Throwable tr) {
            suOut = "(unavailable: " + tr.getClass().getSimpleName() + ")";
        }
        sb.append(",\"suIdOutput\":\"").append(suOut.replace("\"", "'")).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 设备是否 root（2026-10 改为"实证"判定，修掉没 root 被误判的问题）。
     *
     * <p>判据只有一条：{@code su -c id} 真的返回 {@code uid=0}。
     * 不再用"su 文件存在"或"装了某个包名"直接下结论。
     */
    private boolean deviceIsRooted() {
        try {
            return suGrantsRoot();
        } catch (Throwable tr) {
            return false;
        }
    }

    /** 是否安装了 Dhizuku */
    private boolean deviceHasDhizuku() {
        return isInstalledPkg("com.rosan.dhizuku");
    }

    /** 环境良好（绿色描边）提示的 JSON */
    private String buildEnvGoodHintJson() {
        boolean root = deviceIsRooted();
        boolean dhizuku = deviceHasDhizuku();
        boolean show = (!root && !dhizuku);
        return "{\"show\":" + show + ",\"root\":" + root
                + ",\"dhizuku\":" + dhizuku + "}";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ============================================================
        // 【开源版已移除】签名验证 + 防篡改检查 + 签名绑定资源密钥
        // ------------------------------------------------------------
        // 正式发布版在这里会：
        //   ① 校验 APK 签名证书指纹，不一致就 killProcess 直接闪退；
        //   ② 把证书指纹注入 AssetsEncryptor，用于解密内置前端资源
        //      （密钥 = KDF(种子 + 盐 + 证书指纹)，换签名就解不开）。
        // 开源包不含签名材料与资源加密密钥，前端资源以明文分发，
        // 因此这里不再做任何签名校验。
        // ============================================================

        setContentView(R.layout.activity_main);

        // 2026-10 新增：按当前白天/夜间模式应用状态栏外观，
        // 避免部分机型状态栏透明导致时间与图标看不见（见 syncStatusBarAppearance 注释）
        syncStatusBarAppearance();

        // 2026-10 新增：显式预留系统栏区域。Android 15+ 强制 edge-to-edge 后，
        // 不自己消费 WindowInsets 就会被状态栏/手势条压住内容。
        setupWindowInsets();

        // ==================================================================
        // 2026-10 新增（用户需求）：**一打开应用就先弹权限**
        // ------------------------------------------------------------------
        // 顺序：① 允许通知 → ② 允许读取应用列表。
        // 之前放在网页 init()/桥接就绪后请求，时序晚了一拍（表现为"不是刚打开就弹"）；
        // 现在改在原生 onCreate 里直接发起，不依赖 WebView。
        // 用 post 到主线程消息队列，确保窗口已经绑定、系统弹窗能正常出现。
        // ==================================================================
        getWindow().getDecorView().post(this::requestEntryPermissions);

        // 游龙安全护盾 - 安全环境检测（反调试/反Root/反模拟器/签名校验）
        YouLongShield.init(this);

        // 保持屏幕常亮
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 初始化线程池和下载管理器
        executor = Executors.newSingleThreadExecutor();
        downloadManager = new DownloadManager(this);

        // 编程创建 WebView（不再从 XML 中查找，因为 WebView 现在通过 Compose 的 AndroidView 管理）
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        backButton = findViewById(R.id.backButton);

        // 初始化全屏相关视图
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        fullscreenWebView = findViewById(R.id.fullscreenWebView);
        exitFullscreenButton = findViewById(R.id.exitFullscreenButton);
        exitFullscreenButton.setOnClickListener(v -> exitFullscreen());

        // 先设置 WebView，再初始化 Compose 主界面（MainScreen 需要 WebView 实例）
        setupWebView();

        // 初始化 Compose 主界面：将 WebView 传入 MainScreen 全屏显示
        ComposeView mainContent = findViewById(R.id.mainContent);
        MainScreenKt.setMainContent(mainContent, webView, index -> {
            // 根据 tab 索引加载对应页面：首页→PAGES1, 收藏→PAGES2, 我的→PAGES3
            switchIndexPage(index + 1);
            return kotlin.Unit.INSTANCE;
        });

        getStatusBarHeight();
        setupStatusBarPadding();
        setupBackButton();
        startForegroundService();
        checkAutoRotateAndUpdate();
        registerRotationObserver();
        setupSensors();
        initStellar();

        // ===== 病毒库：每次进入 App 都自动拉取服务器最新数据（先用本地缓存立即生效）=====
        VirusDb.loadFromCache(this);
        VirusDb.refreshAsync(this);

        decryptAllAssetsAndLoad();
    }

    private void getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        if (statusBarHeight == 0) {
            statusBarHeight = (int) (24 * getResources().getDisplayMetrics().density);
        }
    }

    private void setupStatusBarPadding() {
        // 2026-10 白屏修复：原来这里会给 statusBarSpacer 这个占位 View 设高度，
        // 但占位 View 方案在 ConstraintLayout 里会被拉伸，导致 mainContent 高度归零
        // （整屏白屏）。现在状态栏区域改由 applyInsetsToViews() 直接做成
        // mainContent 的 paddingTop，这里只剩「返回按钮下移，避开状态栏」这一件事。

        if (backButton != null) {
            ViewGroup.LayoutParams p = backButton.getLayoutParams();
            if (p instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams backButtonParams = (ConstraintLayout.LayoutParams) p;
                backButtonParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + 10 + 40;
                backButton.setLayoutParams(backButtonParams);
            }
        }
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();

        // ==================== 防 WebView dump：显式关闭远程调试 ====================
        // 防止攻击者用 Chrome DevTools 远程调试抓取页面明文/JS 逻辑
        webView.setWebContentsDebuggingEnabled(false);

        // ==================== JavaScript 必须开启 ====================
        webSettings.setJavaScriptEnabled(true);

        // ==================== DOM 存储 ====================
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);

        // ==================== 缓存模式：优先本地缓存，避免重复加载资源 ====================
        webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        // ==================== 允许 WebView 访问文件 ====================
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // ==================== 视口 & 缩放 ====================
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(false);

        // ==================== 最佳性能模式 ====================
        // 硬件加速层
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 自动加载图片
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        // 布局算法：NORMAL 模式性能最佳
        webSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        // 离屏预渲染（Android 6.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webSettings.setOffscreenPreRaster(true);
        }

        // ==================== 内存 & 存储路径 ====================
        webSettings.setDatabasePath(getApplicationContext().getFilesDir().getPath() + "/databases");
        webSettings.setSaveFormData(true);
        webSettings.setSavePassword(false);

        // ==================== 媒体 ====================
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        // ==================== 混合内容 ====================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // ==================== 地理位置 ====================
        webSettings.setGeolocationEnabled(true);

        // ==================== 字体缩放（避免异常缩放） ====================
        webSettings.setTextZoom(100);

        // 硬件加速绘图缓存
        webView.setDrawingCacheEnabled(true);
        webView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        // 设置下载监听器
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                       String mimeType, long contentLength) {
                if (downloadManager == null) return;

                if (url != null && url.startsWith("blob:")) {
                    // blob: 协议 URL（JS URL.createObjectURL 生成），需在 WebView 内部读取 Blob 转为 base64
                    downloadManager.prepareBlobDownload(url, userAgent, contentDisposition, mimeType);
                    injectBlobReaderJs(url);
                } else {
                    downloadManager.downloadFile(url, userAgent, contentDisposition, mimeType);
                }
            }
        });

        // ==================== Blob URL 下载支持 ====================
        // 当网页通过 URL.createObjectURL(blob) 生成 blob: 协议的文件下载时，
        // HttpURLConnection 无法识别该协议。此方法在 WebView 内部用 JS 将 Blob 读为 base64 回传

        webView.addJavascriptInterface(new JavaScriptInterface(), "Android");
        // 屏幕滤镜桥 - 供 pingmu.html 调用（名称运行时解密防静态提取）
        webView.addJavascriptInterface(new ScreenFilterBridge(this), StrX.d(StrX.BRIDGE_ANDROID_NATIVE));
        // Shizuku桥 - 供 shizuku.html 调用（名称运行时解密防静态提取）
        webView.addJavascriptInterface(new StellarBridge(), StrX.d(StrX.BRIDGE_SHIZUKU));

        webView.setWebViewClient(new WebViewClient() {
            // ===== 按需解密拦截器：https://app.local/* 请求从 assets 读密文→解密→用后即毁，绝不落盘 =====
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(LOCAL_SCHEME)) {
                    String path = url.substring(LOCAL_SCHEME.length());
                    // 去掉 query 参数
                    int qIdx = path.indexOf('?');
                    if (qIdx >= 0) path = path.substring(0, qIdx);
                    // 去掉 fragment
                    int fIdx = path.indexOf('#');
                    if (fIdx >= 0) path = path.substring(0, fIdx);
                    // 默认首页
                    if (path.isEmpty()) path = "index.html";

                    WebResourceResponse response = decryptAndServe(path);
                    if (response != null) return response;
                }
                return super.shouldInterceptRequest(view, request);
            }

            // 兼容旧版 API (API < 21) — 虽然现在都 >= 21，保留以防万一
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null && url.startsWith(LOCAL_SCHEME)) {
                    String path = url.substring(LOCAL_SCHEME.length());
                    int qIdx = path.indexOf('?');
                    if (qIdx >= 0) path = path.substring(0, qIdx);
                    int fIdx = path.indexOf('#');
                    if (fIdx >= 0) path = path.substring(0, fIdx);
                    if (path.isEmpty()) path = "index.html";

                    WebResourceResponse response = decryptAndServe(path);
                    if (response != null) return response;
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") || url.startsWith("javascript:")) {
                    return false;
                }

                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            startActivity(intent);
                            return true;
                        }
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }

                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 注入 Blob 拦截器：拦截 URL.createObjectURL / revokeObjectURL，
                // 确保下载触发时 Blob 对象尚未被页面 JS 销毁
                String js = "(function(){" +
                    "if(window.__blobStore)return;" +
                    "window.__blobStore={};" +
                    "var _c=URL.createObjectURL;" +
                    "URL.createObjectURL=function(b){var u=_c.call(URL,b);window.__blobStore[u]=b;return u;};" +
                    "var _r=URL.revokeObjectURL;" +
                    "URL.revokeObjectURL=function(u){setTimeout(function(){delete window.__blobStore[u];_r.call(URL,u);},30000);};" +
                "})();";
                view.evaluateJavascript(js, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                updateBackButtonVisibility();

                // 判断是否首页：首页通过拦截器加载，URL 为 "https://app.local/index.html"（含 index.html 后缀），
                // 根路径（无文件名）也要视为首页
                String urlNoQuery = (url != null && url.contains("?"))
                        ? url.substring(0, url.indexOf("?"))
                        : url;
                boolean isIndexPage = false;
                if (urlNoQuery != null) {
                    // 去掉 scheme 前缀，得到 "app.local" / "app.local/index.html" / "app.local/xxx.html"
                    String path = urlNoQuery;
                    int schemeEnd = path.indexOf("://");
                    if (schemeEnd >= 0) path = path.substring(schemeEnd + 3);
                    // 提取文件名：根路径（无子路径）或 index.html 都算首页
                    int slashIdx = path.indexOf('/');
                    String fileName = (slashIdx < 0) ? "" : path.substring(slashIdx + 1);
                    isIndexPage = fileName.isEmpty() || fileName.equals("index.html");
                }

                // ===== SPA 性能优化：index 主页面加载完成后标记就绪 =====
                // 之后 tab 切换走 evaluateJavascript 内部切页，不再全量重载
                if (isIndexPage) {
                    indexPageReady = true;
                    // 初始 tab 由 URL ?page=PAGESn 控制，无需再次切换
                    currentTabIndex = -1;
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showErrorPage(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    showErrorPage(view);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
            }

            private void showErrorPage(WebView view) {
                String errorHtml = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'></head>"
                    + "<body style='display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,sans-serif;'>"
                    + "<div style='text-align:center;padding:40px;'>"
                    + "<p style='font-size:1.1rem;color:#333;line-height:1.8;margin-bottom:30px;'>软件资源加载出现问题。<br>请重启软件。</p>"
                    + "<button onclick='Android.closeApp()' style='padding:12px 40px;font-size:1rem;color:#fff;background:#007aff;border:none;border-radius:8px;cursor:pointer;'>重启软件</button>"
                    + "</div></body></html>";
                view.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // ==================== JS console（静默：不转发到 logcat，防止泄露 JS 逻辑） ====================
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                return true;
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                uploadCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                // 不强制覆盖 MIME 类型，保留 HTML 中 accept="image/*" 的过滤
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    uploadCallback.onReceiveValue(null);
                    uploadCallback = null;
                    return false;
                }

                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    pendingWebPermissionRequest = request;

                    String[] resources = request.getResources();
                    for (String resource : resources) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            // 本应用只加载本地 assets/index.html（单页应用），该页面中不含任何
                            // 麦克风相关代码（无 getUserMedia / MediaRecorder / SpeechRecognition），
                            // 因此这里原先的 RECORD_AUDIO 申请属多余权限。
                            // 现在直接拒绝该资源并清理挂起引用，避免留下悬空请求。
                            // 若将来确实要支持网页录音：在 AndroidManifest 恢复 RECORD_AUDIO，
                            // 并把这里改回「检查权限 → 申请 → return」的写法即可。
                            Log.w("MainActivity",
                                    "WebView 请求麦克风(RESOURCE_AUDIO_CAPTURE)，已拒绝：本应用不使用录音功能");
                            pendingWebPermissionRequest = null;
                            request.deny();
                            return;
                        }
                    }

                    request.grant(resources);
                    // 放行后清掉引用：原先这里不清，导致该请求一直挂在字段上，
                    // 之后任何一次权限回调都会在 onRequestPermissionsResult() 里
                    // 再次对它 grant（见下方改动说明）。
                    pendingWebPermissionRequest = null;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                super.onShowCustomView(view, callback);
                if (view instanceof ViewGroup) {
                    customViewCallback = callback;
                    fullscreenContainer.setVisibility(View.VISIBLE);
                    fullscreenContainer.addView(view);
                    webView.setVisibility(View.GONE);
                    isFullscreen = true;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    }
                    // 2026-10 新增：全屏播放视频时让内容真正铺满，去掉预留条
                    setFullscreenInsets(true);
                }
            }

            @Override
            public void onHideCustomView() {
                super.onHideCustomView();
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                fullscreenContainer.removeAllViews();
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                isFullscreen = false;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                // 2026-10 新增：退出全屏后重新同步状态栏外观，
                // 否则清掉 FLAG_FULLSCREEN 时状态栏可能停留在透明/错误图标状态
                syncStatusBarAppearance();
                // 2026-10 新增：恢复预留条
                setFullscreenInsets(false);
            }
        });
    }

    // ======================================================================
    // 2026-10 新增（用户需求）：一打开应用就顺序请求两项权限
    // ----------------------------------------------------------------------
    //   ① POST_NOTIFICATIONS（Android 13+ 才是运行时权限）→ 系统弹「允许通知」
    //   ② QUERY_ALL_PACKAGES（读取已安装应用列表）
    // 逐个请求：上一个有结果后再发下一个，保证"先通知、后应用列表"的顺序。
    // ======================================================================
    private void requestEntryPermissions() {
        try {
            entryPermQueue.clear();
            // ① 允许通知
            if (!hasNotificationPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
            // ② 允许读取应用列表
            //
            // ⚠️ 说明：在标准 Android 上 QUERY_ALL_PACKAGES 是 normal 权限（安装即授予），
            //    系统不会弹框；但在**国内 ROM（华为 / HyperOS / ColorOS 等）**上它被当作
            //    敏感权限，会向用户弹窗要求授权（华为应用市场审核文档里就明确有这一条）。
            //    因此这里照常发起运行时请求：
            //      · ROM 支持 → 弹出授权框，用户点允许即完成；
            //      · ROM 不支持 → 系统立刻回调"已授予"，不弹框也不卡流程；
            //      · 若最终仍未拿到权限，handleEntryPermissionResult 会自动跳到
            //        应用信息页让用户手动打开，保证任何 ROM 上都有办法授权。
            if (!hasAppListPermissionOuter()) {
                // 国内 ROM：走厂商的 GET_INSTALLED_APPS（会弹「允许读取应用列表」）
                // 原生 / 海外版：走 QUERY_ALL_PACKAGES（安装即授予，不会弹框）
                if (isRuntimePermissionEnable()) {
                    entryPermQueue.add(PERM_GET_INSTALLED_APPS);
                } else {
                    entryPermQueue.add(android.Manifest.permission.QUERY_ALL_PACKAGES);
                }
            }
            if (entryPermQueue.isEmpty()) {
                Log.i("MainActivity", "进入应用权限检查：无需请求");
                notifyWebPermissions();
                return;
            }
            Log.i("MainActivity", "进入应用权限检查：待请求 " + entryPermQueue.size() + " 项");
            requestNextEntryPermission();
        } catch (Throwable tr) {
            Log.w("MainActivity", "requestEntryPermissions 失败（已忽略）", tr);
        }
    }

    /** 依次弹出下一个权限请求 */
    private void requestNextEntryPermission() {
        if (entryPermQueue.isEmpty()) {
            notifyWebPermissions();
            return;
        }
        final String permission = entryPermQueue.pollFirst();
        try {
            Log.i("MainActivity", "请求权限：" + permission);
            requestPermissions(new String[]{permission}, REQ_ENTRY_PERMISSION);
        } catch (Throwable tr) {
            Log.w("MainActivity", "请求权限失败：" + permission, tr);
            requestNextEntryPermission();
        }
    }

    /**
     * 顺序请求结束后：若「读取应用列表」仍不可用，就引导用户去应用信息页手动打开。
     *
     * <p>为什么需要这一步：国内 ROM 上该权限会弹窗（用户点允许即可）；
     * 但标准 Android 上它不可弹窗，而个别 ROM 又默认关掉 —— 这时界面上会
     * 一直显示"读取已安装应用"缺失。给一个直达应用信息页的入口，
     * 用户总能自己把它打开，不会出现"根本获取不到"的死胡同。
     */
    /** 打开本应用「应用信息」页（外层版本，供权限兜底使用） */
    private void openAppInfoPageOuter() {
        try {
            Intent it = new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            it.setData(android.net.Uri.parse("package:" + getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Throwable tr) {
            Log.w("MainActivity", "打开应用信息页失败", tr);
        }
    }

    private void handleEntryPermissionResult(String deniedPermission) {
        try {
            final boolean appListDenied =
                    android.Manifest.permission.QUERY_ALL_PACKAGES.equals(deniedPermission)
                            || PERM_GET_INSTALLED_APPS.equals(deniedPermission);
            if (appListDenied) {
                Log.w("MainActivity", "读取应用列表权限未获得，跳到应用信息页引导用户手动开启");
                openAppInfoPageOuter();
            }
            // 通知权限被拒：不强制跳转，网页上的卡片会提示用户，点按钮可再次请求
            notifyWebPermissions();
        } catch (Throwable tr) {
            Log.w("MainActivity", "handleEntryPermissionResult 失败（已忽略）", tr);
        }
    }

    /** 把权限状态回灌网页，让界面上的引导卡片同步刷新 */
    private void notifyWebPermissions() {
        try {
            final String js =
                    "if(window.__dshOnPermResult)window.__dshOnPermResult("
                            + buildEntryRequirementsJson() + ");";
            if (webView != null) {
                webView.post(() -> {
                    try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable tr) {
            Log.w("MainActivity", "回灌权限状态失败", tr);
        }
    }

    /** 国内 ROM 的「读取已安装应用列表」权限名（vivo / 小米 / OPPO 等） */
    private static final String PERM_GET_INSTALLED_APPS =
            "com.android.permission.GET_INSTALLED_APPS";

    /** 小米系 ROM 的应用列表权限提供者包名 */
    private static final String MIUI_PERM_PROVIDER = "com.lbe.security.miui";

    /**
     * 当前 ROM 是否支持动态申请「读取已安装应用列表」（2026-10 新增）。
     *
     * <p>并非所有国内系统版本都支持，海外版（原生 Android）没有这个权限，
     * 所以申请前必须先判断：
     *   · vivo / 小米 / OPPO 等国内 ROM 的通用判断方式 —— 读系统隐藏配置项
     *     {@code oem_installed_apps_runtime_permission_enable}；
     *   · 小米另有判断方式：检查权限提供者包名是否为 {@code com.lbe.security.miui}。
     */
    private boolean isRuntimePermissionEnable() {
        try {
            final int v = android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    "oem_installed_apps_runtime_permission_enable", 0);
            if (v > 0) return true;
        } catch (Throwable ignored) {}
        // 小米兜底：装了 MIUI 的权限提供者就认为支持
        try {
            return isInstalledPkg(MIUI_PERM_PROVIDER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 国内 ROM 的 GET_INSTALLED_APPS 是否已授予 */
    private boolean hasOemInstalledAppsPermission() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(PERM_GET_INSTALLED_APPS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 读取应用列表权限是否已授予（两条路互补）。
     *
     * <p>海外版 / 原生 Android：靠 {@code QUERY_ALL_PACKAGES}（安装即授予）；
     * <p>国内 ROM：靠 {@code com.android.permission.GET_INSTALLED_APPS}（需用户授权）。
     * 任一满足即认为可用。
     */
    private boolean hasAppListPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            // 国内 ROM 的权限：支持该机制时必须已授予
            if (isRuntimePermissionEnable()) {
                return hasOemInstalledAppsPermission();
            }
            // 原生 Android：QUERY_ALL_PACKAGES 安装即授予
            return checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 申请「读取已安装应用列表」权限（国内 ROM 会弹系统授权框）。
     *
     * @return true = 已发起申请（或无需申请）；false = 该 ROM 不支持该机制、
     *         或已被永久拒绝，需要引导用户去应用信息页手动打开
     */
    private boolean requestOemInstalledAppsPermission() {
        try {
            if (!isRuntimePermissionEnable()) {
                Log.w("MainActivity", "该 ROM 不支持动态申请读取应用列表（无 oem 开关），走兜底引导");
                return false;
            }
            if (hasOemInstalledAppsPermission()) {
                Log.i("MainActivity", "读取应用列表权限已授予，无需申请");
                return true;
            }
            Log.i("MainActivity", "发起申请：" + PERM_GET_INSTALLED_APPS);
            requestPermissions(new String[]{PERM_GET_INSTALLED_APPS}, REQ_OEM_APPLIST_PERMISSION);
            return true;
        } catch (Throwable tr) {
            Log.w("MainActivity", "申请 " + PERM_GET_INSTALLED_APPS + " 失败", tr);
            return false;
        }
    }

    // ======================================================================
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

    /**
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
     * 向 WebView 注入 JS，将 blob: URL 对应的 Blob 对象读取为 base64 DataURL，
     * 然后通过 Android.onBlobData() 回传给 Java 层处理。
     * 解决 DownloadListener 收到 blob: 协议 URL 导致 "unknown Protocol: blob" 的问题。
     */
    private void injectBlobReaderJs(String blobUrl) {
        String escapedBlobUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function(){" +
            "try{" +
                // 优先从 __blobStore（由 onPageStarted 注入的拦截器填充）直接读取 Blob 引用
                "var blob=window.__blobStore&&window.__blobStore['" + escapedBlobUrl + "'];" +
                "if(blob){" +
                    "var reader=new FileReader();" +
                    "reader.onloadend=function(){Android.onBlobData(reader.result);};" +
                    "reader.onerror=function(){Android.onBlobError('读取文件内容失败');};" +
                    "reader.readAsDataURL(blob);" +
                "}else{" +
                    // 回退：XHR 读取（Blob 可能已被 revoke）
                    "var xhr=new XMLHttpRequest();" +
                    "xhr.open('GET','" + escapedBlobUrl + "',true);" +
                    "xhr.responseType='blob';" +
                    "xhr.onload=function(){" +
                        "var r2=new FileReader();" +
                        "r2.onloadend=function(){Android.onBlobData(r2.result);};" +
                        "r2.readAsDataURL(xhr.response);" +
                    "};" +
                    "xhr.onerror=function(){Android.onBlobError('读取文件内容失败');};" +
                    "xhr.send();" +
                "}" +
            "}catch(e){Android.onBlobError(e.message||'未知错误');}" +
        "})();";
        webView.evaluateJavascript(js, null);
    }

    private void decryptAllAssetsAndLoad() {
        // ===== 按需解密模式 =====
        // 不再启动时一次性解密全部资源（避免明文长期驻留内存可被 dump）。
        // 改为：每个资源在 shouldInterceptRequest 被请求时才临时解密，
        // 通过 WipeInputStream 传给 WebView，读完立即清零明文 —— 用后即毁。
        showLoading();

        executor.execute(() -> {
            // 清理旧版磁盘缓存（如果有）
            File oldCache = new File(getCacheDir(), "web");
            if (oldCache.exists()) deleteRecursive(oldCache);

            // 开源版：前端资源是**明文** assets（不再有"解密预校验"这一步），
            // 这里只确认 assets/index.html 存在且非空，避免白屏时无从判断。
            boolean ok = false;
            try {
                InputStream is = getAssets().open(ASSET_INDEX);
                byte[] head = new byte[64];
                int n = is.read(head);
                is.close();
                ok = n > 0;
            } catch (Exception e) {
                // 静默失败：不输出异常栈
            }

            final boolean success = ok;
            runOnUiThread(() -> {
                hideLoading();
                if (!success) {
                    Toast.makeText(MainActivity.this, "资源解密失败", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                // 走拦截器按需解密加载首页（URL 含 index.html，导航栏判断兼容）
                webView.loadUrl(LOCAL_SCHEME + "index.html");
            });
        });
    }

    /**
     * SPA 性能优化：切换主界面 tab（首页/收藏/我的）。
     * index.html 为单页应用（showHome/showFavorites/showProfile 为全局函数声明，
     * 混淆时通过 reservedNames 保留函数名），
     * 首次加载后 tab 切换直接 evaluateJavascript 内部切页，不再重新 loadUrl，
     * 避免每次重复解密 + 重新解析全部混淆 JS（切换提速 1-2s）。
     * 未就绪或页面异常时降级为 loadUrl（带 ?page= 参数，行为与原来一致）。
     */
    private void switchIndexPage(final int pageNo) {
        if (indexPageReady) {
            // 页面已加载：直接调用内部切页函数（毫秒级，无重载）
            String fn;
            if (pageNo == 1) fn = "showHome";
            else if (pageNo == 2) fn = "showFavorites";
            else fn = "showProfile";
            currentTabIndex = pageNo;
            // 直接调用全局函数（混淆后保留函数名；window.showHome 可能被混淆为索引访问，故不依赖）
            String js = "if(typeof " + fn + "==='function'){" + fn + "();true}else{false}";
            webView.evaluateJavascript(js, value -> {
                // 页面内部函数缺失（如特殊状态），降级全量重载保证可用
                if (value != null && value.contains("false")) {
                    indexPageReady = false;
                    webView.loadUrl(LOCAL_SCHEME + "index.html?page=PAGES" + pageNo);
                }
            });
        } else {
            // 首次加载或页面异常：全量加载（URL 参数驱动内部切页）
            currentTabIndex = pageNo;
            webView.loadUrl(LOCAL_SCHEME + "index.html?page=PAGES" + pageNo);
        }
    }

    /**
     * 提供 WebView 需要的资源（开源版：直接读 assets 明文）。
     *
     * <p>正式发布版这里是"按需解密"：assets 里的 .html 在构建期被改名为 .java
     * 并用 AES-256-GCM 加密（密钥 = KDF(种子 + 盐 + 签名证书指纹)），
     * 由本方法现场解密后经 {@link WipeInputStream} 交给 WebView，读完即清零。
     *
     * <p>开源包移除了签名绑定密钥，前端资源以明文随包分发，
     * 因此这里改为直接读取同名 assets 文件。要恢复加密方案，
     * 需自行实现构建期加密 + 运行期解密（并自行保管密钥）。
     */
    private WebResourceResponse decryptAndServe(String path) {
        InputStream is = null;
        try {
            is = getAssets().open(path);
            byte[] data = AssetsEncryptor.readAllBytes(is);
            is.close();
            is = null;

            String mime = getMimeType(path);
            String encoding = mime.startsWith("text/")
                    || mime.equals("application/javascript")
                    || mime.equals("application/json") ? "UTF-8" : null;
            return new WebResourceResponse(mime, encoding, new java.io.ByteArrayInputStream(data));
        } catch (Exception e) {
            // 静默失败：不输出路径与异常细节
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 安全输入流：读取结束或 close 时立即将底层明文数组清零（用后即毁）。
     * 防止 WebView 渲染完成后明文仍残留在内存堆中。
     */
    private static class WipeInputStream extends java.io.InputStream {
        private byte[] buf;
        private int pos;
        private int count;

        WipeInputStream(byte[] data) {
            this.buf = data;
            this.pos = 0;
            this.count = data != null ? data.length : 0;
        }

        @Override
        public int read() {
            if (buf == null) return -1;
            if (pos >= count) { wipe(); return -1; }
            return buf[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (buf == null) return -1;
            if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
            if (pos >= count) { wipe(); return -1; }
            int n = Math.min(len, count - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            if (pos >= count) wipe();
            return n;
        }

        @Override
        public int available() {
            return buf == null ? 0 : count - pos;
        }

        @Override
        public long skip(long n) {
            if (buf == null || n <= 0) return 0;
            int skip = (int) Math.min(n, count - pos);
            pos += skip;
            if (pos >= count) wipe();
            return skip;
        }

        @Override
        public void close() {
            wipe();
        }

        private void wipe() {
            if (buf != null) {
                java.util.Arrays.fill(buf, (byte) 0);
                buf = null;
            }
        }
    }

    /**
     * 根据文件名返回 MIME 类型（供 shouldInterceptRequest 使用）
     */
    private static String getMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    /**
     * 生成缓存键：versionCode + lastUpdateTime
     * 每次 APK 升级/安装时 lastUpdateTime 都会变，确保缓存自动失效
     */
    private String getCacheKey() {
        try {
            android.content.pm.PackageInfo pkgInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? pkgInfo.getLongVersionCode() + "_" + pkgInfo.lastUpdateTime
                : pkgInfo.versionCode + "_" + pkgInfo.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return "0_0";
        }
    }

    private void showLoading() {
        if (loadingOverlay == null) {
            loadingOverlay = new FrameLayout(this);
            loadingOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loadingOverlay.setBackgroundColor(0xFFFFFFFF);

            LinearLayout ll = new LinearLayout(this);
            ll.setOrientation(LinearLayout.VERTICAL);
            ll.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams llParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llParams.gravity = Gravity.CENTER;
            ll.setLayoutParams(llParams);

            ProgressBar pb = new ProgressBar(this);
            ll.addView(pb);

            TextView tv = new TextView(this);
            tv.setText("正在加载资源...");
            tv.setTextColor(0xFF333333);
            tv.setTextSize(14);
            LinearLayout.LayoutParams tvParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tvParams.topMargin = 20;
            tv.setLayoutParams(tvParams);
            ll.addView(tv);

            loadingOverlay.addView(ll);
            ((ViewGroup) findViewById(android.R.id.content)).addView(loadingOverlay);
        }
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    private void checkAutoRotateAndUpdate() {
        boolean autoRotate;
        try {
            autoRotate = Settings.System.getInt(
                    getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION
            ) == 1;
        } catch (Settings.SettingNotFoundException e) {
            autoRotate = false;
        }

        if (autoRotate) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void registerRotationObserver() {
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                rotationObserver
        );
    }

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        
        if (sensorManager != null) {
            registerAllSensors();
        }
    }

    private void registerAllSensors() {
        registerSensor(Sensor.TYPE_ACCELEROMETER);
        registerSensor(Sensor.TYPE_GYROSCOPE);
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD);
        registerSensor(Sensor.TYPE_LIGHT);
        registerSensor(Sensor.TYPE_PROXIMITY);
        registerSensor(Sensor.TYPE_PRESSURE);
        registerSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        registerSensor(Sensor.TYPE_RELATIVE_HUMIDITY);
    }

    private void registerSensor(int type) {
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // 传感器数据节流：最多每 200ms 更新一次到 WebView
        long now2 = System.currentTimeMillis();
        if (now2 - lastSensorUpdateTime < 200) {
            return;
        }
        lastSensorUpdateTime = now2;

        String sensorType = getSensorName(event.sensor.getType());
        float[] values = event.values;

        webView.evaluateJavascript(
            "if(window.onSensorData) window.onSensorData('" + sensorType + "', " + java.util.Arrays.toString(values) + ");", 
            null
        );
    }

    private String getSensorName(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
            case Sensor.TYPE_GYROSCOPE: return "gyroscope";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnetic";
            case Sensor.TYPE_LIGHT: return "light";
            case Sensor.TYPE_PROXIMITY: return "proximity";
            case Sensor.TYPE_PRESSURE: return "pressure";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "temperature";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "humidity";
            default: return "unknown";
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}



    // ==================== 防篡改检测（签名验证之后） ====================

    /**
     * 检测运行环境是否被篡改/注入
     * true = 检测到篡改（Xposed/Frida/调试器/模拟器等）
     */
    private boolean isTampered() {
        try {
            // 1. 调试器检测
            if (android.os.Debug.isDebuggerConnected()
                    || android.os.Debug.waitingForDebugger()) {
                return true;
            }

            // 2. Xposed 检测：尝试加载 Xposed 框架类
            try {
                ClassLoader cl = getClassLoader();
                cl.loadClass("de.robv.android.xposed.XposedBridge");
                return true; // Xposed 类存在 → 被注入
            } catch (ClassNotFoundException ignored) {
                // 正常 — 没有 Xposed
            }

            // 3. Frida 检测：检查常见 Frida 特征
            String[] fridaPaths = {
                    "/data/local/tmp/frida-server",
                    "/data/local/tmp/frida-agent",
                    "/data/local/tmp/re.frida.server",
                    "/sdcard/frida-agent"
            };
            for (String p : fridaPaths) {
                if (new java.io.File(p).exists()) return true;
            }

            // 4. 检查 /proc/self/maps 中是否有 frida-agent 或 xposed
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream("/proc/self/maps")));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("frida")
                            || line.contains("xposed")
                            || line.contains("gum-js-loop")
                            || line.contains("gmain")) {
                        br.close();
                        return true;
                    }
                }
                br.close();
            } catch (Exception ignored) {}

            return false;
        } catch (Exception e) {
            // 异常不阻止进入
            return false;
        }
    }

    // ==================== 导航返回按钮 ====================

    /** 检查无障碍服务是否开启（供 JS 接口和内部使用） */
    private boolean isAccessibilityEnabled() {
        try {
            int enabled = Settings.Secure.getInt(
                    getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 隐藏后台 & 防终结 ====================
    //
    // ⚠️ 2026-10-01：本应用已**彻底移除设备管理器（DeviceAdmin）能力**。
    //    原先这里有一个 isDeviceAdminActive()，用 DevicePolicyManager 检查
    //    自己的 .DeviceAdminReceiver 是否处于激活状态。相关的东西全部删除了：
    //      · DeviceAdminReceiver.java（整个类）
    //      · 清单里的 <receiver> 声明与 res/xml/device_admin.xml
    //      · requestDeviceAdmin() / getDeviceAdminStatus() /
    //        openDeviceAdminSystemUi() 三个 HTML 桥接
    //      · getMissingPermissions() 返回的 "deviceAdmin" 字段
    //      · openPermSettings("deviceadmin") 分支
    //      · index.html 里「激活设备管理器」的按钮与状态显示
    //
    // 若某些设备上该应用曾经激活过，升级安装不会自动撤销，
    // 系统「设备管理器」列表里可能仍残留一条记录，需用户手动取消勾选或卸载重装。

    /** 隐藏/显示最近任务（防止被用户从后台划掉） */
    private void applyHideRecents(boolean hide) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                for (ActivityManager.AppTask task : am.getAppTasks()) {
                    task.setExcludeFromRecents(hide);
                }
            }
        } catch (Exception e) {
            Log.w("Shield", "applyHideRecents error: " + e.getMessage());
        }
    }

    /**
     * 防终结：应用被用户从"最近任务"划掉后，确保守护服务仍然活着。
     *
     * ⚠️ 必须由 {@code ProtectService.onTaskRemoved()} 调用 —— Android 只在
     * 任务「真的被用户划掉」时才回调它。
     *
     * 🐛 历史 bug（用户反馈"用别的软件几秒后自动跳回本应用"）：
     * 旧实现把这段逻辑放在 Activity 的 onDestroy 里，并用
     * {@code ActivityManager.getAppTasks()} 判断任务是否还在最近列表。
     * ① onDestroy 也会因系统内存回收而触发，不是"被划掉"的信号；
     * ② 开启「隐藏后台」后任务被 setExcludeFromRecents(true) 移出最近任务
     *    列表，getAppTasks() 恒为空 → 被误判成"被划掉"。
     * 两者叠加 → 用户正常使用其它软件时界面被反复自动拉回前台。
     *
     * ✅ 现在的策略：**只保活服务，绝不自动拉起界面**。
     * 划掉最近任务不会杀死前台服务（START_STICKY + 双进程哨兵兜底），
     * 因此"把界面弹回前台"对防护没有增益，只会打扰用户。
     */
    static void restartAfterTaskRemoved(Context ctx) {
        // 延时 600ms 再判定：等 Activity 生命周期走完（onPause/onStop/onDestroy），
        // 避免因回调时序差异把"还在前台"误判成"被划掉"
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> keepAliveAfterTaskRemoved(app), 600);
    }

    private static void keepAliveAfterTaskRemoved(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
        boolean anyOn = sp.getBoolean("protect_on", false)
                || sp.getBoolean("shake_trigger_on", false)
                || sp.getBoolean("volume_trigger_on", false);
        if (!anyOn) return;

        long now = System.currentTimeMillis();
        // 冷却：15 秒内只处理一次，避免任何边界情况造成反复拉起
        if (now - sp.getLong(KEY_AUTO_RESTART_AT, 0L) < 15000L) {
            Log.i("Shield", "防终结保活冷却中，跳过");
            return;
        }
        sp.edit().putLong(KEY_AUTO_RESTART_AT, now).apply();

        Log.i("Shield", "应用被从最近任务划掉，保活守护服务（不拉起界面）");
        // 只拉起前台服务；绝不 startActivity —— 用户在用别的软件时界面不能跳出来
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ProtectService.class));
        } catch (Exception ignored) {}
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ForegroundService.class));
        } catch (Exception ignored) {}
        // 清理历史版本可能残留的"自动弹回主界面"闹钟
        cancelRestartAlarm(ctx);
    }

    /** 取消旧版本残留的"自动弹回主界面"闹钟（关闭护盾 / 服务销毁时调用） */
    static void cancelRestartAlarm(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, RESTART_ALARM_REQ, i,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi == null) return;
            AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
            if (am != null) am.cancel(pi);
        } catch (Exception ignored) {}
    }

    private void setupBackButton() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                safeGoBack();
            }
        });

        backButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    backButton.setAlpha(1.0f);
                    if (transparencyRunnable != null) {
                        transparencyHandler.removeCallbacks(transparencyRunnable);
                    }
                    startTransparencyTimer();
                }
                return false;
            }
        });
    }

    private void updateBackButtonVisibility() {
        // 获取当前 URL（去掉 query 参数）
        String url = webView.getUrl();
        String urlNoQuery = (url != null && url.contains("?"))
                ? url.substring(0, url.indexOf("?"))
                : url;

        // 如果当前页面就是 index.html（不带参数），隐藏返回按钮
        if (urlNoQuery != null && urlNoQuery.contains("app.local/index.html")) {
            backButton.setVisibility(View.GONE);
            if (transparencyRunnable != null) {
                transparencyHandler.removeCallbacks(transparencyRunnable);
            }
        } else if (webView.canGoBack()) {
            backButton.setVisibility(View.VISIBLE);
            backButton.setAlpha(1.0f);
            if (transparencyRunnable != null) {
                transparencyHandler.removeCallbacks(transparencyRunnable);
            }
            startTransparencyTimer();
        } else {
            backButton.setVisibility(View.GONE);
            if (transparencyRunnable != null) {
                transparencyHandler.removeCallbacks(transparencyRunnable);
            }
        }
    }

    private void startTransparencyTimer() {
        transparencyRunnable = new Runnable() {
            @Override
            public void run() {
                backButton.setAlpha(0.35f);
            }
        };
        transparencyHandler.postDelayed(transparencyRunnable, 3000);
    }

    private void exitFullscreen() {
        if (isFullscreen && customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
            fullscreenContainer.removeAllViews();
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            isFullscreen = false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
        }
    }

    /**
     * 安全返回：跳过 about:blank / empty 等无效历史记录，直达上一个有效页面
     */
    private void safeGoBack() {
        if (!webView.canGoBack()) return;

        // 跳过无效历史记录（about:blank 等）
        String backUrl = webView.getOriginalUrl();
        if (backUrl == null) backUrl = "";
        if (backUrl.contains("about:blank") || backUrl.isEmpty()) {
            // 回到首页
            webView.loadUrl(LOCAL_SCHEME + "index.html");
            return;
        }
        webView.goBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 应用隐藏后台设置（防终结自启已改由 ProtectService.onTaskRemoved() 触发，
        // 详见 restartAfterTaskRemoved 注释）
        SharedPreferences sp0 = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        applyHideRecents(sp0.getBoolean("hide_recents", false));
        // 刷新权限小栏（从系统设置页返回后立即更新设备管理器/无障碍状态）
        if (indexPageReady && webView != null) {
            try {
                webView.evaluateJavascript(
                        "if (typeof refreshPermBanners === 'function') refreshPermBanners();",
                        null);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            exitFullscreen();
            return;
        }

        if (webView.canGoBack()) {
            safeGoBack();
        } else {
            if (doubleBackToExitPressedOnce) {
                // 用户主动双击退出：只关界面，守护服务继续后台运行（通知栏可见）
                super.onBackPressed();
                return;
            }

            this.doubleBackToExitPressedOnce = true;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    doubleBackToExitPressedOnce = false;
                }
            }, 2000);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (downloadManager != null) {
            downloadManager.handleActivityResult(requestCode, resultCode, data);
        }
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (uploadCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // 优先处理多选（getClipData），单选用 getData
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            Uri uri = clipData.getItemAt(i).getUri();
                            results[i] = uri;
                            tryPersistableUri(uri);
                        }
                    } else {
                        Uri uri = data.getData();
                        if (uri != null) {
                            results = new Uri[]{uri};
                            tryPersistableUri(uri);
                        }
                    }
                }
                uploadCallback.onReceiveValue(results);
                uploadCallback = null;
            }
        }
    }

    private void tryPersistableUri(Uri uri) {
        try {
            int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ignored) {
            // 非 persistable URI（部分三方文件管理器返回的 content URI），
            // 跳过即可，不影响本次读取
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 2026-10 新增：国内 ROM「读取应用列表」权限的申请结果
        if (requestCode == REQ_OEM_APPLIST_PERMISSION) {
            boolean granted = false;
            try {
                granted = grantResults != null && grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {}
            Log.i("MainActivity", "读取应用列表权限结果：granted=" + granted);
            if (!granted) {
                // 用户拒绝 → 引导到应用信息页手动打开
                openAppInfoPageOuter();
            }
            notifyWebPermissions();
            return;
        }

        // 2026-10 新增（用户需求）：一打开应用时的顺序权限请求 —— 弹完一个接着弹下一个
        if (requestCode == REQ_ENTRY_PERMISSION) {
            String deniedPermission = null;
            try {
                if (permissions != null && grantResults != null && permissions.length > 0) {
                    final boolean granted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                    Log.i("MainActivity", "权限结果：" + permissions[0] + "=" + granted
                            + "，剩余待请求 " + entryPermQueue.size() + " 项");
                    if (!granted) deniedPermission = permissions[0];
                }
            } catch (Throwable ignored) {}
            // 继续弹下一个（通知 → 应用列表）
            requestNextEntryPermission();
            // 队列走完后再处理"被拒绝"的兜底
            if (entryPermQueue.isEmpty()) {
                handleEntryPermissionResult(deniedPermission);
            }
            return;
        }

        // 2026-10 新增：通知权限申请结果回灌网页，让"进入应用权限门槛"卡片立即刷新
        if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            try {
                final String js =
                        "if(window.__dshOnPermResult)window.__dshOnPermResult("
                                + buildEntryRequirementsJson() + ");";
                if (webView != null) {
                    webView.post(() -> {
                        try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                    });
                }
                Log.i("MainActivity", "通知权限申请结果已回灌网页");
            } catch (Throwable tr) {
                Log.w("MainActivity", "回灌权限结果失败", tr);
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (downloadManager != null) {
            downloadManager.handlePermissionResult(requestCode, permissions, grantResults);
        }

        // 原先这里会在任何一次权限回调之后，无条件对挂起的 WebView 请求调用 grant()：
        //
        //     if (pendingWebPermissionRequest != null) {
        //         pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
        //         pendingWebPermissionRequest = null;
        //     }
        //
        // 有两个问题：
        //   1. 没有校验 requestCode，任何权限（通知 / 应用列表…）的回调都会命中；
        //   2. 没有检查 grantResults，即使用户在弹窗里点了「拒绝」，照样 grant。
        // 系统层仍会拦下无权限的采集，所以不是安全漏洞，但逻辑不成立。
        //
        // WebView 请求现在在 onPermissionRequest() 内同步处理完毕（放行后立即清引用，
        // 拒绝则 deny 并清引用），此处不再需要任何收尾动作。若将来重新引入
        // 「先申请系统权限、回调后再放行 WebView 请求」的流程，请在此按 requestCode
        // 精确匹配并对 grantResults 做判断，不要恢复无条件 grant。
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // 2026-10 新增：深浅色切换后重新同步状态栏（否则图标明暗会滞后一拍）
        syncStatusBarAppearance();
        super.onConfigurationChanged(newConfig);

        // 屏幕尺寸/密度/方向变化时，局部重绘 UI，不销毁 Activity

        // 1. 刷新状态栏高度（密度可能变了）
        getStatusBarHeight();
        setupStatusBarPadding();

        // 2. WebView 自适应：重新计算视口
        if (webView != null) {
            webView.requestLayout();

            // 通知 WebView 内 JS 当前屏幕信息，让前端自行重绘
            int widthPx = getResources().getDisplayMetrics().widthPixels;
            int heightPx = getResources().getDisplayMetrics().heightPixels;
            float density = getResources().getDisplayMetrics().density;
            int orientation = newConfig.orientation;
            String orientStr = (orientation == Configuration.ORIENTATION_LANDSCAPE) ? "landscape" : "portrait";

            String js = "if(window.onScreenChanged) { window.onScreenChanged({ "
                    + "width:" + widthPx + ", "
                    + "height:" + heightPx + ", "
                    + "density:" + density + ", "
                    + "orientation:'" + orientStr + "' "
                    + "}); }";

            webView.evaluateJavascript(js, null);
        }

        // 3. 如果是全屏视频，也更新全屏容器布局
        if (isFullscreen && fullscreenContainer != null) {
            fullscreenContainer.requestLayout();
        }
    }

    private void startForegroundService() {
        // Android 8+ 前台服务必须使用 startForegroundService（服务内会 startForeground）
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        getContentResolver().unregisterContentObserver(rotationObserver);
        if (executor != null) {
            // 清理旧版磁盘缓存：先提交任务再关闭线程池，
            // 防止向已 shutdown 的线程池提交任务触发 RejectedExecutionException 闪退
            try {
                if (!executor.isShutdown()) {
                    executor.execute(() -> {
                        File oldCache = new File(getCacheDir(), "web");
                        if (oldCache.exists()) deleteRecursive(oldCache);
                    });
                }
            } catch (RejectedExecutionException ignored) {
                // 线程池已关闭，跳过清理，避免崩溃
            }
            executor.shutdown();
        }
        // 防终结：界面自启改由 ProtectService.onTaskRemoved() 触发
        // （只有用户真的把应用从「最近任务」划掉时 Android 才会回调）。
        // 这里不再用 getAppTasks() 判定 —— 开启「隐藏后台」后任务被移出最近
        // 任务列表，getAppTasks() 恒为空，会被误判成「被划掉」，导致用户正常
        // 使用其它软件时界面被反复自动拉回前台。
        super.onDestroy();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    public class JavaScriptInterface {
        @JavascriptInterface
        public void vibrate(int milliseconds) {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(milliseconds);
                }
            }
        }

        @JavascriptInterface
        public String getSensorData() {
            return "sensorReady";
        }

        @JavascriptInterface
        public void closeApp() {
            // 只关闭界面，守护服务继续后台运行（不再自动弹回前台）
            runOnUiThread(() -> {
                finishAffinity();
            });
        }

        @JavascriptInterface
        public void openStellarPage() {
            final String shizukuUrl = "file:///android_asset/shizuku";
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.loadUrl(shizukuUrl);
                }
            });
        }

        // ========== 病毒库状态（「病毒库」页面轮询）==========

        /**
         * 返回病毒库与服务器的连接状态（JSON 字符串）
         */
        @JavascriptInterface
        public String getVirusDbStatus() {
            try {
                VirusDb.Data d = VirusDb.get(MainActivity.this);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("host", VirusDb.getServerHost());
                o.put("reachable", VirusDb.isServerReachable());
                o.put("refreshing", VirusDb.isRefreshing());
                o.put("lastAttempt", VirusDb.getLastAttempt());
                o.put("lastSuccess", VirusDb.getLastSuccess());
                o.put("lastError", VirusDb.getLastError());
                o.put("fetchTime", VirusDb.getCachedFetchTime());
                o.put("total", d.total());
                o.put("certainPkgs", d.certainPkgs.size());
                o.put("certainNames", d.certainNames.size());
                o.put("suspectPkgs", d.suspectPkgs.size());
                o.put("suspectNames", d.suspectNames.size());
                o.put("keys", d.keys.size());

                org.json.JSONArray arr = new org.json.JSONArray();
                for (VirusDb.ListStatus ls : VirusDb.getListStatus()) {
                    org.json.JSONObject it = new org.json.JSONObject();
                    it.put("key", ls.key);
                    it.put("label", ls.label);
                    it.put("url", ls.url);
                    it.put("ok", ls.ok);
                    it.put("count", ls.count);
                    it.put("updatedAt", ls.updatedAt);
                    arr.put(it);
                }
                o.put("lists", arr);
                return o.toString();
            } catch (Exception e) {
                Log.e("MainActivity", "getVirusDbStatus error", e);
                return "{\"error\":\"" + e.getClass().getSimpleName() + "\"}";
            }
        }

        /**
         * 手动触发一次病毒库立即刷新（后台线程，不阻塞 WebView）
         */
        @JavascriptInterface
        public void refreshVirusDb() {
            if (VirusDb.isRefreshing()) return;
            if (executor == null) return;
            executor.execute(() -> {
                try {
                    VirusDb.refreshNow(MainActivity.this);
                } catch (Exception e) {
                    Log.e("MainActivity", "refreshVirusDb error", e);
                }
            });
        }

        // ========== 内置 Shizuku 配对引导 ==========

        /** 打开 Shizuku 开源仓库（Apache-2.0 要求保留出处，也方便用户核对） */
        @JavascriptInterface
        public void openStellarRepo() {
            openUrlExternally("https://github.com/RikkaApps/Shizuku");
        }

        /** 用系统浏览器/应用打开外部链接 */
        private void openUrlExternally(final String url) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "无法打开链接，请手动访问：\n" + url, Toast.LENGTH_LONG).show();
                }
            });
        }

        // ==================================================================
        // 开源许可（Apache-2.0 第 4(a) 条合规）
        // ------------------------------------------------------------------
        // 本应用融合了 Shizuku、AOSP adb、BoringSSL、kyant backdrop 等开源代码，
        // 它们的许可证（Apache-2.0 / OpenSSL / ISC / MIT）都要求：
        // 「向 Work 或 Derivative Works 的任何接收者提供许可证副本」。
        //
        // 把 LICENSE 只放在代码仓库里是**不满足**的 —— 用户拿到的是 APK，
        // 他才是许可证的接收者。所以许可证文本必须真的随 APK 分发：
        //     res/raw/open_source_licenses.txt
        //         = Apache License 2.0 全文
        //         + NOTICE（第三方署名、BoringSSL/OpenSSL 声明、
        //                   以及每一个被修改文件的改动说明）
        // 并在应用内提供可查看/可复制的入口（高级设置 → 开源许可）。
        //
        // ⚠️ 该资源只在运行时用 getResources().openRawResource() 读取，
        //    资源压缩器识别不到这种引用方式，而 build.gradle 开了
        //    `shrinkResources true` —— 必须靠 res/raw/keep.xml 的
        //    tools:keep 保住，否则文本会被当死资源删掉，直接变成许可证违规。
        // ==================================================================

        /** 打开「开源许可」界面（高级设置卡片里的「开源许可」一行调） */
        @JavascriptInterface
        public void openSourceLicenses() {
            runOnUiThread(() -> {
                try {
                    showLicensesDialog(readRawText(R.raw.open_source_licenses));
                } catch (Throwable e) {
                    Log.e("MainActivity", "openSourceLicenses error", e);
                    Toast.makeText(MainActivity.this,
                            "无法读取许可证文本：\n" + e, Toast.LENGTH_LONG).show();
                }
            });
        }

        /** 按 UTF-8 读取 res/raw 下的文本资源 */
        private String readRawText(int resId) throws java.io.IOException {
            InputStream in = getResources().openRawResource(resId);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关闭失败不影响已读取的内容
                }
            }
        }

        /**
         * 以可滚动、可选中复制的对话框展示许可证全文。
         *
         * 用等宽字体 + 原生 Dialog（不用 AppCompatDialog / 自定义主题），
         * 避开「You need to use a Theme.AppCompat theme」那一类崩溃。
         */
        private void showLicensesDialog(final String text) {
            final float density = getResources().getDisplayMetrics().density;
            final int pad = (int) (18 * density);

            final Dialog dialog = new Dialog(MainActivity.this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFFFFFFFF);
            root.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(MainActivity.this);
            title.setText("开源许可");
            title.setTextSize(19f);
            title.setTextColor(0xFF111111);
            root.addView(title);

            TextView hint = new TextView(MainActivity.this);
            hint.setText("本应用包含 Shizuku、AOSP adb、BoringSSL 等第三方开源代码，"
                    + "以下为它们的许可证与署名声明。");
            hint.setTextSize(12f);
            hint.setTextColor(0xFF8E8E93);
            hint.setPadding(0, (int) (6 * density), 0, (int) (10 * density));
            root.addView(hint);

            ScrollView scroll = new ScrollView(MainActivity.this);
            TextView body = new TextView(MainActivity.this);
            body.setText(text);
            body.setTextSize(11f);
            body.setTextColor(0xFF333333);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            scroll.addView(body);
            // height=0 + weight=1 -> 占满标题与按钮之外的全部剩余空间
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout buttons = new LinearLayout(MainActivity.this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setPadding(0, (int) (12 * density), 0, 0);

            TextView copy = new TextView(MainActivity.this);
            copy.setText("复制全部");
            copy.setTextSize(15f);
            copy.setTextColor(0xFF007AFF);
            copy.setGravity(Gravity.CENTER);
            copy.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
            copy.setOnClickListener(v -> {
                try {
                    ClipboardManager cm =
                            (ClipboardManager) MainActivity.this
                                    .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("开源许可", text));
                        Toast.makeText(MainActivity.this, "许可证已复制",
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable e) {
                    Log.e("MainActivity", "copy licenses error", e);
                }
            });
            buttons.addView(copy, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = new TextView(MainActivity.this);
            close.setText("关闭");
            close.setTextSize(15f);
            close.setTextColor(0xFF007AFF);
            close.setGravity(Gravity.CENTER);
            close.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
            close.setOnClickListener(v -> dialog.dismiss());
            buttons.addView(close, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            root.addView(buttons);

            dialog.setContentView(root);
            dialog.show();

            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0xFFFFFFFF));
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                // 固定屏幕高度的 82%：WRAP_CONTENT 下 ScrollView 会被撑出屏幕
                lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
                w.setAttributes(lp);
            }
        }

        // ==================================================================
        // 内置特权服务界面入口
        // ------------------------------------------------------------------
        // 2026-09-25 恢复：:manager 重新纳入 settings.gradle（用的是 embedded_backup/
        // 里那份已改造为 library 模块的版本 —— com.android.library、去掉了本工程
        // 不支持的 vcsInfo.include），APK 里重新有了 roro.stellar.manager.MainActivity，
        // 页面上的「打开 Shizuku 管理器」按钮（#szInstallBtn）就是调到这里。
        // ==================================================================

        /**
         * 打开内置的特权服务界面。
         *
         * 调用方：页面 #szInstallBtn「打开 Shizuku 管理器」按钮，
         * 以及需要高权限时的引导弹窗「去启动」按钮。
         *
         * ⚠️ 目标类必须是 roro.stellar.manager.MainActivity。
         * 上游的 roro.stellar.manager.ui.features.home.HomeActivity 是 **abstract** 类
         * （abstract class HomeActivity : AppBarActivity()），它只是界面骨架；
         * 直接 startActivity 它必然抛 InstantiationException 闪退。
         * 真正可实例化的入口是 MainActivity：
         *     public class MainActivity extends HomeActivity {}
         * —— 2026-09-23 真机点这个按钮闪退就是踩了这个坑。
         */
        @JavascriptInterface
        public void openStellarManager() {
            runOnUiThread(() -> {
                // 2026-10 改动（用户需求）：这里原本跳到内置第三方管理器的界面，
                // 用户觉得"界面与第三方完全相同"不妥。现在改为打开**本应用自己的**
                // 特权服务面板（PrivilegeActivity）：同样的能力，界面与文案全部自研。
                CrashLogger.event("openStellarManager(): 打开自研特权服务面板");
                try {
                    Intent i = new Intent(MainActivity.this, PrivilegeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable tr) {
                    CrashLogger.event("openStellarManager(): 打开特权面板失败", tr);
                    Toast.makeText(MainActivity.this,
                            "打开特权服务面板失败，已记录日志", Toast.LENGTH_LONG).show();
                }
            });
        }

        /**
         * 需要时打开内置第三方管理器界面（保留给"高级排错"用，普通用户走
         * {@link #openStellarManager()} 的自研面板即可）。
         *
         * ⚠️ 目标类必须是 roro.stellar.manager.MainActivity。
         * 上游的 roro.stellar.manager.ui.features.home.HomeActivity 是 **abstract** 类
         * （abstract class HomeActivity : AppBarActivity()），它只是界面骨架；
         * 直接 startActivity 它必然抛 InstantiationException 闪退。
         * 真正可实例化的入口是 MainActivity：
         *     public class MainActivity extends HomeActivity {}
         * —— 2026-09-23 真机点这个按钮闪退就是踩了这个坑。
         */
        @JavascriptInterface
        public void openBuiltinPrivilegeManager() {
            runOnUiThread(() -> {
                final String cls = "roro.stellar.manager.MainActivity";
                CrashLogger.event("openBuiltinPrivilegeManager(): 准备启动 " + cls);
                try {
                    // 先确认清单里确实声明了这个 Activity：
                    // 没声明就明确报错并留档，而不是让 startActivity 抛异常把应用带走。
                    android.content.ComponentName cn =
                            new android.content.ComponentName(getPackageName(), cls);
                    getPackageManager().getActivityInfo(cn, 0);

                    Intent i = new Intent();
                    i.setComponent(cn);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    CrashLogger.event("openStellarManager(): startActivity 已返回");
                } catch (android.content.pm.PackageManager.NameNotFoundException nnf) {
                    CrashLogger.event("openStellarManager(): 清单里没有 " + cls, nnf);
                    Toast.makeText(MainActivity.this,
                            "APK 里找不到内置管理器界面，已记录日志，请点「复制日志」回传",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable tr) {
                    CrashLogger.event("openStellarManager(): 启动失败", tr);
                    Toast.makeText(MainActivity.this,
                            "打开内置 Shizuku 管理器失败：" + tr.getClass().getSimpleName()
                                    + "，已记录日志，请点「复制日志」回传",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        // ==================================================================
        // 崩溃 / 运行日志（出问题时用户一键复制回传）
        // ==================================================================

        /** 上次运行是否崩溃过（页面用它决定要不要显示红色提示条）。 */
        @JavascriptInterface
        public boolean hasCrashLog() {
            try {
                return CrashLogger.hasCrash(MainActivity.this);
            } catch (Throwable tr) {
                return false;
            }
        }

        /** 诊断报告全文：设备信息 + 运行轨迹 + 崩溃堆栈；没崩溃时也有内容。 */
        @JavascriptInterface
        public String getDiagnostics() {
            try {
                return CrashLogger.buildDiagnostics(MainActivity.this);
            } catch (Throwable tr) {
                return "生成诊断报告失败：" + tr;
            }
        }

        /** 把诊断报告复制到剪贴板（页面「复制日志」按钮调）。 */
        @JavascriptInterface
        public void copyDiagnostics() {
            final String text = getDiagnostics();
            runOnUiThread(() -> {
                copyTextToClipboard(text);
                Toast.makeText(MainActivity.this,
                        "日志已复制，直接粘贴发给开发者即可", Toast.LENGTH_LONG).show();
            });
        }

        /** 只复制崩溃堆栈（页面「复制崩溃日志」按钮调）。 */
        @JavascriptInterface
        public void copyCrashLog() {
            final String text = CrashLogger.readCrash(MainActivity.this);
            runOnUiThread(() -> {
                if (text == null || text.isEmpty()) {
                    Toast.makeText(MainActivity.this, "没有崩溃记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                copyTextToClipboard(text);
                Toast.makeText(MainActivity.this,
                        "崩溃日志已复制，直接粘贴发给开发者即可", Toast.LENGTH_LONG).show();
            });
        }

        /** 清除崩溃记录（不会清掉运行轨迹）。 */
        @JavascriptInterface
        public void clearCrashLog() {
            try {
                CrashLogger.clear(MainActivity.this);
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "崩溃记录已清除", Toast.LENGTH_SHORT).show());
        }

        /**
         * 页面手动记一条日志。
         * 用来把「用户点了什么」也写进诊断报告，方便定位问题。
         */
        @JavascriptInterface
        public void logEvent(final String msg) {
            if (msg == null) return;
            CrashLogger.event("[页面] " + msg);
        }

        /** 把一段文本复制到系统剪贴板（页面「复制诊断信息」按钮调）。 */
        @JavascriptInterface
        public void copyText(final String text) {
            if (text == null || text.isEmpty()) return;
            runOnUiThread(() -> {
                copyTextToClipboard(text);
                Toast.makeText(MainActivity.this, "诊断信息已复制", Toast.LENGTH_SHORT).show();
            });
        }

        /** 复制文本到系统剪贴板 */
        private void copyTextToClipboard(final String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("youlong-shizuku-start", text));
                }
            } catch (Exception e) {
                Log.e("JSBridge", "copyTextToClipboard error", e);
            }
        }

        /** 启动成功后把内置特权服务接上 Binder（保留；页面入口是 #szInstallBtn → openShizukuManager）。 */
        @JavascriptInterface
        public void connectEmbeddedStellar() {
            runOnUiThread(() -> {
                initStellar();
                Toast.makeText(MainActivity.this, "正在连接内置特权服务…", Toast.LENGTH_SHORT).show();
            });
        }

        // ========== Blob 下载回传 ==========
        // 解决 blob: 协议 URL 下载报 "unknown Protocol: blob" 的问题

        @JavascriptInterface
        public void onBlobData(String base64Data) {
            runOnUiThread(() -> {
                if (downloadManager != null) {
                    downloadManager.onBlobDataReceived(base64Data);
                }
            });
        }

        @JavascriptInterface
        public void onBlobError(String errorMsg) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "下载失败: " + errorMsg, Toast.LENGTH_LONG).show();
            });
        }

        // ========== 游龙桌面宠物 ==========
        private static final String PET_PACKAGE = "com.youlong.zoo";
        private static final String PET_ASSET = "youlong-pet.apk";

        @JavascriptInterface
        public boolean isPetAppInstalled() {
            try {
                getPackageManager().getPackageInfo(PET_PACKAGE, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        @JavascriptInterface
        public void launchOrInstallPetApp() {
            runOnUiThread(() -> {
                // 反馈：开始检查
                Toast.makeText(MainActivity.this, "正在检查游龙桌面宠物...", Toast.LENGTH_SHORT).show();
                try {
                    // 先检查是否已安装
                    getPackageManager().getPackageInfo(PET_PACKAGE, 0);
                    // 已安装 → 显式启动 ANekoActivity（该 APK 缺 LAUNCHER category）
                    Intent launchIntent = new Intent();
                    launchIntent.setComponent(new android.content.ComponentName(PET_PACKAGE, "com.youlong.zoo.presentation.ANekoActivity"));
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Toast.makeText(MainActivity.this, "正在启动游龙桌面宠物", Toast.LENGTH_SHORT).show();
                    startActivity(launchIntent);
                } catch (PackageManager.NameNotFoundException e) {
                    // 未安装 → 从 assets 安装
                    Toast.makeText(MainActivity.this, "正在准备安装游龙桌面宠物...", Toast.LENGTH_SHORT).show();
                    installPetApp();
                }
            });
        }

        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = MainActivity.this.getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<android.content.pm.ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
                if (resolveInfos == null) return "[]";
                java.util.LinkedHashSet<String> added = new java.util.LinkedHashSet<>();
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                boolean first = true;
                for (android.content.pm.ResolveInfo ri : resolveInfos) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || added.contains(pkg)) continue;
                    added.add(pkg);
                    CharSequence rawLabel = ri.loadLabel(pm);
                    String label = rawLabel != null ? rawLabel.toString() : pkg;
                    // manual JSON escape for label
                    label = label.replace("\\", "\\\\")
                                 .replace("\"", "\\\"")
                                 .replace("\n", "\\n")
                                 .replace("\r", "\\r")
                                 .replace("\t", "\\t");
                    if (!first) sb.append(",");
                    sb.append("{\"name\":\"").append(label)
                      .append("\",\"pkg\":\"").append(pkg)
                      .append("\"}");
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                Log.e("JSBridge", "getInstalledApps error", e);
                return "[]";
            }
        }

        private void installPetApp() {
            // Android 8+ 需要检查未知来源安装权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "请允许安装未知来源应用，然后重试", Toast.LENGTH_LONG).show();
                        Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        settingsIntent.setData(Uri.parse("package:" + getPackageName()));
                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(settingsIntent);
                    });
                    return;
                }
            }

            MainActivity.this.executor.execute(() -> {
                try {
                    // 清理旧缓存文件（防止损坏文件残留）
                    File oldCache = new File(MainActivity.this.getCacheDir(), PET_ASSET);
                    if (oldCache.exists()) oldCache.delete();

                    // 从 assets 读取 APK
                    InputStream is = MainActivity.this.getAssets().open(PET_ASSET);
                    byte[] apkData = AssetsEncryptor.readAllBytes(is);
                    is.close();

                    // 写入缓存目录
                    File cacheFile = new File(MainActivity.this.getCacheDir(), PET_ASSET);
                    FileOutputStream fos = new FileOutputStream(cacheFile);
                    fos.write(apkData);
                    fos.close();

                    // 使用 FileProvider 生成安全 URI（适配 Android 7+）
                    Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this,
                            MainActivity.this.getPackageName() + ".fileprovider",
                            cacheFile);

                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    installIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    // 已安装旧版本时允许覆盖安装
                    installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                    installIntent.putExtra(Intent.EXTRA_ALLOW_REPLACE, true);

                    MainActivity.this.runOnUiThread(() -> {
                        try {
                            MainActivity.this.startActivity(installIntent);
                            Toast.makeText(MainActivity.this, "正在弹出安装界面...", Toast.LENGTH_SHORT).show();
                        } catch (Exception ex) {
                            Toast.makeText(MainActivity.this, "安装失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception ex) {
                    MainActivity.this.runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "读取安装包失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        }

        // ========== 广告跳过 (AdSkip) ==========

        @JavascriptInterface
        public boolean isAdSkipServiceRunning() {
            return AdSkipService.isRunning();
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void updateAdSkipConfig(String configJson) {
            AdSkipService.updateConfig(configJson);
        }

        @JavascriptInterface
        public String getAdSkipStats() {
            return AdSkipService.getStats();
        }

        @JavascriptInterface
        public void requestAdSkipPermission() {
            // 引导用户去无障碍设置页面手动开启
            openAccessibilitySettings();
        }

        // ========== 垃圾清理（专业版） ==========

        /**
         * 全面扫描垃圾：应用缓存 + 外部缓存 + 缩略图 + 临时文件 + 大文件等
         * 返回 JSON：{"cache":128.5, "thumbnails":389.2, "temp":45.3, "apk":22.9, "logtomb":15.6, "obb":88.0, "total":689.5, "appCount":92}
         */
        @JavascriptInterface
        public String scanAllJunk() {
            try {
                long totalBytes = 0;
                long cacheBytes = 0;
                long thumbBytes = 0;
                long tempBytes = 0;
                long apkBytes = 0;
                long logBytes = 0;
                long obbBytes = 0;
                int appCount = 0;

                // ---- 1. 应用缓存（StorageStatsManager，所有第三方应用） ----
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    StorageStatsManager ssm = (StorageStatsManager) getSystemService(STORAGE_STATS_SERVICE);
                    PackageManager pm = getPackageManager();
                    List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    for (android.content.pm.ApplicationInfo app : apps) {
                        if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                        try {
                            StorageStats stats = ssm.queryStatsForUid(app.storageUuid, app.uid);
                            long c = stats.getCacheBytes();
                            if (c > 0) { cacheBytes += c; appCount++; }
                        } catch (Exception ignored) {}
                    }
                }

                // ---- 2. 缩略图缓存（DCIM/.thumbnails，通常是最大垃圾源） ----
                File dcim = new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumbnails");
                if (dcim.exists()) thumbBytes += dirSize(dcim);
                File picThumb = new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/.thumbnails");
                if (picThumb.exists()) thumbBytes += dirSize(picThumb);
                File dcimThumb2 = new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumb");
                if (dcimThumb2.exists()) thumbBytes += dirSize(dcimThumb2);

                // ---- 3. 外部 app 缓存目录 ----
                File androidData = new File(android.os.Environment.getExternalStorageDirectory(), "Android/data");
                if (androidData.exists()) {
                    File[] pkgs = androidData.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            File cache = new File(pkg, "cache");
                            if (cache.exists()) tempBytes += dirSize(cache);
                            // 某些应用如微信、QQ 还有自己的 temp 目录
                            File tempSub = new File(pkg, "temp");
                            if (tempSub.exists()) tempBytes += dirSize(tempSub);
                        }
                    }
                }

                // ---- 4. Download 目录大文件 / 残留安装包 ----
                File download = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
                if (download.exists()) {
                    File[] files = download.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String n = f.getName().toLowerCase();
                            if (f.isFile() && (n.endsWith(".apk") || n.endsWith(".dex") || n.endsWith(".zip")
                                    || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".iso")
                                    || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".tmp")
                                    || n.endsWith(".log") || n.startsWith("bugreport"))) {
                                apkBytes += f.length();
                            }
                        }
                    }
                }

                // ---- 5. 残留 OBB ----
                File obbDir = new File(android.os.Environment.getExternalStorageDirectory(), "Android/obb");
                if (obbDir.exists()) {
                    File[] pkgs = obbDir.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            obbBytes += dirSize(pkg);
                        }
                    }
                }

                // ---- 6. tombstones + anr 日志 ----
                File tombs = new File("/data/tombstones");
                if (tombs.exists()) logBytes += dirSize(tombs);
                File anr = new File("/data/anr");
                if (anr.exists()) logBytes += dirSize(anr);

                // ---- 7. 各种临时目录 ----
                String[] tmpPaths = {
                    "/data/local/tmp",
                    "/cache",
                    "/data/system/dropbox",
                    "/data/system/usagestats"
                };
                for (String p : tmpPaths) {
                    File f = new File(p);
                    if (f.exists()) tempBytes += dirSize(f);
                }

                // ---- 8. .trash 回收站文件 ----
                File sdcard = android.os.Environment.getExternalStorageDirectory();
                File[] trash = sdcard.listFiles((d, n) -> n.startsWith(".trash") || n.contains(".Trash") || n.contains("trash"));
                if (trash != null) for (File f : trash) tempBytes += dirSize(f);

                // ---- 9. 微信/QQ 等缓存（仅扫描） ----
                String[] socialCaches = {
                    "/sdcard/tencent/MicroMsg/avatar", "/sdcard/tencent/MicroMsg/sns",
                    "/sdcard/tencent/MicroMsg/video", "/sdcard/tencent/MicroMsg/voice2",
                    "/sdcard/Android/data/com.tencent.mm/cache",
                    "/sdcard/Android/data/com.tencent.mobileqq/cache"
                };
                for (String p : socialCaches) {
                    File f = new File(p);
                    if (f.exists()) tempBytes += dirSize(f);
                }

                totalBytes = cacheBytes + thumbBytes + tempBytes + apkBytes + logBytes + obbBytes;

                return String.format(
                    "{\"cache\":%.1f,\"thumbnails\":%.1f,\"temp\":%.1f,\"apk\":%.1f,\"logtomb\":%.1f,\"obb\":%.1f,\"total\":%.1f,\"appCount\":%d}",
                    cacheBytes/1048576.0, thumbBytes/1048576.0, tempBytes/1048576.0,
                    apkBytes/1048576.0, logBytes/1048576.0, obbBytes/1048576.0,
                    totalBytes/1048576.0, appCount
                );
            } catch (Exception e) {
                Log.e("Clean", "scanAllJunk err", e);
                return "{\"total\":0,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        /**
         * 执行强力清理。清理前先记可清总量，清后算实际释放空间。
         * 返回 JSON: {"freed":689.5, "message":"..."}
         */
        @SuppressLint("WrongConstant")
        @JavascriptInterface
        public String performDeepClean() {
            try {
                // ---- 清理前测量剩余空间 ----
                long freeBefore = android.os.Environment.getExternalStorageDirectory().getFreeSpace();

                // ---- 1. 缩略图缓存（最大的垃圾源） ----
                deleteDir(new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumbnails"));
                deleteDir(new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/.thumbnails"));
                deleteDir(new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumb"));

                // ---- 2. 本 App 内部缓存 + WebView 缓存 ----
                deleteDir(getCacheDir());
                deleteDir(getExternalCacheDir());
                deleteDir(new File(getCacheDir().getParentFile(), "app_webview"));
                deleteDir(new File(getCacheDir().getParentFile(), "webview"));
                deleteDir(new File(getCacheDir().getParentFile(), "databases"));
                deleteDir(new File(getCacheDir().getParentFile(), "app_database"));
                deleteDir(new File(getCacheDir().getParentFile(), "app_geolocation"));
                // 清 WebView 内部缓存
                try { getCacheDir().getParentFile().delete(); } catch (Exception ignored) {}

                // ---- 3. 清外部应用缓存目录 ----
                File androidData = new File(android.os.Environment.getExternalStorageDirectory(), "Android/data");
                if (androidData.exists()) {
                    File[] pkgs = androidData.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            deleteDir(new File(pkg, "cache"));
                            deleteDir(new File(pkg, "temp"));
                            deleteDir(new File(pkg, "code_cache"));
                        }
                    }
                }

                // ---- 4. Download 残留安装包/大文件 ----
                File download = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
                if (download.exists()) {
                    File[] files = download.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            String n = f.getName().toLowerCase();
                            if (f.isFile() && (n.endsWith(".apk") || n.endsWith(".dex") || n.endsWith(".zip")
                                    || n.endsWith(".rar") || n.endsWith(".7z") || n.endsWith(".iso")
                                    || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".tmp")
                                    || n.endsWith(".log") || n.startsWith("bugreport"))) {
                                f.delete();
                            }
                        }
                    }
                }

                // ---- 5. 残留 OBB ----
                // 只清除已卸载应用的 obb（标记：只保留当前已安装应用对应的 obb）
                File obbDir = new File(android.os.Environment.getExternalStorageDirectory(), "Android/obb");
                if (obbDir.exists()) {
                    PackageManager pm = getPackageManager();
                    File[] pkgs = obbDir.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            try {
                                pm.getPackageInfo(pkg.getName(), 0);
                                // 应用还在，跳过
                            } catch (PackageManager.NameNotFoundException e) {
                                // 已卸载，删掉残留
                                deleteDir(pkg);
                            }
                        }
                    }
                }

                // ---- 6. 临时目录（有权限的） ----
                deleteDir(new File("/data/local/tmp"));
                deleteDir(new File("/data/tombstones"));
                deleteDir(new File("/data/anr"));
                deleteDir(new File("/data/system/dropbox"));

                // ---- 7. .trash 文件 ----
                File sdcard = android.os.Environment.getExternalStorageDirectory();
                File[] trash = sdcard.listFiles((d, n) -> n.startsWith(".trash") || n.contains(".Trash") || n.contains("trash"));
                if (trash != null) for (File f : trash) deleteDir(f);

                // ---- 8. 微信/QQ 部分缓存（可访问的） ----
                String[] socialCaches = {
                    "/sdcard/tencent/MicroMsg/avatar",
                    "/sdcard/tencent/MicroMsg/sns",
                    "/sdcard/tencent/MicroMsg/video",
                    "/sdcard/tencent/MicroMsg/voice2",
                    "/sdcard/Android/data/com.tencent.mm/cache",
                    "/sdcard/Android/data/com.tencent.mobileqq/cache"
                };
                for (String p : socialCaches) {
                    deleteDir(new File(p));
                }

                // ---- 9. 触发系统级清理（通过反射调用 StorageManager.freeStorageAndNotify） ----
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                        if (sm != null) {
                            java.lang.reflect.Method m = StorageManager.class.getMethod(
                                    "freeStorageAndNotify", java.util.UUID.class, long.class);
                            m.invoke(sm, StorageManager.UUID_DEFAULT, 0L);
                        }
                    }
                } catch (Exception ignored) {}

                // ---- 清理后测量 ----
                long freeAfter = android.os.Environment.getExternalStorageDirectory().getFreeSpace();
                double freedMB = Math.max(0, (freeAfter - freeBefore) / 1048576.0);
                String msg = String.format("成功释放 %.1f MB 存储空间", freedMB > 0 ? freedMB : 
                    // 如果差值不明显，返回扫描总量做参考
                    (scanTotalEstimate() > 0 ? scanTotalEstimate() : 0));

                return String.format("{\"freed\":%.1f,\"message\":\"%s\"}", 
                    freedMB > 0 ? freedMB : 0, 
                    freedMB > 0 ? msg : "清理完成！建议重启手机彻底释放系统缓存");
            } catch (Exception e) {
                Log.e("Clean", "performDeepClean err", e);
                return "{\"freed\":0,\"message\":\"清理出错: " + e.getMessage() + "\"}";
            }
        }

        /** 估算扫描过的垃圾总量（仅参考） */
        private double scanTotalEstimate() {
            try {
                long t = 0;
                t += dirSize(new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumbnails"));
                File dd = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
                if (dd.exists()) {
                    File[] fs = dd.listFiles();
                    if (fs != null) for (File f : fs) {
                        String n = f.getName().toLowerCase();
                        if (f.isFile() && (n.endsWith(".apk") || n.endsWith(".zip") || n.endsWith(".dex"))) t += f.length();
                    }
                }
                return t / 1048576.0;
            } catch (Exception e) { return 0; }
        }

        /**
         * 跳转系统存储设置（辅助：系统缓存需用户手动释放）
         */
        @JavascriptInterface
        public void openStorageSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent("android.settings.MEMORY_CARD_SETTINGS");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }

        private long dirSize(File dir) {
            if (dir == null || !dir.exists()) return 0;
            File[] files = dir.listFiles();
            if (files == null) return 0;
            long size = 0;
            for (File f : files) {
                if (f.isFile()) size += f.length();
                else if (f.isDirectory()) size += dirSize(f);
                // 跳过符号链接防止无限递归
            }
            return size;
        }

        // ========== 游龙安全护盾 ==========

        /** 查询缺失权限 — HTML 用于渲染权限小栏 */
        @JavascriptInterface
        public String getMissingPermissions() {
            boolean accOk = isAccessibilityEnabled();
            boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(MainActivity.this);
            boolean usageOk = checkUsagePermission();
            // ⚠️ 2026-10-01：不再包含 "deviceAdmin" 字段 ——
            //    本应用的设备管理器能力已彻底移除（见 applyHideRecents 上方的说明）。
            //    保留 false 占位是为了兼容可能仍在缓存里的旧页面脚本，
            //    它们若读到 undefined 可能把整块权限栏判为异常。
            return "{\"accessibility\":" + accOk
                    + ",\"overlay\":" + overlayOk
                    + ",\"usage\":" + usageOk
                    + ",\"deviceAdmin\":false}";
        }

        /** 查询 Shizuku 是否已连接并授权 — HTML 用于判断无障碍丢失时能否由 Shizuku 兜底守护 */
        @JavascriptInterface
        public boolean isStellarReady() {
            return isStellarAvailable() && hasStellarPermission();
        }

        // ==================================================================
        // 环境检测（2026-10 新增，用户需求）
        // ------------------------------------------------------------------
        // 首页需要在「开启实时守护」上方展示一条环境异常提示：
        //   · 手机里装了 Dhizuku（com.rosan.dhizuku）→ 拦截率只有 96.2%
        //   · 手机有 root（Magisk / KernelSU / APatch / su 二进制）→ 只有 73.2%
        // 两者同时命中时取更严重的（root，73.2%）。
        //
        // ⚠️ 本方法**只做无副作用的探测**，绝不会去执行 `su -c ...`：
        //    那样会弹出「授予 root 权限？」对话框，而这里只是首页的一次
        //    状态查询，弹框会非常突兀，也可能被用户当成恶意行为。
        //    因此改用「看 su 二进制是否存在」＋「看 root 管理器包是否安装」。
        // ==================================================================
        // ==================================================================
        // 2026-10 新增（用户需求）：进入应用时的权限门槛
        // ------------------------------------------------------------------
        // 需求：进入软件就要拿到「读取应用（已安装应用列表）」与「通知」两项权限，
        //       不开就不允许开启安全守护。
        //
        // 为什么不能只看 checkSelfPermission：
        //   · 读取应用列表靠的是 QUERY_ALL_PACKAGES —— 它是 **normal 权限**，
        //     安装时即授予，没有运行时弹窗。只看 flag 会永远显示"已授予"，
        //     万一 OEM 裁剪或用户在应用信息里关掉了，界面却还显示正常。
        //     所以这里**真的去查一次已安装应用数量**，查得到才算可用。
        //   · 通知权限（Android 13+ POST_NOTIFICATIONS）是运行时权限，可弹窗请求；
        //     Android 12 及以下安装即授予。
        //
        // 返回 JSON 供网页决定：是否弹权限引导、开关是否允许打开。
        // ==================================================================
        @JavascriptInterface
public String getEntryRequirements() {
            return MainActivity.this.buildEntryRequirementsJson();
        }

        /** 通知权限是否可用（Android 13+ 需运行时授予） */
private boolean hasNotificationPermission() {
            return MainActivity.this.hasNotificationPermissionOuter();
        }

        /**
         * 读取已安装应用的能力是否真的可用。
         *
         * <p>不是简单读 QUERY_ALL_PACKAGES 的 flag，而是实际查一次包列表：
         * 只有查得到（返回非空）才算可用。Android 11+ 若该权限被关掉，
         * getInstalledApplications 会返回被过滤后的极短列表甚至空列表。
         */
private boolean hasAppListAccess() {
            return MainActivity.this.hasAppListAccessOuter();
        }

        /**
         * 申请通知权限（网页在进入应用时调用；Android 13+ 才会真的弹窗）。
         */
        @JavascriptInterface
        public void requestNotificationPermission() {
            try {
                if (Build.VERSION.SDK_INT < 33) return;
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                runOnUiThread(() -> {
                    try {
                        requestPermissions(
                                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                                REQ_NOTIFICATION_PERMISSION);
                    } catch (Throwable tr) {
                        Log.w("MainActivity", "申请通知权限失败", tr);
                    }
                });
            } catch (Throwable tr) {
                Log.w("MainActivity", "requestNotificationPermission 失败", tr);
            }
        }

        /**
         * 请求"读取应用列表"相关权限。
         *
         * <p>2026-10 修正（用户反馈）：**不再把用户丢去系统设置**。
         * 应用列表本身用 launcher 查询即可，无需权限；
         * 这里统一改成"由应用直接发起系统授权弹窗"，能弹就弹，
         * 弹不出来（系统已永久拒绝）才退回应用信息页。
         */
        @JavascriptInterface
        public void requestAppListPermission() {
            runOnUiThread(() -> {
                // 优先走国内 ROM 的 GET_INSTALLED_APPS（会弹「允许读取应用列表」）；
                // 该 ROM 不支持时退回 QUERY_ALL_PACKAGES；两者都不行才引导到应用信息页。
                if (MainActivity.this.requestOemInstalledAppsPermission()) {
                    return;
                }
                try {
                    requestPermissions(
                            new String[]{android.Manifest.permission.QUERY_ALL_PACKAGES},
                            REQ_APPLIST_PERMISSION);
                } catch (Throwable tr) {
                    Log.w("MainActivity", "申请应用列表权限失败，退回应用信息页", tr);
                    openAppInfoPage();
                }
            });
        }

        /** 兜底：打开本应用「应用信息」页（仅在系统弹窗不可用时使用） */
        private void openAppInfoPage() {
            try {
                Intent it = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                it.setData(android.net.Uri.parse("package:" + getPackageName()));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Throwable tr) {
                Log.w("MainActivity", "打开应用信息页失败", tr);
            }
        }

        /**
         * 环境提示（绿色描边卡片）的判定：**没有 root 且没有 Dhizuku** 时显示。
         * 与 getEnvironmentWarning 的"异常"方向相反 —— 这里是"环境良好"的正向提示。
         */
        @JavascriptInterface
        public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }


        /**
         * root 判定诊断（2026-10 新增，**仅供排错**，不参与界面判定）。
         *
         * <p>如果还有用户反馈"没 root 却被判定有 root"，在网页控制台执行
         * {@code Android.getRootDiagnostics()}，把返回的 JSON 发回来即可定位：
         * 最终结论、命中了哪个 root 管理器包、哪个 su 路径存在、su -c id 的真实输出。
         */
        @JavascriptInterface
        public String getRootDiagnostics() {
            return MainActivity.this.buildRootDiagnosticsJson();
        }

        @JavascriptInterface
        public String getEnvironmentWarning() {
            // 2026-10 修正（用户反馈：没 root 被误判有 root）：
            // 原来这里自己实现了一套"包名 + su 文件存在"的判定，与外层重复且不可靠。
            // 现在统一委托外层的 deviceIsRooted() / deviceHasDhizuku()，
            // 二者都改成**实证判定**（su -c id 必须返回 uid=0）。
            final boolean root = MainActivity.this.deviceIsRooted();
            final boolean dhizuku = MainActivity.this.deviceHasDhizuku();

            // 严重程度：root > dhizuku
            String level = "none";
            String title = "";
            String message = "";
            if (root) {
                level = "root";
                title = "当前系统环境异常";
                message = "您无法享受日常模式的100%拦截，当前拦截率为73.2%";
            } else if (dhizuku) {
                level = "dhizuku";
                title = "当前系统环境异常";
                message = "您无法享受日常模式的100%拦截，当前拦截率为96.2%";
            }

            return "{\"level\":\"" + level + "\",\"dhizuku\":" + dhizuku
                    + ",\"root\":" + root
                    + ",\"title\":\"" + title + "\",\"message\":\"" + message + "\"}";
        }

        // 2026-10 修正（用户反馈：没 root 被误判有 root）：
        // 这里原本还有一份 isPackageInstalled() 与 suBinaryExists()，与外层重复，
        // 且用"su 文件存在"直接下 root 结论 —— 部分 ROM / 工程模式 / 残留文件
        // 会有同名文件但设备并没有 root，于是误判。
        // 现在 root 判定统一到外层的 deviceIsRooted()（实证：su -c id 返回 uid=0），
        // 本内部类不再持有独立的判定实现。

        /** 查询隐藏后台开关状态 */
        @JavascriptInterface
        public boolean getHideRecents() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("hide_recents", false);
        }

        /** 切换隐藏后台（从最近任务中隐藏，防止被划掉） */
        @JavascriptInterface
        public void setHideRecents(boolean hide) {
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putBoolean("hide_recents", hide).apply();
            applyHideRecents(hide);
        }

        /** HTML 权限小栏「授权」按钮 → 打开对应系统设置 */
        @JavascriptInterface
        public void openPermSettings(String type) {
            Intent i = null;
            if ("accessibility".equals(type)) {
                i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            } else if ("overlay".equals(type)) {
                i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
            } else if ("usage".equals(type)) {
                i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            }
            // ⚠️ 2026-10-01：原 "deviceadmin" 分支已删除（设备管理器能力整体移除）
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            }
        }

        /** 是否已允许后台高耗电（忽略电池优化） */
        @JavascriptInterface
        public boolean isBatteryOptIgnored() {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) {
                return false;
            }
        }

        /** 允许后台高耗电：跳转系统电池优化设置（部分 ROM 需手动选择"不优化"） */
        @JavascriptInterface
        public void openBatteryOptSettings() {
            try {
                if (isBatteryOptIgnored()) {
                    Toast.makeText(MainActivity.this, "已允许后台高耗电，无需重复设置", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                try {
                    // 部分 ROM 不支持直接请求，打开电池优化列表让用户手动选择
                    Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e2) {
                    Toast.makeText(MainActivity.this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
                }
            }
        }

        @JavascriptInterface
        public String startProtect() {
            boolean accessibilityOk = isAccessibilityEnabled();
            boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this);
            boolean usageOk = checkUsagePermission();

            // 收集缺失的权限名
            StringBuilder missing = new StringBuilder();
            if (!accessibilityOk) appendMissing(missing, "无障碍");
            if (!overlayOk) appendMissing(missing, "悬浮窗");
            if (!usageOk) appendMissing(missing, "使用情况");

            // 有任何权限缺失 → 拒绝开启，跳转设置页
            if (missing.length() > 0) {
                if (!overlayOk) {
                    try {
                        Intent permIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                        permIntent.setData(Uri.parse("package:" + getPackageName()));
                        permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(permIntent);
                    } catch (Exception ignored) {}
                }
                if (!usageOk) {
                    try {
                        Intent usageIntent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        usageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(usageIntent);
                    } catch (Exception ignored) {}
                }
                if (!accessibilityOk) {
                    try {
                        Intent accIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        accIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(accIntent);
                    } catch (Exception ignored) {}
                }

                Toast.makeText(MainActivity.this, "请先授权：" + missing.toString(), Toast.LENGTH_LONG).show();
                return "{\"status\":\"need_permission\",\"missing\":\"" + missing.toString() + "\"}";
            }

            // ============ 启动服务 + 触发方式（2026-10-05 修复）============
            // ⚠️ bug「紧急逃生只能触发一次，必须重新开启才行」的另一半根因就在这里：
            //   旧实现每次点「开启守护」都无条件写
            //     shake_trigger_on = true / volume_trigger_on = false，
            //   也就是**把用户自己选的触发方式直接覆盖掉**。
            //   于是只要用户再点一次开启守护（关闭守护/触发救援后重新进页面都会点），
            //   音量键逃生就被静默关掉，界面上也不会提示 —— 只能手动再打开一次，
            //   这正是用户描述的"触发一次之后再也触发不了，需要重新开启才可以"。
            //   现在：用户明确选过触发方式（首页两个开关的 onchange → setXxxTriggerEnabled）
            //   就原样还原，绝不覆盖；只有在用户从没选过时才套用"默认摇一摇"。
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            final boolean userChoseTrigger = prefs.getBoolean("trigger_mode_user_set", false);
            final boolean restoreVol = prefs.getBoolean("user_volume_trigger_on", false);
            final boolean restoreShake = prefs.getBoolean("user_shake_trigger_on", true);

            SharedPreferences.Editor ed = prefs.edit()
                    .putBoolean("protect_on", true)
                    .putLong("protect_start_time", System.currentTimeMillis())
                    // 每次开启实时守护都把病毒库的「自动拦截」强制打开（用户要求：默认开启 + 开守护必开）
                    .putBoolean("auto_block_on", true);
            if (userChoseTrigger) {
                // 还原用户自己的选择（两个开关在界面上互斥，这里照原样写回）
                ed.putBoolean("volume_trigger_on", restoreVol)
                        .putBoolean("shake_trigger_on", restoreShake);
            } else {
                // 用户从未选过 → 用默认值：摇一摇开、音量键关（与旧版一致）
                ed.putBoolean("shake_trigger_on", true)
                        .putBoolean("volume_trigger_on", false);
            }
            ed.apply();

            // 记轨迹（本机 logcat 拿不到本应用日志，只能靠这条文件轨迹事后排查）
            try {
                CrashLogger.event("[逃生] 开启守护：触发方式="
                        + (userChoseTrigger ? "沿用用户选择" : "默认摇一摇")
                        + " 摇一摇=" + prefs.getBoolean("shake_trigger_on", false)
                        + " 音量键=" + prefs.getBoolean("volume_trigger_on", false));
            } catch (Throwable ignored) {}

            Intent intent = new Intent(MainActivity.this, ProtectService.class);
            ContextCompat.startForegroundService(MainActivity.this, intent);

            Toast.makeText(MainActivity.this, "守护已开启", Toast.LENGTH_SHORT).show();
            return "{\"status\":\"started\"}";
        }

        private void appendMissing(StringBuilder sb, String name) {
            if (sb.length() > 0) sb.append("、");
            sb.append(name);
        }

        /** AppOpsManager 可靠检测使用情况访问权限 */
        private boolean checkUsagePermission() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return true;
            try {
                AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
                if (appOps == null) return false;
                int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), getPackageName());
                return mode == AppOpsManager.MODE_ALLOWED;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void stopProtect() {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            // 关闭主护盾开关，同时关闭所有触发器，确保服务彻底停止
            prefs.edit()
                    .putBoolean("protect_on", false)
                    .putBoolean("shake_trigger_on", false)
                    .putBoolean("volume_trigger_on", false)
                    // 清零守护起始时间：下次开启重新计时（通知栏显示已开启时长）
                    .putLong("protect_start_time", 0L)
                    .putLong(KEY_AUTO_RESTART_AT, 0L)
                    .apply();
            // 无条件停止服务（主进程 + 哨兵进程）
            Intent intent = new Intent(MainActivity.this, ProtectService.class);
            stopService(intent);
            try {
                Intent gi = new Intent(MainActivity.this, ForegroundService.class);
                stopService(gi);
            } catch (Exception ignored) {}
            cancelKeepAliveAlarm();
            // 取消"防终结"自启闹钟：防止关闭护盾后 800ms 又把主界面拉回前台
            cancelRestartAlarm(MainActivity.this);
            // 立即断开无障碍侧僵尸监听：防止已停止的 ProtectService 实例
            // 仍通过 AdSkipService 回调继续拦截/拉起界面（关闭后残留的根源）
            try { AdSkipService.setForegroundChangeListener(null); } catch (Exception ignored) {}
            try { AdSkipService.setVolumeChangeListener(null); } catch (Exception ignored) {}
            // 立即取消"紧急救援 — 请尽快卸载"通知（防止关闭护盾后仍残留卸载提示）
            ProtectService.cancelRescueNotifications(MainActivity.this);
            Log.i("MainActivity", "护盾已完全关闭：主开关+触发器均已关闭，服务已停止");
        }

        /**
         * 获取当前拦截模式
         * @return "basic" / "super" / "extreme" / "final"
         */
        @JavascriptInterface
        public String getShieldMode() {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            return prefs.getString("shield_mode", "basic");
        }

        @JavascriptInterface
        public void setShieldMode(String mode) {
            getSharedPreferences("shield_prefs", MODE_PRIVATE).edit().putString("shield_mode", mode).apply();
            Log.d("Shield", "拦截模式已切换为: " + mode);
        }

        @JavascriptInterface
        public String getProtectStatus() {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            boolean prefOn = prefs.getBoolean("protect_on", false);
            boolean actuallyRunning = isServiceRunning(ProtectService.class);

            // preference 说开着，但服务实际没运行 → 自动重启
            if (prefOn && !actuallyRunning) {
                Log.i("MainActivity", "护盾pref为开但服务未运行，自动重启");
                Intent intent = new Intent(MainActivity.this, ProtectService.class);
                ContextCompat.startForegroundService(MainActivity.this, intent);
                actuallyRunning = true;
            }

            // 用 protect_on 配置项决定主开关状态（服务可能因音量/摇动触发器独立运行）
            // 不再强行停止服务，让触发器功能保持独立
            boolean shieldOn = prefOn && actuallyRunning;

            long startTime = prefs.getLong("protect_start_time", 0);
            String duration = "0m";
            if (shieldOn && startTime > 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                long hrs = elapsed / 3600000;
                long mins = (elapsed % 3600000) / 60000;
                duration = hrs > 0 ? hrs + "h" + mins + "m" : mins + "m";
            }
            boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(MainActivity.this);
            // 额外返回服务运行状态供 HTML 判断触发器是否可用
            return "{\"on\":" + shieldOn + ",\"duration\":\"" + duration
                    + "\",\"overlay\":" + overlay + ",\"serviceRunning\":" + actuallyRunning + "}";
        }

        private boolean isServiceRunning(Class<?> serviceClass) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am == null) return false;
                for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
                    if (serviceClass.getName().equals(s.service.getClassName())) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        public String scanDangerApps() {
            try {
                PackageManager pm = getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (android.content.pm.ApplicationInfo app : apps) {
                    try {
                        // 跳过系统应用和自己
                        if (app.packageName.equals(getPackageName())) continue;
                        if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                        if (app.packageName.startsWith("com.android.") || app.packageName.startsWith("com.google.")) continue;

                        String[] reqPerms = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions;
                        if (reqPerms == null) continue;
                        boolean hasDanger = false;
                        StringBuilder riskPerms = new StringBuilder();
                        for (String perm : reqPerms) {
                            if (perm == null) continue;
                            if (perm.contains("BIND_DEVICE_ADMIN")) {
                                hasDanger = true;
                                if (riskPerms.length() > 0) riskPerms.append(", ");
                                riskPerms.append("设备管理器");
                            } else if (perm.contains("SYSTEM_ALERT_WINDOW")) {
                                hasDanger = true;
                                if (riskPerms.length() > 0) riskPerms.append(", ");
                                riskPerms.append("悬浮窗");
                            } else if (perm.contains("BIND_ACCESSIBILITY_SERVICE")) {
                                hasDanger = true;
                                if (riskPerms.length() > 0) riskPerms.append(", ");
                                riskPerms.append("无障碍服务");
                            } else if (perm.contains("BIND_NOTIFICATION_LISTENER_SERVICE")) {
                                hasDanger = true;
                                if (riskPerms.length() > 0) riskPerms.append(", ");
                                riskPerms.append("通知监听");
                            }
                        }
                        if (!hasDanger && !BlacklistConstants.HARDCODED_BLACKLIST.contains(app.packageName)) continue;
                        String label = app.loadLabel(pm).toString();
                        String name = label.replace("\\", "\\\\").replace("\"", "\\\"");
                        String pkg = app.packageName.replace("\\", "\\\\").replace("\"", "\\\"");
                        String perms = riskPerms.toString().replace("\\", "\\\\").replace("\"", "\\\"");
                        boolean isHardcoded = BlacklistConstants.HARDCODED_BLACKLIST.contains(app.packageName);
                        if (!first) sb.append(",");
                        sb.append("{\"name\":\"").append(name)
                          .append("\",\"packageName\":\"").append(pkg)
                          .append("\",\"riskPermissions\":\"").append(perms)
                          .append("\",\"isHardcoded\":").append(isHardcoded)
                          .append(",\"riskLevel\":\"").append(isHardcoded ? "95%" : "").append("\"")
                          .append("}");
                        first = false;
                    } catch (Exception ignored) {}
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                Log.e("Shield", "scanDangerApps error", e);
                return "[]";
            }
        }

        @JavascriptInterface
        public void openAppInfo(String packageName) {
            try {
                Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                intent.setData(Uri.parse("package:" + packageName));
                intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "无法打开卸载页面", Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public boolean isTrustAllApps() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("trust_all_apps", false);
        }

        @JavascriptInterface
        public void trustAllApps() {
            try {
                SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);

                // 获取所有非系统应用
                PackageManager pm = getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                StringBuilder sb = new StringBuilder();
                for (android.content.pm.ApplicationInfo app : apps) {
                    String pkg = app.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    // 跳过系统应用
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                    if (app.sourceDir != null && app.sourceDir.startsWith("/system/")) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(pkg);
                }

                // 保存白名单（当前所有已安装的非系统应用）
                prefs.edit().putString("whitelist_pkgs", sb.toString())
                        .putBoolean("trust_all_apps", true)
                        .apply();

                // 清空黑名单
                prefs.edit().putString("blacklist_pkgs", "").apply();

                Log.d("Shield", "trustAllApps: 已将所有非系统应用加入白名单");
            } catch (Exception e) {
                Log.e("Shield", "trustAllApps error", e);
            }
        }

        @JavascriptInterface
        public void openBlacklistManager() {
            Intent intent = new Intent(MainActivity.this, BlacklistActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        @JavascriptInterface
        public void openWhitelistManager() {
            try {
                Intent intent = new Intent(MainActivity.this, WhitelistActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.e("Shield", "openWhitelistManager error", e);
            }
        }

        // ===== 批量黑名单接口（供 WebView 多选弹窗使用） =====

        /**
         * 获取所有非系统应用列表，供 WebView 黑名单选择弹窗使用
         * 返回 JSON: [{"name":"微信","pkg":"com.tencent.mm"}, ...]
         */
        @JavascriptInterface
        public String getNonSystemApps() {
            try {
                PackageManager pm = getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (android.content.pm.ApplicationInfo app : apps) {
                    String pkg = app.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    // 过滤系统应用
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                    if (app.sourceDir != null && app.sourceDir.startsWith("/system/")) continue;
                    String label = app.loadLabel(pm).toString()
                            .replace("\\", "\\\\").replace("\"", "\\\"");
                    if (!first) sb.append(",");
                    sb.append("{\"name\":\"").append(label)
                      .append("\",\"pkg\":\"").append(pkg)
                      .append("\"}");
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /**
         * 批量将应用加入黑名单
         * @param pkgsJson JSON 数组字符串，如 ["com.xxx.xxx","com.yyy.yyy"]
         */
        @JavascriptInterface
        public void addToBlacklist(String pkgsJson) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(pkgsJson);
                SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                java.util.Set<String> blacklist = new java.util.HashSet<>();
                String raw = prefs.getString("blacklist_pkgs", "");
                if (!raw.isEmpty()) {
                    for (String p : raw.split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) blacklist.add(t);
                    }
                }
                for (int i = 0; i < arr.length(); i++) {
                    blacklist.add(arr.getString(i));
                }
                StringBuilder sb = new StringBuilder();
                for (String p : blacklist) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(p);
                }
                prefs.edit().putString("blacklist_pkgs", sb.toString()).apply();
            } catch (Exception e) {
                Log.e("Shield", "addToBlacklist error", e);
            }
        }

        /**
         * 将单个应用加入黑名单（管控）
         */
        @JavascriptInterface
        public void addSingleToBlacklist(String packageName) {
            if (packageName == null || packageName.isEmpty()) return;
            try {
                SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                String raw = prefs.getString("blacklist_pkgs", "");
                java.util.Set<String> blacklist = new java.util.HashSet<>();
                if (!raw.isEmpty()) {
                    for (String p : raw.split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) blacklist.add(t);
                    }
                }
                if (blacklist.contains(packageName)) return; // 已在管控中
                blacklist.add(packageName);
                StringBuilder sb = new StringBuilder();
                for (String p : blacklist) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(p);
                }
                prefs.edit().putString("blacklist_pkgs", sb.toString()).apply();
                Log.d("Shield", "addSingleToBlacklist: 已将 " + packageName + " 加入管控");
            } catch (Exception e) {
                Log.e("Shield", "addSingleToBlacklist error", e);
            }
        }

        /**
         * 将单个应用移出黑名单（取消管控）
         */
        @JavascriptInterface
        public void removeSingleFromBlacklist(String packageName) {
            if (packageName == null || packageName.isEmpty()) return;
            try {
                SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                String raw = prefs.getString("blacklist_pkgs", "");
                java.util.Set<String> blacklist = new java.util.HashSet<>();
                if (!raw.isEmpty()) {
                    for (String p : raw.split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) blacklist.add(t);
                    }
                }
                if (!blacklist.contains(packageName)) return; // 不在管控中
                blacklist.remove(packageName);
                StringBuilder sb = new StringBuilder();
                for (String p : blacklist) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(p);
                }
                prefs.edit().putString("blacklist_pkgs", sb.toString()).apply();
                Log.d("Shield", "removeSingleFromBlacklist: 已将 " + packageName + " 移出管控");
            } catch (Exception e) {
                Log.e("Shield", "removeSingleFromBlacklist error", e);
            }
        }

        /**
         * 获取当前黑名单
         * 返回 JSON: {"packages":["pkg1","pkg2"]}
         */
        @JavascriptInterface
        public String getBlacklistStatus() {
            try {
                SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                String raw = prefs.getString("blacklist_pkgs", "");
                java.util.List<String> list = new java.util.ArrayList<>();
                if (!raw.isEmpty()) {
                    for (String p : raw.split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) list.add(t);
                    }
                }
                StringBuilder sb = new StringBuilder();
                sb.append("{\"packages\":[");
                boolean first = true;
                for (String p : list) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(p).append("\"");
                    first = false;
                }
                sb.append("]}");
                return sb.toString();
            } catch (Exception e) {
                return "{\"packages\":[]}";
            }
        }

        @JavascriptInterface
        public String getBlockHistory() {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            String history = prefs.getString("block_history", "[]");
            return history;
        }

        @JavascriptInterface
        public String getDangerScanCache() {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            String cache = prefs.getString("danger_scan_cache", "[]");
            long time = prefs.getLong("danger_scan_time", 0);
            return "{\"apps\":" + cache + ",\"scanTime\":" + time + "}";
        }

        @JavascriptInterface
        public void requestPermission(String type) {
            if ("overlay".equals(type)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            } else if ("usage".equals(type)) {
                Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }

        private void deleteDir(File dir) {
            if (dir == null || !dir.exists()) return;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteDir(f);
                    else f.delete();
                }
            }
            dir.delete();
        }

        // ===== 音量按键 / 摇一摇触发开关（HTML 高级设置调用）=====
        @JavascriptInterface
        public void setVolumeTriggerEnabled(boolean enabled) {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            final boolean changed = prefs.getBoolean("volume_trigger_on", false) != enabled;
            SharedPreferences.Editor ed = prefs.edit().putBoolean("volume_trigger_on", enabled);
            // 2026-10-05：只有**值真的发生变化**才认定"这是用户自己的选择"。
            // 这两组开关是界面上的 onchange 回调，不存在页面加载回填的调用；
            // 但仍用 changed 判定，避免任何意外的重复写入把用户选择标记成"已选"。
            // 标记之后，startProtect() 就再也不会覆盖用户的触发方式（见该处注释）。
            if (changed) {
                ed.putBoolean("user_volume_trigger_on", enabled)
                        .putBoolean("trigger_mode_user_set", true);
                try {
                    CrashLogger.event("[逃生] 用户切换触发方式：音量键=" + enabled);
                } catch (Throwable ignored) {}
            }
            ed.apply();
            Log.d("MainActivity", "音量触发开关: " + enabled);
            // 主护盾已关闭时，触发器开关仅记录状态，绝不拉起服务（防止页面加载残留值偷偷启动）
            boolean protectOn = prefs.getBoolean("protect_on", false);
            if (!protectOn) return;
            // 开启音量触发时确保服务在运行
            if (enabled && !isServiceRunning(ProtectService.class)) {
                Intent intent = new Intent(MainActivity.this, ProtectService.class);
                ContextCompat.startForegroundService(MainActivity.this, intent);
            }
        }

        @JavascriptInterface
        public boolean getVolumeTriggerEnabled() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("volume_trigger_on", false);
        }

        // ===== 音量触发自定义：触发按键（音量- / 音量+）与触发次数（2~10）=====
        // 这两个值由 ProtectService 每次按键时实时读取，无需重启服务即可生效
        @JavascriptInterface
        public void setVolumeTriggerKey(String key) {
            String k = "up".equals(key) ? "up" : "down";
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putString("volume_trigger_key", k).apply();
            Log.d("MainActivity", "音量触发按键: " + k);
        }

        @JavascriptInterface
        public String getVolumeTriggerKey() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getString("volume_trigger_key", "down");
        }

        @JavascriptInterface
        public void setVolumeTriggerCount(int count) {
            int n = count;
            if (n < 2) n = 2;
            if (n > 10) n = 10;
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putInt("volume_trigger_count", n).apply();
            Log.d("MainActivity", "音量触发次数: " + n);
        }

        @JavascriptInterface
        public int getVolumeTriggerCount() {
            int n = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getInt("volume_trigger_count", 3);
            if (n < 2) n = 2;
            if (n > 10) n = 10;
            return n;
        }

        @JavascriptInterface
        public void setShakeTriggerEnabled(boolean enabled) {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            final boolean changed = prefs.getBoolean("shake_trigger_on", false) != enabled;
            SharedPreferences.Editor ed = prefs.edit().putBoolean("shake_trigger_on", enabled);
            // 与 setVolumeTriggerEnabled 同理：值真的变了才算用户选择，
            // 之后 startProtect() 会原样还原，不再覆盖。
            if (changed) {
                ed.putBoolean("user_shake_trigger_on", enabled)
                        .putBoolean("trigger_mode_user_set", true);
                try {
                    CrashLogger.event("[逃生] 用户切换触发方式：摇一摇=" + enabled);
                } catch (Throwable ignored) {}
            }
            ed.apply();
            Log.d("MainActivity", "摇动触发开关: " + enabled);
            // 主护盾已关闭时，触发器开关仅记录状态，绝不拉起服务（防止页面加载残留值偷偷启动）
            boolean protectOn = prefs.getBoolean("protect_on", false);
            if (!protectOn) return;
            // 开启摇一摇时确保服务在运行（摇动检测在前台服务中）
            if (enabled && !isServiceRunning(ProtectService.class)) {
                Intent intent = new Intent(MainActivity.this, ProtectService.class);
                ContextCompat.startForegroundService(MainActivity.this, intent);
            }
        }

        @JavascriptInterface
        public boolean getShakeTriggerEnabled() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("shake_trigger_on", false);
        }

        // ===== 摇一摇力度档位（1=轻摇 2=标准 3=用力 4=猛摇）=====
        // 与音量触发按键/次数同理：值只是写进 SharedPreferences，
        // 由 ProtectService 每次传感器采样实时读取，改档位无需重启服务即可生效。
        @JavascriptInterface
        public void setShakeSensitivity(int level) {
            int lv = level;
            if (lv < ProtectService.SHAKE_LEVEL_MIN) lv = ProtectService.SHAKE_LEVEL_MIN;
            if (lv > ProtectService.SHAKE_LEVEL_MAX) lv = ProtectService.SHAKE_LEVEL_MAX;
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putInt("shake_sensitivity", lv).apply();
            Log.d("MainActivity", "摇一摇力度档位: " + lv);
        }

        @JavascriptInterface
        public int getShakeSensitivity() {
            return ProtectService.readShakeLevel(
                    getSharedPreferences("shield_prefs", MODE_PRIVATE));
        }

        // ===== 自动拦截总开关（病毒库 / 自动拦截页面）=====
        // 默认开启（key 不存在时也返回 true，老用户升级后即为开启状态）。
        // 关闭后 ProtectService 不再做任何基于病毒库的自动拦截（既不该弹窗也不该卸载）。
        // 注意：每次 startProtect（开启实时守护）都会把该值强制写回 true。
        @JavascriptInterface
        public void setAutoBlockEnabled(boolean enabled) {
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putBoolean("auto_block_on", enabled).apply();
            Log.d("MainActivity", "自动拦截开关: " + enabled);
        }

        @JavascriptInterface
        public boolean getAutoBlockEnabled() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("auto_block_on", true);
        }

        @JavascriptInterface
        public void triggerShakeAlert() {
            // 摇动触发弹窗 — 发送广播让 ProtectService 显示警告
            Intent si = new Intent(MainActivity.this, ProtectService.class);
            si.setAction("com.youlong.hd.SHAKE_TRIGGER");
            ContextCompat.startForegroundService(MainActivity.this, si);
        }
    }

    // ==================== 内置特权服务（Stellar）相关方法 ====================

    /**
     * 服务连接监听器（Binder 就绪）。
     *
     * ⚠️ 迁移注意：上游 Shizuku 的这些方法带 {@code @JvmOverloads}，
     * 从 Java 可以直接传一个 lambda。Stellar 的同名方法**没有**
     * {@code @JvmOverloads}，编译后的 Java 签名是两个参数：
     *     addBinderReceivedListener(OnBinderReceivedListener, Handler)
     * 因此必须显式传入 {@code null} 作 handler（表示回调派发到主线程）。
     * 本文件其余同名调用同理。
     */
    private final Stellar.OnBinderReceivedListener stellarBinderReceivedListener = () -> {
        runOnUiThread(() -> {
            if (isStellarAvailable()) {
                Toast.makeText(MainActivity.this, "特权服务已连接", Toast.LENGTH_SHORT).show();
            }
        });
    };

    /** 服务断开监听器（Binder 死亡），写法同 {@link #stellarBinderReceivedListener}。 */
    private final Stellar.OnBinderDeadListener stellarBinderDeadListener = () -> {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "特权服务已断开，请重新启动", Toast.LENGTH_LONG).show();
        });
    };

    /**
     * 初始化内置特权服务 —— 监听服务连接状态
     */
    private void initStellar() {
        // ⚠️ 迁移期踩坑记录：这里原先是
        //        StellarProvider.Companion.enableMultiProcessSupport(false);
        //    它来自「客户端接入指南」，但对本工程是**错的**，已删除：
        //
        //    · 该调用的作用是把当前进程标记为「Provider 进程」，供多进程应用
        //      做 binder 转发。本应用只有一个进程，不需要也没意义；
        //    · 更关键的是——本工程的 Stellar Provider 由**内置管理器**提供
        //      （roro.stellar.manager.StellarManagerProvider，继承
        //        roro.stellar.StellarProvider），宿主清单里**没有**、也不该有
        //      自己的 StellarProvider 声明（原因见 app/src/main/AndroidManifest.xml）。
        //      再调用它的静态方法属于张冠李戴。
        //    · StellarProvider.attachInfo() 里自己会设 isProviderProcess=true，
        //      不需要外部干预。

        // 只做一件事：监听特权服务的连接/断开（handler 传 null = 回调走主线程）
        Stellar.INSTANCE.addBinderReceivedListener(stellarBinderReceivedListener, null);
        Stellar.INSTANCE.addBinderDeadListener(stellarBinderDeadListener, null);
    }

    /**
     * 检查 Stellar 特权服务是否可用
     *
     * <p>迁移说明：Shizuku 版这里还有一道 {@code Shizuku.isPreV11()} 判断，
     * Stellar 的客户端 API 没有该方法 —— 服务端 API 版本由
     * {@code StellarApiConstants.SERVER_VERSION} 固定描述，不再有「v11 之前」
     * 这种需要特殊处理的老服务端，因此该判断已删除。
     */
    private boolean isStellarAvailable() {
        return Stellar.INSTANCE.pingBinder();
    }

    /**
     * 检查是否已获得 Stellar 权限
     *
     * <p>迁移说明：{@code Stellar.checkSelfPermission()} 直接返回 boolean，
     * 不再像 Shizuku 那样返回 {@code PackageManager.PERMISSION_*} 常量。
     */
    private boolean hasStellarPermission() {
        return Stellar.INSTANCE.checkSelfPermission("stellar");
    }

    /**
     * 请求 Stellar 权限
     */
    private void requestStellarPermission() {
        if (!isStellarAvailable()) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "特权服务未启动（点「内置特权服务」页的「打开特权服务管理器」启动）", Toast.LENGTH_LONG).show();
            });
            return;
        }

        if (hasStellarPermission()) {
            // 已授权
            return;
        }

        Stellar.INSTANCE.requestPermission("stellar", REQUEST_CODE_STELLAR);
    }

    /**
     * 通过 Shizuku 执行命令
     * 用 /system/bin/sh -c 包装以支持管道/重定向，轮询 exitValue 避免 waitFor 死锁
     * @param command 要执行的shell命令
     * @param timeoutMs 超时毫秒，<=0 不设超时
     * @return 命令执行的标准输出
     */
    @SuppressLint("SetTextI18n")
    private String runStellarCommand(final String command, long timeoutMs) {
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        Process process = null;

        try {
            String cleanCmd = command.trim();
            Log.d("StellarCMD", "Exec: " + cleanCmd);

            // 启动 sh，通过 stdin 传命令，避免 Binder 传参分裂
            process = StellarUtils.newPrivilegedProcess(new String[]{"sh"}, null, null);
            final Process p = process;

            java.io.OutputStream stdin = p.getOutputStream();
            stdin.write((cleanCmd + "\nexit\n").getBytes("UTF-8"));
            stdin.flush();
            stdin.close();

            Thread outReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.InputStream in = p.getInputStream();
                    while ((n = in.read(buf)) != -1)
                        stdout.append(new String(buf, 0, n));
                } catch (Exception e) {
                    Log.e("StellarCMD", "out read err", e);
                }
            });
            Thread errReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.InputStream in = p.getErrorStream();
                    while ((n = in.read(buf)) != -1)
                        stderr.append(new String(buf, 0, n));
                } catch (Exception e) {
                    Log.e("StellarCMD", "err read err", e);
                }
            });
            outReader.start();
            errReader.start();

            // 等待进程结束
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    done = p.waitFor(Math.min(remaining, 200L), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.w("StellarCMD", "waitFor err: " + e.getMessage());
                    done = true;
                }
            }

            if (!done) {
                p.destroyForcibly();
                outReader.join(500);
                errReader.join(500);
                return "执行超时（" + timeoutMs + "ms），进程已终止";
            }

            // 先等待流读完，再取 exitCode
            outReader.join(2000);
            errReader.join(2000);

            // exitValue() 在 Shizuku 远程进程中可能延迟可用，重试最多 5 次
            int exitCode = 0;
            boolean exitOk = false;
            for (int i = 0; i < 5; i++) {
                try {
                    exitCode = p.exitValue();
                    exitOk = true;
                    break;
                } catch (Exception e) {
                    if (i < 4)
                        try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }

            String out = stdout.toString().trim();
            String err = stderr.toString().trim();

            Log.d("StellarCMD", "exitOk=" + exitOk + " exitCode=" + exitCode + " outLen=" + out.length() + " errLen=" + err.length());

            // 有 stdout → 成功（许多命令 exitCode 不可靠）
            if (out.length() > 0) return out;

            // exitCode 不可获取 → Shizuku 远程进程 exitValue 未实现，忽略
            if (!exitOk) {
                if (err.length() > 0) return err;
                return "执行成功";
            }

            // 有 stderr
            if (err.length() > 0) {
                if (exitCode != 0 && exitCode != -1)
                    return "执行失败(code:" + exitCode + ") 错误：" + err;
                return err;
            }

            // 完全无输出
            if (exitCode != 0 && exitCode != -1)
                return "执行失败(code:" + exitCode + ")，无输出";

        } catch (Exception e) {
            Log.e("StellarCMD", "Exception", e);
            return "调用异常：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        return "执行成功";
    }

    /**
     * 暴露给 WebView 调用的 Shizuku 命令执行接口
     */
    public class StellarBridge {
        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = MainActivity.this.getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                StringBuilder sb = new StringBuilder();
                for (android.content.pm.ApplicationInfo app : apps) {
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                        String label = app.loadLabel(pm).toString();
                        sb.append("package:").append(app.packageName)
                          .append(" label:").append(label).append("\n");
                    }
                }
                return sb.toString();
            } catch (Exception e) {
                Log.e("StellarBridge", "getInstalledApps error", e);
                return "error:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String executeCommand(String command) {
            if (!isStellarAvailable()) {
                // 保留「错误：」前缀（HTML 侧按前缀判断失败）
                // 内置管理器界面已随本构建一起打包，服务可以由用户从应用内启动，
                // 所以这里给出可操作的指引，而不是「本构建不支持」。
                return "错误：特权服务未启动（点「内置特权服务」页的「打开特权服务管理器」启动）";
            }

            if (!hasStellarPermission()) {
                // 在主线程请求权限
                runOnUiThread(() -> requestStellarPermission());
                return "正在请求权限，请在弹窗中授权";
            }

            // 在后台线程执行命令（30秒超时）
            final String cmd = command;
            executor.execute(() -> {
                final String result = runStellarCommand(cmd, 30000);
                // 将结果传回 WebView（使用 JSON 转义，防特殊字符破坏 JS）
                runOnUiThread(() -> {
                    if (webView != null) {
                        String jsonResult = org.json.JSONObject.quote(result != null ? result : "");
                        String js = "window.onShizukuCommandResult(" + jsonResult + ");";
                        webView.evaluateJavascript(js, null);
                    }
                });
            });

            return "命令执行中...";
        }

        @JavascriptInterface
        public boolean isStellarReady() {
            return isStellarAvailable() && hasStellarPermission();
        }

        /**
         * {@link #isStellarReady()} 的旧名别名。
         *
         * ⚠️ 真机踩坑（2026-10-01）：页面里的模式判定是这么写的
         *     if(typeof Shizuku!=='undefined' && Shizuku.isShizukuReady) ready = Shizuku.isShizukuReady()===true;
         * 而 StellarBridge 只暴露了 isStellarReady —— 于是
         *   · 状态行调用 Shizuku.getServiceStatus() → 方法存在 → 显示「已连接」；
         *   · 点模式调用 Shizuku.isShizukuReady()   → 方法不存在 → 守卫不成立 →
         *     ready 恒为 false → 每次都弹「需要启动特权服务」。
         * 表现就是「状态行写着已连接，一点高级模式却说需要启动」。
         *
         * 这里补一个同义方法，让新老页面都能工作；
         * 页面侧也已改为不依赖方法名存在性（见 index.html 的 requireShizuku）。
         */
        @JavascriptInterface
        public boolean isShizukuReady() {
            return isStellarReady();
        }

        /**
         * {@link #getServiceStatus()} 的旧名别名（同为兼容性保留）。
         */
        @JavascriptInterface
        public String getShizukuServiceStatus() {
            return getServiceStatus();
        }

        /**
         * 主动请求 Shizuku 授权（供内置配对引导页「自动授权」使用）
         * 服务已启动但未授权时，系统会弹出授权对话框
         */
        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                try {
                    if (isStellarAvailable() && !hasStellarPermission()) {
                        Stellar.INSTANCE.requestPermission("stellar", REQUEST_CODE_STELLAR);
                    }
                } catch (Exception e) {
                    Log.e("StellarBridge", "requestPermission error", e);
                }
            });
        }

        @JavascriptInterface
        public String getServiceStatus() {
            if (!isStellarAvailable()) {
                return "Shizuku未启动";
            }
            if (!hasStellarPermission()) {
                return "Shizuku待授权";
            }
            return "Shizuku已连接";
        }
    }

    // ===== 取消保活闹钟（守护关闭时调用）=====
    private void cancelKeepAliveAlarm() {
        Intent keepIntent = new Intent(this, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(this, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            am.cancel(keepPi);
            Log.d("MainActivity", "保活闹钟已取消");
        }
    }
}
