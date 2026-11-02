// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 一次 shell 会话的状态：调用方、argv、stdin 累计内容、判定结果、退出码。
//
// 生命周期：
//   newProcess/newPtyProcess 创建 → stdin 网关按块判定并转发 → 会话结束
//   （waitFor / destroy / 阻断）时由 finish() 写一条审计并关闭网关。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class ShellSession(
    val caller: CallerIdentity,
    val argv: Array<String?>,
    /** "newProcess" / "newPtyProcess" / "argv"。 */
    val kind: String
) {

    val startTime: Long = System.currentTimeMillis()

    /** 真实进程（argv 预检阶段可能还没创建）。 */
    @Volatile
    var process: Process? = null

    /** stdin 网关（可能不存在：应用没写过 stdin）。 */
    @Volatile
    var gateway: StdinGateway? = null

    /**
     * 2026-10-25：白名单应用在 precheck/脚本文件审查阶段已经弹过确认，
     * 后续 CommandInterceptor 不再重复弹窗。
     */
    @Volatile
    var skipGuard: Boolean = false

    @Volatile
    var blocked: Boolean = false
        private set

    @Volatile
    var blockReason: String? = null
        private set

    // ---- 最近一次判定（写审计用） ----
    @Volatile
    var lastDecision: String = "pending"

    @Volatile
    var lastDecisionReason: String? = null

    @Volatile
    var lastScore: Int = 0

    @Volatile
    var lastScopes: List<String> = emptyList()

    @Volatile
    var lastSignals: List<String> = emptyList()

    private val stdinLock = Any()

    private val stdinBuffer = StringBuilder()

    private val finished = AtomicBoolean(false)

    fun argvText(): String = argv.filterNotNull().joinToString(" ")

    /** 记录 stdin 片段（限长，只用于审计）。 */
    fun appendStdin(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        synchronized(stdinLock) {
            val room = PolicyConfig.MAX_AUDIT_STDIN - stdinBuffer.length
            if (room <= 0) return
            val take = min(length, room)
            stdinBuffer.append(String(bytes, 0, take, Charsets.UTF_8))
        }
    }

    /** 本次会话到目前为止收到的 stdin 全文（限长）。 */
    fun scriptSnapshot(): String = synchronized(stdinLock) { stdinBuffer.toString() }

    fun markDecision(result: String, decision: PolicyDecision?) {
        lastDecision = result
        if (decision != null) {
            lastDecisionReason = decision.reason
            lastScore = decision.score
            lastScopes = decision.facts?.scopes?.map { it.id } ?: emptyList()
            lastSignals = decision.signals
        }
    }

    /** 判定阻断：标记并回收进程树（不写审计，交给 finish）。 */
    fun block(reason: String) {
        if (!blocked) {
            blocked = true
            blockReason = reason
        }
        ProcessTree.killSession(process)
    }

    /** 会话结束（只审计一次）。 */
    fun finish(exitCode: Int?) {
        if (!finished.compareAndSet(false, true)) return
        runCatching { gateway?.shutdown() }
        AuditLog.record(this, exitCode, lastDecision)
    }

    /** 会话是否已经结束。 */
    fun isFinished(): Boolean = finished.get()
}
