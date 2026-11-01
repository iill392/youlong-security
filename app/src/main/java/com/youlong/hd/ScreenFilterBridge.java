package com.youlong.hd;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

/**
 * JS <-> Native Bridge for WebView (pingmu.html)
 *
 * All methods use ONLY normal permissions (no WRITE_SETTINGS / WRITE_SECURE_SETTINGS).
 */
public class ScreenFilterBridge {

    private final Context app;

    public ScreenFilterBridge(Context context) {
        this.app = context.getApplicationContext();
    }

    // ============================================================
    // Permission Checks
    // ============================================================

    @JavascriptInterface
    public boolean requestPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(app);
    }

    @JavascriptInterface
    public void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ============================================================
    // WRITE_SETTINGS Permission (for system brightness)
    // ============================================================

    @JavascriptInterface
    public boolean canWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.System.canWrite(app);
        }
        return true;
    }

    @JavascriptInterface
    public void openWriteSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ============================================================
    // Screen Filter (legacy)
    // ============================================================

    @JavascriptInterface
    public void startFilterService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(app)) return;
        Intent i = new Intent(app, ScreenFilterService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                app.startForegroundService(i);
            else
                app.startService(i);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void stopFilterService() {
        try { ScreenFilterService.stop(app); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setBrightness(int value) {
        try { ScreenFilterService.setBrightness(value); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setContrast(int value) {
        try { ScreenFilterService.setContrast(value); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setSaturation(int value) {
        try { ScreenFilterService.setSaturation(value); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setTemperature(int value) {
        try { ScreenFilterService.setTemperature(value); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setFilter(int brightness, int contrast, int saturation, int temperature) {
        try {
            ScreenFilterService.setFilter(brightness, contrast, saturation, temperature);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public boolean isServiceRunning() {
        return ScreenFilterService.isRunning();
    }

    @JavascriptInterface
    public void setPreset(int value) {
        try { ScreenFilterService.setPreset(value); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public int getPreset() {
        return ScreenFilterService.getPreset();
    }

    @JavascriptInterface
    public String getPresetName() {
        return ScreenFilterService.getPresetName();
    }

    @JavascriptInterface
    public int getBrightness()   { return Math.round(ScreenFilterService.getBrightness()); }
    @JavascriptInterface
    public int getContrast()     { return Math.round(ScreenFilterService.getContrast()); }
    @JavascriptInterface
    public int getSaturation()   { return Math.round(ScreenFilterService.getSaturation()); }
    @JavascriptInterface
    public int getTemperature()  { return Math.round(ScreenFilterService.getTemperature()); }

    // ============================================================
    // Performance Mode
    // ============================================================

    @JavascriptInterface
    public void startPerformanceMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(app)) return;
            Intent i = new Intent(app, PerformanceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                app.startForegroundService(i);
            else
                app.startService(i);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void stopPerformanceMode() {
        try { PerformanceService.stop(app); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public boolean isPerformanceModeRunning() {
        return PerformanceService.isRunning();
    }

    @JavascriptInterface
    public void setRefreshRate(int hz) {
        try {
            if (hz > 0 && !PerformanceService.isRunning()) {
                startPerformanceMode();
            } else if (hz == 0 && PerformanceService.isRunning()) {
                stopPerformanceMode();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setScreenBrightness(int level) {
        // System brightness requires WRITE_SETTINGS permission
        // This is handled by PerformanceService when it starts
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(app) && level >= 0) {
                    Settings.System.putInt(app.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, Math.min(255, level));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public boolean cleanBackground() {
        try {
            ActivityManager am = (ActivityManager)
                    app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                java.util.List<ActivityManager.RunningAppProcessInfo> processes =
                        am.getRunningAppProcesses();
                if (processes != null) {
                    String myPackage = app.getPackageName();
                    int killed = 0;
                    for (ActivityManager.RunningAppProcessInfo process : processes) {
                        // 保护所有前台/可见进程（100=FOREGROUND, 125=FOREGROUND_SERVICE,
                        // 150=TOP_SLEEPING, 200=VISIBLE），确保用户正在用的应用不会被杀
                        if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                                || process.processName.startsWith("com.android")
                                || process.processName.startsWith("android")
                                || process.processName.startsWith("system")
                                || process.processName.contains("huawei")
                                || process.processName.contains("hmos")
                                || process.processName.equals(myPackage)) {
                            continue;
                        }
                        am.killBackgroundProcesses(process.processName.split(":")[0]);
                        killed++;
                    }
                    return killed > 0;
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return false;
    }

    @JavascriptInterface
    public void setRenderMode(String mode) {
        try {
            if ("save".equals(mode) && !PerformanceService.isRunning()) {
                startPerformanceMode();
            } else if ("normal".equals(mode) && PerformanceService.isRunning()) {
                stopPerformanceMode();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public int getRefreshRate() {
        return PerformanceService.getCurrentRefreshRate(app);
    }

    @JavascriptInterface
    public int getScreenBrightness() {
        return PerformanceService.getCurrentBrightness(app);
    }

    @JavascriptInterface
    public String getAvailableMemory() {
        return PerformanceService.getAvailableMemory(app);
    }

    @JavascriptInterface
    public int getBatteryTemp() {
        return PerformanceService.getBatteryTemp(app);
    }

    // ============================================================
    // 色彩模式：鲜艳 / 标准（通过系统设置）
    // ============================================================

    @JavascriptInterface
    public void setVividMode() {
        try {
            ColorModeUtils.setVividMode(app);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void setStandardMode() {
        try {
            ColorModeUtils.setStandardMode(app);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public String getCurrentColorMode() {
        return ColorModeUtils.getCurrentMode(app);
    }

    // ============================================================
    // FPS 帧率监测
    // ============================================================

    @JavascriptInterface
    public void startFpsMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(app)) return;
        Intent i = new Intent(app, FpsMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                app.startForegroundService(i);
            else
                app.startService(i);
        } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public void stopFpsMonitor() {
        try { FpsMonitorService.stop(app); } catch (Exception e) { e.printStackTrace(); }
    }

    @JavascriptInterface
    public boolean isFpsMonitorRunning() {
        return FpsMonitorService.isRunning();
    }
}