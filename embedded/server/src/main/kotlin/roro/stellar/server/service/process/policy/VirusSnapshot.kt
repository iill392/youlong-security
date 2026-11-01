// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 「病毒特征库」服务端快照（对应 yl-tool-data/virus-db-README.md §4.3）。
//
// 宿主 App 拉取并解析 `virus-db.json` / `virus-db-rules.json` 后，会把一份
// 精简快照写到：
//     /data/local/tmp/stellar_guard/virus_db.json
// 服务端按文件 mtime 缓存加载，用于两件事：
//   1) 会话发起者证书命中（强信号，action=block → 直接拒绝）；
//   2) stdin 脚本命中行为规则（token/regex + 权重；block 规则或累计 ≥60 → 拒绝）。
//
// 快照缺失/损坏时静默降级（仍有本工程内置的样本证书黑名单与通用规则）。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import roro.stellar.server.util.Logger
import java.io.File
import java.util.regex.Pattern

// ---------------------------------------------------------------------------
// 快照 JSON 模型（字段名与宿主 App 的 snapshotJson 一一对应）
// ---------------------------------------------------------------------------

data class VirusDecisionPolicy(
    @SerializedName("high_token_block_min") val highTokenBlockMin: Int? = null,
    @SerializedName("weak_only_action") val weakOnlyAction: String? = null,
    @SerializedName("fuzzy_action") val fuzzyAction: String? = null
)

data class VirusCert(
    @SerializedName("sha256") val sha256: String? = null,
    val action: String? = null,
    val family: String? = null,
    @SerializedName("display_name") val displayName: String? = null
)

data class VirusPackage(
    @SerializedName("package_name") val packageName: String? = null,
    val action: String? = null,
    val family: String? = null
)

/**
 * 2026-10-25：黑名单「应用名称」（mingcheng1/2 等名称库）。
 * 服务端用 PackageManager 取调用方 label 后按名称匹配。
 */
data class VirusName(
    val name: String? = null,
    val action: String? = null,
    val family: String? = null
)

data class VirusRule(
    val id: String? = null,
    val family: String? = null,
    val kind: String? = null,
    val pattern: String? = null,
    val weight: Int? = null,
    val action: String? = null,
    val description: String? = null
)

data class VirusSnapshotFile(
    val schema: String? = null,
    val version: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("decision_policy") val decisionPolicy: VirusDecisionPolicy? = null,
    @SerializedName("allowlist_certs") val allowlistCerts: List<String>? = null,
    val certs: List<VirusCert>? = null,
    val packages: List<VirusPackage>? = null,
    val names: List<VirusName>? = null,
    val rules: List<VirusRule>? = null
)

object VirusSnapshot {

    private val LOGGER = Logger("StellarVirusDb")

    /** 与宿主 App 约定的快照路径（管理器经 Binder 写入，服务端原子 rename，0644）。 */
    private val FILE = File("/data/local/tmp/stellar_guard/virus_db.json")

    /** 与宿主 App 的 VirusContentDb.SNAPSHOT_SCHEMA 一致。 */
    private const val SNAPSHOT_SCHEMA = "youlong/virus-db/snapshot/1"

    /** 快照文件上限：超过就当作不可用，避免每次 precheck 解析超大文件。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 正则规则长度上限（防止远程库塞入超长/灾难性表达式）。 */
    private const val MAX_PATTERN_CHARS = 4096

    /** 单次匹配的脚本上限（网关已限制在 64KB，这里再兜一层）。 */
    private const val MAX_MATCH_CHARS = 256 * 1024

    /** 规则 action 白名单。 */
    private val ALLOWED_ACTIONS = setOf("block", "warn", "ask")

    private val gson = Gson()

    private val lock = Any()

    private val EMPTY = Snapshot("", emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyList())

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var loadedMtime = -1L

    /** 上次告警时的 mtime，避免解析失败时每个会话刷一条日志。 */
    @Volatile
    private var warnedMtime = Long.MIN_VALUE

    /** 编译后的规则。 */
    class CompiledRule(
        val id: String,
        val family: String,
        val action: String,
        val weight: Int,
        val token: String?,
        val regex: Pattern?
    )

