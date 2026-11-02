package com.youlong.hd;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;

/**
 * 紧急救援全屏覆盖窗口。
 * 提供：自动倒计时救援、手动救援按钮、详细脱困步骤。
 */
public class RescueWindowService extends Service {

    private static final String TAG = "RescueService";
    private static final String CH_ID = "rescue_channel";
    private WindowManager wm;
    private FrameLayout root;
    private String malPkg = "";
    private CountDownTimer timer;
    private boolean done = false;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "救援通道", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            malPkg = intent.getStringExtra("malicious_package");
            if (malPkg == null) malPkg = "";
        }
        showOverlay();
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        if (root != null) return;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D0D1A);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(40, 60, 40, 60);

        // 标题
        TextView title = new TextView(this);
        title.setText("🛡️ 紧急救援模式");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        box.addView(title);

        // 恶意应用信息
        TextView info = new TextView(this);
        info.setText(malPkg.isEmpty()
                ? "已检测到锁机软件正在运行！"
                : "恶意应用：" + malPkg + "\n正在覆盖您的屏幕！");
        info.setTextColor(0xFFFF6666);
        info.setTextSize(15);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 0, 0, 24);
        box.addView(info);

        // 救援按钮
        Button btnDo = new Button(this);
        btnDo.setText("🔧 立即救援（卸载 + 强制停止）");
        btnDo.setTextColor(Color.WHITE);
        btnDo.setTextSize(17);
        btnDo.setBackgroundColor(0xFFD32F2F);
        btnDo.setPadding(30, 20, 30, 20);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dp.bottomMargin = 12;
        btnDo.setLayoutParams(dp);
        btnDo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { executeRescue(); }
        });
        box.addView(btnDo);

        // 跳过
        Button btnSkip = new Button(this);
        btnSkip.setText("我没事，关闭");
        btnSkip.setTextColor(0xFF888888);
        btnSkip.setTextSize(14);
        btnSkip.setBackgroundColor(0xFF222244);
        btnSkip.setPadding(30, 14, 30, 14);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.bottomMargin = 20;
        btnSkip.setLayoutParams(sp);
        btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { destroy(); }
        });
        box.addView(btnSkip);

        // 倒计时
        final TextView cd = new TextView(this);
        cd.setText("10 秒后自动救援");
        cd.setTextColor(0xFFFFCC00);
        cd.setTextSize(13);
        cd.setGravity(Gravity.CENTER);
        cd.setPadding(0, 0, 0, 20);
        box.addView(cd);

        // 步骤面板（初始隐藏）
        final ScrollView scroll = new ScrollView(this);
        scroll.setVisibility(View.GONE);
        scroll.setPadding(0, 10, 0, 0);
        final TextView stepsTv = new TextView(this);
        stepsTv.setTextColor(0xFFCCCCCC);
        stepsTv.setTextSize(13);
        stepsTv.setLineSpacing(4, 1);
        scroll.addView(stepsTv);
        box.addView(scroll);

        // 脱险关闭按钮（初始隐藏）
        final Button btnDone = new Button(this);
        btnDone.setText("✅ 我已脱险，关闭窗口");
        btnDone.setTextColor(Color.WHITE);
        btnDone.setTextSize(16);
        btnDone.setBackgroundColor(0xFF2E7D32);
        btnDone.setPadding(30, 16, 30, 16);
        btnDone.setVisibility(View.GONE);
        LinearLayout.LayoutParams dnp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dnp.topMargin = 16;
        btnDone.setLayoutParams(dnp);
        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { destroy(); }
        });
        box.addView(btnDone);

        root.addView(box);
        wm.addView(root, lp);

        // 保存引用
        root.setTag(new Object[]{btnDo, btnSkip, cd, scroll, stepsTv, btnDone});

        // 倒计时
        timer = new CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long left) {
                if (!done) {
                    cd.setText((left / 1000) + " 秒后自动救援");
                    if (left <= 5000) cd.setTextColor(0xFFFF4444);
                }
            }
            @Override
            public void onFinish() {
                if (!done) executeRescue();
            }
        }.start();
    }

    private void executeRescue() {
        if (done) return;
        done = true;
        if (timer != null) { timer.cancel(); timer = null; }
        Log.w(TAG, "Executing rescue for: " + malPkg);

        // 1. 卸载
        if (!malPkg.isEmpty()) {
            try {
                Intent u = new Intent(Intent.ACTION_DELETE);
                u.setData(Uri.parse("package:" + malPkg));
                u.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(u);
            } catch (Exception ignored) {}
        }

        // 2. 杀进程
        if (!malPkg.isEmpty()) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) am.killBackgroundProcesses(malPkg);
            } catch (Exception ignored) {}
        }

        // 3. 通知跳转强制停止
        if (!malPkg.isEmpty()) {
            try {
                Intent d = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                d.setData(Uri.parse("package:" + malPkg));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pi = PendingIntent.getActivity(this, 3, d,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                } else {
                    pi = PendingIntent.getActivity(this, 3, d,
                            PendingIntent.FLAG_UPDATE_CURRENT);
                }
                Notification nf = new NotificationCompat.Builder(this, CH_ID)
                        .setContentTitle("🛡️ 强制停止 " + malPkg)
                        .setContentText("点击跳转应用详情 → 强制停止")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(1003, nf);
            } catch (Exception ignored) {}
        }

        // 4. 展示脱困步骤
        showSteps();
    }

    private void showSteps() {
        if (root == null) return;
        Object[] tag = (Object[]) root.getTag();
        if (tag == null) return;

        View btnDo = (View) tag[0];
        View btnSkip = (View) tag[1];
        View cd = (View) tag[2];
        View scroll = (View) tag[3];
        TextView stepsTv = (TextView) tag[4];
        View btnDone = (View) tag[5];

        if (btnDo != null) btnDo.setVisibility(View.GONE);
        if (btnSkip != null) btnSkip.setVisibility(View.GONE);
        if (cd != null) cd.setVisibility(View.GONE);

        String mfr = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        Map<String, String> safeMode = new HashMap<String, String>() {{
            put("samsung","关机 → 按 音量上+Bixby+电源键 → logo后松开");
            put("xiaomi","关机 → 音量下+电源键 → Fastboot → 安全模式");
            put("redmi","关机 → 音量下+电源键 → Fastboot → 安全模式");
            put("oppo","关机 → 开机时连续按音量下");
            put("oneplus","关机 → 开机时连续按音量下");
            put("vivo","关机 → 同时按电源键+音量上 → logo后松电源");
            put("huawei","关机 → 长按电源键 → 长按「关机」→ 安全模式");
            put("honor","关机 → 长按电源键 → 长按「关机」→ 安全模式");
        }};
        String guide = safeMode.get(mfr);
        if (guide == null) {
            for (Map.Entry<String,String> e : safeMode.entrySet())
                if (mfr.contains(e.getKey())) { guide = e.getValue(); break; }
        }
        if (guide == null) guide = "关机后开机时连续按音量下键";

        String p = malPkg.isEmpty() ? "【包名】" : malPkg;
        String steps =
            "🔴 步骤1：进入安全模式\n    " + guide + "\n\n"
            + "🟠 步骤2：强制停止\n    设置 → 应用 → " + p + " → 强制停止\n\n"
            + "🟡 步骤3：卸载\n    设置 → 应用 → " + p + " → 卸载\n\n"
            + "🟢 步骤4：ADB 卸载（需电脑）\n    adb uninstall " + p + "\n\n"
            + "🔵 步骤5：检查权限\n"
            + "    设备管理器：设置 → 安全 → 取消可疑管理器\n"
            + "    无障碍服务：设置 → 无障碍 → 关闭\n"
            + "    拨号 *#*#1234#*#* 可中断覆盖层";

        if (stepsTv != null) stepsTv.setText(steps);
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
        if (btnDone != null) btnDone.setVisibility(View.VISIBLE);
    }

    private void destroy() {
        if (timer != null) { timer.cancel(); timer = null; }
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
            root = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        destroy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
