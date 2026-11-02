// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：对「已授权应用」通过内置 Stellar 下发的 shell 命令做安全拦截与命令分析。
//
// 背景：内置 Stellar 是自包含的特权内核，本应用自己就是管理器。但一旦某个
// 第三方应用获得了 stellar 权限，它就能用 Stellar 的 shell 身份执行任意命令，
// 包括卸载/冻结/批量停止其它应用，甚至针对本产品自身。本文件把这类危险命令
// 拦在「真正 fork 进程」之前，位于 ProcessManager.newProcess() 这个唯一汇聚点。
//
// 与 MPL-2.0 的关系：本文件是**本工程新增**，不是上游派生文件；
// Stellar 侧被改动的文件（ProcessManager.kt）均已按 MPL-2.0 第 3.4 条
// 在文件内标注修改声明。
//
// ==========================================================================
// 判定总览（按优先级，先命中先返回）
// --------------------------------------------------------------------------
//   规则 1  针对自家应用（com.youlong.hd / com.youlong.tool）  → 直接拒绝 + 弹窗告警
//   规则 2  批量停止所有应用（am kill-all / pkill / killall /
//           枚举包后循环 force-stop）                          → 直接拒绝 + 弹窗告警
//   规则 3  卸载 / 冻结 / 清数据                              → 询问用户
//   规则 4  设备管理员 / 用户 / 设备策略（dpm 等）             → 询问用户
//   规则 5  「始终允许」记忆（同一应用 + 同一条指令）           → 直接放行、不弹窗
//
//   ⚠️ 规则 5 在规则 1 之后判定：命令里只要出现 youlong，就已经在规则 1 被直接
//      阻止了，因此针对 com.youlong.hd / com.youlong.tool 的冻结、卸载命令
//      永远不会被「始终允许」记录放行。
//
//   规则 3 命中后会再做一层**命令分析**，把风险类型标出来，供弹窗说清危害：
//     · admin  动设备管理员 / 策略（最敏感）
//     · system 目标是系统应用（设置、系统界面、包安装器…）——
//              冻结/卸载它们会让系统不可用（设置进不去、装不了应用）
//     · bulk   一条命令里批量动 3 个以上包（典型「一键卸载/冻结全部应用」）
//     · normal 普通卸载 / 冻结
//
//   注意：管理器自身（com.youlong.hd）不受这些规则约束 —— 它执行的就是本产品
//   的正常功能（日常模式批量卸载、病毒处置等），拦它等于把功能拦死。
// ==========================================================================

package roro.stellar.server.service.process

import roro.stellar.server.service.process.policy.HardBlockRules
import roro.stellar.server.util.Logger

/**
 * 命令拦截结论。
 */
sealed class InterceptDecision {
    /** 放行。 */
    object Allow : InterceptDecision()

    /** 直接拒绝，不执行；并向用户弹窗告知「某软件尝试某操作已被拦截」。 */
    data class Block(
        val reason: String,
        val riskLevel: String = "critical",
        val analysis: String = "极高风险：该命令已被安全策略直接阻止。"
    ) : InterceptDecision()

    /**
     * 危险操作，需要用户在管理器界面确认后才执行。
     *
     * @param reason 一句人话说明这次要做什么（如「卸载应用（com.x.y）」）
     * @param kind   风险类型，用于弹窗给出针对性说明：
     *              system=动系统应用、bulk=批量操作、admin=设备管理员/策略、
     *              normal=普通
     * @param riskLevel 危险程度：critical / high / medium / low
     * @param analysis  给用户看的危险程度说明
     */
    data class NeedConfirm(
        val reason: String,
        val kind: String = "normal",
        val riskLevel: String = "medium",
        val analysis: String = "中风险：请确认命令来源可信。",
        /** 2026-10-18：调用方是否在「指令拦截白名单」中。 */
        val whitelisted: Boolean = false
    ) : InterceptDecision()
}

/**
 * 命令安全拦截器 + 命令分析。
 */
object CommandInterceptor {

    private val LOGGER = Logger("CommandInterceptor")

