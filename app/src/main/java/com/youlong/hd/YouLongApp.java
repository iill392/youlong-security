package com.youlong.hd;

import android.app.Application;
import android.content.Context;
import android.os.Process;

/**
 * 游龙安全护盾 Application
 * 启动时进行签名校验，防止盗版篡改
 */
public class YouLongApp extends Application {

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

        // ====== 第一件事：装崩溃日志捕获 ======
        // 必须放在最前面，这样后面任何一步抛异常都能留下堆栈 + 运行轨迹。
        // 日志文件：/sdcard/Android/data/com.youlong.hd/files/crash/
        //            last_crash.txt（最近一次崩溃）、crash_history.txt（最近 5 次）
        // 页面里也有一键「复制日志」按钮，不需要 root / 连电脑。
        CrashLogger.install(this);
        CrashLogger.event("YouLongApp.onCreate 开始");

        // ====== 签名防篡改校验 ======
        // 密钥密码：youlong123 / 别名：youlong_release
        if (!SignatureVerifier.verify(this)) {
            // 签名不匹配 → 直接闪退，不给任何机会
            CrashLogger.event("签名校验失败，主动终止进程");
            Process.killProcess(Process.myPid());
            System.exit(0);
            return;
        }
        CrashLogger.event("签名校验通过");

        // ====== 签名绑定加密密钥：注入证书指纹 ======
        // 必须在任何资源解密之前执行！
        // 构建期用 jks 证书指纹加密 assets；此处用运行期 APK 签名指纹派生密钥
        // 重签名 / 盗版 → 指纹不同 → 密钥不同 → GCM 解密失败 → 闪退
        try {
            byte[] fp = SignatureVerifier.getCertSha256Fingerprint(this);
            if (fp != null) {
                AssetsEncryptor.setSignatureFingerprint(fp);
            }
            CrashLogger.event("证书指纹已注入（"
                    + (fp == null ? "空" : fp.length + " 字节") + "）");
        } catch (Throwable tr) {
            CrashLogger.event("注入证书指纹失败", tr);
        }

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

        // ====== 2026-11-02：旧版本自动加入系统白名单的启动迁移清理 ======
        // 主进程启动时清理旧版 whitelist_pkgs 里的系统应用，并同步覆盖服务端
        // command_whitelist.txt；用户自己加入的普通应用保留。
        try {
            if (isMainProcess()) {
                WhitelistMigration.cleanupAsync(this);
            }
        } catch (Throwable tr) {
            CrashLogger.event("启动旧版系统白名单清理失败", tr);
        }

        // ====== 2026-10-24 新增：病毒特征库（virus-db）定时拉取 ======
        // 用户需求：只要应用在运行，每 3 分钟拉取一次新增的 virus-db.json /
        // virus-db-rules.json（ETag 未变时服务器只回 304，流量很小）。
        // 只在主进程启动：:guard 是独立进程，Application 会在每个进程各初始化
        // 一次，重复启动会让请求翻倍。
        try {
            if (isMainProcess()) {
                VirusContentDb.startPeriodicRefresh(this);
                CrashLogger.event("病毒特征库定时拉取已启动（每 3 分钟）");
            }
        } catch (Throwable tr) {
            CrashLogger.event("启动病毒特征库定时拉取失败", tr);
        }

        // ====== 2026-10-24 新增：把病毒库状态/手动同步能力注入内置特权 UI ======
        // manager 模块「设置 → 病毒库同步」卡片通过 VirusDbAccess 读取版本/上次
        // 同步时间并触发「立即同步」；manager 不能反向依赖 app，所以由宿主注入。
        try {
            roro.stellar.manager.virusdb.VirusDbAccess.setProvider(
                    new roro.stellar.manager.virusdb.VirusDbAccess.StatusProvider() {
                        @Override
                        public roro.stellar.manager.virusdb.VirusDbSyncStatus status() {
                            return VirusContentDb.syncStatus(YouLongApp.this);
                        }
                    },
                    new roro.stellar.manager.virusdb.VirusDbAccess.SyncTrigger() {
                        @Override
                        public boolean sync(boolean force) {
                            return VirusContentDb.requestSync(YouLongApp.this, force);
                        }
                    }
            );
            CrashLogger.event("病毒库同步桥已注册（内置特权页面可用）");
        } catch (Throwable tr) {
            CrashLogger.event("注册病毒库同步桥失败", tr);
        }

        // 2026-10-31（用户需求）：自动拦截固定开启，任何旧版本留下的 false 都强制纠正。
        try {
            getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putBoolean("auto_block_on", true).apply();
        } catch (Throwable ignored) {
        }

        // 2026-10-30：应用更新后自动检查/重启旧版内置特权服务端，无需用户手动重启。
        try {
            ServerRestartManager.checkAsync(this);
        } catch (Throwable tr) {
            CrashLogger.event("启动服务端版本检查失败", tr);
        }

        try {
            RuntimeEventLog.log(this, "APP",
                    "Application 启动，进程=" + (isMainProcess() ? "main" : "other"));
        } catch (Throwable ignored) {
        }

        CrashLogger.event("YouLongApp.onCreate 结束");
    }

    /**
     * 当前是否主进程（com.youlong.hd）。
     *
     * <p>Android 的 Application 会在每个进程各实例化一次：本应用除主进程外还有
     * :guard 哨兵进程，定时任务只允许在主进程启动，避免重复网络请求。
     * 读 /proc/self/cmdline 拿当前进程名；读失败时保守返回 true（宁多一次不漏）。
     */
    private boolean isMainProcess() {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return true;
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            String name = new String(buf, 0, end, "UTF-8").trim();
            return getPackageName().equals(name);
        } catch (Throwable tr) {
            return true;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
