package com.youlong.priv;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * 远程进程代理 / 桩（服务端进程控制的客户端侧协议封装）。
 *
 * <p>进程的 stdin/stdout/stderr 在 {@link YlService.Proxy#newProcess} 建立进程时
 * 就以管道 fd 的形式交付，所以这里没有 getOutputStream/getInputStream/getErrorStream
 * —— 原 TX_PROC_OUT / TX_PROC_IN / TX_PROC_ERR 三个事务服务端从来没实现过（恒返回 null），
 * 属于死协议，已删除。
 */
public final class YlRemoteProcess {

    private final IBinder mRemote;

    public YlRemoteProcess(IBinder remote) {
        this.mRemote = remote;
    }

    /** 底层 Binder */
    public IBinder asBinder() {
        return mRemote;
    }

    /**
     * 等待进程结束。
     *
     * @return 退出码；服务端同 uid 等待名额已满时返回 {@link YlProtocol#PROC_WAIT_BUSY}
     *         （调用方应改用 {@link #alive()} 轮询，见 {@link YlService.YlProcess#waitFor()}）
     */
    public int waitFor() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_WAIT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * 取退出码。
     *
     * @throws IllegalThreadStateException 进程尚未结束（服务端如实抛出，不再返回假 -1）
     */
    public int exitValue() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_EXIT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 进程是否还在运行 */
    public boolean alive() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_ALIVE, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 主动结束进程 */
    public void destroy() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_DESTROY, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ==================================================================
    // 服务端桩
    // ==================================================================

    /** 服务端实现接口（由 YlServerMain 的进程管理实现类实现） */
    public interface Stub {
        /**
         * 阻塞等待进程结束。
         *
         * <p>注意：实现方必须限制同一 uid 的并发等待数，否则会把 Binder 线程池占满
         * （见 YlServerMain 里的 MAX_WAIT_PER_UID）；名额满时返回
         * {@link YlProtocol#PROC_WAIT_BUSY}。
         */
        int waitFor();

        /** 取退出码，未结束时抛 IllegalThreadStateException */
        int exitValue();

        /** 是否仍在运行（检测到已退出时应保存退出码并清理进程表） */
        boolean alive();

        /** 主动结束并清理 */
        void destroy();
    }

    /** 服务端桩的 Binder 封装 */
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
                switch (code) {
                    case YlProtocol.TX_PROC_WAIT:
                        reply.writeNoException();
                        reply.writeInt(impl.waitFor());
                        return true;
                    case YlProtocol.TX_PROC_EXIT:
                        reply.writeNoException();
                        reply.writeInt(impl.exitValue());
                        return true;
                    case YlProtocol.TX_PROC_ALIVE:
                        reply.writeNoException();
                        reply.writeInt(impl.alive() ? 1 : 0);
                        return true;
                    case YlProtocol.TX_PROC_DESTROY:
                        impl.destroy();
                        reply.writeNoException();
                        return true;
                    default:
                        return false;
                }
            }
        };
    }
}
