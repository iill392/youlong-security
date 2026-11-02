# ============================================
# 游龙安全护盾 v4.0 - ProGuard 超级混淆加固规则
# YouLong Security Shield Pro - ProGuard Rules
# ============================================
# 加固策略：
#   ✓ 全量混淆 + 激进重打包 + 方法重载合并
#   ✓ 字符串加密（R8 编译器支持）
#   ✓ 内联短方法 + 移除无用参数 + 泛型擦除
#   ✓ 移除日志 + 调试信息
#   ✓ 资源文件名缩减 + 无用资源移除
# ============================================

# ---- 保留签名验证 + 盗版警告（绝对不混淆、不优化、不内联）----
-keep class com.youlong.hd.SignatureVerifier { *; }
-keep class com.youlong.hd.PiracyWarningActivity { *; }
# 防止关键方法被内联导致哈希片段暴露
-keepclassmembers class com.youlong.hd.SignatureVerifier {
    private static java.lang.String rebuildExpectedHash();
    private static java.lang.String x(java.lang.String, int);
}
-keepclassmembers class com.youlong.hd.PiracyWarningActivity {
    private boolean checkSignatureIndependently();
    private static java.lang.String rebuildHashInline();
    private static java.lang.String xorStr(java.lang.String, int);
}

# ---- 保留护盾类（允许混淆类名/方法名，防止反编译者定位检测逻辑）----
-keep,allowobfuscation,allowshrinking class com.youlong.hd.YouLongShield { *; }
-keep,allowobfuscation,allowshrinking class com.youlong.hd.YouLongShield$* { *; }

# ---- 保留入口点（Activity/Service/Receiver）----
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
# 设备管理器接收器：必须保留类名，否则系统无法识别为合法 DeviceAdmin
-keep class com.youlong.hd.DeviceAdminReceiver { *; }
-keep class com.youlong.hd.DownloadService { *; }
-keep class com.youlong.hd.ProtectService { *; }
-keep class com.youlong.hd.RescueWindowService { *; }

# ---- 保留 Application 类（防止 R8 移除）----
-keep class com.youlong.hd.YouLongApp { *; }

# ---- 保留 Native 加密核心（JNI 方法名必须与 SO 符号一致）----
-keep class com.youlong.hd.NativeCrypto {
    native <methods>;
}
-keepclasseswithmembernames class com.youlong.hd.NativeCrypto {
    native <methods>;
}
-keep class com.youlong.hd.NativeCrypto { *; }

# ---- 保留哨兵守护 Native 桥接层（JNI_OnLoad 动态注册，方法名必须保持）----
-keep class com.youlong.hd.GuardNative { *; }
-keepclasseswithmembernames class com.youlong.hd.GuardNative {
    native <methods>;
}

# ---- 保留 WebView JavaScript 接口 ----
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keep class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 保留 Compose 相关 ----
-keep class com.youlong.hd.MainScreenKt { *; }
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ============================================================
# 内置特权内核（Stellar 源码级融合）—— 必须原样保留全限定名
# ------------------------------------------------------------
# 2026 年迁移：内核由上游 Shizuku 整体替换为 Stellar
# （https://github.com/roro2239/Stellar，MPL-2.0 + Apache-2.0）。
# 规则集合随之改写。
#
# 服务进程由 /system/bin/app_process 以「类名字符串」加载：
#       roro.stellar.server.StellarService
# 另外还有两个同样以类名字符串启动的 main()：
#       roro.stellar.server.bootstrap.ServerBootstrap
#       roro.stellar.server.userservice.UserServiceStarter
#       roro.stellar.server.daemon.StellarDaemon
# 服务内部又按名字引用：
#       roro.stellar.server.shizuku.ShizukuServiceIntercept （Shizuku 兼容层）
#       com.stellar.server.*                                 （AIDL 接口）
# 一旦 R8 改名、搬包（见下方 -repackageclasses 'lI1lIlIO'）或裁剪掉这些类，
# 内置服务将永远无法启动，且表现为「ClassNotFoundException」式的静默失败
# （只有 logcat 能看到），极难排查。因此这里全部锁定全限定名。
#
# ⚠️ 这几条 -keep 同时阻止了 -repackageclasses 把这些类搬进 lI1lIlIO 包，
#    这是必须的：app_process 只认原始类名。
#
# ⚠️ 内置 Stellar 管理器（roro.stellar.manager）的界面由宿主
#    MainActivity 以类名字符串 "roro.stellar.manager.MainActivity" 启动，
#    同样必须保名。
# ============================================================
-keep class roro.stellar.** { *; }
-keep interface roro.stellar.** { *; }
-keep class com.stellar.** { *; }
-keep interface com.stellar.** { *; }
# Stellar 内置的 Shizuku 兼容层 AIDL 接口（包名沿用上游 moe.shizuku.server）
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
# rish 终端客户端（rikka.rish）
-keep class rikka.rish.** { *; }
# 隐藏 API 兼容层
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

# ---- 无线调试自激活（Stellar 管理器源码级融合）----
# adb/ 子包里的 PairingContext 是 native 类：libadb.so 在 JNI_OnLoad 里用
#     env->FindClass("roro/stellar/manager/adb/PairingContext")
#     RegisterNatives({ "nativeConstructor", "(Z[B)J", ... })
# 硬编码绑定类名 + 方法名 + 方法签名。
# 上面的 "-keep class roro.stellar.** { *; }" 已经把它连同 PairingContext$Companion
# 一起原样保住了（既不改名，也不会被 -repackageclasses 搬包）。
# 这里再补一条显式声明，防止日后有人调整上面的通配规则时无声破坏 JNI 绑定。
-keep class roro.stellar.manager.adb.** { *; }
-keepclassmembers class roro.stellar.manager.adb.** {
    native <methods>;
}
-keepclasseswithmembers class roro.stellar.manager.adb.PairingContext {
    native <methods>;
}

