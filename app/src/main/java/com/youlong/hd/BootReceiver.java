package com.youlong.hd;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;


public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        SharedPreferences prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
        boolean protectOn = prefs.getBoolean("protect_on", false);

        if (protectOn) {
            Intent serviceIntent = new Intent(context, ProtectService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
            
            setupKeepAliveAlarm(context);
        } else {
            
            cancelKeepAliveAlarm(context);
        }
    }

    private void setupKeepAliveAlarm(Context context) {
        Intent keepIntent = new Intent(context, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(context, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 60_000, 120_000, keepPi);
                Log.i(TAG, "开机保活闹钟已设置（请求间隔 2 分钟；系统实际最小间隔约 15 分钟）");
            } catch (Exception e) {
                Log.e(TAG, "设置保活闹钟失败", e);
            }
        }
    }

    private void cancelKeepAliveAlarm(Context context) {
        Intent keepIntent = new Intent(context, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(context, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(keepPi);
            Log.i(TAG, "保活闹钟已取消");
        }
    }
}
