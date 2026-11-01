package com.youlong.hd;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 屏幕色彩模式工具类
 *
 * 兼容：华为/鸿蒙、三星、小米、OPPO/vivo、一加、Google Pixel、摩托罗拉等
 * 通过写入 Settings.System / Settings.Global / Settings.Secure 的多厂商兼容键值实现。
 *
 * 需要 WRITE_SETTINGS 权限（用户在设置里手动授权）。
 */
public class ColorModeUtils {

    private static final String TAG = "ColorMode";

    // ============================================================
    // 厂商已知键名映射
    // ============================================================

    /** 华为 / 鸿蒙 色彩模式键 */
    private static final String KEY_HUAWEI_COLOR_MODE        = "color_mode";
    /** 华为 / 鸿蒙 色温模式键 */
    private static final String KEY_HUAWEI_COLOR_TEMP        = "color_temperature";
    /** 三星 屏幕模式键 */
    private static final String KEY_SAMSUNG_SCREEN_MODE      = "screen_mode_setting";
    /** 小米 色彩模式键 */
    private static final String KEY_XIAOMI_COLOR_SCHEME      = "screen_color_mode";
    /** OPPO / realme 色彩模式键 */
    private static final String KEY_OPPO_COLOR_MODE          = "oppo_display_color_mode";
    /** 一加 屏幕色彩模式 */
    private static final String KEY_ONEPLUS_SCREEN_MODE      = "oneplus_screen_color_mode";
    /** vivo 色彩模式键 */
    private static final String KEY_VIVO_SCREEN_MODE         = "vivo_screen_color_mode";
    /** 通用色彩模式（Android 11+ hidden API） */
    private static final String KEY_GENERIC_COLOR_MODE       = "screen_color_mode";
    /** Google Pixel 色彩模式 */
    private static final String KEY_PIXEL_NIGHT_LIGHT        = "night_display_activated";

    // ============================================================
    // 模式下标（各厂商不同）
    // ============================================================

    // 通用：0=自然/标准  1=鲜艳  2=增强
    // 华为：0=标准  1=鲜艳
    // 三星：1=自适应(鲜艳)  2=自然  3=基本
    // 小米：0=标准  1=鲜艳  2=原色

    // ============================================================
    // 设置鲜艳模式
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

        // 试多个已知键，只会一个生效
        tryPutSystemInt(cr, KEY_GENERIC_COLOR_MODE, 1);       // 通用
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_MODE, 1);        // 华为/鸿蒙
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_TEMP, 1);        // 华为色温

        // 三星：1=自适应（鲜艳）
        if (brand.contains("samsung") || manufacturer.contains("samsung")) {
            tryPutSystemInt(cr, KEY_SAMSUNG_SCREEN_MODE, 1);
        }

        // 小米
        if (brand.contains("xiaomi") || manufacturer.contains("xiaomi")) {
            tryPutSystemInt(cr, KEY_XIAOMI_COLOR_SCHEME, 1);
            // 小米饱和度增强
            tryPutGlobalInt(cr, "display_color_mode", 1);
        }

        // OPPO / realme / 一加
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

        // Pixel: 关闭自然模式（night light）, 使用增强模式
        if (brand.contains("google") || manufacturer.contains("google")) {
            tryPutSecureInt(cr, KEY_PIXEL_NIGHT_LIGHT, 0);
            tryPutSystemInt(cr, "display_color_mode", 1);
        }

        // 统一尝试全局键
        tryPutGlobalInt(cr, "display_color_enhance", 1);
        tryPutGlobalInt(cr, "vivid_mode", 1);
        tryPutGlobalInt(cr, "color_mode_vivid", 1);
        tryPutGlobalInt(cr, "color_vivid_enabled", 1);

        Log.i(TAG, "Vivid mode applied (multiple keys)");
    }

    // ============================================================
    // 设置标准模式
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

        tryPutSystemInt(cr, KEY_GENERIC_COLOR_MODE, 0);       // 通用 0=自然
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_MODE, 0);        // 华为 0=标准
        tryPutSystemInt(cr, KEY_HUAWEI_COLOR_TEMP, 0);        // 华为色温居中

        if (brand.contains("samsung") || manufacturer.contains("samsung")) {
            tryPutSystemInt(cr, KEY_SAMSUNG_SCREEN_MODE, 2);  // 三星 2=自然
        }

        if (brand.contains("xiaomi") || manufacturer.contains("xiaomi")) {
            tryPutSystemInt(cr, KEY_XIAOMI_COLOR_SCHEME, 0);  // 小米 0=标准
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
    // 读取当前模式（读第一个找到的键）
    // ============================================================

    public static String getCurrentMode(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();

        // 按优先级读
        int val;
        val = tryGetSystemInt(cr, KEY_GENERIC_COLOR_MODE, -1);
        if (val >= 0) return val == 1 ? "Vivid" : "Standard";

        val = tryGetSystemInt(cr, KEY_HUAWEI_COLOR_MODE, -1);
        if (val >= 0) return val == 1 ? "Vivid" : "Standard";

        val = tryGetSystemInt(cr, KEY_SAMSUNG_SCREEN_MODE, -1);
        if (val == 1) return "Vivid";
        if (val == 2 || val == 3) return "Standard";

        val = tryGetSystemInt(cr, KEY_XIAOMI_COLOR_SCHEME, -1);
        if (val == 1) return "Vivid";
        if (val == 0 || val == 2) return "Standard";

        // 回退：读全局键
        val = tryGetGlobalInt(cr, "display_color_enhance", -1);
        if (val == 1) return "Vivid";
        if (val == 0) return "Standard";

        return "Unknown";
    }

    // ============================================================
    // 安全写入工具
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