// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// stdin 代理网关（《游龙 Shizuku 防御方案》的核心）：
//
//   App ──写──► 服务端管道读端 ──► 本网关 ──► 真实 sh 的 stdin
//
// 关键：不再把 App 的 stdin 直接透传给真实进程。网关先攒数据、做判定，
// 只有放行才喂给真实 `sh`；拒绝时一个字节都不写并回收进程树 —— 脚本里再
// 危险的命令也执行不了。
//
// 两种使用形态：
//   · 脚本模式：App 一次性写入整段脚本后 close（本样本）。网关攒到 EOF 才判定，
//     因此判定看到的是完整脚本；
//   · 交互模式：用户逐行输入。网关按「空闲窗口」切块（默认 180ms），逐块判定并
//     转发，避免交互式终端被缓冲卡死。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import android.os.ParcelFileDescriptor
import roro.stellar.server.service.process.CommandGuard
import roro.stellar.server.util.Logger
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class StdinGateway(
    private val session: ShellSession,
    private val readEnd: ParcelFileDescriptor,
    private val realStdin: OutputStream
) : Thread("StellarStdinGateway") {

    companion object {
        private val LOGGER = Logger("StellarStdinGateway")

        /** 收到数据后等待「更多的数据」的窗口：同一批写入会合并成一次判定。 */
        private const val COALESCE_MS = 180L

        /**
         * 单次判定的脚本上限（与 ShellScriptAnalyzer.MAX_ANALYZE 对齐）。
         *
         * 2026-10-24：以前这里允许 256KB，而分析器只静默看前 64KB，尾部命令
         * 可以绕过能力/评分判定。现在超过上限且没有换行时直接拒绝（fail-closed）。
         */
        private const val MAX_SCRIPT = 64 * 1024

        /**
         * 「语法不完整」（引号/for-do 未闭合）最多等多久。
         * 超过就按无法审计拒绝，避免脚本永远挂起，也避免用它拖到超时再续写绕过。
         */
        private const val INCOMPLETE_TIMEOUT_MS = 10_000L

        /** 队列结束标记。 */
        private val EOF = Any()
    }

    // 有界队列：App 疯狂写入时对 App 形成背压，避免服务端内存被刷爆
    // （256 × 8KB ≈ 2MB 上限）。
    private val queue = LinkedBlockingQueue<Any>(256)

    @Volatile
    private var stopped = false

    private var reader: Thread? = null

    private var realClosed = false

    /** 启动读线程与网关线程。 */
    fun startGateway() {
        isDaemon = true
        reader = Thread({ readerLoop() }, "StellarStdinReader").also {
            it.isDaemon = true
            it.start()
        }
        start()
    }

    /** 结束网关（会话结束/阻断时调用）。 */
    fun shutdown() {
        stopped = true
        runCatching { readEnd.close() }
        interrupt()
    }

    // ------------------------------------------------------------------
    // 读线程：只负责把 App 写进来的字节放进队列
    // ------------------------------------------------------------------

    private fun readerLoop() {
        val input = ParcelFileDescriptor.AutoCloseInputStream(readEnd)
        val buf = ByteArray(8192)
        try {
            while (!stopped) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    // 有界队列 + 超时：网关卡在审批时对 App 形成背压；
                    // 会话已结束/网关不消费时放弃读取，避免线程永久阻塞。
                    if (!queue.offer(buf.copyOf(n), 5, TimeUnit.SECONDS)) break
                }
            }
        } catch (_: Throwable) {
            // 关闭/异常都按 EOF 处理
        } finally {
            runCatching { input.close() }
            runCatching { queue.offer(EOF, 1, TimeUnit.SECONDS) }
        }
    }

    // ------------------------------------------------------------------
    // 网关线程：攒块 → 判定 → 转发/阻断
    // ------------------------------------------------------------------

    override fun run() {
        // 2026-10-30（用户需求）：游龙工具/自身的 stdin 原样透传，不攒块、不判定、不审计。
        if (session.caller.isReviewExempt) {
            runPassThroughForTool()
            return
        }

        val buffer = ByteArrayOutputStream()
        var holdSince = 0L
        try {
            while (!stopped) {
                val item = queue.poll(COALESCE_MS, TimeUnit.MILLISECONDS) ?: continue

                if (item === EOF) {
                    if (buffer.size() > 0 && !flush(buffer.toByteArray())) return
                    closeReal()
                    return
                }

                buffer.write(item as ByteArray)

                // 合并紧跟着到达的片段（脚本通常分多次写）
                var eof = false
                while (!stopped) {
                    val next = queue.poll(COALESCE_MS, TimeUnit.MILLISECONDS) ?: break
                    if (next === EOF) {
                        eof = true
                        break
                    }
                    buffer.write(next as ByteArray)
                    if (buffer.size() >= MAX_SCRIPT) break
                }

                if (eof) {
                    if (buffer.size() > 0 && !flush(buffer.toByteArray())) return
                    closeReal()
                    return
                }

                // 2026-10-24：只按「完整命令行」判定。没有换行就继续等 —— sh 本身
                // 也要等换行/EOF 才执行，所以把一条命令拆成多次写入不再能绕过判定。
                val text = buffer.toString("UTF-8")
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline < 0) {
                    if (buffer.size() > MAX_SCRIPT) {
                        blockTooLarge()
                        return
                    }
                    continue
                }

                val complete = text.substring(0, lastNewline + 1)
                if (looksIncomplete(complete)) {
                    // 引号未闭合 / for-do 未结束：命令还没写完，继续攒；
                    // 但最多等 INCOMPLETE_TIMEOUT_MS，超时按无法审计拒绝（fail-closed）。
                    if (holdSince == 0L) holdSince = System.currentTimeMillis()
                    if (buffer.size() > MAX_SCRIPT) {
                        blockTooLarge()
                        return
                    }
                    if (System.currentTimeMillis() - holdSince >= INCOMPLETE_TIMEOUT_MS) {
                        blockIncomplete()
                        return
                    }
                    continue
                }
                holdSince = 0L

                val completeBytes = complete.toByteArray(Charsets.UTF_8)
                val rest = text.substring(lastNewline + 1)
                buffer.reset()
                if (rest.isNotEmpty()) buffer.write(rest.toByteArray(Charsets.UTF_8))

                if (!flush(completeBytes)) return
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            LOGGER.w(t, "stdin 网关异常")
            session.markDecision("gateway-error", null)
        } finally {
            closeReal()
        }
    }

    /**
     * 判定并处理一个完整的命令块。
     *
     * @return true 继续会话；false 表示会话已被阻断/结束
     */
    private fun flush(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true

        session.appendStdin(bytes, bytes.size)
        val text = String(bytes, Charsets.UTF_8)

        // 纯空白直接转发（对 shell 无副作用）
        if (text.isBlank()) return writeReal(bytes)

        val decision = PolicyEngine.evaluate(
            session.caller,
            session.argv,
            text
        )

        when (decision.action) {
            PolicyAction.ALLOW -> {
                session.markDecision(if (decision.auditOnly) "audit-allow" else "allow", decision)
                return writeReal(bytes)
            }

            PolicyAction.BLOCK -> {
                blockAndAlert(decision, "blocked")
                return false
            }

            PolicyAction.ASK -> {
                val reply = ask(decision)
                if (reply.allowed) {
                    if (reply.always && decision.scope != null) {
                        PolicyConfig.grant(session.caller.packageName, decision.scope)
                    }
                    session.markDecision(
                        if (reply.always) "allow-always" else "allow-once",
                        decision
                    )
                    return writeReal(bytes)
                }
                session.markDecision("denied", decision)
                session.block("用户拒绝了该操作：${decision.reason}")
                session.finish(1)
                return false
            }
        }
    }

    /**
     * 游龙工具专用：收到多少字节就转发多少字节，完全不做审查/大小限制/审计。
     */
    private fun runPassThroughForTool() {
        try {
            while (!stopped) {
                val item = queue.poll(COALESCE_MS, TimeUnit.MILLISECONDS) ?: continue
                if (item === EOF) {
                    closeReal()
                    return
                }
                if (item is ByteArray && !writeReal(item)) return
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            LOGGER.w(t, "游龙工具 stdin 透传异常")
        } finally {
            closeReal()
        }
    }

    private fun ask(decision: PolicyDecision): CommandGuard.PolicyReply =
        CommandGuard.askPolicyConfirmation(
            packageName = session.caller.packageName,
            scopeId = decision.scope?.id ?: "",
            // 没有具体能力（例如行为链评分/编码执行）时给一个通用抬头
            scopeLabel = decision.scope?.label ?: "敏感操作",
            reason = decision.reason,
            analysis = decision.analysis,
            riskLevel = decision.riskLevel,
            score = decision.score,
            command = session.argvText(),
            script = session.scriptSnapshot(),
            userId = session.caller.userId
        )

    private fun blockAndAlert(decision: PolicyDecision, result: String) {
        session.markDecision(result, decision)
        session.block(decision.reason)
        // 即使调用方之后不调用 waitFor，也要有审计记录
        session.finish(1)
        runCatching {
            CommandGuard.showBlockedAlert(
                session.caller.packageName,
                session.argvText(),
                decision.reason,
                session.caller.userId,
                "script",
                decision.riskLevel,
                decision.analysis
            )
        }
    }

    /**
     * 2026-10-24：脚本超过审计上限 —— 一个字节都不转发，直接阻止并回收进程树。
     * 避免「分析器只看前 64KB、其余照常执行」的绕过。
     */
    private fun blockTooLarge() {
        val why = "脚本超过 ${MAX_SCRIPT / 1024}KB，无法完整审计"
        session.markDecision("blocked-too-large", null)
        session.block(why)
        session.finish(1)
        runCatching {
            CommandGuard.showBlockedAlert(
                session.caller.packageName,
                session.argvText(),
                why,
                session.caller.userId,
                "script",
                "critical",
                "脚本长度超过审计上限，可能包含未检查的危险命令，已直接阻止。"
            )
        }
    }

    /** 语法长时间不完整：无法审计，直接拒绝（fail-closed）。 */
    private fun blockIncomplete() {
        val why = "脚本语法不完整（引号/循环未闭合），无法审计"
        session.markDecision("blocked-incomplete", null)
        session.block(why)
        session.finish(1)
        runCatching {
            CommandGuard.showBlockedAlert(
                session.caller.packageName,
                session.argvText(),
                why,
                session.caller.userId,
                "script",
                "high",
                "脚本在超时后仍未闭合，可能包含无法检查的内容，已直接阻止。"
            )
        }
    }

    private fun writeReal(bytes: ByteArray): Boolean {        return try {
            realStdin.write(bytes)
            realStdin.flush()
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "写入真实进程 stdin 失败（可能进程已退出）")
            false
        }
    }

    private fun closeReal() {
        if (realClosed) return
        realClosed = true
        runCatching { realStdin.close() }
    }

    /**
     * 粗略判断脚本是否「还没写完」（引号未闭合、行尾是 do/then/管道符等）。
     * 只用于决定是否再等一会儿，不参与安全判定。
     */
    private fun looksIncomplete(text: String): Boolean {
        var quote = 0.toChar()
        // 是否处于「词首」：只有词首的 # 才是注释，避免把 echo a#b 当注释。
        var atWordStart = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
                i++
                continue
            }
            when {
                c == '\'' || c == '"' -> {
                    quote = c
                    atWordStart = false
                }

                // 注释里的引号不参与配对（否则 `# don't` 会把会话永久挂起）
                c == '#' && atWordStart -> {
                    while (i < text.length && text[i] != '\n') i++
                    atWordStart = true
                    continue
                }

                c == '\\' -> i++
                c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '|' || c == '&' -> {
                    atWordStart = true
                }

                else -> atWordStart = false
            }
            i++
        }
        if (quote != 0.toChar()) return true

        val trimmed = text.trimEnd()
        if (trimmed.endsWith("\\")) return true
        return trimmed.endsWith(" do") ||
                trimmed.endsWith(" then") ||
                trimmed.endsWith(" else") ||
                trimmed.endsWith(" in") ||
                trimmed.endsWith(" {") ||
                trimmed.endsWith("|") ||
                trimmed.endsWith("&&") ||
                trimmed.endsWith("||") ||
                trimmed.endsWith(">")
    }
}
