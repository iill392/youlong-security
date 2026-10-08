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





import com.youlong.hd.AssetsEncryptor;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private WebView webView;
    private ImageButton backButton;
    private boolean doubleBackToExitPressedOnce = false;
    
    private static final int RESTART_ALARM_REQ = 0x5A1;
    
    private static final int NOTIFY_OPEN_REQ = 0x5A2;
    
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

    // 三个 JS 桥的名字（注入/移除共用同一份，避免手写字符串漂移）
    private static final String BRIDGE_NAME_JS = "Android";
    private static final String BRIDGE_NAME_NATIVE = StrX.d(StrX.BRIDGE_ANDROID_NATIVE);
    private static final String BRIDGE_NAME_SHIZUKU = StrX.d(StrX.BRIDGE_SHIZUKU);

    // 当前页面是否为本应用受信任的本地页面：决定 JS 桥是否保留、传感器数据是否回灌
    private volatile boolean currentPageTrusted = false;
    // JS 桥当前是否已挂载，避免重复 add/remove
    private boolean jsBridgesAttached = false;

    
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;
    private static final int REQ_APPLIST_PERMISSION = 0x7A22;
    
    private static final int REQ_ENTRY_PERMISSION = 0x7A23;
    
    private static final int REQ_OEM_APPLIST_PERMISSION = 0x7A24;

    
    private final java.util.ArrayDeque<String> entryPermQueue = new java.util.ArrayDeque<>();

    
    
    
    private View mainContent;
    private int lastStatusBarInset = 0;
    private int lastNavBarInset = 0;
    private int lastSideInset = 0;

    
    private ExecutorService executor;
    
    private FrameLayout loadingOverlay;

    
    private static final String LOCAL_SCHEME = StrX.d(StrX.LOCAL_SCHEME);

    
    private static final String ASSET_INDEX = "index.html";

    
    
    
    private volatile boolean indexPageReady = false;
    private int currentTabIndex = 0;

    
    private FrameLayout fullscreenContainer;
    private WebView fullscreenWebView;
    private ImageButton exitFullscreenButton;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean isFullscreen = false;

    
    private static final int REQUEST_CODE_STELLAR = 1001;

    private final ContentObserver rotationObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            checkAutoRotateAndUpdate();
        }
    };


    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    
    // ======================================================================

    
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

    
    private boolean hasNotificationPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 33) return true;
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return true;
        }
    }

    
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

    
    private boolean isInstalledPkg(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    
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

    // ======================================================================
    // 进程等待/终止兼容层
    // ----------------------------------------------------------------------
    // Process.waitFor(long, TimeUnit) 与 Process.destroyForcibly() 都是 API 26
    // 才有的方法，API 24/25 上直接调用会抛 NoSuchMethodError 崩溃，所以统一走这里。
    // ======================================================================
    // 语义与 Process.waitFor(timeout, unit) 一致：超时返回 false，进程已结束返回 true
    private static boolean waitForTimeout(Process proc, long timeoutMs) {
        if (proc == null) return true;
        final long waitMs = Math.max(0L, timeoutMs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                return proc.waitFor(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return true;
            } catch (Throwable tr) {
                Log.w("MainActivity", "waitFor(timeout) 失败，改用轮询", tr);
            }
        }
        final long deadline = System.currentTimeMillis() + waitMs;
        while (true) {
            try {
                proc.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                if (System.currentTimeMillis() >= deadline) return false;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            } catch (Throwable tr) {
                // 查不到状态（底层异常）按已结束处理，避免卡死调用方
                return true;
            }
        }
    }

    // 尽力终止进程：API 26+ 用 destroyForcibly，低版本回退 destroy
    private static void destroyQuietly(Process proc) {
        if (proc == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                proc.destroyForcibly();
                return;
            } catch (Throwable ignored) {
            }
        }
        try { proc.destroy(); } catch (Throwable ignored) {}
    }

    
    private boolean suGrantsRoot() {
        
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

            boolean done = waitForTimeout(p, 3000L);
            if (!done) {
                destroyQuietly(p);
                return false;
            }
            reader.join(500);
            String s = out.toString();
            boolean rooted = s.contains("uid=0");
            Log.i("MainActivity", "su -c id => " + s.replace('\n', ' ').trim() + "  rooted=" + rooted);
            return rooted;
        } catch (Throwable tr) {
            
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    
    private String buildRootDiagnosticsJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"rooted\":").append(deviceIsRooted());
        sb.append(",\"dhizuku\":").append(deviceHasDhizuku());

        
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
            if (!waitForTimeout(p, 3000L)) {
                destroyQuietly(p);
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

    
    private boolean deviceIsRooted() {
        try {
            return suGrantsRoot();
        } catch (Throwable tr) {
            return false;
        }
    }

    
    private boolean deviceHasDhizuku() {
        return isInstalledPkg("com.rosan.dhizuku");
    }

    
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
        
        // ------------------------------------------------------------
        
        
        
        
        
        
        // ============================================================

        setContentView(R.layout.activity_main);

        
        
        syncStatusBarAppearance();

        
        
        setupWindowInsets();

        // ==================================================================
        
        // ------------------------------------------------------------------
        
        
        
        
        // ==================================================================
        getWindow().getDecorView().post(this::requestEntryPermissions);

        
        YouLongShield.init(this);

        
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        
        executor = Executors.newSingleThreadExecutor();
        downloadManager = new DownloadManager(this);

        
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        backButton = findViewById(R.id.backButton);

        
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        fullscreenWebView = findViewById(R.id.fullscreenWebView);
        exitFullscreenButton = findViewById(R.id.exitFullscreenButton);
        exitFullscreenButton.setOnClickListener(v -> exitFullscreen());

        
        setupWebView();

        
        ComposeView mainContent = findViewById(R.id.mainContent);
        MainScreenKt.setMainContent(mainContent, webView, index -> {
            
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

        
        VirusDb.loadFromCache(this);
        VirusDb.refreshAsync(this);

        verifyAssetsAndLoad();
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
        
        
        
        

        if (backButton != null) {
            ViewGroup.LayoutParams p = backButton.getLayoutParams();
            if (p instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams backButtonParams = (ConstraintLayout.LayoutParams) p;
                backButtonParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + 10 + 40;
                backButton.setLayoutParams(backButtonParams);
            }
        }
    }

    // ======================================================================
    // WebView 页面可信判定 / JS 桥开关 / 跳转策略
    // ----------------------------------------------------------------------
    // 只有打包进 APK 的本地页面算可信页面：
    //   · LOCAL_SCHEME（https://app.local/ 资产页，由 shouldInterceptRequest 提供）
    //   · file:///android_asset/、file:///android_res/（APK 内只读资源）
    //   · about:blank / data:（本地错误页 loadDataWithBaseURL）
    // 可信页面才注入三个 JS 桥；http/https 等外部页面一律摘掉桥，且不允许在应用内加载。
    // ======================================================================
    // 允许通过 intent:// 拉起的本应用 Activity（白名单，别的一律拦截）
    private static final java.util.Set<String> TRUSTED_APP_ACTIVITIES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "com.youlong.hd.MainActivity",
                    "com.youlong.hd.ShieldWarnActivity",
                    "com.youlong.hd.BatchCleanupActivity",
                    "com.youlong.hd.PrivAuthActivity",
                    "com.youlong.hd.WhitelistActivity",
                    "com.youlong.hd.BlacklistActivity",
                    "com.youlong.hd.AppListActivity",
                    "com.youlong.hd.PrivSettingsActivity",
                    "com.youlong.hd.PrivilegeActivity",
                    "com.youlong.hd.AdbPairActivity"));

    private static boolean isTrustedPageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        final String lower = url.toLowerCase(java.util.Locale.US);
        if (lower.startsWith(LOCAL_SCHEME.toLowerCase(java.util.Locale.US))) return true;
        if (lower.startsWith("file:///android_asset/")) return true;
        if (lower.startsWith("file:///android_res/")) return true;
        if (lower.startsWith("about:blank")) return true;
        if (lower.startsWith("data:")) return true;
        return false;
    }

    // 按 URL 判定当前页面是否可信，并挂载/摘除三个 JS 桥（只能在 UI 线程调用）
    private void updateJsBridgesForUrl(String url) {
        final boolean trusted = isTrustedPageUrl(url);
        currentPageTrusted = trusted;
        try {
            if (trusted && !jsBridgesAttached) {
                webView.addJavascriptInterface(new JavaScriptInterface(), BRIDGE_NAME_JS);
                webView.addJavascriptInterface(new ScreenFilterBridge(this), BRIDGE_NAME_NATIVE);
                webView.addJavascriptInterface(new StellarBridge(), BRIDGE_NAME_SHIZUKU);
                jsBridgesAttached = true;
                Log.i("MainActivity", "JS 桥已注入（本地受信任页面）");
            } else if (!trusted && jsBridgesAttached) {
                webView.removeJavascriptInterface(BRIDGE_NAME_JS);
                webView.removeJavascriptInterface(BRIDGE_NAME_NATIVE);
                webView.removeJavascriptInterface(BRIDGE_NAME_SHIZUKU);
                jsBridgesAttached = false;
                Log.w("MainActivity", "JS 桥已移除（非本地页面）：" + url);
            }
        } catch (Throwable tr) {
            Log.w("MainActivity", "更新 JS 桥失败 trusted=" + trusted, tr);
        }
    }

    // 页面跳转策略：本地页面留在 WebView，其它一律拦截或交给系统
    private boolean handleUrlOverride(String url) {
        if (url == null || url.isEmpty()) return false;

        // 本地受信任页面（app.local 资产页 / file:///android_asset 等）留在 WebView 内加载
        if (isTrustedPageUrl(url)) return false;

        final String lower = url.toLowerCase(java.util.Locale.US);

        // 外部网页：交给系统浏览器打开，不在应用内加载
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            openUrlInSystemBrowser(url);
            return true;
        }

        // 本地文件与 javascript: 一律拦截，不加载不执行
        if (lower.startsWith("file://")) {
            Log.w("MainActivity", "已拦截 file:// 跳转：" + url);
            return true;
        }
        if (lower.startsWith("javascript:")) {
            Log.w("MainActivity", "已拦截 javascript: 跳转");
            return true;
        }

        if (lower.startsWith("intent://")) {
            return handleIntentUrl(url);
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    // intent:// 只允许拉起本应用白名单内的组件，其它一律拦截
    private boolean handleIntentUrl(String url) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            if (intent == null) return true;

            // 强行限定到本应用，并清掉页面指定的 URI 授权标志
            intent.setPackage(getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final int grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                intent.removeFlags(grantFlags);
            } else {
                // Intent.removeFlags 需要 API 26：低版本用 setFlags 等价清除
                intent.setFlags(intent.getFlags() & ~grantFlags);
            }

            ComponentName cn = intent.getComponent();
            final boolean known = cn != null
                    && getPackageName().equals(cn.getPackageName())
                    && TRUSTED_APP_ACTIVITIES.contains(cn.getClassName());
            if (!known) {
                Log.w("MainActivity", "已拦截非白名单 intent:// 跳转："
                        + (cn == null ? "(无组件)" : cn.flattenToShortString()));
                return true;
            }
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private void openUrlInSystemBrowser(final String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(MainActivity.this, "无法打开此链接", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();

        
        
        webView.setWebContentsDebuggingEnabled(false);

        
        webSettings.setJavaScriptEnabled(true);

        
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);

        
        webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        
        // 本地 assets 页面需要文件访问能力；但 file:// 页面不得再读其它本地文件、不得跨域请求
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(false);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);

        
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(false);

        
        
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        
        webSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webSettings.setOffscreenPreRaster(true);
        }

        
        webSettings.setDatabasePath(getApplicationContext().getFilesDir().getPath() + "/databases");
        webSettings.setSaveFormData(true);
        webSettings.setSavePassword(false);

        
        webSettings.setMediaPlaybackRequiresUserGesture(false);

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 禁止 https 页面再夹带 http 混合内容
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        
        webSettings.setGeolocationEnabled(true);

        
        webSettings.setTextZoom(100);

        
        webView.setDrawingCacheEnabled(true);
        webView.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);

        
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                       String mimeType, long contentLength) {
                if (downloadManager == null) return;

                if (url != null && url.startsWith("blob:")) {
                    
                    downloadManager.prepareBlobDownload(url, userAgent, contentDisposition, mimeType);
                    injectBlobReaderJs(url);
                } else {
                    downloadManager.downloadFile(url, userAgent, contentDisposition, mimeType);
                }
            }
        });

        
        
        

        webView.addJavascriptInterface(new JavaScriptInterface(), BRIDGE_NAME_JS);
        
        webView.addJavascriptInterface(new ScreenFilterBridge(this), BRIDGE_NAME_NATIVE);
        
        webView.addJavascriptInterface(new StellarBridge(), BRIDGE_NAME_SHIZUKU);
        // 首个页面固定是本地受信任页（app.local 的 index.html），先挂上三个桥；
        // 之后每次页面导航都由 onPageStarted → updateJsBridgesForUrl 按 URL 重新判定：
        // 本地页面（app.local / file:///android_asset）保留，http/https 等外部页面一律摘掉桥
        jsBridgesAttached = true;

        webView.setWebViewClient(new WebViewClient() {
            
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(LOCAL_SCHEME)) {
                    String path = url.substring(LOCAL_SCHEME.length());
                    
                    int qIdx = path.indexOf('?');
                    if (qIdx >= 0) path = path.substring(0, qIdx);
                    
                    int fIdx = path.indexOf('#');
                    if (fIdx >= 0) path = path.substring(0, fIdx);
                    
                    if (path.isEmpty()) path = "index.html";

                    WebResourceResponse response = serveAssetResponse(path);
                    if (response != null) return response;
                }
                return super.shouldInterceptRequest(view, request);
            }

            
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                if (url != null && url.startsWith(LOCAL_SCHEME)) {
                    String path = url.substring(LOCAL_SCHEME.length());
                    int qIdx = path.indexOf('?');
                    if (qIdx >= 0) path = path.substring(0, qIdx);
                    int fIdx = path.indexOf('#');
                    if (fIdx >= 0) path = path.substring(0, fIdx);
                    if (path.isEmpty()) path = "index.html";

                    WebResourceResponse response = serveAssetResponse(path);
                    if (response != null) return response;
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null) return false;
                String reqUrl = request.getUrl() == null ? null : request.getUrl().toString();
                if (!request.isForMainFrame()) {
                    // 子框架：只放行本地资源，远程 iframe 一律不加载，避免远程页面拿到 JS 桥
                    return !isTrustedPageUrl(reqUrl);
                }
                return handleUrlOverride(reqUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlOverride(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 按 URL 判定当前页面是否可信：本地页面保留/挂上 JS 桥，外部页面摘掉桥
                updateJsBridgesForUrl(url);
                
                
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

                
                
                String urlNoQuery = (url != null && url.contains("?"))
                        ? url.substring(0, url.indexOf("?"))
                        : url;
                boolean isIndexPage = false;
                if (urlNoQuery != null) {
                    
                    String path = urlNoQuery;
                    int schemeEnd = path.indexOf("://");
                    if (schemeEnd >= 0) path = path.substring(schemeEnd + 3);
                    
                    int slashIdx = path.indexOf('/');
                    String fileName = (slashIdx < 0) ? "" : path.substring(slashIdx + 1);
                    isIndexPage = fileName.isEmpty() || fileName.equals("index.html");
                }

                
                
                if (isIndexPage) {
                    indexPageReady = true;
                    
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
                            
                            
                            
                            
                            
                            
                            Log.w("MainActivity",
                                    "WebView 请求麦克风(RESOURCE_AUDIO_CAPTURE)，已拒绝：本应用不使用录音功能");
                            pendingWebPermissionRequest = null;
                            request.deny();
                            return;
                        }
                    }

                    request.grant(resources);
                    
                    
                    
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
                
                
                syncStatusBarAppearance();
                
                setFullscreenInsets(false);
            }
        });
    }

    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    
    // ======================================================================
    private void requestEntryPermissions() {
        try {
            entryPermQueue.clear();
            
            if (!hasNotificationPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
            
            //
            
            
            
            
            
            
            
            
            if (!hasAppListPermissionOuter()) {
                
                
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
            
            notifyWebPermissions();
        } catch (Throwable tr) {
            Log.w("MainActivity", "handleEntryPermissionResult 失败（已忽略）", tr);
        }
    }

    
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

    
    private static final String PERM_GET_INSTALLED_APPS =
            "com.android.permission.GET_INSTALLED_APPS";

    
    private static final String MIUI_PERM_PROVIDER = "com.lbe.security.miui";

    
    private boolean isRuntimePermissionEnable() {
        try {
            final int v = android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    "oem_installed_apps_runtime_permission_enable", 0);
            if (v > 0) return true;
        } catch (Throwable ignored) {}
        
        try {
            return isInstalledPkg(MIUI_PERM_PROVIDER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    
    private boolean hasOemInstalledAppsPermission() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(PERM_GET_INSTALLED_APPS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    
    private boolean hasAppListPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            
            if (isRuntimePermissionEnable()) {
                return hasOemInstalledAppsPermission();
            }
            
            return checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    
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
    
    // ----------------------------------------------------------------------
    
    
    
    //
    
    
    
    
    
    
    
    
    //
    
    
    
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
                
                return insets;
            });

            
            try {
                WindowInsetsControllerCompat c =
                        ViewCompat.getWindowInsetsController(root);
                if (c != null) {
                    c.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
                }
            } catch (Throwable ignored) {}

            
            ViewCompat.requestApplyInsets(root);
        } catch (Throwable tr) {
            Log.w("MainActivity", "setupWindowInsets 失败（已忽略）", tr);
        }
    }

    
    private void applyInsetsToViews(boolean fullscreen) {
        if (mainContent == null) return;
        if (fullscreen) {
            mainContent.setPadding(0, 0, 0, 0);
        } else {
            mainContent.setPadding(
                    lastSideInset,          
                    lastStatusBarInset,     
                    lastSideInset,          
                    lastNavBarInset);       
        }
    }

    
    private void setFullscreenInsets(boolean fullscreen) {
        try {
            applyInsetsToViews(fullscreen);
            Log.d("MainActivity", "setFullscreenInsets: fullscreen=" + fullscreen);
        } catch (Throwable tr) {
            Log.w("MainActivity", "setFullscreenInsets 失败（已忽略）", tr);
        }
    }

    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    //
    
    
    
    
    
    
    
    
    
    // ======================================================================
    private void syncStatusBarAppearance() {
        try {
            final Window win = getWindow();
            if (win == null) return;

            final int bg = androidx.core.content.ContextCompat.getColor(this, R.color.app_window_bg);
            final boolean lightBar = getResources().getBoolean(R.bool.app_light_status_bar);

            
            win.setStatusBarColor(bg);
            
            win.setNavigationBarColor(bg);

            
            final View decor = win.getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int flags = decor.getSystemUiVisibility();
                if (lightBar) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (lightBar) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                decor.setSystemUiVisibility(flags);
            }

            
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
            
            Log.w("MainActivity", "syncStatusBarAppearance 失败（已忽略）", tr);
        }
    }

    
    private void injectBlobReaderJs(String blobUrl) {
        String escapedBlobUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function(){" +
            "try{" +
                
                "var blob=window.__blobStore&&window.__blobStore['" + escapedBlobUrl + "'];" +
                "if(blob){" +
                    "var reader=new FileReader();" +
                    "reader.onloadend=function(){Android.onBlobData(reader.result);};" +
                    "reader.onerror=function(){Android.onBlobError('读取文件内容失败');};" +
                    "reader.readAsDataURL(blob);" +
                "}else{" +
                    
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

    // 说明：这里只校验 assets 能否读出并启动首页，没有任何解密动作（旧名 decrypt* 会误导）
    private void verifyAssetsAndLoad() {
        
        
        
        
        showLoading();

        executor.execute(() -> {
            
            File oldCache = new File(getCacheDir(), "web");
            if (oldCache.exists()) deleteRecursive(oldCache);

            
            
            boolean ok = false;
            try {
                InputStream is = getAssets().open(ASSET_INDEX);
                byte[] head = new byte[64];
                int n = is.read(head);
                is.close();
                ok = n > 0;
            } catch (Exception e) {
                
            }

            final boolean success = ok;
            runOnUiThread(() -> {
                hideLoading();
                if (!success) {
                    Toast.makeText(MainActivity.this, "资源加载失败", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                
                webView.loadUrl(LOCAL_SCHEME + "index.html");
            });
        });
    }

    
    private void switchIndexPage(final int pageNo) {
        if (indexPageReady) {
            
            String fn;
            if (pageNo == 1) fn = "showHome";
            else if (pageNo == 2) fn = "showFavorites";
            else fn = "showProfile";
            currentTabIndex = pageNo;
            
            String js = "if(typeof " + fn + "==='function'){" + fn + "();true}else{false}";
            webView.evaluateJavascript(js, value -> {
                
                if (value != null && value.contains("false")) {
                    indexPageReady = false;
                    webView.loadUrl(LOCAL_SCHEME + "index.html?page=PAGES" + pageNo);
                }
            });
        } else {
            
            currentTabIndex = pageNo;
            webView.loadUrl(LOCAL_SCHEME + "index.html?page=PAGES" + pageNo);
        }
    }

    
    // 说明：这里只是把 assets 里的文件原样读出（AssetsEncryptor 的加解密并未参与），
    //       旧名 decryptAndServe 会被误读成"解密后返回"，改名不改行为
    private WebResourceResponse serveAssetResponse(String path) {
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
            
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

    
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
        // 仅向本地受信任页面回灌传感器数据，外部页面不回灌
        if (!currentPageTrusted) return;
        
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

    
    //
    
    
    
    
    
    //      · requestDeviceAdmin() / getDeviceAdminStatus() /
    
    
    
    
    //
    
    

    
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

    
    static void restartAfterTaskRemoved(Context ctx) {
        
        
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
        
        if (now - sp.getLong(KEY_AUTO_RESTART_AT, 0L) < 15000L) {
            Log.i("Shield", "防终结保活冷却中，跳过");
            return;
        }
        sp.edit().putLong(KEY_AUTO_RESTART_AT, now).apply();

        Log.i("Shield", "应用被从最近任务划掉，保活守护服务（不拉起界面）");
        
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ProtectService.class));
        } catch (Exception ignored) {}
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ForegroundService.class));
        } catch (Exception ignored) {}
        
        cancelRestartAlarm(ctx);
    }

    
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
        
        String url = webView.getUrl();
        String urlNoQuery = (url != null && url.contains("?"))
                ? url.substring(0, url.indexOf("?"))
                : url;

        
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

    
    private void safeGoBack() {
        if (!webView.canGoBack()) return;

        
        String backUrl = webView.getOriginalUrl();
        if (backUrl == null) backUrl = "";
        if (backUrl.contains("about:blank") || backUrl.isEmpty()) {
            
            webView.loadUrl(LOCAL_SCHEME + "index.html");
            return;
        }
        webView.goBack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        
        SharedPreferences sp0 = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        applyHideRecents(sp0.getBoolean("hide_recents", false));
        
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
            
            
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        
        if (requestCode == REQ_OEM_APPLIST_PERMISSION) {
            boolean granted = false;
            try {
                granted = grantResults != null && grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {}
            Log.i("MainActivity", "读取应用列表权限结果：granted=" + granted);
            if (!granted) {
                
                openAppInfoPageOuter();
            }
            notifyWebPermissions();
            return;
        }

        
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
            
            requestNextEntryPermission();
            
            if (entryPermQueue.isEmpty()) {
                handleEntryPermissionResult(deniedPermission);
            }
            return;
        }

        
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

        
        //
        //     if (pendingWebPermissionRequest != null) {
        //         pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
        //         pendingWebPermissionRequest = null;
        //     }
        //
        
        
        
        
        //
        
        
        
        
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        
        syncStatusBarAppearance();
        super.onConfigurationChanged(newConfig);

        

        
        getStatusBarHeight();
        setupStatusBarPadding();

        
        if (webView != null) {
            webView.requestLayout();

            
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

        
        if (isFullscreen && fullscreenContainer != null) {
            fullscreenContainer.requestLayout();
        }
    }

    private void startForegroundService() {
        
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
            
            
            try {
                if (!executor.isShutdown()) {
                    executor.execute(() -> {
                        File oldCache = new File(getCacheDir(), "web");
                        if (oldCache.exists()) deleteRecursive(oldCache);
                    });
                }
            } catch (RejectedExecutionException ignored) {
                
            }
            executor.shutdown();
        }
        
        
        
        
        
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

        

        
        @JavascriptInterface
        public void openStellarRepo() {
            openUrlExternally("https://github.com/RikkaApps/Shizuku");
        }

        
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
        
        // ------------------------------------------------------------------
        
        
        
        //
        
        
        //     res/raw/open_source_licenses.txt
        
        
        
        
        //
        
        
        
        
        // ==================================================================

        
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
                    
                }
            }
        }

        
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
                
                lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.82f);
                w.setAttributes(lp);
            }
        }

        // ==================================================================
        
        // ------------------------------------------------------------------
        
        
        
        
        // ==================================================================

        
        @JavascriptInterface
        public void openStellarManager() {
            runOnUiThread(() -> {
                
                
                
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

        
        @JavascriptInterface
        public void openBuiltinPrivilegeManager() {
            runOnUiThread(() -> {
                final String cls = "roro.stellar.manager.MainActivity";
                CrashLogger.event("openBuiltinPrivilegeManager(): 准备启动 " + cls);
                try {
                    
                    
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
        
        // ==================================================================

        
        @JavascriptInterface
        public boolean hasCrashLog() {
            try {
                return CrashLogger.hasCrash(MainActivity.this);
            } catch (Throwable tr) {
                return false;
            }
        }

        
        @JavascriptInterface
        public String getDiagnostics() {
            try {
                return CrashLogger.buildDiagnostics(MainActivity.this);
            } catch (Throwable tr) {
                return "生成诊断报告失败：" + tr;
            }
        }

        
        @JavascriptInterface
        public void copyDiagnostics() {
            final String text = getDiagnostics();
            runOnUiThread(() -> {
                copyTextToClipboard(text);
                Toast.makeText(MainActivity.this,
                        "日志已复制，直接粘贴发给开发者即可", Toast.LENGTH_LONG).show();
            });
        }

        
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

        
        @JavascriptInterface
        public void clearCrashLog() {
            try {
                CrashLogger.clear(MainActivity.this);
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "崩溃记录已清除", Toast.LENGTH_SHORT).show());
        }

        
        @JavascriptInterface
        public void logEvent(final String msg) {
            if (msg == null) return;
            CrashLogger.event("[页面] " + msg);
        }

        
        @JavascriptInterface
        public void copyText(final String text) {
            if (text == null || text.isEmpty()) return;
            runOnUiThread(() -> {
                copyTextToClipboard(text);
                Toast.makeText(MainActivity.this, "诊断信息已复制", Toast.LENGTH_SHORT).show();
            });
        }

        
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

        
        @JavascriptInterface
        public void connectEmbeddedStellar() {
            runOnUiThread(() -> {
                initStellar();
                Toast.makeText(MainActivity.this, "正在连接内置特权服务…", Toast.LENGTH_SHORT).show();
            });
        }

        
        

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
                
                Toast.makeText(MainActivity.this, "正在检查游龙桌面宠物...", Toast.LENGTH_SHORT).show();
                try {
                    
                    getPackageManager().getPackageInfo(PET_PACKAGE, 0);
                    
                    Intent launchIntent = new Intent();
                    launchIntent.setComponent(new android.content.ComponentName(PET_PACKAGE, "com.youlong.zoo.presentation.ANekoActivity"));
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Toast.makeText(MainActivity.this, "正在启动游龙桌面宠物", Toast.LENGTH_SHORT).show();
                    startActivity(launchIntent);
                } catch (PackageManager.NameNotFoundException e) {
                    
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
                    
                    File oldCache = new File(MainActivity.this.getCacheDir(), PET_ASSET);
                    if (oldCache.exists()) oldCache.delete();

                    
                    InputStream is = MainActivity.this.getAssets().open(PET_ASSET);
                    byte[] apkData = AssetsEncryptor.readAllBytes(is);
                    is.close();

                    
                    File cacheFile = new File(MainActivity.this.getCacheDir(), PET_ASSET);
                    FileOutputStream fos = new FileOutputStream(cacheFile);
                    fos.write(apkData);
                    fos.close();

                    
                    Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this,
                            MainActivity.this.getPackageName() + ".fileprovider",
                            cacheFile);

                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    installIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    
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
            
            openAccessibilitySettings();
        }

        

        
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

                
                File dcim = new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumbnails");
                if (dcim.exists()) thumbBytes += dirSize(dcim);
                File picThumb = new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/.thumbnails");
                if (picThumb.exists()) thumbBytes += dirSize(picThumb);
                File dcimThumb2 = new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumb");
                if (dcimThumb2.exists()) thumbBytes += dirSize(dcimThumb2);

                
                File androidData = new File(android.os.Environment.getExternalStorageDirectory(), "Android/data");
                if (androidData.exists()) {
                    File[] pkgs = androidData.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            File cache = new File(pkg, "cache");
                            if (cache.exists()) tempBytes += dirSize(cache);
                            
                            File tempSub = new File(pkg, "temp");
                            if (tempSub.exists()) tempBytes += dirSize(tempSub);
                        }
                    }
                }

                
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

                
                File obbDir = new File(android.os.Environment.getExternalStorageDirectory(), "Android/obb");
                if (obbDir.exists()) {
                    File[] pkgs = obbDir.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            obbBytes += dirSize(pkg);
                        }
                    }
                }

                
                File tombs = new File("/data/tombstones");
                if (tombs.exists()) logBytes += dirSize(tombs);
                File anr = new File("/data/anr");
                if (anr.exists()) logBytes += dirSize(anr);

                
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

                
                File sdcard = android.os.Environment.getExternalStorageDirectory();
                File[] trash = sdcard.listFiles((d, n) -> n.startsWith(".trash") || n.contains(".Trash") || n.contains("trash"));
                if (trash != null) for (File f : trash) tempBytes += dirSize(f);

                
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

        
        @SuppressLint("WrongConstant")
        @JavascriptInterface
        public String performDeepClean() {
            try {
                
                long freeBefore = android.os.Environment.getExternalStorageDirectory().getFreeSpace();

                
                deleteDir(new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumbnails"));
                deleteDir(new File(android.os.Environment.getExternalStorageDirectory(), "Pictures/.thumbnails"));
                deleteDir(new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/.thumb"));

                
                deleteDir(getCacheDir());
                deleteDir(getExternalCacheDir());
                deleteDir(new File(getCacheDir().getParentFile(), "app_webview"));
                deleteDir(new File(getCacheDir().getParentFile(), "webview"));
                deleteDir(new File(getCacheDir().getParentFile(), "databases"));
                deleteDir(new File(getCacheDir().getParentFile(), "app_database"));
                deleteDir(new File(getCacheDir().getParentFile(), "app_geolocation"));
                
                try { getCacheDir().getParentFile().delete(); } catch (Exception ignored) {}

                
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

                
                
                File obbDir = new File(android.os.Environment.getExternalStorageDirectory(), "Android/obb");
                if (obbDir.exists()) {
                    PackageManager pm = getPackageManager();
                    File[] pkgs = obbDir.listFiles();
                    if (pkgs != null) {
                        for (File pkg : pkgs) {
                            try {
                                pm.getPackageInfo(pkg.getName(), 0);
                                
                            } catch (PackageManager.NameNotFoundException e) {
                                
                                deleteDir(pkg);
                            }
                        }
                    }
                }

                
                deleteDir(new File("/data/local/tmp"));
                deleteDir(new File("/data/tombstones"));
                deleteDir(new File("/data/anr"));
                deleteDir(new File("/data/system/dropbox"));

                
                File sdcard = android.os.Environment.getExternalStorageDirectory();
                File[] trash = sdcard.listFiles((d, n) -> n.startsWith(".trash") || n.contains(".Trash") || n.contains("trash"));
                if (trash != null) for (File f : trash) deleteDir(f);

                
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

                
                long freeAfter = android.os.Environment.getExternalStorageDirectory().getFreeSpace();
                double freedMB = Math.max(0, (freeAfter - freeBefore) / 1048576.0);
                String msg = String.format("成功释放 %.1f MB 存储空间", freedMB > 0 ? freedMB : 
                    
                    (scanTotalEstimate() > 0 ? scanTotalEstimate() : 0));

                return String.format("{\"freed\":%.1f,\"message\":\"%s\"}", 
                    freedMB > 0 ? freedMB : 0, 
                    freedMB > 0 ? msg : "清理完成！建议重启手机彻底释放系统缓存");
            } catch (Exception e) {
                Log.e("Clean", "performDeepClean err", e);
                return "{\"freed\":0,\"message\":\"清理出错: " + e.getMessage() + "\"}";
            }
        }

        
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
                
            }
            return size;
        }

        

        
        @JavascriptInterface
        public String getMissingPermissions() {
            boolean accOk = isAccessibilityEnabled();
            boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(MainActivity.this);
            boolean usageOk = checkUsagePermission();
            
            
            
            
            return "{\"accessibility\":" + accOk
                    + ",\"overlay\":" + overlayOk
                    + ",\"usage\":" + usageOk
                    + ",\"deviceAdmin\":false}";
        }

        
        @JavascriptInterface
        public boolean isStellarReady() {
            return isStellarAvailable() && hasStellarPermission();
        }

        // ==================================================================
        
        // ------------------------------------------------------------------
        
        
        
        
        //
        
        
        
        
        // ==================================================================
        // ==================================================================
        
        // ------------------------------------------------------------------
        
        
        //
        
        
        
        
        
        
        
        //
        
        // ==================================================================
        @JavascriptInterface
public String getEntryRequirements() {
            return MainActivity.this.buildEntryRequirementsJson();
        }

        
private boolean hasNotificationPermission() {
            return MainActivity.this.hasNotificationPermissionOuter();
        }

        
private boolean hasAppListAccess() {
            return MainActivity.this.hasAppListAccessOuter();
        }

        
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

        
        @JavascriptInterface
        public void requestAppListPermission() {
            runOnUiThread(() -> {
                
                
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

        
        @JavascriptInterface
        public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }


        
        @JavascriptInterface
        public String getRootDiagnostics() {
            return MainActivity.this.buildRootDiagnosticsJson();
        }

        @JavascriptInterface
        public String getEnvironmentWarning() {
            
            
            
            
            final boolean root = MainActivity.this.deviceIsRooted();
            final boolean dhizuku = MainActivity.this.deviceHasDhizuku();

            
            String level = "none";
            String title = "";
            String message = "";
            if (root) {
                level = "root";
                title = "当前系统环境异常";
                // 拦截率是真实运行统计，这里不编造数字
                message = "检测到 Root 环境，拦截率统计中";
            } else if (dhizuku) {
                level = "dhizuku";
                title = "当前系统环境异常";
                message = "检测到 Dhizuku 环境，拦截率统计中";
            }

            return "{\"level\":\"" + level + "\",\"dhizuku\":" + dhizuku
                    + ",\"root\":" + root
                    + ",\"title\":\"" + title + "\",\"message\":\"" + message + "\"}";
        }

        
        
        
        
        
        

        
        @JavascriptInterface
        public boolean getHideRecents() {
            return getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("hide_recents", false);
        }

        
        @JavascriptInterface
        public void setHideRecents(boolean hide) {
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putBoolean("hide_recents", hide).apply();
            applyHideRecents(hide);
        }

        
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
            
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            }
        }

        
        @JavascriptInterface
        public boolean isBatteryOptIgnored() {
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            } catch (Exception e) {
                return false;
            }
        }

        
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

            
            StringBuilder missing = new StringBuilder();
            if (!accessibilityOk) appendMissing(missing, "无障碍");
            if (!overlayOk) appendMissing(missing, "悬浮窗");
            if (!usageOk) appendMissing(missing, "使用情况");

            
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

            
            
            
            
            
            
            
            
            
            
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            final boolean userChoseTrigger = prefs.getBoolean("trigger_mode_user_set", false);
            final boolean restoreVol = prefs.getBoolean("user_volume_trigger_on", false);
            final boolean restoreShake = prefs.getBoolean("user_shake_trigger_on", true);

            SharedPreferences.Editor ed = prefs.edit()
                    .putBoolean("protect_on", true)
                    .putLong("protect_start_time", System.currentTimeMillis())
                    
                    .putBoolean("auto_block_on", true);
            if (userChoseTrigger) {
                
                ed.putBoolean("volume_trigger_on", restoreVol)
                        .putBoolean("shake_trigger_on", restoreShake);
            } else {
                
                ed.putBoolean("shake_trigger_on", true)
                        .putBoolean("volume_trigger_on", false);
            }
            ed.apply();

            
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
            
            prefs.edit()
                    .putBoolean("protect_on", false)
                    .putBoolean("shake_trigger_on", false)
                    .putBoolean("volume_trigger_on", false)
                    
                    .putLong("protect_start_time", 0L)
                    .putLong(KEY_AUTO_RESTART_AT, 0L)
                    .apply();
            
            Intent intent = new Intent(MainActivity.this, ProtectService.class);
            stopService(intent);
            try {
                Intent gi = new Intent(MainActivity.this, ForegroundService.class);
                stopService(gi);
            } catch (Exception ignored) {}
            cancelKeepAliveAlarm();
            
            cancelRestartAlarm(MainActivity.this);
            
            
            try { AdSkipService.setForegroundChangeListener(null); } catch (Exception ignored) {}
            try { AdSkipService.setVolumeChangeListener(null); } catch (Exception ignored) {}
            
            ProtectService.cancelRescueNotifications(MainActivity.this);
            Log.i("MainActivity", "护盾已完全关闭：主开关+触发器均已关闭，服务已停止");
        }

        
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

            
            if (prefOn && !actuallyRunning) {
                Log.i("MainActivity", "护盾pref为开但服务未运行，自动重启");
                Intent intent = new Intent(MainActivity.this, ProtectService.class);
                ContextCompat.startForegroundService(MainActivity.this, intent);
                actuallyRunning = true;
            }

            
            
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

                
                PackageManager pm = getPackageManager();
                List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
                StringBuilder sb = new StringBuilder();
                for (android.content.pm.ApplicationInfo app : apps) {
                    String pkg = app.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) continue;
                    
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if ((app.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) continue;
                    if (app.sourceDir != null && app.sourceDir.startsWith("/system/")) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(pkg);
                }

                
                prefs.edit().putString("whitelist_pkgs", sb.toString())
                        .putBoolean("trust_all_apps", true)
                        .apply();

                
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
                if (blacklist.contains(packageName)) return; 
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
                if (!blacklist.contains(packageName)) return; 
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

        
        @JavascriptInterface
        public void setVolumeTriggerEnabled(boolean enabled) {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            final boolean changed = prefs.getBoolean("volume_trigger_on", false) != enabled;
            SharedPreferences.Editor ed = prefs.edit().putBoolean("volume_trigger_on", enabled);
            
            
            
            
            if (changed) {
                ed.putBoolean("user_volume_trigger_on", enabled)
                        .putBoolean("trigger_mode_user_set", true);
                try {
                    CrashLogger.event("[逃生] 用户切换触发方式：音量键=" + enabled);
                } catch (Throwable ignored) {}
            }
            ed.apply();
            Log.d("MainActivity", "音量触发开关: " + enabled);
            
            boolean protectOn = prefs.getBoolean("protect_on", false);
            if (!protectOn) return;
            
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
            
            
            if (changed) {
                ed.putBoolean("user_shake_trigger_on", enabled)
                        .putBoolean("trigger_mode_user_set", true);
                try {
                    CrashLogger.event("[逃生] 用户切换触发方式：摇一摇=" + enabled);
                } catch (Throwable ignored) {}
            }
            ed.apply();
            Log.d("MainActivity", "摇动触发开关: " + enabled);
            
            boolean protectOn = prefs.getBoolean("protect_on", false);
            if (!protectOn) return;
            
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
            
            Intent si = new Intent(MainActivity.this, ProtectService.class);
            si.setAction("com.youlong.hd.SHAKE_TRIGGER");
            ContextCompat.startForegroundService(MainActivity.this, si);
        }
    }

    

    
    private final Stellar.OnBinderReceivedListener stellarBinderReceivedListener = () -> {
        runOnUiThread(() -> {
            if (isStellarAvailable()) {
                Toast.makeText(MainActivity.this, "特权服务已连接", Toast.LENGTH_SHORT).show();
            }
        });
    };

    
    private final Stellar.OnBinderDeadListener stellarBinderDeadListener = () -> {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, "特权服务已断开，请重新启动", Toast.LENGTH_LONG).show();
        });
    };

    
    private void initStellar() {
        
        //        StellarProvider.Companion.enableMultiProcessSupport(false);
        
        //
        
        
        
        
        
        
        
        
        

        
        Stellar.INSTANCE.addBinderReceivedListener(stellarBinderReceivedListener, null);
        Stellar.INSTANCE.addBinderDeadListener(stellarBinderDeadListener, null);
    }

    
    private boolean isStellarAvailable() {
        return Stellar.INSTANCE.pingBinder();
    }

    
    private boolean hasStellarPermission() {
        return Stellar.INSTANCE.checkSelfPermission("stellar");
    }

    
    private void requestStellarPermission() {
        if (!isStellarAvailable()) {
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "特权服务未启动（点「内置特权服务」页的「打开特权服务管理器」启动）", Toast.LENGTH_LONG).show();
            });
            return;
        }

        if (hasStellarPermission()) {
            
            return;
        }

        Stellar.INSTANCE.requestPermission("stellar", REQUEST_CODE_STELLAR);
    }

    
    @SuppressLint("SetTextI18n")
    private String runStellarCommand(final String command, long timeoutMs) {
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        Process process = null;

        try {
            String cleanCmd = command.trim();
            Log.d("StellarCMD", "Exec: " + cleanCmd);

            
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

            
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                done = waitForTimeout(p, Math.min(remaining, 200L));
            }

            if (!done) {
                destroyQuietly(p);
                outReader.join(500);
                errReader.join(500);
                return "执行超时（" + timeoutMs + "ms），进程已终止";
            }

            
            outReader.join(2000);
            errReader.join(2000);

            
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

            
            if (out.length() > 0) return out;

            
            if (!exitOk) {
                if (err.length() > 0) return err;
                return "执行成功";
            }

            
            if (err.length() > 0) {
                if (exitCode != 0 && exitCode != -1)
                    return "执行失败(code:" + exitCode + ") 错误：" + err;
                return err;
            }

            
            if (exitCode != 0 && exitCode != -1)
                return "执行失败(code:" + exitCode + ")，无输出";

        } catch (Exception e) {
            Log.e("StellarCMD", "Exception", e);
            return "调用异常：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        return "执行成功";
    }

    
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
                
                
                
                return "错误：特权服务未启动（点「内置特权服务」页的「打开特权服务管理器」启动）";
            }

            if (!hasStellarPermission()) {
                
                runOnUiThread(() -> requestStellarPermission());
                return "正在请求权限，请在弹窗中授权";
            }

            
            final String cmd = command;
            executor.execute(() -> {
                final String result = runStellarCommand(cmd, 30000);
                
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

        
        @JavascriptInterface
        public boolean isShizukuReady() {
            return isStellarReady();
        }

        
        @JavascriptInterface
        public String getShizukuServiceStatus() {
            return getServiceStatus();
        }

        
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
            // 注意：下面三个返回值是前端逐字比对的协议常量（index.html 里
            // 'Shizuku已连接'/'Shizuku待授权'/'Shizuku未启动'，分别对应
            // 已连接 / 待授权 / 未启动三种界面状态与按钮），不是品牌文案。
            // 只改 Java 侧会让页面落到「桥接不可用」分支、丢掉「点击启动」入口，
            // 要改必须和 index.html 同步改（见 docs/MIGRATION_STELLAR.md §6.1）。
            if (!isStellarAvailable()) {
                return "Shizuku未启动";
            }
            if (!hasStellarPermission()) {
                return "Shizuku待授权";
            }
            return "Shizuku已连接";
        }
    }

    
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
