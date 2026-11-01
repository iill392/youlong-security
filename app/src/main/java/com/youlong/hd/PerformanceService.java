package com.youlong.hd;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 性能模式前台服务 - 鸿蒙6全面适配版
 */
public class PerformanceService extends Service {

    private static final String TAG = "PerfService";
    private static final String CHANNEL_ID = "perf_mode_channel";
    private static final int NOTIFICATION_ID = 3001;
    public static final String ACTION_STOP = "com.youlong.hd.action.STOP_PERF";
    public static final String ACTION_CLEAN = "com.youlong.hd.action.CLEAN";

    private static volatile boolean running = false;
    private static volatile int originalBrightness = -1;
    private static volatile int originalBrightnessMode = -1;

    private PowerManager.WakeLock wakeLock;
    private PowerManager pm;
    private WindowManager windowManager;
    private View rateOverlay;
    private View brightnessOverlay;
    private Handler handler;
    private Runnable periodicCleanTask;

    private static final long CLEAN_INTERVAL_MS = 3 * 60 * 1000;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000; // 每10分钟续一次

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        acquireWakeLock();

        saveOriginalBrightness();
        applyAllOptimizations();
        // 定时清理第一次在 3 分钟后触发，避免和 onCreate 重复
        startPeriodicClean();

        running = true;
        Log.i(TAG, "PerformanceService started");
    }

    // ============================================================
    // FIX Bug 1: 每 10 分钟续一次 WakeLock 而不是一次性 30 分钟
    // ============================================================

    private void acquireWakeLock() {
        if (pm == null) return;
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "YouLongTool:PerfWakeLock");
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            Log.i(TAG, "WakeLock acquired/renewed for " + (WAKE_LOCK_TIMEOUT_MS / 60000) + " min");
        } catch (Exception e) {
            Log.e(TAG, "WakeLock acquire failed", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_STOP.equals(intent.getAction())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_CLEAN.equals(intent.getAction())) {
                performClean();
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopPeriodicClean();
        removeRateOverlay();
        removeBrightnessOverlay();
        restoreSystemBrightness();
        releaseWakeLock();
        stopForeground(true);
        super.onDestroy();
        Log.i(TAG, "PerformanceService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ============================================================
    // 保存/恢复原始亮度
    // ============================================================

    private void saveOriginalBrightness() {
        try {
            originalBrightness = Settings.System.getInt(
                    getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            originalBrightnessMode = Settings.System.getInt(
                    getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save original brightness", e);
        }
    }

    private void applyAllOptimizations() {
        forceMaxRefreshRate();
        setSystemMaxBrightness();
        addBrightnessOverlay();
        // 首次清理在 3 分钟后由 startPeriodicClean 触发，这里不做清理
    }

    private void removeRateOverlay() {
        if (windowManager != null && rateOverlay != null) {
            try { windowManager.removeView(rateOverlay); } catch (Exception ignored) {}
        }
        rateOverlay = null;
    }

    private void removeBrightnessOverlay() {
        if (windowManager != null && brightnessOverlay != null) {
            try { windowManager.removeView(brightnessOverlay); } catch (Exception ignored) {}
        }
        brightnessOverlay = null;
    }

    // ============================================================
    // 1. 强制最高刷新率
    // ============================================================

    private void forceMaxRefreshRate() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return;
            Display.Mode[] modes = display.getSupportedModes();
            if (modes == null || modes.length == 0) return;

            Display.Mode currentMode = display.getMode();
            Display.Mode bestMode = currentMode;
            float maxRate = currentMode.getRefreshRate();
            for (Display.Mode mode : modes) {
                float rate = mode.getRefreshRate();
                if (rate > maxRate && rate >= 90f) {
                    maxRate = rate;
                    bestMode = mode;
                }
            }
            createPersistentRateOverlay(bestMode.getModeId());
            Log.i(TAG, "Refresh rate set to " + bestMode.getRefreshRate() + "Hz (mode=" + bestMode.getModeId() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Force refresh rate failed", e);
        }
    }

    private void createPersistentRateOverlay(int modeId) {
        if (windowManager == null) return;
        try {
            removeRateOverlay();
            rateOverlay = new View(this);
            rateOverlay.setBackgroundColor(0x00000000);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSPARENT);
            params.preferredDisplayModeId = modeId;
            params.alpha = 0.0f;
            windowManager.addView(rateOverlay, params);
        } catch (Exception e) {
            Log.e(TAG, "Create persistent rate overlay failed", e);
        }
    }

    // ============================================================
    // 2. 系统级最高亮度
    // ============================================================

    private void setSystemMaxBrightness() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.System.canWrite(this)) return;
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 255);
        } catch (Exception e) {
            Log.e(TAG, "Set system brightness failed", e);
        }
    }

    private void restoreSystemBrightness() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.System.canWrite(this)) return;
            if (originalBrightnessMode >= 0) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE, originalBrightnessMode);
            }
            if (originalBrightness >= 0) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, originalBrightness);
            }
        } catch (Exception e) {
            Log.e(TAG, "Restore system brightness failed", e);
        }
    }

    // ============================================================
    // 3. 亮度悬浮窗
    // ============================================================

    private void addBrightnessOverlay() {
        if (windowManager == null) return;
        try {
            removeBrightnessOverlay();
            brightnessOverlay = new View(this);
            brightnessOverlay.setBackgroundColor(0x00000000);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSPARENT);
            params.screenBrightness = 1.0f;
            params.alpha = 0.0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            windowManager.addView(brightnessOverlay, params);
        } catch (Exception e) {
            Log.e(TAG, "Brightness overlay failed", e);
        }
    }

    // ============================================================
    // 4. 真正的清理后台（每 3 分钟 + 异步执行 → FIX Bug 2）
    // ============================================================

    private void performClean() {
        // 异步执行，避免阻塞 UI 线程 (FIX Bug 2)
        AsyncTask.execute(() -> {
            cleanBackgroundProcesses();
            releaseMemoryDeep();
            // 续 WakeLock (FIX Bug 1)
            acquireWakeLock();
        });
    }

    private void cleanBackgroundProcesses() {
        int killed = 0;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                String myPkg = getPackageName();
                for (ActivityManager.RunningAppProcessInfo proc : processes) {
                    if (isSystemOrForeground(proc, myPkg)) continue;
                    String pkg = proc.processName.split(":")[0];
                    am.killBackgroundProcesses(pkg);
                    killed++;
                    tryForceStopPackage(am, pkg);
                }
            }
            Log.i(TAG, "Cleaned " + killed + " background processes");
        } catch (Exception e) {
            Log.e(TAG, "Clean background failed", e);
        }
    }

    private boolean isSystemOrForeground(ActivityManager.RunningAppProcessInfo proc, String myPkg) {
        return proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                || proc.processName.startsWith("com.android")
                || proc.processName.startsWith("android")
                || proc.processName.startsWith("system")
                || proc.processName.contains("huawei")
                || proc.processName.contains("hmos")
                || proc.processName.equals(myPkg);
    }

    private void tryForceStopPackage(ActivityManager am, String pkg) {
        try {
            Method forceStop = am.getClass().getMethod("forceStopPackage", String.class);
            forceStop.invoke(am, pkg);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // FIX Bug 4: 移除 getAppTasks() 方法（已废弃，对第三方应用无效）
    // ============================================================
    // cleanRecentTasks() 已彻底移除

    // ============================================================
    // 5. 释放内存
    // ============================================================

    private void releaseMemoryDeep() {
        try {
            System.gc();
            Runtime.getRuntime().gc();

            try {
                Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
                Method getRuntime = vmRuntime.getMethod("getRuntime");
                Object runtime = getRuntime.invoke(null);
                Method trimMemory = vmRuntime.getMethod("trimMemory", int.class);
                trimMemory.invoke(runtime, 80);
            } catch (Exception e) {
                Log.e(TAG, "VMRuntime trimMemory failed", e);
            }

            System.runFinalization();
            clearAppCache();

            // 读取内存信息
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                Log.i(TAG, "Memory - avail: " + (mi.availMem / 1024 / 1024) + "MB / " + (mi.totalMem / 1024 / 1024) + "MB");
            }
        } catch (Exception e) {
            Log.e(TAG, "Release memory failed", e);
        }
    }

    private void clearAppCache() {
        try {
            deleteDir(getCacheDir());
            if (getExternalCacheDir() != null) deleteDir(getExternalCacheDir());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) deleteDir(getCodeCacheDir());
        } catch (Exception e) {
            Log.e(TAG, "Clear cache failed", e);
        }
    }

    private boolean deleteDir(java.io.File dir) {
        if (dir == null || !dir.exists()) return true;
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    deleteDir(new java.io.File(dir, child));
                }
            }
        }
        return dir.delete();
    }

    // ============================================================
    // 6. 定时清理（FIX Bug 3: 不再在 onCreate 时立刻清理）
    // ============================================================

    private void startPeriodicClean() {
        periodicCleanTask = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                Log.i(TAG, "Periodic clean triggered");
                performClean();
                handler.postDelayed(this, CLEAN_INTERVAL_MS);
            }
        };
        handler.postDelayed(periodicCleanTask, CLEAN_INTERVAL_MS);
        Log.i(TAG, "Periodic clean scheduled every " + (CLEAN_INTERVAL_MS / 60000) + " min");
    }

    private void stopPeriodicClean() {
        if (periodicCleanTask != null) {
            handler.removeCallbacks(periodicCleanTask);
            periodicCleanTask = null;
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    public static boolean isRunning() { return running; }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, PerformanceService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public static void triggerClean(Context ctx) {
        Intent i = new Intent(ctx, PerformanceService.class);
        i.setAction(ACTION_CLEAN);
        ctx.startService(i);
    }

    public static int getCurrentRefreshRate(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) return Math.round(display.getRefreshRate());
            }
        } catch (Exception e) { Log.e(TAG, "Get refresh rate failed", e); }
        return 60;
    }

    public static int getCurrentBrightness(Context ctx) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Exception e) { return 128; }
    }

    public static String getAvailableMemory(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long availMB = mi.availMem / (1024 * 1024);
                long totalMB = mi.totalMem / (1024 * 1024);
                return availMB + "MB / " + totalMB + "MB";
            }
        } catch (Exception e) { Log.e(TAG, "Get memory failed", e); }
        return "--";
    }

    public static int getBatteryTemp(Context ctx) {
        // FIX Bug 5: try-with-resources
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/sys/class/power_supply/battery/temp"))) {
            String line = reader.readLine();
            if (line != null) return Integer.parseInt(line.trim()) / 10;
        } catch (Exception e) { Log.e(TAG, "Get battery temp failed", e); }
        return -1;
    }

    // ============================================================
    // Notification
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Performance Mode", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Performance mode: 120Hz + Max Brightness + Auto Clean every 3 min");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stopI = new Intent(this, PerformanceService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopI, piFlags);

        Intent cleanI = new Intent(this, PerformanceService.class);
        cleanI.setAction(ACTION_CLEAN);
        PendingIntent cleanPi = PendingIntent.getService(this, 3, cleanI, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Performance Mode ON")
                .setContentText("120Hz | Max Brightness | Auto-clean every 3 min")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_delete, "Clean Now", cleanPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
                .build();
    }
}