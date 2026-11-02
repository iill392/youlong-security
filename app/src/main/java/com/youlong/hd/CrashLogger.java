package com.youlong.hd;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 全局运行日志 + 崩溃捕获。
 *
 * <p>为什么需要它：内置的 Shizuku 管理器界面是独立的一套 Activity，它闪退时
 * WebView 首页还活着，但用户拿不到任何信息（logcat 普通人看不到）。本类做三件事：
 *
 * <ol>
 *   <li><b>运行轨迹</b>：把关键操作（打开管理器、Activity 生命周期、Shizuku 初始化）
 *       记进内存环形缓冲，不写盘，零 IO 开销。</li>
 *   <li><b>崩溃捕获</b>：接管 {@link Thread.UncaughtExceptionHandler}，把异常堆栈 +
 *       完整运行轨迹 + 设备信息写进文件，然后**才**交还给系统原处理器
 *       （保证系统仍会正常杀掉进程、弹「应用已停止运行」）。</li>
 *   <li><b>可取出</b>：文件写在应用外部私有目录，同时页面上有「复制日志」按钮，
 *       用户一键复制粘贴即可把日志发出来，不需要 root、不需要连电脑。</li>
 * </ol>
 *
 * <p>注意：我们**不做**「崩溃后重启应用」这类花招。崩溃就让它崩，
 * 但一定要把原因留下来。
 */
public final class CrashLogger {

    private static final String TAG = "CrashLogger";

    /** 最近一次崩溃（会被下一次崩溃覆盖）。 */
    private static final String FILE_LAST_CRASH = "last_crash.txt";
    /** 崩溃历史（每次崩溃追加，最多保留 {@link #MAX_HISTORY} 次）。 */
    private static final String FILE_HISTORY = "crash_history.txt";

    /** 内存轨迹最多保留条数。 */
    private static final int MAX_EVENTS = 240;
    /** 崩溃日志最多写多少行堆栈，防止异常被反复包装导致文件爆炸。 */
    private static final int MAX_STACK_LINES = 200;
    /** 历史文件最多保留的崩溃次数。 */
    private static final int MAX_HISTORY = 5;

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * 取当前时间字符串。
     * ⚠️ {@link SimpleDateFormat} 不是线程安全的，而 {@link #event(String)} 会被
     * 主线程与后台 executor 同时调用，直接 format 可能返回错乱结果甚至抛
     * ArrayIndexOutOfBoundsException（那就把调用方一起带崩了）。必须加锁。
     */
    private static String now() {
        synchronized (TIME_FMT) {
            return TIME_FMT.format(new Date());
        }
    }

    /** 内存环形轨迹。用 synchronized 保护，因为后台线程也会写。 */
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();

    private static volatile Application sApp;
    private static volatile boolean sInstalled;
    private static volatile Thread.UncaughtExceptionHandler sPreviousHandler;
    /** 崩溃写入过程中再次崩溃的保护位。 */
    private static volatile boolean sWriting;

    private CrashLogger() {
    }

    // ==================================================================
    // 安装
    // ==================================================================

    /**
     * 在 {@code Application.onCreate()} 最开头调用。重复调用安全。
     */
    public static void install(Application app) {
        if (app == null) return;
        sApp = app;
        if (sInstalled) return;
        synchronized (CrashLogger.class) {
            if (sInstalled) return;
            sInstalled = true;
        }

        // Activity 轨迹：谁被创建 / 谁被销毁，闪退前最后进的是哪个界面一目了然
        try {
            app.registerActivityLifecycleCallbacks(new ActivityCallbacks());
        } catch (Throwable tr) {
            Log.w(TAG, "registerActivityLifecycleCallbacks failed", tr);
        }

        sPreviousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Handler());
        event("CrashLogger 已安装（上一个处理器="
                + (sPreviousHandler == null ? "无" : sPreviousHandler.getClass().getName()) + "）");
    }

    // ==================================================================
    // 记录
    // ==================================================================

