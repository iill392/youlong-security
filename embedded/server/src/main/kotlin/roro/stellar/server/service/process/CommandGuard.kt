// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：命令安全拦截的弹窗投递与用户答复等待（详见文件内注释）。
// 合规相关：它属于「与 MPL 代码并置的独立文件」（MPL-2.0 第 3.3 条 Larger Work），
//           本产品以 Apache-2.0 发布本文件；它调用 MPL 覆盖的 Stellar 服务端代码
//           仅通过既有内部 API，未复制或修改 MPL 代码本身。
// ==========================================================================

// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：把「命令被拦截 / 需要用户确认」这件事变成用户看得见的一次弹窗，
//       并在需要确认时把用户在弹窗上的选择回传给正在等待的服务端线程。
//
// 通信方式（服务端 <-> 管理器）：
//   服务端（uid 2000 shell）把请求写进 /data/local/tmp/stellar_guard/，
//   再把该文件路径通过 Intent 交给管理器侧的 CommandGuardActivity；
//   管理器读完请求后，把 "ALLOW" / "DENY" / "ACKNOWLEDGED" 写回同目录的
//   同名 .resp 文件；服务端轮询该文件拿到答复。
//
//   为什么不走 Binder：Stellar 的 AIDL 接口是上游定义的，新增一个「回传确认
//   结果」的方法要改 AIDL 与协议（并影响兼容性）；用文件握手完全不触碰上游
//   接口，且 /data/local/tmp 对服务端(shell)始终可写。
//
// ==========================================================================

package roro.stellar.server.service.process

