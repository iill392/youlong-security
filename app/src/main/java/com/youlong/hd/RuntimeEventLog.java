package com.youlong.hd;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 2026-10-30：运行事件日志。
 *
 * 写入 App 外部目录 runtime_events.log，服务端也会向同一文件追加事件。
 * 记录：App 打开/恢复、进入内置特权界面、服务端启动、版本变化、自动重启等。
 */
public final class RuntimeEventLog {

    private static final String TAG = "RuntimeEventLog";
    private static final String FILE_NAME = "runtime_events.log";
    private static final long MAX_BYTES = 512 * 1024L;
    private static final Object LOCK = new Object();

    private RuntimeEventLog() {
    }

    public static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return new File(context.getFilesDir(), FILE_NAME);
        return new File(dir, FILE_NAME);
    }

    public static void log(Context context, String source, String message) {
        if (context == null) return;
        try {
            String line = "[" + new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                    .format(new Date()) + "] [" + source + "] " + message;
            append(context.getApplicationContext(), line);
        } catch (Throwable tr) {
            Log.w(TAG, "写运行日志失败", tr);
        }
    }

    /** 服务端等外部进程也可调用。 */
    public static void append(Context context, String line) {
        synchronized (LOCK) {
            try {
                File f = file(context);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                if (f.isFile() && f.length() > MAX_BYTES) {
                    File old = new File(f.getParentFile(), FILE_NAME + ".1");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable tr) {
                Log.w(TAG, "追加运行日志失败", tr);
            }
        }
    }
}
