// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 「脚本级防御」的决策引擎（《游龙 Shizuku 防御方案》L2 + L3 + L4）。
//
// 决策顺序（先命中先返回）：
//   0. 管理器自身         → 放行（仍然审计）
//   1. 包名/名称黑名单    → 直接拒绝（不弹窗，防社工）；签名黑名单已按用户要求移除
//   2. 目标保护           → 直接拒绝（本产品/配套/用户自定义受保护包）
//   3. 系统关键组件       → 审批（默认拒绝）
//   4. 受信应用           → 放行；仅行为链评分 ≥100 时改为审批
//   5. 行为链评分 ≥100    → 直接拒绝
//   6. 缺少能力作用域     → 审批（允许一次 / 始终允许该能力 / 拒绝）
//   7. 行为链评分 60–99   → 审批
//   8. 编码/动态执行      → 审批（内容不可见，保守处理）
//   9. 其它               → 放行 + 审计
//
// 只审计模式（policy_mode.txt = audit）下，本应 BLOCK/ASK 的判定只记录、不执行。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import roro.stellar.server.service.process.CommandInterceptor
import roro.stellar.server.service.process.CommandWhitelist
import roro.stellar.server.service.process.InterceptDecision
import roro.stellar.server.util.Logger

enum class PolicyAction { ALLOW, BLOCK, ASK }

/**
 * 一次策略判定。
 *
 * @param wouldBeAction 只审计模式下，本应采取的原始动作（用于审计/灰度分析）
 */
data class PolicyDecision(
    val action: PolicyAction,
    val reason: String,
    val analysis: String,
    val riskLevel: String,
    val scope: Capability?,
    val score: Int,
    val signals: List<String>,
    val facts: ScriptFacts?,
    val auditOnly: Boolean = false,
    val wouldBeAction: PolicyAction = action
)

object PolicyEngine {

    private val LOGGER = Logger("StellarPolicyEngine")

    // ------------------------------------------------------------------
    // 进程创建前的快速检查（只做确定性拒绝，不做审批）
    // ------------------------------------------------------------------

    /**
     * `newProcess` / `newPtyProcess` 在 fork 之前调用。
     *
     * 这里只做「确定性拒绝」：包名/名称黑名单、argv 里已经能看到的受保护目标。
     * 需要审批的情况留到 stdin 网关（有完整脚本时才问，避免只看到 `sh` 就弹窗）。
     *
     * @return null 表示放行；非 null 且 action=BLOCK 表示直接拒绝
     */
    fun precheck(caller: CallerIdentity, argv: Array<String?>): PolicyDecision? {
        if (caller.isManager) return null
        val argvText = argv.filterNotNull().joinToString(" ")
        val facts = ShellScriptAnalyzer.analyze(argvText)
        val raw = rawPrecheck(caller, facts, argvText) ?: return null
        if (PolicyConfig.isAuditOnly()) {
            LOGGER.w(
                "（只审计模式）本应拦截调用：pkg=%s, reason=%s",
                caller.displayName, raw.reason
            )
            return raw.copy(action = PolicyAction.ALLOW, auditOnly = true)
        }
        LOGGER.w("拦截调用：pkg=%s, reason=%s", caller.displayName, raw.reason)
        return wrapCommandWhitelist(caller, raw, argvText)
    }

