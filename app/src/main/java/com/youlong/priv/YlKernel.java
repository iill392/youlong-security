package com.youlong.priv;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.youlong.hd.YouLongApp;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 客户端单例：拉起特权服务端、完成握手、持有服务端 Binder。
 *
 * <p>握手链路（修复前是环：服务端没有回传 Binder 的通道，冷启动必然 15 秒超时）：
 * <ol>
 *   <li>客户端 arm 一个“握手 Binder + latch”（{@link #armHandshake}），
 *       并把同一个握手 Binder 放进 Bundle 交给 {@link YlBootProvider}；</li>
 *   <li>Provider 拉起服务端（命令行带上会合通道名与包名）；</li>
 *   <li>服务端起来后把 Binder 推进宿主的 Provider（{@code sendBinder}），
 *       Provider 调 {@link #onServiceBinder}；</li>
 *   <li>{@code onServiceBinder} 把 Binder 交付给所有等待中的握手方
 *       （{@link YlHandshake#deliver}）并 countDown，等待线程醒来拿到
 *       {@link YlService.Proxy}。</li>
 * </ol>
 */
public final class YlKernel {

    private static final String TAG = "YlKernel";

    /** 冷启动等待服务端回传 Binder 的超时（与 YlBootProvider 一致） */
    private static final long READY_TIMEOUT_MS = 15_000L;

    private static volatile YlKernel sInstance;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private volatile YlService.Proxy mService;
    private volatile IBinder mServiceBinder;
    private volatile boolean mStarted;

    /** 等待中的握手方：多个调用方并发等待时各自持有 latch，不再互相覆盖 */
    private static final class Pending {
        final IBinder handshake;
        final CountDownLatch latch;

        Pending(IBinder handshake, CountDownLatch latch) {
            this.handshake = handshake;
            this.latch = latch;
        }
    }

    private static final Pending[] EMPTY_PENDING = new Pending[0];

    private final Object mPendingLock = new Object();
    private final ArrayList<Pending> mPending = new ArrayList<>();

    private final CopyOnWriteArrayList<BinderReceivedListener> mReceivedListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BinderDeadListener> mDeadListeners =
            new CopyOnWriteArrayList<>();

    /** Binder 到手回调（主线程） */
    public interface BinderReceivedListener {
        void onBinderReceived();
    }

    /** 服务端断连回调（主线程） */
    public interface BinderDeadListener {
        void onBinderDead();
    }

    private YlKernel() {}

    public static YlKernel get() {
        YlKernel k = sInstance;
        if (k == null) {
            synchronized (YlKernel.class) {
                if (sInstance == null) sInstance = new YlKernel();
                k = sInstance;
            }
        }
        return k;
    }

    // ==================================================================
    // 对外能力（都要先 ensureService 成功才有意义）
    // ==================================================================

    public boolean pingBinder() {
        YlService.Proxy s = mService;
        return s != null && s.ping();
    }

    public boolean checkSelfPermission(String permission) {
        YlService.Proxy s = mService;
        return s != null && s.checkPermission(permission);
    }

    /** 在特权身份下拉起进程；服务端未就绪抛 IllegalStateException */
    public Process newProcess(String[] cmd, String[] env, String dir) throws Exception {
        YlService.Proxy s = ensureService();
        if (s == null) throw new IllegalStateException("特权服务未就绪");
        return s.newProcess(cmd, env, dir);
    }

    public int getServerUid() {
        YlService.Proxy s = mService;
        return s == null ? -1 : s.getServerUid();
    }

    public void requestPermission(int requestCode, String permission) {
        YlService.Proxy s = mService;
        if (s != null) s.requestPermission(requestCode, permission);
    }

    public void addBinderReceivedListener(BinderReceivedListener l) {
        if (l == null) return;
        mReceivedListeners.add(l);
        if (pingBinder()) {
            mMain.post(l::onBinderReceived);
        }
    }

    public void removeBinderReceivedListener(BinderReceivedListener l) {
        if (l != null) mReceivedListeners.remove(l);
    }

    public void addBinderDeadListener(BinderDeadListener l) {
        if (l != null) mDeadListeners.add(l);
    }

    public void removeBinderDeadListener(BinderDeadListener l) {
        if (l != null) mDeadListeners.remove(l);
    }

    // ==================================================================
    // 拉起与握手
    // ==================================================================

    /**
     * 确保服务端 Binder 可用：已连上直接返回；否则经 Provider 拉起并等服务端回传 Binder。
     *
     * @return 服务端代理；拉起/握手失败返回 null（如实返回，不再假装成功）
     */
    public YlService.Proxy ensureService() {
        YlService.Proxy s = mService;
        if (s != null && s.ping()) return s;

        Context ctx = appContext();
        if (ctx == null) {
            Log.w(TAG, "拿不到 Context，无法拉起服务端");
            return null;
        }
        synchronized (this) {
            s = mService;
            if (s != null && s.ping()) return s;

            CountDownLatch latch = new CountDownLatch(1);
            IBinder handshake = handshakeBinder();
            Pending pending = new Pending(handshake, latch);
            armPending(pending);

            long pid = -1;
            try {
                Bundle args = new Bundle();
                args.setClassLoader(YlHandshake.class.getClassLoader());
                args.putBinder(YlHandshake.KEY_HANDSHAKE, handshake);
                Bundle out = ctx.getContentResolver()
                        .call(YlBootProvider.URI, YlBootProvider.METHOD_START, null, args);
                if (out != null) {
                    pid = out.getLong(YlBootProvider.KEY_PID, -1);
                    String error = out.getString(YlBootProvider.KEY_ERROR);
                    if (error != null) Log.e(TAG, "Provider 拉起失败: " + error);
                } else {
                    Log.e(TAG, "Provider 无应答（" + YlBootProvider.URI + "）");
                }
                if (!latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "等待服务端握手超时（服务端未把 Binder 推送到 "
                            + YlBootProvider.AUTHORITY + "）");
                    // 失败自清理：否则每次失败都会留一个卡死的 youlong_priv 进程
                    YlPrivLauncher.killServer(pid);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "等待服务端握手被中断");
            } catch (Throwable t) {
                Log.e(TAG, "拉起服务端异常", t);
                YlPrivLauncher.killServer(pid);
            } finally {
                forgetPending(pending);
            }
            return mService;
        }
    }

    /**
     * 登记一个等待中的握手方（可并发调用，互不覆盖）。
     *
     * @param handshake 客户端的握手 Binder（服务端 Binder 到手后会向它交付）
     * @param latch     等待者自己的闸门，Binder 到手时 countDown
     */
    void armHandshake(IBinder handshake, CountDownLatch latch) {
        if (handshake == null || latch == null) return;
        armPending(new Pending(handshake, latch));
    }

    /** 服务端 Binder 到手：交付给所有等待中的握手方，再唤醒它们的等待线程 */
    void onServiceBinder(IBinder binder) {
        if (binder == null) return;
        final boolean sameBinder = (binder == mServiceBinder);
        if (!sameBinder) {
            YlService.Proxy proxy = new YlService.Proxy(binder);
            mService = proxy;
            mServiceBinder = binder;
            mStarted = true;
        }

        // 先把槽清空再交付：deliver 会同步回调本进程（同进程 Binder 直接调用），
        // 清空后即使回调重入也不会重复交付。
        Pending[] pending = drainPending();
        for (Pending p : pending) {
            YlHandshake.deliver(p.handshake, binder);
            p.latch.countDown();
        }
        if (sameBinder) return;    // 同进程重入：只需要补交付，别重复注册死亡通知/回调

        Log.i(TAG, "已连接特权服务 serverUid=" + getServerUid()
                + "，本次唤醒等待方=" + pending.length);
        mMain.post(() -> {
            for (BinderReceivedListener l : mReceivedListeners) {
                try { l.onBinderReceived(); } catch (Throwable ignored) {}
            }
        });
        try {
            binder.linkToDeath(() -> {
                Log.w(TAG, "特权服务已断开");
                mService = null;
                mServiceBinder = null;
                mStarted = false;
                mMain.post(() -> {
                    for (BinderDeadListener l : mDeadListeners) {
                        try { l.onBinderDead(); } catch (Throwable ignored) {}
                    }
                });
            }, 0);
        } catch (Throwable t) {
            Log.w(TAG, "linkToDeath 失败", t);
        }
    }

    private void armPending(Pending p) {
        synchronized (mPendingLock) {
            mPending.add(p);
        }
    }

    private Pending[] drainPending() {
        synchronized (mPendingLock) {
            if (mPending.isEmpty()) return EMPTY_PENDING;
            Pending[] out = mPending.toArray(EMPTY_PENDING);
            mPending.clear();
            return out;
        }
    }

    /** 超时/异常退出时清掉自己的槽位，避免把过期握手方留给下一次拉起 */
    private void forgetPending(Pending p) {
        synchronized (mPendingLock) {
            mPending.remove(p);
        }
    }

    IBinder serviceBinder() {
        return mServiceBinder;
    }

    boolean isStarted() {
        return mStarted;
    }

    /** 本进程的握手 Binder（交给服务端/BootProvider，用于接收服务端 Binder） */
    IBinder handshakeBinder() {
        return YlHandshake.asBinder(this::onServiceBinder);
    }

    String describeState() {
        YlService.Proxy s = mService;
        return "service=" + (s == null ? "null" : "ok")
                + " ping=" + (s != null && s.ping())
                + " started=" + mStarted
                + " apk=" + YlPrivLauncher.apkPath()
                + " app_process=" + YlPrivLauncher.appProcessPath();
    }

    private Context appContext() {
        YouLongApp app = YouLongApp.instance();
        return app;
    }

    /** 确保目录存在 */
    public static java.io.File ensureDir(java.io.File dir) {
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir;
    }
}
