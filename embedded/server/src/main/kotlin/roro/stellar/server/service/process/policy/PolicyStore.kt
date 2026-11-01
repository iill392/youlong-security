// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 策略快照的「受信写入」通道（2026-10-24，修复 ROUND25 审查 C1）：
//
// 背景：病毒库快照与 policy_mode / trusted_apps / scope_grants 都放在
// /data/local/tmp/stellar_guard/，而服务端与它 fork 出来的 sh 是同一 uid，
// 0600 挡不住「已经拿到 shell 的应用」。之前由管理器用 shell 写快照，等于把
// 策略存储暴露在同一条通道上。
//
// 现在改为：管理器通过 Binder 调用 IStellarService.writePolicyFile，服务端在
// StellarService 里用 CallerIdentity（enforceManager）确认调用方就是管理器，
// 再由本对象做 schema 校验 + 临时文件 + rename 原子替换。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import com.google.gson.JsonParser
import roro.stellar.server.util.Logger
import java.io.File

object PolicyStore {

    private val LOGGER = Logger("StellarPolicyStore")

    /** 允许管理器写入的文件名白名单（防止变成任意文件写入口）。 */
    private val ALLOWED_FILES = setOf("virus_db.json", "command_whitelist.txt")

    /** 与服务端 CommandWhitelist 约定的包名格式（每行一个包名）。 */
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private const val MAX_WHITELIST_LINES = 1000

    /** 与宿主 App 的 VirusContentDb.SNAPSHOT_SCHEMA 保持一致。 */
    private const val SNAPSHOT_SCHEMA = "youlong/virus-db/snapshot/1"

    /** 单个快照上限：防止被塞入超大文件，拖慢每次 precheck 的解析。 */
    private const val MAX_CHARS = 2 * 1024 * 1024

    /**
     * 写入病毒库快照。
     *
     * 落盘方式：先写 `virus_db.json.tmp` → chmod → rename 覆盖目标文件。
     * 同目录 rename 在 Linux 上是原子的，读取方不会看到写了一半的 JSON。
     *
     * 权限用 0644：快照内容来自公开 CDN（证书/规则，不含密钥）；0644 可以让
     * 内置特权在 root / shell 两种激活方式之间切换后仍然读得到。目录本身由
     * shell 拥有，普通应用无法在目录内创建/替换文件。
     */
    fun writeSnapshot(name: String?, content: String?): Boolean {
        if (name == null || content == null) return false

        if (name !in ALLOWED_FILES) {
            LOGGER.w("拒绝写入非白名单策略文件: %s", name)
            return false
        }
        if (content.length > MAX_CHARS) {
            LOGGER.w("拒绝写入过大的策略文件: %s (%d 字符)", name, content.length)
            return false
        }

        // schema 校验：病毒库快照必须是合法 JSON；指令白名单必须是包名列表。
        if (name == "command_whitelist.txt") {
            val lines = content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (lines.size > MAX_WHITELIST_LINES || lines.any { !PACKAGE_NAME.matches(it) }) {
                LOGGER.w("拒绝写入格式非法的指令白名单")
                return false
            }
        } else {
            val schemaOk = runCatching {
                JsonParser.parseString(content).asJsonObject.get("schema")?.asString == SNAPSHOT_SCHEMA
            }.getOrDefault(false)
            if (!schemaOk) {
                LOGGER.w("拒绝写入 schema 不匹配的策略快照")
                return false
            }
        }

        return try {
            PolicyConfig.ensureDir()
            val target = File(PolicyConfig.DIR, name)
            val tmp = File(PolicyConfig.DIR, "$name.tmp")

            tmp.writeText(content)
            PolicyConfig.chmod(tmp, "0644")

            if (!tmp.renameTo(target)) {
                // rename 失败（极少数文件系统/目标存在时）退化为覆盖写；仍在同一目录
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
                PolicyConfig.chmod(target, "0644")
            }

            LOGGER.i("策略文件已写入: %s (%d 字符)", target.absolutePath, content.length)
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "写入策略快照失败")
            false
        }
    }
}