# 内置的 conscrypt（org.conscrypt.*，用于无线调试配对时导出 TLS keying material）。
# 它的 native 库 libconscrypt_jni.so 在 JNI_OnLoad 里按**硬编码类名**做
#     FindClass("org/conscrypt/NativeCrypto") + RegisterNatives(...)
# 而本工程开了 -repackageclasses 'lI1lIlIO'，若不 keep 会被整体改名/搬包，
# 结果就是 native 注册失败、配对直接崩（只在 logcat 里可见）。
-keep class org.conscrypt.** { *; }
-keepclassmembers class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# 系统 @hide 的 com.android.org.conscrypt.Conscrypt 只作为反射兜底被引用
# （以字符串形式出现，不在 R8 的 program path 上），关掉缺失警告。
-dontwarn com.android.org.conscrypt.**

# ---- 保留 AndroidX 核心库 ----
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ---- 保留 Android 核心类 ----
-keep class android.** { *; }
-dontwarn android.**

# ---- 保留 R 资源文件 ----
-keep class **.R$* { *; }
-keep class **.R { *; }

# ---- 保留枚举类 ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 保留 Serializable/Parcelable ----
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

# ---- 保留 WebView 回调类 ----
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
    public boolean onShowFileChooser(...);
}

# ---- JSON/序列化相关 ----
-keepattributes Signature
-keepattributes *Annotation*

# ---- 保留反射调用的类 ----
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod

# ---- 保留所有 WebView 内部回调接口 ----
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 超级加固：激进混淆 ----
-allowaccessmodification
-repackageclasses 'lI1lIlIO'
-overloadaggressively
-mergeinterfacesaggressively

# ---- 超级加固 v4.0：额外混淆优化 ----
# 轮次越多，短方法内联/类合并越彻底，反编译出来的抽象层级越少
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ---- 超级加固：移除日志（生产环境）----
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ---- 移除调试信息 ----
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# ---- 保留行号信息（便于崩溃分析），但抹掉原始源文件名 ----
# 不写 -renamesourcefileattribute 的话，堆栈里会直接出现 MainActivity.java /
# ProtectService.java 这类真实文件名，逆向者按名字就能定位核心逻辑。
# 抹掉后名字变成 l1IlI1，需要配合 mapping.txt 做 retrace 才能还原。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute l1IlI1

# ---- 输出混淆映射 ----
-printmapping build/outputs/mapping/release/mapping.txt

# ============================================================
# 游龙安全护盾 v5.0 — 反逆向加固层
# ============================================================
# ✓ 类名/方法名随机化（全限定名混淆）
# ✓ 字符串常量混淆（R8 fullMode 字符串加密）
# ✓ 隐式反射调用保护
# ✓ 防 APKTool / JADX 核心 API 暴露（隐藏 key/密钥变量名）
# ============================================================

# ---- 混淆核心加密密钥名称（防止 grep 密钥）----
# 注意：AssetsEncryptor 的包名是 com.youlong.hd（类文件位于 gj 目录，但 package 声明为 hd）
-keep,allowobfuscation class com.youlong.hd.AssetsEncryptor { *; }
-keepclassmembers,allowobfuscation class com.youlong.hd.AssetsEncryptor { *; }

# ---- 防反编译：隐藏 Application 内部实现 ----
-keep,allowobfuscation class com.youlong.hd.YouLongApp {
    public void *(android.content.Context);
}

# ---- 移除 R 资源 ID 常量名称，防止布局文件对照猜测 ----
-keepclassmembers class **.R$* {
    public static <fields>;
}
-assumenosideeffects class **.R$* {
    public static final int *;
}

# ---- 防篡改：移除常见的调试/日志字符串标记 ----
-keepclassmembers,allowobfuscation class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- 加固签名检查类 — 已在文件头部用严格 -keep 保护 ----

# ---- 防范运行时反射探针（隐藏关键类结构）----
# 注意：签名验证类不允许 shrink，已在头部保护
# allowobfuscation：允许类名/方法名混淆，防止反编译者通过类名定位功能模块
-keep,allowshrinking,allowobfuscation class com.youlong.hd.** { *; }

# ---- 使用随机字母种子进一步混乱字节码 ----
# 注意：移除 -keeppackagenames（它会锁定所有包名，阻止 -repackageclasses 'yl' 生效）

# ---- BouncyCastle（Stellar 管理器生成 ADB 配对证书用）----
# bcprov-jdk18on 里的 LDAP 证书库实现了 JDK 的 javax.naming.*，
# Android 上并不存在这些类（也永远不会走到那条分支），R8 会因此报 Missing class。
# 这些 API 只被 LDAP 取 CRL 的路径引用，本应用用不到，直接忽略。
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper

# ---- Stellar 管理器用到的第三方库 ----
# OkHttp / Okio 的 JDK 专用分支在 Android 上不会走到，但会被 R8 标记为缺失。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn com.google.errorprone.annotations.**
# Conscrypt 的 native 库按硬编码类名注册，必须保名（同 Shizuku 时期）
-keep class org.conscrypt.** { *; }
-keepclassmembers class org.conscrypt.** { *; }
