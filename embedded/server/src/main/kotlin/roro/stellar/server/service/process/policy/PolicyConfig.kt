// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 内置特权「脚本级防御」的配置与能力定义（2026-10-24，按《游龙 Shizuku
// 防御方案》落地）。
//
// 背景：恶意应用可以只执行 `sh`，真正的攻击脚本写进它的 stdin。原实现把
// stdin 直接透传给真实进程，服务端只看得到 `sh`，看不到脚本，也就无法拦截。
// 本包负责：stdin 代理网关 + 审计 + 确定性拦截（包名/名称黑名单、目标保护）+
// 能力作用域审批 + 行为链评分。
//
// 本文件只做「配置与本地文件」：
//   /data/local/tmp/stellar_guard/policy_mode.txt      audit | enforce（默认 enforce）
//   /data/local/tmp/stellar_guard/trusted_apps.txt     每行一个受信包名（追加进内置名单）
//   /data/local/tmp/stellar_guard/protected_apps.txt   每行一个受保护包名（追加进内置名单）
//   /data/local/tmp/stellar_guard/scope_grants.txt     每行「包名<TAB>能力id」（用户授权）
//   /data/local/tmp/stellar_guard/shell_audit.jsonl    会话审计（JSONL）
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import roro.stellar.server.util.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 「能力作用域」——授权的粒度是能力，不是某一条命令。
 * 这样恶意软件换写法、混淆命令都不影响判定：它要动包管理，就需要 PKG_CONTROL。
 */
enum class Capability(val id: String, val label: String) {
    /** 只读/无副作用命令：默认允许。 */
    SHELL_BASIC("shell_basic", "基础命令"),

    /** pm disable/enable/suspend/uninstall/clear、cmd package 等。 */
    PKG_CONTROL("pkg_control", "包管理"),

    /** settings put/delete system|secure|global（含无障碍列表）。 */
    SETTINGS_SECURE("settings_secure", "系统设置"),

    /** am force-stop、kill、pkill、killall。 */
    PROC_CONTROL("proc_control", "进程控制"),

    /** 往 /data/local/tmp 落可执行文件、nohup、看门狗、base64 -d > 文件。 */
    PERSISTENCE("persistence", "持久化"),

    /** 交互式 shell（PTY）。未知应用默认不授予。 */
    INTERACTIVE("interactive", "交互式终端");

    companion object {
        fun fromId(id: String?): Capability? = entries.firstOrNull { it.id == id }
    }
}

object PolicyConfig {

    private val LOGGER = Logger("StellarPolicy")

    /** 与服务端其它握手文件共用的目录。 */
    val DIR: File = File("/data/local/tmp/stellar_guard")

    private val MODE_FILE = File(DIR, "policy_mode.txt")
    private val TRUSTED_FILE = File(DIR, "trusted_apps.txt")
    private val PROTECTED_FILE = File(DIR, "protected_apps.txt")

    /** 能力授权文件：每行「包名<TAB>能力id」。管理器可查看/撤销。 */
    val GRANTS_FILE: File = File(DIR, "scope_grants.txt")

    /** 会话审计。 */
    val AUDIT_FILE: File = File(DIR, "shell_audit.jsonl")

    /** 本应用（管理器）。 */
    const val MANAGER_PACKAGE = "com.youlong.hd"

    /** 配套应用：默认受信，但仍受「目标保护」约束。 */
    const val TOOL_PACKAGE = "com.youlong.tool"

    /** 审计里单条会话最多保留的 stdin 字节数（防止日志爆炸）。 */
    const val MAX_AUDIT_STDIN = 16 * 1024

    /** 审计文件超过该大小后轮转一次（只保留一份 .1）。 */
    const val MAX_AUDIT_FILE = 2 * 1024 * 1024

