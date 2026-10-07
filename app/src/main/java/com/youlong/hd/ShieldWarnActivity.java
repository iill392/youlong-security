package com.youlong.hd;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 高风险应用拦截弹窗 — 使用 Activity 自身布局（不依赖悬浮窗权限）。
 *
 * 启动方式：
 * 1. 用户点击通知 → PendingIntent 启动（最可靠）
 * 2. Android 10+ 后台 startActivity() → 可能被限制，作为补充
 *
 * 布局特性：
 * - showWhenLocked + turnScreenOn 确保锁屏上覆盖
 * - 倒计时默认执行拦截卸载
 * - 窗口布局在 Activity 的 contentView 上（无需 SYSTEM_ALERT_WINDOW 权限）
 *
 * 模式：
 * - 基础模式（basic）：原有逻辑，系统卸载 + 强制停止
 * - 超级拦截模式（super）：集成 Shizuku，am force-stop + pm uninstall
 * - 极强拦截模式（extreme）：Shizuku 全面 force-stop 全部第三方应用
 * - 终结模式（final）：覆盖层处理，此处仅兜底
 */
public class ShieldWarnActivity extends Activity {

    private static final String TAG = "ShieldWarn";

    private String suspectPkg = "";
    private String reason = "";
    private int warnCount = 1;
    private boolean isVolumeRescue = false;
    private String shieldMode = "basic"; // basic / super / extreme / final
    private boolean resolved = false;
    private TextView cdText;
    private CountDownTimer timer;
    private LinearLayout buttonContainer;
    private LinearLayout rootView;
    private Handler mainHandler;
    private TextView warnTv;
    private TextView reasonTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        suspectPkg = getIntent().getStringExtra("suspect_package");
        if (suspectPkg == null) suspectPkg = "未知应用";
        reason = getIntent().getStringExtra("reason");
        if (reason == null) reason = "检测到高风险应用";
        warnCount = getIntent().getIntExtra("warn_count", 1);
        isVolumeRescue = getIntent().getBooleanExtra("is_volume_rescue", false);
        shieldMode = getIntent().getStringExtra("shield_mode");
        if (shieldMode == null || (!shieldMode.equals("basic") && !shieldMode.equals("super")
                && !shieldMode.equals("extreme") && !shieldMode.equals("final"))) {
            shieldMode = "basic";
        }

        mainHandler = new Handler(Looper.getMainLooper());

