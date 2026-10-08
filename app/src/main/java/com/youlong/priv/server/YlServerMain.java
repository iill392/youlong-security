package com.youlong.priv.server;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.youlong.priv.YlApplication;
import com.youlong.priv.YlBootProvider;
import com.youlong.priv.YlPrivLauncher;
import com.youlong.priv.YlProtocol;
import com.youlong.priv.YlRemoteProcess;
import com.youlong.priv.YlService;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 特权服务端入口（{@code app_process ... YlServerMain}）。
 *
 * <p>启动参数（由 {@link YlPrivLauncher} 拼进命令行，app_process 会原样转给 main）：
 * <ul>
 *   <li>{@code --yl-channel=<抽象 socket 名>} 会合通道：等宿主连上来，可让服务端重新推送 Binder</li>
 *   <li>{@code --yl-pkg=<宿主包名>} 用于把包名解析成宿主 uid（权限裁决的可信来源之一）</li>
 * </ul>
 *
 * <p>Binder 回传：Binder 只能走 Binder 事务，socket/管道都传不了 Binder 对象。
 * 所以服务端起来后主动把 Binder 推进宿主的导出 ContentProvider
 * （{@link YlBootProvider#METHOD_SEND_BINDER}），宿主 Provider 再交给 YlKernel；
 * 会合通道只作为“服务端已起来 / 请求重新推送”的控制通道。
 */
public final class YlServerMain {

    private static final String TAG = "YlServer";

    private static final String LOG_FILE = "/data/local/tmp/ylpriv_server.log";
    /** 落盘日志上限：超过就截断重写，避免无限追加把 /data/local/tmp 写爆 */
    private static final long MAX_LOG_BYTES = 256 * 1024L;
    /** 单条日志上限（防止把整条 shell 脚本/超大异常砸进日志） */
    private static final int MAX_LOG_CHARS = 2048;

    /** 向宿主推送 Binder 的总时长与重试间隔 */
    private static final long PUSH_TIMEOUT_MS = 15_000L;
    private static final long PUSH_RETRY_MS = 400L;

    /** 每个调用方 uid 同时活跃的远程进程数上限 */
    private static final int MAX_PROC_PER_UID = 8;
    /** 每个调用方 uid 同时阻塞等待进程结束的数量上限（保护 Binder 线程池） */
    private static final int MAX_WAIT_PER_UID = 4;

    /** 会合通道读写超时 */
    private static final int CHANNEL_READ_TIMEOUT_MS = 3000;

    private static volatile YlServerMain sInstance;

    private Context mContext;
    /** 服务端 Binder：只创建一次，推送与进程回调共用同一实例 */
    private volatile IBinder mServiceBinder;

    private boolean mInitialized;
    private final AtomicInteger mProcessSeq = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, YlRemoteProcessImpl> mProcesses =
            new ConcurrentHashMap<>();
    /** 调用方 uid → 活跃远程进程数 */
    private final ConcurrentHashMap<Integer, AtomicInteger> mProcessCount =
            new ConcurrentHashMap<>();
    /** 调用方 uid → 正在阻塞等待 waitFor 的数量 */
    private final ConcurrentHashMap<Integer, AtomicInteger> mWaitCount =
            new ConcurrentHashMap<>();

    private YlServerMain() {}

    public static YlServerMain get() {
        YlServerMain s = sInstance;
        if (s == null) {
            synchronized (YlServerMain.class) {
                if (sInstance == null) sInstance = new YlServerMain();
                s = sInstance;
            }
        }
        return s;
    }

    // ==================================================================
    // 入口
    // ==================================================================

    public static void main(String[] args) {
        final String channel = argValue(args, YlPrivLauncher.ARG_CHANNEL);
        final String hostPackage = argValue(args, YlPrivLauncher.ARG_PKG);
        diag("main() 进入，args=" + java.util.Arrays.toString(args)
                + " uid=" + Process.myUid() + " pid=" + Process.myPid());
        try {
            Looper.prepareMainLooper();
            diag("Looper 就绪，开始 init()");
            get().init(hostPackage);
            diag("init() 完成，开始发布 Binder（channel=" + channel + "）");
            get().publish(channel);
            diag("Binder 已发布，进入 Looper.loop()");
            Looper.loop();
        } catch (Throwable t) {
            diag("服务端启动失败: " + t);
            Log.e(TAG, "服务端启动失败", t);
            System.exit(1);
        }
    }

    /** 从参数表里取 {@code --key=value} 形式的取值，取不到返回 null */
    private static String argValue(String[] args, String prefix) {
        if (args == null || prefix == null) return null;
        for (String a : args) {
            if (a != null && a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }

    /** 诊断日志：磁盘上留一份（应用进程读不到 logcat 时可以看它），并限长截断 */
    static void diag(String msg) {
        String text = msg == null ? "null" : msg;
        if (text.length() > MAX_LOG_CHARS) {
            text = text.substring(0, MAX_LOG_CHARS) + "…(截断)";
        }
        String time = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date());
        File f = new File(LOG_FILE);
        java.io.FileOutputStream fos = null;
        try {
            boolean over = f.exists() && f.length() > MAX_LOG_BYTES;
            fos = new java.io.FileOutputStream(f, !over);
            if (over) {
                fos.write(("[" + time + "] （日志超过 " + (MAX_LOG_BYTES / 1024)
                        + "KB，已截断重写）\n").getBytes("UTF-8"));
            }
            fos.write(("[" + time + "] " + text + "\n").getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(fos);
        }
        Log.i(TAG, text);
    }

    private void init(String hostPackage) {
        if (mInitialized) return;
        mInitialized = true;
        mContext = YlSystemContext.get();
        if (hostPackage != null && !hostPackage.isEmpty()) {
            YlPermission.noteHostPackage(this, hostPackage);
        }
        Log.i(TAG, "服务端已就绪，Context="
                + (mContext == null ? "null(退回文件存储)" : mContext.getPackageName())
                + "，授权记录=" + YlPermission.describeStore(this));
    }

    public Context context() {
        return mContext;
    }

    private synchronized IBinder serviceBinder() {
        if (mServiceBinder == null) {
            mServiceBinder = asBinder();
        }
        return mServiceBinder;
    }

    // ==================================================================
    // Binder 发布：推送到宿主 + 会合通道
    // ==================================================================

    /** 发布顺序：先起会合通道（宿主可能正在重试连接），再推 Binder（带重试） */
    private void publish(String channel) {
        final IBinder binder = serviceBinder();
        if (channel != null && !channel.isEmpty()) {
            startChannelServer(channel, binder);
        }
        pushBinderWithRetry(binder, PUSH_TIMEOUT_MS);
    }

    /** 重试推送：宿主 Provider 可能还没就绪（进程刚起/被系统杀过） */
    private void pushBinderWithRetry(final IBinder binder, final long timeoutMs) {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (pushBinderToHost(binder)) return;
                try {
                    Thread.sleep(PUSH_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            Log.w(TAG, "推送 Binder 超时：宿主未接收（宿主可能没在跑或 Provider 不可达）");
        }, "yl-push-binder");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把服务端 Binder 推进宿主的导出 ContentProvider。
     *
     * <p>这是 Binder 唯一能跨进程传递的通道：{@code Bundle.putBinder} 走 Binder 事务。
     * 宿主侧 {@link YlBootProvider} 会校验调用方 uid 后交给 YlKernel。
     */
    private boolean pushBinderToHost(IBinder binder) {
        Context ctx = mContext;
        if (ctx == null) {
            Log.w(TAG, "无 Context，无法推送 Binder");
            return false;
        }
        if (binder == null) return false;
        try {
            Bundle extras = new Bundle();
            extras.putBinder(YlProtocol.KEY_SERVICE_BINDER, binder);
            Bundle reply = ctx.getContentResolver().call(
                    Uri.parse("content://" + YlBootProvider.AUTHORITY),
                    YlBootProvider.METHOD_SEND_BINDER, null, extras);
            if (reply == null) {
                Log.w(TAG, "推送 Binder：宿主无应答");
                return false;
            }
            String err = reply.getString(YlBootProvider.KEY_ERROR);
            if (err != null) {
                Log.w(TAG, "推送 Binder 被拒绝: " + err);
                return false;
            }
            Log.i(TAG, "服务端 Binder 已推送到宿主");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "推送 Binder 失败", t);
            return false;
        }
    }

    /**
     * 会合通道（服务端侧）：监听抽象 Unix socket，等宿主连上来。
     *
     * <p>通道只跑控制字，不承载 Binder —— Binder 走上面的 Provider 推送。
     * 宿主要求重推时（比如首次推送时它还没准备好）就地重推一次并回 ack。
     */
    private void startChannelServer(final String channel, final IBinder binder) {
        Thread t = new Thread(() -> {
            LocalServerSocket server = null;
            try {
                server = new LocalServerSocket(channel);
                Log.i(TAG, "会合通道已就绪: " + channel);
            } catch (Throwable e) {
                Log.w(TAG, "会合通道创建失败（不影响 Provider 推送）: " + channel, e);
                return;
            }
            while (true) {
                LocalSocket c = null;
                try {
                    c = server.accept();
                    c.setSoTimeout(CHANNEL_READ_TIMEOUT_MS);
                    InputStream in = c.getInputStream();
                    OutputStream out = c.getOutputStream();
                    int cmd = in.read();
                    if (cmd == YlProtocol.CHANNEL_CMD_PUSH) {
                        boolean ok = pushBinderToHost(binder);
                        out.write(ok ? YlProtocol.CHANNEL_ACK_OK : YlProtocol.CHANNEL_ACK_FAIL);
                        out.flush();
                        Log.i(TAG, "宿主请求重推 Binder，结果=" + ok);
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "会合通道处理异常（继续监听）", e);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } finally {
                    closeQuietly(c);
                }
            }
        }, "yl-channel-server");
        t.setDaemon(true);
        t.start();
    }

    // ==================================================================
    // IYlService 实现
    // ==================================================================

    public IBinder asBinder() {
        return YlService.asBinder(new YlService.Stub() {
            @Override
            public boolean ping() {
                return true;
            }

            @Override
            public boolean checkPermission(String permission) {
                return YlPermission.check(YlServerMain.this, permission);
            }

            @Override
            public void requestPermission(int requestCode, String permission) {
                YlPermission.request(YlServerMain.this, requestCode, permission);
            }

            @Override
            public void attachApplication(IBinder callback, Bundle args) {
                YlPermission.attachApplication(YlServerMain.this,
                        callback == null ? null : new YlApplication(callback), args);
            }

            @Override
            public IBinder newProcess(String[] cmd, String[] env, String dir,
                                      ParcelFileDescriptor stdinRead,
                                      ParcelFileDescriptor stdoutWrite,
                                      ParcelFileDescriptor stderrWrite)
                    throws android.os.RemoteException {
                final int callerUid = Binder.getCallingUid();
                // 未授权 / 超限 / 启动失败分别抛不同异常，客户端可据此区分
                // （旧实现三种情况一律返回 null，客户端只能瞎猜）
                if (!YlPermission.checkPermission(YlServerMain.this, callerUid,
                        YlProtocol.PERMISSION_PRIVILEGED)) {
                    Log.w(TAG, "拒绝未授权 uid 的进程请求: " + callerUid);
                    throw new SecurityException("未授权: uid=" + callerUid
                            + " 缺少 " + YlProtocol.PERMISSION_PRIVILEGED);
                }
                if (cmd == null || cmd.length == 0) {
                    throw new android.os.RemoteException("命令为空");
                }
                if (!acquireProcessSlot(callerUid)) {
                    Log.w(TAG, "同 uid 活跃进程数已达上限 uid=" + callerUid
                            + " 上限=" + MAX_PROC_PER_UID);
                    throw new android.os.RemoteException("同 uid 活跃进程数已达上限("
                            + MAX_PROC_PER_UID + ")");
                }
                final int id = mProcessSeq.incrementAndGet();
                YlRemoteProcessImpl impl = new YlRemoteProcessImpl(id, callerUid, cmd, env, dir,
                        stdinRead, stdoutWrite, stderrWrite);
                if (!impl.start()) {
                    releaseProcessSlot(callerUid);
                    Log.e(TAG, "拉起进程失败: " + java.util.Arrays.toString(cmd));
                    throw new android.os.RemoteException("拉起进程失败: "
                            + java.util.Arrays.toString(cmd));
                }
                mProcesses.put(id, impl);
                Log.i(TAG, "远程进程已登记 id=" + id + " callerUid=" + callerUid);
                return YlRemoteProcess.asBinder(impl);
            }

            @Override
            public int getServerUid() {
                return Process.myUid();
            }
        });
    }

    // ==================================================================
    // 进程表
    // ==================================================================

    /** 进程结束/被销毁时清理登记，顺手释放该 uid 的进程名额 */
    void forget(int id) {
        YlRemoteProcessImpl impl = mProcesses.remove(id);
        if (impl == null) return;
        releaseProcessSlot(impl.callerUid());
        Log.i(TAG, "进程表已清理 id=" + id);
    }

    private boolean acquireProcessSlot(int uid) {
        AtomicInteger c = counter(mProcessCount, uid);
        while (true) {
            int now = c.get();
            if (now >= MAX_PROC_PER_UID) return false;
            if (c.compareAndSet(now, now + 1)) return true;
        }
    }

    private void releaseProcessSlot(int uid) {
        decrement(mProcessCount, uid);
    }

    private boolean acquireWaitSlot(int uid) {
        AtomicInteger c = counter(mWaitCount, uid);
        while (true) {
            int now = c.get();
            if (now >= MAX_WAIT_PER_UID) return false;
            if (c.compareAndSet(now, now + 1)) return true;
        }
    }

    private void releaseWaitSlot(int uid) {
        decrement(mWaitCount, uid);
    }

    private static AtomicInteger counter(ConcurrentHashMap<Integer, AtomicInteger> map, int uid) {
        AtomicInteger c = map.get(uid);
        if (c == null) {
            c = new AtomicInteger(0);
            AtomicInteger old = map.putIfAbsent(uid, c);
            if (old != null) c = old;
        }
        return c;
    }

    private static void decrement(ConcurrentHashMap<Integer, AtomicInteger> map, int uid) {
        AtomicInteger c = map.get(uid);
        if (c == null) return;
        while (true) {
            int now = c.get();
            if (now <= 0) return;
            if (c.compareAndSet(now, now - 1)) return;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    // ==================================================================
    // 远程进程实现
    // ==================================================================

    static final class YlRemoteProcessImpl implements YlRemoteProcess.Stub {

        private final int mId;
        private final int mCallerUid;
        private final String[] mCmd;
        private final String[] mEnv;
        private final String mDir;
        private final ParcelFileDescriptor mStdinRead;
        private final ParcelFileDescriptor mStdoutWrite;
        private final ParcelFileDescriptor mStderrWrite;

        private java.lang.Process mProc;
        private int mExit = -1;
        private boolean mDone;

        YlRemoteProcessImpl(int id, int callerUid, String[] cmd, String[] env, String dir,
                            ParcelFileDescriptor stdinRead,
                            ParcelFileDescriptor stdoutWrite,
                            ParcelFileDescriptor stderrWrite) {
            this.mId = id;
            this.mCallerUid = callerUid;
            this.mCmd = cmd;
            this.mEnv = env;
            this.mDir = YlService.normalizeDir(dir);
            this.mStdinRead = stdinRead;
            this.mStdoutWrite = stdoutWrite;
            this.mStderrWrite = stderrWrite;
        }

        int callerUid() {
            return mCallerUid;
        }

        synchronized boolean start() {
            try {
                // 用 shell 拼命令：Java 的 ProcessBuilder 在 API 24 上没有
                // “把管道 fd 交给子进程”的能力，只能让 sh 用 /proc/self/fd/<n> 重定向，
                // 这些 fd 就是服务端从 Binder 收到的管道端。
                //
                // 注意：权限放大只发生在 shell 身份的服务端进程内部，
                // 命令行参数全部 shellQuote 过，客户端无法注入额外命令。
                StringBuilder script = new StringBuilder();
                for (int i = 0; i < mCmd.length; i++) {
                    if (i > 0) script.append(' ');
                    script.append(shellQuote(mCmd[i]));
                }
                String inPath = fdPath(mStdinRead);
                String outPath = fdPath(mStdoutWrite);
                String errPath = fdPath(mStderrWrite);
                if (inPath != null) script.append(" < ").append(inPath);
                if (outPath != null) script.append(" > ").append(outPath);
                if (errPath != null) script.append(" 2> ").append(errPath);
                Log.i(TAG, "进程脚本: " + script);

                ProcessBuilder pb = new ProcessBuilder("sh", "-c", script.toString());
                if (mEnv != null && mEnv.length > 0) {
                    pb.environment().clear();
                    for (String e : mEnv) {
                        int i = e.indexOf('=');
                        if (i > 0) pb.environment().put(e.substring(0, i), e.substring(i + 1));
                    }
                }
                if (mDir != null) pb.directory(new File(mDir));
                // 三个流默认就是 PIPE，这里不显式设置
                // （ProcessBuilder.Redirect 是 API 26+，minSdk 24 上会 NoSuchMethodError）

                mProc = pb.start();
                Log.i(TAG, "进程已启动 id=" + mId + " cmd=" + java.util.Arrays.toString(mCmd)
                        + " serverUid=" + Process.myUid());
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "start 失败", t);
                mProc = null;
                return false;
            }
        }

        /** 取 ParcelFileDescriptor 的原始 fd 号，拼成子进程可重定向的 /proc/self/fd/<n> */
        private static String fdPath(ParcelFileDescriptor pfd) {
            if (pfd == null) return null;
            try {
                java.io.FileDescriptor fd = pfd.getFileDescriptor();
                java.lang.reflect.Field f = java.io.FileDescriptor.class.getDeclaredField("descriptor");
                f.setAccessible(true);
                int raw = (Integer) f.get(fd);
                if (raw < 0) return null;
                return "/proc/self/fd/" + raw;
            } catch (Throwable t) {
                Log.w(TAG, "取 fd 号失败", t);
                return null;
            }
        }

        private static String shellQuote(String s) {
            if (s == null) return "''";
            return "'" + s.replace("'", "'\\''") + "'";
        }

        /**
         * 阻塞等待进程结束。
         *
         * <p>这个方法会一直占着调用它的 Binder 线程，所以服务端对每个 uid 限了
         * {@link #MAX_WAIT_PER_UID} 个并发等待名额；名额满了立刻返回
         * {@link YlProtocol#PROC_WAIT_BUSY}，让客户端改用 alive() 轮询，
         * 避免少数客户端把服务端 Binder 线程池（16 个）占满。
         *
         * <p>注意：阻塞期间不持有本对象的锁，否则同进程的 alive()/exitValue()
         * 会被一起堵住，轮询退化方案就失效了。
         */
        @Override
        public int waitFor() {
            java.lang.Process proc;
            synchronized (this) {
                if (mDone) return mExit;
                proc = mProc;
            }
            if (proc == null) return mExit;

            final int uid = Binder.getCallingUid();
            if (!YlServerMain.get().acquireWaitSlot(uid)) {
                Log.w(TAG, "同 uid 并发等待已达上限(" + MAX_WAIT_PER_UID + ")，返回重试哨兵"
                        + " uid=" + uid + " id=" + mId);
                return YlProtocol.PROC_WAIT_BUSY;
            }
            int code;
            try {
                code = proc.waitFor();
            } catch (Throwable t) {
                Log.w(TAG, "waitFor 被中断/异常 id=" + mId, t);
                return YlProtocol.PROC_WAIT_BUSY;
            } finally {
                YlServerMain.get().releaseWaitSlot(uid);
            }
            synchronized (this) {
                mExit = code;
                mDone = true;
            }
            YlServerMain.get().forget(mId);
            return code;
        }

        /**
         * 取退出码：进程没结束就抛 {@link IllegalThreadStateException}
         * （旧实现把异常吞掉返回 -1，客户端永远读到假的退出码）。
         */
        @Override
        public synchronized int exitValue() {
            if (mDone) return mExit;
            if (mProc == null) {
                throw new IllegalThreadStateException("进程未启动");
            }
            try {
                mExit = mProc.exitValue();
                mDone = true;
                YlServerMain.get().forget(mId);
                return mExit;
            } catch (IllegalThreadStateException notExited) {
                throw notExited;
            } catch (Throwable t) {
                throw new IllegalThreadStateException("取退出码失败: " + t);
            }
        }

        /** 探活：检测到已退出时保存退出码并清理进程表（旧实现把退出码丢掉了） */
        @Override
        public synchronized boolean alive() {
            if (mDone) return false;
            if (mProc == null) {
                mDone = true;
                YlServerMain.get().forget(mId);
                return false;
            }
            try {
                mExit = mProc.exitValue();
                mDone = true;
                YlServerMain.get().forget(mId);
                Log.i(TAG, "进程已退出 id=" + mId + " exit=" + mExit);
                return false;
            } catch (IllegalThreadStateException running) {
                return true;
            } catch (Throwable t) {
                // 取状态异常时保守认为还在跑，避免误报“已退出”把客户端带偏
                Log.w(TAG, "alive 检测异常 id=" + mId, t);
                return true;
            }
        }

        @Override
        public synchronized void destroy() {
            try {
                if (mProc != null && !mDone) {
                    mProc.destroy();
                }
            } catch (Throwable t) {
                Log.w(TAG, "destroy 失败 id=" + mId, t);
            }
            YlServerMain.get().forget(mId);
        }
    }
}