    private fun rawPrecheck(
        caller: CallerIdentity,
        facts: ScriptFacts,
        argvText: String
    ): PolicyDecision? {
        // 2026-10-31（用户需求）：白名单应用免逐条确认，但针对游龙/系统关键组件的
        // 卸载、冻结、强停、kill 永远硬拦截。
        val hardBlock = HardBlockRules.detect(argvText)
        if (hardBlock != null) {
            return block(
                facts,
                hardBlock,
                "极高风险：$hardBlock，已直接阻止（白名单也不放行）。",
                "critical"
            )
        }
        // 2026-10-25：按用户要求去掉签名黑名单，只保留包名/名称黑名单与脚本内容审计。
        // 2026-10-25：包名黑名单（safe1 + virus-db packages）→ 直接拒绝。
        // 之前只查证书，包名命中的应用执行命令不会被拦（用户反馈「特征库最新但不拦截」）。
        val virusPkg = VirusSnapshot.matchPackage(caller.packageName)
        if (virusPkg != null && "block".equals(virusPkg.action, ignoreCase = true)) {
            return block(
                facts,
                "执行超级高风险命令。",
                "此应用执行超高风险命令，我们已阻止。",
                "critical"
            )
        }
        // 2026-10-25：名称库命中（mingcheng1/2）→ 同样直接拒绝
        val virusName = VirusSnapshot.matchName(caller.appLabel)
        if (virusName != null && "block".equals(virusName.action, ignoreCase = true)) {
            return block(
                facts,
                "执行超级高风险命令。",
                "此应用执行超高风险命令，我们已阻止。",
                "critical"
            )
        }
        // 2026-10-25：脚本内容危险命令直接拦（youlong / 批量冻结卸载 / 系统破坏 /
        // 无障碍写入 / nohup 持久化 / 编码执行 / 安装应用 等，见 DangerousScriptDetector）。
        val dangerousArgv = DangerousScriptDetector.detect(argvText)
        if (dangerousArgv != null) {
            return block(
                facts,
                "脚本包含危险命令：$dangerousArgv",
                "命令/脚本内容命中危险操作特征（$dangerousArgv），已直接阻止。",
                "critical"
            )
        }
        // 2026-10-24：argv 里就能看出的策略/快照篡改（如 sh -c 'cp ... trusted_apps.txt'）
        if (facts.policyTamper) {
            return block(
                facts,
                "尝试修改内置特权策略/快照目录",
                "命令会写入、删除或复制内置特权的策略/病毒库快照文件，可能用于关闭防御，已直接阻止。",
                "critical"
            )
        }
        if (facts.protectedTargets.isNotEmpty()) {
            return block(
                facts,
                "目标保护：${facts.protectedTargets.joinToString("、")}",
                "命令会冻结/卸载/清除受保护应用（游龙安全护盾、配套应用或系统关键组件），" +
                        "已直接阻止。",
                "critical"
            )
        }
        // 2026-10-24（病毒库对接）：argv 里直接带恶意样本特征（如 sh -c '...'）也拦。
        val ruleResult = VirusSnapshot.matchScript(argvText)
        if (ruleResult.block) {
            return block(
                facts,
                "病毒特征库规则命中：${ruleResult.reasons.take(4).joinToString("、")}",
                "命令行命中已知恶意样本的行为规则（累计 ${ruleResult.score} 分），已直接阻止。",
                "critical"
            )
        }
        return null
    }

    // ------------------------------------------------------------------
    // 完整脚本判定
    // ------------------------------------------------------------------

    /**
     * 对一次 stdin 写入（脚本/交互命令）做判定。
     *
     * @param script 会话累计的完整脚本（交互式会话则为累计的命令历史）
     */
    fun evaluate(
        caller: CallerIdentity,
        argv: Array<String?>,
        script: String,
        /** 脚本所在目录；用于解析脚本里 `. /相对路径` 这类引用。 */
        scriptDir: String? = null
    ): PolicyDecision {
        if (caller.isManager) {
            return allow("管理器自身", null, 0)
        }
        val textForWhitelist = if (script.isBlank()) {
            argv.filterNotNull().joinToString(" ")
        } else {
            script
        }
        return wrapCommandWhitelist(caller, evaluateInternal(caller, argv, script, scriptDir), textForWhitelist)!!
    }

