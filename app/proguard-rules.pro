# ============================================

# YouLong Security Shield Pro - ProGuard Rules
# ============================================






# ============================================






-keep,allowobfuscation,allowshrinking class com.youlong.hd.YouLongShield { *; }
-keep,allowobfuscation,allowshrinking class com.youlong.hd.YouLongShield$* { *; }


-keep class com.youlong.hd.MainActivity { *; }
-keep class com.youlong.hd.MainActivity$* { *; }
-keep class com.youlong.hd.ForegroundService { *; }
-keep class com.youlong.hd.ScreenFilterService { *; }
-keep class com.youlong.hd.PerformanceService { *; }
-keep class com.youlong.hd.FpsMonitorService { *; }
-keep class com.youlong.hd.ShieldWarnActivity { *; }
-keep class com.youlong.hd.BlacklistActivity { *; }
-keep class com.youlong.hd.BootReceiver { *; }
-keep class com.youlong.hd.KeepAliveReceiver { *; }

-keep class com.youlong.hd.DeviceAdminReceiver { *; }
-keep class com.youlong.hd.ProtectService { *; }
-keep class com.youlong.hd.RescueWindowService { *; }


-keep class com.youlong.hd.YouLongApp { *; }


-keep class com.youlong.hd.NativeCrypto {
    native <methods>;
}
-keepclasseswithmembernames class com.youlong.hd.NativeCrypto {
    native <methods>;
}
-keep class com.youlong.hd.NativeCrypto { *; }


-keep class com.youlong.hd.GuardNative { *; }
-keepclasseswithmembernames class com.youlong.hd.GuardNative {
    native <methods>;
}


-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keep class * {
    @android.webkit.JavascriptInterface <methods>;
}


-keep class com.youlong.hd.MainScreenKt { *; }
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ============================================================

# ------------------------------------------------------------



#

#       roro.stellar.server.StellarService

#       roro.stellar.server.bootstrap.ServerBootstrap
#       roro.stellar.server.userservice.UserServiceStarter
#       roro.stellar.server.daemon.StellarDaemon






#


#



# ============================================================
-keep class roro.stellar.** { *; }
-keep interface roro.stellar.** { *; }
-keep class com.stellar.** { *; }
-keep interface com.stellar.** { *; }

-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }

-keep class rikka.rish.** { *; }

-keep class rikka.hidden.** { *; }
-keep class dev.rikka.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

-keepclassmembers class roro.stellar.server.StellarService {
    public static void main(java.lang.String[]);
}
-keepclassmembers class roro.stellar.server.bootstrap.ServerBootstrap {
    public static void main(java.lang.String[]);
}
-keepclassmembers class roro.stellar.server.userservice.UserServiceStarter {
    public static void main(java.lang.String[]);
}
-keepclassmembers class roro.stellar.server.daemon.StellarDaemon {
    public static void main(java.lang.String[]);
}

-dontwarn roro.stellar.**
-dontwarn com.stellar.**
-dontwarn moe.shizuku.**
-dontwarn rikka.rish.**
-dontwarn rikka.hidden.**
-dontwarn dev.rikka.**
-dontwarn org.lsposed.**
-dontwarn android.content.IContentProvider
-dontwarn android.content.pm.IPackageManager
-dontwarn android.app.IActivityManager
-dontwarn android.app.IApplicationThread
-dontwarn android.content.ContextHidden
-dontwarn android.os.UserHandleHidden



#     env->FindClass("roro/stellar/manager/adb/PairingContext")
#     RegisterNatives({ "nativeConstructor", "(Z[B)J", ... })




-keep class roro.stellar.manager.adb.** { *; }
-keepclassmembers class roro.stellar.manager.adb.** {
    native <methods>;
}
-keepclasseswithmembers class roro.stellar.manager.adb.PairingContext {
    native <methods>;
}



#     FindClass("org/conscrypt/NativeCrypto") + RegisterNatives(...)


-keep class org.conscrypt.** { *; }
-keepclassmembers class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**



-dontwarn com.android.org.conscrypt.**


-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**



-dontwarn android.**


-keep class **.R$* { *; }
-keep class **.R { *; }


-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}


-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
    public boolean onShowFileChooser(...);
}


-keepattributes Signature
-keepattributes *Annotation*


-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod


-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}


-allowaccessmodification
-repackageclasses 'lI1lIlIO'
-overloadaggressively
-mergeinterfacesaggressively



# -optimizationpasses / -optimizations 对 R8 无效（R8 固定优化管道），
# 已移除（2026-10 审查）


-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}


-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}





-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute l1IlI1


-printmapping build/outputs/mapping/release/mapping.txt

# ============================================================

# ============================================================




# ============================================================



-keep,allowobfuscation class com.youlong.hd.AssetsEncryptor { *; }
-keepclassmembers,allowobfuscation class com.youlong.hd.AssetsEncryptor { *; }


-keep,allowobfuscation class com.youlong.hd.YouLongApp {
    public void *(android.content.Context);
}


-keepclassmembers class **.R$* {
    public static <fields>;
}

# -assumenosideeffects 只对方法生效，作用于字段（R$* 的 int 字段）无效，
# 已移除（2026-10 审查）


-keepclassmembers,allowobfuscation class * {
    @android.webkit.JavascriptInterface <methods>;
}






-keep,allowshrinking,allowobfuscation class com.youlong.hd.** { *; }








-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper



-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn com.google.errorprone.annotations.**

-keep class org.conscrypt.** { *; }
-keepclassmembers class org.conscrypt.** { *; }

# ==========================================================================

# --------------------------------------------------------------------------

#       com.youlong.priv.server.YlServerMain


#       NoSuchMethodError: no static method "...YlServerMain;.main([Ljava/lang/String;)V"

# ==========================================================================
-keep class com.youlong.priv.server.YlServerMain { *; }
-keepclassmembers class com.youlong.priv.server.YlServerMain {
    public static void main(java.lang.String[]);
}

-keep class com.youlong.priv.server.** { *; }

-keep class com.youlong.priv.YlProtocol { *; }
-keep class com.youlong.priv.YlService { *; }
-keep class com.youlong.priv.YlRemoteProcess { *; }
-keep class com.youlong.priv.YlApplication { *; }
-keep class com.youlong.priv.YlHandshake { *; }
-keep class com.youlong.priv.YlKernel { *; }
-keep class com.youlong.priv.YlBootProvider { *; }
-keep class com.youlong.priv.YlPrivLauncher { *; }



-keepclasseswithmembers,allowshrinking,allowobfuscation class * {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class * {
    public static void main(java.lang.String[]);
}

-keepclassmembers,allowoptimization class com.youlong.priv.server.YlServerMain {
    public static void main(java.lang.String[]);
}
