// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   server/src/main/kotlin/roro/stellar/server/service/process/ProcessManager.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动 1（2026-10，历史）：newProcess() 与 newPtyProcess() 在真正 fork 进程之前，
//         调用 CommandInterceptor 做一次「命令行级」命令安全拦截。
//   改动 2（2026-10-24，按《游龙 Shizuku 防御方案》）：
//         · 解析调用方身份（包名 + 应用名称）并做确定性拦截（包名/名称黑名单 / 目标保护）；
//         · newProcess 不再把真实进程的 stdin 直接交给 App：交给 RemoteProcessHolder
//           一个服务端可控的管道写端，由 StdinGateway 审计完整脚本、判定后再按需
//           转发给真实进程（这正是「只看到 sh」问题的修复）；
//         · 未知应用打开 PTY（交互式 shell）默认拒绝，避免绕过 stdin 审计；
//         · 每次会话写审计（调用方/argv/stdin/判定/退出码）。
//   原因：获得 stellar 权限的第三方应用可以只执行 `sh`，把真正的攻击脚本写进
//         stdin（样本「终末神明Ultra」就是这样定向禁用本产品的）。只看 argv
//         永远看不到脚本，必须接管 stdin 管道。
//   新增逻辑的完整实现见本包新增文件以及 policy/ 子包（均为本工程新增）。
// ==========================================================================

package roro.stellar.server.service.process

import com.stellar.server.IRemoteProcess
import com.stellar.server.IRemotePtyProcess
import rikka.rish.RishConfig
import rikka.rish.RishConstants
import rikka.rish.RishHost
import roro.stellar.server.ClientManager
import roro.stellar.server.bootstrap.ServerBootstrap
import roro.stellar.server.api.RemoteProcessHolder
import roro.stellar.server.api.RemotePtyProcessHolder
import roro.stellar.server.service.process.policy.CallerResolver
import roro.stellar.server.service.process.policy.Capability
import roro.stellar.server.service.process.policy.HardBlockRules
import roro.stellar.server.service.process.policy.PolicyAction
import roro.stellar.server.service.process.policy.PolicyConfig
import roro.stellar.server.service.process.policy.PolicyDecision
import roro.stellar.server.service.process.policy.PolicyEngine
import roro.stellar.server.service.process.policy.ScriptFileInspector
import roro.stellar.server.service.process.policy.ShellScriptAnalyzer
import roro.stellar.server.service.process.policy.ShellSession
import roro.stellar.server.shizuku.ShizukuApiConstants
import roro.stellar.server.util.Logger
import java.io.File
import java.io.IOException

