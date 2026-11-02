package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02：旧版本自动把系统应用加入白名单的迁移清理。
 *
 * 旧版本曾把系统应用批量写入：
 *   · shield_prefs / whitelist_pkgs
 *   · stellar_command_whitelist / pkgs
 *   · 内置特权的 command_whitelist.txt
 *
 * 新版本不再自动加入系统应用；这里在主进程启动时清理旧数据：
 *   · 从 whitelist_pkgs 删除 FLAG_SYSTEM 包；
 *   · 通过 WhitelistActivity.syncToStellar() 把清理后的用户白名单重写到
 *     command_whitelist.txt（从而覆盖旧系统包）；
 *   · 用户自己加入的普通应用保持不变。
 *
 * 清理失败不影响主流程；下一次启动/自保脚本启动还会再次尝试。
 */
public final class WhitelistMigration {

    private static final String TAG = "WhitelistMigration";
    private static final String SHIELD_PREFS = "shield_prefs";
    private static final String KEY_WHITELIST = "whitelist_pkgs";

    private WhitelistMigration() {
    }

    /** 在后台线程执行，避免阻塞 Application.onCreate。 */
    public static void cleanupAsync(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                int removed = cleanup(app);
                if (removed > 0) {
                    Log.i(TAG, "已清理旧版自动加入的系统白名单: " + removed);
                    RuntimeEventLog.log(app, "MIGRATION",
                            "清理旧版自动加入的系统白名单 " + removed + " 个");
                }
            } catch (Throwable tr) {
                Log.w(TAG, "清理旧版系统白名单失败", tr);
            }
        }, "Whitelist-Migration").start();
    }

    /**
     * 同步清理。返回从用户白名单中移除的系统应用数量。
     *
     * 注意：只在主进程启动时调用；PackageManager 查询失败时不做任何删除，
     * 避免误清空用户白名单。
     */
    public static int cleanup(Context context) {
        if (context == null) return 0;
        PackageManager pm = context.getPackageManager();
        if (pm == null) return 0;

        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(0);
        } catch (Throwable tr) {
            Log.w(TAG, "读取已安装应用失败，跳过迁移", tr);
            return 0;
        }
        if (installed == null || installed.isEmpty()) return 0;

        java.util.Set<String> systemPkgs = new java.util.HashSet<>();
        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null) continue;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                systemPkgs.add(ai.packageName);
            }
        }

        SharedPreferences prefs = context.getSharedPreferences(SHIELD_PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_WHITELIST, "");
        if (raw == null) raw = "";

        List<String> keep = new ArrayList<>();
        int removed = 0;
        for (String part : raw.split(",")) {
            String pkg = part == null ? "" : part.trim();
            if (pkg.isEmpty()) continue;
            if (systemPkgs.contains(pkg)) {
                removed++;
            } else {
                keep.add(pkg);
            }
        }

        if (removed > 0) {
            StringBuilder sb = new StringBuilder();
            for (String pkg : keep) {
                if (sb.length() > 0) sb.append(',');
                sb.append(pkg);
            }
            prefs.edit().putString(KEY_WHITELIST, sb.toString()).apply();
        }

        // 无论是否有删除都同步一次：旧版本可能已经删掉 prefs，但服务端
        // command_whitelist.txt 还残留系统包；这次同步会以当前用户白名单为准
        // 覆盖服务端文件，同时刷新 stellar_command_whitelist/pkgs。
        WhitelistActivity.syncToStellar(context);
        return removed;
    }
}
