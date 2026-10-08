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
        createChannel();
        startForeground(NOTIFY_ID, buildNotify());

        
        tickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                checkAndRevive("TIME_TICK");
                
                updateNotify();
            }
        };
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            registerReceiver(tickReceiver, f);
        } catch (Exception ignored) {}

        
        try {
            GuardNative.startSentinel(findMainProcessPid(), Process.myPid(),
                    getFilesDir().getAbsolutePath());
        } catch (Throwable ignored) {}

        running = true;
        guardThread = new Thread(this::guardLoop, "guard-sentinel");
        guardThread.start();
        Log.i(TAG, "哨兵服务启动 pid=" + Process.myPid());
    }

    
    private Notification buildNotify() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        long start = sp.getLong("protect_start_time", 0L);
        String duration = formatDuration(start > 0 ? System.currentTimeMillis() - start : 0L);

        PendingIntent pi = null;
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 0x5A3, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ignored) {}

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("游龙安全护盾")
                .setContentText("已守护 " + duration)
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

    
    private void checkAndRevive(String from) {
        
        if (!anyTriggerOn()) {
            stopSelf();
            return;
        }
        if (!isServiceRunning(ProtectService.class)) {
            Log.w(TAG, "[" + from + "] 主进程 ProtectService 已停止，哨兵拉起...");
            try {
                Intent si = new Intent(this, ProtectService.class);
                ContextCompat.startForegroundService(this, si);
            } catch (Exception ignored) {}
        }
        
        try {
            File md = new File(getFilesDir(), "sentinel_main_dead");
            if (md.exists() && isServiceRunning(ProtectService.class)) {
                md.delete();
            }
        } catch (Exception ignored) {}
    }

    
    private boolean anyTriggerOn() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", Context.MODE_MULTI_PROCESS);
        return sp.getBoolean("protect_on", false)
                || sp.getBoolean("shake_trigger_on", false)
                || sp.getBoolean("volume_trigger_on", false);
    }

    
    private int findMainProcessPid() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return -1;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return -1;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pid > 0 && p.processName != null && p.processName.equals(getPackageName())) {
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
