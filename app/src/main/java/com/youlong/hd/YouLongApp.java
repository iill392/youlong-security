package com.youlong.hd;

import android.app.Application;
import android.content.Context;

/**
 * 游龙安全护盾 Application
 * 启动时进行签名校验，防止盗版篡改
 */
public class YouLongApp extends Application {

    /**
     * 进程内 Application 单例。
     *
     * <p>自研特权内核需要拿到 Application 来定位 APK 路径（app_process 的
     * CLASSPATH 必须指向本应用 APK），而启动服务端时未必处在能拿到 Context 的位置，
     * 所以这里保留一个静态引用；进程退出即失效，不跨进程、不写盘。
     */
    private static volatile YouLongApp sInstance;

    /** 取当前进程的 Application（可能为 null：app_process 场景下尚未初始化）。 */
    public static YouLongApp instance() {
        return sInstance;
    }

    /**
     * ======================================================================
     * 源码融合改造（游龙安全护盾）：内置 Stellar 特权内核的初始化
     * ----------------------------------------------------------------------
     * 2026 年迁移：本应用原先源码级内置上游 Shizuku，现已整体替换为
     * Stellar（https://github.com/roro2239/Stellar，MPL-2.0 + Apache-2.0）。
     *
     * ⚠️ 与 Shizuku 时期的两点差异：
     *   1. 不再需要 attachBaseContext() 里的
     *      rikka.shizuku.ShizukuProvider.disableAutomaticSuiInitialization()。
     *      Stellar 的 Shizuku 兼容层不带 Sui 自动初始化（上游 README 写明
     *      已移除 Sui 支持），并在服务端主动拒绝来自 Shizuku Manager 的请求，
     *      因此外部 Sui 抢占 binder 通道的问题不复存在，该绕行已删除。
     *
     *   2. Stellar 的 manager 上游是独立 APK，靠清单里的
     *      android:name=".StellarApplication" 由系统自动实例化。
     *      本工程把它内置为库模块，清单里的 Application 仍是
     *      com.youlong.hd.YouLongApp（清单合并以主模块为准），
     *      StellarApplication **不会**被系统实例化 ——
     *      因此它原本在 onCreate() 里做的初始化必须由这里显式补上。
     *
     * 初始化必须放在签名校验之后：Stellar 的初始化会加载原生库、初始化偏好与
     * Room 数据库，签名不匹配的盗版包不应该走到这一步。
     * ======================================================================
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 迁移说明见上方注释第 1 点：Shizuku 时代的
        // ShizukuProvider.disableAutomaticSuiInitialization() 已随 Shizuku 移除。
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        // ====== 特权内核：沿用内置开源内核（Stellar）======
        // 2026-10 结论（真机实测）：完全自研的 "app_process from app 进程" 路线
        // 在 Android 14 非 root 设备上拿不到 shell 身份（子进程只能继承应用 uid，
        // 且卡死在启动早期、不会执行到我们的 main()）。
        // 因此内核沿用 Stellar（MPL-2.0 / Apache-2.0，开源可商用）；
        // 用户可见的界面与授权流程改为本应用自研（见 PrivilegeActivity）。
        // 自研内核代码 com.youlong.priv 保留在工程中作为技术储备，不再在启动路径启用。
        // 自研内核自检已移除（内核启动调用同时停用，见下方说明）。

        // ====== 第一件事：装崩溃日志捕获 ======
        // 必须放在最前面，这样后面任何一步抛异常都能留下堆栈 + 运行轨迹。
        // 日志文件：/sdcard/Android/data/com.youlong.hd/files/crash/
        //            last_crash.txt（最近一次崩溃）、crash_history.txt（最近 5 次）
        // 页面里也有一键「复制日志」按钮，不需要 root / 连电脑。
        CrashLogger.install(this);
        CrashLogger.event("YouLongApp.onCreate 开始");

        // ==================================================================
        // 【开源版已移除】签名校验 + 签名绑定密钥
        // ------------------------------------------------------------------
        // 正式发布版在这里做了两件事，本开源包里**全部删除**：
        //   ① SignatureVerifier.verify(this)：把 APK 签名证书的 SHA-256 与
        //      内置的官方指纹比对，不一致直接杀进程（防盗版）；
        //   ② AssetsEncryptor.setSignatureFingerprint(...)：把证书指纹注入
        //      资源解密器 —— 内置前端资源是用 KDF(种子 + 盐 + 证书指纹) 加密的，
        //      换签名就无法解密，等于把资源绑死在官方签名上。
        //
        // 开源包不携带任何签名材料、也不携带资源加密密钥，因此：
        //   · 前端资源（assets/index.html）以**明文**随包分发；
        //   · 任何人对编译出的 APK 重新签名都能正常运行，方便二次开发。
        // 需要防盗版的发布版请自行实现签名校验，并自行保管 keystore。
        // ==================================================================
        CrashLogger.event("开源版：跳过签名校验（无内置签名指纹）");

        // ====== 内置 Stellar 管理器初始化 ======
        // 对应上游 StellarApplication.onCreate() 里做的三件事：
        //   application = this       → attachApplication(this)
        //   init(this)               → StellarSettings 偏好 + 夜间模式
        //   BootStartNotifications.createChannel(this)
        // 另外伴生对象的初始化块（libsu Shell 默认构造器、HiddenApiBypass 豁免、
        // 加载 libadb.so）会在本类首次被类加载时执行 ——
        // 第一次触达 StellarApplication.attachApplication 即可触发。
        //
        // 整块包 try/catch：初始化失败不应该拖垮宿主主流程（WebView 首页）。
        try {
            CrashLogger.event("StellarApplication 初始化开始");
            roro.stellar.manager.StellarApplication.Companion.attachApplication(this);
            roro.stellar.manager.StellarApplication.Companion.init(this);
            roro.stellar.manager.startup.notification.BootStartNotifications.INSTANCE.createChannel(this);
            CrashLogger.event("StellarApplication 初始化完成");
        } catch (Throwable tr) {
            android.util.Log.w("YouLongApp", "StellarApplication init failed", tr);
            CrashLogger.event("StellarApplication 初始化失败", tr);
        }

        CrashLogger.event("YouLongApp.onCreate 结束");
    }
}
