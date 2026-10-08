package com.youlong.priv;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 自研特权内核的手写 Binder 协议（事务码 / 描述符 / Bundle 键 / 权限名）。
 *
 * <p>客户端与服务端共用，不使用 AIDL 生成代码，所有协议常量集中在这里，
 * 两端必须同步修改。
 */
public final class YlProtocol {

    private YlProtocol() {}

    /** 协议版本：客户端与服务端必须一致 */
    public static final int SERVER_VERSION = 1;

    // ==================================================================
    // 权限（自研命名空间 + 分级）
    // ==================================================================

    /**
     * 完全特权：允许在特权身份下拉起任意进程。
     *
     * <p>旧版本取值是 "privileged"，看着像系统权限名，实际系统里根本没有这个权限，
     * 容易被误读成“系统已授予”。现在改成自研命名空间，并由 YlPermission
     * 维护“已授权集合”做真实校验（未知权限名一律拒绝）。
     */
    public static final String PERMISSION_PRIVILEGED =
            "com.youlong.priv.permission.PRIVILEGED";

    /** 只读状态：查询服务端 uid / 自己是否被授权，不允许拉起进程 */
    public static final String PERMISSION_READ_STATE =
            "com.youlong.priv.permission.READ_STATE";

    /** 全部已知权限名（分级：PRIVILEGED 隐含 READ_STATE） */
    private static final String[] PERMISSIONS_ALL = {
            PERMISSION_PRIVILEGED,
            PERMISSION_READ_STATE,
    };

    /** 权限名是否是本内核认识的（未知权限名一律拒绝） */
    public static boolean isKnownPermission(String permission) {
        if (permission == null) return false;
        for (String p : PERMISSIONS_ALL) {
            if (p.equals(permission)) return true;
        }
        return false;
    }

    /** 已授权 granted 是否覆盖 requested（分级：最高级隐含其余级别） */
    public static boolean implies(String granted, String requested) {
        if (granted == null || requested == null) return false;
        if (granted.equals(requested)) return true;
        return PERMISSION_PRIVILEGED.equals(granted);
    }

    // ==================================================================
    // Bundle 键
    // ==================================================================

    public static final String KEY_API_VERSION = "api_version";
    public static final String KEY_PACKAGE_NAME = "package_name";
    /**
     * 已废弃：客户端自报的 uid 不可信，服务端只用 Binder.getCallingUid()。
     * 保留常量仅为了兼容旧版客户端写进来的 Bundle，服务端不再读取。
     */
    public static final String KEY_CLIENT_UID = "client_uid";
    public static final String KEY_PERMISSION_GRANTED = "permission_granted";
    public static final String KEY_SERVER_UID = "server_uid";
    public static final String KEY_SERVER_VERSION = "server_version";
    public static final String KEY_PERMISSION = "permission";
    public static final String KEY_ALLOWED = "allowed";
    /** 服务端 Binder 回传：Bundle 里的 IBinder（服务端 → 宿主 ContentProvider） */
    public static final String KEY_SERVICE_BINDER = "service_binder";

    // ==================================================================
    // IYlService 事务码
    // ==================================================================

    /** boolean ping() */
    public static final int TX_PING = 1;
    /** boolean checkPermission(String) */
    public static final int TX_CHECK_PERMISSION = 2;
    /** void requestPermission(int requestCode, String permission) */
    public static final int TX_REQUEST_PERMISSION = 3;
    /** void attachApplication(IYlApplication, Bundle) */
    public static final int TX_ATTACH = 4;
    /** IBinder newProcess(String[] cmd, String[] env, String dir, PFD in, PFD out, PFD err) */
    public static final int TX_NEW_PROCESS = 5;
    /** int getServerUid() */
    public static final int TX_GET_SERVER_UID = 6;

    // ==================================================================
    // IYlRemoteProcess 事务码
    // ==================================================================
    // 20 / 21 / 22（TX_PROC_OUT / TX_PROC_IN / TX_PROC_ERR）已删除：
    // 进程的 stdin/stdout/stderr 在 TX_NEW_PROCESS 时就已经以管道 fd 交付给客户端，
    // 这三个事务服务端从来没有实现（恒返回 null），属于死协议。

    /** int waitFor()：返回退出码；同 uid 等待名额已满时返回 {@link #PROC_WAIT_BUSY} */
    public static final int TX_PROC_WAIT = 23;
    /** int exitValue()：进程未结束时抛 IllegalThreadStateException */
    public static final int TX_PROC_EXIT = 24;
    /** void destroy() */
    public static final int TX_PROC_DESTROY = 25;
    /** boolean alive() */
    public static final int TX_PROC_ALIVE = 26;

    /**
     * waitFor 的“服务端忙”哨兵值：同一个 uid 已在阻塞等待的调用太多，
     * 服务端立即返回该值，客户端据此退化成 alive() 轮询，
     * 避免少数客户端把服务端 Binder 线程池（16 个线程）占满。
     * 正常退出码在 0-255 之间，不会与它冲突。
     */
    public static final int PROC_WAIT_BUSY = Integer.MIN_VALUE;

    // ==================================================================
    // IYlApplication 事务码（服务端 → 客户端回调）
    // ==================================================================

    /** void onServerReady() */
    public static final int TX_APP_SERVER_READY = 40;
    /** void onPermissionResult(int requestCode, String permission, boolean allowed) */
    public static final int TX_APP_PERM_RESULT = 41;

    // ==================================================================
    // 会合通道（LocalSocket 抽象命名空间）指令字
    // ==================================================================

    /** 宿主 → 服务端：请求把服务端 Binder 重新推送到宿主 Provider */
    public static final int CHANNEL_CMD_PUSH = 'P';
    /** 服务端 → 宿主：已重新推送成功 */
    public static final int CHANNEL_ACK_OK = 'K';
    /** 服务端 → 宿主：推送失败 */
    public static final int CHANNEL_ACK_FAIL = 'F';

    // ==================================================================
    // 描述符与工具
    // ==================================================================

    public static final String DESCRIPTOR_SERVICE = "com.youlong.priv.IYlService";
    public static final String DESCRIPTOR_PROCESS = "com.youlong.priv.IYlRemoteProcess";
    public static final String DESCRIPTOR_APPLICATION = "com.youlong.priv.IYlApplication";

    /** 事务开头写入接口描述符 */
    public static void writeDescriptor(Parcel data, String descriptor) {
        data.writeInterfaceToken(descriptor);
    }

    /** 事务开头校验接口描述符（不匹配抛 RemoteException） */
    public static void checkDescriptor(Parcel data, String descriptor) throws RemoteException {
        data.enforceInterface(descriptor);
    }

    /** 轻量探活：ping 事务 */
    public static boolean pingBinder(IBinder binder) {
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            writeDescriptor(data, DESCRIPTOR_SERVICE);
            binder.transact(TX_PING, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
