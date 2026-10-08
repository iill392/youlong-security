package com.youlong.priv.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.youlong.priv.YlApplication;
import com.youlong.priv.YlProtocol;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端权限裁决。
 *
 * <p>三条规则：
 * <ol>
 *   <li>调用方身份只认内核给的 uid（{@code Binder.getCallingUid()}），
 *       从不相信客户端自报的 uid。</li>
 *   <li>权限名必须在 {@link YlProtocol} 的已知权限表里，并且出现在该 uid 的
 *       “已授权集合”里；已授权集合落盘保存（prefs 优先，写不进去退回文件）。</li>
 *   <li>只有本应用亲自拉起的宿主进程（包名经 PackageManager 解析成 uid，见
 *       {@link #noteHostPackage}）隐式拥有 {@link YlProtocol#PERMISSION_PRIVILEGED}
 *       —— 服务端本来就是它拉起来的；其余 uid 一律要靠授权记录。</li>
 * </ol>
 */
final class YlPermission {

    private static final String TAG = "YlPermission";
    private static final String PREFS_CTX = "yl_priv_auth";
    private static final String AUTH_FILE = "auth.properties";
    /**
     * 授权记录键：{@code granted_uid_<uid> = 权限名1|权限名2}。
     * 旧版本这里存 boolean（true 等价于整包特权），读取时按 PERMISSION_PRIVILEGED 兼容。
     */
    private static final String KEY_PREFIX = "granted_uid_";
    private static final String SEP = "|";

    /** 宿主应用 uid：启动参数里的包名解析所得，或 attachApplication 的调用方 uid */
    private static volatile int sClientAppUid = -1;

    /** 客户端回调：按底层 Binder 去重（同一个回调 Binder 重复 attach 只保留一份） */
    private static final Map<IBinder, YlApplication> CALLBACKS = new ConcurrentHashMap<>();

    /** uid → 已授权集合缓存（写授权记录时同步更新） */
    private static final Map<Integer, Set<String>> GRANTS = new ConcurrentHashMap<>();

    private YlPermission() {}

    private static int serverUid() {
        return android.os.Process.myUid();
    }

    // ==================================================================
    // 身份
    // ==================================================================

    /**
     * 记录宿主包名（{@code YlPrivLauncher} 启动服务端时用 --yl-pkg 传入）并解析可信 uid。
     *
     * <p>包名 → uid 的换算交给 PackageManager，客户端没法直接伪造 uid；
     * 而能走到这里的只有本应用自己的启动流程（Provider 侧校验过调用方 uid）。
     */
    static void noteHostPackage(YlServerMain server, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Context ctx = server.context();
        if (ctx == null) {
            Log.w(TAG, "无 Context，无法解析宿主包名: " + packageName);
            return;
        }
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(packageName, 0);
            if (ai == null || ai.uid <= 0) {
                Log.w(TAG, "宿主包名解析不到 uid: " + packageName);
                return;
            }
            sClientAppUid = ai.uid;
            // 宿主应用隐式获得最高权限：它是本内核的拥有者
            Set<String> perms = new HashSet<>();
            perms.add(YlProtocol.PERMISSION_PRIVILEGED);
            GRANTS.put(ai.uid, perms);
            Log.i(TAG, "宿主包名=" + packageName + " uid=" + ai.uid
                    + "（隐式授予 " + YlProtocol.PERMISSION_PRIVILEGED + "）");
        } catch (Throwable t) {
            Log.w(TAG, "解析宿主包名失败: " + packageName, t);
        }
    }

    /** 调用方 uid 是否属于受信主体（服务端自身 / root / 宿主应用 / Context 所属 uid / 已有授权记录） */
    static boolean checkUid(YlServerMain server, int uid) {
        if (uid < 0) return false;
        if (uid == serverUid()) return true;
        if (uid == 0) return true;
        if (sClientAppUid > 0 && uid == sClientAppUid) return true;
        Context ctx = server.context();
        if (ctx != null) {
            try {
                if (uid == ctx.getApplicationInfo().uid) return true;
            } catch (Throwable ignored) {
            }
        }
        return !grantsOf(server, uid).isEmpty();
    }

    // ==================================================================
    // 权限裁决
    // ==================================================================

    /** 内核统一入口：只按 Binder 给的调用方 uid 裁决 */
    static boolean check(YlServerMain server, String permission) {
        return checkPermission(server, Binder.getCallingUid(), permission);
    }

    /** 按指定 uid 校验权限名：未知权限名一律拒绝，其余看已授权集合（分级） */
    static boolean checkPermission(YlServerMain server, int uid, String permission) {
        if (!YlProtocol.isKnownPermission(permission)) {
            Log.w(TAG, "拒绝未知权限名: uid=" + uid + " permission=" + permission);
            return false;
        }
        if (!checkUid(server, uid)) {
            Log.w(TAG, "权限校验：调用方 uid 未受信 uid=" + uid + " permission=" + permission);
            return false;
        }
        for (String granted : grantsOf(server, uid)) {
            if (YlProtocol.implies(granted, permission)) return true;
        }
        Log.w(TAG, "权限校验未通过 uid=" + uid + " permission=" + permission
                + " 已授权=" + grantsOf(server, uid));
        return false;
    }

    /**
     * 写授权记录（裁决链的落地动作）。
     *
     * <p>先写 prefs 并回读校验，写不进去（system Context 的常见情况）再写文件，
     * 文件也是 tmp + rename 原子替换，最后同样回读校验。
     */
    static void grant(YlServerMain server, int uid, String permission, boolean allowed) {
        if (uid <= 0 || !YlProtocol.isKnownPermission(permission)) return;
        Set<String> perms = new HashSet<>(grantsOf(server, uid));
        if (allowed) {
            perms.add(permission);
        } else {
            perms.remove(permission);
        }
        boolean ok = writeGrants(server, uid, perms);
        if (ok) {
            Log.i(TAG, "授权记录已落盘 uid=" + uid + " permission=" + permission
                    + " allowed=" + allowed + " 记录=" + perms);
        } else {
            Log.e(TAG, "授权记录落盘失败 uid=" + uid + " permission=" + permission
                    + " allowed=" + allowed);
        }
    }

    /**
     * 客户端挂载回调。
     *
     * <p>只用 {@code Binder.getCallingUid()}：{@code args} 里旧版的 KEY_CLIENT_UID
     * 是客户端自报值，直接忽略（拿到 Binder 就能自称任意 uid 是历史漏洞）。
     */
    static void attachApplication(YlServerMain server, YlApplication app, Bundle args) {
        int uid = Binder.getCallingUid();
        if (app == null) return;
        if (!checkUid(server, uid)) {
            Log.w(TAG, "拒绝未受信客户端的 attachApplication uid=" + uid);
            return;
        }
        IBinder key = app.asBinder();
        if (key == null) {
            Log.w(TAG, "attachApplication：回调 Binder 为空 uid=" + uid);
            return;
        }
        YlApplication old = CALLBACKS.put(key, app);
        if (sClientAppUid <= 0) sClientAppUid = uid;
        Log.i(TAG, "客户端已挂载" + (old == null ? "" : "（替换同 Binder 的旧回调）")
                + " uid=" + uid + " 记录的宿主uid=" + sClientAppUid
                + " pkg=" + (args == null ? "?" : args.getString(YlProtocol.KEY_PACKAGE_NAME)));
        try {
            app.onServerReady();
        } catch (Throwable t) {
            Log.w(TAG, "回调 onServerReady 失败", t);
        }
    }

    /**
     * 权限申请：调用方 uid 合法且权限名已知才判 true，并把授权落盘；
     * 否则一律 false（旧实现无条件广播 true，等于谁问都给）。
     */
    static void request(YlServerMain server, int requestCode, String permission) {
        int uid = Binder.getCallingUid();
        boolean allowed = YlProtocol.isKnownPermission(permission) && checkUid(server, uid);
        Log.i(TAG, "收到授权请求 uid=" + uid + " permission=" + permission + " allowed=" + allowed);
        if (allowed) grant(server, uid, permission, true);
        for (Map.Entry<IBinder, YlApplication> e : CALLBACKS.entrySet()) {
            try {
                e.getValue().onPermissionResult(requestCode, permission, allowed);
            } catch (Throwable t) {
                Log.w(TAG, "回调 onPermissionResult 失败，移除该回调", t);
                CALLBACKS.remove(e.getKey());
            }
        }
    }

    // ==================================================================
    // 授权记录：读写 + 落盘
    // ==================================================================

    /** 读 uid 的已授权集合（带缓存，永不为 null） */
    private static Set<String> grantsOf(YlServerMain server, int uid) {
        Set<String> cached = GRANTS.get(uid);
        if (cached != null) return cached;
        Set<String> perms = readGrants(server, uid);
        GRANTS.put(uid, perms);
        return perms;
    }

    /** 读授权记录：prefs 优先，没有记录再读文件；两处都没有返回空集合 */
    private static Set<String> readGrants(YlServerMain server, int uid) {
        Set<String> fromPrefs = null;
        Context ctx = server.context();
        if (ctx != null && YlSystemContext.prefsWritable(ctx, PREFS_CTX)) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_CTX, Context.MODE_PRIVATE);
                Map<String, ?> all = sp.getAll();
                fromPrefs = toPermSet(all.get(KEY_PREFIX + uid));
                if (fromPrefs != null && !fromPrefs.isEmpty()) return fromPrefs;
            } catch (Throwable t) {
                Log.w(TAG, "读 prefs 授权记录失败", t);
            }
        }
        Properties p = YlSystemContext.loadProps(authFile(server));
        Set<String> fromFile = toPermSet(p.getProperty(KEY_PREFIX + uid));
        if (fromFile != null) return fromFile;
        return fromPrefs == null ? new HashSet<String>() : fromPrefs;
    }

    /** 写授权记录；prefs 与文件两条路都做“写后回读校验”，任一成功即算成功 */
    private static boolean writeGrants(YlServerMain server, int uid, Set<String> perms) {
        GRANTS.put(uid, new HashSet<>(perms));
        String value = joinPerms(perms);

        Context ctx = server.context();
        if (ctx != null && YlSystemContext.prefsWritable(ctx, PREFS_CTX)) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_CTX, Context.MODE_PRIVATE);
                // 用 commit() 同步写：apply() 写失败不报错，没法判断结果
                boolean committed = sp.edit().putString(KEY_PREFIX + uid, value).commit();
                String readBack = sp.getString(KEY_PREFIX + uid, null);
                if (committed && equalsPerms(perms, readBack)) {
                    Log.i(TAG, "授权记录写入 prefs uid=" + uid);
                    return true;
                }
                Log.w(TAG, "prefs 写入未生效（committed=" + committed
                        + " 回读=" + readBack + "），退回文件");
            } catch (Throwable t) {
                Log.w(TAG, "写 prefs 失败，退回文件", t);
            }
        }

        File f = authFile(server);
        Properties p = YlSystemContext.loadProps(f);
        p.setProperty(KEY_PREFIX + uid, value);
        YlSystemContext.saveProps(f, p);
        String verify = YlSystemContext.loadProps(f).getProperty(KEY_PREFIX + uid, null);
        boolean ok = equalsPerms(perms, verify);
        if (!ok) {
            Log.e(TAG, "授权记录落盘校验失败 uid=" + uid + " 期望=" + value + " 实际=" + verify);
        }
        return ok;
    }

    private static File authFile(YlServerMain server) {
        return new File(YlSystemContext.storeDir(server.context()), AUTH_FILE);
    }

    // ==================================================================
    // 序列化
    // ==================================================================

    private static String joinPerms(Set<String> perms) {
        StringBuilder sb = new StringBuilder();
        for (String p : perms) {
            if (p == null || !YlProtocol.isKnownPermission(p)) continue;
            if (sb.length() > 0) sb.append(SEP);
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean equalsPerms(Set<String> perms, String value) {
        Set<String> parsed = parsePerms(value);
        return parsed != null && parsed.equals(perms);
    }

    /**
     * 解析授权记录字符串。
     *
     * @return null 表示“没有这条记录”（区别于空集合），未知权限名会被过滤掉
     */
    private static Set<String> parsePerms(String value) {
        if (value == null) return null;
        Set<String> out = new HashSet<>();
        for (String part : value.split("\\" + SEP)) {
            String p = part.trim();
            if (YlProtocol.isKnownPermission(p)) out.add(p);
        }
        return out;
    }

    /** 把 prefs 里读出来的对象转成权限集合；null 表示没有记录 */
    private static Set<String> toPermSet(Object v) {
        if (v == null) return null;
        if (v instanceof String) return parsePerms((String) v);
        if (v instanceof Boolean) {
            // 旧版：granted_uid_<uid> 存 boolean，true 等价于整包特权
            Set<String> out = new HashSet<>();
            if ((Boolean) v) out.add(YlProtocol.PERMISSION_PRIVILEGED);
            return out;
        }
        if (v instanceof Set) {
            Set<String> out = new HashSet<>();
            for (Object o : (Set<?>) v) {
                if (o instanceof String && YlProtocol.isKnownPermission((String) o)) {
                    out.add((String) o);
                }
            }
            return out;
        }
        return parsePerms(String.valueOf(v));
    }

    // ==================================================================

    /** 授权记录的落盘位置（日志/诊断用） */
    static String describeStore(YlServerMain server) {
        Context ctx = server.context();
        if (ctx != null) {
            try {
                return new File(ctx.getDataDir(),
                        "shared_prefs/" + PREFS_CTX + ".xml").getAbsolutePath()
                        + (YlSystemContext.prefsWritable(ctx, PREFS_CTX) ? "（可写）" : "（不可写→用文件）")
                        + " | 文件: " + authFile(server).getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        return authFile(server).getAbsolutePath();
    }
}