    /** 本产品自身与配套应用；任何针对它们的危险命令都被拒绝。 */
    private val SELF_PACKAGES = listOf(
        "com.youlong.hd",    // 游龙安全护盾（本应用）
        "com.youlong.tool"   // 游龙工具（配套）
    )

    /** 游龙工具：执行命令时免确认（但 youlong / 加密命令仍然拦截）。 */
    private const val TOOL_PACKAGE = "com.youlong.tool"

    /**
     * 2026-10 第 19 轮：疑似「加密 / 编码后执行」的命令特征。
     *
     * 用户的原始诉求是「命令是加密过的就自动阻止」。由于 shell 命令没有统一的
     * 加密格式，这里按常见规避手法识别：
     *   · base64/base32/base16 解码后交给 sh；
     *   · openssl / gpg / ccrypt 解密；
     *   · xxd -r、printf/echo 的 \xNN 十六进制串；
     *   · eval + 命令替换（把解码结果再 eval）；
     *   · 超长 base64 块或 64 位以上十六进制块（密文/编码载荷）。
     * 宁可误杀，也不放过：命中即直接 Block。
     */
    private val ENCODED_PATTERNS = listOf(
        Regex("""(?i)\b(base64|base32|base16)\b[^\n|;&]*\s(-d|--decode)\b"""),
        Regex("""(?i)\b(base64|base32)\s+(-d|--decode)\b"""),
        Regex("""(?i)\bopenssl\s+(enc|aes|des|rsautl|pkeyutl)\b"""),
        Regex("""(?i)\bgpg\s+(--decrypt|-d)\b"""),
        Regex("""(?i)\bccrypt\s+-d\b"""),
        Regex("""(?i)\bxxd\s+-r\b"""),
        Regex("""\\x[0-9a-fA-F]{2}(\\x[0-9a-fA-F]{2}){4,}"""),
        Regex("""(?i)\beval\b[^\n]*(\$\(|`)""")
    )
    private val BASE64_BLOB = Regex("""(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{48,}={0,2}(?![A-Za-z0-9+/])""")
    private val HEX_BLOB = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{64,}(?![0-9a-f])""")

    /**
     * 2026-10 第 19 轮（用户需求）：给用户看的危险程度分析。
     * critical（极高）/ high（高）/ medium（中）/ low（低）。
     *
     * 下面这组是最危险的系统级操作：删系统数据、重启、格盘、改安全设置等。
     */
    private val CRITICAL_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-z]*[rf][a-z]*\s+)+(/|/system|/data|/sdcard)(\s|$)"""),
        Regex("""\b(reboot|shutdown|poweroff)\b"""),
        Regex("""\bmkfs(\.\w+)?\b"""),
        Regex("""\bdd\s+[^\n]*of=/dev/block"""),
        Regex("""\bwipe\s+(data|system|cache)\b"""),
        Regex("""\bmount\s+[^\n]*remount"""),
        Regex("""\bsettings\s+put\s+(secure|global)\b"""),
        Regex("""\bpm\s+(uninstall|disable-user|disable|suspend|hide|clear)\b[^\n]*(com\.android\.(settings|systemui|packageinstaller|permissioncontroller|shell|phone)|com\.google\.android\.packageinstaller)"""),
        Regex("""\bam\s+force-stop\s+com\.android\.(systemui|settings|phone)\b"""),
        Regex("""\bdpm\s+(set-device-owner|set-profile-owner|remove-active-admin)\b""")
    )

    /** 中风险：安装、授权、UI 自动化、内容提供者写入、网络、普通文件写操作等。 */
    private val MEDIUM_PATTERNS = listOf(
        Regex("""\b(pm\s+install|cmd\s+package\s+install)\b"""),
        Regex("""\b(pm\s+grant|pm\s+revoke|appops\s+set)\b"""),
        Regex("""\b(input|sendevent|monkey)\b"""),
        Regex("""\bcontent\s+(insert|update|delete)\b"""),
        Regex("""\b(curl|wget|nc|ncat|ping|ssh|scp)\b"""),
        Regex("""\bsvc\s+(wifi|data|bluetooth)\b"""),
        Regex("""\b(kill|pkill|killall)\b"""),
        Regex("""\b(cp|mv|rm|chmod|chown)\b"""),
        Regex("""\bam\s+(start|broadcast|instrument)\b""")
    )

    /**
     * 「批量停止所有应用」的特征。
     * 只匹配**全局性**停止动作；针对单个包名的 force-stop/kill 不在其列。
     */
    private val STOP_ALL_PATTERNS = listOf(
        Regex("""\bam\s+kill-all\b"""),
        Regex("""\bpm\s+disable-user\s+--user\s+\d+\s+-a\b"""),
        Regex("""\bpkill\b"""),
        Regex("""\bkillall\b"""),
        // am force-stop 后面跟通配符（部分 ROM 支持）
        Regex("""\bam\s+force-stop\s+["']?\*"""),
        // 对「所有包」的操作：pm uninstall/disable/force-stop 后直接跟 -a / --all
        Regex("""\bpm\s+(uninstall|disable-user|disable|force-stop|suspend)\b[^\n|;&]*\s(-a|--all)\b""")
    )

    /**
     * 「先枚举包、再批量操作」的组合特征：
     * 命令里同时出现「枚举第三方包」和「循环停止」两类元素时，视为批量停止。
     */
    private val ENUMERATE_PACKAGES = Regex("""\bpm\s+list\s+packages\b""")
    private val LOOP_STOP = Regex("""\b(am\s+force-stop|pm\s+disable-user|pm\s+disable|pm\s+suspend|pm\s+hide)\b""")

    /**
     * 系统关键组件（包名）。命中即视为高风险，弹窗里必须明确提示。
     *
     * 这里只列**最容易被恶意软件拿来搞破坏**的那些：设置、系统界面、包安装器、
     * 电话短信、权限控制器等。冻结/卸载它们会导致系统不可用 ——
     * 设置进不去、装不了应用、连病毒都卸不掉。
     */
    private val CRITICAL_SYSTEM_PACKAGES = listOf(
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

    /**
     * 系统应用的**通用**包名前缀（厂商框架），用于兜底识别非关键系统应用。
     */
    private val SYSTEM_PACKAGE_PATTERN = Regex(
        "^(android|com\\.android|com\\.google\\.android|com\\.qualcomm|" +
            "com\\.mediatek|com\\.miui|com\\.xiaomi|com\\.huawei|com\\.honor|" +
            "com\\.oppo|com\\.coloros|com\\.vivo|com\\.bbk|com\\.oneplus|" +
            "com\\.oplus|com\\.realme|com\\.meizu|com\\.smartisan|com\\.sony|" +
            "com\\.samsung|com\\.sec|com\\.lg|com\\.htc|com\\.motorola|" +
            "com\\.lenovo|com\\.asus|com\\.nokia|com\\.zte)\\."
    )

    /** 设备管理员 / 用户 / 设备策略类命令：非常敏感，必须确认。 */
    private val ADMIN_PATTERNS = listOf(
        Regex("""\bdpm\b"""),
        Regex("""\bpm\s+set-user-restriction\b"""),
        Regex("""\bpm\s+remove-user\b"""),
        Regex("""\bpm\s+create-user\b"""),
        Regex("""\bam\s+set-user-restriction\b"""),
        Regex("""\bcmd\s+device_policy\b"""),
        Regex("""\bcpm\s+(install|remove)\b""")
    )

    /** 卸载 / 冻结 / 清数据：需要用户确认。 */
    private val NEED_CONFIRM_PATTERNS = listOf(
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\bpm\s+disable-user\b"""),
        Regex("""\bpm\s+disable\b"""),
        Regex("""\bpm\s+suspend\b"""),
        Regex("""\bpm\s+hide\b"""),
        Regex("""\bpm\s+clear\b"""),
        Regex("""\bam\s+(force-stop|kill)\b"""),
        Regex("""\bpm\s+set-user-restriction\b"""),
        Regex("""\bcmd\s+package\s+(uninstall|suspend|disable)\b""")
    )

    /**
     * 对一条即将执行的命令做判定。
     *
     * @param cmd           原始命令数组（如 ["sh","-c","pm uninstall xxx"]）
     * @param callerPackage 调用方包名（由 uid 反查得到）
     * @param isManager     调用方是否就是管理器（本应用自己）
     */
    fun inspect(cmd: Array<String?>, callerPackage: String?, isManager: Boolean): InterceptDecision {
        // 管理器自己（本应用）不受拦截约束 —— 它执行的就是本产品的正常功能
        if (isManager) return InterceptDecision.Allow

        // 2026-10-24（《游龙 Shizuku 防御方案》）：裸 shell（sh / bash，无 -c、
        // 无脚本文件参数）真正的命令在 stdin 里，argv 只有 `sh`。这里先放行，
        // 交给 StdinGateway 拿到完整脚本后再审计/判定，避免「只看到 sh 就弹一次
        // 无意义的确认」。如果 argv 里带了脚本（sh -c '...'），继续走下面的规则。
        if (isBareShell(cmd)) {
            LOGGER.d("裸 shell 交给 stdin 网关判定: caller=%s", callerPackage ?: "未知应用")
            return InterceptDecision.Allow
        }

        val flat = flatten(cmd)
        if (flat.isBlank()) return InterceptDecision.Allow

        val who = callerPackage ?: "未知应用"

        // 2026-11-02（用户需求）：加入指令拦截白名单的应用，所有命令直接放行，
        // 不再经过 HardBlockRules / youlong / 加密命令等任何拦截。
        val whitelisted = CommandWhitelist.contains(callerPackage)
        if (whitelisted) {
            LOGGER.i("指令白名单应用全部放行: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.Allow
        }

        // 2026-10-31（用户需求）：非白名单应用，针对游龙自身/工具/系统设置/
        // SystemUI 的卸载、冻结、强停、kill 一律硬拦截。
        val hardBlock = HardBlockRules.detect(flat)
        if (hardBlock != null) {
            LOGGER.w("硬拦截危险命令: caller=%s, reason=%s, cmd=%s", who, hardBlock, flat)
            return InterceptDecision.Block(
                reason = hardBlock,
                riskLevel = "critical",
                analysis = "极高风险：$hardBlock，已直接阻止。"
            )
        }

        // 2026-10-18：指令拦截白名单只能由上面的提前返回处理；
        // 下面保留变量供非白名单逻辑使用。
        val encrypted = looksEncryptedOrEncoded(flat)

        // ---- 规则 1：youlong 关键字 / 加密（编码）命令 → 直接阻止 ----------
        // 2026-10 第 19 轮（用户需求）：
        //   · 命令里出现 youlong（不区分大小写）→ 自动阻止；
        //   · 命令疑似经过 Base64/Hex/OpenSSL/GPG 等加密或编码 → 自动阻止。
        // 2026-10-18：白名单应用只豁免「加密命令」的自动阻止，youlong 仍拦截。
        val lower = flat.lowercase()
        if (lower.contains("youlong")) {
            LOGGER.w("拦截含 youlong 的命令: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.Block(
                reason = "执行含 youlong 的命令",
                riskLevel = "critical",
                analysis = "极高风险：命令包含 youlong，可能针对本产品或配套应用，已直接阻止。"
            )
        }
        if (!whitelisted && encrypted) {
            LOGGER.w("拦截疑似加密/编码命令: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.Block(
                reason = "执行加密/编码后的命令",
                riskLevel = "critical",
                analysis = "极高风险：命令疑似经过加密或编码，可能用于隐藏真实操作，已直接阻止。"
            )
        }

        // ---- 规则 2：游龙工具免确认 ---------------------------------------
        // 2026-10 第 19 轮（用户需求）：com.youlong.tool 执行命令不需要弹窗询问。
        if (callerPackage == TOOL_PACKAGE) return InterceptDecision.Allow

        // ---- 规则 5：「始终允许」记忆（2026-10-23 新增，用户需求）----------
        // 用户在确认弹窗里对「某应用 + 某条指令」选了「始终允许」后，同一应用
        // 再执行**完全相同的这条指令**不再询问；换一条指令仍然询问。
        //
        // ⚠️ 本判断必须放在规则 1 之后：含 youlong 的命令已经在上方直接 Block，
        //    因此针对 com.youlong.hd / com.youlong.tool 的冻结、卸载类命令
        //    无论是否被记录过「始终允许」，都到不了这里，也永远放行不了。
        if (CommandAlwaysAllow.contains(callerPackage, flat)) {
            LOGGER.i("命中「始终允许」免确认: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.Allow
        }

        // ---- 规则 3：其它任何已授权应用，每条命令都要用户确认 ------------
        // 2026-10 第 19 轮（用户需求）：不再只对卸载/冻结等危险命令询问，
        // 而是「每次执行命令都询问」，并在弹窗里告知分析出的危险程度。
        // 2026-10-18：白名单应用也走这里，只把 whitelisted 标记传给弹窗，
        // 由弹窗改问「白名单应用 X 要执行指令是否放行」。
        val risk = assessRisk(flat)
        val reason = describeCommand(flat)
        val analysis = if (whitelisted && encrypted) {
            "注意：该指令疑似经过加密或编码。白名单已跳过自动阻止，但仍需你确认是否放行。"
        } else {
            risk.analysis
        }
        LOGGER.i(
            "命令需用户确认: caller=%s, whitelisted=%s, level=%s, kind=%s, cmd=%s",
            who, whitelisted, risk.level, risk.kind, flat
        )
        return InterceptDecision.NeedConfirm(
            reason,
            risk.kind,
            risk.level,
            analysis,
            whitelisted
        )
    }

    /** 命令危险程度分析结果。 */
    private data class RiskAssessment(
        val level: String,
        val kind: String,
        val analysis: String
    )

    /**
     * 2026-10 第 19 轮（用户需求）：分析命令危险程度，并把结论告诉用户。
     *
     * 分级：
     *   critical（极高）—— 删系统数据 / 重启 / 格盘 / 改安全设置 / 动系统关键组件；
     *   high（高）    —— 卸载、停用、冻结、清数据、设备管理员/策略、批量停止；
     *   medium（中）  —— 装应用、授权、UI 自动化、写内容提供者、联网、普通文件写；
     *   low（低）     —— 只读查询或普通命令。
     */
    private fun assessRisk(flat: String): RiskAssessment {
        if (CRITICAL_PATTERNS.any { it.containsMatchIn(flat) }) {
            return RiskAssessment(
                "critical",
                "system",
                "极高风险：该命令会删除系统数据、重启设备、格盘或修改系统安全设置，" +
                        "可能导致设备无法正常使用。"
            )
        }
        if (ADMIN_PATTERNS.any { it.containsMatchIn(flat) }) {
            return RiskAssessment(
                "high",
                "admin",
                "高风险：该命令会修改设备管理员、用户或设备策略，可能影响设备安全。"
            )
        }
        if (STOP_ALL_PATTERNS.any { it.containsMatchIn(flat) }
            || (ENUMERATE_PACKAGES.containsMatchIn(flat) && LOOP_STOP.containsMatchIn(flat))
        ) {
            return RiskAssessment(
                "high",
                "bulk",
                "高风险：该命令会停止所有应用，正在使用的应用会被强制退出，未保存的数据可能丢失。"
            )
        }
        if (NEED_CONFIRM_PATTERNS.any { it.containsMatchIn(flat) }) {
            if (mentionsSystemPackage(flat)) {
                return RiskAssessment(
                    "critical",
                    "system",
                    "极高风险：操作目标是系统关键组件（设置、系统界面等），" +
                            "可能导致系统设置进不去、应用无法安装。"
                )
            }
            if (affectedPackageCount(flat) >= 3) {
                return RiskAssessment(
                    "critical",
                    "bulk",
                    "极高风险：一条命令会同时操作多个应用，可能造成批量数据丢失。"
                )
            }
            return RiskAssessment(
                "high",
                "normal",
                "高风险：该命令会卸载、停用或清除应用数据，相关数据可能无法恢复。"
            )
        }
        if (MEDIUM_PATTERNS.any { it.containsMatchIn(flat) }) {
            return RiskAssessment(
                "medium",
                "normal",
                "中风险：该命令会安装应用、修改权限、模拟界面操作、写入数据或访问网络，" +
                        "请确认来源可信。"
            )
        }
        return RiskAssessment(
            "low",
            "normal",
            "低风险：这是只读查询或普通命令，一般不会修改系统状态。"
        )
    }

    /** 是否是疑似加密 / 编码后执行的命令。 */
    private fun looksEncryptedOrEncoded(flat: String): Boolean {
        if (ENCODED_PATTERNS.any { it.containsMatchIn(flat) }) return true
        if (BASE64_BLOB.containsMatchIn(flat)) return true
        if (HEX_BLOB.containsMatchIn(flat)) return true
        return false
    }

    /** 为「每条命令都确认」的弹窗生成一句人话说明。 */
    private fun describeCommand(flat: String): String {
        if (CRITICAL_PATTERNS.any { it.containsMatchIn(flat) }
            || STOP_ALL_PATTERNS.any { it.containsMatchIn(flat) }
            || (ENUMERATE_PACKAGES.containsMatchIn(flat) && LOOP_STOP.containsMatchIn(flat))
        ) {
            return describeDanger(flat)
        }
        if (NEED_CONFIRM_PATTERNS.any { it.containsMatchIn(flat) }
            || ADMIN_PATTERNS.any { it.containsMatchIn(flat) }
        ) {
            return describeDanger(flat)
        }
        return "执行命令"
    }

    // ------------------------------------------------------------------
    // 命令分析
    // ------------------------------------------------------------------

    /**
     * 分析这条命令属于哪种风险类型。
     * 优先级：admin > system > bulk > normal。
     */
    private fun analyzeRiskKind(flat: String): String {
        if (ADMIN_PATTERNS.any { it.containsMatchIn(flat) }) return "admin"
        if (STOP_ALL_PATTERNS.any { it.containsMatchIn(flat) }
            || (ENUMERATE_PACKAGES.containsMatchIn(flat) && LOOP_STOP.containsMatchIn(flat))
        ) {
            return "bulk"
        }
        // 只有确实命中卸载/冻结/清数据类命令时，才继续区分 system / bulk；
        // 普通命令（ls、getprop、pm list…）保持 normal，避免风险提示误报。
        if (NEED_CONFIRM_PATTERNS.any { it.containsMatchIn(flat) }) {
            if (mentionsSystemPackage(flat)) return "system"
            if (affectedPackageCount(flat) >= 3) return "bulk"
        }
        return "normal"
    }

    /** 命令里是否点到了系统应用（关键组件清单 + 厂商系统包名前缀） */
    private fun mentionsSystemPackage(flat: String): Boolean {
        if (CRITICAL_SYSTEM_PACKAGES.any { flat.contains(it) }) return true
        return extractPackages(flat).any { SYSTEM_PACKAGE_PATTERN.containsMatchIn(it) }
    }

    /**
     * 这条命令大概会影响多少个包。
     * 「一键卸载/冻结全部应用」这类命令通常会在一条命令里串起很多包名。
     */
    private fun affectedPackageCount(flat: String): Int = extractPackages(flat).size

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    /**
     * 把命令数组压平成一个字符串用于匹配。
     *
     * 调用方通常传 ["sh","-c","<真实脚本>"]，也可能直接传 ["pm","uninstall","x"]。
     * 统一用空格连接即可覆盖这几种形态；多余空格会被归一化，
     * 避免「pm   空格   uninstall」这种绕过。
     */
    /**
     * 是否是「裸 shell」：命令就是 sh/bash 本身，没有 -c，也没有脚本文件参数。
     *
     * 这种形态下 argv 里看不到任何真实命令（脚本走 stdin），所以命令行级规则
     * 无从判断；交给 stdin 网关拿完整脚本后再判定。
     */
    private fun isBareShell(cmd: Array<String?>): Boolean {
        val args = cmd.filterNotNull()
        if (args.isEmpty()) return false
        val bin = args[0].substringAfterLast('/')
        if (bin !in SHELL_NAMES) return false
        if (args.any { it == "-c" || it.startsWith("-c") }) return false
        // 第一个非选项参数若存在，则是脚本文件（例如 sh /data/local/tmp/x.sh），
        // 交给常规规则按命令行处理。
        val scriptFile = args.drop(1).firstOrNull { !it.startsWith("-") }
        return scriptFile == null
    }

    /** 交互式 shell 的可执行名。 */
    private val SHELL_NAMES = setOf("sh", "bash", "zsh", "mksh", "ash", "dash", "ksh", "busybox_sh")

    private fun flatten(cmd: Array<String?>): String {
        val joined = cmd.filterNotNull().joinToString(" ")
        return joined.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 是否是只读查询命令。
     *
     * 只读命令允许包含自家包名：例如授权应用做「已安装应用清单」时
     * 会执行 pm list packages，或 dumpsys package com.youlong.hd。
     * 这些不会改变任何状态，拦下来只会误伤正常功能。
     * 只要出现任何写操作特征，就不算只读。
     */
    private fun isReadOnly(flat: String): Boolean {
        val writeOps = Regex(
            """\b(uninstall|disable-user|disable|suspend|hide|clear|force-stop|kill|kill-all|pkill|killall|rm|mv|chmod|chown|setprop|svc|reboot|grant|revoke)\b"""
        )
        if (writeOps.containsMatchIn(flat)) return false
        val readOps = Regex("""\b(pm\s+list|pm\s+path|dumpsys|ps|pidof|getprop|ls|cat|which|id|echo|grep)\b""")
        return readOps.containsMatchIn(flat)
    }

    /**
     * 为用户确认弹窗生成一句人话说明：这次到底是什么危险动作、针对什么。
     */
    private fun describeDanger(flat: String): String {
        // 批量停止所有应用（不是针对单个包）
        if (STOP_ALL_PATTERNS.any { it.containsMatchIn(flat) }
            || (ENUMERATE_PACKAGES.containsMatchIn(flat) && LOOP_STOP.containsMatchIn(flat))
        ) {
            return "停止所有应用"
        }

        val action = when {
            Regex("""\b(reboot|shutdown|poweroff)\b""").containsMatchIn(flat) -> "重启或关机"
            Regex("""\brm\s+(-[a-z]*[rf][a-z]*\s+)+(/|/system|/data|/sdcard)(\s|$)""").containsMatchIn(flat) ->
                "删除系统数据"
            Regex("""\b(dd\s+[^\n]*of=/dev/block|mkfs(\.\w+)?)\b""").containsMatchIn(flat) ->
                "格式化或写入磁盘"
            Regex("""\bmount\s+[^\n]*remount\b""").containsMatchIn(flat) ->
                "重新挂载系统分区"
            Regex("""\bsettings\s+put\s+(secure|global)\b""").containsMatchIn(flat) ->
                "修改系统安全设置"
            Regex("""\bpm\s+uninstall\b""").containsMatchIn(flat) -> "卸载应用"
            Regex("""\bpm\s+suspend\b""").containsMatchIn(flat) -> "冻结应用"
            Regex("""\bpm\s+hide\b""").containsMatchIn(flat) -> "隐藏应用"
            Regex("""\bpm\s+clear\b""").containsMatchIn(flat) -> "清除应用数据"
            Regex("""\bpm\s+disable""").containsMatchIn(flat) -> "停用应用"
            Regex("""\bam\s+force-stop\b""").containsMatchIn(flat) -> "强制停止应用"
            Regex("""\bam\s+kill\b""").containsMatchIn(flat) -> "停止应用"
            Regex("""\bdpm\b""").containsMatchIn(flat) -> "修改设备管理员"
            Regex("""\bpm\s+set-user-restriction\b""").containsMatchIn(flat) -> "修改用户限制策略"
            else -> "执行危险操作"
        }
        val targets = extractPackages(flat)
        return if (targets.isEmpty()) action else "$action（${targets.joinToString("、")}）"
    }

    /**
     * 从命令里粗略提取被操作的包名，仅用于弹窗展示与风险统计（不参与"是否拦"的判定）。
     */
    private fun extractPackages(flat: String): List<String> {
        val pkgRegex = Regex("""\b([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+){2,})\b""")
        return pkgRegex.findAll(flat)
            .map { it.groupValues[1] }
            .filter {
                !it.startsWith("android.") &&
                    !it.startsWith("java.") &&
                    !it.startsWith("com.android.internal")
            }
            .distinct()
            .take(8)
            .toList()
    }
}
