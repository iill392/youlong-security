package com.youlong.priv;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 客户端代理 / 服务端桩的公共壳（手写 Binder 协议，见 {@link YlProtocol}）。
 */
public final class YlService {

    private static final String TAG = "YlService";

    private YlService() {}

    /** waitFor 退化成 alive() 轮询时的轮询间隔 */
    private static final long WAIT_POLL_MS = 100L;

    
    public static final int SERVER_VERSION = YlProtocol.SERVER_VERSION;

    
    public static final String PERMISSION_PRIVILEGED = YlProtocol.PERMISSION_PRIVILEGED;

    
    public static final int REQ_PERMISSION = 1001;

    
    public static final String KEY_CALLBACK = "callback";

    // ==================================================================
    
    // ==================================================================

    
    public static final class Proxy {
        private final IBinder mRemote;

        public Proxy(IBinder remote) {
            this.mRemote = remote;
        }

        public IBinder asBinder() {
            return mRemote;
        }

        
        public boolean ping() {
            return YlProtocol.pingBinder(mRemote);
        }

        
        public boolean checkPermission(String permission) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeString(permission);
                mRemote.transact(YlProtocol.TX_CHECK_PERMISSION, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } catch (Throwable t) {
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /**
         * 申请权限（服务端裁决并回推结果，最终由 {@code YlApplication.onPermissionResult} 通知）。
         *
         * <p>事务失败不抛给调用方（申请是异步的），但要记录，别静默吞掉。
         */
        public void requestPermission(int requestCode, String permission) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeInt(requestCode);
                data.writeString(permission);
                mRemote.transact(YlProtocol.TX_REQUEST_PERMISSION, data, reply, 0);
                reply.readException();
            } catch (Throwable t) {
                Log.w(TAG, "申请权限事务失败 permission=" + permission, t);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /**
         * 挂载客户端回调（服务端据此按 uid 记录宿主、回推权限结果）。
         *
         * <p>失败只记日志：挂载失败后续调用会被服务端按未授权拒掉，不值得在这里抛异常。
         */
        public void attachApplication(IBinder callback, String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeStrongBinder(callback);
                Bundle args = new Bundle();
                args.putInt(YlProtocol.KEY_API_VERSION, SERVER_VERSION);
                args.putString(YlProtocol.KEY_PACKAGE_NAME, packageName);
                args.writeToParcel(data, 0);
                mRemote.transact(YlProtocol.TX_ATTACH, data, reply, 0);
                reply.readException();
            } catch (Throwable t) {
                Log.w(TAG, "挂载客户端回调失败 pkg=" + packageName, t);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        
        public int getServerUid() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                mRemote.transact(YlProtocol.TX_GET_SERVER_UID, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } catch (Throwable t) {
                return -1;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /**
         * 拉起远程进程。
         *
         * <p>stdin/stdout/stderr 三对管道在这里建好，服务端那一半随事务交出去；
         * 本进程只保留用得到的三端（stdin 写 / stdout 读 / stderr 读）。
         * 失败时六个 fd 全部关闭后再抛，避免失败路径漏 fd。
         *
         * @throws SecurityException 调用方未授权（服务端裁决）
         * @throws RemoteException   服务端拉起失败 / 同 uid 进程数超限
         */
        public Process newProcess(String[] cmd, String[] env, String dir)
                throws RemoteException {
            ParcelFileDescriptor[] stdinPipe = null;
            ParcelFileDescriptor[] stdoutPipe = null;
            ParcelFileDescriptor[] stderrPipe = null;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            boolean handedOver = false;
            try {
                // 客户端先做一次同样的目录检查：客户端拿不到目录时不要浪费一次事务
                dir = normalizeDir(dir);

                stdinPipe = ParcelFileDescriptor.createReliablePipe();
                stdoutPipe = ParcelFileDescriptor.createReliablePipe();
                stderrPipe = ParcelFileDescriptor.createReliablePipe();

                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeStringArray(cmd);
                data.writeStringArray(env);
                data.writeString(dir);
                stdinPipe[0].writeToParcel(data, 0);    // 服务端持有读端
                stdoutPipe[1].writeToParcel(data, 0);   // 服务端持有写端
                stderrPipe[1].writeToParcel(data, 0);   // 服务端持有写端

                mRemote.transact(YlProtocol.TX_NEW_PROCESS, data, reply, 0);
                reply.readException();
                IBinder procBinder = reply.readStrongBinder();
                if (procBinder == null) {
                    throw new RemoteException("服务端未返回远程进程");
                }
                // 交出去的三个 fd 归服务端，剩下三个归 YlProcess
                closeQuietly(stdinPipe[0]);
                closeQuietly(stdoutPipe[1]);
                closeQuietly(stderrPipe[1]);
                YlProcess proc = new YlProcess(new YlRemoteProcess(procBinder),
                        stdinPipe[1], stdoutPipe[0], stderrPipe[0]);
                handedOver = true;
                return proc;
            } catch (SecurityException e) {
                throw e;                    // 未授权：保留原异常类型，调用方可区分
            } catch (RemoteException e) {
                throw e;                    // 拉起失败 / 超限：保留原异常类型
            } catch (Throwable t) {
                throw new RemoteException("建管道/发起进程失败: " + t);
            } finally {
                if (!handedOver) {
                    // 失败路径统一清理：六个 fd 一个都不留
                    closeQuietly(stdinPipe == null ? null : stdinPipe[0]);
                    closeQuietly(stdinPipe == null ? null : stdinPipe[1]);
                    closeQuietly(stdoutPipe == null ? null : stdoutPipe[0]);
                    closeQuietly(stdoutPipe == null ? null : stdoutPipe[1]);
                    closeQuietly(stderrPipe == null ? null : stderrPipe[0]);
                    closeQuietly(stderrPipe == null ? null : stderrPipe[1]);
                }
                reply.recycle();
                data.recycle();
            }
        }

        private static void closeQuietly(ParcelFileDescriptor p) {
            if (p == null) return;
            try { p.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================================================================
    
    // ==================================================================

    
    public static final class YlProcess extends Process {
        private final YlRemoteProcess mRemote;
        private final android.os.ParcelFileDescriptor mStdinWrite;   
        private final android.os.ParcelFileDescriptor mStdoutRead;   
        private final android.os.ParcelFileDescriptor mStderrRead;   
        private InputStream mIn;
        private OutputStream mOut;
        private InputStream mErr;
        private final Object mStateLock = new Object();
        private volatile int mExit = -1;
        private volatile boolean mDone;

        YlProcess(YlRemoteProcess remote,
                  android.os.ParcelFileDescriptor stdinWrite,
                  android.os.ParcelFileDescriptor stdoutRead,
                  android.os.ParcelFileDescriptor stderrRead) {
            this.mRemote = remote;
            this.mStdinWrite = stdinWrite;
            this.mStdoutRead = stdoutRead;
            this.mStderrRead = stderrRead;
        }

        @Override
        public synchronized OutputStream getOutputStream() {
            if (mOut == null) {
                mOut = (mStdinWrite == null) ? nullOut()
                        : new android.os.ParcelFileDescriptor.AutoCloseOutputStream(mStdinWrite);
            }
            return mOut;
        }

        @Override
        public synchronized InputStream getInputStream() {
            if (mIn == null) {
                mIn = (mStdoutRead == null) ? emptyIn()
                        : new android.os.ParcelFileDescriptor.AutoCloseInputStream(mStdoutRead);
            }
            return mIn;
        }

        @Override
        public synchronized InputStream getErrorStream() {
            if (mErr == null) {
                mErr = (mStderrRead == null) ? emptyIn()
                        : new android.os.ParcelFileDescriptor.AutoCloseInputStream(mStderrRead);
            }
            return mErr;
        }

        /**
         * 等进程结束。
         *
         * <p>服务端对同一 uid 的阻塞等待有并发上限（见 {@link YlProtocol#PROC_WAIT_BUSY}）：
         * 名额满时远端立刻返回哨兵值，这里退化成 alive() 轮询，避免把服务端
         * Binder 线程池占满，也避免本线程被一个永久阻塞的事务钉死。
         *
         * <p>本方法刻意不加 `synchronized`：等待可能持续到进程结束，
         * 持锁会把同对象上的 getInputStream()/alive() 一起堵死。
         */
        @Override
        public int waitFor() throws InterruptedException {
            if (mDone) return mExit;
            while (true) {
                int code;
                try {
                    code = mRemote.waitFor();
                } catch (Throwable t) {
                    // 远端不可达（服务端重启、进程记录已清理）：如实记录，不再假装退出码
                    Log.w(TAG, "waitFor 远端失败，未能取到退出码", t);
                    synchronized (mStateLock) { mDone = true; }
                    return mExit;
                }
                if (code != YlProtocol.PROC_WAIT_BUSY) {
                    synchronized (mStateLock) {
                        mExit = code;
                        mDone = true;
                    }
                    return mExit;
                }
                try {
                    if (!mRemote.alive()) {
                        int value = mRemote.exitValue();
                        synchronized (mStateLock) {
                            mExit = value;
                            mDone = true;
                        }
                        return value;
                    }
                } catch (Throwable ignored) {
                }
                Thread.sleep(WAIT_POLL_MS);
            }
        }

        @Override
        public int exitValue() {
            if (mDone) return mExit;
            try {
                if (!mRemote.alive()) {
                    int value = mRemote.exitValue();
                    synchronized (mStateLock) {
                        mExit = value;
                        mDone = true;
                    }
                    return value;
                }
            } catch (Throwable ignored) {
            }
            throw new IllegalThreadStateException("进程尚未结束");
        }

        @Override
        public synchronized void destroy() {
            try {
                mRemote.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================================================================
    
    // ==================================================================

    
    public interface Stub {
        boolean ping();

        boolean checkPermission(String permission);

        void requestPermission(int requestCode, String permission);

        void attachApplication(IBinder callback, Bundle args);

        
        IBinder newProcess(String[] cmd, String[] env, String dir,
                           android.os.ParcelFileDescriptor stdinRead,
                           android.os.ParcelFileDescriptor stdoutWrite,
                           android.os.ParcelFileDescriptor stderrWrite) throws RemoteException;

        int getServerUid();
    }

    
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                switch (code) {
                    case YlProtocol.TX_PING:
                        reply.writeNoException();
                        reply.writeInt(impl.ping() ? 1 : 0);
                        return true;
                    case YlProtocol.TX_CHECK_PERMISSION: {
                        String permission = data.readString();
                        reply.writeNoException();
                        reply.writeInt(impl.checkPermission(permission) ? 1 : 0);
                        return true;
                    }
                    case YlProtocol.TX_REQUEST_PERMISSION: {
                        int requestCode = data.readInt();
                        String permission = data.readString();
                        impl.requestPermission(requestCode, permission);
                        reply.writeNoException();
                        return true;
                    }
                    case YlProtocol.TX_ATTACH: {
                        IBinder callback = data.readStrongBinder();
                        Bundle args = data.readInt() != 0
                                ? Bundle.CREATOR.createFromParcel(data) : null;
                        impl.attachApplication(callback, args);
                        reply.writeNoException();
                        return true;
                    }
                    case YlProtocol.TX_NEW_PROCESS: {
                        String[] cmd = data.createStringArray();
                        String[] env = data.createStringArray();
                        String dir = data.readString();
                        android.os.ParcelFileDescriptor stdinRead =
                                android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data);
                        android.os.ParcelFileDescriptor stdoutWrite =
                                android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data);
                        android.os.ParcelFileDescriptor stderrWrite =
                                android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data);
                        IBinder proc = null;
                        try {
                            proc = impl.newProcess(cmd, env, dir,
                                    stdinRead, stdoutWrite, stderrWrite);
                        } finally {
                            
                            closeQuietly(stdinRead);
                            closeQuietly(stdoutWrite);
                            closeQuietly(stderrWrite);
                        }
                        reply.writeNoException();
                        reply.writeStrongBinder(proc);
                        return true;
                    }
                    case YlProtocol.TX_GET_SERVER_UID:
                        reply.writeNoException();
                        reply.writeInt(impl.getServerUid());
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    
    public static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        File f = new File(dir);
        return f.isDirectory() ? dir : null;
    }

    
    public static InputStream emptyIn() {
        return new java.io.ByteArrayInputStream(new byte[0]);
    }

    
    public static OutputStream nullOut() {
        return new OutputStream() {
            @Override public void write(int b) {}
            @Override public void write(byte[] b, int off, int len) {}
        };
    }

    
    public static void closeQuietly(android.os.ParcelFileDescriptor p) {
        if (p == null) return;
        try { p.close(); } catch (Throwable ignored) {}
    }
}