    /** 解析后的快照索引。 */
    class Snapshot(
        val version: String,
        val allowlist: Set<String>,
        val certs: Map<String, VirusCert>,
        val packages: Map<String, VirusPackage>,
        val names: Map<String, VirusName>,
        val rules: List<CompiledRule>
    ) {
        val isEmpty: Boolean
            get() = certs.isEmpty() && packages.isEmpty() && names.isEmpty() && rules.isEmpty()
    }

    /** 脚本命中结果。 */
    data class RuleResult(
        val block: Boolean,
        val score: Int,
        val reasons: List<String>
    ) {
        val hit: Boolean
            get() = block || score > 0
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /** 当前快照（按 mtime 缓存；文件不存在/解析失败返回上一份有效快照）。 */
    fun current(): Snapshot {
        synchronized(lock) {
            val exists = FILE.isFile
            val mtime = if (exists) FILE.lastModified() else 0L
            val old = cached
            if (old != null && mtime == loadedMtime) return old

            val tooLarge = exists && FILE.length() > MAX_FILE_BYTES
            val parsed = if (exists && !tooLarge) parse() else null

            if (parsed == null) {
                // 2026-10-24：解析失败/文件被删/被写坏时保留上一份有效快照，
                // 并且不更新 loadedMtime（下次调用会重试）。
                // 之前这里会用空快照覆盖 cached 并记住 mtime，导致一次坏读就把
                // 证书/规则静默关掉，且同版本不再重推时长期不恢复。
                if (mtime != warnedMtime) {
                    warnedMtime = mtime
                    LOGGER.w(
                        "病毒库快照不可用（exists=%s, tooLarge=%s），%s",
                        exists,
                        tooLarge,
                        if (old != null) "继续使用上一份有效快照" else "使用内置默认名单"
                    )
                }
                return old ?: EMPTY
            }

            cached = parsed
            loadedMtime = mtime
            LOGGER.i(
                "病毒库快照已加载：version=%s, certs=%d, rules=%d",
                parsed.version, parsed.certs.size, parsed.rules.size
            )
            return parsed
        }
    }

    private fun parse(): Snapshot? {
        return try {
            val text = FILE.readText()
            val file = gson.fromJson(text, VirusSnapshotFile::class.java) ?: return null
            if (file.schema != SNAPSHOT_SCHEMA) {
                LOGGER.w("快照 schema 不匹配（%s），拒绝加载", file.schema)
                return null
            }
            val allowlist = (file.allowlistCerts ?: emptyList())
                .mapNotNull { it?.trim()?.lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

            val certs = LinkedHashMap<String, VirusCert>()
            file.certs.orEmpty().forEach { cert ->
                if (cert == null) return@forEach
                val sha = cert.sha256?.trim()?.lowercase()
                if (!sha.isNullOrEmpty()) certs[sha] = cert
            }

            val packages = LinkedHashMap<String, VirusPackage>()
            file.packages.orEmpty().forEach { pkg ->
                if (pkg == null) return@forEach
                val name = pkg.packageName?.trim()
                if (!name.isNullOrEmpty()) packages[name] = pkg
            }

            // 2026-10-25：黑名单应用名称（mingcheng1/2），按小写建索引。
            val names = LinkedHashMap<String, VirusName>()
            file.names.orEmpty().forEach { entry ->
                if (entry == null) return@forEach
                val name = entry.name?.trim()?.lowercase()
                if (!name.isNullOrEmpty()) names[name] = entry
            }

            val rules = ArrayList<CompiledRule>()
            file.rules.orEmpty().forEach { rule ->
                if (rule == null) return@forEach
                val id = rule.id?.trim().orEmpty()
                val pattern = rule.pattern?.trim().orEmpty()
                if (id.isEmpty() || pattern.isEmpty() || pattern.length > MAX_PATTERN_CHARS) {
                    LOGGER.w("跳过非法规则 %s（空或过长）", id)
                    return@forEach
                }
                val action = rule.action?.trim()?.lowercase().orEmpty().ifEmpty { "warn" }
                if (action !in ALLOWED_ACTIONS) {
                    LOGGER.w("跳过未知 action 的规则 %s: %s", id, action)
                    return@forEach
                }
                // 负权重可被用来抵消其它规则分数，这里钳到 0..200
                val weight = (rule.weight ?: 0).coerceIn(0, 200)
                val kind = rule.kind?.trim()?.lowercase().orEmpty().ifEmpty { "token" }
                val family = rule.family?.trim().orEmpty()
                when (kind) {
                    "regex" -> {
                        // 单条规则编译失败（含 StackOverflowError/OOM）只跳过它，
                        // 不能让整份快照（含证书）一起失效。
                        val compiled = try {
                            Pattern.compile(pattern, Pattern.MULTILINE)
                        } catch (t: Throwable) {
                            LOGGER.w(t, "跳过非法正则规则 %s", id)
                            null
                        }
                        if (compiled != null) {
                            rules.add(CompiledRule(id, family, action, weight, null, compiled))
                        }
                    }

                    "token" -> rules.add(CompiledRule(id, family, action, weight, pattern, null))

                    else -> LOGGER.w("跳过未知规则类型 %s: %s", id, kind)
                }
            }

            Snapshot(
                version = file.version.orEmpty(),
                allowlist = allowlist,
                certs = certs,
                packages = packages,
                names = names,
                rules = rules
            )
        } catch (t: Throwable) {
            LOGGER.w(t, "解析病毒库快照失败（将使用内置默认名单）")
            null
        }
    }

    // ------------------------------------------------------------------
    // 匹配
    // ------------------------------------------------------------------

    /**
     * 调用方证书命中（强信号）。
     *
     * @return 命中且不在 allowlist 时返回对应记录；否则 null
     */
    fun matchCert(certSha256: String?): VirusCert? {
        val sha = certSha256?.trim()?.lowercase() ?: return null
        val snapshot = current()
        if (snapshot.isEmpty) return null
        if (snapshot.allowlist.contains(sha)) return null
        return snapshot.certs[sha]
    }

    /** 包名命中（黑名单应用）。 */
    fun matchPackage(packageName: String?): VirusPackage? {
        if (packageName.isNullOrEmpty()) return null
        val snapshot = current()
        if (snapshot.isEmpty) return null
        return snapshot.packages[packageName]
    }

    /**
     * 2026-10-25：应用名称命中（mingcheng1/2 名称库）。
     * 比较时忽略大小写与首尾空白。
     */
    fun matchName(appLabel: String?): VirusName? {
        val label = appLabel?.trim()?.lowercase() ?: return null
        if (label.isEmpty()) return null
        val snapshot = current()
        if (snapshot.isEmpty) return null
        return snapshot.names[label]
    }

    /**
     * stdin 脚本命中行为规则。
     *
     * 与 README §4.3 一致：命中 action=block 的规则 → 直接拒绝；
     * 否则按 weight 累计，累计 ≥60 也拒绝（返回 block=true）。
     */
    fun matchScript(script: String?): RuleResult {
        if (script.isNullOrBlank()) return RuleResult(false, 0, emptyList())
        val snapshot = current()
        if (snapshot.rules.isEmpty()) return RuleResult(false, 0, emptyList())

        var score = 0
        var block = false
        val reasons = ArrayList<String>()
        val text = if (script.length > MAX_MATCH_CHARS) script.take(MAX_MATCH_CHARS) else script

        for (rule in snapshot.rules) {
            val hit = try {
                if (rule.regex != null) {
                    rule.regex.matcher(text).find()
                } else {
                    val token = rule.token
                    !token.isNullOrEmpty() && text.contains(token)
                }
            } catch (t: Throwable) {
                // 单条规则匹配异常（含 Error）只跳过这一条，不能让整个会话失败
                LOGGER.w(t, "规则 %s 匹配异常，已跳过", rule.id)
                false
            }
            if (!hit) continue
            score += rule.weight
            if (rule.action == "block") block = true
            reasons.add("${rule.id}(${rule.family})")
        }

        if (score >= 60) block = true
        return RuleResult(block, score, reasons)
    }
}
