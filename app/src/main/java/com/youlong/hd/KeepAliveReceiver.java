package com.youlong.hd;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 保活闹钟接收器
 * ==========================================================================
 * 2026-10 强化（用户需求：LMK 清除不掉的闹钟自启动）
 * --------------------------------------------------------------------------
 * 旧实现只有 BootReceiver 在开机时排一个 2 分钟的不精确重复闹钟，
 * 一旦用户升级安装 / 闹钟被系统清掉 / 进程被 LMK 回收，就再也没有下一次。
 *
 * 现在：
 *   · 由 {@link KeepAliveAlarm} 排「一次性精确闹钟 + 触发后重新排下一次」的链，
 *     任何一次触发（哪怕进程刚被 LMK 杀过）都会把下一次排上，链不会断；
 *   · 收到闹钟后先拿 10 秒 WakeLock，保证启动前台服务的动作做完；
 *   · ProtectService / ForegroundService 谁没在跑就拉谁（双进程一起兜底）；
 *   · 顺带把下一次闹钟排上，再退出。
 * ==========================================================================
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "KeepAliveReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
        boolean protectOn = prefs.getBoolean("protect_on", false);
        boolean shakeOn = prefs.getBoolean("shake_trigger_on", false);
        boolean volumeOn = prefs.getBoolean("volume_trigger_on", false);
        // 2026-10：自动拦截独立于实时守护运行，也要被保活
        boolean autoBlockOn = prefs.getBoolean("auto_block_on", true);

        // 用户已关闭全部守护 → 不再重启，也不再排闹钟
        if (!protectOn && !shakeOn && !volumeOn && !autoBlockOn) {
            Log.i(TAG, "守护已全部关闭，保活闹钟不再续期");
            KeepAliveAlarm.cancel(context);
            return;
        }

        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":alarm");
                wl.acquire(10_000L);
            }
        } catch (Throwable ignored) {}

        try {
            // ① 先把下一次闹钟排上（先排再拉服务：即使后面启动服务失败，链也不会断）
            KeepAliveAlarm.schedule(context);

            // ② 主守护服务：实时守护用前台服务，否则普通 Service（无通知）
            if (!isServiceRunning(context, ProtectService.class)) {
                Log.w(TAG, "ProtectService 已停止，自动重启...");
                try {
                    if (protectOn) {
                        ContextCompat.startForegroundService(context,
                                new Intent(context, ProtectService.class));
                    } else {
                        context.startService(new Intent(context, ProtectService.class));
                    }
                } catch (Throwable tr) {
                    Log.w(TAG, "拉起 ProtectService 失败", tr);
                }
            }

            // ③ 哨兵进程：只有实时守护才需要
            if (protectOn) {
                if (!isServiceRunning(context, ForegroundService.class)) {
                    Log.w(TAG, "ForegroundService 已停止，自动重启...");
                    ContextCompat.startForegroundService(context,
                            new Intent(context, ForegroundService.class));
                }
            } else {
                try {
                    context.stopService(new Intent(context, ForegroundService.class));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable tr) {
            Log.e(TAG, "保活唤醒处理失败", tr);
        } finally {
            if (wl != null && wl.isHeld()) {
                try { wl.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private boolean isServiceRunning(Context ctx, Class<?> serviceClass) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
                    if (serviceClass.getName().equals(s.service.getClassName())) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
