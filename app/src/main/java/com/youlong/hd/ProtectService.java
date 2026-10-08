package com.youlong.hd;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import roro.stellar.Stellar;


public class ProtectService extends Service implements SensorEventListener {

    private static final String TAG = "ProtectService";
    private static final String CHANNEL_ID = "shield_channel";
    private static final String WARN_CHANNEL_ID = "shield_warn_channel";
    private static final int NOTIFY_ID = 1001;

    private long startTime;
    private Handler h;
    private Runnable tick;
    private Runnable notifyUpdater;
    private PowerManager.WakeLock wakeLock;
    
    
    
    
    
    
    private final Set<String> whitelistCache =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private final Set<String> blacklistCache =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private BroadcastReceiver volRcvr;
    private BroadcastReceiver pkgRcvr;
    
    
    
    private final AdSkipService.VolumeChangeListener volumeListener =
            new AdSkipService.VolumeChangeListener() {
                @Override
                public void onVolumeChanged(int volumeType, int direction) {
                    Log.v(TAG, "无障碍音量回调 type=" + volumeType + " dir=" + direction);
                    countVolumePress(direction);
                }
            };
    
    
    private static class VolPress {
        final long ts;
        int dir;          
        VolPress(long ts, int dir) { this.ts = ts; this.dir = dir; }
    }
    private final List<VolPress> volPresses = new ArrayList<>();
    