import android.content.Intent
import rikka.hidden.compat.ActivityManagerApis
import roro.stellar.server.ServerConstants
import roro.stellar.server.util.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object CommandGuard {

    private val LOGGER = Logger("CommandGuard")

    /** 握手目录。shell 身份可读写；管理器侧用自己的 shell 权限写入答复。 */
    private val DIR: File = File("/data/local/tmp/stellar_guard")

    /** 用户确认的最长等待时间：超时即视为拒绝（安全默认）。 */
    private const val CONFIRM_TIMEOUT_MS = 30_000L

    /** 拦截告警弹窗的最长等待时间：只为确保用户看到说明，命令本身已拒绝。 */
    private const val BLOCK_ACK_TIMEOUT_MS = 8_000L

    /** 轮询间隔。 */
    private const val POLL_INTERVAL_MS = 150L

    /** 请求文件里「命令」字段的长度上限，避免超长脚本把文件撑爆。 */
    private const val MAX_CMD_LEN = 4000

    private val SEQ = AtomicLong(0)

    const val KIND_ALERT = "block"
    const val KIND_CONFIRM = "confirm"

    const val REPLY_ALLOW = "ALLOW"
    const val REPLY_DENY = "DENY"
    const val REPLY_ACK = "ACKNOWLEDGED"

    /**
     * 2026-10-23（用户需求）：「始终允许」。
     * 管理器弹窗的第三个按钮；服务端收到后除了放行这次，还会把
     * 「调用方包名 + 这条指令」记进 CommandAlwaysAllow，下次同一应用执行
     * 同一条指令不再询问（换指令仍询问）。
     *
     * 注意：前缀必须与 REPLY_ALLOW 区分开 —— "ALWAYS_ALLOW" 不以 "ALLOW"
     * 开头，所以不会误走「允许一次」分支。
     */
    const val REPLY_ALWAYS = "ALWAYS_ALLOW"

    // 2026-10-24 新增（《游龙 Shizuku 防御方案》）：脚本级审批。
    const val KIND_POLICY = "policy"

    /** 脚本级审批的「始终允许该能力」。 */
    const val REPLY_ALLOW_ALWAYS = "ALLOW_ALWAYS"

    /** 脚本级审批结果。 */
    data class PolicyReply(val allowed: Boolean, val always: Boolean)

    /**
     * 弹一个「已被拦截」告警框。不等待用户操作（命令已经被拒绝了，
     * 用户点不点「好的」都不影响结果）。
     */
    fun showBlockedAlert(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）
        riskKind: String = "normal",
        // 2026-10 第 19 轮：危险程度与给用户看的分析
        riskLevel: String = "critical",
        analysis: String = "极高风险：该命令已被安全策略直接阻止。"
    ) {
        val id = nextId()
        val request = writeRequest(
            id, KIND_ALERT, packageName, command, reason, riskKind, riskLevel, analysis
        )
        if (request == null) {
            LOGGER.w("告警框请求文件写入失败，跳过弹窗")
            return
        }
        if (!launch(
                id, KIND_ALERT, packageName, command, reason, request, userId,
                riskKind, riskLevel, analysis
            )
        ) {
            LOGGER.w("告警框拉起失败")
            cleanup(id)
            return
        }

        // 2026-10 第 19 轮（用户需求）：拦截必须有弹窗。
        // 这里短暂等待用户点一下「好的」，既保证弹窗真的展示出来，也让调用方
        // 的 SecurityException 在用户看到说明之后才返回。
        val deadline = System.currentTimeMillis() + BLOCK_ACK_TIMEOUT_MS
        val response = File(DIR, "$id.resp")
        while (System.currentTimeMillis() < deadline) {
            try {
                if (response.isFile && response.length() > 0) {
                    LOGGER.i("用户已看到拦截弹窗: %s", packageName)
                    cleanup(id)
                    return
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        LOGGER.w("拦截弹窗等待确认超时（%d ms），命令仍保持阻止", BLOCK_ACK_TIMEOUT_MS)
        cleanup(id)
    }

    /**
     * 弹一个「是否允许」确认框，并阻塞等待用户选择。
     *
     * <p>调用方是 binder 线程；等待期间该线程被占用，这是有意为之——
     * 命令必须等用户答复后才能决定是否执行。
     *
     * @return true 表示用户允许；超时、异常、管理器未响应一律返回 false。
     */
    fun askForConfirmation(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）
        riskKind: String = "normal",
        // 2026-10 第 19 轮：危险程度与给用户看的分析
        riskLevel: String = "medium",
        analysis: String = "中风险：请确认命令来源可信。",
        // 2026-10-18：调用方是否在指令拦截白名单中
        whitelisted: Boolean = false
    ): Boolean {
        val id = nextId()
        val request = writeRequest(
            id, KIND_CONFIRM, packageName, command, reason, riskKind, riskLevel, analysis, whitelisted
        )
        if (request == null) {
            LOGGER.w("确认框请求文件写入失败，按拒绝处理")
            return false
        }
        if (!launch(
                id, KIND_CONFIRM, packageName, command, reason, request, userId,
                riskKind, riskLevel, analysis, whitelisted
            )
        ) {
            LOGGER.w("确认框拉起失败，按拒绝处理")
            cleanup(id)
            return false
        }

        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        val response = File(DIR, "$id.resp")
        while (System.currentTimeMillis() < deadline) {
            try {
                if (response.isFile && response.length() > 0) {
                    val text = runCatching { response.readText() }.getOrDefault("")
                    val reply = text.trim().uppercase()
                    // 2026-10-23（用户需求）：用户选择「始终允许」。
                    // 先记住「调用方包名 + 这条指令」，本次直接放行。
                    // 是否命中、是否还允许，最终仍由 CommandInterceptor 的规则 1
                    // 把关：含 youlong 的命令在上一步就已经被 Block，进不到这里。
                    if (reply.startsWith(REPLY_ALWAYS)) {
                        CommandAlwaysAllow.add(packageName, command)
                        LOGGER.i("用户选择了「始终允许」: pkg=%s", packageName)
                        cleanup(id)
                        return true
                    }
                    val allow = reply.startsWith(REPLY_ALLOW)
                    LOGGER.i("用户%s了对 %s 的命令", if (allow) "允许" else "拒绝", packageName)
                    // 答复已读取，请求/答复文件不再需要，顺手清掉，
                    // 避免 /data/local/tmp/stellar_guard 里越积越多。
                    cleanup(id)
                    return allow
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                LOGGER.w("等待用户确认被中断，按拒绝处理")
                cleanup(id)
                return false
            }
        }
        LOGGER.w("等待用户确认超时（%d ms），按拒绝处理", CONFIRM_TIMEOUT_MS)
        cleanup(id)
        return false
    }

    // ------------------------------------------------------------------
    // 2026-10-24 新增（《游龙 Shizuku 防御方案》）：脚本级审批
    // ------------------------------------------------------------------

    /**
     * 弹一个「脚本级」审批框并阻塞等待用户选择。
     *
     * 与管理器 `CommandGuardActivity` 的协议（kind=policy）：
     *   · 请求文件 / Intent 里带 scopeId / scopeLabel / score / command / script；
     *   · 答复 token：DENY（默认拒绝）/ ALLOW（允许一次）/ ALLOW_ALWAYS（始终允许该能力）。
     *
     * 超时、异常、管理器未响应一律按拒绝处理（安全默认）。
     */
    fun askPolicyConfirmation(
        packageName: String?,
        scopeId: String,
        scopeLabel: String,
        reason: String,
        analysis: String,
        riskLevel: String,
        score: Int,
        command: String,
        script: String,
        userId: Int
    ): PolicyReply {
        val id = nextId()
        val request = writePolicyRequest(
            id, packageName, scopeId, scopeLabel, reason, analysis, riskLevel, score, command, script
        )
        if (request == null) {
            LOGGER.w("脚本审批请求写入失败，按拒绝处理")
            return PolicyReply(false, false)
        }
        if (!launchPolicy(
                id, packageName, scopeId, scopeLabel, reason, analysis, riskLevel, score,
                command, script, request, userId
            )
        ) {
            LOGGER.w("脚本审批框拉起失败，按拒绝处理")
            cleanup(id)
            return PolicyReply(false, false)
        }

        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        val response = File(DIR, "$id.resp")
        while (System.currentTimeMillis() < deadline) {
            try {
                if (response.isFile && response.length() > 0) {
                    val reply = runCatching { response.readText() }
                        .getOrDefault("")
                        .trim()
                        .uppercase()
                    val always = reply.startsWith(REPLY_ALLOW_ALWAYS)
                    val allowed = always || reply.startsWith(REPLY_ALLOW)
                    LOGGER.i(
                        "脚本审批：pkg=%s, allowed=%s, always=%s",
                        packageName, allowed, always
                    )
                    cleanup(id)
                    return PolicyReply(allowed, always)
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                cleanup(id)
                return PolicyReply(false, false)
            }
        }
        LOGGER.w("脚本审批等待超时（%d ms），按拒绝处理", CONFIRM_TIMEOUT_MS)
        cleanup(id)
        return PolicyReply(false, false)
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun nextId(): Long {
        // 时间戳 + 进程内自增，保证同一毫秒内多次请求也互不覆盖。
        // 用字符串拼接而不是位运算，避免 "shl 10" 触发 Long 溢出告警。
        val seq = SEQ.incrementAndGet()
        return "${System.currentTimeMillis()}$seq".toLong()
    }

    /**
     * 把请求写成 key=value 文本文件。命令原文可能含换行，统一把换行替换成
     * " ⏎ " 以便单行存放（只影响展示，不影响判定——判定早已完成）。
     */
    private fun writeRequest(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        // 2026-10 新增：风险类型，写进请求文件供弹窗使用
        riskKind: String = "normal",
        // 2026-10 第 19 轮：危险程度 + 给用户看的分析
        riskLevel: String = "medium",
        analysis: String = "中风险：请确认命令来源可信。",
        // 2026-10-18：指令拦截白名单标记
        whitelisted: Boolean = false
    ): String? {
        return try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                LOGGER.w("无法创建握手目录 %s", DIR.absolutePath)
                return null
            }
            // 收紧目录权限：0711 = 属主(shell)可读写、其它身份只能穿过不能列目录。
            // /data/local/tmp 本身也是 0711，普通应用连目录项都看不到，
            // 因此第三方应用无法读取请求内容或伪造答复文件。
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "0711", DIR.absolutePath)).waitFor()
            }.onFailure { LOGGER.w(it, "设置握手目录权限失败（不影响功能）") }
            val req = File(DIR, "$id.req")
            val body = buildString {
                append("id=").append(id).append('\n')
                append("kind=").append(kind).append('\n')
                append("package=").append(packageName ?: "").append('\n')
                append("reason=").append(reason.replace('\n', ' ')).append('\n')
                // 2026-10 新增：命令分析得出的风险类型，供弹窗给出针对性说明
                append("riskKind=").append(riskKind).append('\n')
                // 2026-10 第 19 轮：危险程度与分析
                append("riskLevel=").append(riskLevel).append('\n')
                append("analysis=").append(analysis.replace('\n', ' ')).append('\n')
                append("whitelisted=").append(whitelisted).append('\n')
                append("command=")
                    .append(
                        command.take(MAX_CMD_LEN).replace('\n', ' ').replace('\r', ' ')
                    )
                    .append('\n')
            }
            req.writeText(body)
            // 2026-10 第 19 轮修复：预先创建答复文件并放开写权限。
            // 握手目录是 0711（app 只能穿过、不能创建文件），如果等 app 自己
            // 创建 .resp 会 EACCES，用户点「允许」也永远传不回服务端。
            // 这里由 shell 先创建同名空文件并 chmod 0666，app 只能写这一个
            // 已存在的文件，仍然不能列目录或伪造其它请求的答复。
            val resp = File(DIR, "$id.resp")
            resp.delete()
            resp.writeText("")
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("chmod", "0666", resp.absolutePath))
                    .waitFor()
            }.onFailure { LOGGER.w(it, "设置答复文件权限失败（app 可能无法回写）") }
            req.absolutePath
        } catch (t: Throwable) {
            LOGGER.w(t, "写请求文件失败")
            null
        }
    }

    private fun launch(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        requestPath: String,
        userId: Int,
        // 2026-10 新增：风险类型，放进 Intent 给弹窗
        riskKind: String = "normal",
        riskLevel: String = "medium",
        analysis: String = "中风险：请确认命令来源可信。",
        whitelisted: Boolean = false
    ): Boolean {
        return try {
            val intent = Intent(ServerConstants.COMMAND_GUARD_ACTION)
                .setPackage(ServerConstants.MANAGER_APPLICATION_ID)
                // 2026-10-18 修复：弹窗必须独立任务，不能复用安全护盾主界面所在的
                // 任务栈。否则弹窗关闭后会露出安全护盾主页，而不是回到调用方应用。
                // 配合 manifest 的独立 taskAffinity + 弹窗自己的 finishAndRemoveTask。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("id", id)
                .putExtra("kind", kind)
                .putExtra("packageName", packageName)
                .putExtra("command", command.take(MAX_CMD_LEN))
                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)
                // 2026-10 新增：风险类型，弹窗据此给出针对性说明
                .putExtra("riskKind", riskKind)
                // 2026-10 第 19 轮：危险程度 + 分析
                .putExtra("riskLevel", riskLevel)
                .putExtra("analysis", analysis)
                .putExtra("whitelisted", whitelisted)
            ActivityManagerApis.startActivityNoThrow(intent, null, userId)
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "拉起命令拦截对话框失败")
            false
        }
    }

    /** 2026-10-24 新增：脚本预览长度上限（只影响展示；审计另有 16KB 上限）。 */
    private const val MAX_SCRIPT_LEN = 4000

    /** 2026-10-24 新增：写脚本级审批的请求文件（格式与 confirm 一致，额外带能力/评分/脚本）。 */
    private fun writePolicyRequest(
        id: Long,
        packageName: String?,
        scopeId: String,
        scopeLabel: String,
        reason: String,
        analysis: String,
        riskLevel: String,
        score: Int,
        command: String,
        script: String
    ): String? {
        return try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                LOGGER.w("无法创建握手目录 %s", DIR.absolutePath)
                return null
            }
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "0711", DIR.absolutePath)).waitFor()
            }.onFailure { LOGGER.w(it, "设置握手目录权限失败（不影响功能）") }

            val req = File(DIR, "$id.req")
            val body = buildString {
                append("id=").append(id).append('\n')
                append("kind=").append(KIND_POLICY).append('\n')
                append("package=").append(packageName ?: "").append('\n')
                append("scope=").append(scopeId).append('\n')
                append("scopeLabel=").append(scopeLabel.replace('\n', ' ')).append('\n')
                append("score=").append(score).append('\n')
                append("reason=").append(reason.replace('\n', ' ')).append('\n')
                append("riskLevel=").append(riskLevel).append('\n')
                append("analysis=").append(analysis.replace('\n', ' ')).append('\n')
                append("command=")
                    .append(command.take(MAX_CMD_LEN).replace('\n', ' ').replace('\r', ' '))
                    .append('\n')
                append("script=")
                    .append(script.take(MAX_SCRIPT_LEN).replace('\r', ' ').replace('\n', ' '))
                    .append('\n')
            }
            req.writeText(body)

            val resp = File(DIR, "$id.resp")
            resp.delete()
            resp.writeText("")
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("chmod", "0666", resp.absolutePath))
                    .waitFor()
            }.onFailure { LOGGER.w(it, "设置答复文件权限失败（app 可能无法回写）") }
            req.absolutePath
        } catch (t: Throwable) {
            LOGGER.w(t, "写脚本审批请求文件失败")
            null
        }
    }

    /** 2026-10-24 新增：拉起脚本级审批弹窗（kind=policy）。 */
    private fun launchPolicy(
        id: Long,
        packageName: String?,
        scopeId: String,
        scopeLabel: String,
        reason: String,
        analysis: String,
        riskLevel: String,
        score: Int,
        command: String,
        script: String,
        requestPath: String,
        userId: Int
    ): Boolean {
        return try {
            val intent = Intent(ServerConstants.COMMAND_GUARD_ACTION)
                .setPackage(ServerConstants.MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("id", id)
                .putExtra("kind", KIND_POLICY)
                .putExtra("packageName", packageName)
                .putExtra("command", command.take(MAX_CMD_LEN))
                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)
                .putExtra("riskKind", "script")
                .putExtra("riskLevel", riskLevel)
                .putExtra("analysis", analysis)
                .putExtra("scope", scopeId)
                .putExtra("scopeLabel", scopeLabel)
                .putExtra("score", score)
                .putExtra("script", script.take(MAX_SCRIPT_LEN))
            ActivityManagerApis.startActivityNoThrow(intent, null, userId)
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "拉起脚本审批对话框失败")
            false
        }
    }

    private fun cleanup(id: Long) {
        runCatching { File(DIR, "$id.req").delete() }
        runCatching { File(DIR, "$id.resp").delete() }
    }

    /**
     * 管理器侧写答复用的目录路径（供管理器代码引用，保证两端一致）。
     */
    fun handshakeDirPath(): String = DIR.absolutePath
}
