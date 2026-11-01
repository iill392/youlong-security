package com.youlong.hd;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 后台保活闹钟（2026-10，用户需求：LMK 清除不掉的闹钟自启动）
 * ==========================================================================
 * 为什么是闹钟
 * --------------------------------------------------------------------------
 * AlarmManager 的闹钟登记在 system_server 里，**进程被 LMK 杀掉并不会清除它**。
 * 到点后系统会重新拉起本应用进程来接收广播 —— 这正是「后台被删了也能自己回来」
 * 需要的效果（START_STICKY / 双进程哨兵只能保证进程还在时互相拉起，
 * 整个进程组被 LMK 回收后就没辙了）。
 *
 * 本类提供的闹钟：
 *   · 一次性精确闹钟（{@code setExactAndAllowWhileIdle}），触发后在
 *     {@link KeepAliveReceiver} 里**重新排下一次** → 链式续期，永远不会断；
 *   · Doze 下同样会唤醒（allowWhileIdle）；
 *   · Android 12+ 没有精确闹钟权限时自动降级为 {@code setAndAllowWhileIdle}
 *     （不精确但仍能在 Doze 中唤醒，只是可能被延后），最差再降级为普通 set()。
 *
 * 多层保活体系（本类是「进程被 LMK 全灭」时的最后一道）：
 *   1. 本类：系统级闹钟链（进程被杀也能把它拉回来）
 *   2. 双进程哨兵：ProtectService(:main) ↔ ForegroundService(:guard) 互拉
 *   3. ForegroundService 里的 ACTION_TIME_TICK 每分钟检查
 *   4. START_STICKY + 开机广播 + 应用更新广播
 * ==========================================================================
 */
public final class KeepAliveAlarm {

    private static final String TAG = "KeepAliveAlarm";

    /** 闹钟广播 action：与旧版「无 action」的 2 分钟重复闹钟区分开 */
    public static final String ACTION_KEEPALIVE = "com.youlong.hd.KEEPALIVE_ALARM";

    /** PendingIntent requestCode（与 MainActivity 的 0x5A1/0x5A2 区分开） */
    private static final int REQ_EXACT = 0x5A9;
    /** 旧版重复闹钟的 requestCode（用于把历史版本留下的闹钟一起取消） */
    private static final int REQ_LEGACY = 0;

    /** 触发间隔：2 分钟（与历史行为一致；链式续期，单次只排一个） */
    private static final long INTERVAL_MS = 120_000L;

    private KeepAliveAlarm() {}

    /** 是否有任一守护开关处于开启状态（全关时不应再排闹钟） */
    public static boolean anyTriggerOn(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
            return sp.getBoolean("protect_on", false)
                    || sp.getBoolean("shake_trigger_on", false)
                    || sp.getBoolean("volume_trigger_on", false)
                    // 2026-10：自动拦截可独立于实时守护运行，也要保活
                    || sp.getBoolean("auto_block_on", true);
        } catch (Throwable tr) {
            return false;
        }
    }

    /** 排下一次保活闹钟（幂等：重复调用只会覆盖同一枚 PendingIntent） */
    public static void schedule(Context ctx) {
        if (!anyTriggerOn(ctx)) {
            cancel(ctx);
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(ctx);
        long triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS;

        // ① 首选：精确 + Doze 可唤醒（system_server 持有，LMK 清不掉）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            Log.i(TAG, "保活闹钟已排定（精确，2 分钟后）");
            return;
        } catch (SecurityException se) {
            Log.w(TAG, "无精确闹钟权限，降级为 allowWhileIdle: " + se.getMessage());
        } catch (Throwable tr) {
            Log.w(TAG, "排精确闹钟失败，降级: " + tr.getMessage());
        }

        // ② 降级：不精确但 Doze 中仍会唤醒
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            Log.i(TAG, "保活闹钟已排定（allowWhileIdle 降级）");
        } catch (Throwable tr) {
            Log.e(TAG, "排保活闹钟失败", tr);
        }
    }

    /** 取消保活闹钟（用户关闭全部守护开关时调用） */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.cancel(buildPendingIntent(ctx));
            // 顺带取消历史版本用 requestCode=0 排下的重复闹钟
            Intent legacy = new Intent(ctx, KeepAliveReceiver.class);
            PendingIntent legacyPi = PendingIntent.getBroadcast(ctx, REQ_LEGACY, legacy,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (legacyPi != null) {
                am.cancel(legacyPi);
                legacyPi.cancel();
            }
            Log.i(TAG, "保活闹钟已取消");
        } catch (Throwable tr) {
            Log.w(TAG, "取消保活闹钟失败", tr);
        }
    }

    private static PendingIntent buildPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveReceiver.class);
        i.setAction(ACTION_KEEPALIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ_EXACT, i, flags);
    }
}
