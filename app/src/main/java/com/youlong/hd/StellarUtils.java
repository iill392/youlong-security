package com.youlong.hd;

import android.util.Log;

import roro.stellar.Stellar;

/**
 * Stellar 工具类 — 提供静态方法用于执行特权命令。
 * 供 ProtectService、MainActivity、ShieldWarnActivity 等各处复用。
 *
 * <p>历史：本类原名 {@code ShizukuUtils}，内部走的是内置的上游 Shizuku
 * （{@code rikka.shizuku.Shizuku}）。2026 年内置特权内核整体替换为
 * <a href="https://github.com/roro2239/Stellar">Stellar</a>
 * （Shizuku 的分支，MPL-2.0 + Apache-2.0），本类随之改为
 * {@code roro.stellar.Stellar}，并改名为 {@code StellarUtils}。
 *
 * <p><b>与 Shizuku 版的关键差别</b>（新 API 更简单，旧的兼容绕行已删除）：
 * <ul>
 *   <li>{@code checkSelfPermission()} 返回 {@code boolean}，不再返回
 *       {@code PackageManager.PERMISSION_*}</li>
 *   <li>Stellar 没有 {@code isPreV11()}（服务端 API 版本恒定），删去该判断</li>
 *   <li>{@code Stellar.newProcess(...)} 是 **public** 方法 —— 上游 Shizuku 13.6
 *       把它改成了 private，本类原先只能用反射调用；Stellar 已重新开放该 API，
 *       因此直接调用即可（原先的反射 + R8 keep 规则一并删除）</li>
 * </ul>
 */
public class StellarUtils {

    private static final String TAG = "StellarUtils";

    /**
     * 检查 Stellar 服务是否可用
     */
    public static boolean isStellarAvailable() {
        try {
            return Stellar.INSTANCE.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查是否已获得 Stellar 权限
     */
    public static boolean hasStellarPermission() {
        try {
            return Stellar.INSTANCE.checkSelfPermission("stellar");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 Stellar 创建特权进程。
     *
     * <p>历史对照：上游 Shizuku 13.6 起把 {@code Shizuku.newProcess} 改成了
     * private（仅内部使用），本类原先只能用反射调用它并依赖一条 R8 keep 规则。
     * Stellar 重新把 {@code Stellar.newProcess} 开放为 public API，
     * 因此这里直接调用，不再需要反射与 keep 规则。
     */
    public static Process newPrivilegedProcess(String[] cmd, String[] env, String dir) {
        try {
            return Stellar.INSTANCE.newProcess(cmd, env, dir);
        } catch (Exception e) {
            throw new RuntimeException("Stellar.newProcess 不可用", e);
        }
    }

    /**
     * 通过 Stellar 执行命令（静态方法，可在任何地方调用）。
     * 用 {@code sh} + stdin 传命令以支持管道/重定向，轮询 exitValue 避免 waitFor 死锁。
     *
     * @param command   要执行的 shell 命令
     * @param timeoutMs 超时毫秒，&lt;=0 不设超时
     * @return 命令执行的标准输出，失败则返回 "ERROR:xxx"
     */
    public static String runCommand(final String command, long timeoutMs) {
        if (!isStellarAvailable()) {
            return "ERROR:Stellar服务未启动";
        }
        if (!hasStellarPermission()) {
            return "ERROR:Stellar未授权";
        }

        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        Process process = null;

        try {
            String cleanCmd = command.trim();
            Log.d(TAG, "Exec: " + cleanCmd);

            // 启动 sh，通过 stdin 传命令，避免 Binder 传参分裂
            process = Stellar.INSTANCE.newProcess(new String[]{"sh"}, null, null);
            final Process p = process;

            java.io.OutputStream stdin = p.getOutputStream();
            stdin.write((cleanCmd + "\nexit\n").getBytes("UTF-8"));
            stdin.flush();
            stdin.close();

            Thread outReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.InputStream in = p.getInputStream();
                    while ((n = in.read(buf)) != -1)
                        stdout.append(new String(buf, 0, n));
                } catch (Exception e) {
                    Log.e(TAG, "out read err", e);
                }
            });
            Thread errReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.InputStream in = p.getErrorStream();
                    while ((n = in.read(buf)) != -1)
                        stderr.append(new String(buf, 0, n));
                } catch (Exception e) {
                    Log.e(TAG, "err read err", e);
                }
            });
            outReader.start();
            errReader.start();

