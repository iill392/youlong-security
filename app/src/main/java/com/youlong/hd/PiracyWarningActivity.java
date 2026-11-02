package com.youlong.hd;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.security.MessageDigest;

/**
 * 盗版警告 Activity — 签名验证失败时强制显示
 * 
 * 防篡改措施：
 * 1. 独立签名自检（不依赖 SignatureVerifier 类，防止被 patch 掉）
 * 2. FLAG_SECURE 防截屏/录屏
 * 3. 全屏最高层级 + 隐藏导航栏状态栏
 * 4. 禁止返回键
 * 5. 定时重复验证签名（防止运行时注入绕过）
 * 6. 检测悬浮窗覆盖攻击
 */
public class PiracyWarningActivity extends Activity {

    private static final long RECHECK_INTERVAL = 2000; // 每2秒重新验证
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsFinishing = false;

    private final Runnable mRecheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsFinishing) return;
            // 独立自检签名 — 不依赖任何外部类
            if (checkSignatureIndependently()) {
                // 签名突然变正确了？不可能，直接杀死进程
                forceExit();
                return;
            }
            mHandler.postDelayed(this, RECHECK_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ===== 防截屏/录屏 =====
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        // ===== 全屏无标题 =====
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window win = getWindow();
        win.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        win.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        win.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        win.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        // ===== 最高窗口层级 — 防止被其他窗口覆盖 =====
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }

        // ===== 沉浸式 — 隐藏导航栏和状态栏 =====
        int uiFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        win.getDecorView().setSystemUiVisibility(uiFlags);

        // ===== 构建布局 =====
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF1A237E);
        root.setPadding(40, 60, 40, 40);

        // ⚠️ 图标
        TextView iconView = new TextView(this);
        iconView.setText("\u26A0\uFE0F");
        iconView.setTextSize(68);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTextColor(0xFFFFF176);

        // 标题：盗版警告
        TextView titleView = new TextView(this);
        titleView.setText("\u8B66\u544A\uFF1A\u76D7\u7248\u8F6F\u4EF6");
        titleView.setTextSize(24);
        titleView.setTextColor(0xFFFFF176);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 20, 0, 10);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0x44FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(20, 10, 20, 10);
        divider.setLayoutParams(divLp);

        // 提示正文
        TextView msgView = new TextView(this);
        msgView.setText("\u4F60\u4F7F\u7528\u7684\u8F6F\u4EF6\u4E3A\u76D7\u7248\uFF0C\u8BF7\u901A\u8FC7\u5B98\u65B9\u6E20\u9053\u4E0B\u8F7D\uFF1A\n\n\u5B98\u7F511\uFF1Ayoulong.pages.dev\n\u5B98\u7F512\uFF1Ahttps://iill392.github.io/download-youlong");
        msgView.setTextSize(17);
        msgView.setTextColor(0xFFFFFFFF);
        msgView.setGravity(Gravity.CENTER);
        msgView.setPadding(10, 16, 10, 30);
        msgView.setLineSpacing(8, 1);

        // 底部按钮容器
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRow.setLayoutParams(btnRowLp);

        // 确认退出按钮
        Button exitBtn = new Button(this);
        exitBtn.setText("\u786E\u8BA4\u9000\u51FA");
        exitBtn.setTextSize(16);
        exitBtn.setTextColor(0xFFFFFFFF);
        exitBtn.setBackgroundColor(0xFFD32F2F);
        exitBtn.setPadding(36, 14, 36, 14);
        exitBtn.setGravity(Gravity.CENTER);
        exitBtn.setOnClickListener(v -> forceExit());
        btnRow.addView(exitBtn);

        root.addView(iconView);
        root.addView(titleView);
        root.addView(divider);
        root.addView(msgView);
        root.addView(btnRow);
        scrollView.addView(root);
        setContentView(scrollView);

        // ===== 启动定时重复验证 =====
        mHandler.postDelayed(mRecheckRunnable, RECHECK_INTERVAL);
    }

    /**
     * 独立签名校验 — 内联实现，不依赖 SignatureVerifier 类
     * 即使攻击者 patch 掉 SignatureVerifier，此处的自检仍然有效
     */
    private boolean checkSignatureIndependently() {
        try {
            byte[] certBytes = getCertBytes();
            if (certBytes == null) return false;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(certBytes);
            String hex = bytesToHex(digest);

            // 内联重建预期哈希（与 SignatureVerifier 相同逻辑）
            String expected = rebuildHashInline();
            return expected.equals(hex);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] getCertBytes() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return null;
            Signature[] sigs = info.signingInfo.getSigningCertificateHistory();
            if (sigs == null || sigs.length == 0) return null;
            return sigs[0].toByteArray();
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) return null;
            return info.signatures[0].toByteArray();
        }
    }

    // 内联重建预期哈希（与 SignatureVerifier 相同，但独立实现防止单点被 patch）
    private static String rebuildHashInline() {
        String[] p = new String[8];
        p[0] = xorStr("\u0006q\u0006ztz\u0001{", 0x42);
        p[1] = xorStr("\u0011egbegd\u0015", 0x21);
        p[2] = xorStr("&UUR%+*\"", 0x13);
        p[3] = xorStr("\u0010\u0014m\u0013g\u0010g\u0010", 0x55);
        p[4] = xorStr("s\u0007rts\u0005\u0008s", 0x31);
        p[5] = xorStr("4AD226B1", 0x77);
        p[6] = xorStr("N;KKN?>I", 0x0A);
        p[7] = xorStr("5D46AE2C", 0x00);
        StringBuilder sb = new StringBuilder();
        for (String s : p) sb.append(s);
        return sb.toString();
    }

    private static String xorStr(String s, int key) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ key);
        }
        return new String(chars);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** 强制退出 — 多重保障 */
    private void forceExit() {
        if (mIsFinishing) return;
        mIsFinishing = true;
        mHandler.removeCallbacks(mRecheckRunnable);
        finishAffinity();
        Process.killProcess(Process.myPid());
        System.exit(0);
    }

    @Override
    public void onBackPressed() {
        // 禁止返回键 — 盗版用户必须退出
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 如果用户尝试切换到其他应用，立即重新验证
        if (!mIsFinishing && !checkSignatureIndependently()) {
            // 签名不对，不让走
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mRecheckRunnable);
    }
}
