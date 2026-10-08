package com.youlong.hd;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;


public class ColorModeUtils {

    private static final String TAG = "ColorMode";

    // ============================================================
    
    // ============================================================

    
    private static final String KEY_HUAWEI_COLOR_MODE        = "color_mode";
    
    private static final String KEY_HUAWEI_COLOR_TEMP        = "color_temperature";
    
    private static final String KEY_SAMSUNG_SCREEN_MODE      = "screen_mode_setting";
    
    private static final String KEY_XIAOMI_COLOR_SCHEME      = "screen_color_mode";
    
    private static final String KEY_OPPO_COLOR_MODE          = "oppo_display_color_mode";
    
    private static final String KEY_ONEPLUS_SCREEN_MODE      = "oneplus_screen_color_mode";
    
    private static final String KEY_VIVO_SCREEN_MODE         = "vivo_screen_color_mode";
    
    private static final String KEY_GENERIC_COLOR_MODE       = "screen_color_mode";
    
    private static final String KEY_PIXEL_NIGHT_LIGHT        = "night_display_activated";

    // ============================================================
    
    // ============================================================

    
    
    
    

    // ============================================================
    
    // ============================================================

    public static void setVividMode(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.System.canWrite(ctx)) {
            Log.w(TAG, "WRITE_SETTINGS not granted, cannot set color mode");
            return;
        }

        ContentResolver cr = ctx.getContentResolver();
        String brand = Build.BRAND.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();

