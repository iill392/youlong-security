package com.youlong.hd;

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

}
