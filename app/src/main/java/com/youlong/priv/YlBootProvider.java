package com.youlong.priv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 启动 Provider：客户端与服务端（{@code app_process}）的会合点。
 *
 * <p>两个方向各一条路：
 * <ul>
 *   <li>宿主 → 服务端：{@link #METHOD_START} 拉起服务端，命令行里带上会合通道名
 *       （抽象 Unix socket）和宿主包名；</li>
 *   <li>服务端 → 宿主：{@link #METHOD_SEND_BINDER}，服务端起来后把 Binder 塞进
 *       Bundle 调进来（Binder 只能走 Binder 事务，socket / 管道传不了 Binder 对象），
 *       本 Provider 校验调用方 uid 后交给 {@link YlKernel}。</li>
 * </ul>
 *
 * <p>注意：服务端如果跑在 shell(uid 2000) 下，需要清单里本 Provider
 * {@code android:exported="true"} 才调得进来；跑在应用自己的 uid 下则同 uid 直调。
 */
public final class YlBootProvider extends ContentProvider {

    private static final String TAG = "YlBootProvider";

    /** Provider 授权名（与 AndroidManifest 一致） */
    public static final String AUTHORITY = "com.youlong.hd.ylpriv";

    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    /** 拉起服务端并等待 Binder 回传 */
    public static final String METHOD_START = "start";

    /** 查询内核状态描述 */
    public static final String METHOD_STATUS = "status";

    /** 服务端把 Binder 推送到宿主（服务端 → 本 Provider） */
    public static final String METHOD_SEND_BINDER = "sendBinder";

    /** 返回体：错误信息（非空表示失败） */
    public static final String KEY_ERROR = "error";

    /** 返回体：服务端 pid */
    public static final String KEY_PID = "pid";

    /** 返回体：结果标记（already-running / started） */
    public static final String KEY_RESULT = "result";

    /** 等 Binder 回传的超时 */
    private static final long READY_TIMEOUT_MS = 15_000L;

    /** 排查线索：服务端自己的落盘日志（应用进程读不到 logcat 时看它） */
    private static final String SERVER_LOG_HINT = "/data/local/tmp/ylpriv_server.log";

    /** 同一时刻只允许一个拉起流程 */
    private static final Object SPAWN_LOCK = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        // 本 Provider 在 shell 身份的服务端场景下需要 exported=true 才调得进来，
        // 因此每个方法都先按调用方 uid 过滤（状态描述里包含 APK 路径，别泄露给别的应用）
        final int callerUid = Binder.getCallingUid();
        if (!isTrustedCaller(callerUid)) {
            Log.e(TAG, "拒绝不可信调用方 uid=" + callerUid + " method=" + method);
            out.putString(KEY_ERROR, "不可信调用方 uid=" + callerUid);
            return out;
        }
        if (METHOD_STATUS.equals(method)) {
            out.putString("state", YlKernel.get().describeState());
            return out;
        }
        if (METHOD_START.equals(method)) {
            return handleStart(extras, out);
        }
        if (METHOD_SEND_BINDER.equals(method)) {
            return handleSendBinder(extras, out);
        }
        out.putString(KEY_ERROR, "未知方法: " + method);
        return out;
    }

    // ==================================================================
    // 宿主 → 服务端：拉起 + 握手等待
    // ==================================================================

    /** 调用方 uid 已在 {@link #call} 里统一校验过 */
    private Bundle handleStart(Bundle extras, Bundle out) {
        IBinder handshake = YlHandshake.fromBundle(extras);
        if (handshake == null) {
            out.putString(KEY_ERROR, "缺少握手 Binder");
            return out;
        }

        YlKernel kernel = YlKernel.get();
        if (kernel.pingBinder()) {
            YlHandshake.deliver(handshake, kernel.serviceBinder());
            out.putString(KEY_RESULT, "already-running");
            out.putInt("serverUid", kernel.getServerUid());
            return out;
        }

        synchronized (SPAWN_LOCK) {
            if (kernel.pingBinder()) {
                YlHandshake.deliver(handshake, kernel.serviceBinder());
                out.putString(KEY_RESULT, "already-running");
                out.putInt("serverUid", kernel.getServerUid());
                return out;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            kernel.armHandshake(handshake, latch);

            final String channel = YlPrivLauncher.newChannelName();
            final String packageName = getContext() == null
                    ? null : getContext().getPackageName();
            YlPrivLauncher.SpawnResult r =
                    YlPrivLauncher.spawnServer(getContext(), channel, packageName);
            Log.i(TAG, "spawnServer ok=" + r.ok + " pid=" + r.pid + " msg=" + r.message
                    + " channel=" + channel);
            if (!r.ok) {
                out.putString(KEY_ERROR, "拉起服务端失败: " + r.message);
                Log.e(TAG, "拉起服务端失败: " + r.message);
                return out;
            }

            // 会合通道：连上就请服务端把 Binder 重推一次（首次推送可能早于宿主就绪）
            ChannelWatcher.start(channel, latch);

            try {
                if (!latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    // 如实失败：不再假装成功，同时清掉可能卡死的残留进程
                    out.putString(KEY_ERROR, "等待服务端 Binder 回传超时（未收到 "
                            + METHOD_SEND_BINDER + "，检查 " + AUTHORITY
                            + " 是否 exported、服务端日志 " + SERVER_LOG_HINT + "）");
                    Log.e(TAG, "等待服务端 Binder 回传超时 pid=" + r.pid);
                    YlPrivLauncher.killServer(r.pid);
                    return out;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.putString(KEY_ERROR, "握手被中断");
                YlPrivLauncher.killServer(r.pid);
                return out;
            }
            out.putString(KEY_RESULT, "started");
            out.putInt("serverUid", kernel.getServerUid());
            out.putLong(KEY_PID, r.pid);
            Log.i(TAG, "服务端握手完成 serverUid=" + kernel.getServerUid() + " pid=" + r.pid);
            return out;
        }
    }

    // ==================================================================
    // 服务端 → 宿主：接收 Binder 注入
    // ==================================================================

    /** 调用方 uid 已在 {@link #call} 里统一校验过 */
    private Bundle handleSendBinder(Bundle extras, Bundle out) {
        IBinder binder = extras == null ? null
                : extras.getBinder(YlProtocol.KEY_SERVICE_BINDER);
        if (binder == null) {
            out.putString(KEY_ERROR, "缺少服务端 Binder");
            return out;
        }
        YlKernel.get().onServiceBinder(binder);
        out.putString(KEY_RESULT, "binder-received");
        out.putInt("serverUid", YlKernel.get().getServerUid());
        return out;
    }

    /**
     * 只信任本应用自身 / root / shell(2000)。
     *
     * <p>服务端一定是这三者之一（同 uid 直调、root 或 shell 拉起）；
     * 其它应用即使拿到本 Provider 也不能注入 Binder 或触发拉起。
     */
    private static boolean isTrustedCaller(int uid) {
        return uid == Process.myUid() || uid == Process.ROOT_UID || uid == Process.SHELL_UID;
    }

    // ==================================================================
    // 会合通道（宿主侧）
    // ==================================================================

    /**
     * 连接服务端创建的抽象 Unix socket。
     *
     * <p>通道只跑一个控制字：连上后请服务端把 Binder 重推到本 Provider，
     * 拿到 ack 就收工。它不承载 Binder（物理上做不到），作用是
     * ① 证明服务端已起来；② 首次推送失败/宿主就绪较晚时触发重推。
     */
    private static final class ChannelWatcher {

        private static final long RETRY_MS = 300L;
        private static final int READ_TIMEOUT_MS = 2000;

        private static volatile Thread sThread;

        private ChannelWatcher() {}

        static void start(final String channel, final CountDownLatch delivered) {
            if (channel == null || channel.isEmpty()) return;
            Thread running = sThread;
            if (running != null && running.isAlive()) return;
            Thread t = new Thread(() -> run(channel, delivered), "yl-channel-watch");
            t.setDaemon(true);
            sThread = t;
            t.start();
        }

        private static void run(String channel, CountDownLatch delivered) {
            long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (delivered.getCount() == 0) return;      // Binder 已到手
                LocalSocket s = null;
                try {
                    s = new LocalSocket();
                    s.connect(new LocalSocketAddress(channel,
                            LocalSocketAddress.Namespace.ABSTRACT));
                    if (requestPush(s)) {
                        Log.i(TAG, "会合通道：已请求服务端重推 Binder");
                        return;
                    }
                } catch (Throwable t) {
                    // 服务端还没监听 / 通道不可达：等一会儿再试
                } finally {
                    closeQuietly(s);
                }
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Log.w(TAG, "会合通道：超时未连上 " + channel);
        }

        private static boolean requestPush(LocalSocket s) throws IOException {
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(YlProtocol.CHANNEL_CMD_PUSH);
            out.flush();
            int ack = s.getInputStream().read();
            return ack == YlProtocol.CHANNEL_ACK_OK;
        }

        private static void closeQuietly(LocalSocket s) {
            if (s == null) return;
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================================================================

    /** 特权内核的缓存目录 */
    public static File cacheDir(android.content.Context ctx) {
        return YlKernel.ensureDir(new File(ctx.getCacheDir(), "ylpriv"));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
