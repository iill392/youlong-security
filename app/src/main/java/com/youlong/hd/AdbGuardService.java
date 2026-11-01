// ==========================================================================
// 游龙安全护盾 —— 新增文件（2026-10-25）
// ADB 防护常驻服务（主进程）：每 5 秒执行一次 AdbGuard.enforce。
//
// 放在主进程而不是 :guard，是因为 Stellar Binder 只投递到主进程；
// :guard 里 pingBinder() 为 false，防护会一直暂停。
// ==========================================================================

package com.youlong.hd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import roro.stellar.manager.adb.AdbGuard;

public class AdbGuardService extends Service {

    private static final String TAG = "AdbGuardService";
    private static final String CHANNEL_ID = "adb_guard_channel";
    private static final int NOTIFY_ID = 2001;

    private HandlerThread workerThread;
    private Handler handler;
    private int tick = 0;
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            try {
                AdbGuard.enforce(AdbGuardService.this);
            } catch (Throwable tr) {
                Log.w(TAG, "ADB 防护执行失败", tr);
            }
            try {
                // 自保 watchdog：每 30 秒确认一次是否还在跑。
                if (tick++ % 6 == 0) {
                    SelfDefense.ensureRunning(AdbGuardService.this);
                }
            } catch (Throwable tr) {
                Log.w(TAG, "自保 watchdog 检查失败", tr);
            }
            if (handler != null) handler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 2026-10-30（用户要求）：ADB 防护不再显示前台通知。
        // 改成普通后台 Service；不调用 startForeground，也不创建通知。
        // 2026-10-25：防护里的 pingBinder / Settings / getprop 不能在主线程阻塞；
        // 放后台线程，避免主进程卡住导致 Stellar Binder 投递失败/启动后显示断开。
        workerThread = new HandlerThread("AdbGuardWorker");
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        handler.post(loop);
        Log.i(TAG, "ADB 防护服务已启动（无通知）");
        SelfDefense.ensureRunning(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ADB 防护",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("阻止第三方 App 通过无线/TCP ADB 绕过内置特权");
        nm.createNotificationChannel(channel);
    }
}
