package com.youlong.hd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 开机自启 / 应用更新自启
 * ==========================================================================
 * 2026-10 强化（用户需求：LMK 清除不掉的闹钟自启动）
 * --------------------------------------------------------------------------
 * 原来只在 ACTION_BOOT_COMPLETED 时排一个 2 分钟的不精确重复闹钟，
 * 覆盖不到「应用被更新覆盖」这种场景（更新会清掉旧的 PendingIntent）。
 *
 * 现在：
 *   · 同时响应 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED；
 *   · 闹钟统一交给 {@link KeepAliveAlarm}（精确 + 触发后链式续期），
 *     LMK 杀掉进程也不会丢；
 *   · 守护开启时顺手把两个前台服务拉起来。
 * ==========================================================================
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        // 部分 ROM 的「快速开机」会发这个广播（非标准，收到就顺手处理）
        boolean quickBoot = "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!boot && !updated && !quickBoot) return;

        SharedPreferences prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
        // 2026-10-31：自动拦截固定开启，开机时纠正旧版本留下的 false。
        prefs.edit().putBoolean("auto_block_on", true).apply();
        boolean protectOn = prefs.getBoolean("protect_on", false);
        boolean shakeOn = prefs.getBoolean("shake_trigger_on", false);
        boolean volumeOn = prefs.getBoolean("volume_trigger_on", false);
        // 2026-10：自动拦截（默认开启）可以脱离实时守护独立运行，开机也要拉起
        boolean autoBlockOn = prefs.getBoolean("auto_block_on", true);
        boolean anyOn = protectOn || shakeOn || volumeOn || autoBlockOn;

        if (anyOn) {
            // 2026-11-02（用户需求）：只有实时守护才用前台服务显示常驻通知；
            // 仅自动拦截/摇一摇/音量触发时用普通 Service，不显示通知。
            try {
                if (protectOn) {
                    ContextCompat.startForegroundService(context,
                            new Intent(context, ProtectService.class));
                } else {
                    context.startService(new Intent(context, ProtectService.class));
                }
            } catch (Throwable tr) {
                Log.w(TAG, "开机拉起 ProtectService 失败", tr);
            }
            if (protectOn) {
                try {
                    ContextCompat.startForegroundService(context,
                            new Intent(context, ForegroundService.class));
                } catch (Throwable tr) {
                    Log.w(TAG, "开机拉起 ForegroundService 失败", tr);
                }
            } else {
                try {
                    context.stopService(new Intent(context, ForegroundService.class));
                } catch (Throwable ignored) {}
            }
            // 排保活闹钟（精确 + 链式续期）
            KeepAliveAlarm.schedule(context);
            Log.i(TAG, "已处理 " + action + "：守护服务已拉起，保活闹钟已排定");
        } else {
            KeepAliveAlarm.cancel(context);
            Log.i(TAG, "已处理 " + action + "：守护未开启，保活闹钟已取消");
        }

        // 2026-10-30：应用更新后，自动重启仍在跑旧代码的 stellar_server。
        try {
            ServerRestartManager.checkAsync(context);
        } catch (Throwable tr) {
            Log.w(TAG, "自动重启内置特权检查失败", tr);
        }
    }
}