    /**
     * 2026-11-02（用户需求）：加入指令拦截白名单后，该应用执行的所有命令
     * 不再被拦截，也不再弹确认；包括 youlong、HardBlockRules、脚本文件、
     * 变量拼接等全部放行。
     */
    private fun wrapCommandWhitelist(
        caller: CallerIdentity,
        decision: PolicyDecision?,
        text: String
    ): PolicyDecision? {
        if (decision == null) return null
        if (!CommandWhitelist.contains(caller.packageName)) return decision

        return decision.copy(
            action = PolicyAction.ALLOW,
            reason = "指令白名单应用，全部命令放行",
            analysis = "该应用在指令拦截白名单中，所有命令免拦截、免确认放行。",
            riskLevel = "low",
            scope = null
        )
    }

    private fun riskPrefix(decision: PolicyDecision): String =
        riskPrefix(decision.score, decision.riskLevel)

    private fun riskPrefix(score: Int, riskLevel: String): String {
        val effective = when (riskLevel.lowercase()) {
            "critical" -> maxOf(score, 100)
            "high" -> maxOf(score, 80)
            "medium" -> maxOf(score, 50)
            else -> score
        }
        return "风险评估：${effective}/100（${riskLabel(riskLevel)}）；"
    }

    private fun riskLabel(level: String): String = when (level.lowercase()) {
        "critical" -> "极高"
        "high" -> "高"
        "medium" -> "中"
        else -> "低"
    }

    private fun evaluateInternal(
        caller: CallerIdentity,
        argv: Array<String?>,
        script: String,
        /** 脚本所在目录；用于解析脚本里 `. /相对路径` 这类引用。 */
        scriptDir: String? = null
    ): PolicyDecision {
        if (caller.isManager) {
            return allow("管理器自身", null, 0)
        }
        val facts = if (script.isBlank()) {
            ShellScriptAnalyzer.analyzeArgv(argv)
        } else {
            ShellScriptAnalyzer.analyze(script)
        }

        // 2026-10-24（病毒库对接）：先跑病毒库行为规则（README §4.3）。
        //   · 命中 action=block 的规则，或规则累计 ≥60 分 → 直接拒绝；
        //   · 否则把规则分数/原因并进行为链，后续阈值判定会用到。
        val analyzedText = if (script.isBlank()) {
            argv.filterNotNull().joinToString(" ")
        } else {
            script
        }

        // 2026-10-25：脚本内容危险命令直接拦（文件/stdin 都走这里）
        val dangerous = DangerousScriptDetector.detect(analyzedText)
        if (dangerous != null) {
            return block(
                facts,
                "脚本包含危险命令：$dangerous",
                "脚本内容命中危险操作特征（$dangerous），已直接阻止。",
                "critical"
            )
        }

        // 2026-10-25：脚本里 `. /path` / `source /path` / `sh /path` / `$(cat /path)`
        // 等引用到的 sh 文件，同样在执行前审查内容；加密/危险命令/动态路径直接拒绝。
        val referenced = ScriptFileInspector.dangerousReferences(analyzedText, scriptDir)
        if (referenced != null) {
            return block(
                facts,
                "脚本引用的文件不安全：$referenced",
                "脚本会执行一个无法通过安全审计的 sh 文件（加密/二进制/含危险命令/动态路径），已直接阻止。",
                "critical"
            )
        }

        // 2026-10-24：裸 shell 不再「交给网关」就等于放行 —— 把历史
        // CommandInterceptor 的确定性硬拦截（youlong 关键字 / 编码命令）补回来。
        // 它返回的 NeedConfirm 仍交给下面的能力模型，避免每条命令都弹窗。
        val legacy = CommandInterceptor.inspect(
            arrayOf("sh", "-c", analyzedText),
            caller.packageName,
            caller.isManager
        )
        if (legacy is InterceptDecision.Block) {
            return block(facts, legacy.reason, legacy.analysis, legacy.riskLevel)
        }

        val ruleResult = VirusSnapshot.matchScript(analyzedText)
        val raw = if (ruleResult.block) {
            block(
                facts,
                "病毒特征库规则命中：${ruleResult.reasons.take(4).joinToString("、")}",
                "脚本命中已知恶意样本的行为规则（累计 ${ruleResult.score} 分），已直接阻止。",
                "critical"
            )
        } else {
            if (ruleResult.score > 0) {
                facts.score += ruleResult.score
                ruleResult.reasons.forEach { facts.signals.add("病毒库规则 $it") }
            }
            decide(caller, facts)
        }

        if (PolicyConfig.isAuditOnly() && raw.action != PolicyAction.ALLOW) {
            LOGGER.w(
                "（只审计模式）本应 %s：pkg=%s, reason=%s, score=%d",
                raw.action, caller.displayName, raw.reason, raw.score
            )
            return raw.copy(action = PolicyAction.ALLOW, auditOnly = true)
        }
        return raw
    }

