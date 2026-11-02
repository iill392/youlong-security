package com.youlong.hd;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;

/**
 * 哨兵守护服务 — 运行在独立进程（:guard）
 *
 * "哨兵"机制核心：与主进程 ProtectService 互相监视，兄弟被杀立即复活。
 * 三重保障：
 *   1. 守护线程每 5 秒用 ActivityManager 检查主进程 ProtectService，
 *      发现被杀立即 startForegroundService 拉起；
 *   2. 动态注册 ACTION_TIME_TICK（系统每分钟广播，不经 AlarmManager，
 *      厂商省电策略无法延迟前台进程接收）；
 *   3. Native fork 子进程（GuardNative.startSentinel）：C 层独立进程
 *      实时监视主进程/哨兵进程存活，兄弟死亡立即写标记文件，
 *      Java 层轮询核实后拉起。
 *
 * 本进程（哨兵）被杀 → 主进程 ProtectService 的 tick 循环检测到并拉起
 * （ProtectService 内实现，构成双向守护）。
 */
public class ForegroundService extends Service {
    private static final String TAG = "GuardService";
    private static final String CHANNEL_ID = "guard_channel";
    private static final int NOTIFY_ID = 1002;

    private volatile boolean running = false;
    private Thread guardThread;
    private final Object lock = new Object();
    private BroadcastReceiver tickReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        // 2026-11-02（用户需求）：只有开启实时守护才启动哨兵并显示常驻通知。
        // 仅自动拦截/摇一摇/音量触发时，由 ProtectService 后台运行，不拉起哨兵通知。
        if (!guardTriggerOn()) {
            stopSelf();
            return;
        }
        createChannel();
        startForeground(NOTIFY_ID, buildNotify());

        // 动态注册系统每分钟广播：不依赖 AlarmManager
        tickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                checkAndRevive("TIME_TICK");
                // 同步刷新"已开启守护多久"（系统每分钟发一次，成本极低）
                updateNotify();
            }
        };
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            registerReceiver(tickReceiver, f);
        } catch (Exception ignored) {}

        // Native fork 哨兵：独立 C 子进程监视兄弟存活
        try {
            GuardNative.startSentinel(findMainProcessPid(), Process.myPid(),
                    getFilesDir().getAbsolutePath());
        } catch (Throwable ignored) {}

        running = true;
        guardThread = new Thread(this::guardLoop, "guard-sentinel");
        guardThread.start();
        Log.i(TAG, "哨兵服务启动 pid=" + Process.myPid());
    }

    /**
     * 2026-11-02（用户需求）：只有实时守护需要哨兵和常驻通知；
     * 摇一摇/音量触发/自动拦截都不再显示常驻通知。
     */
    private boolean guardTriggerOn() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        return sp.getBoolean("protect_on", false);
    }

    /**
     * 哨兵通知：实时守护时显示"已守护多久"；只开自动拦截时如实显示自动拦截状态。
     */
    private Notification buildNotify() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        long start = sp.getLong("protect_start_time", 0L);
        String duration = formatDuration(start > 0 ? System.currentTimeMillis() - start : 0L);
        boolean protectOn = sp.getBoolean("protect_on", false);

        PendingIntent pi = null;
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 0x5A3, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ignored) {}

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("游龙安全护盾")
                .setContentText(protectOn ? ("已守护 " + duration) : "自动拦截运行中（未开启实时守护）")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    private void updateNotify() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID, buildNotify());
        } catch (Exception ignored) {}
    }

    /** 守护时长格式化：X天X小时X分钟 / X小时X分钟 / X分钟 */
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

    private void guardLoop() {
        long lastPidUpdate = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                // 主进程 PID 可能变化，每 30 秒刷新 native 哨兵监视目标
                if (now - lastPidUpdate > 30_000) {
                    lastPidUpdate = now;
                    try {
                        GuardNative.startSentinel(findMainProcessPid(), Process.myPid(),
                                getFilesDir().getAbsolutePath());
                    } catch (Throwable ignored) {}
                }
                checkAndRevive("guard-loop");
            } catch (Throwable ignored) {}
            synchronized (lock) {
                try {
                    lock.wait(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /** 主检查：主进程被杀则拉起；native 哨兵标记核实后清除 */
    private void checkAndRevive(String from) {
        // 2026-11-02：哨兵只在实时守护开启时存在；关闭后立即退场，避免残留通知。
        if (!guardTriggerOn()) {
            stopSelf();
            return;
        }
        // 开关全关 = 用户已关闭守护 → 哨兵退场（自身停止，不拉起任何东西）
        if (!anyTriggerOn()) {
            stopSelf();
            return;
        }
        if (!isServiceRunning(ProtectService.class)) {
            Log.w(TAG, "[" + from + "] 主进程 ProtectService 已停止，哨兵拉起...");
            try {
                // 此时 guardTriggerOn 为 true，走前台服务是安全的；用统一入口避免遗漏。
                ProtectService.startCompat(this);
            } catch (Exception ignored) {}
        }
        // native 哨兵标记：主进程曾死 → 核实已恢复运行则清除
        try {
            File md = new File(getFilesDir(), "sentinel_main_dead");
            if (md.exists() && isServiceRunning(ProtectService.class)) {
                md.delete();
            }
        } catch (Exception ignored) {}
    }

    /** 任一开关开启？（2026-10：自动拦截也算 —— 不开实时守护也要保留哨兵） */
    private boolean anyTriggerOn() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", Context.MODE_MULTI_PROCESS);
        return sp.getBoolean("protect_on", false)
                || sp.getBoolean("shake_trigger_on", false)
                || sp.getBoolean("volume_trigger_on", false)
                || sp.getBoolean("auto_block_on", true);
    }

    /** 主进程 PID（com.youlong.hd，不含 :guard 后缀），找不到返回 -1 */
    private int findMainProcessPid() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return -1;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return -1;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pid > 0 && p.processName != null && p.processName.equals("com.youlong.hd")) {
                    return p.pid;
                }
            }
        } catch (Exception ignored) {}
        return -1;
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

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "哨兵守护", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("游龙安全护盾双进程守护哨兵");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (guardThread != null) guardThread.interrupt();
        if (tickReceiver != null) {
            try {
                unregisterReceiver(tickReceiver);
            } catch (Exception ignored) {}
        }
        try {
            GuardNative.stopSentinel();
        } catch (Throwable ignored) {}
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        super.onDestroy();
        Log.i(TAG, "哨兵服务销毁 pid=" + Process.myPid());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