class ProcessManager(
    private val clientManager: ClientManager
) {
    companion object {
        private val LOGGER = Logger("ProcessManager")
    }

    fun newProcess(
        uid: Int,
        pid: Int,
        cmd: Array<String?>,
        env: Array<String?>?,
        dir: String?
    ): IRemoteProcess {
        LOGGER.d(
            "newProcess: uid=$uid, cmd=${cmd.contentToString()}, env=${env.contentToString()}, dir=$dir"
        )

        // 2026-10-24：调用方身份 + 确定性拦截（包名/名称黑名单 / argv 目标保护）
        val session = ShellSession(CallerResolver.resolve(uid, clientManager), cmd, "newProcess")
        precheck(session, cmd)

        // 2026-10-24：`sh /path/x.sh` 形态 —— spawn 前先把脚本文件读出来分析
        // （TOCTOU 说明：检查后到执行前文件仍可能被偷换，属已知残余风险）。
        applyScriptFilePolicy(session, cmd, dir)

        // 历史逻辑：命令行级规则（受保护应用 / 批量停止 / 卸载冻结确认等）
        guard(session, cmd)

        val process: Process = try {
            Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
        } catch (e: IOException) {
            session.markDecision("spawn-failed", null)
            session.finish(null)
            throw IllegalStateException(e.message)
        }
        session.process = process

        val clientRecord = clientManager.findClient(uid, pid)
        val token = clientRecord?.client?.asBinder()

        // stdin 由 RemoteProcessHolder + StdinGateway 接管（审计 + 判定 + 转发）
        return RemoteProcessHolder(process, token, session)
    }

    fun newPtyProcess(
        uid: Int,
        pid: Int,
        cmd: Array<String?>,
        env: Array<String?>?,
        dir: String?
    ): IRemotePtyProcess {
        val session = ShellSession(CallerResolver.resolve(uid, clientManager), cmd, "newPtyProcess")
        LOGGER.d("newPtyProcess: uid=$uid, cmd=${cmd.contentToString()}")
        precheck(session, cmd)

        // 2026-10-24：PTY 下 `sh /path/x.sh` 也要做脚本文件内容检查
        applyScriptFilePolicy(session, cmd, dir)

        // 历史逻辑：PTY 通道同样是完整 shell，必须先过命令行规则
        guard(session, cmd)

        // 2026-10-24（防御方案 §4/§8）：交互式 PTY 的 stdin 无法逐条审计，
        // 未知应用默认不授予；首次使用时走一次「脚本级审批」，允许后可用
        // （「始终允许」写入 scope_grants.txt，可在管理器撤销）。
        val caller = session.caller
        if (!caller.isManager && !caller.isTrusted && !caller.isReviewExempt
            && !CommandWhitelist.contains(caller.packageName)) {
            val pkg = caller.packageName
            val granted = pkg != null && PolicyConfig.hasGrant(pkg, Capability.INTERACTIVE)
            if (!granted) {
                val reason = "申请交互式终端（PTY）能力"
                val analysis = "风险评估：80/100（高）；交互式终端的输入无法逐条审计，" +
                        "只能以会话为单位记录；仅在你信任该应用时才允许。"
                val reply = CommandGuard.askPolicyConfirmation(
                    packageName = pkg,
                    scopeId = Capability.INTERACTIVE.id,
                    scopeLabel = Capability.INTERACTIVE.label,
                    reason = reason,
                    analysis = analysis,
                    riskLevel = "high",
                    score = 0,
                    command = session.argvText(),
                    script = "",
                    userId = caller.userId
                )
                if (!reply.allowed) {
                    val why = "用户拒绝了交互式终端（PTY）"
                    session.markDecision("denied-pty", null)
                    session.block(why)
                    session.finish(1)
                    throw SecurityException("命令已被「游龙安全护盾」拦截：$why")
                }
                if (reply.always && pkg != null) {
                    PolicyConfig.grant(pkg, Capability.INTERACTIVE)
                }
                session.markDecision(
                    if (reply.always) "allow-pty-always" else "allow-pty-once",
                    null
                )
            }
        }

        ServerBootstrap.managerApplicationInfo?.nativeLibraryDir?.let {
            RishConfig.setLibraryPath(it)
        }
        RishConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)
        val tty = (RishConstants.ATTY_IN or RishConstants.ATTY_OUT or RishConstants.ATTY_ERR).toByte()
        val host = RishHost(
            cmd.filterNotNull().toTypedArray(),
            env?.filterNotNull()?.toTypedArray(),
            dir ?: "",
            tty,
            null,
            null,
            null
        )
        host.start()
        session.markDecision("allow-pty", null)
        val token = clientManager.findClient(uid, pid)?.client?.asBinder()
        return RemotePtyProcessHolder(host, token, session)
    }

    // ======================================================================
    // 2026-10-24 新增：防御方案接入
    // ======================================================================

    /**
     * 进程创建前的确定性拦截（不弹审批）：
     * 包名/名称黑名单、argv 里已经能看到的受保护目标 → 直接拒绝。
     *
     * @throws SecurityException 命中硬拦截时抛出
     */
    private fun precheck(session: ShellSession, cmd: Array<String?>) {
        // 2026-10-30（用户需求）：游龙工具/自身不拦截、不审查。
        if (session.caller.isReviewExempt) return
        val decision = PolicyEngine.precheck(session.caller, cmd) ?: return
        if (decision.auditOnly) {
            session.markDecision("audit-block-precheck", decision)
            return
        }
        if (decision.action == PolicyAction.ASK) {
            // 2026-10-31（用户需求）：白名单应用免逐条询问。
            if (CommandWhitelist.contains(session.caller.packageName)
                && HardBlockRules.detect(session.argvText()) == null) {
                session.markDecision("allowed-precheck-whitelist", decision)
                session.skipGuard = true
                return
            }
            val reply = CommandGuard.askPolicyConfirmation(
                packageName = session.caller.packageName,
                scopeId = decision.scope?.id ?: "",
                scopeLabel = decision.scope?.label ?: "白名单命令",
                reason = decision.reason,
                analysis = decision.analysis,
                riskLevel = decision.riskLevel,
                score = decision.score,
                command = session.argvText(),
                script = "",
                userId = session.caller.userId
            )
            if (!reply.allowed) {
                session.markDecision("denied-precheck-whitelist", decision)
                session.block("用户拒绝了该命令：${decision.reason}")
                session.finish(1)
                throw SecurityException("命令已被用户拒绝：${decision.reason}")
            }
            session.markDecision("allowed-precheck-whitelist", decision)
            session.skipGuard = true
            return
        }
        if (decision.action != PolicyAction.BLOCK) return

        session.markDecision("blocked-precheck", decision)
        session.block(decision.reason)
        session.finish(null)
        CommandGuard.showBlockedAlert(
            session.caller.packageName,
            session.argvText(),
            decision.reason,
            session.caller.userId,
            "script",
            decision.riskLevel,
            decision.analysis
        )
        throw SecurityException("命令已被「游龙安全护盾」拦截：${decision.reason}")
    }

    /**
     * 2026-10-24：`sh /path/x.sh` / `sh x.sh` 形态的脚本文件分析。
     *
     * 进程还没创建时先读文件内容，走与 stdin 网关相同的判定：
     *   · 内容含 youlong（不区分大小写）→ 直接拒绝；
     *   · 内容疑似加密/编码（base64/openssl/gpg/xxd/eval/长 blob）→ 直接拒绝；
     *   · 文件不是可读文本（含 NUL / 大量控制字符）→ 按加密/二进制拒绝；
     *   · 超过 1MB → 无法完整审计，拒绝（fail-closed）。
     *
     * 相对路径按 newProcess 的 `dir` 解析，PTY 通道同样调用本函数。
     *
     * ⚠️ TOCTOU：检查后到真正执行前，文件仍可能被调用方偷换。彻底做法是
     * 把内容复制到服务端独占目录再执行副本（后续可做）。
     */
    private fun applyScriptFilePolicy(
        session: ShellSession,
        cmd: Array<String?>,
        dir: String?
    ) {
        // 2026-10-30（用户需求）：游龙工具/自身完全不审查脚本文件。
        if (session.caller.isReviewExempt) return
        // 管理器自身不受此限（与 PolicyEngine 的管理器豁免一致）。
        if (session.caller.isManager) return
        val commandWhitelisted = CommandWhitelist.contains(session.caller.packageName)

        // 1) 脚本/argv 里引用的文件：. /path、source /path、sh /path、
        //    sh -c '...'、$(cat /path)、cat /path | sh 等。
        //    动态路径（$X、通配符）无法确认实际文件 → fail-closed。
        val referenced = ScriptFileInspector.dangerousReferences(session.argvText(), dir)
            ?: ScriptFileInspector.dangerousShellCommandReferences(cmd, dir)
        if (referenced != null) {
            val reason = "脚本引用的文件不安全：$referenced"
            val analysis = "应用会执行一个无法通过安全审计的 sh 文件（加密/二进制/含危险命令/动态路径），" +
                    "已标记为高风险。"
            blockScriptFile(session, reason, analysis)
        }

        // 2) 找出本次真正会执行的脚本文件（含 env/busybox/timeout 包装、
        //    sh 文件、sh -c 'sh 文件'、直接执行 .sh 等），逐个读内容审查。
        val files = ScriptFileInspector.filesForExecution(cmd, dir)
        for (file in files) {
            when (val inspection = ScriptFileInspector.inspect(file)) {
                is ScriptFileInspector.Inspection.Blocked -> {
                    // 白名单也不再询问这些危险文件，直接硬拦截。
                    blockScriptFile(
                        session,
                        inspection.reason,
                        inspection.analysis
                    )
                }

                is ScriptFileInspector.Inspection.Text -> {
                    // 脚本内容里再引用别的文件（相对路径按脚本所在目录）
                    val nested = ScriptFileInspector.dangerousReferences(
                        inspection.content,
                        file.parent
                    )
                    if (nested != null) {
                        val reason = "脚本引用的文件不安全：$nested"
                        val analysis = "脚本 ${file.name} 引用了另一个无法通过安全审计的 sh 文件，" +
                                "已标记为高风险。"
                        blockScriptFile(session, reason, analysis)
                    }

                    val decision = PolicyEngine.evaluate(
                        session.caller,
                        cmd,
                        inspection.content,
                        file.parent
                    )
                    if (decision.auditOnly) {
                        session.markDecision("audit-script-file", decision)
                        continue
                    }
                    when (decision.action) {
                        PolicyAction.ALLOW -> session.markDecision("allow-script-file", decision)

                        PolicyAction.BLOCK -> {
                            if (commandWhitelisted && HardBlockRules.detect(inspection.content) == null) {
                                // 普通策略 BLOCK 对白名单放行；硬危险已在 PolicyEngine 返回 BLOCK。
                                session.markDecision("allow-script-file-whitelist", decision)
                            } else {
                                blockScriptFile(
                                    session,
                                    decision.reason,
                                    decision.analysis,
                                    decision.riskLevel,
                                    decision
                                )
                            }
                        }

                        PolicyAction.ASK -> {
                            if (commandWhitelisted) {
                                // 白名单应用免逐条确认；硬危险脚本已在上面的 BLOCK 分支。
                                session.markDecision("allow-script-file-whitelist", decision)
                                continue
                            }
                            val reply = CommandGuard.askPolicyConfirmation(
                                packageName = session.caller.packageName,
                                scopeId = decision.scope?.id ?: "",
                                scopeLabel = decision.scope?.label ?: "敏感操作",
                                reason = decision.reason,
                                analysis = decision.analysis,
                                riskLevel = decision.riskLevel,
                                score = decision.score,
                                command = session.argvText(),
                                script = inspection.content.take(4000),
                                userId = session.caller.userId
                            )
                            if (!reply.allowed) {
                                session.markDecision("denied-script-file", decision)
                                session.block("用户拒绝了脚本文件执行：${decision.reason}")
                                session.finish(1)
                                throw SecurityException("命令已被用户拒绝：${decision.reason}")
                            }
                            if (commandWhitelisted) {
                                session.skipGuard = true
                            }
                            if (reply.always && decision.scope != null) {
                                PolicyConfig.grant(session.caller.packageName, decision.scope)
                            }
                            session.markDecision(
                                if (reply.always) "allow-script-file-always" else "allow-script-file-once",
                                decision
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 2026-10-25：指令白名单应用遇到高风险脚本文件时，不硬拦截，弹窗询问。
     * 用户允许后跳过 CommandInterceptor 的重复确认。
     */
    private fun askWhitelistedFileRisk(
        session: ShellSession,
        reason: String,
        analysis: String,
        score: Int
    ) {
        val effectiveScore = maxOf(score, 100)
        val reply = CommandGuard.askPolicyConfirmation(
            packageName = session.caller.packageName,
            scopeId = "",
            scopeLabel = "白名单脚本",
            reason = reason,
            analysis = "风险评估：${effectiveScore}/100（极高）；$analysis",
            riskLevel = "critical",
            score = effectiveScore,
            command = session.argvText(),
            script = "",
            userId = session.caller.userId
        )
        if (!reply.allowed) {
            session.markDecision("denied-script-file-whitelist", null)
            session.block("用户拒绝了白名单应用的脚本文件执行：$reason")
            session.finish(1)
            throw SecurityException("命令已被用户拒绝：$reason")
        }
        session.markDecision("allow-script-file-whitelist-once", null)
        session.skipGuard = true
    }

    /** 脚本文件硬拦截：写审计 + 弹「已阻止」+ 抛 SecurityException。 */
    private fun blockScriptFile(
        session: ShellSession,
        reason: String,
        analysis: String,
        riskLevel: String = "critical",
        decision: PolicyDecision? = null
    ): Nothing {
        session.markDecision("blocked-script-file", decision)
        session.block(reason)
        session.finish(null)
        CommandGuard.showBlockedAlert(
            session.caller.packageName,
            session.argvText(),
            reason,
            session.caller.userId,
            "script",
            riskLevel,
            analysis
        )
        throw SecurityException("命令已被「游龙安全护盾」拦截：$reason")
    }

    // ======================================================================
    // 历史逻辑：命令行级命令安全拦截（CommandInterceptor）
    // ======================================================================

    /**
     * 对一条即将执行的命令做安全判定；不允许时抛出 SecurityException，
     * 该异常会沿 binder 调用栈返回给发起方。
     */
    private fun guard(session: ShellSession, cmd: Array<String?>) {
        // 2026-10-30（用户需求）：游龙工具/自身任何命令都不拦截、不确认。
        if (session.caller.isReviewExempt) return
        // 2026-10-25：前面的 precheck/脚本文件审查已经弹过确认，不再重复问。
        if (session.skipGuard) return

        val caller = session.caller
        val decision = CommandInterceptor.inspect(cmd, caller.packageName, caller.isManager)
        if (decision is InterceptDecision.Allow) return

        val flat = session.argvText()
        val userId = caller.userId
        val facts = ShellScriptAnalyzer.analyze(flat)
        val commandWhitelisted = CommandWhitelist.contains(caller.packageName)

        when (decision) {
            is InterceptDecision.Block -> {
                // 2026-10-31：硬危险命令对白名单也直接拦截，不再询问。
                session.markDecision("blocked-argv", null)
                session.block(decision.reason)
                session.finish(null)
                CommandGuard.showBlockedAlert(
                    caller.packageName,
                    flat,
                    decision.reason,
                    userId,
                    "normal",
                    decision.riskLevel,
                    decision.analysis
                )
                throw SecurityException(
                    "命令已被「游龙安全护盾」拦截：${decision.reason}"
                )
            }

            is InterceptDecision.NeedConfirm -> {
                // 2026-10-31：白名单应用普通命令免确认。
                if (commandWhitelisted && HardBlockRules.detect(flat) == null) {
                    session.markDecision("allowed-argv-whitelist", null)
                    return
                }
                val riskLevel = decision.riskLevel
                val score = facts.score
                val analysis = riskPrefix(score, riskLevel) + decision.analysis +
                        if (commandWhitelisted) "；该应用在指令拦截白名单中，请确认本次是否执行。" else ""
                val allowed = CommandGuard.askForConfirmation(
                    caller.packageName,
                    flat,
                    decision.reason,
                    userId,
                    decision.kind,
                    riskLevel,
                    analysis,
                    commandWhitelisted || decision.whitelisted
                )
                if (!allowed) {
                    session.markDecision("denied-argv", null)
                    session.block("用户拒绝了该命令：${decision.reason}")
                    session.finish(null)
                    throw SecurityException(
                        "命令已被用户拒绝：${decision.reason}"
                    )
                }
                session.markDecision("allowed-argv", null)
                LOGGER.i("用户已允许 %s 执行：%s", caller.packageName, decision.reason)
            }

            is InterceptDecision.Allow -> Unit
        }
    }

    /** 指令白名单应用遇到 Block 级命令：改成询问，用户允许后放行。 */
    private fun askWhitelistedCommand(
        session: ShellSession,
        reason: String,
        analysis: String,
        score: Int,
        riskLevel: String,
        kind: String
    ): Boolean {
        val effective = maxOf(score, baseScore(riskLevel))
        val allowed = CommandGuard.askForConfirmation(
            session.caller.packageName,
            session.argvText(),
            reason,
            session.caller.userId,
            kind,
            riskLevel,
            riskPrefix(effective, riskLevel) + analysis +
                    "；该应用在指令拦截白名单中，请确认本次是否执行。",
            true
        )
        if (!allowed) {
            session.markDecision("denied-argv-whitelist", null)
            session.block("用户拒绝了该命令：$reason")
            session.finish(null)
            throw SecurityException("命令已被用户拒绝：$reason")
        }
        session.markDecision("allowed-argv-whitelist", null)
        return true
    }

    private fun riskPrefix(score: Int, riskLevel: String): String {
        val label = when (riskLevel.lowercase()) {
            "critical" -> "极高"
            "high" -> "高"
            "medium" -> "中"
            else -> "低"
        }
        return "风险评估：${maxOf(score, baseScore(riskLevel))}/100（${label}）；"
    }

    private fun baseScore(riskLevel: String): Int = when (riskLevel.lowercase()) {
        "critical" -> 100
        "high" -> 80
        "medium" -> 50
        else -> 0
    }
}
