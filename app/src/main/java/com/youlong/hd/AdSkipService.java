package com.youlong.hd;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.content.ContextCompat;

/**
 * 无障碍核心服务（2026-11-02 精简）。
 *
 * 广告跳过的节点扫描/点击/监控配置已经全部删除；当前只保留安全守护
 * 真正依赖的能力：
 *   · WINDOW_STATE_CHANGED → 前台应用包名 → ProtectService；
 *   · 音量键回调 / 可选按键过滤；
 *   · 无障碍服务被系统拉起时补拉守护服务与保活闹钟。
 *
 * 类名保留 AdSkipService，是为了兼容已经写入系统设置里的
 * com.youlong.hd/com.youlong.hd.AdSkipService 组件名和自保恢复列表；
 * 如果改名，升级后系统会认为原无障碍服务不存在，用户需要重新开启。
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "ShieldA11y";

    private static AdSkipService sInstance;

    /** 前台应用变化回调接口 */
    public interface ForegroundChangeListener {
        void onForegroundChanged(String pkg);
    }
    private static ForegroundChangeListener sFgListener = null;

    public static void setForegroundChangeListener(ForegroundChangeListener l) {
        sFgListener = l;
    }

    /** 音量键回调接口 */
    public interface VolumeChangeListener {
        /**
         * @param volumeType 变化的音频流编号（-1 表示来自物理按键层）
         * @param direction  -1 = 音量减，+1 = 音量加，0 = 未知
         */
        void onVolumeChanged(int volumeType, int direction);
    }
    private static VolumeChangeListener sVolListener = null;

    public static void setVolumeChangeListener(VolumeChangeListener l) {
        sVolListener = l;
    }

    /** 当前是否需要按键过滤（onServiceConnected 时按它恢复） */
    private static volatile boolean sKeyFilterWanted = false;

    public static void setKeyFilterEnabled(boolean enabled) {
        sKeyFilterWanted = enabled;
        AdSkipService inst = sInstance;
        if (inst != null) inst.applyKeyFilter(enabled);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private String mCurrentPkg = "";

    private void applyKeyFilter(final boolean enabled) {
        mHandler.post(() -> applyKeyFilterNow(enabled));
    }

    private void applyKeyFilterNow(boolean enabled) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            boolean has = (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
            if (enabled == has) return;
            if (enabled) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            } else {
                info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            }
            setServiceInfo(info);
            Log.d(TAG, "按键过滤 → " + (enabled ? "开" : "关"));
        } catch (Throwable tr) {
            Log.e(TAG, "设置按键过滤失败", tr);
        }
    }

    /** 获取当前前台应用包名 */
    public static String getForegroundPkg() {
        return sInstance != null ? sInstance.mCurrentPkg : null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.d(TAG, "无障碍核心服务创建");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        boolean volTrigger = false;
        try {
            volTrigger = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getBoolean("volume_trigger_on", false);
        } catch (Throwable ignored) {
        }
        applyKeyFilter(volTrigger);
        ensureGuardAfterBoot();
        Log.d(TAG, "无障碍核心服务已连接 keyFilter=" + volTrigger);
    }

    /**
     * 不依赖 BOOT_COMPLETED 的开机兜底：无障碍服务被系统拉起时，
     * 补拉守护服务并排保活闹钟。
     */
    private void ensureGuardAfterBoot() {
        try {
            SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            boolean anyOn = sp.getBoolean("protect_on", false)
                    || sp.getBoolean("shake_trigger_on", false)
                    || sp.getBoolean("volume_trigger_on", false)
                    || sp.getBoolean("auto_block_on", true);
            if (!anyOn) return;
            boolean protectOn = sp.getBoolean("protect_on", false);
            try {
                if (protectOn) {
                    ContextCompat.startForegroundService(this,
                            new Intent(this, ProtectService.class));
                } else {
                    startService(new Intent(this, ProtectService.class));
                }
            } catch (Throwable tr) {
                Log.w(TAG, "无障碍兜底拉起 ProtectService 失败", tr);
            }
            if (protectOn) {
                try {
                    ContextCompat.startForegroundService(this,
                            new Intent(this, ForegroundService.class));
                } catch (Throwable tr) {
                    Log.w(TAG, "无障碍兜底拉起 ForegroundService 失败", tr);
                }
            } else {
                try {
                    stopService(new Intent(this, ForegroundService.class));
                } catch (Throwable ignored) {
                }
            }
            try {
                KeepAliveAlarm.schedule(this);
            } catch (Throwable tr) {
                Log.w(TAG, "无障碍兜底排保活闹钟失败", tr);
            }
            Log.i(TAG, "无障碍服务已补拉守护服务/保活闹钟");
        } catch (Throwable tr) {
            Log.w(TAG, "无障碍开机兜底失败", tr);
        }
    }

    /**
     * 音量变化回调（API 26+），只通知 ProtectService，不做广告扫描。
     */
    public void onVolumeChanged(int volumeType) {
        VolumeChangeListener l = sVolListener;
        if (l != null) {
            try {
                l.onVolumeChanged(volumeType, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 物理音量键回调：只做一次异步投递后立刻返回，避免拖慢系统音量。
     */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            final int code = event.getKeyCode();
            int dir = 0;
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) dir = -1;
            else if (code == KeyEvent.KEYCODE_VOLUME_UP) dir = 1;
            if (dir != 0) {
                final int dirFinal = dir;
                mHandler.post(() -> {
                    VolumeChangeListener l = sVolListener;
                    if (l != null) {
                        try {
                            l.onVolumeChanged(-1, dirFinal);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        }
        return super.onKeyEvent(event);
    }

    /**
     * 2026-11-02：广告跳过扫描已删除，只保留前台应用变化通知。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (pkg.equals(mCurrentPkg)) return;
        mCurrentPkg = pkg;
        ForegroundChangeListener l = sFgListener;
        if (l != null) {
            try {
                l.onForegroundChanged(pkg);
            } catch (Throwable ignored) {
            }
        }
    }
}
