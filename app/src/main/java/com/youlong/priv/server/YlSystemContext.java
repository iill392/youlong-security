package com.youlong.priv.server;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 服务端的 Context / 落盘工具。
 *
 * <p>服务端是用 {@code app_process} 拉起来的，没有普通应用 Context，
 * 只能反射 {@code ActivityThread.systemMain().getSystemContext()} 拿系统 Context。
 * 这个 Context 的 data 目录常常不属于当前 uid（uid 不匹配就写不进去，而且
 * {@code SharedPreferences.apply()} 静默失败不报错），所以这里所有落盘操作都要
 * 先做可写性检查、写完回读校验，失败一律退回 {@code /data/local/tmp}。
 */
final class YlSystemContext {

    private static final String TAG = "YlSystemContext";

    private static final String FALLBACK_DIR = "/data/local/tmp/ylpriv";

    private YlSystemContext() {}

    /** 反射取系统 Context；失败返回 null（不影响特权执行，落盘退回 /data/local/tmp） */
    static Context get() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            if (thread == null) return null;
            Object ctx = at.getMethod("getSystemContext").invoke(thread);
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
        } catch (Throwable t) {
            Log.w(TAG, "反射取系统 Context 失败（不影响特权执行）: " + t);
        }
        return null;
    }

    /** 存储目录：优先 Context 私有目录，不可写就退回 /data/local/tmp/ylpriv */
    static File storeDir(Context ctx) {
        if (ctx != null) {
            try {
                File dir = new File(ctx.getFilesDir(), "ylpriv");
                if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
                    return dir;
                }
                Log.w(TAG, "Context 存储目录不可写，退回文件存储: " + dir);
            } catch (Throwable t) {
                Log.w(TAG, "取 Context 存储目录失败，退回文件存储", t);
            }
        }
        File fallback = new File(FALLBACK_DIR);
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    /**
     * 检查 SharedPreferences 是否真的能写。
     *
     * <p>system Context 的 data 目录通常不属于 shell uid：
     * {@code getSharedPreferences().edit().apply()} 不抛异常但一个字也写不进去，
     * 所以写之前必须先判断目录/文件可写。
     */
    static boolean prefsWritable(Context ctx, String prefsName) {
        if (ctx == null || prefsName == null || prefsName.isEmpty()) return false;
        try {
            File dir = new File(ctx.getDataDir(), "shared_prefs");
            if (!dir.exists() && !dir.mkdirs()) return false;
            if (!dir.canWrite()) return false;
            File f = new File(dir, prefsName + ".xml");
            // 已存在的文件必须可写；不存在时只要目录可写就能创建
            return !f.exists() || f.canWrite();
        } catch (Throwable t) {
            Log.w(TAG, "检查 prefs 可写性失败: " + prefsName, t);
            return false;
        }
    }

    /** 读属性文件；不存在或读失败返回空表 */
    static Properties loadProps(File f) {
        Properties p = new Properties();
        if (f == null || !f.exists()) return p;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            Log.w(TAG, "读属性失败: " + f, t);
        } finally {
            closeQuietly(in);
        }
        return p;
    }

    /**
     * 原子写属性文件：先写 {@code <file>.tmp}，成功后再 rename 覆盖。
     * 这样进程被杀/写一半不会留下半截文件（授权记录最怕这个）。
     */
    static void saveProps(File f, Properties p) {
        if (f == null || p == null) return;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(tmp);
            p.store(out, "youlong privilege auth");
            out.flush();
            out.close();
            out = null;
            if (tmp.length() <= 0) {
                Log.w(TAG, "临时文件为空，放弃写入: " + tmp);
                return;
            }
            if (!tmp.renameTo(f)) {
                // rename 失败（跨设备 / 权限）：退回直接写，并清掉临时文件
                Log.w(TAG, "原子替换失败，改为直接写: " + f);
                writeDirect(f, p);
                if (!tmp.delete()) Log.w(TAG, "临时文件清理失败: " + tmp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "写属性失败: " + f, t);
        } finally {
            closeQuietly(out);
        }
    }

    private static void writeDirect(File f, Properties p) {
        OutputStream direct = null;
        try {
            direct = new FileOutputStream(f);
            p.store(direct, "youlong privilege auth");
            direct.flush();
        } catch (Throwable t) {
            Log.w(TAG, "直接写属性失败: " + f, t);
        } finally {
            closeQuietly(direct);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