        // ===== 最高级别全屏窗口：覆盖锁屏 + 强制亮屏 + 全屏 + 隐藏状态栏导航栏 =====
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        // 沉浸式隐藏系统UI
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        // ===== 构建 UI 作为 Activity 的内容视图 =====
        buildAndShowUI();
    }

    private void buildAndShowUI() {
        final boolean isSuper = "super".equals(shieldMode);
        final boolean isExtreme = "extreme".equals(shieldMode);
        final boolean isShizukuMode = isSuper || isExtreme;

        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setGravity(Gravity.CENTER);
        rootView.setBackgroundColor(isShizukuMode ? 0xDD0D1B3D : 0xDD1B0000);
        rootView.setPadding(40, 60, 40, 60);

        // 倒计时文字
        cdText = new TextView(this);
        if (isSuper) {
            cdText.setText("10 秒后将自动强制停止");
        } else if (isExtreme) {
            cdText.setText("5 秒后将自动全面拦截");
        } else {
            cdText.setText(isVolumeRescue ? "7 秒后将自动卸载前台应用" : "3 秒后将自动拦截卸载");
        }
        cdText.setTextColor(0xFFFFCC00);
        cdText.setTextSize(16);
        cdText.setGravity(Gravity.CENTER);
        cdText.setPadding(0, 0, 0, 10);
        rootView.addView(cdText);

        // 大图标
        TextView icon = new TextView(this);
        if (isShizukuMode) {
            icon.setText("\uD83D\uDEE1\uFE0F");
        } else {
            icon.setText("\u26A0\uFE0F");
        }
        icon.setTextSize(64);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 6);
        rootView.addView(icon);

        // 标题
        TextView title = new TextView(this);
        if (isSuper) {
            title.setText("超级拦截 · 强制停止");
        } else if (isExtreme) {
            title.setText("极强拦截 · 全面停止");
        } else {
            title.setText(isVolumeRescue ? "是否拦截前台应用？" : "检测到高风险应用！");
        }
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        rootView.addView(title);

        // 包名
        TextView pkgTv = new TextView(this);
        pkgTv.setText("应用：" + suspectPkg);
        pkgTv.setTextColor(0xFFFFCC00);
        pkgTv.setTextSize(16);
        pkgTv.setGravity(Gravity.CENTER);
        pkgTv.setPadding(0, 0, 0, 6);
        rootView.addView(pkgTv);

        // 原因
        reasonTv = new TextView(this);
        reasonTv.setText(reason);
        reasonTv.setTextColor(0xFFFF8888);
        reasonTv.setTextSize(14);
        reasonTv.setGravity(Gravity.CENTER);
        reasonTv.setPadding(0, 0, 0, 20);
        rootView.addView(reasonTv);

        // 危险提示
        warnTv = new TextView(this);
        if (isSuper) {
            warnTv.setText("通过 Shizuku 执行 am force-stop 强制停止应用\n如不操作将在10秒后自动执行");
        } else if (isExtreme) {
            warnTv.setText("通过 Shizuku 获取全部第三方应用并逐个强制停止\n如不操作将在5秒后自动执行（自动跳过游龙安全护盾）");
        } else if (isVolumeRescue) {
            warnTv.setText("检测到三击音量键逃脱触发，是否立即卸载前台应用？\n如不操作将在7秒后自动卸载");
        } else {
            warnTv.setText("此应用包含高风险权限，可能危害您的设备！\n请选择是否拦截卸载：");
        }
        warnTv.setTextColor(0xFFFFCCCC);
        warnTv.setTextSize(15);
        warnTv.setGravity(Gravity.CENTER);
        warnTv.setPadding(20, 16, 20, 22);
        warnTv.setBackgroundColor(isShizukuMode ? 0x441566C0 : 0x44FF0000);
        rootView.addView(warnTv);

        // ===== 按钮容器 =====
        buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setGravity(Gravity.CENTER);

        // ===== 立即强制停止 / 立即拦截 / 是，拦截卸载 =====
        Button btnIntercept = new Button(this);
        if (isSuper) {
            btnIntercept.setText("立即强制停止");
        } else if (isExtreme) {
            btnIntercept.setText("立即全面拦截");
        } else {
            btnIntercept.setText(isVolumeRescue ? "立即拦截" : "是，拦截卸载");
        }
        btnIntercept.setTextColor(Color.WHITE);
        btnIntercept.setTextSize(18);
        btnIntercept.setBackgroundColor(0xFFD32F2F);
        btnIntercept.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.bottomMargin = 12;
        btnIntercept.setLayoutParams(ip);
        btnIntercept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 极强拦截/终结模式：全面拦截影响所有第三方应用，先二次确认防止误触
                if (isExtreme || "final".equals(shieldMode)) {
                    showDangerConfirm("确认全面拦截？",
                            "将强制停止所有第三方应用（受保护应用除外）",
                            "确认全面拦截", new Runnable() {
                        @Override
                        public void run() { doRescue(); }
                    });
                } else {
                    doRescue();
                }
            }
        });
        buttonContainer.addView(btnIntercept);

        // ===== 取消 / 否，信任此应用 =====
        Button btnTrust = new Button(this);
        if (isSuper) {
            btnTrust.setText("取消");
        } else {
            btnTrust.setText(isVolumeRescue ? "取消" : "否，信任此应用");
        }
        btnTrust.setTextColor(Color.WHITE);
        btnTrust.setTextSize(15);
        btnTrust.setBackgroundColor(0xFF2E7D32);
        btnTrust.setPadding(30, 18, 30, 18);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnTrust.setLayoutParams(tp);
        btnTrust.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { doTrustAndDismiss(); }
        });
        buttonContainer.addView(btnTrust);
        rootView.addView(buttonContainer);

        setContentView(rootView);

        Log.i(TAG, "ShieldWarnActivity UI 已显示, pkg=" + suspectPkg
                + ", isVolumeRescue=" + isVolumeRescue + ", shieldMode=" + shieldMode);

        // ===== 启动倒计时 → 默认拦截 =====
        final long cdMs;
        if (isSuper) {
            cdMs = 10000;
        } else if (isExtreme) {
            cdMs = 5000;
        } else {
            cdMs = isVolumeRescue ? 7000 : 3000;
        }
        timer = new CountDownTimer(cdMs, 1000) {
            @Override
            public void onTick(long left) {
                String secStr;
                if (isSuper) {
                    secStr = (left / 1000) + " 秒后将自动强制停止";
                } else if (isExtreme) {
                    secStr = (left / 1000) + " 秒后将自动全面拦截";
                } else if (isVolumeRescue) {
                    secStr = (left / 1000) + " 秒后将自动卸载前台应用";
                } else {
                    secStr = (left / 1000) + " 秒后将自动拦截卸载";
                }
                cdText.setText(secStr);
                if (left <= 2000) cdText.setTextColor(0xFFFF4444);
            }
            @Override
            public void onFinish() {
                if (!resolved) doRescue();
            }
        }.start();
    }

    // ===== 执行拦截卸载 =====
    private void doRescue() {
        if (resolved) return;
        resolved = true;
        if (timer != null) { timer.cancel(); timer = null; }

        final String pkg = suspectPkg;
        if (pkg.isEmpty() || pkg.equals("未知应用")) {
            finish();
            return;
        }

        // ===== 保护检查：受保护应用（自己/桌面宠物/游龙工具/白名单）直接关闭，绝不执行任何拦截/卸载/冻结 =====
        if (isProtectedApp(pkg)) {
            Log.w(TAG, "受保护应用，跳过拦截: " + pkg);
            cdText.setText("⚠ 受保护应用，已跳过拦截: " + pkg);
            cdText.setTextColor(0xFFFF4444);
            cdText.setTextSize(16);
            buttonContainer.removeAllViews();
            Button btnDone = new Button(ShieldWarnActivity.this);
            btnDone.setText("关闭");
            btnDone.setTextColor(Color.WHITE);
            btnDone.setTextSize(18);
            btnDone.setBackgroundColor(0xFF2E7D32);
            btnDone.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnDone.setLayoutParams(dp);
            btnDone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { finish(); }
            });
            buttonContainer.addView(btnDone);
            return;
        }

        final boolean isSuper = "super".equals(shieldMode);
        final boolean isExtreme = "extreme".equals(shieldMode);

        if (isSuper) {
            // ===== 超级拦截模式：Shizuku am force-stop → 询问卸载 → pm uninstall =====
            doSuperIntercept(pkg);
        } else if (isExtreme) {
            // ===== 极强拦截模式：pm list packages -3 → 逐个 am force-stop =====
            doExtremeIntercept();
        } else if ("final".equals(shieldMode)) {
            // ===== 终结模式（兜底）：同样执行全面 force-stop =====
            doExtremeIntercept();
        } else if (isVolumeRescue) {
            cdText.setText("正在启动系统卸载...");
            cdText.setTextColor(0xFFFFCC00);
            buttonContainer.removeAllViews();

            try {
                Intent u = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                u.setData(Uri.parse("package:" + pkg));
                u.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                u.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(u);
            } catch (Exception ignored) {}

            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isAppInstalled(pkg)) {
                        showRescueUI(pkg);
                    } else {
                        finish();
                    }
                }
            }, 3000);
        } else {
            forceStopPackage(pkg);
            bringYouLongToFront();

            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    bringYouLongToFront();
                    try {
                        Intent u = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                        u.setData(Uri.parse("package:" + pkg));
                        u.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                        u.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(u);
                    } catch (Exception ignored) {}
                    finish();
                    scheduleUninstallRetry(pkg, 5000);
                }
            }, 400);
        }
    }

    // ===== 极强拦截模式：获取全部第三方应用并逐个强制停止 =====
    private void doExtremeIntercept() {
        cdText.setText("正在获取全部第三方应用...");
        cdText.setTextColor(0xFFFFCC00);
        buttonContainer.removeAllViews();
        showCloseButton(); // 执行中也可随时安全关闭

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 第1步：pm list packages -3 获取全部第三方包名
                String listResult;
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    listResult = StellarUtils.runCommand("pm list packages -3", 15000);
                } else {
                    listResult = execShell("pm list packages -3");
                }

                // 第2步：解析包名，过滤 com.youlong.hd
                final java.util.List<String> pkgs = new java.util.ArrayList<>();
                if (listResult != null) {
                    String[] lines = listResult.split("\n");
                    for (String line : lines) {
                        String t = line.trim();
                        if (t.startsWith("package:")) {
                            String name = t.substring(8).trim();
                            if (name.isEmpty()) continue;
                            if (isProtectedApp(name)) continue; // 过滤自己 + 桌面宠物 + 游龙工具 + 白名单
                            pkgs.add(name);
                        }
                    }
                }

                // 第3步：串联 am force-stop 命令
                String cmd = "";
                if (!pkgs.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < pkgs.size(); i++) {
                        if (i > 0) sb.append("; ");
                        sb.append("am force-stop ").append(pkgs.get(i));
                    }
                    cmd = sb.toString();
                }

                // 第4步：执行串联命令
                String result = "OK";
                if (!cmd.isEmpty()) {
                    if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                        result = StellarUtils.runCommand(cmd, 60000);
                    } else {
                        result = execShell(cmd);
                    }
                }
                Log.w(TAG, "极强拦截全面 force-stop 结果: " + result + " (共" + pkgs.size() + "个应用)");

                final int stopCount = pkgs.size();
                final boolean hasForeground = !suspectPkg.isEmpty() && !suspectPkg.equals("未知应用");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        buttonContainer.removeAllViews();
                        // 全面停止完成提示
                        if (stopCount == 0) {
                            cdText.setText("已全面停止（未发现第三方应用）");
                        } else {
                            cdText.setText("已强制停止 " + stopCount + " 个第三方应用");
                        }
                        cdText.setTextColor(0xFF4CAF50);
                        cdText.setTextSize(16);

                        if (hasForeground && isProtectedApp(suspectPkg)) {
                            // 前台应用为受保护应用（自己/桌面宠物/游龙工具/白名单）→ 直接提示并关闭，绝不卸载/冻结
                            final String fg = suspectPkg;
                            if (warnTv != null) {
                                warnTv.setText("前台应用为受保护应用，已自动跳过（绝不卸载/冻结）: " + fg);
                                warnTv.setTextColor(0xFF4CAF50);
                            }
                            buttonContainer.removeAllViews();
                            Button btnDone = new Button(ShieldWarnActivity.this);
                            btnDone.setText("关闭");
                            btnDone.setTextColor(Color.WHITE);
                            btnDone.setTextSize(18);
                            btnDone.setBackgroundColor(0xFF2E7D32);
                            btnDone.setPadding(40, 24, 40, 24);
                            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            btnDone.setLayoutParams(dp);
                            btnDone.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) { finish(); }
                            });
                            buttonContainer.addView(btnDone);
                        } else if (hasForeground) {
                            // 扫描到前台应用 → 询问是否卸载该前台应用
                            final String fg = suspectPkg;
                            if (warnTv != null) {
                                warnTv.setText("已全面停止，是否卸载前台应用 " + fg + " ？");
                                warnTv.setTextColor(0xFFFFCC00);
                            }

                            // 立即卸载按钮
                            Button btnUninstall = new Button(ShieldWarnActivity.this);
                            btnUninstall.setText("立即卸载");
                            btnUninstall.setTextColor(Color.WHITE);
                            btnUninstall.setTextSize(18);
                            btnUninstall.setBackgroundColor(0xFFD32F2F);
                            btnUninstall.setPadding(40, 24, 40, 24);
                            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            up.bottomMargin = 12;
                            btnUninstall.setLayoutParams(up);
                            btnUninstall.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    showDangerConfirm("确认卸载？",
                                            "卸载后应用数据将被删除，不可恢复！\n" + fg,
                                            "确认卸载", new Runnable() {
                                        @Override
                                        public void run() { executeUninstall(fg); }
                                    });
                                }
                            });
                            buttonContainer.addView(btnUninstall);

                            // 不卸载按钮
                            Button btnKeep = new Button(ShieldWarnActivity.this);
                            btnKeep.setText("不卸载，关闭");
                            btnKeep.setTextColor(Color.WHITE);
                            btnKeep.setTextSize(15);
                            btnKeep.setBackgroundColor(0xFF2E7D32);
                            btnKeep.setPadding(30, 18, 30, 18);
                            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            btnKeep.setLayoutParams(kp);
                            btnKeep.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (timer != null) { timer.cancel(); timer = null; }
                                    showFreezeDialog(fg);
                                }
                            });
                            buttonContainer.addView(btnKeep);

                            // 5 秒倒计时：无操作则自动安全关闭（绝不自动卸载，防止误触不可逆操作）
                            timer = new CountDownTimer(5000, 1000) {
                                @Override
                                public void onTick(long left) {
                                    cdText.setText((left / 1000) + " 秒后无操作将自动关闭");
                                    if (left <= 2000) cdText.setTextColor(0xFFFF4444);
                                }
                                @Override
                                public void onFinish() {
                                    safeFinish();
                                }
                            }.start();
                        } else {
                            // 未扫描到前台应用 → 直接关闭
                            Button btnDone = new Button(ShieldWarnActivity.this);
                            btnDone.setText("关闭");
                            btnDone.setTextColor(Color.WHITE);
                            btnDone.setTextSize(18);
                            btnDone.setBackgroundColor(0xFF2E7D32);
                            btnDone.setPadding(40, 24, 40, 24);
                            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            btnDone.setLayoutParams(dp);
                            btnDone.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) { finish(); }
                            });
                            buttonContainer.addView(btnDone);
                        }
                    }
                });
            }
        }).start();
    }

    // ===== 无 Shizuku 时回退执行 shell 命令并返回输出 =====
    private String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "execShell 失败: " + cmd, e);
            return "ERROR:" + e.getMessage();
        }
    }

    // ===== 判断是否受保护应用（自己 + 桌面宠物 + 游龙工具 + 白名单），任何卸载/冻结/停止前都必须过滤 =====
    // 2026-10 修复（白名单没效果）：改用 WhitelistActivity.isWhitelisted 统一判定。
    // 旧实现只读 whitelist_pkgs 原始串，漏掉两类信任包：
    //   1) 内置默认信任包（微信/QQ/支付宝/拼多多等）——它们不写入 whitelist_pkgs，
    //      导致音量键救援 7 秒倒计时后对它们照样拉起系统卸载框；
    //   2) 历史遗留信任包（com.larus.nova / com.smile.gifmaker）。
    private boolean isProtectedApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(getPackageName())) return true;   // 自己
        if (pkg.equals("com.youlong.hd")) return true;  // 硬编码双保险
        if (pkg.equals("com.youlong.zoo")) return true; // 桌面宠物保留
        if (pkg.equals("com.youlong.tool")) return true; // 游龙工具（自家应用保留，绝不冻结/卸载）
        // 白名单应用不处理（内置默认 + 历史遗留 + 用户自定义，按条目精确匹配）
        return WhitelistActivity.isWhitelisted(this, pkg);
    }

    // ===== 超级拦截模式 =====
    private void doSuperIntercept(final String pkg) {
        // ===== 保护检查：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不允许强制停止 =====
        if (isProtectedApp(pkg)) {
            Log.w(TAG, "受保护应用，禁止强制停止: " + pkg);
            cdText.setText("⚠ 受保护应用，禁止强制停止: " + pkg);
            cdText.setTextColor(0xFFFF4444);
            buttonContainer.removeAllViews();
            Button btnClose = new Button(ShieldWarnActivity.this);
            btnClose.setText("关闭");
            btnClose.setTextColor(Color.WHITE);
            btnClose.setTextSize(18);
            btnClose.setBackgroundColor(0xFF2E7D32);
            btnClose.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnClose.setLayoutParams(ccp);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { finish(); }
            });
            buttonContainer.addView(btnClose);
            return;
        }
        cdText.setText("正在通过 Shizuku 强制停止...");
        cdText.setTextColor(0xFFFFCC00);
        buttonContainer.removeAllViews();
        showCloseButton(); // 执行中也可随时安全关闭

        // 在后台线程执行 am force-stop
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result = StellarUtils.runCommand("am force-stop " + pkg, 10000);
                } else {
                    // Shizuku 不可用，回退到 Runtime.exec
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop " + pkg}).waitFor();
                        result = "OK(fallback)";
                    } catch (Exception e) {
                        result = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "超级拦截 force-stop 结果: " + result);

                // 回到主线程显示卸载弹窗
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showUninstallDialog(pkg);
                    }
                });
            }
        }).start();
    }

    // ===== 超级拦截模式：显示是否卸载弹窗（5秒倒计时自动卸载）=====
    private void showUninstallDialog(final String pkg) {
        // ===== 保护检查：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不进入卸载询问 =====
        if (isProtectedApp(pkg)) {
            Log.w(TAG, "受保护应用，跳过卸载询问: " + pkg);
            cdText.setText("⚠ 受保护应用，禁止卸载: " + pkg);
            cdText.setTextColor(0xFFFF4444);
            cdText.setTextSize(16);
            if (reasonTv != null) {
                reasonTv.setText("此应用为受保护应用（自己/桌面宠物/游龙工具/白名单）");
                reasonTv.setTextColor(0xFFFF4444);
            }
            if (warnTv != null) {
                warnTv.setText("受保护应用不会被执行卸载/冻结操作");
                warnTv.setTextColor(0xFFFFCCCC);
            }
            buttonContainer.removeAllViews();
            Button btnClose = new Button(this);
            btnClose.setText("关闭");
            btnClose.setTextColor(Color.WHITE);
            btnClose.setTextSize(18);
            btnClose.setBackgroundColor(0xFF2E7D32);
            btnClose.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnClose.setLayoutParams(cp);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { finish(); }
            });
            buttonContainer.addView(btnClose);
            return;
        }
        // 更新 UI
        cdText.setText("5 秒后将自动卸载");
        cdText.setTextColor(0xFFFF4444);
        cdText.setTextSize(18);

        // 更新包名显示
        if (rootView.getChildCount() >= 4) {
            View pkgView = rootView.getChildAt(3);
            if (pkgView instanceof TextView) {
                ((TextView) pkgView).setText("已强制停止：" + pkg);
                ((TextView) pkgView).setTextColor(0xFF4CAF50);
            }
        }

        // 更新原因
        if (reasonTv != null) {
            reasonTv.setText("am force-stop 已执行，是否继续卸载此应用？");
            reasonTv.setTextColor(0xFFFFCC00);
        }

        // 更新警告文字
        if (warnTv != null) {
            warnTv.setText("应用已被强制停止，建议立即卸载\n如不操作将在5秒后自动卸载");
            warnTv.setTextColor(0xFFFFCCCC);
        }

        // 重建按钮
        buttonContainer.removeAllViews();

        // 卸载按钮
        Button btnUninstall = new Button(this);
        btnUninstall.setText("立即卸载");
        btnUninstall.setTextColor(Color.WHITE);
        btnUninstall.setTextSize(18);
        btnUninstall.setBackgroundColor(0xFFD32F2F);
        btnUninstall.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.bottomMargin = 12;
        btnUninstall.setLayoutParams(up);
        btnUninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDangerConfirm("确认卸载？",
                        "卸载后应用数据将被删除，不可恢复！\n" + pkg,
                        "确认卸载", new Runnable() {
                    @Override
                    public void run() { executeUninstall(pkg); }
                });
            }
        });
        buttonContainer.addView(btnUninstall);

        // 不卸载按钮
        Button btnKeep = new Button(this);
        btnKeep.setText("不卸载，关闭");
        btnKeep.setTextColor(Color.WHITE);
        btnKeep.setTextSize(15);
        btnKeep.setBackgroundColor(0xFF2E7D32);
        btnKeep.setPadding(30, 18, 30, 18);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnKeep.setLayoutParams(kp);
        btnKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (timer != null) { timer.cancel(); timer = null; }
                showFreezeDialog(pkg);
            }
        });
        buttonContainer.addView(btnKeep);

        // 5 秒倒计时：无操作则自动安全关闭（绝不自动卸载，防止误触不可逆操作）
        timer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long left) {
                cdText.setText((left / 1000) + " 秒后无操作将自动关闭");
                if (left <= 2000) cdText.setTextColor(0xFFFF4444);
            }
            @Override
            public void onFinish() {
                safeFinish();
            }
        }.start();
    }

    // ===== 冻结询问阶段（卸载被取消后）：20秒倒计时自动执行 pm disable-user =====
    private void showFreezeDialog(final String pkg) {
        // ===== 保护检查：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不进入冻结询问 =====
        if (isProtectedApp(pkg)) {
            Log.w(TAG, "受保护应用，跳过冻结询问: " + pkg);
            cdText.setText("⚠ 受保护应用，禁止冻结: " + pkg);
            cdText.setTextColor(0xFFFF4444);
            cdText.setTextSize(16);
            if (warnTv != null) {
                warnTv.setText("此应用为受保护应用（自己/桌面宠物/游龙工具/白名单）");
                warnTv.setTextColor(0xFFFF4444);
            }
            buttonContainer.removeAllViews();
            Button btnClose = new Button(this);
            btnClose.setText("关闭");
            btnClose.setTextColor(Color.WHITE);
            btnClose.setTextSize(18);
            btnClose.setBackgroundColor(0xFF2E7D32);
            btnClose.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnClose.setLayoutParams(cp);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { finish(); }
            });
            buttonContainer.addView(btnClose);
            return;
        }
        // 更新 UI → 询问是否冻结
        if (warnTv != null) {
            warnTv.setText("是否冻结此应用 " + pkg + " ？");
            warnTv.setTextColor(0xFFFFCC00);
        }
        cdText.setText("20 秒后将自动冻结");
        cdText.setTextColor(0xFFFFCC00);
        cdText.setTextSize(16);
        buttonContainer.removeAllViews();

        // 立即冻结按钮
        Button btnFreeze = new Button(this);
        btnFreeze.setText("立即冻结");
        btnFreeze.setTextColor(Color.WHITE);
        btnFreeze.setTextSize(18);
        btnFreeze.setBackgroundColor(0xFFD32F2F);
        btnFreeze.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.bottomMargin = 12;
        btnFreeze.setLayoutParams(fp);
        btnFreeze.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDangerConfirm("确认冻结？",
                        "冻结后应用将无法使用，可在系统设置的应用管理中启用恢复！\n" + pkg,
                        "确认冻结", new Runnable() {
                    @Override
                    public void run() { executeDisable(pkg); }
                });
            }
        });
        buttonContainer.addView(btnFreeze);

        // 不冻结按钮
        Button btnNoFreeze = new Button(this);
        btnNoFreeze.setText("不冻结，关闭");
        btnNoFreeze.setTextColor(Color.WHITE);
        btnNoFreeze.setTextSize(15);
        btnNoFreeze.setBackgroundColor(0xFF2E7D32);
        btnNoFreeze.setPadding(30, 18, 30, 18);
        LinearLayout.LayoutParams nfp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnNoFreeze.setLayoutParams(nfp);
        btnNoFreeze.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        buttonContainer.addView(btnNoFreeze);

        // 20 秒倒计时：无操作则自动安全关闭（绝不自动冻结，防止误触不可逆操作）
        timer = new CountDownTimer(20000, 1000) {
            @Override
            public void onTick(long left) {
                cdText.setText((left / 1000) + " 秒后无操作将自动关闭");
                if (left <= 3000) cdText.setTextColor(0xFFFF4444);
            }
            @Override
            public void onFinish() {
                safeFinish();
            }
        }.start();
    }

    // ===== 执行 pm disable-user（冻结）=====
    private void executeDisable(final String pkg) {
        // ===== 终极保险：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不允许冻结 =====
        if (isProtectedApp(pkg)) {
            Log.w(TAG, "受保护应用，禁止冻结: " + pkg);
            cdText.setText("⚠ 受保护应用，禁止冻结: " + pkg);
            cdText.setTextColor(0xFFFF4444);
            buttonContainer.removeAllViews();
            Button btnDone = new Button(ShieldWarnActivity.this);
            btnDone.setText("关闭");
            btnDone.setTextColor(Color.WHITE);
            btnDone.setTextSize(18);
            btnDone.setBackgroundColor(0xFF2E7D32);
            btnDone.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnDone.setLayoutParams(dp);
            btnDone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { finish(); }
            });
            buttonContainer.addView(btnDone);
            return;
        }
        if (timer != null) { timer.cancel(); timer = null; }
        cdText.setText("正在冻结...");
        cdText.setTextColor(0xFFFFCC00);
        buttonContainer.removeAllViews();
        showCloseButton(); // 执行中也可随时安全关闭

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] result = new String[1];
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result[0] = StellarUtils.runCommand("pm disable-user --user 0 " + pkg, 15000);
                } else {
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm disable-user --user 0 " + pkg}).waitFor();
                        result[0] = "OK(fallback)";
                    } catch (Exception e) {
                        result[0] = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "冻结 pm disable-user 结果: " + result[0]);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        boolean disabled = isAppDisabled(pkg);
                        if (disabled || (result[0] != null && (result[0].contains("disabled") || result[0].contains("Success")))) {
                            cdText.setText("✅ 已冻结 " + pkg);
                            cdText.setTextColor(0xFF4CAF50);
                            if (warnTv != null) {
                                warnTv.setText("该应用已被冻结，可在设置中解冻");
                                warnTv.setTextColor(0xFF4CAF50);
                            }
                        } else {
                            cdText.setText("⚠ 冻结可能失败，请手动处理");
                            cdText.setTextColor(0xFFFF4444);
                        }
                        buttonContainer.removeAllViews();
                        Button btnDone = new Button(ShieldWarnActivity.this);
                        btnDone.setText("关闭");
                        btnDone.setTextColor(Color.WHITE);
                        btnDone.setTextSize(18);
                        btnDone.setBackgroundColor(0xFF2E7D32);
                        btnDone.setPadding(40, 24, 40, 24);
                        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        btnDone.setLayoutParams(dp);
                        btnDone.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) { finish(); }
                        });
                        buttonContainer.addView(btnDone);
                    }
                });
            }
        }).start();
    }

    // ===== 检查应用是否被禁用 =====
    private boolean isAppDisabled(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return !ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    // ===== 执行卸载（超级拦截模式：Shizuku pm uninstall）=====
    private void executeUninstall(final String pkg) {
        // ===== 终极保险：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不允许卸载 =====
        if (isProtectedApp(pkg)) {
            Log.w(TAG, "受保护应用，禁止卸载: " + pkg);
            cdText.setText("⚠ 受保护应用，禁止卸载: " + pkg);
            cdText.setTextColor(0xFFFF4444);
            buttonContainer.removeAllViews();
            Button btnDone = new Button(ShieldWarnActivity.this);
            btnDone.setText("关闭");
            btnDone.setTextColor(Color.WHITE);
            btnDone.setTextSize(18);
            btnDone.setBackgroundColor(0xFF2E7D32);
            btnDone.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnDone.setLayoutParams(dp);
            btnDone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { finish(); }
            });
            buttonContainer.addView(btnDone);
            return;
        }
        if (timer != null) { timer.cancel(); timer = null; }
        cdText.setText("正在卸载...");
        cdText.setTextColor(0xFFFFCC00);
        buttonContainer.removeAllViews();
        showCloseButton(); // 执行中也可随时安全关闭

        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result = StellarUtils.runCommand("pm uninstall " + pkg, 15000);
                } else {
                    // Shizuku 不可用，回退到系统卸载 Intent
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm uninstall " + pkg}).waitFor();
                        result = "OK(fallback)";
                    } catch (Exception e) {
                        result = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "超级拦截 pm uninstall 结果: " + result);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        // 检查是否卸载成功
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (isAppInstalled(pkg)) {
                                    // 仍在 → 尝试系统卸载
                                    showRescueUI(pkg);
                                } else {
                                    cdText.setText("卸载成功！");
                                    cdText.setTextColor(0xFF4CAF50);
                                    if (warnTv != null) {
                                        warnTv.setText(pkg + " 已成功卸载");
                                        warnTv.setTextColor(0xFF4CAF50);
                                    }
                                    mainHandler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() { finish(); }
                                    }, 2000);
                                }
                            }
                        }, 1500);
                    }
                });
            }
        }).start();
    }

    private void showRescueUI(final String pkg) {
        buttonContainer.removeAllViews();
        cdText.setText("紧急脱险");
        cdText.setTextColor(0xFFFF4444);
        cdText.setTextSize(22);

        if (rootView.getChildCount() >= 6) {
            View maybeWarn = rootView.getChildAt(6);
            if (maybeWarn instanceof TextView) {
                ((TextView) maybeWarn).setText("系统卸载未成功，请手动卸载该应用：");
                ((TextView) maybeWarn).setTextColor(0xFFFFCCCC);
            }
        }

        Button btnUninstall = new Button(this);
        btnUninstall.setText("卸载应用");
        btnUninstall.setTextColor(Color.WHITE);
        btnUninstall.setTextSize(18);
        btnUninstall.setBackgroundColor(0xFFD32F2F);
        btnUninstall.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.bottomMargin = 12;
        btnUninstall.setLayoutParams(up);
        btnUninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    s.setData(Uri.parse("package:" + pkg));
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(s);
                } catch (Exception ignored) {}
            }
        });
        buttonContainer.addView(btnUninstall);

        Button btnSafe = new Button(this);
        btnSafe.setText("我已脱险");
        btnSafe.setTextColor(Color.WHITE);
        btnSafe.setTextSize(18);
        btnSafe.setBackgroundColor(0xFF4CAF50);
        btnSafe.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnSafe.setLayoutParams(sp);
        btnSafe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        buttonContainer.addView(btnSafe);
    }

    private boolean isAppInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void doTrustAndDismiss() {
        if (resolved) return;
        resolved = true;
        if (timer != null) { timer.cancel(); timer = null; }
        if (!isVolumeRescue) addToWhitelist(suspectPkg);
        finish();
    }

    private void addToWhitelist(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals("未知应用")) return;
        try {
            SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
            String raw = prefs.getString("whitelist_pkgs", "");
            // 2026-10 修复：去重必须按条目精确比较，不能用 raw.contains(pkg) 子串匹配。
            // 旧写法在新增包名是已有条目的子串时（如已有 "com.a.bb" 再加 "com.a.b"）
            // 会误判"已在白名单"而静默不写入 —— 用户点"信任此应用"却没生效。
            if (raw != null && !raw.isEmpty()) {
                for (String p : raw.split(",")) {
                    if (pkg.equals(p.trim())) return; // 已存在，无需重复添加
                }
            }
            StringBuilder sb = new StringBuilder(raw == null ? "" : raw);
            if (sb.length() > 0) sb.append(",");
            sb.append(pkg);
            prefs.edit().putString("whitelist_pkgs", sb.toString()).apply();
            Log.d(TAG, "已将 " + pkg + " 加入白名单");
        } catch (Exception e) {
            Log.e(TAG, "addToWhitelist error", e);
        }
    }

    private void bringYouLongToFront() {
        try {
            Intent self = new Intent(this, com.youlong.hd.MainActivity.class);
            self.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(self);
        } catch (Exception ignored) {}
    }

    private void forceStopPackage(String pkg) {
        try { Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop " + pkg}).waitFor(); } catch (Exception ignored) {}
        try { ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE); if (am != null) am.killBackgroundProcesses(pkg); } catch (Exception ignored) {}
    }

    private void scheduleUninstallRetry(final String pkg, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    getPackageManager().getPackageInfo(pkg, 0);
                    forceStopPackage(pkg);
                    Intent u = new Intent(Intent.ACTION_DELETE);
                    u.setData(Uri.parse("package:" + pkg));
                    u.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    startActivity(u);
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                getPackageManager().getPackageInfo(pkg, 0);
                                Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                s.setData(Uri.parse("package:" + pkg));
                                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(s);
                            } catch (PackageManager.NameNotFoundException ignored) {} catch (Exception ignored) {}
                        }
                    }, 8000);
                } catch (PackageManager.NameNotFoundException ignored) {} catch (Exception ignored) {}
            }
        }, delayMs);
    }

    // ===== 显示关闭按钮（每个页面都有关闭按钮，点击安全退出，不执行任何拦截操作）=====
    private void showCloseButton() { showCloseButton(null); }

    // ===== 显示关闭按钮（每个页面都有关闭按钮，点击安全退出，不执行任何拦截操作）=====
    private void showCloseButton(String text) {
        Button btnClose = new Button(this);
        btnClose.setText(text == null ? "关闭" : text);
        btnClose.setTextColor(Color.WHITE);
        btnClose.setTextSize(18);
        btnClose.setBackgroundColor(0xFF455A64);
        btnClose.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnClose.setLayoutParams(ccp);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { safeFinish(); }
        });
        buttonContainer.addView(btnClose);
    }

    // ===== 不可逆操作二次确认：确认后执行 action，否则安全关闭（防止误触产生不可逆伤害）=====
    private void showDangerConfirm(String title, String desc, String confirmText, final Runnable action) {
        if (timer != null) { timer.cancel(); timer = null; }
        cdText.setText(title);
        cdText.setTextColor(0xFFFF4444);
        cdText.setTextSize(18);
        if (reasonTv != null) {
            reasonTv.setText(desc);
            reasonTv.setTextColor(0xFFFF8888);
        }
        if (warnTv != null) {
            warnTv.setText("⚠ 此操作不可逆，请务必确认！");
            warnTv.setTextColor(0xFFFFCCCC);
        }
        buttonContainer.removeAllViews();

        // 确认执行（红色）
        Button btnConfirm = new Button(this);
        btnConfirm.setText(confirmText);
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(18);
        btnConfirm.setBackgroundColor(0xFFD32F2F);
        btnConfirm.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams ccp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ccp.bottomMargin = 12;
        btnConfirm.setLayoutParams(ccp);
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { action.run(); }
        });
        buttonContainer.addView(btnConfirm);

        // 关闭（不执行）
        showCloseButton("关闭（不执行）");
    }

    // ===== 安全退出：取消一切倒计时与后续动作，直接关闭页面 =====
    private void safeFinish() {
        resolved = true;
        if (timer != null) { timer.cancel(); timer = null; }
        finish();
    }

    @Override
    public void onBackPressed() {}

    @Override
    protected void onDestroy() {
        if (timer != null) { timer.cancel(); timer = null; }
        super.onDestroy();
    }
}
