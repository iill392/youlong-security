package com.youlong.redteam;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import roro.stellar.Stellar;
import roro.stellar.StellarRemoteProcess;

/**
 * 红队测试工具（仅用于测试自己的设备）：
 *  A. 独立 ADB：自带 RSA key，直接连本机 legacy TCP adbd（不走 Stellar/Shizuku）。
 *  B. Stellar：作为普通 Stellar 客户端调用 newProcess，测试脚本内容拦截。
 *
 * 所有“危险命令”都是 echo 字符串/随机数据，不执行真正的卸载/冻结/删除。
 */
public class MainActivity extends Activity {

    private static final String TAG = "RedTeam";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusView;
    private final StringBuilder report = new StringBuilder();

    private AdbKeyPair adbKey;
    private AdbClient adb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("红队测试（独立 ADB + Stellar）");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("仅测试自己的设备。ADB 模式使用本 App 自己的 RSA key，不依赖游龙内置特权。"
                + "若手机弹出「允许 USB 调试」，请只给本测试 App 授权。");
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, 12);
        root.addView(hint);

        addButton(root, "1. 连接独立 ADB（自带 key）", this::connectAdb);
        addButton(root, "2. ADB 只读探测 + 绕过演示", this::runAdbProbe);
        addButton(root, "3. ADB 防护测试（开无线看是否被关）", this::runAdbGuardTest);
        addButton(root, "4. Stellar 脚本拦截测试", this::runStellarTests);
        addButton(root, "5. 保存/复制报告", this::saveReport);
        addButton(root, "6. 重启测试 App（首次授权后）", this::restartSelf);
        addButton(root, "7. 测试 adbd usb:/tcpip:0", this::testAdbTcpClose);

        statusView = new TextView(this);
        statusView.setTextSize(12);
        statusView.setTextIsSelectable(true);
        statusView.setMovementMethod(new ScrollingMovementMethod());
        statusView.setText("等待测试...\n");
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        try {
            adbKey = AdbKeyPair.loadOrCreate(this);
            append("ADB key fingerprint: " + adbKey.fingerprint + "\n");
        } catch (Throwable t) {
            append("生成 ADB key 失败: " + t + "\n");
        }
    }

    private void addButton(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> runAsync(action));
        parent.addView(b);
    }

    private void runAsync(Runnable action) {
        executor.execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.e(TAG, "task failed", t);
                append("任务失败: " + t + "\n");
            }
        });
    }

    private void append(String text) {
        Log.i(TAG, text);
        main.post(() -> {
            report.append(text);
            statusView.append(text);
        });
    }

    private void setStatus(String text) {
        append(text + "\n");
    }

    // ------------------------------------------------------------------
    // A. 独立 ADB
    // ------------------------------------------------------------------

    private void connectAdb() {
        if (adbKey == null) {
            setStatus("ADB key 未生成");
            return;
        }
        if (adb != null) {
            adb.close();
            adb = null;
        }
        int port = AdbClient.resolvePort();
        if (port <= 0) {
            append("未找到 legacy TCP ADB 端口。\n");
            append("说明：需要设备处于 adb tcpip 模式，或无线调试的 legacy 端口；"
                    + "如果我们的 ADB 防护已关闭该入口，这里连不上属于正常。\n");
            return;
        }
        append("尝试连接 127.0.0.1:" + port + " ...\n");
        append("如手机弹出「允许 USB 调试」，请点允许（只授权本测试 App）。\n");
        try {
            AdbClient client = new AdbClient(adbKey, port);
            client.connect();
            adb = client;
            setStatus("独立 ADB 连接成功：127.0.0.1:" + port);
        } catch (Throwable t) {
            append("独立 ADB 连接失败: " + t + "\n");
            append("可能原因：端口未开放/TLS 无线调试端口/未允许 RSA key/ADB 防护已关闭入口。\n");
        }
    }

    private void runAdbProbe() {
        if (adb == null) {
            connectAdb();
        }
        if (adb == null) {
            append("ADB 未连接，无法探测\n");
            return;
        }
        append("\n===== ADB 只读探测 =====\n");
        runAdb("id");
        runAdb("getprop service.adb.tcp.port");
        runAdb("settings get global adb_wifi_enabled");
        runAdb("settings get global adb_enabled");
        runAdb("pm path com.youlong.hd");
        runAdb("ls -l /data/local/tmp/stellar_guard");
        runAdb("cat /data/local/tmp/stellar_guard/command_whitelist.txt 2>/dev/null");
        append("\n===== 独立 ADB 绕过 Stellar 演示（安全内容） =====\n");
        runAdb("echo youlong");
        runAdb("sh -c 'echo \"pm disable-user --user 0 -a\"; echo \"pm uninstall -a\"; echo \"rm -rf /data/app\"'");
        append("说明：以上命令由独立 ADB shell 执行，Stellar 的脚本策略看不到它。\n");
    }

    private void runAdbGuardTest() {
        if (adb == null) connectAdb();
        if (adb == null) {
            append("ADB 未连接，无法测试 ADB 防护\n");
            return;
        }
        append("\n===== ADB 防护测试 =====\n");
        try {
            append("把无线调试打开：settings put global adb_wifi_enabled 1\n");
            runAdb("settings put global adb_wifi_enabled 1");
            append("等待 8 秒，看游龙 ADB 防护是否自动关回 0...\n");
            Thread.sleep(8000);
            String value = adbShellFresh("settings get global adb_wifi_enabled").trim();
            append("8 秒后 adb_wifi_enabled = " + value + "\n");
            if ("0".equals(value)) {
                append("结果：ADB 防护生效（无线调试被关闭）。\n");
            } else {
                append("结果：ADB 防护未生效/被暂停（当前值 " + value + "）。\n");
                // 避免测试工具把无线调试留在打开状态
                try { adbShellFresh("settings put global adb_wifi_enabled 0"); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            append("ADB 防护测试失败: " + t + "\n");
        }
    }

    private void runAdb(String command) {
        try {
            String out = adbShellFresh(command);
            if (out.length() > 4000) out = out.substring(0, 4000) + "\n...[截断]";
            append("$ " + command + "\n" + out + "\n");
        } catch (Throwable t) {
            append("$ " + command + "\n<失败: " + t + ">\n");
        }
    }

    /**
     * 每条命令用一条新的 ADB 连接。ADB 的 shell 服务是多路复用的，
     * 复用同一连接时旧服务的 WRTE/CLSE 容易串到下一次命令，导致输出错位。
     * 这张 App 的 key 已授权，重连不需要再点允许。
     */
    private String adbShellFresh(String command) throws Exception {
        int port = adb != null ? adb.port : AdbClient.resolvePort();
        if (port <= 0) throw new IllegalStateException("没有可用 ADB 端口");
        try (AdbClient c = new AdbClient(adbKey, port)) {
            c.connect();
            return c.shell(command + " 2>&1");
        }
    }

    // ------------------------------------------------------------------
    // B. Stellar（普通客户端）
    // ------------------------------------------------------------------

    private void runStellarTests() {
        append("\n===== Stellar 脚本拦截测试 =====\n");
        if (!waitBinder(10000)) {
            append("Stellar Binder 未连接：请确认内置特权在运行，并允许本测试 App 的 Stellar 权限。\n");
            return;
        }
        try {
            Stellar.INSTANCE.requestPermission("stellar", 7);
        } catch (Throwable t) {
            append("请求 stellar 权限失败: " + t + "\n");
        }
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        boolean granted;
        try {
            granted = Stellar.INSTANCE.checkSelfPermission("stellar");
        } catch (Throwable t) {
            granted = false;
        }
        append("stellar permission=" + granted + "\n");
        if (!granted) {
            append("首次授权可能已写入，但当前进程 allowedMap 未刷新；请重启测试 App 后再点第 4 项。\n");
            return;
        }

        File dir = getExternalFilesDir("scripts");
        if (dir == null) {
            append("外部目录不可用\n");
            return;
        }
        if (!dir.exists()) dir.mkdirs();
        File youlong = new File(dir, "youlong.sh");
        File danger = new File(dir, "danger_dry.sh");
        File encrypted = new File(dir, "encrypted.bin");
        writeText(youlong, "echo youlong\n");
        writeText(danger, "echo \"pm disable-user --user 0 -a\"\n"
                + "echo \"pm uninstall -a\"\n"
                + "echo \"pm install /sdcard/x.apk\"\n"
                + "echo \"rm -rf /data/app\"\n");
        writeRandom(encrypted, 64);

        stellarCase("sh 文件含 youlong", new String[]{"sh", youlong.getAbsolutePath()});
        stellarCase("sh 文件含危险命令（echo 文本）", new String[]{"sh", danger.getAbsolutePath()});
        stellarCase("sh 加密/二进制文件", new String[]{"sh", encrypted.getAbsolutePath()});
        stellarCase("env 包装 youlong", new String[]{"env", "sh", youlong.getAbsolutePath()});
        stellarCase("sh -c 'sh 文件'", new String[]{"sh", "-c", "sh " + youlong.getAbsolutePath()});
        stellarCase("sh -c '. 文件'", new String[]{"sh", "-c", ". " + youlong.getAbsolutePath()});
        append("预期：以上都应被游龙脚本策略拦截（BLOCK）。\n");
    }

    private boolean waitBinder(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                if (Stellar.INSTANCE.pingBinder()) return true;
            } catch (Throwable ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private void stellarCase(String label, String[] argv) {
        append("\n--- " + label + " ---\n");
        append("argv=" + Arrays.toString(argv) + "\n");
        try {
            StellarRemoteProcess p = Stellar.INSTANCE.newProcess(argv, null, null);
            append("结果：ALLOW（进程已创建）\n");
            try { p.destroy(); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            append("结果：BLOCK/ERROR\n" + msg + "\n");
            if (msg != null && msg.contains("requires permission stellar")) {
                append("首次授权已写入服务端，但当前进程 allowedMap 未刷新；请点「6. 重启测试 App」后再点第 4 项。\n");
            }
        }
    }

    private void writeText(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            append("写文件失败 " + f + ": " + t + "\n");
        }
    }

    private void writeRandom(File f, int size) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            byte[] data = new byte[size];
            new Random().nextBytes(data);
            out.write(data);
        } catch (Throwable t) {
            append("写随机文件失败 " + f + ": " + t + "\n");
        }
    }

    /** 测试 adbd 是否支持 usb:/tcpip:0 清掉 TCP 端口，而不停 adbd。 */
    private void testAdbTcpClose() {
        if (adb == null) connectAdb();
        int port = adb != null ? adb.port : AdbClient.resolvePort();
        if (port <= 0) {
            append("没有 ADB 端口\n");
            return;
        }
        for (String svc : new String[]{"usb:", "tcpip:0", "tcpip:-1"}) {
            append("\n===== " + svc + " =====\n");
            try (AdbClient c = new AdbClient(adbKey, port)) {
                c.connect();
                String out = c.service(svc);
                append("回显: " + out + "\n");
            } catch (Throwable t) {
                append("失败: " + t + "\n");
            }
            try {
                append("service.adb.tcp.port = " + adbShellFresh("getprop service.adb.tcp.port") + "\n");
            } catch (Throwable t) {
                append("读取端口失败: " + t + "\n");
            }
        }
    }

    /** 首次 Stellar 授权后，服务端写的是配置；当前进程 allowedMap 不会刷新，重启一次即可。 */
    private void restartSelf() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(
                        this,
                        42,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi);
                }
            }
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable t) {
            append("重启失败: " + t + "\n");
        }
    }

    private void saveReport() {        String path = getExternalFilesDir(null) + "/redteam_report.txt";
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(report.toString().getBytes(StandardCharsets.UTF_8));
            append("报告已保存: " + path + "\n");
        } catch (Throwable t) {
            append("保存报告失败: " + t + "\n");
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("redteam", report.toString()));
            Toast.makeText(this, "报告已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        try { if (adb != null) adb.close(); } catch (Throwable ignored) {}
        executor.shutdownNow();
        super.onDestroy();
    }
}
