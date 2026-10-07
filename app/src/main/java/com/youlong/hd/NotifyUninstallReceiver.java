package com.youlong.hd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 通知栏"卸载软件"按钮的广播接收器。
 * 点击后：先启动原生卸载 → 5秒后尝试打开应用设置页（供手动卸载）。
 *
 * <p>2026-10 修复（白名单没效果）：执行前先校验目标是否为受保护/白名单应用。
 * 拦截通知可能由音量键/摇一摇误触产生并滞留在通知栏，用户晚点才点"卸载软件"，
 * 若不校验，白名单应用（含微信等内置信任应用）也会被直接拉起系统卸载框。
 */
public class NotifyUninstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String pkg = intent.getStringExtra("target_pkg");
        if (pkg == null || pkg.isEmpty()) return;

        // ===== 保护检查：自己 / 桌面宠物 / 游龙工具 / 白名单应用绝不拉起卸载 =====
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        if (pkg.equals(appCtx.getPackageName())
                || "com.youlong.hd".equals(pkg)
                || "com.youlong.zoo".equals(pkg)
                || "com.youlong.tool".equals(pkg)
                || WhitelistActivity.isWhitelisted(appCtx, pkg)) {
            android.util.Log.w("NotifyUninstall", "受保护/白名单应用，拒绝卸载: " + pkg);
            try {
                Toast.makeText(appCtx, "该应用在白名单中，已跳过卸载", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
            return;
        }

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
