package com.youlong.hd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * 新装 / 更新应用后立即做一次单包病毒库检测（2026-10-24 新增）
 * ==========================================================================
 * 之前后台检测只能等 3 分钟轮询，用户会觉得「装完半天没反应」。这个广播接收
 * 器在 PACKAGE_ADDED / PACKAGE_REPLACED 时只扫这一个包（证书/包名/哈希/token），
 * 命中就弹「高风险应用」窗。
 *
 * 注意：
 *   · PACKAGE_ADDED 在「覆盖安装」时也会发一次（EXTRA_REPLACING=true），
 *     这种交给 PACKAGE_REPLACED 处理，避免同一个包扫两遍；
 *   · 特征库未就绪时直接返回，等 3 分钟轮询兜底；
 *   · 扫描在独立线程，不阻塞广播。
 * ==========================================================================
 */
public class VirusInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "VirusInstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_ADDED.equals(action)
                    && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                return;
            }
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return;
            }
            Uri data = intent.getData();
            if (data == null) return;
            String pkg = data.getSchemeSpecificPart();
            if (pkg == null || pkg.isEmpty()) return;

            Context app = context.getApplicationContext();
            if (app == null) app = context;
            final Context scanCtx = app;
            final String scanPkg = pkg;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    VirusAutoScanner.scanPackage(scanCtx, scanPkg);
                }
            }, "VirusInstallScan").start();
        } catch (Throwable tr) {
            Log.w(TAG, "安装广播处理失败", tr);
        }
    }
}