    private final SparseIntArray pressBaseVolumes = new SparseIntArray();
    private String lastWarnPkg = "";
    private long lastWarnTime = 0;
    private AudioManager audioManager;
    private Runnable volPollRunnable;
    
    
    private Runnable rescueNotifLoop;
    private int rescueNotifId = -1;

    
    public static void cancelRescueNotifications(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                for (int id = 2000; id < 3000; id++) {
                    nm.cancel(id);
                }
            }
        } catch (Exception ignored) {}
    }

    
    private final Stellar.OnBinderDeadListener shizukuDeadListener = () -> {
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String currentMode = prefs.getString("shield_mode", "basic");
        if ("super".equals(currentMode) || "extreme".equals(currentMode)
                || "final".equals(currentMode) || "daily".equals(currentMode)) {
            prefs.edit().putString("shield_mode", "basic").apply();
            Log.w(TAG, "Shizuku服务已断开，自动从超级/极强/终结/日常拦截降级为基础模式");
            h.post(() -> Toast.makeText(ProtectService.this,
                    "Shizuku已断开，已自动降级为基础模式", Toast.LENGTH_LONG).show());
        }
    };

    
    private SensorManager sensorManager;
    private Vibrator vibrator;
    private float lastAccelX, lastAccelY, lastAccelZ;
    private long lastShakeTs = 0;
    private int shakeHitCount = 0;
    private long lastShakeRescueTime = 0;
    
    
    
    
    
    
    private static final float[] SHAKE_THRESHOLDS = { 7.0f, 10f, 13.5f, 18f };
    private static final int[] SHAKE_HITS_TABLE = { 3, 3, 4, 5 };
    public static final int SHAKE_LEVEL_MIN = 1;
    public static final int SHAKE_LEVEL_MAX = 4;
    public static final int SHAKE_LEVEL_DEFAULT = 4;   
    private static final long SHAKE_WINDOW = 1500;
    private static final long SHAKE_RESCUE_COOLDOWN_MS = 6000;

    
    static int readShakeLevel(SharedPreferences prefs) {
        int lv = prefs.getInt("shake_sensitivity", SHAKE_LEVEL_DEFAULT);
        if (lv < SHAKE_LEVEL_MIN) return SHAKE_LEVEL_MIN;
        if (lv > SHAKE_LEVEL_MAX) return SHAKE_LEVEL_MAX;
        return lv;
    }
    
    private volatile boolean finalForceStopLoop = false;

    
    private final java.util.concurrent.atomic.AtomicBoolean finalForceStopThreadAlive =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    
    
    
    private volatile java.util.List<String> finalForceStopPkgs = null;

    
    
    
    private volatile boolean dailyForceStopLoop = false;

    
    
    
    private Boolean lastA11yAvailable = null;
    
    private boolean volTriggerSuspendedForA11y = false;
    
    
    private boolean shakeTriggerAutoOnByDegrade = false;
    
    private Boolean lastOverlayAvailable = null;
    
    
    
    
    private volatile java.io.File uninstallForceStopFlag = null;
    
    private volatile boolean dailyOverlayShowing = false;
    
    private volatile boolean dailyAborted = false;
    
    
    private WindowManager.LayoutParams dailyOverlayLp;
    
    
    private volatile View dailyOverlayRoot;

    
    
    private static final int VIRUS_RECHECK_EVERY = 50;
    private int virusCheckCount = 0;
    private int virusAppCacheTick = 0;
    
    private volatile java.util.List<String[]> virusAppCache = null;
    
    private final Set<String> virusHandledPkgs =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    
    private volatile boolean virusPopupShowing = false;
    
    private volatile boolean virusUninstallRunning = false;
    private View virusOverlayView = null;
    private WindowManager virusOverlayWm = null;

    @Override
    public void onCreate() {
        super.onCreate();

        // ==================================================================
        
        // ------------------------------------------------------------------
        
        
        //     RemoteServiceException$ForegroundServiceDidNotStartInTimeException
        
        //
        
        
        
        //
        
        
        
        // ==================================================================
        boolean foregroundRaised = false;
        try {
            createChannels();
            startForeground(NOTIFY_ID, buildMinimalNotify());
            foregroundRaised = true;
            Log.i(TAG, "onCreate：已先行挂上前台通知（避免 5 秒超时崩溃）");
        } catch (Throwable t) {
            
            Log.e(TAG, "先行挂前台通知失败", t);
            CrashLogger.event("[守护服务] 先行挂前台通知失败: " + t);
        }

        
        
        SharedPreferences bootPrefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean protectOn = bootPrefs.getBoolean("protect_on", false);
        boolean shakeOn = bootPrefs.getBoolean("shake_trigger_on", false);
        boolean volOn = bootPrefs.getBoolean("volume_trigger_on", false);
        if (!protectOn && !shakeOn && !volOn) {
            Log.w(TAG, "守护/摇动/音量触发均未开启，停止服务");
            CrashLogger.event("[逃生] 守护服务启动即退出：所有触发开关都是关的");
            
            
            if (foregroundRaised) {
                try {
                    stopForeground(true);
                } catch (Throwable ignored) {
                }
            }
            stopSelf();
            return;
        }
        
        
        CrashLogger.event("[逃生] 守护服务已启动：主开关=" + protectOn
                + " 摇一摇=" + shakeOn + " 音量键=" + volOn
                + " 自动拦截=" + bootPrefs.getBoolean("auto_block_on", true)
                + " 模式=" + bootPrefs.getString("shield_mode", "basic")
                + " 无障碍=" + isAccessibilityServiceEnabled());

        
        
        
        SharedPreferences spTime = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        startTime = spTime.getLong("protect_start_time", 0L);
        if (startTime <= 0L) {
            startTime = System.currentTimeMillis();
            spTime.edit().putLong("protect_start_time", startTime).apply();
        }
        h = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        
        if (audioManager != null) {
            StringBuilder sb = new StringBuilder("音量基线: ");
            for (int stream : VOLUME_STREAMS) {
                int v = audioManager.getStreamVolume(stream);
                lastVolumes.put(stream, v);
                pressBaseVolumes.put(stream, v);
                sb.append(streamName(stream)).append("=").append(v).append(" ");
            }
            Log.i(TAG, sb.toString());
        }

        loadWhitelist();
        loadBlacklist();

        
        VirusDb.loadFromCache(this);
        VirusDb.refreshAsync(this);

        createChannels();
        startForeground(NOTIFY_ID, buildNotify(null));

        
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":WakeLock");
            if (wakeLock != null) wakeLock.acquire();
        }

        
        setupKeepAliveAlarm();

        
        
        
        notifyUpdater = () -> {
            updateNotify(null);
            h.postDelayed(notifyUpdater, 60000);
        };
        h.postDelayed(notifyUpdater, 8000);

        
        
        

        
        AdSkipService.setVolumeChangeListener(volumeListener);

        
        
        
        volRcvr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int dir = 0;
                try {
                    if (i != null) {
                        int cur = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                        int prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                        if (cur >= 0 && prev >= 0) dir = cur > prev ? 1 : (cur < prev ? -1 : 0);
                    }
                } catch (Exception ignored) {}
                Log.v(TAG, "收到 VOLUME_CHANGED_ACTION 广播 dir=" + dir);
                countVolumePress(dir);
            }
        };
        registerReceiver(volRcvr, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));

        
        volPollRunnable = new Runnable() {
            @Override
            public void run() {
                try { checkVolumeStreams(); } catch (Exception e) {
                    Log.e(TAG, "轮询异常", e);
                }
                
                h.removeCallbacks(this);
                h.postDelayed(this, 120);
            }
        };
        h.postDelayed(volPollRunnable, 120);
        Log.i(TAG, "音量轮询已启动 (主线程, 120ms间隔)");

        
        AdSkipService.setForegroundChangeListener(fgPkg -> {
            if (fgPkg == null || fgPkg.equals(getPackageName())) return;
            Log.d(TAG, "无障碍检测到前台变化: " + fgPkg);
            if (isSys(fgPkg)) return;
            if (isWhitelisted(fgPkg)) return;
            if (blacklistCache.contains(fgPkg)) {
                if (fgPkg.equals(lastWarnPkg) &&
                        System.currentTimeMillis() - lastWarnTime < 60000) return;
                Log.w(TAG, "无障碍检测到黑名单应用: " + fgPkg);
                warnPopup(fgPkg, 1, "检测到黑名单应用（" + fgPkg + "）", false);
            }
        });

        
        pkgRcvr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String d = i.getDataString();
                if (d != null && d.startsWith("package:")) {
                    final String p = d.substring(8);
                    h.postDelayed(() -> {
                        if (p.equals(getPackageName())) return;
                        loadBlacklist();
                        
                        virusAppCache = null;
                        virusHandledPkgs.remove(p);
                        if (!isSys(p) && !isWhitelisted(p) && blacklistCache.contains(p)) {
                            warnPopup(p, 1, "检测到黑名单应用已安装", false);
                        }
                    }, 3000);
                }
            }
        };
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgRcvr, pf);

        
        
        
        
        
        
        
        
        
        
        tick = new Runnable() {
            @Override
            public void run() {
                
                
                if (anyTriggerOn() && !isServiceRunning(ForegroundService.class)) {
                    Log.w(TAG, "哨兵进程 ForegroundService 已停止，主进程拉起...");
                    try {
                        ContextCompat.startForegroundService(ProtectService.this,
                                new Intent(ProtectService.this, ForegroundService.class));
                    } catch (Exception ignored) {}
                }

                
                runTickHeavyWorkAsync();

                h.postDelayed(this, 4000);
            }
        };
        h.postDelayed(tick, 2000);

        
        setupShakeSensor();

        
        Stellar.INSTANCE.addBinderDeadListener(shizukuDeadListener, null);
        Log.d(TAG, "Shizuku断连监听已注册");

        
        
        
        //   StellarUtils.isStellarAvailable() / hasStellarPermission() ——
        
        
        
        
        
        h.postDelayed(() -> {
            if (isAccessibilityServiceEnabled()) return;
            Log.w(TAG, "检测到无障碍权限已丢失，若 Shizuku 已连接仍可正常守护");
            new Thread(() -> {
                boolean hasPriv;
                try {
                    hasPriv = StellarUtils.isStellarAvailable()
                            && StellarUtils.hasStellarPermission();
                } catch (Throwable t) {
                    hasPriv = false;
                }
                final boolean hasPrivFinal = hasPriv;
                h.post(() -> showA11yLostToastOnce(hasPrivFinal));
            }, "a11y-lost-check").start();
        }, 1500);

        Log.d(TAG, "ProtectService started");
    }

    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    
    
    
    
    //
    
    
    
    //
    
    //
    
    // ======================================================================
    private void monitorPermissionDegrade() {
        final boolean a11yOk = isAccessibilityServiceEnabled();
        final boolean overlayOk = canDrawOverlays();
        final SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);

        boolean overlayChanged = (lastOverlayAvailable != null && lastOverlayAvailable != overlayOk);
        lastOverlayAvailable = overlayOk;

        boolean a11yChanged = (lastA11yAvailable != null && lastA11yAvailable != a11yOk);

        if (a11yChanged) {
            if (!a11yOk) {
                
                
                
                
                
                
                
                Log.w(TAG, "权限降级：无障碍已丢失，触发方式自动切换为摇一摇");
                shakeTriggerAutoOnByDegrade = !sp.getBoolean("shake_trigger_on", false);
                if (shakeTriggerAutoOnByDegrade) {
                    sp.edit().putBoolean("shake_trigger_on", true).apply();
                }
                
                
                CrashLogger.event("[权限] 无障碍丢失：自动打开摇一摇=" + shakeTriggerAutoOnByDegrade
                        + "，音量键开关保持=" + sp.getBoolean("volume_trigger_on", false));
                final String msg = "无障碍权限已丢失，已自动切换为摇一摇触发（摇动手机即可）。"
                        + "音量键逃生仍保持原来的开关状态，权限恢复后一切照旧";
                h.post(() -> Toast.makeText(ProtectService.this, msg, Toast.LENGTH_LONG).show());
            } else {
                
                Log.i(TAG, "权限恢复：无障碍已重新开启");
                CrashLogger.event("[权限] 无障碍已恢复：摇一摇=" + sp.getBoolean("shake_trigger_on", false)
                        + " 音量键=" + sp.getBoolean("volume_trigger_on", false));
                if (shakeTriggerAutoOnByDegrade) {
                    shakeTriggerAutoOnByDegrade = false;
                    
                    
                    if (sp.getBoolean("volume_trigger_on", false)) {
                        sp.edit().putBoolean("shake_trigger_on", false).apply();
                        Log.i(TAG, "权限恢复：已还原权限丢失前自动打开的摇一摇（用户选择的是音量键）");
                    } else {
                        Log.i(TAG, "权限恢复：保留摇一摇触发（用户未开启音量键，避免逃生方式全空）");
                    }
                }
                if (volTriggerSuspendedForA11y) {
                    volTriggerSuspendedForA11y = false;
                    sp.edit().putBoolean("volume_trigger_on", true).apply();
                    Log.i(TAG, "权限恢复：音量键触发已还原");
                }
                h.post(() -> Toast.makeText(ProtectService.this,
                        "无障碍权限已恢复，防护功能回到正常状态", Toast.LENGTH_LONG).show());
            }
        }

        if (overlayChanged) {
            if (!overlayOk) {
                Log.w(TAG, "权限降级：悬浮窗已丢失，覆盖层无法显示（触发时改为直接跳转本应用）");
                h.post(() -> Toast.makeText(ProtectService.this,
                        "悬浮窗权限已丢失：拦截弹窗将无法显示，触发时会直接跳转到游龙安全护盾",
                        Toast.LENGTH_LONG).show());
            } else {
                Log.i(TAG, "权限恢复：悬浮窗已重新开启");
                h.post(() -> Toast.makeText(ProtectService.this,
                        "悬浮窗权限已恢复，拦截弹窗可正常显示", Toast.LENGTH_LONG).show());
            }
        }

        
        if (a11yChanged && overlayChanged && !a11yOk && !overlayOk) {
            h.post(() -> Toast.makeText(ProtectService.this,
                    "无障碍与悬浮窗权限都已丢失：请摇动手机触发，触发后会直接跳转到游龙安全护盾",
                    Toast.LENGTH_LONG).show());
        }

        lastA11yAvailable = a11yOk;
    }

    
    private boolean canDrawOverlays() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return android.provider.Settings.canDrawOverlays(this);
            }
            return true;   
        } catch (Throwable tr) {
            return false;
        }
    }

    
    private void launchAppForRescue(String reason) {
        Log.w(TAG, "悬浮窗不可用，改为直接跳转到本应用：reason=" + reason);

        
        try {
            Intent it = new Intent(ProtectService.this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            it.putExtra("rescue_from_service", true);
            it.putExtra("rescue_reason", reason);
            startActivity(it);
            Log.i(TAG, "已直接拉起 MainActivity 继续救援流程");
            return;
        } catch (Throwable tr) {
            Log.w(TAG, "直接 startActivity 被系统拦截（后台启动限制），改用全屏通知", tr);
        }

        
        try {
            launchAppForRescueByNotification(reason);
        } catch (Throwable tr) {
            Log.e(TAG, "全屏通知兜底也失败", tr);
        }
    }

    
    private void launchAppForRescueByNotification(String reason) {
        final String CH = "rescue_channel";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "紧急拦截救援", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("悬浮窗不可用时，点击进入应用继续拦截流程");
            nm.createNotificationChannel(ch);
        }

        Intent full = new Intent(ProtectService.this, MainActivity.class);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        full.putExtra("rescue_from_service", true);
        full.putExtra("rescue_reason", reason);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(
                ProtectService.this, 0x5E5C, full, piFlags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("检测到威胁，需要你确认")
                .setContentText("悬浮窗不可用，点此进入游龙安全护盾继续拦截")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("悬浮窗权限不可用，无法直接弹出拦截窗口。\n"
                                + "点击本通知进入「游龙安全护盾」继续处理。\n触发原因：" + reason))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            b.setFullScreenIntent(pi, true);
        } catch (Throwable ignored) {}

        try {
            nm.notify(0x5E5C, b.build());
            Log.i(TAG, "已发全屏通知，引导用户进入应用继续救援");
        } catch (Throwable tr) {
            Log.e(TAG, "发通知失败", tr);
        }
    }

    
    private boolean isAccessibilityServiceEnabled() {
        try {
            int enabled = android.provider.Settings.Secure.getInt(
                    getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    
    private void scanBlacklistedApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);

            for (ApplicationInfo app : apps) {
                String pkg = app.packageName;
                if (pkg == null || pkg.equals(getPackageName())) continue;

                if (isSys(pkg)) continue;
                if (isWhitelisted(pkg)) continue;

                
                if (blacklistCache.contains(pkg)) {
                    
                    
                    
                    
                    
                    
                    final String hitPkg = pkg;
                    h.post(() -> warnIfNotRecent(hitPkg, "检测到黑名单应用（" + hitPkg + "）"));
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "scanBlacklistedApps err", e);
        }
    }

    
    private void warnIfNotRecent(String pkg, String reason) {
        if (pkg.equals(lastWarnPkg) && System.currentTimeMillis() - lastWarnTime < 60000) return;
        Log.w(TAG, reason);
        warnPopup(pkg, 1, reason, false);
    }

    
    private void loadWhitelist() {
        whitelistCache.clear();
        
        whitelistCache.addAll(WhitelistActivity.DEFAULT_TRUSTED_PKGS);
        
        
        
        whitelistCache.addAll(WhitelistActivity.LEGACY_TRUSTED_PKGS);
        
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String raw = prefs.getString("whitelist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) whitelistCache.add(t);
            }
        }
    }

    
    private void loadBlacklist() {
        blacklistCache.clear();
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String raw = prefs.getString("blacklist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) blacklistCache.add(t);
            }
        }
    }

    
    private String getFgSimple() {
        
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int u0idx = line.indexOf("u0 ");
                if (u0idx < 0) continue;
                String afterU0 = line.substring(u0idx + 3);
                int slash = afterU0.indexOf('/');
                if (slash > 0) {
                    String pkg = afterU0.substring(0, slash).trim();
                    
                    
                    
                    
                    if (PkgGuard.isValid(pkg)) { r.close(); return pkg; }
                    Log.w(TAG, "getFgSimple：dumpsys 解析出非法包名，已忽略: " + pkg);
                }
            }
            r.close();
        } catch (Exception e) {
            Log.v(TAG, "getFgSimple dumpsys方法失败", e);
        }

        
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p.processName.equals(getPackageName())) continue;
                        if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                && p.pkgList != null && p.pkgList.length > 0) {
                            return p.pkgList[0];
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "getFgSimple RunningAppProcessInfo方法失败", e);
        }
        return null;
    }

    
    
    
    private String getFgViaStellar() {
        try {
            if (!StellarUtils.isStellarAvailable() || !StellarUtils.hasStellarPermission()) {
                return null;
            }
            String out = StellarUtils.runCommand(
                    "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'", 8000);
            if (out == null || out.startsWith("ERROR:")) return null;

            BufferedReader r = new BufferedReader(new StringReader(out));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int u0idx = line.indexOf("u0 ");
                if (u0idx < 0) continue;
                String afterU0 = line.substring(u0idx + 3);
                int slash = afterU0.indexOf('/');
                if (slash > 0) {
                    String pkg = afterU0.substring(0, slash).trim();
                    
                    if (PkgGuard.isValid(pkg)) {
                        Log.d(TAG, "Shizuku检测前台: " + pkg);
                        return pkg;
                    }
                    Log.w(TAG, "getFgViaStellar：dumpsys 解析出非法包名，已忽略: " + pkg);
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "getFgViaStellar 失败", e);
        }
        return null;
    }

    
    
    private final int[] VOLUME_STREAMS = {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM
    };
    private final SparseIntArray lastVolumes = new SparseIntArray();

    
    private String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC: return "媒体";
            case AudioManager.STREAM_RING: return "铃声";
            case AudioManager.STREAM_NOTIFICATION: return "通知";
            case AudioManager.STREAM_ALARM: return "闹钟";
            case AudioManager.STREAM_SYSTEM: return "系统";
            case AudioManager.STREAM_VOICE_CALL: return "通话";
            case AudioManager.STREAM_DTMF: return "DTMF";
            default: return "流" + stream;
        }
    }

    
    private void checkVolumeStreams() {
        if (audioManager == null) return;

        int changedDir = 0;   
        for (int stream : VOLUME_STREAMS) {
            int cur = audioManager.getStreamVolume(stream);
            int last = lastVolumes.get(stream, -1);
            if (last >= 0 && cur != last) {
                if (changedDir == 0) changedDir = cur > last ? 1 : -1;
                Log.v(TAG, "音量变化: " + streamName(stream) + "(" + stream + ") "
                        + last + "→" + cur);
            }
            lastVolumes.put(stream, cur);
        }

        if (changedDir != 0) {
            
            
            countVolumePress(changedDir);
        }
    }

    
    private void countVolumePress(int dir) {
        
        
        
        
        //
        
        
        
        
        
        
        //
        
        
        
        
        final SharedPreferences volPrefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        final boolean volOn = volPrefs.getBoolean("volume_trigger_on", false);
        final boolean shakeOn = volPrefs.getBoolean("shake_trigger_on", false);
        if (shakeOn && !shakeTriggerAutoOnByDegrade && !volOn) {
            Log.v(TAG, "用户选择的是摇一摇触发，本次音量按键忽略");
            return;
        }
        if (!volOn) {
            
            
            CrashLogger.event("[逃生] 收到音量按键但已忽略：音量键触发开关是关的"
                    + "（摇一摇=" + shakeOn + "）");
            return; 
        }
        if (shakeOn) {
            
            Log.v(TAG, "摇一摇与音量键同时开启，音量键逃生照常计数（不再互相屏蔽）");
        }
        final boolean traceThisPress = volPresses.isEmpty(); 
        if (traceThisPress) {
            CrashLogger.event("[逃生] 收到音量按键（模式="
                    + volPrefs.getString("shield_mode", "basic")
                    + " 摇一摇=" + shakeOn + "）");
        }
        long now = System.currentTimeMillis();
        
        
        
        if (!volPresses.isEmpty() && now - volPresses.get(volPresses.size() - 1).ts < VOL_PRESS_DEDUP_MS) {
            if (dir != 0) {
                VolPress last = volPresses.get(volPresses.size() - 1);
                if (last.dir == 0) {
                    last.dir = dir;
                    Log.v(TAG, "音量按键去重：补全方向=" + dir);
                }
            }
            Log.v(TAG, "音量按键去重忽略（500ms 内已有记录）");
            return;
        }

        
        
        
        if (dir == 0) dir = inferVolumeDirection();

        volPresses.add(new VolPress(now, dir));

        
        final long window = volumePressWindowMs();
        for (int i = volPresses.size() - 1; i >= 0; i--) {
            if (now - volPresses.get(i).ts > window) volPresses.remove(i);
        }

        
        
        final int wantDir = isVolumeUpTrigger() ? 1 : -1;
        int matched = 0;
        for (VolPress p : volPresses) {
            if (p.dir == wantDir || p.dir == 0) matched++;
        }
        final int need = getVolumeTriggerCount();
        Log.d(TAG, "音量按键计数=" + matched + "/" + need
                + " 目标=" + (wantDir > 0 ? "音量+" : "音量-")
                + " 窗口=" + window + "ms");

        if (matched >= need) {
            volPresses.clear();
            
            
            CrashLogger.event("[逃生] 音量键达到触发条件（" + matched + "/" + need
                    + "，目标=" + (wantDir > 0 ? "音量+" : "音量-") + "）");
            triggerVolumeRescue();
        }
    }

    
    private void ensureVolumeTriggerListener() {
        if (!getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getBoolean("volume_trigger_on", false)) {
            return; 
        }
        if (AdSkipService.isVolumeListenerRegistered()) return; 
        if (!isAccessibilityServiceEnabled()) {
            
            return;
        }
        if (AdSkipService.ensureVolumeListener(volumeListener)) {
            Log.w(TAG, "音量键监听曾丢失，已自动重新挂上（逃生恢复可用，无需重启）");
            CrashLogger.event("[逃生] 音量键监听曾丢失，已自愈重挂");
        }
    }

    
    private static final String KEY_VOL_TRIGGER_KEY = "volume_trigger_key";
    private static final String KEY_VOL_TRIGGER_COUNT = "volume_trigger_count";
    private static final int VOL_TRIGGER_COUNT_MIN = 2;
    private static final int VOL_TRIGGER_COUNT_MAX = 10;
    private static final int VOL_TRIGGER_COUNT_DEFAULT = 3;
    
    private static final long VOL_PRESS_DEDUP_MS = 500;
    
    private static final long VOL_PRESS_WINDOW_BASE_MS = 2500;

    
    private int getVolumeTriggerCount() {
        int n = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getInt(KEY_VOL_TRIGGER_COUNT, VOL_TRIGGER_COUNT_DEFAULT);
        if (n < VOL_TRIGGER_COUNT_MIN) n = VOL_TRIGGER_COUNT_MIN;
        if (n > VOL_TRIGGER_COUNT_MAX) n = VOL_TRIGGER_COUNT_MAX;
        return n;
    }

    
    private boolean isVolumeUpTrigger() {
        return "up".equals(getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getString(KEY_VOL_TRIGGER_KEY, "down"));
    }

    
    private long volumePressWindowMs() {
        return Math.max(VOL_PRESS_WINDOW_BASE_MS, getVolumeTriggerCount() * 700L);
    }

    
    private int inferVolumeDirection() {
        if (audioManager == null) return 0;
        int dir = 0;
        for (int stream : VOLUME_STREAMS) {
            int cur = audioManager.getStreamVolume(stream);
            int base = pressBaseVolumes.get(stream, -1);
            if (base < 0) {
                pressBaseVolumes.put(stream, cur);
                continue;
            }
            if (cur != base) {
                if (dir == 0) dir = cur > base ? 1 : -1;
                pressBaseVolumes.put(stream, cur);
            }
        }
        return dir;
    }

    
    private void triggerVolumeRescue() {
        Log.w(TAG, "逃生触发！启动救援");

        
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        final boolean isExtreme = "extreme".equals(prefs.getString("shield_mode", "basic"));
        final boolean isFinalMode = "final".equals(prefs.getString("shield_mode", "basic"));
        final boolean isDailyMode = "daily".equals(prefs.getString("shield_mode", "basic"));
        final boolean isStrictMode = isExtreme || isFinalMode || isDailyMode;

        
        
        CrashLogger.event("[逃生] 开始救援：模式=" + prefs.getString("shield_mode", "basic")
                + " 音量键触发=" + prefs.getBoolean("volume_trigger_on", false)
                + " 摇一摇=" + prefs.getBoolean("shake_trigger_on", false)
                + " 无障碍=" + isAccessibilityServiceEnabled());

        
        
        String fgPkg = AdSkipService.getForegroundPkg();
        if (fgPkg == null || fgPkg.isEmpty()) {
            
            fgPkg = getFgViaUsageStats();
        }
        if (fgPkg == null || fgPkg.isEmpty()) {
            
            fgPkg = getFgViaStellar();
        }
        if (fgPkg == null || fgPkg.isEmpty()) {
            
            fgPkg = getFgSimple();
        }

        Log.i(TAG, "救援前台检测结果: " + (fgPkg != null ? fgPkg : "(null)"));

        
        
        
        
        
        
        
        
        
        if (isDailyMode) {
            
            
            if (fgPkg != null && !fgPkg.isEmpty()
                    && WhitelistActivity.isWhitelisted(ProtectService.this, fgPkg)) {
                Log.i(TAG, "日常模式：前台应用在白名单中，跳过拦截（不弹窗/不写拦截历史）: " + fgPkg);
                CrashLogger.event("[逃生] 前台是白名单应用，已跳过拦截: " + fgPkg);
                h.post(() -> Toast.makeText(ProtectService.this,
                        "前台应用在白名单中，已跳过拦截（如需拦截请先在白名单中移除）",
                        Toast.LENGTH_LONG).show());
                lastShakeRescueTime = System.currentTimeMillis(); 
                return;
            }
            String safePkg = (fgPkg == null || fgPkg.isEmpty()) ? "(无法识别的界面)" : fgPkg;
            Log.i(TAG, "日常模式：不受前台/系统应用限制，直接弹出覆盖层（前台=" + safePkg + "）");
            warnPopup(safePkg, 2, "逃生触发", true);
            return;
        }

        
        if ("com.youlong.hd".equals(fgPkg)) {
            Log.i(TAG, "前台是游龙安全护盾自身，跳过");
            return;
        }

        
        
        
        
        
        
        
        if (fgPkg != null && !fgPkg.isEmpty()
                && WhitelistActivity.isWhitelisted(ProtectService.this, fgPkg)) {
            Log.i(TAG, "前台应用在白名单中：跳过拦截，不弹救援窗: " + fgPkg);
            CrashLogger.event("[逃生] 前台是白名单应用，已跳过拦截: " + fgPkg);
            h.post(() -> Toast.makeText(ProtectService.this,
                    "前台应用在白名单中，已跳过拦截（如需拦截请先在白名单中移除）",
                    Toast.LENGTH_LONG).show());
            lastShakeRescueTime = System.currentTimeMillis(); 
            return;
        }

        
        if (fgPkg != null && !fgPkg.isEmpty() && isSys(fgPkg)) {
            if (isStrictMode) {
                Log.i(TAG, "极强/终结拦截模式：系统应用界面也触发拦截: " + fgPkg);
            } else {
                Log.i(TAG, "前台应用是系统应用，不触发救援: " + fgPkg);
                
                
                CrashLogger.event("[逃生] 已跳过：基础模式下前台是系统应用（" + fgPkg + "）");
                return;
            }
        }

        
        warnPopup(fgPkg, 2, "逃生触发", true);
    }

    
    private String getFgViaUsageStats() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long now = System.currentTimeMillis();
            
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 10000, now);
            if (stats == null || stats.isEmpty()) return null;

            
            UsageStats top = null;
            for (UsageStats s : stats) {
                if (s.getLastTimeUsed() <= 0) continue;
                if (top == null || s.getLastTimeUsed() > top.getLastTimeUsed()) {
                    top = s;
                }
            }

            if (top != null) {
                Log.d(TAG, "UsageStats检测前台: " + top.getPackageName()
                        + " lastUsed=" + (now - top.getLastTimeUsed()) + "ms ago");
                return top.getPackageName();
            }
        } catch (Exception e) {
            Log.e(TAG, "UsageStats前台检测失败", e);
        }
        return null;
    }

    
    
    
    private void showWarnOverlay(String pkg, String reason, boolean isVolumeRescue, final String shieldMode) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;

            final boolean isSuper = "super".equals(shieldMode);
            final boolean isExtreme = "extreme".equals(shieldMode);
            final boolean isFinal = "final".equals(shieldMode);
            final boolean isDaily = "daily".equals(shieldMode);
            final boolean isShizukuMode = isSuper || isExtreme || isFinal || isDaily;

            
            if (isFinal) {
                showFinalOverlay(wm, pkg, reason, isVolumeRescue);
                return;
            }

            
            if (isDaily) {
                showDailyOverlay(wm, pkg, reason, isVolumeRescue);
                return;
            }

            
            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            final boolean pkgUnknown = (pkg == null || pkg.isEmpty());
            final String pkgF = pkg;

            
            final LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(isShizukuMode ? 0xDD0D1B3D : 0xDD1B0000);
            root.setPadding(40, 60, 40, 60);

            
            root.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            root.setFitsSystemWindows(false);

            
            TextView icon = new TextView(this);
            icon.setText(isShizukuMode ? "\uD83D\uDEE1\uFE0F" : "\u26A0\uFE0F");
            icon.setTextSize(56);
            icon.setGravity(Gravity.CENTER);
            icon.setPadding(0, 0, 0, 8);
            root.addView(icon);

            
            TextView title = new TextView(this);
            if (isSuper) {
                title.setText("超级拦截 · 强制停止");
            } else if (isExtreme) {
                title.setText("极强拦截 · 全面停止");
            } else {
                title.setText(isVolumeRescue ? "\u2757 紧急救援" : "\u2757 检测到高风险应用！");
            }
            title.setTextColor(Color.WHITE);
            title.setTextSize(22);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, 10);
            root.addView(title);

            
            final TextView pkgTv = new TextView(this);
            pkgTv.setText(pkgUnknown ? "⚠ 无法识别前台应用" : pkg);
            pkgTv.setTextColor(pkgUnknown ? 0xFFFF8888 : 0xFFFFCC00);
            pkgTv.setTextSize(16);
            pkgTv.setGravity(Gravity.CENTER);
            pkgTv.setPadding(0, 0, 0, 6);
            root.addView(pkgTv);

            
            final TextView reasonTv = new TextView(this);
            if (isSuper) {
                reasonTv.setText("通过 Shizuku 执行 am force-stop 强制停止应用\n如不操作将在10秒后自动执行");
            } else if (isExtreme) {
                reasonTv.setText("通过 Shizuku 获取全部第三方应用并逐个强制停止\n如不操作将在5秒后自动执行（自动跳过游龙安全护盾）");
            } else {
                reasonTv.setText(pkgUnknown ? reason + " — 请手动选择要卸载的应用" : reason);
            }
            reasonTv.setTextColor(isShizukuMode ? 0xFFFFCCCC : 0xFFFF8888);
            reasonTv.setTextSize(14);
            reasonTv.setGravity(Gravity.CENTER);
            reasonTv.setPadding(0, 0, 0, 24);
            root.addView(reasonTv);

            
            final TextView countdownTv = new TextView(this);
            countdownTv.setGravity(Gravity.CENTER);
            countdownTv.setPadding(0, 0, 0, 16);
            root.addView(countdownTv);

            
            final LinearLayout btnContainer = new LinearLayout(this);
            btnContainer.setOrientation(LinearLayout.VERTICAL);
            btnContainer.setGravity(Gravity.CENTER);
            root.addView(btnContainer);

            
            final Button btnMain = new Button(this);
            btnMain.setTextColor(Color.WHITE);
            btnMain.setTextSize(18);
            btnMain.setBackgroundColor(0xFFD32F2F);
            btnMain.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mp.bottomMargin = 12;
            btnMain.setLayoutParams(mp);
            btnContainer.addView(btnMain);

            
            final Button btnClose = new Button(this);
            btnClose.setText("关闭");
            btnClose.setTextColor(0xFFAAAAAA);
            btnClose.setTextSize(15);
            btnClose.setBackgroundColor(0xFF444444);
            btnClose.setPadding(30, 18, 30, 18);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnClose.setLayoutParams(cp);
            btnContainer.addView(btnClose);

            
            if (isExtreme) {
                final boolean[] stageDone = {false};

                
                countdownTv.setText("5 秒后将自动全面拦截");
                countdownTv.setTextColor(0xFFFFCC00);
                countdownTv.setTextSize(16);
                btnMain.setText("立即全面拦截");

                final Runnable[] countdownTask = new Runnable[1];
                final java.util.concurrent.atomic.AtomicInteger countdown =
                        new java.util.concurrent.atomic.AtomicInteger(5);

                
                final Runnable doExtremeStop = new Runnable() {
                    @Override
                    public void run() {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        stageDone[0] = true;
                        countdownTv.setText("正在获取全部第三方应用...");
                        countdownTv.setTextColor(0xFFFFCC00);
                        btnMain.setEnabled(false);
                        btnClose.setEnabled(false);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                
                                String listResult;
                                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                                    listResult = StellarUtils.runCommand("pm list packages -3", 15000);
                                } else {
                                    listResult = execShell("pm list packages -3");
                                }
                                Log.w(TAG, "极强拦截 pm list packages -3 结果长度: " + (listResult == null ? 0 : listResult.length()));

                                
                                final java.util.List<String> pkgs = new ArrayList<>();
                                if (listResult != null) {
                                    String[] lines = listResult.split("\n");
                                    for (String line : lines) {
                                        String t = line.trim();
                                        if (t.startsWith("package:")) {
                                            String name = t.substring(8).trim();
                                            if (name.isEmpty()) continue;
                                            if (isProtectedFinalPkg(name)) continue; 
                                            pkgs.add(name);
                                        }
                                    }
                                }

                                
                                String result = "OK";
                                if (!pkgs.isEmpty()) {
                                    StringBuilder batch = new StringBuilder();
                                    int batchSize = 0;
                                    for (int i = 0; i < pkgs.size(); i++) {
                                        if (batchSize > 0) batch.append("; ");
                                        batch.append("am force-stop ").append(pkgs.get(i));
                                        batchSize++;
                                        if (batchSize >= 20 || i == pkgs.size() - 1) {
                                            String cmd = batch.toString();
                                            
                                            if (cmd.contains("com.youlong.hd") || cmd.contains("com.youlong.zoo")
                                                    || cmd.contains("com.youlong.tool")
                                                    || cmd.contains(getPackageName())) {
                                                Log.w(TAG, "极强拦截 force-stop 命令包含保护包名，已拦截丢弃!");
                                            } else {
                                                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                                                    result = StellarUtils.runCommand(cmd, 60000);
                                                } else {
                                                    result = execShell(cmd);
                                                }
                                            }
                                            batch.setLength(0);
                                            batchSize = 0;
                                        }
                                    }
                                }
                                Log.w(TAG, "极强拦截全面 force-stop 结果: " + result + " (共" + pkgs.size() + "个应用)");

                                final int stopCount = pkgs.size();
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        
                                        if (stopCount == 0) {
                                            countdownTv.setText("已全面停止（未发现第三方应用）");
                                        } else {
                                            countdownTv.setText("已强制停止 " + stopCount + " 个第三方应用");
                                        }
                                        countdownTv.setTextColor(0xFF4CAF50);
                                        countdownTv.setTextSize(16);

                                        
                                        if (!pkgUnknown && isProtectedFinalPkg(pkgF)) {
                                            
                                            pkgTv.setText("已全面停止：" + pkgF);
                                            pkgTv.setTextColor(0xFF4CAF50);
                                            reasonTv.setText("前台应用为受保护应用，已自动跳过（绝不卸载/冻结）");
                                            reasonTv.setTextColor(0xFF4CAF50);
                                            countdownTv.setText("受保护应用已保护");
                                            countdownTv.setTextColor(0xFF4CAF50);
                                            btnMain.setText("关闭");
                                            btnMain.setEnabled(true);
                                            btnMain.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    lastShakeRescueTime = System.currentTimeMillis();
                                                    try { wm.removeView(root); } catch (Exception ignored) {}
                                                }
                                            });
                                            btnClose.setVisibility(View.GONE);
                                        } else if (!pkgUnknown) {
                                            
                                            pkgTv.setText("已全面停止：" + pkgF);
                                            pkgTv.setTextColor(0xFF4CAF50);
                                            
                                            reasonTv.setText("已全面停止，是否卸载前台应用 " + pkgF + " ？");
                                            reasonTv.setTextColor(0xFFFFCC00);

                                            
                                            btnMain.setText("立即卸载");
                                            btnMain.setEnabled(true);
                                            btnClose.setText("不卸载，关闭");
                                            btnClose.setTextColor(0xFFFFFFFF);
                                            btnClose.setBackgroundColor(0xFF2E7D32);
                                            btnClose.setEnabled(true);
                                            btnClose.setVisibility(View.VISIBLE);

                                            
                                            final java.util.concurrent.atomic.AtomicInteger cd2 =
                                                    new java.util.concurrent.atomic.AtomicInteger(5);
                                            final Runnable[] cd2Task = new Runnable[1];
                                            cd2Task[0] = new Runnable() {
                                                @Override
                                                public void run() {
                                                    int sec = cd2.decrementAndGet();
                                                    if (sec > 0) {
                                                        countdownTv.setText(sec + " 秒后将自动卸载");
                                                        if (sec <= 2) countdownTv.setTextColor(0xFFFF4444);
                                                        h.postDelayed(this, 1000);
                                                    } else {
                                                        countdownTv.setText("正在卸载...");
                                                        executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                                    }
                                                }
                                            };
                                            countdownTask[0] = cd2Task[0];
                                            h.postDelayed(cd2Task[0], 1000);

                                            
                                            btnMain.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                    countdownTv.setText("正在卸载...");
                                                    executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                                }
                                            });

                                            
                                            btnClose.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                    showFreezeAsk(pkgF, reasonTv, countdownTv, btnMain, btnClose, root, wm);
                                                }
                                            });
                                        } else {
                                            
                                            btnMain.setText("关闭");
                                            btnMain.setEnabled(true);
                                            btnMain.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    lastShakeRescueTime = System.currentTimeMillis();
                                                    try { wm.removeView(root); } catch (Exception ignored) {}
                                                }
                                            });
                                            btnClose.setVisibility(View.GONE);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                };

                
                btnMain.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (stageDone[0]) return;
                        doExtremeStop.run();
                    }
                });

                
                btnClose.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        lastShakeRescueTime = System.currentTimeMillis();
                        try { wm.removeView(root); } catch (Exception ignored) {}
                    }
                });

                
                countdownTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        int sec = countdown.decrementAndGet();
                        if (sec > 0) {
                            countdownTv.setText(sec + " 秒后将自动全面拦截");
                            if (sec <= 3) countdownTv.setTextColor(0xFFFF4444);
                            h.postDelayed(this, 1000);
                        } else {
                            doExtremeStop.run();
                        }
                    }
                };
                h.postDelayed(countdownTask[0], 1000);

                wm.addView(root, lp);
                Log.i(TAG, "极强拦截覆盖层已显示: " + pkg);
                return;
            }

            
            if (isSuper) {
                final boolean[] stageDone = {false}; 

                
                countdownTv.setText("5 秒后将自动强制停止");
                countdownTv.setTextColor(0xFFFFCC00);
                countdownTv.setTextSize(16);
                btnMain.setText("立即强制停止");

                final Runnable[] countdownTask = new Runnable[1];
                final java.util.concurrent.atomic.AtomicInteger countdown =
                        new java.util.concurrent.atomic.AtomicInteger(5);

                
                final Runnable doForceStop = new Runnable() {
                    @Override
                    public void run() {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);

                        
                        
                        
                        
                        if (!pkgUnknown && isProtectedFinalPkg(pkgF)) {
                            Log.w(TAG, "超级拦截：受保护应用（含白名单），禁止强制停止: " + pkgF);
                            h.post(new Runnable() {
                                @Override
                                public void run() {
                                    countdownTv.setText("⚠ 受保护应用，禁止强制停止: " + pkgF);
                                    countdownTv.setTextColor(0xFFFF4444);
                                    reasonTv.setText("此应用为受保护应用（自己/桌面宠物/游龙工具/白名单）");
                                    reasonTv.setTextColor(0xFFFF4444);
                                    pkgTv.setText("受保护应用：" + pkgF);
                                    pkgTv.setTextColor(0xFF4CAF50);
                                    btnMain.setText("关闭");
                                    btnMain.setEnabled(true);
                                    btnMain.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            lastShakeRescueTime = System.currentTimeMillis();
                                            try { wm.removeView(root); } catch (Exception ignored) {}
                                        }
                                    });
                                    btnClose.setVisibility(View.GONE);
                                }
                            });
                            return;
                        }
                        stageDone[0] = true;
                        countdownTv.setText("正在通过 Shizuku 强制停止...");
                        countdownTv.setTextColor(0xFFFFCC00);
                        btnMain.setEnabled(false);
                        btnClose.setEnabled(false);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String result;
                                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                                    result = StellarUtils.runCommand("am force-stop " + pkgF, 10000);
                                } else {
                                    try {
                                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop " + pkgF}).waitFor();
                                        result = "OK(fallback)";
                                    } catch (Exception e) {
                                        result = "ERROR:" + e.getMessage();
                                    }
                                }
                                Log.w(TAG, "超级拦截 force-stop 结果: " + result);

                                
                                final String finalResult = result;
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        
                                        boolean stopped = !isAppRunning(pkgF);
                                        if (stopped || finalResult.contains("OK")) {
                                            pkgTv.setText("已强制停止：" + pkgF);
                                            pkgTv.setTextColor(0xFF4CAF50);
                                            reasonTv.setText("am force-stop 已执行，是否继续卸载此应用？");
                                            reasonTv.setTextColor(0xFFFFCC00);
                                        } else {
                                            pkgTv.setText("强制停止可能失败：" + pkgF);
                                            pkgTv.setTextColor(0xFFFF4444);
                                            reasonTv.setText("am force-stop 执行后应用仍在运行，请手动处理");
                                            reasonTv.setTextColor(0xFFFF4444);
                                        }
                                        
                                        countdownTv.setText("5 秒后将自动卸载");
                                        countdownTv.setTextColor(0xFFFF4444);
                                        countdownTv.setTextSize(18);
                                        
                                        btnMain.setText("立即卸载");
                                        btnMain.setEnabled(true);
                                        btnClose.setText("不卸载，关闭");
                                        btnClose.setTextColor(0xFFFFFFFF);
                                        btnClose.setBackgroundColor(0xFF2E7D32);
                                        btnClose.setEnabled(true);

                                        
                                        final java.util.concurrent.atomic.AtomicInteger cd2 =
                                                new java.util.concurrent.atomic.AtomicInteger(5);
                                        final Runnable[] cd2Task = new Runnable[1];
                                        cd2Task[0] = new Runnable() {
                                            @Override
                                            public void run() {
                                                int sec = cd2.decrementAndGet();
                                                if (sec > 0) {
                                                    countdownTv.setText(sec + " 秒后将自动卸载");
                                                    if (sec <= 2) countdownTv.setTextColor(0xFFFF4444);
                                                    h.postDelayed(this, 1000);
                                                } else {
                                                    countdownTv.setText("正在卸载...");
                                                    executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                                }
                                            }
                                        };
                                        countdownTask[0] = cd2Task[0];
                                        h.postDelayed(cd2Task[0], 1000);

                                        
                                        btnMain.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                countdownTv.setText("正在卸载...");
                                                executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                            }
                                        });

                                        
                                        btnClose.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                showFreezeAsk(pkgF, reasonTv, countdownTv, btnMain, btnClose, root, wm);
                                            }
                                        });
                                    }
                                });
                            }
                        }).start();
                    }
                };

                
                btnMain.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (stageDone[0]) return;
                        doForceStop.run();
                    }
                });

                
                btnClose.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        lastShakeRescueTime = System.currentTimeMillis();
                        try { wm.removeView(root); } catch (Exception ignored) {}
                    }
                });

                
                countdownTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        int sec = countdown.decrementAndGet();
                        if (sec > 0) {
                            countdownTv.setText(sec + " 秒后将自动强制停止");
                            if (sec <= 3) countdownTv.setTextColor(0xFFFF4444);
                            h.postDelayed(this, 1000);
                        } else {
                            doForceStop.run();
                        }
                    }
                };
                h.postDelayed(countdownTask[0], 1000);

                wm.addView(root, lp);
                Log.i(TAG, "超级拦截覆盖层已显示: " + pkg);
                return;
            }

            
            final Runnable[] countdownTask = new Runnable[1];
            if (isVolumeRescue) {
                countdownTv.setText("5 秒后自动跳转到应用设置");
                countdownTv.setTextColor(0xFFFFFF00);
                countdownTv.setTextSize(16);

                final java.util.concurrent.atomic.AtomicInteger countdown =
                        new java.util.concurrent.atomic.AtomicInteger(5);
                countdownTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        int sec = countdown.decrementAndGet();
                        if (sec > 0) {
                            countdownTv.setText(sec + " 秒后自动跳转到应用设置");
                            h.postDelayed(this, 1000);
                        } else {
                            countdownTv.setText("正在跳转...");
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                            jumpToAppSettings(pkgF, pkgUnknown);
                        }
                    }
                };
                h.postDelayed(countdownTask[0], 1000);
            } else {
                countdownTv.setVisibility(View.GONE);
            }

            btnMain.setText(pkgUnknown ? "打开全部应用列表" : "卸载此应用");
            btnMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (countdownTask[0] != null) {
                        h.removeCallbacks(countdownTask[0]);
                    }
                    lastShakeRescueTime = System.currentTimeMillis();
                    try { wm.removeView(root); } catch (Exception ignored) {}
                    jumpToAppSettings(pkgF, pkgUnknown);
                }
            });

            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (countdownTask[0] != null) {
                        h.removeCallbacks(countdownTask[0]);
                    }
                    lastShakeRescueTime = System.currentTimeMillis();
                    try { wm.removeView(root); } catch (Exception ignored) {}
                }
            });

            wm.addView(root, lp);
            Log.i(TAG, "直接覆盖层弹窗已显示: " + pkg);
        } catch (SecurityException e) {
            
            
            
            Log.w(TAG, "覆盖层权限不足(SYSTEM_ALERT_WINDOW)，改为跳转本应用: " + pkg, e);
            launchAppForRescue(reason != null && !reason.isEmpty() ? reason : "检测到威胁");
        } catch (Exception e) {
            Log.e(TAG, "showWarnOverlay 失败，改为跳转本应用", e);
            launchAppForRescue(reason != null && !reason.isEmpty() ? reason : "检测到威胁");
        }
    }

    
    
    
    
    
    
    private void showFinalOverlay(final WindowManager wm, final String pkg,
                                  final String reason, final boolean isVolumeRescue) {
        try {
            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            
            final LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xDD0D1B3D);
            root.setPadding(40, 60, 40, 60);
            root.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            root.setFitsSystemWindows(false);

            
            final LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            root.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            wm.addView(root, lp);
            Log.i(TAG, "终结模式覆盖层已显示: " + pkg);

            
            showFinalStage1(wm, root, content, pkg, reason, isVolumeRescue);
        } catch (SecurityException e) {
            Log.w(TAG, "终结模式覆盖层权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showFinalOverlay 失败", e);
        }
    }

    
    private void showFinalStage1(final WindowManager wm, final LinearLayout root,
                                 final LinearLayout content, final String pkg,
                                 final String reason, final boolean isVolumeRescue) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDEE1\uFE0F");
        icon.setTextSize(48);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 6);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("终结模式 · 强力清除");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);
        content.addView(title);

        
        final ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                dp(72), dp(72));
        sp.gravity = Gravity.CENTER;
        sp.topMargin = 6;
        sp.bottomMargin = 10;
        spinner.setLayoutParams(sp);
        spinner.setIndeterminate(true);
        content.addView(spinner);

        
        final TextView percentTv = new TextView(this);
        percentTv.setText("正在终止所有系统应用  0%");
        percentTv.setTextColor(0xFF7CFF8A);
        percentTv.setTextSize(20);
        percentTv.setGravity(Gravity.CENTER);
        percentTv.setPadding(0, 0, 0, 8);
        content.addView(percentTv);

        final TextView statusTv = new TextView(this);
        statusTv.setText("正在获取全部应用并强制停止...");
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(15);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 10);
        content.addView(statusTv);

        final TextView tipTv = new TextView(this);
        tipTv.setText("正在终止所有系统应用\n（已自动保护游龙安全护盾）");
        tipTv.setTextColor(0xFFFFCCCC);
        tipTv.setTextSize(14);
        tipTv.setGravity(Gravity.CENTER);
        tipTv.setPadding(0, 0, 0, 10);
        content.addView(tipTv);

        
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        
        final java.util.concurrent.atomic.AtomicInteger percent = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger firstCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean firstDone = new java.util.concurrent.atomic.AtomicBoolean(false);

        
        final LinearLayout stage1Btn = new LinearLayout(this);
        stage1Btn.setOrientation(LinearLayout.VERTICAL);
        stage1Btn.setGravity(Gravity.CENTER);
        stage1Btn.setPadding(0, 8, 0, 0);
        content.addView(stage1Btn);

        Button btnStage1Close = new Button(this);
        btnStage1Close.setText("关闭（停止并退出）");
        btnStage1Close.setTextColor(Color.WHITE);
        btnStage1Close.setTextSize(15);
        btnStage1Close.setBackgroundColor(0xFF555555);
        btnStage1Close.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams s1p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnStage1Close.setLayoutParams(s1p);
        stage1Btn.addView(btnStage1Close);
        btnStage1Close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finalForceStopLoop = false; 
                alive.set(false);           
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });

        
        
        final Runnable[] percentTicker = new Runnable[1];
        percentTicker[0] = new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) return;
                if (percent.get() < 99) {
                    percent.incrementAndGet();
                    percentTv.setText("正在终止所有系统应用  " + percent.get() + "%");
                }
                h.postDelayed(percentTicker[0], 350);
            }
        };

        
        final Runnable[] switchCheck = new Runnable[1];
        switchCheck[0] = new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) {
                    finalForceStopLoop = false;
                    return;
                }
                if (firstDone.get() && percent.get() >= 92) {
                    percentTv.setText("正在终止所有系统应用  99%");
                    showFinalSelectStage(wm, root, content, firstCount.get());
                    return; 
                }
                h.postDelayed(switchCheck[0], 400);
            }
        };

        
        new Thread(new Runnable() {
            @Override
            public void run() {
                finalForceStopThreadAlive.set(true);
                try {
                    if (!StellarUtils.isStellarAvailable() || !StellarUtils.hasStellarPermission()) {
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                spinner.setVisibility(View.GONE);
                                percentTv.setVisibility(View.GONE);
                                statusTv.setText("⚠ 需要 Shizuku 权限");
                                statusTv.setTextColor(0xFFFF6B6B);
                                tipTv.setText("请先在 Shizuku 应用中授权本应用\n授权后重新触发终结模式");
                                tipTv.setTextColor(0xFFFF6B6B);
                            }
                        });
                        return;
                    }

                    finalForceStopLoop = true;
                    finalForceStopPkgs = null;


                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive.get()) {
                                finalForceStopLoop = false;
                                return;
                            }
                            h.postDelayed(percentTicker[0], 120);
                            h.postDelayed(switchCheck[0], 500);
                        }
                    });


                    int stopCount = doFinalForceStopOnce();
                    firstCount.set(stopCount);
                    firstDone.set(true);

                    if (!alive.get()) {
                        finalForceStopLoop = false;
                        return;
                    }


                    percent.set(Math.max(percent.get(), Math.min(60, Math.max(15, firstCount.get() / 10))));
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive.get()) {
                                finalForceStopLoop = false;
                                return;
                            }
                            percentTv.setText("正在终止所有系统应用  " + percent.get() + "%");
                            statusTv.setText("已终结 " + firstCount.get() + " 个应用");
                        }
                    });



                    while (finalForceStopLoop && alive.get()) {
                        try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                        if (!finalForceStopLoop || !alive.get()) break;
                        doFinalForceStopOnce();
                    }
                } finally {
                    finalForceStopThreadAlive.set(false);
                }
            }
        }).start();
    }

    
    
    
    
    private int doFinalForceStopOnce() {
        try {
            if (!finalForceStopLoop) return 0;

            
            if (finalForceStopPkgs == null) {
                String listResult;
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    listResult = StellarUtils.runCommand("pm list packages", 15000);
                } else {
                    listResult = execShell("pm list packages");
                }
                if (listResult == null || listResult.startsWith("ERROR:")) {
                    Log.w(TAG, "终结模式 pm list packages 失败: " + listResult);
                    return 0;
                }

                
                final java.util.List<String> pkgs = new ArrayList<>();
                for (String line : listResult.split("\n")) {
                    String t = line.trim();
                    if (!t.startsWith("package:")) continue;
                    String name = cleanPkgName(t.substring(8));
                    if (name.isEmpty()) continue;
                    if (isProtectedFinalPkg(name)) continue;      
                    if (isFinalSystemCriticalPkg(name)) continue; 
                    pkgs.add(name);
                }
                Log.w(TAG, "终结模式首轮解析出 " + pkgs.size() + " 个应用待 force-stop（已缓存，首轮全量执行）");
                finalForceStopPkgs = pkgs;
                
                forceStopPkgRange(pkgs, 0, pkgs.size());
                return pkgs.size();
            }

            
            
            String listResult;
            if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                listResult = StellarUtils.runCommand("pm list packages -3", 15000);
            } else {
                listResult = execShell("pm list packages -3");
            }
            if (listResult == null || listResult.startsWith("ERROR:")) {
                Log.w(TAG, "终结模式循环 pm list packages -3 失败: " + listResult);
                return 0;
            }

            
            final java.util.List<String> thirdPkgs = new ArrayList<>();
            for (String line : listResult.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("package:")) continue;
                String name = cleanPkgName(t.substring(8));
                if (name.isEmpty()) continue;
                if (isProtectedFinalPkg(name)) continue;      
                if (isFinalSystemCriticalPkg(name)) continue; 
                thirdPkgs.add(name);
            }
            
            forceStopPkgRange(thirdPkgs, 0, thirdPkgs.size());
            Log.i(TAG, "终结模式循环中：pm list packages -3 检出 " + thirdPkgs.size()
                    + " 个第三方应用，已全部 force-stop");
            return thirdPkgs.size();
        } catch (Exception e) {
            Log.e(TAG, "终结模式 force-stop 失败", e);
            return 0;
        }
    }

    
    private void forceStopPkgRange(java.util.List<String> pkgs, int start, int end) {
        StringBuilder batch = new StringBuilder();
        int cnt = 0;
        for (int i = start; i < end && i < pkgs.size(); i++) {
            String p = pkgs.get(i);
            if (isProtectedFinalPkg(p)) continue;      
            if (isFinalSystemCriticalPkg(p)) continue; 
            if (cnt > 0) batch.append("; ");
            batch.append("am force-stop ").append(p);
            cnt++;
            if (cnt >= 10) {
                runFinalCommand(batch.toString());
                batch.setLength(0);
                cnt = 0;
            }
        }
        if (cnt > 0) runFinalCommand(batch.toString());
    }

    
    private String runFinalCommand(String cmd) {
        
        if (cmd != null && (cmd.contains("com.youlong.hd") || cmd.contains("com.youlong.zoo")
                || cmd.contains("com.youlong.tool") || cmd.contains(getPackageName()))) {
            Log.w(TAG, "终结模式命令包含保护包名，已拦截丢弃: " + cmd);
            return "BLOCKED(protected)";
        }
        if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
            return StellarUtils.runCommand(cmd, 60000);
        }
        return execShell(cmd);
    }

    
    private boolean isProtectedFinalPkg(String name) {
        if (name == null || name.isEmpty()) return true;
        if (name.equals(getPackageName())) return true;   
        if (name.equals("com.youlong.hd")) return true;  
        if (name.equals("com.youlong.zoo")) return true; 
        if (name.equals("com.youlong.tool")) return true; 
        
        if (isWhitelisted(name)) {
            Log.i(TAG, "终结模式：白名单应用跳过，不终结: " + name);
            return true;
        }
        return false;
    }

    
    
    
    
    private boolean isFinalSystemCriticalPkg(String name) {
        if (name == null || name.isEmpty()) return false;
        
        if (name.equals("com.android.systemui")) return true;
        if (name.startsWith("com.android.internal.systemui.")) return true;
        if (name.startsWith("com.android.systemui.")) return true;
        
        if (name.contains("systemuiplugin") || name.contains("SystemUI")) return true;
        if (name.equals("com.vivo.minscreen")) return true; 
        
        if (name.startsWith("com.android.providers.")) return true;
        
        if (name.equals("com.android.shell")) return true;
        if (name.equals("com.android.incallui")) return true;
        if (name.equals("com.android.emergency")) return true;
        if (name.equals("com.android.mtp")) return true;
        
        if (name.startsWith("com.android.inputmethod")) return true;
        
        if (name.equals("com.google.android.webview")) return true;
        return false;
    }

    
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    
    private String cleanPkgName(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\uFEFF", "").replace("\r", "").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    
    private void showFinalSelectStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int stopCount) {
        content.removeAllViews();

        
        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD28");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        
        TextView title = new TextView(this);
        title.setText("请选择可疑应用，我们将强力清除他");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        content.addView(title);

        TextView sub = new TextView(this);
        sub.setText("已终结 " + stopCount + " 个应用（已排除游龙安全护盾，仍在循环终结中）\n请勾选可疑应用，高敏感权限应用优先");
        sub.setTextColor(0xFFFFCC00);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, 12);
        content.addView(sub);

        
        final TextView hintTv = new TextView(this);
        hintTv.setText("");
        hintTv.setTextColor(0xFFFF6B6B);
        hintTv.setTextSize(14);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, 0, 0, 8);
        content.addView(hintTv);

        
        final ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = 12;
        scroll.setLayoutParams(slp);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setGravity(Gravity.CENTER);
        scroll.addView(listContainer);

        TextView loadingTv = new TextView(this);
        loadingTv.setText("正在加载应用列表...");
        loadingTv.setTextColor(0xFFAAAAAA);
        loadingTv.setTextSize(15);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, 20, 0, 20);
        listContainer.addView(loadingTv);
        content.addView(scroll);

        
        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String[]> apps = getFinalThirdPartyApps();
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        
                        if (root.getParent() == null) return;
                        buildFinalSelectList(wm, root, content, stopCount, listContainer,
                                btnContainer, hintTv, apps);
                    }
                });
            }
        }).start();
    }

    
    private void buildFinalSelectList(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int stopCount,
                                      final LinearLayout listContainer,
                                      final LinearLayout btnContainer,
                                      final TextView hintTv,
                                      final java.util.List<String[]> apps) {
        listContainer.removeAllViews();
        btnContainer.removeAllViews();
        hintTv.setText("");

        final java.util.List<CheckBox> boxes = new ArrayList<>();
        if (apps.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("未发现第三方应用");
            emptyTv.setTextColor(0xFFAAAAAA);
            emptyTv.setTextSize(15);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, 20, 0, 20);
            listContainer.addView(emptyTv);
        } else {
            for (final String[] app : apps) {
                final CheckBox cb = new CheckBox(this);
                String label = app[0];
                String apkg = app[1];
                boolean highRisk = app.length > 2 && "1".equals(app[2]);
                cb.setText((highRisk ? "⚠ " : "") + label + "\n" + apkg);
                cb.setTextColor(highRisk ? 0xFFFF6B6B : Color.WHITE);
                cb.setTextSize(14);
                cb.setPadding(12, 8, 12, 8);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 6, 0, 6);
                cb.setLayoutParams(clp);
                listContainer.addView(cb);
                boxes.add(cb);
            }
        }

        
        final Button btnConfirm = new Button(this);
        btnConfirm.setText("确定");
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(18);
        btnConfirm.setBackgroundColor(0xFFD32F2F);
        btnConfirm.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cbp.bottomMargin = 12;
        btnConfirm.setLayoutParams(cbp);
        btnContainer.addView(btnConfirm);

        
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnConfirm.setEnabled(false);
                final java.util.List<String> selected = new ArrayList<>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (boxes.get(i).isChecked()) {
                        selected.add(apps.get(i)[1]);
                    }
                }
                if (selected.isEmpty()) {
                    hintTv.setText("请先勾选可疑应用");
                    btnConfirm.setEnabled(true);
                    return;
                }
                
                
                showFinalClearingStage(wm, root, content, selected, "正在卸载所选应用...", true);
            }
        });

        
        final Button btnRecover = new Button(this);
        btnRecover.setText("我不小心误触了，立即重启恢复");
        btnRecover.setTextColor(0xFFDDDDDD);
        btnRecover.setTextSize(14);
        btnRecover.setBackgroundColor(0xFF555555);
        btnRecover.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rcp.topMargin = 16;
        btnRecover.setLayoutParams(rcp);
        btnContainer.addView(btnRecover);

        
        btnRecover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnRecover.setEnabled(false);
                btnRecover.setText("正在重启...");
                btnConfirm.setEnabled(false);
                finalForceStopLoop = false;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r;
                        if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                            r = StellarUtils.runCommand("reboot", 10000);
                        } else {
                            r = execShell("reboot");
                        }
                        Log.w(TAG, "终结模式误触恢复 reboot 结果: " + r);
                        final String finalR = r;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalR == null || finalR.startsWith("ERROR:")) {
                                    btnRecover.setText("需要系统权限，请手动重启");
                                    Toast.makeText(ProtectService.this,
                                            "需要系统权限，请手动重启", Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });

        
        final Button btnFinalClose = new Button(this);
        btnFinalClose.setText("关闭（退出，不执行任何操作）");
        btnFinalClose.setTextColor(0xFFAAAAAA);
        btnFinalClose.setTextSize(14);
        btnFinalClose.setBackgroundColor(0xFF3A3A3A);
        btnFinalClose.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fcp.topMargin = 10;
        btnFinalClose.setLayoutParams(fcp);
        btnContainer.addView(btnFinalClose);
        btnFinalClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finalForceStopLoop = false; 
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
    }

    
    
    
    private void showFinalClearingStage(final WindowManager wm, final LinearLayout root,
                                        final LinearLayout content,
                                        final java.util.List<String> targets,
                                        final String statusText,
                                        final boolean uninstall) {

        finalForceStopLoop = false;

        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD25");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        final TextView statusTv = new TextView(this);
        statusTv.setText(statusText);
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(16);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 16);
        content.addView(statusTv);


        final Button btnClearingClose = new Button(this);
        btnClearingClose.setText("关闭（退出，不执行任何操作）");
        btnClearingClose.setTextColor(0xFFAAAAAA);
        btnClearingClose.setTextSize(14);
        btnClearingClose.setBackgroundColor(0xFF3A3A3A);
        btnClearingClose.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnClearingClose.setLayoutParams(clp);
        content.addView(btnClearingClose);
        btnClearingClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });


        new Thread(new Runnable() {
            @Override
            public void run() {


                long waitUntil = System.currentTimeMillis() + 8000;
                while (finalForceStopThreadAlive.get() && System.currentTimeMillis() < waitUntil) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }



                StringBuilder cmdSb = new StringBuilder();
                boolean first = true;
                java.util.List<String> safeTargets = new ArrayList<>();
                for (String t : targets) {
                    if (isProtectedFinalPkg(t)) continue;
                    safeTargets.add(t);
                    if (!first) cmdSb.append("; ");
                    if (uninstall) {
                        cmdSb.append("pm uninstall --user 0 ").append(t);
                    } else {
                        cmdSb.append("pm disable-user --user 0 ").append(t);
                    }
                    first = false;
                }
                Log.w(TAG, "终结模式将" + (uninstall ? "卸载" : "冻结") + " " + safeTargets.size() + " 个: " + safeTargets);
                String finalResult = runFinalCommand(cmdSb.toString());
                Log.w(TAG, "终结模式 " + (uninstall ? "uninstall" : "disable-user") + " 结果: " + finalResult + " (共" + safeTargets.size() + "个)");

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        
                        if (root.getParent() == null) return;
                        
                        showFinalRebootStage(wm, root, content, targets.size(), uninstall);
                    }
                });
            }
        }).start();
    }

    
    private void showFinalRebootStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int clearedCount,
                                      final boolean uninstall) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD04");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("清除完成，需要重启手机");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);
        content.addView(title);

        TextView desc = new TextView(this);
        if (uninstall) {
            
            desc.setText("已卸载 " + clearedCount + " 个可疑应用\n重启后生效，卸载状态持久保持");
        } else {
            
            desc.setText("已冻结 " + clearedCount + " 个可疑应用\n重启后生效，冻结状态持久保持");
        }
        desc.setTextColor(0xFFFFCC00);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, 24);
        content.addView(desc);

        
        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        
        final Button btnReboot = new Button(this);
        btnReboot.setText("立即重启");
        btnReboot.setTextColor(Color.WHITE);
        btnReboot.setTextSize(18);
        btnReboot.setBackgroundColor(0xFFD32F2F);
        btnReboot.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 12;
        btnReboot.setLayoutParams(rp);
        btnContainer.addView(btnReboot);

        
        final Button btnLater = new Button(this);
        btnLater.setText("稍后重启");
        btnLater.setTextColor(0xFFAAAAAA);
        btnLater.setTextSize(15);
        btnLater.setBackgroundColor(0xFF444444);
        btnLater.setPadding(30, 18, 30, 18);
        LinearLayout.LayoutParams lap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLater.setLayoutParams(lap);
        btnContainer.addView(btnLater);

        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnReboot.setEnabled(false);
                btnReboot.setText("正在重启...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r;
                        if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                            r = StellarUtils.runCommand("reboot", 10000);
                        } else {
                            r = execShell("reboot");
                        }
                        Log.w(TAG, "终结模式 reboot 结果: " + r);
                        final String finalR = r;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalR == null || finalR.startsWith("ERROR:")) {
                                    btnReboot.setText("需要系统权限，请手动重启");
                                    Toast.makeText(ProtectService.this,
                                            "需要系统权限，请手动重启", Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lastShakeRescueTime = System.currentTimeMillis();
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
    }

    // ======================================================================
    
    // ======================================================================
    
    
    
    
    
    
    
    private void showDailyOverlay(final WindowManager wm, final String pkg,
                                  final String reason, final boolean isVolumeRescue) {
        if (dailyOverlayShowing) {
            
            
            View r = dailyOverlayRoot;
            if (r == null || r.getParent() == null) {
                Log.w(TAG, "日常模式：旧覆盖层已脱离窗口，重置标记后重新弹出");
                dailyOverlayShowing = false;
                dailyOverlayRoot = null;
            } else {
                Log.w(TAG, "日常模式覆盖层已在显示，忽略重复触发");
                return;
            }
        }
        try {
            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            dailyOverlayLp = lp;

            
            final LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xDD0D1B3D);
            root.setPadding(40, 60, 40, 60);
            root.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            root.setFitsSystemWindows(false);

            
            final LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            root.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            wm.addView(root, lp);
            dailyOverlayShowing = true;
            dailyOverlayRoot = root;
            dailyAborted = false;
            Log.i(TAG, "日常模式覆盖层已显示: " + pkg + " / " + reason);

            
            showDailyThreatStage(wm, root, content);
        } catch (SecurityException e) {
            Log.w(TAG, "日常模式覆盖层权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showDailyOverlay 失败", e);
        }
    }

    
    private void closeDailyOverlay(final WindowManager wm, final LinearLayout root) {
        dailyAborted = true;
        
        
        
        stopUninstallForceStopLoop();
        dailyOverlayShowing = false;
        dailyOverlayRoot = null;
        lastShakeRescueTime = System.currentTimeMillis(); 
        try { wm.removeView(root); } catch (Exception ignored) {}
    }

    
    private void showDailyThreatStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83E\uDDA0");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("你是否遭遇病毒威胁");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 14);
        content.addView(title);

        final TextView statusTv = new TextView(this);
        statusTv.setText("正在终止全部第三方应用...");
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(14);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 8);
        content.addView(statusTv);

        final TextView countdownTv = new TextView(this);
        countdownTv.setText("10 秒后自动选择「是」");
        countdownTv.setTextColor(0xFF7CFF8A);
        countdownTv.setTextSize(15);
        countdownTv.setGravity(Gravity.CENTER);
        countdownTv.setPadding(0, 0, 0, 22);
        content.addView(countdownTv);

        
        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        final Button btnYes = new Button(this);
        btnYes.setText("是");
        btnYes.setTextColor(Color.WHITE);
        btnYes.setTextSize(20);
        btnYes.setBackgroundColor(0xFFD32F2F);
        btnYes.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        yp.bottomMargin = 12;
        btnYes.setLayoutParams(yp);
        btnContainer.addView(btnYes);

        final Button btnNo = new Button(this);
        btnNo.setText("否，误触了");
        btnNo.setTextColor(0xFFDDDDDD);
        btnNo.setTextSize(16);
        btnNo.setBackgroundColor(0xFF555555);
        btnNo.setPadding(30, 18, 30, 18);
        btnNo.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        btnContainer.addView(btnNo);

        final java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        
        final Runnable[] goSelect = new Runnable[1];
        goSelect[0] = new Runnable() {
            @Override
            public void run() {
                if (!answered.compareAndSet(false, true)) return;
                if (!dailyOverlayShowing || dailyAborted) return;
                showDailySelectStage(wm, root, content);
            }
        };

        btnYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goSelect[0].run();
            }
        });
        btnNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answered.set(true);
                closeDailyOverlay(wm, root);
            }
        });

        
        final java.util.concurrent.atomic.AtomicInteger cd =
                new java.util.concurrent.atomic.AtomicInteger(10);
        final Runnable[] cdTask = new Runnable[1];
        cdTask[0] = new Runnable() {
            @Override
            public void run() {
                if (!dailyOverlayShowing || dailyAborted || answered.get()) return;
                int left = cd.decrementAndGet();
                if (left <= 0) {
                    countdownTv.setText("未响应，已自动选择「是」");
                    goSelect[0].run();
                    return;
                }
                countdownTv.setText(left + " 秒后自动选择「是」");
                h.postDelayed(cdTask[0], 1000);
            }
        };
        h.postDelayed(cdTask[0], 1000);

        
        final boolean shizukuOk =
                StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();
        if (!shizukuOk) {
            statusTv.setText("Shizuku 未连接，无法自动终止应用（卸载功能不受影响）");
            statusTv.setTextColor(0xFFFF9500);
        }
        dailyForceStopLoop = true;
        
        
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                runDailyAccessibilityHarden();
            }
        }, "daily-a11y-harden").start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (dailyForceStopLoop && dailyOverlayShowing && !dailyAborted) {
                    
                    
                    
                    
                    
                    
                    
                    
                    runDailyAccessibilityHarden();
                    final int n = doDailyForceStopOnce();
                    if (n > 0) {
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!dailyOverlayShowing || dailyAborted) return;
                                statusTv.setText("已终止 " + n + " 个第三方应用（持续循环中）");
                                statusTv.setTextColor(0xFFFFCC00);
                            }
                        });
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                }
            }
        }).start();
    }

    // ======================================================================
    
    // ----------------------------------------------------------------------
    
    
    //
    
    
    
    
    
    
    //
    
    //     T=com.youlong.hd; C=$(settings get secure enabled_accessibility_services); \
    //     N=$(echo "$C" | tr ':' '\n' | grep "^$T/" | tr '\n' ':' | sed 's/:$//'); \
    //     settings put secure enabled_accessibility_services "$N"; \
    //     settings put secure accessibility_enabled 1; \
    
    //
    
    
    
    
    
    
    
    
    
    //
    
    
    
    // ======================================================================
    private void runDailyAccessibilityHarden() {
        try {
            final String self = getPackageName();
            if (self == null || self.isEmpty()) return;

            
            StringBuilder blacklistPattern = new StringBuilder();
            try {
                for (String p : BlacklistConstants.HARDCODED_BLACKLIST) {
                    if (p != null && !p.isEmpty()) {
                        if (blacklistPattern.length() > 0) blacklistPattern.append("|");
                        blacklistPattern.append(p);
                    }
                }
            } catch (Exception ignored) {}
            try {
                SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                String raw = prefs.getString("blacklist_pkgs", "");
                if (!raw.isEmpty()) {
                    for (String p : raw.split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) {
                            if (blacklistPattern.length() > 0) blacklistPattern.append("|");
                            blacklistPattern.append(t);
                        }
                    }
                }
            } catch (Exception ignored) {}

            StringBuilder sb = new StringBuilder();
            sb.append("T=").append(self).append("; ");
            sb.append("BL='").append(blacklistPattern).append("'; ");
            sb.append("C=$(settings get secure enabled_accessibility_services 2>/dev/null); ");
            sb.append("N=$(echo \"$C\" | tr ':' '\\n' | grep -vE \"^($BL)/\" | tr '\\n' ':' | sed 's/:$//'); ");
            sb.append("timeout 5 settings put secure enabled_accessibility_services \"$N\" 2>/dev/null; ");
            sb.append("timeout 5 settings put secure accessibility_enabled 1 2>/dev/null; ");
            sb.append("echo \"当前启用：$(settings get secure enabled_accessibility_services 2>/dev/null)\"");

            final String script = sb.toString();
            final String out;
            if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {

                out = StellarUtils.runCommand(script, 12000);
            } else {
                out = execShell(script);
            }
            Log.i(TAG, "日常模式：无障碍服务已收窄（仅移除黑名单服务） —— "
                    + (out == null ? "null" : out.trim().replace('\n', ' ')));
        } catch (Throwable tr) {


            Log.w(TAG, "日常模式：清理无障碍服务失败（已忽略）", tr);
        }
    }

    
    
    
    
    private int doDailyForceStopOnce() {
        try {
            if (!dailyForceStopLoop) return 0;
            String listResult;
            if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                listResult = StellarUtils.runCommand("pm list packages -3", 15000);
            } else {
                listResult = execShell("pm list packages -3");
            }
            if (listResult == null || listResult.startsWith("ERROR:")) {
                Log.w(TAG, "日常模式 pm list packages -3 失败: " + listResult);
                return 0;
            }

            final java.util.List<String> pkgs = new ArrayList<>();
            for (String line : listResult.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("package:")) continue;
                String name = cleanPkgName(t.substring(8));
                if (name.isEmpty()) continue;
                if (isProtectedFinalPkg(name)) continue;      
                if (isFinalSystemCriticalPkg(name)) continue; 
                pkgs.add(name);
            }
            if (pkgs.isEmpty()) return 0;

            
            forceStopPkgRange(pkgs, 0, pkgs.size());
            Log.i(TAG, "日常模式：本轮已 force-stop " + pkgs.size() + " 个第三方应用");
            return pkgs.size();
        } catch (Exception e) {
            Log.e(TAG, "日常模式 force-stop 失败", e);
            return 0;
        }
    }

    
    private void showDailySelectStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDCF1");
        icon.setTextSize(46);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 6);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("请选择要卸载的应用");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        content.addView(title);

        TextView sub = new TextView(this);
        sub.setText("带无障碍 / 悬浮窗的应用已排在前面（可多选）\n卸载完成后需要重启手机");
        sub.setTextColor(0xFFFFCC00);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, 10);
        content.addView(sub);

        final TextView hintTv = new TextView(this);
        hintTv.setText("");
        hintTv.setTextColor(0xFFFF6B6B);
        hintTv.setTextSize(14);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, 0, 0, 8);
        content.addView(hintTv);

        final ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = 12;
        scroll.setLayoutParams(slp);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);

        TextView loadingTv = new TextView(this);
        loadingTv.setText("正在读取全部第三方应用...");
        loadingTv.setTextColor(0xFFAAAAAA);
        loadingTv.setTextSize(15);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, 20, 0, 20);
        listContainer.addView(loadingTv);
        content.addView(scroll);

        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String[]> apps = getDailyThirdPartyApps();
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dailyOverlayShowing || dailyAborted) return;
                        buildDailySelectList(wm, root, content, listContainer, btnContainer, hintTv, apps);
                    }
                });
            }
        }).start();
    }

    
    private void buildDailySelectList(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content,
                                      final LinearLayout listContainer,
                                      final LinearLayout btnContainer,
                                      final TextView hintTv,
                                      final java.util.List<String[]> apps) {
        listContainer.removeAllViews();
        btnContainer.removeAllViews();
        hintTv.setText("");

        final java.util.List<CheckBox> boxes = new ArrayList<>();
        if (apps.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("未发现第三方应用");
            emptyTv.setTextColor(0xFFAAAAAA);
            emptyTv.setTextSize(15);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, 20, 0, 20);
            listContainer.addView(emptyTv);
        } else {
            for (String[] app : apps) {
                final CheckBox cb = new CheckBox(this);
                String label = app[0];
                String apkg = app[1];
                String mark = app.length > 2 && app[2] != null ? app[2] : "";
                boolean marked = !mark.isEmpty();
                cb.setText((marked ? "⚠ " : "") + label
                        + (marked ? "（" + mark + "）" : "") + "\n" + apkg);
                cb.setTextColor(marked ? 0xFFFF6B6B : Color.WHITE);
                cb.setTextSize(14);
                cb.setPadding(12, 8, 12, 8);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 6, 0, 6);
                cb.setLayoutParams(clp);
                listContainer.addView(cb);
                boxes.add(cb);
            }
        }

        
        final Button btnUninstall = new Button(this);
        btnUninstall.setText("我已选择完成，立刻卸载");
        btnUninstall.setTextColor(Color.WHITE);
        btnUninstall.setTextSize(18);
        btnUninstall.setBackgroundColor(0xFFD32F2F);
        btnUninstall.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        up.bottomMargin = 12;
        btnUninstall.setLayoutParams(up);
        btnContainer.addView(btnUninstall);

        
        final Button btnClose = new Button(this);
        btnClose.setText("我误触了，关闭");
        btnClose.setTextColor(0xFFDDDDDD);
        btnClose.setTextSize(15);
        btnClose.setBackgroundColor(0xFF555555);
        btnClose.setPadding(30, 16, 30, 16);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        btnContainer.addView(btnClose);

        btnUninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final java.util.List<String> selected = new ArrayList<>();
                for (int i = 0; i < boxes.size() && i < apps.size(); i++) {
                    if (boxes.get(i).isChecked()) selected.add(apps.get(i)[1]);
                }
                if (selected.isEmpty()) {
                    hintTv.setText("请先选择要卸载的应用");
                    return;
                }
                btnUninstall.setEnabled(false);
                btnClose.setEnabled(false);
                showDailyUninstallStage(wm, root, content, selected);
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDailyOverlay(wm, root);
            }
        });
    }

    
    private void showDailyUninstallStage(final WindowManager wm, final LinearLayout root,
                                         final LinearLayout content,
                                         final java.util.List<String> targets) {
        dailyForceStopLoop = false; 

        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD25");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        final TextView statusTv = new TextView(this);
        statusTv.setText("正在卸载所选应用...");
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(16);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 12);
        content.addView(statusTv);

        TextView tipTv = new TextView(this);
        tipTv.setText("请勿关闭屏幕，卸载完成后会提示重启手机");
        tipTv.setTextColor(0xFFAAAAAA);
        tipTv.setTextSize(13);
        tipTv.setGravity(Gravity.CENTER);
        tipTv.setPadding(0, 0, 0, 18);
        content.addView(tipTv);

        
        
        
        final TextView adminLogTv = new TextView(this);
        adminLogTv.setTextColor(0xFF9BE29B);
        adminLogTv.setTextSize(11);
        adminLogTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        adminLogTv.setGravity(Gravity.START);
        adminLogTv.setPadding(8, 8, 8, 8);
        adminLogTv.setTextIsSelectable(true); 
        android.widget.ScrollView adminScroll = new android.widget.ScrollView(this);
        adminScroll.setBackgroundColor(0xFF141414);
        adminScroll.setPadding(6, 6, 6, 6);
        adminScroll.addView(adminLogTv);
        LinearLayout.LayoutParams adminLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        adminLp.bottomMargin = 12;
        adminScroll.setLayoutParams(adminLp);
        adminScroll.setVisibility(View.GONE); 
        content.addView(adminScroll);

        
        final Button btnExit = new Button(this);
        btnExit.setText("关闭（退出，不执行后续操作）");
        btnExit.setTextColor(0xFFAAAAAA);
        btnExit.setTextSize(14);
        btnExit.setBackgroundColor(0xFF3A3A3A);
        btnExit.setPadding(30, 16, 30, 16);
        btnExit.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(btnExit);
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDailyOverlay(wm, root);
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                
                long waitUntil = System.currentTimeMillis() + 8000;
                while (dailyForceStopLoop && System.currentTimeMillis() < waitUntil) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }

                final java.util.List<String> safe = new ArrayList<>();
                for (String t : targets) {
                    if (isProtectedFinalPkg(t)) continue; 
                    safe.add(t);
                }
                if (safe.isEmpty()) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dailyOverlayShowing && !dailyAborted) {
                                showDailyRebootStage(wm, root, content, 0);
                            }
                        }
                    });
                    return;
                }

                // ==========================================================
                
                // ----------------------------------------------------------
                
                
                
                
                //
                
                
                
                //
                
                
                
                
                
                // ==========================================================

                
                
                
                
                startUninstallForceStopLoop(safe);

                String result = runPreUninstallAdminRevoke(safe);
                Log.i(TAG, "日常模式：预卸载解除设备管理员 -> " + result);
                final String adminResult = result;

                
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (adminResult != null && adminResult.trim().length() > 0) {
                                adminLogTv.setText(adminResult.trim());
                                adminScroll.setVisibility(View.VISIBLE);
                            }
                            statusTv.setText("已解除设备管理员限制，开始卸载所选应用（后台持续强制停止中）...");
                        } catch (Exception ignored) {}
                    }
                });

                boolean shizukuOk =
                        StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();
                if (shizukuOk) {
                    
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < safe.size(); i++) {
                        if (i > 0) sb.append("; ");
                        sb.append("pm uninstall ").append(safe.get(i));
                    }
                    String uninstallOut = StellarUtils.runCommand(sb.toString(), 120000);
                    Log.w(TAG, "日常模式 Stellar 卸载结果: " + uninstallOut + " (共" + safe.size() + "个)");
                    
                    if (uninstallOut == null || uninstallOut.startsWith("ERROR:")) shizukuOk = false;
                }

                if (!shizukuOk) {
                    
                    
                    
                    stopUninstallForceStopLoop();
                    uninstallDailyViaSystemApi(wm, root, content, statusTv, safe);
                    return;
                }

                
                stopUninstallForceStopLoop();
                Log.i(TAG, "日常模式：批量卸载完成，已停止后台 force-stop 循环");

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dailyOverlayShowing || dailyAborted) return;
                        showDailyRebootStage(wm, root, content, safe.size());
                    }
                });
            }
        }).start();
    }

    
    private void startUninstallForceStopLoop(final java.util.List<String> targets) {
        dailyForceStopLoop = true;

        
        final java.io.File flag = new java.io.File(getFilesDir(), "uninstall_fs_loop.flag");
        try {
            flag.getParentFile().mkdirs();
            flag.createNewFile();
        } catch (Throwable tr) {
            Log.w(TAG, "创建 force-stop 哨兵文件失败，循环将持续到超时", tr);
        }
        uninstallForceStopFlag = flag;

        
        StringBuilder quoted = new StringBuilder();
        if (targets != null) {
            for (String p : targets) {
                if (p == null || p.isEmpty()) continue;
                if (!p.matches("[A-Za-z0-9_.]+")) continue;
                quoted.append(p).append(' ');
            }
        }
        final String targetList = quoted.toString().trim();

        
        StringBuilder whitelistPattern = new StringBuilder("com.youlong.hd|com.youlong.zoo|com.youlong.tool");
        try {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            String raw = prefs.getString("whitelist_pkgs", "");
            if (!raw.isEmpty()) {
                for (String p : raw.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty() && t.matches("[A-Za-z0-9_.]+")) {
                        whitelistPattern.append("|").append(t);
                    }
                }
            }
        } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("TARGETS=\"").append(targetList).append("\"\n");
        sb.append("FLAG=\"").append(flag.getAbsolutePath()).append("\"\n");
        sb.append("i=0\n");

        sb.append("while [ $i -lt 600 ]; do\n");

        sb.append("  [ -f \"$FLAG\" ] || break\n");

        sb.append("  for p in $TARGETS; do am force-stop $p 2>/dev/null; done\n");

        sb.append("  if [ $((i % 4)) -eq 0 ]; then\n");
        sb.append("    for p in $(pm list packages -3 2>/dev/null | cut -d: -f2); do\n");

        sb.append("      case \"$p\" in ").append(whitelistPattern).append(") continue ;; esac\n");
        sb.append("      case \" $TARGETS \" in *\" $p \"*) continue ;; esac\n");
        sb.append("      am force-stop $p 2>/dev/null\n");
        sb.append("    done\n");
        sb.append("  fi\n");
        sb.append("  i=$((i+1))\n");
        sb.append("  sleep 0.5\n");
        sb.append("done\n");
        sb.append("rm -f \"$FLAG\"\n");
        sb.append("echo 'force-stop 循环结束'\n");

        
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    
                    String out = StellarUtils.runCommand(sb.toString(), 300000);
                    Log.i(TAG, "后台 force-stop 循环退出: "
                            + (out == null ? "null" : out.substring(0, Math.min(80, out.length()))));
                } catch (Throwable tr) {
                    Log.w(TAG, "后台 force-stop 循环异常退出", tr);
                }
            }
        }, "daily-force-stop-loop");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "日常模式：已启动后台 force-stop 循环（目标 " + targetList + "）");
    }

    
    private void stopUninstallForceStopLoop() {
        dailyForceStopLoop = false;
        try {
            if (uninstallForceStopFlag != null && uninstallForceStopFlag.exists()) {
                uninstallForceStopFlag.delete();
            }
        } catch (Throwable ignored) {}
    }

    
    private String runPreUninstallAdminRevoke(final java.util.List<String> targets) {
        
        StringBuilder quoted = new StringBuilder();
        if (targets != null) {
            for (String p : targets) {
                if (p == null || p.isEmpty()) continue;
                if (!p.matches("[A-Za-z0-9_.]+")) continue;  
                quoted.append(p).append(' ');
            }
        }
        final String targetList = quoted.toString().trim();

        
        
        
        
        java.io.File scriptFile = new java.io.File(getFilesDir(), "uninstall_helpers.sh");
        try {
            java.io.InputStream in = getAssets().open("uninstall_helpers.sh");
            java.io.FileOutputStream out = new java.io.FileOutputStream(scriptFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
        } catch (Throwable tr) {
            Log.e(TAG, "写出 uninstall_helpers.sh 失败", tr);
            return "ERROR:无法写出脚本: " + tr;
        }
        
        try { scriptFile.setReadable(true, false); scriptFile.setExecutable(true, false); } catch (Throwable ignored) {}

        
        
        String cmd = "sh " + scriptFile.getAbsolutePath()
                + (targetList.isEmpty() ? "" : " " + targetList);
        Log.i(TAG, "执行预卸载脚本: " + cmd);
        String out = StellarUtils.runCommand(cmd, 180000);
        
        if (out != null && out.length() > 4000) {
            out = "…（前部已省略）…\n" + out.substring(out.length() - 4000);
        }
        return out;
    }

    
    
    
    
    private void uninstallDailyViaSystemApi(final WindowManager wm, final LinearLayout root,
                                            final LinearLayout content, final TextView statusTv,
                                            final java.util.List<String> pkgs) {
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    statusTv.setText("Shizuku服务丢失，现在将调用系统API进行卸载");
                    statusTv.setTextColor(0xFFFF6B6B);
                } catch (Exception ignored) {}
            }
        });

        
        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}

        
        h.post(new Runnable() {
            @Override
            public void run() {
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        for (String p : pkgs) {
            if (dailyAborted) break;
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + p));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Log.w(TAG, "日常模式系统API卸载: " + p);
            } catch (Exception e) {
                Log.e(TAG, "日常模式系统API卸载失败: " + p, e);
            }
            
            try { Thread.sleep(3500); } catch (InterruptedException e) { break; }
        }

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        
        h.post(new Runnable() {
            @Override
            public void run() {
                if (dailyAborted) return;
                try {
                    if (root.getParent() == null && dailyOverlayLp != null) {
                        wm.addView(root, dailyOverlayLp);
                    }
                    dailyOverlayShowing = true;
                    dailyOverlayRoot = root;
                    showDailyRebootStage(wm, root, content, pkgs.size());
                } catch (Exception e) {
                    Log.e(TAG, "日常模式重新挂载覆盖层失败", e);
                }
            }
        });
    }

    
    
    private void showDailyRebootStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int count) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD04");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("卸载完成，需要重启手机");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);
        content.addView(title);

        TextView desc = new TextView(this);
        desc.setText("已卸载 " + count + " 个应用\n重启后生效");
        desc.setTextColor(0xFFFFCC00);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, 20);
        content.addView(desc);

        final TextView hintTv = new TextView(this);
        hintTv.setText("");
        hintTv.setTextColor(0xFFFF6B6B);
        hintTv.setTextSize(15);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, 0, 0, 12);
        content.addView(hintTv);

        final Button btnReboot = new Button(this);
        btnReboot.setText("重启");
        btnReboot.setTextColor(Color.WHITE);
        btnReboot.setTextSize(18);
        btnReboot.setBackgroundColor(0xFFD32F2F);
        btnReboot.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 12;
        btnReboot.setLayoutParams(rp);
        content.addView(btnReboot);

        final Button btnLater = new Button(this);
        btnLater.setText("不重启");
        btnLater.setTextColor(0xFFDDDDDD);
        btnLater.setTextSize(15);
        btnLater.setBackgroundColor(0xFF555555);
        btnLater.setPadding(30, 18, 30, 18);
        btnLater.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(btnLater);

        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                
                if (!(StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission())) {
                    hintTv.setText("Shizuku 权限已丢失，请手动重启手机");
                    return;
                }
                btnReboot.setEnabled(false);
                btnReboot.setText("正在重启...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r = StellarUtils.runCommand("reboot", 10000);
                        Log.w(TAG, "日常模式 reboot 结果: " + r);
                        final String finalR = r;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (finalR == null || finalR.startsWith("ERROR:")) {
                                    btnReboot.setText("需要系统权限，请手动重启");
                                    Toast.makeText(ProtectService.this,
                                            "需要系统权限，请手动重启", Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }).start();
            }
        });

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDailyOverlay(wm, root);
            }
        });
    }

    
    
    private java.util.List<String[]> getDailyThirdPartyApps() {
        java.util.List<String[]> highRiskList = new ArrayList<>();
        java.util.List<String[]> normalList = new ArrayList<>();
        PackageManager pm = getPackageManager();

        
        java.util.Set<String> accessibilityPkgs = new java.util.HashSet<>();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.contains("/")) accessibilityPkgs.add(s.substring(0, s.indexOf('/')));
                }
            }
        } catch (Exception ignored) {}

        
        java.util.Set<String> declaredA11yPkgs = new java.util.HashSet<>();
        try {
            Intent a11yIntent = new Intent(
                    android.accessibilityservice.AccessibilityService.SERVICE_INTERFACE);
            java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentServices(a11yIntent, 0);
            if (ris != null) {
                for (android.content.pm.ResolveInfo ri : ris) {
                    if (ri != null && ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                        declaredA11yPkgs.add(ri.serviceInfo.packageName);
                    }
                }
            }
        } catch (Exception ignored) {}

        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);

        
        java.util.List<ApplicationInfo> apps = null;
        try {
            apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Exception e) {
            Log.e(TAG, "日常模式 getInstalledApplications 失败", e);
        }
        if (apps == null) return new ArrayList<>();

        for (ApplicationInfo ai : apps) {
            try {
                if (ai == null || ai.packageName == null || ai.packageName.isEmpty()) continue;
                
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                String pkg = ai.packageName;
                if (isProtectedFinalPkg(pkg)) continue; 

                String label = pkg;
                try {
                    CharSequence cs = ai.loadLabel(pm);
                    if (cs != null && cs.length() > 0) label = cs.toString();
                } catch (Exception ignored) {}

                
                String mark = "";
                if (accessibilityPkgs.contains(pkg)) {
                    mark = "无障碍";
                } else if (isDailyOverlayGranted(appOps, ai)) {
                    mark = "悬浮窗";
                } else if (declaredA11yPkgs.contains(pkg)) {
                    mark = "无障碍(未启用)";
                }

                if (mark.isEmpty()) {
                    normalList.add(new String[]{label, pkg, ""});
                } else {
                    highRiskList.add(new String[]{label, pkg, mark});
                }
            } catch (Exception ignored) {}
        }

        
        java.util.List<String[]> all = new ArrayList<>(highRiskList);
        all.addAll(normalList);
        Log.i(TAG, "日常模式第三方应用列表：带无障碍/悬浮窗 " + highRiskList.size()
                + " 个，普通 " + normalList.size() + " 个");
        return all;
    }

    
    private boolean isDailyOverlayGranted(AppOpsManager appOps, ApplicationInfo ai) {
        try {
            if (appOps == null || ai == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    ai.uid, ai.packageName);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================================================================
    
    // ======================================================================
    
    
    
    
    //
    
    
    
    
    
    
    

    
    private void virusScanOnce() {
        
        
        
        
        
        if (!getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getBoolean("auto_block_on", true)) {
            return;
        }

        final VirusDb.Data db = VirusDb.get(this);
        if (db.isEmpty()) return;

        
        virusCheckCount++;
        if (virusCheckCount >= VIRUS_RECHECK_EVERY) {
            virusCheckCount = 0;
            Log.i(TAG, "病毒库：已检测 " + VIRUS_RECHECK_EVERY + " 次，重新拉取服务器最新数据");
            VirusDb.refreshAsync(this);
        }

        
        if (virusPopupShowing || virusUninstallRunning) return;
        if (dailyOverlayShowing || finalForceStopLoop) return;

        
        java.util.List<String[]> apps = virusAppCache;
        if (apps == null || virusAppCacheTick >= 10) {
            apps = buildVirusScanAppList();
            virusAppCache = apps;
            virusAppCacheTick = 0;
        }
        virusAppCacheTick++;

        
        for (java.util.Iterator<String> it = virusHandledPkgs.iterator(); it.hasNext(); ) {
            String p = it.next();
            if (!isAppInstalled(p)) it.remove();
        }

        final List<String> certain = new ArrayList<>();       
        final List<String[]> suspect = new ArrayList<>();     
        for (String[] a : apps) {
            if (a == null || a.length < 2) continue;
            String pkg = a[0];
            String label = a[1];
            if (pkg == null || pkg.isEmpty()) continue;
            if (isProtectedFinalPkg(pkg)) continue;           
            if (virusHandledPkgs.contains(pkg)) continue;

            if (db.isCertain(pkg, label)) {
                certain.add(pkg);
            } else {
                String why = db.matchSuspect(pkg, label);
                if (why != null) suspect.add(new String[]{pkg, label, why});
            }
        }

        if (!certain.isEmpty()) {
            Log.w(TAG, "病毒库：发现 " + certain.size() + " 个 100% 病毒应用，需用户确认后卸载");
            for (String p : certain) Log.w(TAG, "病毒库命中(100%): " + p);
            
            
            
            final List<String> certainMain = certain;
            h.post(() -> showVirusCertainConfirm(certainMain));
            return;
        }
        if (!suspect.isEmpty()) {
            Log.w(TAG, "病毒库：发现 " + suspect.size() + " 个可疑应用，询问用户是否卸载");
            for (String[] s : suspect) Log.w(TAG, "病毒库命中(可疑): " + s[0] + " / " + s[1] + " / " + s[2]);
            final List<String[]> suspectMain = suspect;
            h.post(() -> showVirusAskOverlay(suspectMain));
        }
    }

    
    private java.util.List<String[]> buildVirusScanAppList() {
        java.util.List<String[]> out = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                String label = "";
                try {
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null) label = cs.toString().trim();
                } catch (Exception ignored) {}
                out.add(new String[]{ai.packageName, label});
            }
        } catch (Exception e) {
            Log.e(TAG, "构建病毒检测清单失败", e);
        }
        return out;
    }

    
    
    
    private void showVirusCertainConfirm(final List<String> targets) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) { virusUninstallRunning = false; return; }
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\uD83D\uDEE1\uFE0F");
            icon.setTextSize(42);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            title.setText("发现 " + targets.size() + " 个病毒应用");
            title.setTextColor(0xFFFF6B6B);
            title.setTextSize(20);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText("以下应用被病毒库 100% 命中，即将卸载：\n\n是否确认卸载？");
            sub.setTextColor(0xFFFFDDDD);
            sub.setTextSize(14);
            sub.setLineSpacing(dp(4), 1f);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(10), 0, dp(10));
            card.addView(sub);

            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (String p : targets) {
                TextView item = new TextView(this);
                item.setText("• " + appLabelOf(p) + "\n    " + p);
                item.setTextColor(0xFFFFDDDD);
                item.setTextSize(13);
                item.setPadding(0, dp(4), 0, dp(4));
                list.addView(item);
            }
            sv.addView(list);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int listH = Math.min((int) (screenH * 0.32f), dp(30) + targets.size() * dp(46));
            if (listH < dp(60)) listH = dp(60);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, listH));
            card.addView(sv);

            final Button btnGo = new Button(this);
            btnGo.setText("确认卸载");
            btnGo.setTextColor(Color.WHITE);
            btnGo.setTextSize(17);
            btnGo.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp1.topMargin = dp(14);
            btnGo.setLayoutParams(bp1);
            card.addView(btnGo);

            final Button btnLater = new Button(this);
            btnLater.setText("稍后处理");
            btnLater.setTextColor(0xFFDDDDDD);
            btnLater.setTextSize(15);
            btnLater.setBackgroundColor(0xFF555555);
            LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp2.topMargin = dp(8);
            btnLater.setLayoutParams(bp2);
            card.addView(btnLater);

            
            final Runnable[] timeout = new Runnable[1];

            btnGo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    removeVirusOverlayIfAny();
                    startVirusUninstall(targets, true);
                }
            });
            btnLater.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    removeVirusOverlayIfAny();
                    virusUninstallRunning = false;
                    Log.w(TAG, "病毒库：用户在「100% 病毒确认」弹窗中选择稍后处理");
                }
            });

            addVirusOverlay(wm, card);

            timeout[0] = new Runnable() {
                @Override
                public void run() {
                    if (!virusPopupShowing || virusOverlayView != card) return;
                    Log.w(TAG, "病毒库：100% 病毒确认弹窗超时未响应，按稍后处理");
                    removeVirusOverlayIfAny();
                    virusUninstallRunning = false;
                }
            };
            h.postDelayed(timeout[0], 120000);
            Log.i(TAG, "病毒库：已弹出「100% 病毒确认」弹窗，共 " + targets.size() + " 个应用");
        } catch (SecurityException e) {
            
            Log.w(TAG, "病毒库：无悬浮窗权限，跳过确认弹窗直接系统卸载", e);
            runSystemUninstall(targets);
        } catch (Exception e) {
            Log.e(TAG, "showVirusCertainConfirm 失败", e);
            runSystemUninstall(targets);
        }
    }

    
    private void startVirusUninstall(final List<String> pkgs, final boolean certain) {
        if (virusUninstallRunning) return;
        if (pkgs == null || pkgs.isEmpty()) return;
        virusUninstallRunning = true;

        final List<String> targets = new ArrayList<>();
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            if (isProtectedFinalPkg(p)) continue;   
            virusHandledPkgs.add(p);                
            targets.add(p);
        }
        if (targets.isEmpty()) {
            virusUninstallRunning = false;
            return;
        }

        
        h.post(new Runnable() {
            @Override
            public void run() {
                removeVirusOverlayIfAny();
            }
        });

        final boolean shizukuOk =
                StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();

        if (shizukuOk) {
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        StringBuilder sb = new StringBuilder();
                        int cnt = 0;
                        for (String p : targets) {
                            if (cnt > 0) sb.append("; ");
                            sb.append("pm uninstall ").append(p);
                            cnt++;
                            if (cnt >= 10) {
                                runFinalCommand(sb.toString());
                                sb.setLength(0);
                                cnt = 0;
                            }
                        }
                        if (cnt > 0) runFinalCommand(sb.toString());
                        Log.w(TAG, "病毒库：Shizuku 批量卸载完成 " + targets.size() + " 个应用");
                    } catch (Exception e) {
                        Log.e(TAG, "病毒库 Shizuku 卸载异常", e);
                    }
                    finishVirusUninstall(targets);
                }
            }).start();
        } else {
            
            Log.w(TAG, "病毒库：Shizuku 未连接，先弹窗确认再系统卸载 " + targets.size() + " 个应用");
            h.post(new Runnable() {
                @Override
                public void run() {
                    showVirusNoShizukuConfirm(targets);
                }
            });
        }
    }

    
    
    
    private void showVirusNoShizukuConfirm(final List<String> targets) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) { virusUninstallRunning = false; return; }
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\uD83D\uDEE1\uFE0F");
            icon.setTextSize(42);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            title.setText("未连接 Shizuku");
            title.setTextColor(0xFFFFCC00);
            title.setTextSize(20);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText("检测到 " + targets.size() + " 个病毒应用。\n"
                    + "未连接 Shizuku 时无法静默卸载，\n"
                    + "需要你在系统卸载框中逐个点击「确定」。\n\n是否继续卸载？");
            sub.setTextColor(0xFFFFDDDD);
            sub.setTextSize(14);
            sub.setLineSpacing(dp(4), 1f);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(10), 0, dp(10));
            card.addView(sub);

            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (String p : targets) {
                TextView item = new TextView(this);
                item.setText("• " + appLabelOf(p) + "\n    " + p);
                item.setTextColor(0xFFFFDDDD);
                item.setTextSize(13);
                item.setPadding(0, dp(4), 0, dp(4));
                list.addView(item);
            }
            sv.addView(list);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int listH = Math.min((int) (screenH * 0.32f), dp(30) + targets.size() * dp(46));
            if (listH < dp(60)) listH = dp(60);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, listH));
            card.addView(sv);

            final Button btnGo = new Button(this);
            btnGo.setText("继续卸载");
            btnGo.setTextColor(Color.WHITE);
            btnGo.setTextSize(17);
            btnGo.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp1.topMargin = dp(14);
            btnGo.setLayoutParams(bp1);
            card.addView(btnGo);

            final Button btnLater = new Button(this);
            btnLater.setText("稍后处理");
            btnLater.setTextColor(0xFFDDDDDD);
            btnLater.setTextSize(15);
            btnLater.setBackgroundColor(0xFF555555);
            LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp2.topMargin = dp(8);
            btnLater.setLayoutParams(bp2);
            card.addView(btnLater);

            
            final Runnable[] timeout = new Runnable[1];

            btnGo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    removeVirusOverlayIfAny();
                    runSystemUninstall(targets);
                }
            });
            btnLater.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    removeVirusOverlayIfAny();
                    
                    virusUninstallRunning = false;
                    Log.w(TAG, "病毒库：用户在「未连接 Shizuku」弹窗中选择稍后处理");
                }
            });

            addVirusOverlay(wm, card);

            timeout[0] = new Runnable() {
                @Override
                public void run() {
                    if (!virusPopupShowing || virusOverlayView != card) return;
                    Log.w(TAG, "病毒库：未连接 Shizuku 弹窗超时未响应，按稍后处理");
                    removeVirusOverlayIfAny();
                    virusUninstallRunning = false;
                }
            };
            h.postDelayed(timeout[0], 120000);
            Log.i(TAG, "病毒库：已弹出「未连接 Shizuku」确认弹窗，共 " + targets.size() + " 个应用");
        } catch (SecurityException e) {
            
            Log.w(TAG, "病毒库：无悬浮窗权限，跳过确认弹窗直接系统卸载", e);
            runSystemUninstall(targets);
        } catch (Exception e) {
            Log.e(TAG, "showVirusNoShizukuConfirm 失败", e);
            runSystemUninstall(targets);
        }
    }

    
    private void runSystemUninstall(final List<String> targets) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < targets.size(); i++) {
                    final String p = targets.get(i);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent del = new Intent(Intent.ACTION_DELETE,
                                        Uri.parse("package:" + p));
                                del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                        | 0x00000004);
                                startActivity(del);
                                Log.i(TAG, "病毒库：已发起系统卸载 " + p);
                            } catch (Exception e) {
                                Log.e(TAG, "病毒库系统卸载失败: " + p, e);
                            }
                        }
                    });
                    if (i < targets.size() - 1) {
                        try { Thread.sleep(3500); } catch (InterruptedException e) { break; }
                    }
                }
                finishVirusUninstall(targets);
            }
        }).start();
    }

    
    private String appLabelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return cs == null ? pkg : cs.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    
    private void finishVirusUninstall(final List<String> targets) {
        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
        for (String p : targets) {
            if (isAppInstalled(p)) {
                virusHandledPkgs.remove(p);   
                Log.w(TAG, "病毒库：应用仍未卸载，稍后继续尝试: " + p);
            }
        }
        virusAppCache = null;
        final List<String> ok = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        for (String p : targets) {
            if (isAppInstalled(p)) failed.add(p); else ok.add(p);
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                
                virusUninstallRunning = false;
                showVirusDoneOverlay(ok, failed);
            }
        });
    }

    
    private void showVirusDoneOverlay(final List<String> ok, final List<String> failed) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\uD83D\uDEE1");
            icon.setTextSize(44);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            if (!failed.isEmpty()) {
                title.setText("部分卸载失败（" + failed.size() + " 个）");
            } else {
                title.setText("我们发现了威胁病毒，已强制卸载成功");
            }
            title.setTextColor(Color.WHITE);
            title.setTextSize(19);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(8), 0, dp(10));
            card.addView(title);

            if (!ok.isEmpty()) {
                TextView tv = new TextView(this);
                tv.setText("已卸载：" + joinPkgList(ok, 6));
                tv.setTextColor(0xFF7CFF8A);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                card.addView(tv);
            }
            if (!failed.isEmpty()) {
                TextView tv2 = new TextView(this);
                tv2.setText("未能卸载（可手动处理）：" + joinPkgList(failed, 6));
                tv2.setTextColor(0xFFFF9500);
                tv2.setTextSize(13);
                tv2.setGravity(Gravity.CENTER);
                tv2.setPadding(0, dp(6), 0, 0);
                card.addView(tv2);
            }

            final Button okBtn = new Button(this);
            okBtn.setText("好的");
            okBtn.setTextColor(Color.WHITE);
            okBtn.setTextSize(17);
            okBtn.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(16);
            okBtn.setLayoutParams(bp);
            okBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lastShakeRescueTime = System.currentTimeMillis();
                    removeVirusOverlayIfAny();
                }
            });
            card.addView(okBtn);

            addVirusOverlay(wm, card);
            Log.i(TAG, "病毒库：已弹出卸载完成告知弹窗");
        } catch (SecurityException e) {
            Log.w(TAG, "病毒库弹窗权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showVirusDoneOverlay 失败", e);
        }
    }

    
    private void showVirusAskOverlay(final java.util.List<String[]> suspect) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            if (virusPopupShowing) return;
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\u26A0\uFE0F");
            icon.setTextSize(42);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            title.setText("检测到可疑软件");
            title.setTextColor(0xFFFF6B6B);
            title.setTextSize(20);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText("以下软件可能为伪病毒，是否卸载？");
            sub.setTextColor(0xFFFFCC00);
            sub.setTextSize(14);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(8), 0, dp(8));
            card.addView(sub);

            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (String[] s : suspect) {
                TextView item = new TextView(this);
                item.setText("• " + (s[1] == null || s[1].isEmpty() ? s[0] : s[1])
                        + "\n    " + s[0] + "（" + s[2] + "）");
                item.setTextColor(0xFFFFDDDD);
                item.setTextSize(13);
                item.setPadding(0, dp(4), 0, dp(4));
                list.addView(item);
            }
            sv.addView(list);
            
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int listH = Math.min((int) (screenH * 0.40f), dp(30) + suspect.size() * dp(46));
            if (listH < dp(70)) listH = dp(70);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, listH));
            card.addView(sv);

            final Button btnUninstall = new Button(this);
            btnUninstall.setText("卸载");
            btnUninstall.setTextColor(Color.WHITE);
            btnUninstall.setTextSize(17);
            btnUninstall.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp1.topMargin = dp(14);
            btnUninstall.setLayoutParams(bp1);
            card.addView(btnUninstall);

            final Button btnSkip = new Button(this);
            btnSkip.setText("不卸载");
            btnSkip.setTextColor(0xFFDDDDDD);
            btnSkip.setTextSize(15);
            btnSkip.setBackgroundColor(0xFF555555);
            LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp2.topMargin = dp(8);
            btnSkip.setLayoutParams(bp2);
            card.addView(btnSkip);

            
            final Runnable[] timeout = new Runnable[1];

            btnUninstall.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    final List<String> pkgs = new ArrayList<>();
                    for (String[] s : suspect) pkgs.add(s[0]);
                    removeVirusOverlayIfAny();
                    
                    startVirusUninstall(pkgs, false);
                }
            });
            btnSkip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    for (String[] s : suspect) virusHandledPkgs.add(s[0]);  
                    lastShakeRescueTime = System.currentTimeMillis();
                    removeVirusOverlayIfAny();
                }
            });

            addVirusOverlay(wm, card);

            timeout[0] = new Runnable() {
                @Override
                public void run() {
                    if (!virusPopupShowing || virusOverlayView != card) return;
                    Log.w(TAG, "病毒库：可疑应用询问超时未响应，按不卸载处理");
                    for (String[] s : suspect) virusHandledPkgs.add(s[0]);
                    removeVirusOverlayIfAny();
                }
            };
            h.postDelayed(timeout[0], 120000);
            Log.i(TAG, "病毒库：已弹出可疑应用询问弹窗，共 " + suspect.size() + " 个");
        } catch (SecurityException e) {
            Log.w(TAG, "病毒库弹窗权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showVirusAskOverlay 失败", e);
        }
    }

    
    private LinearLayout buildVirusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF15182B);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), 0xFFD32F2F);
        card.setBackground(bg);
        return card;
    }

    
    private void addVirusOverlay(final WindowManager wm, final LinearLayout card) {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.86),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        wm.addView(card, lp);
        virusOverlayWm = wm;
        virusOverlayView = card;
        virusPopupShowing = true;
    }

    
    private void removeVirusOverlayIfAny() {
        virusPopupShowing = false;
        try {
            if (virusOverlayWm != null && virusOverlayView != null) {
                virusOverlayWm.removeView(virusOverlayView);
            }
        } catch (Exception ignored) {}
        virusOverlayView = null;
        virusOverlayWm = null;
    }

    
    private String joinPkgList(java.util.List<String> list, int limit) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), limit);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("、");
            sb.append(list.get(i));
        }
        if (list.size() > limit) sb.append(" 等 ").append(list.size()).append(" 个");
        return sb.toString();
    }

    
    
    
    private java.util.List<String[]> getFinalThirdPartyApps() {
        java.util.List<String[]> highRiskList = new ArrayList<>();
        java.util.List<String[]> normalList = new ArrayList<>();
        PackageManager pm = getPackageManager();

        
        java.util.Set<String> accessibilityPkgs = new java.util.HashSet<>();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.contains("/")) accessibilityPkgs.add(s.substring(0, s.indexOf('/')));
                }
            }
        } catch (Exception ignored) {}

        
        java.util.Set<String> notifListenerPkgs = new java.util.HashSet<>();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.contains("/")) notifListenerPkgs.add(s.substring(0, s.indexOf('/')));
                }
            }
        } catch (Exception ignored) {}

        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);

        
        java.util.List<String> pkgNames = getThirdPartyPackageNamesFromShell();

        
        if (pkgNames.isEmpty()) {
            try {
                java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                if (apps != null) {
                    for (ApplicationInfo ai : apps) {
                        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                        pkgNames.add(ai.packageName);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "getInstalledApplications 兜底失败", e);
            }
        }

        for (String pkg : pkgNames) {
            try {
                if (isProtectedFinalPkg(pkg)) continue; 
                if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) continue;

                
                ApplicationInfo ai = null;
                String label = pkg;
                String[] perms = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                    if (ai != null) label = ai.loadLabel(pm).toString();
                } catch (Exception ignored) {}
                try {
                    perms = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
                } catch (Exception ignored) {}

                boolean highRisk = isFinalHighRiskApp(pkg, ai, perms, accessibilityPkgs,
                        notifListenerPkgs, appOps);
                if (highRisk) {
                    highRiskList.add(new String[]{label, pkg, "1"});
                } else {
                    normalList.add(new String[]{label, pkg, "0"});
                }
            } catch (Exception ignored) {}
        }

        
        java.util.List<String[]> all = new ArrayList<>(highRiskList);
        all.addAll(normalList);
        return all;
    }

    
    private java.util.List<String> getThirdPartyPackageNamesFromShell() {
        java.util.List<String> names = new ArrayList<>();
        try {
            if (!(StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission())) {
                return names; 
            }
            String r = StellarUtils.runCommand("pm list packages -3", 15000);
            if (r != null) {
                String[] lines = r.split("\n");
                for (String line : lines) {
                    String t = line.trim();
                    if (t.startsWith("package:")) {
                        String name = cleanPkgName(t.substring(8));
                        if (!name.isEmpty()) names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pm list packages -3 失败", e);
        }
        return names;
    }

    
    private boolean isFinalHighRiskApp(String pkg, ApplicationInfo app, String[] perms,
                                       java.util.Set<String> accessibilityPkgs,
                                       java.util.Set<String> notifListenerPkgs,
                                       AppOpsManager appOps) {
        try {
            
            if (accessibilityPkgs.contains(pkg)) return true;
            
            if (notifListenerPkgs.contains(pkg)) return true;
            
            if (appOps != null && app != null) {
                try {
                    int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                            app.uid, pkg);
                    if (mode == AppOpsManager.MODE_ALLOWED) return true;
                } catch (Exception ignored) {}
            }
            
            if (perms != null) {
                for (String p : perms) {
                    if (p == null) continue;
                    if (p.equals("android.permission.SYSTEM_ALERT_WINDOW")
                            || p.equals("android.permission.BIND_ACCESSIBILITY_SERVICE")
                            || p.equals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
                            || p.equals("android.permission.BIND_DEVICE_ADMIN")
                            || p.equals("android.permission.RECEIVE_BOOT_COMPLETED")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isFinalHighRiskApp 失败: " + pkg, e);
        }
        return false;
    }

    
    private java.util.List<String> detectFinalHighRiskApps() {
        java.util.List<String> risky = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();

            
            java.util.Set<String> accessibilityPkgs = new java.util.HashSet<>();
            try {
                String enabled = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabled != null) {
                    for (String s : enabled.split(":")) {
                        if (s.contains("/")) accessibilityPkgs.add(s.substring(0, s.indexOf('/')));
                    }
                }
            } catch (Exception ignored) {}

            
            java.util.Set<String> notifListenerPkgs = new java.util.HashSet<>();
            try {
                String enabled = Settings.Secure.getString(getContentResolver(),
                        "enabled_notification_listeners");
                if (enabled != null) {
                    for (String s : enabled.split(":")) {
                        if (s.contains("/")) notifListenerPkgs.add(s.substring(0, s.indexOf('/')));
                    }
                }
            } catch (Exception ignored) {}

            
            java.util.List<String> pkgNames = getThirdPartyPackageNamesFromShell();
            if (pkgNames.isEmpty()) {
                try {
                    java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    if (apps != null) {
                        for (ApplicationInfo ai : apps) {
                            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                            pkgNames.add(ai.packageName);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "detectFinalHighRiskApps getInstalledApplications 兜底失败", e);
                }
            }

            
            java.util.Map<String, String[]> permsMap = new java.util.HashMap<>();
            try {
                java.util.List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
                if (pkgs != null) {
                    for (PackageInfo pi : pkgs) {
                        permsMap.put(pi.packageName, pi.requestedPermissions);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "detectFinalHighRiskApps getInstalledPackages 失败", e);
            }

            
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            java.util.Set<String> overlayPkgs = new java.util.HashSet<>();
            if (appOps != null) {
                for (String pkg : pkgNames) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                                ai.uid, pkg);
                        if (mode == AppOpsManager.MODE_ALLOWED) overlayPkgs.add(pkg);
                    } catch (Exception ignored) {}
                }
            }

            for (String pkg : pkgNames) {
                try {
                    
                    if (isProtectedFinalPkg(pkg)) continue; 
                    if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) continue;

                    boolean hasDanger = accessibilityPkgs.contains(pkg)
                            || notifListenerPkgs.contains(pkg)
                            || overlayPkgs.contains(pkg);

                    
                    if (!hasDanger) {
                        String[] reqPerms = permsMap.get(pkg);
                        if (reqPerms != null) {
                            for (String perm : reqPerms) {
                                if (perm == null) continue;
                                if (perm.contains("BIND_DEVICE_ADMIN")
                                        || perm.contains("SYSTEM_ALERT_WINDOW")
                                        || perm.contains("BIND_ACCESSIBILITY_SERVICE")
                                        || perm.contains("BIND_NOTIFICATION_LISTENER_SERVICE")) {
                                    hasDanger = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (hasDanger) risky.add(pkg);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "detectFinalHighRiskApps 失败", e);
        }
        return risky;
    }

    
    private void executeUninstallOverlay(final String pkg, final LinearLayout root,
                                          final WindowManager wm, final TextView countdownTv,
                                          final Button btnMain, final Button btnClose) {
        
        if (isProtectedFinalPkg(pkg)) {
            Log.w(TAG, "受保护应用，禁止卸载: " + pkg);
            h.post(new Runnable() {
                @Override
                public void run() {
                    countdownTv.setText("⚠ 受保护应用，禁止卸载: " + pkg);
                    countdownTv.setTextColor(0xFFFF4444);
                    btnMain.setText("关闭");
                    btnMain.setEnabled(true);
                    btnClose.setVisibility(View.GONE);
                    btnMain.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                        }
                    });
                }
            });
            return;
        }
        btnMain.setEnabled(false);
        btnClose.setEnabled(false);
        countdownTv.setTextColor(0xFFFFCC00);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] result = new String[1];
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result[0] = StellarUtils.runCommand("pm uninstall " + pkg, 15000);
                } else {
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm uninstall " + pkg}).waitFor();
                        result[0] = "OK(fallback)";
                    } catch (Exception e) {
                        result[0] = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "超级拦截 pm uninstall 结果: " + result[0]);

                final String finalResult = result[0];
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean stillInstalled = isAppInstalled(pkg);
                        if (!stillInstalled || finalResult.contains("Success")) {
                            countdownTv.setText("✅ 已成功卸载");
                            countdownTv.setTextColor(0xFF4CAF50);
                        } else {
                            countdownTv.setText("⚠ 卸载可能失败，请手动处理");
                            countdownTv.setTextColor(0xFFFF4444);
                        }
                        btnMain.setText("关闭");
                        btnMain.setEnabled(true);
                        btnMain.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                lastShakeRescueTime = System.currentTimeMillis();
                                try { wm.removeView(root); } catch (Exception ignored) {}
                            }
                        });
                        btnClose.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    
    private void showFreezeAsk(final String pkg, final TextView reasonTv, final TextView countdownTv,
                               final Button btnMain, final Button btnClose,
                               final LinearLayout root, final WindowManager wm) {
        
        if (isProtectedFinalPkg(pkg)) {
            Log.w(TAG, "受保护应用，跳过冻结询问: " + pkg);
            h.post(new Runnable() {
                @Override
                public void run() {
                    reasonTv.setText("⚠ 受保护应用，禁止冻结: " + pkg);
                    reasonTv.setTextColor(0xFFFF4444);
                    countdownTv.setText("此应用为受保护应用（自己/桌面宠物/游龙工具/白名单）");
                    countdownTv.setTextColor(0xFFFF4444);
                    btnMain.setText("关闭");
                    btnMain.setEnabled(true);
                    btnClose.setVisibility(View.GONE);
                    btnMain.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                        }
                    });
                }
            });
            return;
        }
        
        reasonTv.setText("是否冻结此应用 " + pkg + " ？（冻结后不可用，可在设置中解冻）");
        reasonTv.setTextColor(0xFFFFCC00);
        countdownTv.setText("20 秒后将自动冻结");
        countdownTv.setTextColor(0xFFFFCC00);
        countdownTv.setTextSize(16);
        btnMain.setText("立即冻结");
        btnMain.setEnabled(true);
        btnClose.setText("不冻结，关闭");
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setBackgroundColor(0xFF2E7D32);
        btnClose.setEnabled(true);
        btnClose.setVisibility(View.VISIBLE);

        
        final java.util.concurrent.atomic.AtomicInteger cd3 = new java.util.concurrent.atomic.AtomicInteger(20);
        final Runnable[] cd3Task = new Runnable[1];
        cd3Task[0] = new Runnable() {
            @Override
            public void run() {
                int sec = cd3.decrementAndGet();
                if (sec > 0) {
                    countdownTv.setText(sec + " 秒后将自动冻结");
                    if (sec <= 3) countdownTv.setTextColor(0xFFFF4444);
                    h.postDelayed(this, 1000);
                } else {
                    countdownTv.setText("正在冻结...");
                    executeDisableOverlay(pkg, countdownTv, btnMain, btnClose, root, wm);
                }
            }
        };
        h.postDelayed(cd3Task[0], 1000);

        
        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cd3Task[0] != null) h.removeCallbacks(cd3Task[0]);
                countdownTv.setText("正在冻结...");
                executeDisableOverlay(pkg, countdownTv, btnMain, btnClose, root, wm);
            }
        });

        
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cd3Task[0] != null) h.removeCallbacks(cd3Task[0]);
                lastShakeRescueTime = System.currentTimeMillis();
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
    }

    
    private void executeDisableOverlay(final String pkg, final TextView countdownTv,
                                       final Button btnMain, final Button btnClose,
                                       final LinearLayout root, final WindowManager wm) {
        
        if (isProtectedFinalPkg(pkg)) {
            Log.w(TAG, "受保护应用，禁止冻结: " + pkg);
            h.post(new Runnable() {
                @Override
                public void run() {
                    countdownTv.setText("⚠ 受保护应用，禁止冻结: " + pkg);
                    countdownTv.setTextColor(0xFFFF4444);
                    btnMain.setText("关闭");
                    btnMain.setEnabled(true);
                    btnClose.setVisibility(View.GONE);
                    btnMain.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                        }
                    });
                }
            });
            return;
        }
        btnMain.setEnabled(false);
        btnClose.setEnabled(false);
        countdownTv.setTextColor(0xFFFFCC00);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] result = new String[1];
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result[0] = StellarUtils.runCommand("pm disable-user --user 0 " + pkg, 15000);
                } else {
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm disable-user --user 0 " + pkg}).waitFor();
                        result[0] = "OK(fallback)";
                    } catch (Exception e) {
                        result[0] = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "冻结 pm disable-user 结果: " + result[0]);

                final String finalResult = result[0];
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean disabled = isAppDisabled(pkg);
                        if (disabled || finalResult.contains("disabled") || finalResult.contains("Success")) {
                            countdownTv.setText("✅ 已冻结 " + pkg);
                            countdownTv.setTextColor(0xFF4CAF50);
                        } else {
                            countdownTv.setText("⚠ 冻结可能失败，请手动处理");
                            countdownTv.setTextColor(0xFFFF4444);
                        }
                        btnMain.setText("关闭");
                        btnMain.setEnabled(true);
                        btnMain.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                lastShakeRescueTime = System.currentTimeMillis();
                                try { wm.removeView(root); } catch (Exception ignored) {}
                            }
                        });
                        btnClose.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    
    private boolean isAppDisabled(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return !ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    
    private void jumpToAppSettings(final String pkgF, boolean pkgUnknown) {
        int bgFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | 0x00000004;

        if (pkgUnknown) {
            
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                s.addFlags(bgFlags);
                startActivity(s);
                Log.i(TAG, "未知包名，打开全部应用列表");
            } catch (Exception e) {
                Log.e(TAG, "打开全部应用列表失败", e);
            }
        } else {
            
            boolean opened = false;
            try {
                Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                s.setData(Uri.parse("package:" + pkgF));
                s.addFlags(bgFlags);
                startActivity(s);
                Log.i(TAG, "已打开应用设置页: " + pkgF);
                opened = true;
            } catch (Exception e) {
                Log.e(TAG, "打开应用设置页失败: " + pkgF, e);
            }
            
            if (!opened) {
                try {
                    Intent s = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                    s.addFlags(bgFlags);
                    startActivity(s);
                    Log.i(TAG, "打开全部应用列表(备胎)");
                } catch (Exception e2) {
                    Log.e(TAG, "全部应用列表也失败", e2);
                }
            }

            
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        
                        getPackageManager().getPackageInfo(pkgF, 0);
                        
                        Log.w(TAG, "5秒后应用仍未卸载，调用原生卸载: " + pkgF);
                        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                        uninstall.setData(Uri.parse("package:" + pkgF));
                        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(uninstall);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.i(TAG, "应用已卸载，无需原生卸载: " + pkgF);
                    } catch (Exception e) {
                        Log.e(TAG, "原生卸载失败: " + pkgF, e);
                        
                        try {
                            Intent del = new Intent(Intent.ACTION_DELETE);
                            del.setData(Uri.parse("package:" + pkgF));
                            del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(del);
                        } catch (Exception e2) {
                            Log.e(TAG, "ACTION_DELETE 也失败", e2);
                        }
                    }
                }
            }, 5000);
        }
    }

    
    
    
    private String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread t1 = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String l;
                    while ((l = br.readLine()) != null) out.append(l).append("\n");
                } catch (Exception ignored) {}
            });
            Thread t2 = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    String l;
                    while ((l = br.readLine()) != null) err.append(l).append("\n");
                } catch (Exception ignored) {}
            });
            t1.start();
            t2.start();
            boolean done;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    done = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    p.waitFor();
                    done = true;
                }
            } catch (InterruptedException e) {
                done = false;
            }
            if (!done) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    p.destroyForcibly();
                } else {
                    p.destroy();
                }
                try { t1.join(500); } catch (Exception ignored) {}
                try { t2.join(500); } catch (Exception ignored) {}
                return "ERROR:执行超时(30s): " + cmd;
            }
            try { t1.join(1000); } catch (Exception ignored) {}
            try { t2.join(1000); } catch (Exception ignored) {}
            String o = out.toString().trim();
            String e = err.toString().trim();
            if (!o.isEmpty()) return o;
            if (!e.isEmpty()) return "ERROR:" + e;
            return "OK";
        } catch (Exception e) {
            Log.e(TAG, "execShell 失败: " + cmd, e);
            return "ERROR:" + e.getMessage();
        }
    }

    
    private void warnPopup(String pkg, int warnCount, String warnReason, boolean isVolumeRescue) {
        
        
        
        
        
        
        if (pkg != null && WhitelistActivity.isWhitelisted(this, pkg)) {
            Log.i(TAG, "白名单应用，跳过拦截弹窗（不弹窗/不写拦截历史）: " + pkg);
            CrashLogger.event("[拦截] 白名单应用，已跳过弹窗: " + pkg);
            return;
        }
        lastWarnPkg = pkg;
        lastWarnTime = System.currentTimeMillis();

        
        h.post(() -> Toast.makeText(ProtectService.this, "游龙护盾正在拦截中", Toast.LENGTH_SHORT).show());

        
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String shieldMode = prefs.getString("shield_mode", "basic");
        final boolean isSuper = "super".equals(shieldMode);
        final boolean isExtreme = "extreme".equals(shieldMode);
        final boolean isFinal = "final".equals(shieldMode);
        final boolean isDaily = "daily".equals(shieldMode);
        final boolean isShizukuMode = isSuper || isExtreme || isFinal || isDaily;

        
        
        showWarnOverlay(pkg, warnReason, isVolumeRescue, shieldMode);

        
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    prefs.getString("block_history", "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("description", "拦截: " + pkg);
            o.put("packageName", pkg);
            o.put("time", time);
            o.put("type", warnReason.contains("黑名单") ? "blacklist_block" : "manual_block");
            arr.put(o);
            if (arr.length() > 50) {
                org.json.JSONArray n = new org.json.JSONArray();
                for (int i = arr.length() - 50; i < arr.length(); i++) n.put(arr.get(i));
                arr = n;
            }
            prefs.edit().putString("block_history", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "记录拦截历史失败", e);
        }

        
        if (isShizukuMode) {
            Log.i(TAG, "超级/极强拦截模式，覆盖层已处理，跳过ShieldWarnActivity");
            return;
        }

        try {
            Intent wi = new Intent(this, ShieldWarnActivity.class);
            wi.putExtra("suspect_package", pkg);
            wi.putExtra("reason", warnReason);
            wi.putExtra("warn_count", warnCount);
            wi.putExtra("is_volume_rescue", isVolumeRescue);
            
            wi.putExtra("shield_mode", shieldMode);
            
            wi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | 0x00000004);

            PendingIntent fullScreenPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                fullScreenPi = PendingIntent.getActivity(this,
                        (int) System.currentTimeMillis() % 100000,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                fullScreenPi = PendingIntent.getActivity(this,
                        (int) System.currentTimeMillis() % 100000,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            }

            
            PendingIntent openShieldPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                openShieldPi = PendingIntent.getActivity(this,
                        (int) (System.currentTimeMillis() % 100000) + 1,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                openShieldPi = PendingIntent.getActivity(this,
                        (int) (System.currentTimeMillis() % 100000) + 1,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            }

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            
            Intent uninstallIntent = new Intent(this, NotifyUninstallReceiver.class);
            uninstallIntent.putExtra("target_pkg", pkg);
            PendingIntent uninstallPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                uninstallPi = PendingIntent.getBroadcast(this,
                        (int) (System.currentTimeMillis() % 100000) + 1000,
                        uninstallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                uninstallPi = PendingIntent.getBroadcast(this,
                        (int) (System.currentTimeMillis() % 100000) + 1000,
                        uninstallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            }

            final boolean isVolumeRescueLocal = isVolumeRescue;

            Notification nf;
            if (isVolumeRescueLocal) {
                
                nf = new NotificationCompat.Builder(this, WARN_CHANNEL_ID)
                        .setContentTitle("⚠️ 紧急救援 — 请尽快卸载")
                        .setContentText("点击「卸载软件」按钮 → 系统卸载 → 自动跳转设置页")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(fullScreenPi, true)
                        .setContentIntent(openShieldPi)
                        .addAction(android.R.drawable.ic_menu_delete, "卸载软件", uninstallPi)
                        .addAction(android.R.drawable.ic_menu_info_details, "打开拦截面板", openShieldPi)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .build();
            } else {
                nf = new NotificationCompat.Builder(this, WARN_CHANNEL_ID)
                        .setContentTitle("检测到需拦截应用")
                        .setContentText(pkg + " — " + warnReason)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(fullScreenPi, true)
                        .setContentIntent(openShieldPi)
                        .addAction(android.R.drawable.ic_menu_delete, "卸载软件", uninstallPi)
                        .addAction(android.R.drawable.ic_menu_info_details, "打开拦截面板", openShieldPi)
                        .setAutoCancel(true)
                        .setOngoing(false)
                        .setTimeoutAfter(15000)
                        .build();
            }

            final int notifId = 2000 + (int)(System.currentTimeMillis() % 1000);
            if (nm != null) {
                nm.notify(notifId, nf);
            }

            
            
            
            if (isVolumeRescueLocal) {
                final NotificationManager nmFinal = nm;
                final int finalNotifId = notifId;
                rescueNotifId = finalNotifId;
                rescueNotifLoop = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            
                            SharedPreferences sp2 = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                            boolean pOn2 = sp2.getBoolean("protect_on", false);
                            boolean sOn2 = sp2.getBoolean("shake_trigger_on", false);
                            boolean vOn2 = sp2.getBoolean("volume_trigger_on", false);
                            if (!pOn2 && !sOn2 && !vOn2) {
                                Log.i(TAG, "护盾已关闭，停止紧急救援通知循环: " + pkg);
                                if (nmFinal != null) nmFinal.cancel(finalNotifId);
                                return; 
                            }
                            
                            getPackageManager().getPackageInfo(pkg, 0);
                            
                            if (nmFinal != null) {
                                Notification repeat = new NotificationCompat.Builder(
                                        ProtectService.this, WARN_CHANNEL_ID)
                                        .setContentTitle("⚠️ 紧急救援 — 请尽快卸载")
                                        .setContentText("应用 " + pkg + " 仍未卸载，点击下方按钮操作")
                                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                                        .setFullScreenIntent(fullScreenPi, true)
                                        .setContentIntent(openShieldPi)
                                        .addAction(android.R.drawable.ic_menu_delete,
                                                "卸载软件", uninstallPi)
                                        .addAction(android.R.drawable.ic_menu_info_details,
                                                "打开拦截面板", openShieldPi)
                                        .setAutoCancel(false)
                                        .setOngoing(true)
                                        .build();
                                nmFinal.notify(finalNotifId, repeat);
                            }
                            h.postDelayed(this, 5000);
                        } catch (PackageManager.NameNotFoundException e) {
                            
                            if (nmFinal != null) {
                                nmFinal.cancel(finalNotifId);
                            }
                            Log.i(TAG, "救援完成，应用已卸载: " + pkg);
                        } catch (Exception e) {
                            Log.e(TAG, "救援通知循环异常", e);
                        }
                    }
                };
                h.postDelayed(rescueNotifLoop, 5000);
            }

            
            try {
                startActivity(wi);
                Log.i(TAG, "直接启动ShieldWarnActivity成功: " + pkg);
            } catch (Exception e) {
                Log.w(TAG, "直接启动ShieldWarnActivity失败(预期内，后台限制): " + pkg, e);
            }

            
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(wi);
                        Log.i(TAG, "延迟启动ShieldWarnActivity成功: " + pkg);
                    } catch (Exception e) {
                        Log.w(TAG, "延迟启动ShieldWarnActivity失败(预期内，后台限制): " + pkg, e);
                    }
                }
            }, 500);
        } catch (Exception e) {
            Log.e(TAG, "warnPopup ShieldWarnActivity 启动阶段异常", e);
        }
    }

    
    
    private boolean isDailyMode() {
        return "daily".equals(getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getString("shield_mode", "basic"));
    }

    private boolean isSys(String pkg) {
        if (pkg == null) return true;
        if (pkg.equals(getPackageName())) return true;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
            if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
            if (ai.sourceDir != null && ai.sourceDir.startsWith("/system/")) return true;
        } catch (Exception ignored) { return true; }
        return false;
    }

    private boolean isWhitelisted(String pkg) {
        return pkg != null && whitelistCache.contains(pkg);
    }

    private boolean isAppInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    
    private boolean isAppRunning(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.pkgList != null) {
                        for (String s : p.pkgList) {
                            if (pkg.equals(s)) return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    
    private void runTickHeavyWorkAsync() {
        if (!tickHeavyRunning.compareAndSet(false, true)) return;
        tickHeavyExecutor.execute(() -> {
            final long t0 = System.currentTimeMillis();
            try {
                
                loadWhitelist();
                loadBlacklist();

                
                scanBlacklistedApps();

                
                if (anyTriggerOn()) {
                    try {
                        virusScanOnce();
                    } catch (Exception e) {
                        Log.e(TAG, "病毒库检测异常", e);
                    }
                }

                
                try {
                    monitorPermissionDegrade();
                } catch (Exception e) {
                    Log.e(TAG, "权限降级巡检异常", e);
                }

                
                try {
                    ensureVolumeTriggerListener();
                } catch (Exception e) {
                    Log.e(TAG, "音量监听自愈失败", e);
                }

                
                try {
                    File gd = new File(getFilesDir(), "sentinel_guard_dead");
                    if (gd.exists() && isServiceRunning(ForegroundService.class)) {
                        gd.delete();
                    }
                } catch (Exception ignored) {}
            } catch (Throwable t) {
                Log.e(TAG, "巡检后台任务异常", t);
                CrashLogger.event("[巡检] 本轮后台任务异常：" + t);
            } finally {
                
                
                long cost = System.currentTimeMillis() - t0;
                if (cost > 800) {
                    CrashLogger.event("[巡检] 本轮耗时偏长：" + cost + "ms");
                }
                tickHeavyRunning.set(false);
            }
        });
    }

    
    private final java.util.concurrent.atomic.AtomicBoolean tickHeavyRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    
    private final java.util.concurrent.ExecutorService tickHeavyExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "protect-tick");
                t.setDaemon(true);
                return t;
            });

    
    private void showA11yLostToastOnce(boolean hasPrivilege) {
        long now = System.currentTimeMillis();
        if (now - lastA11yToastTime < A11Y_TOAST_INTERVAL_MS) return;
        lastA11yToastTime = now;
        try {
            Toast.makeText(ProtectService.this, "检测到无障碍权限已丢失", Toast.LENGTH_LONG).show();
            Toast.makeText(ProtectService.this,
                    hasPrivilege
                            ? "已连接特权服务，守护功能仍可正常使用，无需担心"
                            : "音量键三连击/摇一摇仍可正常使用，无需担心",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    
    private static final long A11Y_TOAST_INTERVAL_MS = 10 * 60 * 1000L;
    
    private volatile long lastA11yToastTime = 0L;

    
    
    private Notification buildMinimalNotify() {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("游龙安全护盾")
                .setContentText("正在启动守护…")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0x5A2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setContentIntent(pi);
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "游龙安全护盾", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("游龙安全护盾正在后台守护中");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);

            NotificationChannel warnCh = new NotificationChannel(
                    WARN_CHANNEL_ID, "安全警告", NotificationManager.IMPORTANCE_HIGH);
            warnCh.setDescription("黑名单应用拦截弹窗");
            warnCh.setBypassDnd(true);
            nm.createNotificationChannel(warnCh);
        }
    }

    
    
    private Notification buildNotify(String extra) {
        long elapsed = startTime > 0 ? System.currentTimeMillis() - startTime : 0L;
        String duration = formatDuration(elapsed);
        String modeName = currentModeName();
        int blocked = getBlockCount();

        String content = "已开启 " + duration + " · " + modeName;
        StringBuilder big = new StringBuilder();
        big.append("已开启守护：").append(duration)
                .append("\n拦截模式：").append(modeName)
                .append("\n拦截次数：").append(blocked).append(" 次");
        if (extra != null && !extra.isEmpty()) big.append("\n").append(extra);

        PendingIntent pi = null;
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 0x5A2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ignored) {}

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("游龙安全护盾 · 守护中")
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(big.toString()))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setOnlyAlertOnce(true)   
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    
    private String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / 60000L;
        if (totalMin <= 0) return "不到 1 分钟";
        long days = totalMin / 1440L;
        long hours = (totalMin % 1440L) / 60L;
        long mins = totalMin % 60L;
        if (days > 0) return days + "天" + hours + "小时" + mins + "分钟";
        if (hours > 0) return hours + "小时" + mins + "分钟";
        return mins + "分钟";
    }

    
    private String currentModeName() {
        String m = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getString("shield_mode", "basic");
        if ("super".equals(m)) return "超级拦截";
        if ("extreme".equals(m)) return "极强拦截";
        if ("final".equals(m)) return "终结模式";
        if ("daily".equals(m)) return "日常模式";
        return "基础拦截";
    }

    
    private int getBlockCount() {
        try {
            String raw = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getString("block_history", "[]");
            return new org.json.JSONArray(raw).length();
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateNotify(String txt) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotify(txt));
    }

    
    private boolean anyTriggerOn() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        return sp.getBoolean("protect_on", false)
                || sp.getBoolean("shake_trigger_on", false)
                || sp.getBoolean("volume_trigger_on", false);
    }

    
    private boolean isServiceRunning(Class<?> cls) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
                if (cls.getName().equals(s.service.getClassName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        
        
        
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean pOn = sp.getBoolean("protect_on", false);
        boolean sOn = sp.getBoolean("shake_trigger_on", false);
        boolean vOn = sp.getBoolean("volume_trigger_on", false);
        if (!pOn && !sOn && !vOn) {
            Log.i(TAG, "护盾已关闭（所有开关全关），兜底停止服务并移除通知");
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        
        if (intent != null && "com.youlong.hd.SHAKE_TRIGGER".equals(intent.getAction())) {
            if (sp.getBoolean("shake_trigger_on", false)) {
                h.postDelayed(() -> {
                    String fg = AdSkipService.getForegroundPkg();
                    if (fg == null) fg = getFgViaUsageStats();
                    if (fg == null) fg = getFgViaStellar();
                    if (fg == null) fg = getFgSimple();

                    
                    
                    
                    
                    if (fg != null && !fg.isEmpty()
                            && WhitelistActivity.isWhitelisted(ProtectService.this, fg)) {
                        Log.i(TAG, "摇动触发：前台在白名单中，跳过拦截（不弹窗/不写拦截历史）: " + fg);
                        CrashLogger.event("[摇动] 前台是白名单应用，已跳过拦截: " + fg);
                        Toast.makeText(ProtectService.this,
                                "前台应用在白名单中，已跳过拦截（如需拦截请先在白名单中移除）",
                                Toast.LENGTH_LONG).show();
                        lastShakeRescueTime = System.currentTimeMillis();
                        return;
                    }

                    
                    
                    if (isDailyMode()) {
                        String safePkg = (fg == null || fg.isEmpty())
                                ? "(无法识别的界面)" : fg;
                        Log.i(TAG, "摇动触发（日常模式）：不受前台限制，前台=" + safePkg);
                        warnPopup(safePkg, 1, "检测到猛烈摇动", true);
                        return;
                    }

                    if (fg != null && !fg.equals(getPackageName())) {
                        Log.i(TAG, "摇动触发：前台=" + fg);
                        warnPopup(fg, 1, "检测到猛烈摇动", true);
                    }
                }, 200);
            }
        }
        return START_STICKY;
    }

    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "检测到应用被从最近任务划掉");
        try {
            MainActivity.restartAfterTaskRemoved(this);
        } catch (Exception e) {
            Log.e(TAG, "防终结自启失败", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        
        cancelKeepAliveAlarm();
        
        try { MainActivity.cancelRestartAlarm(this); } catch (Exception ignored) {}
        if (notifyUpdater != null) h.removeCallbacks(notifyUpdater);
        if (tick != null) h.removeCallbacks(tick);
        if (volPollRunnable != null) h.removeCallbacks(volPollRunnable);
        
        virusUninstallRunning = false;
        virusHandledPkgs.clear();
        removeVirusOverlayIfAny();
        
        if (rescueNotifLoop != null) h.removeCallbacks(rescueNotifLoop);
        if (rescueNotifId >= 0) {
            try {
                NotificationManager nmD = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nmD != null) nmD.cancel(rescueNotifId);
            } catch (Exception ignored) {}
        }
        try { unregisterReceiver(volRcvr); } catch (Exception ignored) {}
        try { unregisterReceiver(pkgRcvr); } catch (Exception ignored) {}
        AdSkipService.setVolumeChangeListener(null);
        
        
        AdSkipService.setForegroundChangeListener(null);
        
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        
        try { Stellar.INSTANCE.removeBinderDeadListener(shizukuDeadListener); } catch (Exception ignored) {}
        stopForeground(true);

        
        SharedPreferences spE = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean pE = spE.getBoolean("protect_on", false);
        boolean sE = spE.getBoolean("shake_trigger_on", false);
        boolean vE = spE.getBoolean("volume_trigger_on", false);
        if (!pE && !sE && !vE) {
            try {
                stopService(new Intent(this, ForegroundService.class));
            } catch (Exception ignored) {}
        }

        
        
        SharedPreferences spD = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean pD = spD.getBoolean("protect_on", false);
        boolean sD = spD.getBoolean("shake_trigger_on", false);
        boolean vD = spD.getBoolean("volume_trigger_on", false);
        if (pD || sD || vD) {
            Log.w(TAG, "服务被终结/回收，2秒后自动重启（防终结自愈）");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    ContextCompat.startForegroundService(ProtectService.this,
                            new Intent(ProtectService.this, ProtectService.class));
                } catch (Exception ignored) {}
            }, 2000);
        }
    }

    
    private void setupShakeSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (sensorManager != null) {
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                Log.i(TAG, "摇动传感器已注册 (SENSOR_DELAY_GAME)");
            } else {
                Log.w(TAG, "设备无加速度传感器");
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        SharedPreferences spShake = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        if (!spShake.getBoolean("shake_trigger_on", false)) return;

        
        int shakeLevel = readShakeLevel(spShake);
        float shakeThreshold = SHAKE_THRESHOLDS[shakeLevel - 1];
        int shakeHitsNeeded = SHAKE_HITS_TABLE[shakeLevel - 1];

        float x = event.values[0], y = event.values[1], z = event.values[2];
        float dx = Math.abs(x - lastAccelX);
        float dy = Math.abs(y - lastAccelY);
        float dz = Math.abs(z - lastAccelZ);
        lastAccelX = x; lastAccelY = y; lastAccelZ = z;

        float mag = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (mag < shakeThreshold) return;

        long now = System.currentTimeMillis();
        if (now - lastShakeTs < SHAKE_WINDOW) {
            shakeHitCount++;
        } else {
            shakeHitCount = 1;
        }
        lastShakeTs = now;

        if (shakeHitCount >= shakeHitsNeeded) {
            shakeHitCount = 0;
            
            if (now - lastShakeRescueTime < SHAKE_RESCUE_COOLDOWN_MS) {
                Log.v(TAG, "摇动触发冷却中，跳过 (" + (now - lastShakeRescueTime) + "ms)");
                return;
            }
            lastShakeRescueTime = now;
            Log.w(TAG, "检测到猛烈摇动！触发逃生弹窗（力度档位 " + shakeLevel
                    + "，阈值 " + shakeThreshold + "，需 " + shakeHitsNeeded + " 次尖峰）");
            
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(100);
                }
            }
            
            h.post(this::triggerVolumeRescue);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    
    private void setupKeepAliveAlarm() {
        Intent keepIntent = new Intent(this, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(this, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            try {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 120_000, 120_000, keepPi);
                Log.i(TAG, "保活闹钟已设置（每2分钟）");
            } catch (Exception e) {
                Log.e(TAG, "设置保活闹钟失败", e);
            }
        }
    }

    private void cancelKeepAliveAlarm() {
        Intent keepIntent = new Intent(this, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(this, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(keepPi);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
