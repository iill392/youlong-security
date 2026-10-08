package com.youlong.hd;

import android.util.Log;


public class StellarUtils {

    private static final String TAG = "YlPrivUtils";

    
    public static boolean isPrivilegeBinderAlive() {
        try {
            return PrivRouter.isBinderAlive(YouLongApp.instance());
        } catch (Throwable t) {
            return false;
        }
    }

    
    private static boolean onMainThread() {
        try {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    
    public static void requestReconnect(android.content.Context ctx) {
        try {
            if (ctx == null) return;
            PrivRouter.requestReconnect(ctx);
            Log.i(TAG, "已请求服务端重新投递 Binder");
        } catch (Throwable t) {
            Log.w(TAG, "请求重投 Binder 失败", t);
        }
    }

    
    public static boolean isStellarAvailable() {
        try {
            if (isPrivilegeBinderAlive()) return true;
            if (onMainThread()) {
                
                YouLongApp app = YouLongApp.instance();
                requestReconnect(app);
                Log.i(TAG, "主线程探活：当前未连接（已异步请求重投，不阻塞界面）");
                return false;
            }
            
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                if (isPrivilegeBinderAlive()) return true;
                Thread.sleep(200L);
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "特权服务不可用", t);
            return false;
        }
    }

    
    public static String activeSourceName() {
        try {
            return PrivRouter.activeName(YouLongApp.instance());
        } catch (Throwable t) {
            return "未知";
        }
    }

    
    public static boolean hasStellarPermission() {
        try {
            if (!isPrivilegeBinderAlive()) {
                if (onMainThread()) {
                    requestReconnect(YouLongApp.instance());
                    return false;
                }
                if (!isStellarAvailable()) return false;
            }
            return PrivRouter.hasPermission(YouLongApp.instance());
        } catch (Throwable t) {
            Log.w(TAG, "权限查询失败", t);
            return false;
        }
    }

    
    public static Process newPrivilegedProcess(String[] cmd, String[] env, String dir) {
        try {
            Process p = PrivRouter.newProcess(YouLongApp.instance(), cmd, env, dir);
            if (p == null) throw new IllegalStateException("内核未返回进程（可能未授权）");
            return p;
        } catch (Throwable t) {
            throw new RuntimeException("特权进程创建失败: " + t, t);
        }
    }

    
    public static String runCommand(final String command, long timeoutMs) {
        if (onMainThread()) {
            
            if (!isPrivilegeBinderAlive() || !hasStellarPermission()) {
                requestReconnect(YouLongApp.instance());
                return "ERROR:特权服务不可用（主线程不做等待，已请求重连）";
            }
        } else {
            if (!isStellarAvailable()) return "ERROR:特权服务未启动";
            if (!hasStellarPermission()) {
                requestReconnect(YouLongApp.instance());
                return "ERROR:特权服务未授权";
            }
        }

        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        Process process = null;

        try {
            String cleanCmd = command.trim();
            // 脱敏：完整特权命令可能含路径/包名，只记摘要
            Log.d(TAG, "Exec: " + (cleanCmd.length() > 40 ? cleanCmd.substring(0, 40) + "…(" + cleanCmd.length() + " 字符)" : cleanCmd));

            process = newPrivilegedProcess(new String[]{"sh"}, null, null);
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

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        done = p.waitFor(Math.min(remaining, 200L),
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                    } else {
                        // API 24/25 没有 waitFor(long, TimeUnit)：轮询 exitValue 模拟超时
                        try {
                            p.exitValue();
                            done = true;
                        } catch (IllegalThreadStateException e) {
                            try {
                                Thread.sleep(Math.min(remaining, 200L));
                            } catch (InterruptedException ie) {
                                done = true;
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "waitFor err: " + e.getMessage());
                    done = true;
                }
            }

            if (!done) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        p.destroyForcibly();
                    } else {
                        p.destroy();
                    }
                } catch (Throwable ignored) {
                }
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

            Log.d(TAG, "exitOk=" + exitOk + " exitCode=" + exitCode
                    + " outLen=" + out.length() + " errLen=" + err.length());

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
}
