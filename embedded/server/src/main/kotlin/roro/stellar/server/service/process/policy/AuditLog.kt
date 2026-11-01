// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 会话审计（L1）：一次 shell 会话结束时写一条 JSONL，包含调用方、argv、
// 完整 stdin（限长）、判定结果、行为链评分、退出码与耗时。
//
// 文件：/data/local/tmp/stellar_guard/shell_audit.jsonl（0600，超过 2MB 轮转）
// 只记录、不改变执行行为；即使策略判定放行也照样记录，便于回溯与调优。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import roro.stellar.server.util.Logger
import java.io.File

object AuditLog {

    private val LOGGER = Logger("StellarAudit")

    private val lock = Any()

    /** 一条会话审计记录。 */
    fun record(session: ShellSession, exitCode: Int?, decision: String) {
        // 2026-10-30（用户需求）：游龙工具/自身执行任何命令都不写审计。
        if (session.caller.isReviewExempt) return
        // 管理器自己的普通命令（没有 stdin 内容）不写审计，避免刷屏；
        // 第三方应用的会话一律记录。
        if (session.caller.isManager && session.scriptSnapshot().isEmpty()) return
        try {
            synchronized(lock) {
                PolicyConfig.ensureDir()
                rotateIfNeeded()
                val file = PolicyConfig.AUDIT_FILE
                val firstWrite = !file.exists()
                file.appendText(buildJson(session, exitCode, decision) + "\n")
                if (firstWrite) PolicyConfig.chmod(file, "0600")
            }
            LOGGER.i(
                "审计：pkg=%s, decision=%s, blocked=%s, score=%d, exit=%s",
                session.caller.displayName,
                decision,
                session.blocked,
                session.lastScore,
                exitCode?.toString() ?: "-"
            )
        } catch (t: Throwable) {
            LOGGER.w(t, "写审计记录失败")
        }
    }

    private fun rotateIfNeeded() {
        val file = PolicyConfig.AUDIT_FILE
        if (!file.isFile) return
        if (file.length() <= PolicyConfig.MAX_AUDIT_FILE) return
        val old = File(file.parentFile, file.name + ".1")
        runCatching {
            if (old.exists()) old.delete()
            file.renameTo(old)
        }.onFailure { LOGGER.w(it, "审计日志轮转失败") }
    }

    private fun buildJson(session: ShellSession, exitCode: Int?, decision: String): String {
        val caller = session.caller
        val sb = StringBuilder(256)
        sb.append('{')
        field(sb, "ts", System.currentTimeMillis().toString())
        field(sb, "uid", caller.uid.toString())
        field(sb, "userId", caller.userId.toString())
        field(sb, "pkg", caller.packageName)
        field(sb, "cert", caller.certSha256)
        field(sb, "kind", session.kind)
        field(sb, "argv", session.argvText())
        field(sb, "stdin", session.scriptSnapshot())
        field(sb, "blocked", session.blocked.toString())
        field(sb, "decision", decision)
        field(sb, "reason", session.blockReason ?: session.lastDecisionReason)
        field(sb, "score", session.lastScore.toString())
        field(sb, "scopes", session.lastScopes.joinToString(","))
        field(sb, "signals", session.lastSignals.joinToString(","))
        field(sb, "exitCode", exitCode?.toString() ?: "")
        field(sb, "durationMs", (System.currentTimeMillis() - session.startTime).toString())
        sb.append('}')
        return sb.toString()
    }

    private fun field(sb: StringBuilder, name: String, value: String?) {
        if (sb.length > 1) sb.append(',')
        sb.append('"').append(name).append("\":\"").append(escape(value)).append('"')
    }

    private fun escape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val sb = StringBuilder(value.length + 16)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append('?') else sb.append(c)
            }
        }
        return sb.toString()
    }
}