            // 等待进程结束
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    done = p.waitFor(Math.min(remaining, 200L), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "waitFor err: " + e.getMessage());
                    done = true;
                }
            }

            if (!done) {
                p.destroyForcibly();
                outReader.join(500);
                errReader.join(500);
                return "ERROR:执行超时（" + timeoutMs + "ms）";
            }

            outReader.join(2000);
            errReader.join(2000);

            int exitCode = 0;
            boolean exitOk = false;
            for (int i = 0; i < 5; i++) {
                try {
                    exitCode = p.exitValue();
                    exitOk = true;
                    break;
                } catch (Exception e) {
                    if (i < 4)
                        try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }

            String out = stdout.toString().trim();
            String err = stderr.toString().trim();

            Log.d(TAG, "exitOk=" + exitOk + " exitCode=" + exitCode + " outLen=" + out.length() + " errLen=" + err.length());

            if (out.length() > 0) return out;
            if (!exitOk) {
                if (err.length() > 0) return err;
                return "OK";
            }
            if (err.length() > 0) {
                if (exitCode != 0 && exitCode != -1)
                    return "ERROR:执行失败(code:" + exitCode + ") " + err;
                return err;
            }
            if (exitCode != 0 && exitCode != -1)
                return "ERROR:执行失败(code:" + exitCode + ")，无输出";

        } catch (Exception e) {
            Log.e(TAG, "Exception", e);
            return "ERROR:" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        return "OK";
    }

    // ======================================================================
    // 2026-10-24：病毒特征库快照写入（管理器认证的 Binder 通道）
    // ----------------------------------------------------------------------
    // 历史实现用 `sh` + stdin 把 base64 写进 /data/local/tmp/stellar_guard/，
    // 但该目录与服务端 fork 出来的 sh 是同一 uid —— 任何拿到 shell 的应用都能
    // 用 cp/rm 改写策略文件（审查 C1）。
    // 现在改为 IStellarService.writePolicyFile：服务端用 enforceManager 校验
    // 调用方就是本应用，再以「临时文件 + rename」原子落盘。
    // ======================================================================

    /**
     * 写入病毒特征库快照（仅管理器可用）。
     *
     * @param json 快照 JSON（schema 必须是 youlong/virus-db/snapshot/1）
     * @return true = 服务端已原子落盘
     */
    public static boolean writeVirusSnapshot(String json) {
        if (json == null || json.length() <= 2) return false;
        if (!isStellarAvailable()) {
            Log.w(TAG, "内置特权未启动，暂不推送特征库快照");
            return false;
        }
        if (!hasStellarPermission()) {
            Log.w(TAG, "内置特权未授权，暂不推送特征库快照");
            return false;
        }
        try {
            return Stellar.INSTANCE.writePolicyFile("virus_db.json", json);
        } catch (Throwable tr) {
            Log.w(TAG, "推送特征库快照失败", tr);
            return false;
        }
    }

    /**
     * 2026-10-25：把「外面的白名单」同步到内置特权的「指令拦截白名单」。
     *
     * <p>内容为每行一个包名；服务端 PolicyStore 校验后原子写入
     * /data/local/tmp/stellar_guard/command_whitelist.txt，CommandWhitelist
     * 1 秒内生效。白名单应用不再被自动拦截，改为弹窗询问是否执行。
     *
     * @param packageList 每行一个包名
     */
    public static boolean writeCommandWhitelist(String packageList) {
        if (packageList == null) return false;
        if (!isStellarAvailable() || !hasStellarPermission()) {
            Log.w(TAG, "内置特权未连接，暂不同步指令白名单");
            return false;
        }
        try {
            return Stellar.INSTANCE.writePolicyFile("command_whitelist.txt", packageList);
        } catch (Throwable tr) {
            Log.w(TAG, "同步指令白名单失败", tr);
            return false;
        }
    }
}
