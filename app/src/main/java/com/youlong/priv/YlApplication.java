package com.youlong.priv;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;


public final class YlApplication {

    private final IBinder mRemote;

    public YlApplication(IBinder remote) {
        this.mRemote = remote;
    }

    /** 底层回调 Binder（服务端按 Binder 去重、判空用） */
    public IBinder asBinder() {
        return mRemote;
    }

    
    public void onServerReady() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_APPLICATION);
            mRemote.transact(YlProtocol.TX_APP_SERVER_READY, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    
    public void onPermissionResult(int requestCode, String permission, boolean allowed)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_APPLICATION);
            data.writeInt(requestCode);
            data.writeString(permission);
            data.writeInt(allowed ? 1 : 0);
            mRemote.transact(YlProtocol.TX_APP_PERM_RESULT, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ==================================================================
    
    // ==================================================================

    
    public interface Stub {
        void onServerReady();

        void onPermissionResult(int requestCode, String permission, boolean allowed);
    }

    
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, YlProtocol.DESCRIPTOR_APPLICATION);
                switch (code) {
                    case YlProtocol.TX_APP_SERVER_READY:
                        impl.onServerReady();
                        reply.writeNoException();
                        return true;
                    case YlProtocol.TX_APP_PERM_RESULT: {
                        int requestCode = data.readInt();
                        String permission = data.readString();
                        boolean allowed = data.readInt() != 0;
                        impl.onPermissionResult(requestCode, permission, allowed);
                        reply.writeNoException();
                        return true;
                    }
                    default:
                        return false;
                }
            }
        };
    }

    /**
     * 从 Bundle 还原回调（键名统一走 {@link YlService#KEY_CALLBACK}，旧实现写死了 "callback"）。
     *
     * <p>当前没有调用方：attachApplication 是把回调 Binder 直接放在事务里传的，
     * 不走 Bundle。保留此方法给 hd 层后续「把回调塞进 Bundle 附带传递」的用法。
     */
    public static YlApplication fromBundle(Bundle bundle) {
        if (bundle == null) return null;
        bundle.setClassLoader(YlApplication.class.getClassLoader());
        IBinder b = bundle.getBinder(YlService.KEY_CALLBACK);
        return b == null ? null : new YlApplication(b);
    }
}
