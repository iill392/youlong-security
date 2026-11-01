package com.youlong.hd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * 通知栏"卸载软件"按钮的广播接收器。
 * 点击后：先启动原生卸载 → 5秒后尝试打开应用设置页（供手动卸载）。
 */
public class NotifyUninstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String pkg = intent.getStringExtra("target_pkg");
        if (pkg == null || pkg.isEmpty()) return;

        // 第一步：启动原生系统卸载界面
        try {
            Intent u = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
            u.setData(Uri.parse("package:" + pkg));
            u.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            u.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(u);
        } catch (Exception ignored) {}

        // 第二步：5秒后尝试打开应用设置页面（卸载若失败，用户可在此手动卸载）
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    s.setData(Uri.parse("package:" + pkg));
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(s);
                } catch (Exception ignored) {}
            }
        }, 5000);
    }
}