        Log.i(TAG, "Setting VIVID mode for brand=" + brand + " mfr=" + manufacturer);

        
        tryPutSystemInt(cr, KEY_GENERIC_COLOR_MODE, 1);       
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_MODE, 1);        
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_TEMP, 1);        

        
        if (brand.contains("samsung") || manufacturer.contains("samsung")) {
            tryPutSystemInt(cr, KEY_SAMSUNG_SCREEN_MODE, 1);
        }

        
        if (brand.contains("xiaomi") || manufacturer.contains("xiaomi")) {
            tryPutSystemInt(cr, KEY_XIAOMI_COLOR_SCHEME, 1);
            
            tryPutGlobalInt(cr, "display_color_mode", 1);
        }

        
        if (brand.contains("oppo") || brand.contains("realme")
                || manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            tryPutSystemInt(cr, KEY_OPPO_COLOR_MODE, 1);
        }
        if (brand.contains("oneplus") || manufacturer.contains("oneplus")) {
            tryPutSystemInt(cr, KEY_ONEPLUS_SCREEN_MODE, 1);
        }

        // vivo
        if (brand.contains("vivo") || manufacturer.contains("vivo")) {
            tryPutSystemInt(cr, KEY_VIVO_SCREEN_MODE, 1);
        }

        
        if (brand.contains("google") || manufacturer.contains("google")) {
            tryPutSecureInt(cr, KEY_PIXEL_NIGHT_LIGHT, 0);
            tryPutSystemInt(cr, "display_color_mode", 1);
        }

        
        tryPutGlobalInt(cr, "display_color_enhance", 1);
        tryPutGlobalInt(cr, "vivid_mode", 1);
        tryPutGlobalInt(cr, "color_mode_vivid", 1);
        tryPutGlobalInt(cr, "color_vivid_enabled", 1);

        Log.i(TAG, "Vivid mode applied (multiple keys)");
    }

    // ============================================================
    
    // ============================================================

    public static void setStandardMode(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.System.canWrite(ctx)) {
            Log.w(TAG, "WRITE_SETTINGS not granted, cannot set color mode");
            return;
        }

        ContentResolver cr = ctx.getContentResolver();
        String brand = Build.BRAND.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();

        Log.i(TAG, "Setting STANDARD mode for brand=" + brand + " mfr=" + manufacturer);

        tryPutSystemInt(cr, KEY_GENERIC_COLOR_MODE, 0);       
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_MODE, 0);        
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_TEMP, 0);        

        if (brand.contains("samsung") || manufacturer.contains("samsung")) {
            tryPutSystemInt(cr, KEY_SAMSUNG_SCREEN_MODE, 2);  
        }

        if (brand.contains("xiaomi") || manufacturer.contains("xiaomi")) {
            tryPutSystemInt(cr, KEY_XIAOMI_COLOR_SCHEME, 0);  
            tryPutGlobalInt(cr, "display_color_mode", 0);
        }

        if (brand.contains("oppo") || brand.contains("realme")
                || manufacturer.contains("oppo") || manufacturer.contains("realme")) {
            tryPutSystemInt(cr, KEY_OPPO_COLOR_MODE, 0);
        }
        if (brand.contains("oneplus") || manufacturer.contains("oneplus")) {
            tryPutSystemInt(cr, KEY_ONEPLUS_SCREEN_MODE, 0);
        }
        if (brand.contains("vivo") || manufacturer.contains("vivo")) {
            tryPutSystemInt(cr, KEY_VIVO_SCREEN_MODE, 0);
        }
        if (brand.contains("google") || manufacturer.contains("google")) {
            tryPutSecureInt(cr, KEY_PIXEL_NIGHT_LIGHT, 0);
            tryPutSystemInt(cr, "display_color_mode", 0);
        }

        tryPutGlobalInt(cr, "display_color_enhance", 0);
        tryPutGlobalInt(cr, "vivid_mode", 0);
        tryPutGlobalInt(cr, "color_mode_vivid", 0);
        tryPutGlobalInt(cr, "color_vivid_enabled", 0);

        Log.i(TAG, "Standard mode applied (multiple keys)");
    }

    // ============================================================
    
    // ============================================================

    public static String getCurrentMode(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();

        
        int val;
        val = tryGetSystemInt(cr, KEY_GENERIC_COLOR_MODE, -1);
        if (val >= 0) return val == 1 ? "Vivid" : "Standard";

        val = tryGetSystemInt(cr, KEY_HUAWEI_COLOR_MODE, -1);
        if (val >= 0) return val == 1 ? "Vivid" : "Standard";

        val = tryGetSystemInt(cr, KEY_SAMSUNG_SCREEN_MODE, -1);
        if (val == 1) return "Vivid";
        if (val == 2 || val == 3) return "Standard";

        // KEY_XIAOMI_COLOR_SCHEME 与 KEY_GENERIC_COLOR_MODE 同为 "screen_color_mode"，
        // 上面的通用分支已覆盖，此分支不可达，已移除（2026-10 审查）

        
        val = tryGetGlobalInt(cr, "display_color_enhance", -1);
        if (val == 1) return "Vivid";
        if (val == 0) return "Standard";

        return "Unknown";
    }

    // ============================================================
    
    // ============================================================

    private static void tryPutSystemInt(ContentResolver cr, String key, int val) {
        try {
            Settings.System.putInt(cr, key, val);
            Log.d(TAG, "Settings.System." + key + " = " + val);
        } catch (Exception ignored) {}
    }

    private static void tryPutGlobalInt(ContentResolver cr, String key, int val) {
        try {
            Settings.Global.putInt(cr, key, val);
            Log.d(TAG, "Settings.Global." + key + " = " + val);
        } catch (Exception ignored) {}
    }

    private static void tryPutSecureInt(ContentResolver cr, String key, int val) {
        try {
            Settings.Secure.putInt(cr, key, val);
            Log.d(TAG, "Settings.Secure." + key + " = " + val);
        } catch (Exception ignored) {}
    }

    private static int tryGetSystemInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.System.getInt(cr, key);
        } catch (Exception e) {
            return def;
        }
    }

    private static int tryGetGlobalInt(ContentResolver cr, String key, int def) {
        try {
            return Settings.Global.getInt(cr, key);
        } catch (Exception e) {
            return def;
        }
    }
}