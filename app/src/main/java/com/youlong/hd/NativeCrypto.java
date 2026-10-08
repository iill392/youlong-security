package com.youlong.hd;


public final class NativeCrypto {

    private static volatile boolean sNativeLoaded = false;

    static {
        try {
            System.loadLibrary("nativecrypto");
            sNativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            
            
            sNativeLoaded = false;
        }
    }

    private NativeCrypto() {}

    /** native 库是否成功加载（静态标志，供 Java fallback 判断） */
    public static boolean isNativeLoaded() {
        return sNativeLoaded;
    }

    
    public static native byte[] deriveKey(byte[] fingerprint);

    
    public static native byte[] decrypt(byte[] iv, byte[] ciphertext, byte[] fingerprint);

    
    public static native boolean isTracerAttached();

    
    public static native boolean detectFrida();
}
