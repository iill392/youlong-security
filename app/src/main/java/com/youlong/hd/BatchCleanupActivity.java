package com.youlong.hd;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量清理弹窗 — 检测到软件级黑名单应用时弹出
 * 显示所有被发现的病毒应用，提供"一键清除"批量卸载
 */
public class BatchCleanupActivity extends Activity {

    private static final String TAG = "BatchCleanup";
    private List<String> foundPackages = new ArrayList<>();
    private WindowManager wm;
    private View overlayView;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private boolean resolved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] arr = getIntent().getStringArrayExtra("found_packages");
        if (arr != null) {
            for (String p : arr) foundPackages.add(p);
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        refreshHandler = new Handler(Looper.getMainLooper());

        setContentView(new View(this));
        showOverlay();

        // 800ms 刷新保持最顶层
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!resolved && overlayView != null) {
                    try { wm.removeView(overlayView); } catch (Exception ignored) {}
                    wm.addView(overlayView, makeLayoutParams());
                }
                refreshHandler.postDelayed(this, 800);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, 800);
    }

    private WindowManager.LayoutParams makeLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        return lp;
    }

    private void showOverlay() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xDD1B0000);
        root.setPadding(30, 50, 30, 40);

        // ⚠️ 大图标
        TextView icon = new TextView(this);
        icon.setText("\u26A0\uFE0F");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 10);
        root.addView(icon);

        // 标题
        TextView title = new TextView(this);
        title.setText("检测到高风险锁机病毒！");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        // 数量 + 风险等级
        TextView countTv = new TextView(this);
        countTv.setText("发现 " + foundPackages.size() + " 款应用是 95% 高风险锁机病毒");
        countTv.setTextColor(0xFFFF4444);
        countTv.setTextSize(17);
        countTv.setGravity(Gravity.CENTER);
        countTv.setPadding(0, 0, 0, 6);
        root.addView(countTv);

        TextView subTv = new TextView(this);
        subTv.setText("以下应用极可能存在恶意锁机行为，建议立即清除！");
        subTv.setTextColor(0xFFFFCC80);
        subTv.setTextSize(13);
        subTv.setGravity(Gravity.CENTER);
        subTv.setPadding(0, 0, 0, 18);
        root.addView(subTv);

        // 应用列表（可滚动）
        ScrollView scrollView = new ScrollView(this);
        scrollView.setPadding(8, 0, 8, 0);

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        PackageManager pm = getPackageManager();
        for (String pkg : foundPackages) {
            String appName = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                appName = ai.loadLabel(pm).toString();
            } catch (Exception ignored) {}

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackgroundColor(0x33FF0000);
            item.setPadding(16, 12, 16, 12);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = 8;
            item.setLayoutParams(itemLp);

            // 应用名称
            TextView nameTv = new TextView(this);
            nameTv.setText(appName);
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(15);
            nameTv.setPadding(0, 0, 0, 3);
            item.addView(nameTv);

            // 包名
            TextView pkgTv = new TextView(this);
            pkgTv.setText(pkg);
            pkgTv.setTextColor(0xFFAAAAAA);
            pkgTv.setTextSize(12);
            pkgTv.setPadding(0, 0, 0, 3);
            item.addView(pkgTv);

            // 风险等级
            TextView riskTv = new TextView(this);
            riskTv.setText("95% 高危锁机病毒 ⚠️  建议立即卸载！");
            riskTv.setTextColor(0xFFFF4444);
            riskTv.setTextSize(13);
            riskTv.setBackgroundColor(0x22FF0000);
            riskTv.setPadding(6, 4, 6, 4);
            item.addView(riskTv);

            listLayout.addView(item);
        }

        scrollView.addView(listLayout);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        svLp.weight = 1;
        svLp.bottomMargin = 16;
        scrollView.setLayoutParams(svLp);
        root.addView(scrollView);

        // ===== 一键清除 =====
        Button btnClean = new Button(this);
        btnClean.setText("一键清除所有病毒");
        btnClean.setTextColor(Color.WHITE);
        btnClean.setTextSize(19);
        btnClean.setBackgroundColor(0xFFD32F2F);
        btnClean.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams cleanLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cleanLp.bottomMargin = 12;
        btnClean.setLayoutParams(cleanLp);
        btnClean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doBatchUninstall();
            }
        });
        root.addView(btnClean);

        // ===== 稍后处理 =====
        Button btnLater = new Button(this);
        btnLater.setText("稍后处理");
        btnLater.setTextColor(Color.WHITE);
        btnLater.setTextSize(15);
        btnLater.setBackgroundColor(0xFF555555);
        btnLater.setPadding(30, 18, 30, 18);
        LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLater.setLayoutParams(laterLp);
        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        root.addView(btnLater);

        overlayView = root;

        try {
            wm.addView(overlayView, makeLayoutParams());
        } catch (Exception e) {
            setContentView(root);
        }
    }

    // ===== 逐个触发系统卸载 =====
    private void doBatchUninstall() {
        if (resolved) return;
        resolved = true;

        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < foundPackages.size(); i++) {
                    final String pkg = foundPackages.get(i);
                    Log.w(TAG, "批量卸载: " + pkg + " (" + (i + 1) + "/" + foundPackages.size() + ")");

                    // 直接打开系统原生卸载界面
                    try {
                        Intent u = new Intent(Intent.ACTION_DELETE);
                        u.setData(Uri.parse("package:" + pkg));
                        u.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        startActivity(u);
                    } catch (Exception ignored) {}

                    // 等待 5 秒让用户完成当前应用的卸载操作，再继续下一个
                    try { Thread.sleep(5000); } catch (Exception ignored) {}
                }

                // 全部处理完
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dismiss();
                    }
                });
            }
        }).start();
    }

    private void dismiss() {
        resolved = true;
        stopRefresh();
        destroyOverlay();
        finish();
    }

    private void stopRefresh() {
        if (refreshHandler != null && refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
    }

    private void destroyOverlay() {
        stopRefresh();
        if (overlayView != null && wm != null) {
            try { wm.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    @Override
    public void onBackPressed() {
        // 禁止返回键
    }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacksAndMessages(null);
        destroyOverlay();
        super.onDestroy();
    }
}