    /**
     * 2026-10-25：非 shell 入口（transactRemote / getSystemService / getApplications）
     * 的确定性黑名单检查。命中返回原因，未命中返回 null。
     *
     * 这些入口不经过 newProcess，之前黑名单应用可以绕过包名/名称拦截。
     */
    fun blacklistReason(caller: CallerIdentity): String? {
        if (caller.isManager) return null
        // 2026-10-25：受信/指令白名单应用不再因为黑名单被非 shell 入口硬拦截。
        if (caller.isTrusted || CommandWhitelist.contains(caller.packageName)) return null
        // 2026-10-25：按用户要求去掉签名黑名单（证书匹配）。
        val pkg = VirusSnapshot.matchPackage(caller.packageName)
        if (pkg != null && "block".equals(pkg.action, ignoreCase = true)) {
            return "调用方包名命中黑名单（${pkg.family ?: "病毒库"}）"
        }
        val name = VirusSnapshot.matchName(caller.appLabel)
        if (name != null && "block".equals(name.action, ignoreCase = true)) {
            return "调用方应用名称命中黑名单（${name.family ?: "病毒库"}）"
        }
        return null
    }

    private fun decide(caller: CallerIdentity, facts: ScriptFacts): PolicyDecision {
        // 0. 防御完整性：脚本尝试改写策略/快照目录 → 直接拒绝（受信应用也一样）
        if (facts.policyTamper) {
            return block(
                facts,
                "尝试修改内置特权策略/快照目录",
                "脚本尝试写入、删除或复制内置特权的策略/病毒库快照文件，可能用于关闭防御，已直接阻止。",
                "critical"
            )
        }

        // 1. （2026-10-25 按用户要求移除签名黑名单）

        // 2. 包名黑名单（safe1 + virus-db packages）：直接拒绝，不弹窗
        val virusPkg = VirusSnapshot.matchPackage(caller.packageName)
        if (virusPkg != null && "block".equals(virusPkg.action, ignoreCase = true)) {
            return block(
                facts,
                "执行超级高风险命令。",
                "此应用执行超高风险命令，我们已阻止。",
                "critical"
            )
        }
        // 2026-10-25：名称库命中（mingcheng1/2）→ 同样直接拒绝
        val virusName = VirusSnapshot.matchName(caller.appLabel)
        if (virusName != null && "block".equals(virusName.action, ignoreCase = true)) {
            return block(
                facts,
                "执行超级高风险命令。",
                "此应用执行超高风险命令，我们已阻止。",
                "critical"
            )
        }

        // 3. 目标保护：本产品 / 配套 / 自定义受保护包 → 直接拒绝
        if (facts.protectedTargets.isNotEmpty()) {
            return block(
                facts,
                "目标保护：${facts.protectedTargets.joinToString("、")}",
                "命令会冻结/卸载/清除受保护应用（游龙安全护盾、配套应用或系统关键组件），" +
                        "已直接阻止。",
                "critical"
            )
        }

        // 4. 系统关键组件：审批（默认拒绝）
        if (facts.systemTargets.isNotEmpty()) {
            return ask(
                facts,
                "操作系统关键组件：${facts.systemTargets.joinToString("、")}",
                "目标是系统关键组件（设置/系统界面等），冻结或卸载可能导致系统无法正常使用。",
                "high",
                Capability.PKG_CONTROL
            )
        }

        // 5. 受信应用：预授权全部能力；只有评分极高时改为审批
        if (caller.isTrusted) {
            if (facts.score >= 100) {
                return ask(
                    facts,
                    "行为链评分过高（${facts.score}）：${signalText(facts)}",
                    "受信应用触发了多个高危行为信号，请确认这一次是否确实要执行。",
                    "critical",
                    null
                )
            }
            return allow("受信应用", facts, facts.score)
        }

        // 6. 行为链评分极高：直接拒绝（不弹窗）
        if (facts.score >= 100) {
            return block(
                facts,
                "行为链评分过高（${facts.score}）：${signalText(facts)}",
                "该脚本同时出现多个高危行为信号（批量禁用组件、前台杀手、持久化看门狗、" +
                        "无障碍写入等），已直接阻止。",
                "critical"
            )
        }

        // 7. 缺少能力作用域：审批「是否授予该能力」
        val missing = facts.scopes.filter {
            it != Capability.SHELL_BASIC && !PolicyConfig.hasGrant(caller.packageName, it)
        }
        if (missing.isNotEmpty()) {
            val cap = missing.first()
            return ask(
                facts,
                "请求使用【${cap.label}】能力",
                "该应用此前未被授予「${cap.label}」能力。允许后，它可以用内置特权的 shell 身份" +
                        "执行这一类操作；「始终允许」会记住这次授权，可在管理器里撤销。",
                "high",
                cap
            )
        }

        // 8. 行为链评分中高：审批
        if (facts.score >= 60) {
            return ask(
                facts,
                "行为链评分 ${facts.score}：${signalText(facts)}",
                "检测到多个可疑行为信号，请确认是否放行。",
                "high",
                null
            )
        }

        // 9. 编码/动态执行：内容不可见，保守处理
        if (facts.encodedOrDynamic) {
            return ask(
                facts,
                "执行编码/动态生成的命令",
                "命令疑似经过 base64/openssl/xxd 解码或 eval 动态生成，真实内容不可见，" +
                        "可能用于隐藏危险操作。",
                "high",
                null
            )
        }

        // 10. 分析不完整（超过 64KB）：无法确认剩余部分安全 → 审批
        if (facts.truncated) {
            return ask(
                facts,
                "脚本超过分析上限（64KB）",
                "脚本过大，未能完整分析，无法确认其中是否包含危险命令，请确认是否放行。",
                "high",
                null
            )
        }

        // 11. 放行
        return allow("未发现危险行为", facts, facts.score)
    }