    /** 系统关键组件：被冻结/卸载会导致系统不可用。 */
    private val CRITICAL_SYSTEM_PACKAGES = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.providers.settings",
        "com.android.shell",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.mms",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.documentsui",
        "com.android.vending",
        "com.android.launcher3",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.wifi",
        "com.android.se",
        "com.android.networkstack",
        "com.android.inputmethod.latin",
        "com.android.keychain"
    )

    /** 厂商系统包前缀：兜底识别非关键的系统应用。 */
    private val SYSTEM_PREFIXES = listOf(
        "android", "com.android.", "com.google.android.", "com.qualcomm.",
        "com.mediatek.", "com.miui.", "com.xiaomi.", "com.huawei.", "com.honor.",
        "com.oppo.", "com.coloros.", "com.vivo.", "com.bbk.", "com.oneplus.",
        "com.oplus.", "com.realme.", "com.meizu.", "com.smartisan.", "com.sony.",
        "com.samsung.", "com.sec.", "com.lg.", "com.htc.", "com.motorola.",
        "com.lenovo.", "com.asus.", "com.nokia.", "com.zte."
    )

    /** 默认受保护包：本产品 + 配套应用 + 系统关键组件。 */
    private val DEFAULT_PROTECTED: Set<String> =
        setOf(MANAGER_PACKAGE, TOOL_PACKAGE) + CRITICAL_SYSTEM_PACKAGES

    /** 默认受信应用：配套应用。 */
    private val DEFAULT_TRUSTED: Set<String> = setOf(TOOL_PACKAGE)

    private data class CacheEntry(val mtime: Long, val values: Set<String>)

    private val setCache = ConcurrentHashMap<String, CacheEntry>()

    // ------------------------------------------------------------------
    // 模式
    // ------------------------------------------------------------------

    /**
     * 是否「只审计、不拦截」（方案第 13 节的一键降级开关）。
     *
     * 默认 enforce；把 `policy_mode.txt` 写成 `audit` 后，所有本应 BLOCK 的
     * 判定只记录不执行，用于灰度/排障。
     */
    fun isAuditOnly(): Boolean {
        val file = MODE_FILE
        if (!file.isFile) return false
        return runCatching { file.readText().trim().equals("audit", true) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    fun isTrusted(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return readSet(TRUSTED_FILE, DEFAULT_TRUSTED).contains(packageName)
    }

    fun isProtected(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return readSet(PROTECTED_FILE, DEFAULT_PROTECTED).contains(packageName)
    }

    fun isSystemCritical(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (CRITICAL_SYSTEM_PACKAGES.contains(packageName)) return true
        return SYSTEM_PREFIXES.any { packageName.startsWith(it) }
    }

    /** 当前生效的受保护包集合（内置 + 文件追加），供脚本原文兜底扫描。 */
    fun protectedPackages(): Set<String> = readSet(PROTECTED_FILE, DEFAULT_PROTECTED)

    // ------------------------------------------------------------------
    // 能力授权
    // ------------------------------------------------------------------

    /** 该应用是否已获得某项能力的「始终允许」。 */
    fun hasGrant(packageName: String?, capability: Capability): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return grants()[packageName]?.contains(capability.id) == true
    }

    /** 用户点「始终允许该能力」后落盘。 */
    fun grant(packageName: String?, capability: Capability) {
        if (packageName.isNullOrEmpty()) return
        synchronized(GRANTS_LOCK) {
            val map = readGrants()
            val set = map.getOrPut(packageName) { LinkedHashSet() }
            if (!set.add(capability.id)) return
            writeGrants(map)
            LOGGER.i("已授权能力：%s → %s", packageName, capability.id)
        }
    }

    /** 撤销某应用的一项能力（管理器侧也可以直接改文件）。 */
    fun revoke(packageName: String, capabilityId: String) {
        synchronized(GRANTS_LOCK) {
            val map = readGrants()
            val set = map[packageName] ?: return
            if (!set.remove(capabilityId)) return
            if (set.isEmpty()) map.remove(packageName)
            writeGrants(map)
        }
    }

    private val GRANTS_LOCK = Any()

    @Volatile
    private var grantsCache: Pair<Long, Map<String, Set<String>>>? = null

    private fun grants(): Map<String, Set<String>> {
        val mtime = if (GRANTS_FILE.isFile) GRANTS_FILE.lastModified() else 0L
        val cached = grantsCache
        if (cached != null && cached.first == mtime) return cached.second
        val map = readGrants()
        grantsCache = mtime to map
        return map
    }

    private fun readGrants(): MutableMap<String, MutableSet<String>> {
        val map = LinkedHashMap<String, MutableSet<String>>()
        if (!GRANTS_FILE.isFile) return map
        runCatching {
            GRANTS_FILE.readLines().forEach { line ->
                val idx = line.indexOf('\t')
                if (idx > 0) {
                    val pkg = line.substring(0, idx).trim()
                    val cap = line.substring(idx + 1).trim()
                    if (pkg.isNotEmpty() && cap.isNotEmpty()) {
                        map.getOrPut(pkg) { LinkedHashSet() }.add(cap)
                    }
                }
            }
        }.onFailure { LOGGER.w(it, "读取能力授权文件失败") }
        return map
    }

    private fun writeGrants(map: Map<String, Set<String>>) {
        runCatching {
            ensureDir()
            val body = buildString {
                map.forEach { (pkg, caps) ->
                    caps.forEach { cap -> append(pkg).append('\t').append(cap).append('\n') }
                }
            }
            GRANTS_FILE.writeText(body)
            chmod(GRANTS_FILE, "0600")
            grantsCache = GRANTS_FILE.lastModified() to map
        }.onFailure { LOGGER.w(it, "写入能力授权文件失败") }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 读取「一行一个」的名单文件，并与内置默认值合并。
     *
     * 合并而不是替换：默认黑名单/受保护名单是安全底线，不允许被文件清空；
     * 文件只能追加。
     */
    private fun readSet(file: File, defaults: Set<String>): Set<String> {
        val mtime = if (file.isFile) file.lastModified() else 0L
        val cached = setCache[file.absolutePath]
        if (cached != null && cached.mtime == mtime) return cached.values
        val values = if (mtime <= 0L) {
            defaults
        } else {
            val fromFile = runCatching {
                file.readLines()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }.getOrDefault(emptySet())
            defaults + fromFile
        }
        setCache[file.absolutePath] = CacheEntry(mtime, values)
        return values
    }

    fun ensureDir() {
        if (!DIR.exists() && !DIR.mkdirs()) {
            LOGGER.w("无法创建握手目录 %s", DIR.absolutePath)
        }
    }

    fun chmod(file: File, mode: String) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", mode, file.absolutePath)).waitFor()
        }.onFailure { LOGGER.w(it, "chmod %s 失败", file.absolutePath) }
    }
}