    /** 记一条运行轨迹（内存，不写盘）。 */
    public static void event(String message) {
        String line = "[" + now() + "] "
                + "tid=" + Process.myTid() + " " + message;
        synchronized (EVENTS) {
            EVENTS.addLast(line);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
        Log.i(TAG, message);
    }

    /** 记一条带异常的运行轨迹（同样只进内存）。 */
    public static void event(String message, Throwable tr) {
        event(message + " -> " + (tr == null ? "null" : tr.toString()));
        if (tr != null) {
            for (StackTraceElement el : tr.getStackTrace()) {
                event("    at " + el);
            }
        }
    }

    // ==================================================================
    // 崩溃处理
    // ==================================================================

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {
                writeCrash(thread, ex);
            } catch (Throwable inner) {
                Log.e(TAG, "写崩溃日志本身又失败了", inner);
            }
            // 交还给系统原处理器（默认会弹「应用已停止运行」并杀进程），
            // 保证行为与没装本类时一致，绝不吞掉崩溃。
            Thread.UncaughtExceptionHandler prev = sPreviousHandler;
            if (prev != null && prev != this) {
                prev.uncaughtException(thread, ex);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }

    private static void writeCrash(Thread thread, Throwable ex) {
        synchronized (CrashLogger.class) {
            if (sWriting) return;
            sWriting = true;
        }
        try {
            event("*** 崩溃：线程 " + (thread == null ? "?" : thread.getName())
                    + " " + (ex == null ? "null" : ex.toString()));

            StringBuilder sb = new StringBuilder(8192);
            header(sb, "崩溃");
            sb.append("崩溃线程: ").append(thread == null ? "?" : thread.getName()).append('\n');
            sb.append("异常类型: ").append(ex == null ? "null" : ex.getClass().getName()).append('\n');
            sb.append("异常信息: ").append(ex == null ? "" : String.valueOf(ex.getMessage())).append('\n');

            sb.append("\n--------- 异常堆栈 ---------\n");
            if (ex != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                sb.append(clipLines(sw.toString(), MAX_STACK_LINES));
            } else {
                sb.append("(无异常对象)\n");
            }

            sb.append("\n").append(snapshotEvents());
            sb.append("\n--------- 日志文件位置 ---------\n");
            sb.append(new File(crashDir(sApp), FILE_LAST_CRASH).getAbsolutePath()).append('\n');

            String text = sb.toString();
            writeFile(lastCrashFile(sApp), text);
            appendHistory(sApp, text);
            Log.e(TAG, "崩溃日志已写入 " + lastCrashFile(sApp).getAbsolutePath());
        } finally {
            sWriting = false;
        }
    }

    // ==================================================================
    // 读取 / 清除（供 JS 桥使用）
    // ==================================================================

    /** 是否存在未清除的崩溃记录。 */
    public static boolean hasCrash(Context ctx) {
        File f = lastCrashFile(ctx);
        return f != null && f.isFile() && f.length() > 0;
    }

    /** 读取最近一次崩溃日志；没有则返回空串。 */
    public static String readCrash(Context ctx) {
        File f = lastCrashFile(ctx);
        if (f == null || !f.isFile()) return "";
        return readFile(f, 64 * 1024);
    }

    /**
     * 组装「诊断报告」：不管有没有崩溃都能用。
     * 内容 = 设备信息 + 运行轨迹 + （有的话）最近一次崩溃。
     */
    public static String buildDiagnostics(Context ctx) {
        StringBuilder sb = new StringBuilder(16384);
        header(sb, hasCrash(ctx) ? "诊断报告（含最近一次崩溃）" : "诊断报告（本次运行暂无崩溃）");
        sb.append(snapshotEvents());

        if (hasCrash(ctx)) {
            sb.append("\n--------- 最近一次崩溃 ---------\n");
            sb.append(readCrash(ctx));
        } else {
            sb.append("\n（未捕获到崩溃）\n");
        }

        sb.append("\n--------- 崩溃历史（最近 ").append(MAX_HISTORY).append(" 次）---------\n");
        String hist = readFile(historyFile(ctx), 64 * 1024);
        sb.append(hist.isEmpty() ? "（无）\n" : hist);

        sb.append("\n--------- 日志文件位置 ---------\n");
        sb.append(new File(crashDir(ctx), FILE_LAST_CRASH).getAbsolutePath()).append('\n');
        return sb.toString();
    }

    /** 清除崩溃记录（诊断轨迹保留）。 */
    public static void clear(Context ctx) {
        deleteQuietly(lastCrashFile(ctx));
        deleteQuietly(historyFile(ctx));
        event("崩溃日志已被用户清除");
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static void header(StringBuilder sb, String kind) {
        sb.append("========== 游龙安全护盾 ").append(kind).append(" ==========\n");
        sb.append("时间: ").append(now()).append('\n');
        Application app = sApp;
        if (app != null) {
            sb.append("包名: ").append(app.getPackageName()).append('\n');
            try {
                android.content.pm.PackageInfo pi =
                        app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                sb.append("版本: ").append(pi.versionName)
                        .append(" (").append(pi.versionCode).append(")\n");
            } catch (Throwable ignored) {
            }
        }
        sb.append("进程: ").append(Process.myPid()).append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
                .append("  ABI=").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                .append('\n');
        String sig;
        try {
            sig = SignatureVerifier.verify(app) ? "通过" : "失败";
        } catch (Throwable tr) {
            sig = "校验异常 " + tr;
        }
        sb.append("签名校验: ").append(sig).append('\n');
        sb.append('\n');
    }

    private static String snapshotEvents() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("--------- 运行轨迹（最近 ").append(MAX_EVENTS).append(" 条）---------\n");
        synchronized (EVENTS) {
            if (EVENTS.isEmpty()) {
                sb.append("（无）\n");
            } else {
                for (String e : EVENTS) sb.append(e).append('\n');
            }
        }
        return sb.toString();
    }

    private static String clipLines(String text, int maxLines) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        if (lines.length <= maxLines) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append('\n');
        sb.append("... （省略 ").append(lines.length - maxLines).append(" 行）\n");
        return sb.toString();
    }