    // ------------------------------------------------------------------
    // 构造结果
    // ------------------------------------------------------------------

    private fun allow(reason: String, facts: ScriptFacts?, score: Int): PolicyDecision =
        PolicyDecision(
            action = PolicyAction.ALLOW,
            reason = reason,
            analysis = "低风险：未发现危险动作。",
            riskLevel = "low",
            scope = null,
            score = score,
            signals = facts?.signals?.toList() ?: emptyList(),
            facts = facts
        )

    private fun block(
        facts: ScriptFacts?,
        reason: String,
        analysis: String,
        riskLevel: String
    ): PolicyDecision =
        PolicyDecision(
            action = PolicyAction.BLOCK,
            reason = reason,
            analysis = analysis,
            riskLevel = riskLevel,
            scope = null,
            score = facts?.score ?: 0,
            signals = facts?.signals?.toList() ?: emptyList(),
            facts = facts
        )

    private fun ask(
        facts: ScriptFacts?,
        reason: String,
        analysis: String,
        riskLevel: String,
        scope: Capability?
    ): PolicyDecision =
        PolicyDecision(
            action = PolicyAction.ASK,
            reason = reason,
            analysis = riskPrefix(facts?.score ?: 0, riskLevel) + analysis,
            riskLevel = riskLevel,
            scope = scope,
            score = facts?.score ?: 0,
            signals = facts?.signals?.toList() ?: emptyList(),
            facts = facts
        )

    private fun signalText(facts: ScriptFacts): String =
        if (facts.signals.isEmpty()) "无" else facts.signals.joinToString("、")
}