    /**
     * 崩溃日志目录：外部私有目录优先（用户/电脑都好取），失败退回内部目录。
     * 两个都拿不到时（只在 Application 还没起来时可能发生）退回内部缓存目录。
     */
    private static File crashDir(Context ctx) {
        File dir = null;
        if (ctx != null) {
            try {
                dir = ctx.getExternalFilesDir(null);       // /sdcard/Android/data/<pkg>/files
            } catch (Throwable ignored) {
            }
            if (dir == null) {
                try {
                    dir = ctx.getFilesDir();
                } catch (Throwable ignored) {
                }
            }
            if (dir == null) {
                try {
                    dir = ctx.getCacheDir();
                } catch (Throwable ignored) {
                }
            }
        }
        File target = (dir == null) ? new File("/data/local/tmp") : new File(dir, "crash");
        //noinspection ResultOfMethodCallIgnored
        if (!target.isDirectory()) target.mkdirs();
        return target;
    }

    private static File lastCrashFile(Context ctx) {
        return new File(crashDir(ctx), FILE_LAST_CRASH);
    }

    private static File historyFile(Context ctx) {
        return new File(crashDir(ctx), FILE_HISTORY);
    }

    private static void appendHistory(Context ctx, String text) {
        try {
            File f = historyFile(ctx);
            String old = readFile(f, 256 * 1024);
            String merged = text + "\n\n" + old;
            // 只保留最近 MAX_HISTORY 次（以分隔线数量粗略裁剪）
            String[] parts = merged.split("========== 游龙安全护盾 崩溃 ==========");
            StringBuilder sb = new StringBuilder(parts.length * 64);
            int kept = 0;
            for (int i = 1; i < parts.length && kept < MAX_HISTORY; i++, kept++) {
                sb.append("========== 游龙安全护盾 崩溃 ==========").append(parts[i]);
            }
            writeFile(f, sb.toString());
        } catch (Throwable tr) {
            Log.w(TAG, "appendHistory failed", tr);
        }
    }

    private static void writeFile(File f, String text) {
        if (f == null || text == null) return;
        Writer w = null;
        try {
            // 不用 try-with-resources，保持对老版本 Java 的兼容写法一致
            FileOutputStream fos = new FileOutputStream(f, false);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write(text);
            w.flush();
            Log.i(TAG, "written " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
        } catch (Throwable tr) {
            Log.e(TAG, "writeFile failed: " + f, tr);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readFile(File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        BufferedReader r = null;
        try {
            FileInputStream fis = new FileInputStream(f);
            r = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder(4096);
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > maxBytes) break;
            }
            return sb.toString();
        } catch (Throwable tr) {
            Log.e(TAG, "readFile failed: " + f, tr);
            return "";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        try {
            if (f.isFile() && !f.delete()) {
                // 删不掉就清空内容，效果等价
                writeFile(f, "");
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    // Activity 轨迹
    // ==================================================================

    private static final class ActivityCallbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity a, Bundle b) {
            event("Activity.onCreate  " + a.getClass().getName());
        }

        @Override
        public void onActivityStarted(Activity a) {
            event("Activity.onStart   " + a.getClass().getSimpleName());
        }

        @Override
        public void onActivityResumed(Activity a) {
            event("Activity.onResume  " + a.getClass().getSimpleName());
        }

        @Override
        public void onActivityPaused(Activity a) {
            event("Activity.onPause   " + a.getClass().getSimpleName());
        }

        @Override
        public void onActivityStopped(Activity a) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle b) {
        }

        @Override
        public void onActivityDestroyed(Activity a) {
            event("Activity.onDestroy " + a.getClass().getSimpleName());
        }
    }
}
