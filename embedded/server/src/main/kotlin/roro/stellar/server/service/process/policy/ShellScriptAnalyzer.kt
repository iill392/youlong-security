// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// Shell 脚本静态分析（不执行脚本）。
//
// 目标：把「stdin 里拿到的整段脚本」拆成语句，做轻量变量展开，提取：
//   · 危险动词 + 目标包名（含 pkg/component 形态）
//   · 所需能力作用域（PKG_CONTROL / SETTINGS_SECURE / PROC_CONTROL / PERSISTENCE）
//   · 行为链信号与评分（组件级批量禁用 / 枚举后禁用 / 前台杀手 / 看门狗 /
//     无障碍写入 / 大脚本 / 编码动态执行）
//
// 注意：这不是完整的 shell 解析器，也不执行任何东西。对解析不出来的危险目标，
// 按「高风险」处理（交由审批），而不是猜测放行。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

/**
 * 脚本分析结果。
 */
data class ScriptFacts(
    /** 本次会话需要的能力。 */
    val scopes: MutableSet<Capability> = linkedSetOf(),
    /** 危险动词操作到的具体目标（可能是包名或 pkg/comp）。 */
    val dangerousTargets: MutableSet<String> = linkedSetOf(),
    /** 目标里带组件名的（pkg/comp），用于识别「组件级批量禁用」。 */
    val componentTargets: MutableSet<String> = linkedSetOf(),
    /** 命中受保护包的目标。 */
    val protectedTargets: MutableSet<String> = linkedSetOf(),
    /** 命中系统关键组件的目标。 */
    val systemTargets: MutableSet<String> = linkedSetOf(),
    /** 出现了危险动词，但目标因变量/替换无法静态解析。 */
    var unresolvedDangerousTarget: Boolean = false,
    /** 疑似编码/动态生成后执行（base64 -d、openssl、xxd -r、eval、长 blob）。 */
    var encodedOrDynamic: Boolean = false,
    /** 命中的行为链信号（中文，供弹窗/审计展示）。 */
    val signals: MutableSet<String> = linkedSetOf(),
    /** 行为链评分。 */
    var score: Int = 0,
    /** 给人看的补充说明。 */
    val reasons: MutableList<String> = mutableListOf(),
    /** 脚本超过分析上限（64KB），结论不完整。 */
    var truncated: Boolean = false,
    /** 脚本尝试修改内置特权的策略/快照目录（防御完整性）。 */
    var policyTamper: Boolean = false
) {
    /** 是否出现了危险动作。 */
    val dangerous: Boolean
        get() = dangerousTargets.isNotEmpty() || unresolvedDangerousTarget

    fun addScope(capability: Capability) {
        scopes.add(capability)
    }

    fun addSignal(label: String, weight: Int) {
        if (signals.add(label)) score += weight
    }
}

object ShellScriptAnalyzer {

    /** 只分析前 64KB，避免超大脚本拖慢 binder 线程。 */
    private const val MAX_ANALYZE = 64 * 1024

    private val PACKAGE_RE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+){2,}$""")
    private val COMPONENT_RE = Regex("""^([A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+){2,})/([A-Za-z0-9_.$]+)$""")
    private val ASSIGNMENT_RE = Regex("""^([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")
    private val BASE64_BLOB = Regex("""[A-Za-z0-9+/]{64,}={0,2}""")
    private val HEX_BLOB = Regex("""(?i)\b[0-9a-f]{64,}\b""")

    private val SHELL_WORDS = setOf("sh", "bash", "zsh", "mksh", "ash", "dash", "ksh")
    private val PREFIX_WORDS = setOf("command", "busybox", "toybox", "sudo", "env", "nice", "nohup")

    /** 出现在命令前面的 shell 语法关键字 / case 分支标签。 */
    private val SYNTAX_WORDS = setOf(
        "then", "do", "else", "elif", "fi", "done", "esac", "{", "}", "(", ")", "!", ";;"
    )

    /** 原文兜底扫描：命令被包在 sh -c / 变量里时，按文本特征兜底。 */
    private val DANGEROUS_RAW = Regex(
        """(?i)\b(pm\s+(disable-user|disable|suspend|hide|uninstall|clear)|""" +
                """am\s+(force-stop|kill)|cmd\s+package\s+(disable|suspend|uninstall))\b"""
    )

    /** 危险动词（会导致目标应用不可用）。 */
    private val DANGEROUS_PM = setOf(
        "disable-user", "disable", "suspend", "hide", "uninstall", "clear", "set-user-restriction"
    )

    /** 需要 PKG_CONTROL 的 pm 动词（含恢复类与安装）。 */
    private val PMCONTROL_PM =
        DANGEROUS_PM + setOf("enable", "unsuspend", "unhide", "grant", "revoke", "install")

    fun analyzeArgv(cmd: Array<String?>): ScriptFacts =
        analyze(cmd.filterNotNull().joinToString(" "))

    fun analyze(text: String): ScriptFacts {
        val facts = ScriptFacts()
        // 2026-10-24：超过上限就标记 truncated，由 PolicyEngine 转成审批，
        // 不再「静默只看前 64KB 然后放行剩余部分」。
        if (text.length > MAX_ANALYZE) facts.truncated = true
        val script = text.take(MAX_ANALYZE)
        if (script.isBlank()) return facts

        val statements = splitStatements(script)
        val vars = HashMap<String, String>()

        var dumpSeen = false
        var foregroundSeen = false
        var loopSeen = false
        var accessibilityWrite = false
        var forceStopSeen = false
        var disableSeen = false

        for (rawStatement in statements) {
            val statement = rawStatement.trim()
            if (statement.isEmpty()) continue

            // 2026-10-24：防御完整性 —— 任何对策略/快照目录的写入、删除、复制、
            // 改权限都按最高风险处理（该目录是病毒库快照与本策略开关的存放地）。
            // 同时检查变量展开后的文本，避免 `D=...; cp x $D/trusted_apps.txt` 绕过。
            val expandedStatement = expand(statement, vars).first
            if (touchesPolicyStore(expandedStatement) || touchesPolicyStore(statement)) {
                facts.policyTamper = true
                facts.addSignal("尝试修改内置特权策略/快照目录", 100)
            }

            // 循环/看门狗特征（用于判断「批量」与「前台杀手」）
            if (Regex("""\b(while|for)\b""").containsMatchIn(statement)) loopSeen = true
            if (statement.contains("mCurrentFocus") || statement.contains("topResumedActivity")) {
                foregroundSeen = true
            }
            if (Regex("""\b(dumpsys\s+package|cmd\s+package\s+dump|dumpsys\s+activity\s+packages)\b""")
                    .containsMatchIn(statement)
            ) {
                dumpSeen = true
            }

            // 编码 / 动态执行
            if (looksEncodedOrDynamic(statement)) {
                facts.encodedOrDynamic = true
                facts.signals.add("编码或动态执行")
            }

            // 持久化特征
            if (statement.contains("nohup") ||
                (Regex("""\bchmod\s+(-R\s+)?(\+x|0?7[0-7]{2})\b""").containsMatchIn(statement)) ||
                (Regex(""">\s*\S*/data/local/tmp/\S+""").containsMatchIn(statement)) ||
                (statement.contains("/data/local/tmp") && statement.contains(">"))
            ) {
                facts.addScope(Capability.PERSISTENCE)
                facts.addSignal("持久化/看门狗", 40)
            }

            var tokens = tokenize(statement)
            if (tokens.isEmpty()) continue

            // 前置变量赋值（A=1 B=2 cmd ...）
            var cursor = 0
            while (cursor < tokens.size) {
                val assign = ASSIGNMENT_RE.matchEntire(tokens[cursor])
                if (assign == null) break
                val name = assign.groupValues[1]
                val value = expand(assign.groupValues[2], vars).first
                vars[name] = value
                cursor++
            }
            if (cursor >= tokens.size) continue
            tokens = tokens.subList(cursor, tokens.size)

            // 跳过 shell 语法关键字（then/do/else/…）与命令前缀（sudo/busybox/timeout…）
            var cmdIndex = skipSyntax(tokens, 0)
            while (cmdIndex < tokens.size) {
                val word = baseName(tokens[cmdIndex])
                if (word == "timeout") {
                    cmdIndex++
                    if (cmdIndex < tokens.size && tokens[cmdIndex].all { it.isDigit() }) cmdIndex++
                    continue
                }
                if (word in PREFIX_WORDS) {
                    cmdIndex++
                    continue
                }
                break
            }
            if (cmdIndex >= tokens.size) continue
            val command = baseName(tokens[cmdIndex])
            val args = tokens.subList(cmdIndex + 1, tokens.size)

            when (command) {
                "pm" -> {
                    // 2026-10-24：pm install 不需要危险目标，但属于高影响操作
                    if (args.firstOrNull { !it.startsWith("-") } == "install") {
                        facts.addSignal("安装应用", 20)
                    }
                    if (analyzePm(args, facts, vars)) disableSeen = true
                }

                "cmd" -> {
                    if (args.firstOrNull() == "package") {
                        if (analyzePm(args.drop(1), facts, vars)) disableSeen = true
                    } else if (args.firstOrNull() == "appops") {
                        facts.addScope(Capability.PKG_CONTROL)
                    }
                }

                "am" -> {
                    when (args.firstOrNull()) {
                        "force-stop" -> {
                            forceStopSeen = true
                            facts.addScope(Capability.PROC_CONTROL)
                            val targets = targetsOf(args.drop(1), vars)
                            targets.forEach { addTarget(it, facts) }
                            if (targets.isEmpty() || targets.any { it.contains('$') }) {
                                facts.unresolvedDangerousTarget = true
                            }
                        }

                        "kill" -> {
                            forceStopSeen = true
                            facts.addScope(Capability.PROC_CONTROL)
                            val targets = targetsOf(args.drop(1), vars)
                            targets.forEach { addTarget(it, facts) }
                            if (targets.isEmpty() || targets.any { it.contains('$') }) {
                                facts.unresolvedDangerousTarget = true
                            }
                        }
                    }
                }

                "kill", "pkill", "killall" -> {
                    forceStopSeen = true
                    facts.addScope(Capability.PROC_CONTROL)
                }

                "settings" -> {
                    val sub = args.firstOrNull()
                    if (sub == "put" || sub == "delete") {
                        facts.addScope(Capability.SETTINGS_SECURE)
                        val joined = args.joinToString(" ")
                        if (joined.contains("accessibility")) {
                            accessibilityWrite = true
                            facts.addSignal("静默修改无障碍服务", 40)
                        }
                    }
                }

                // 2026-10-24：补上此前完全没覆盖、但 shell 权限下影响很大的命令。
                // 这些命令以前会 score=0 直接放行（裸 sh 不再经过 CommandInterceptor）。
                "rm", "rmdir", "unlink" -> {
                    if (Regex("""\brm\s+(-[a-z]*[rf][a-z]*\s+)+(/|/system|/data|/sdcard)(\s|$)""")
                            .containsMatchIn(statement.lowercase())
                    ) {
                        facts.addSignal("删除系统/数据目录", 100)
                    } else {
                        facts.addSignal("删除文件", 40)
                    }
                }

                "reboot", "shutdown", "poweroff" -> facts.addSignal("重启/关机设备", 60)
                "mkfs" -> facts.addSignal("格式化分区", 100)
                "wipe" -> facts.addSignal("擦除分区/数据", 100)
                "dd" -> if (statement.contains("of=/dev/block")) facts.addSignal("直接写块设备", 100)
                "mount" -> if (statement.contains("remount")) facts.addSignal("重新挂载系统分区", 100)

                "appops" -> {
                    facts.addScope(Capability.PKG_CONTROL)
                    facts.addSignal("修改应用权限（appops）", 20)
                }

                "input", "sendevent", "monkey" -> {
                    facts.addScope(Capability.SETTINGS_SECURE)
                    facts.addSignal("UI 自动化/模拟输入", 20)
                }

                "content" -> {
                    val sub = args.firstOrNull()
                    if (sub == "insert" || sub == "update" || sub == "delete") {
                        facts.addScope(Capability.SETTINGS_SECURE)
                        facts.addSignal("写入内容提供者", 20)
                    }
                }

                "svc" -> {
                    val sub = args.firstOrNull()
                    if (sub == "wifi" || sub == "data" || sub == "bluetooth") {
                        facts.addScope(Capability.SETTINGS_SECURE)
                        facts.addSignal("修改系统开关（svc）", 20)
                    }
                }

                "dpm" -> {
                    if (args.any {
                            it == "set-device-owner" || it == "set-profile-owner" ||
                                    it == "remove-active-admin"
                        }
                    ) {
                        facts.addScope(Capability.SETTINGS_SECURE)
                        facts.addSignal("设备管理员/策略操作（dpm）", 100)
                    }
                }

                "chmod" -> facts.addScope(Capability.PERSISTENCE)
                "eval" -> {
                    facts.encodedOrDynamic = true
                    facts.signals.add("编码或动态执行")
                }
            }

            // 脚本里直接出现 shell 解释器再跑文件（sh /path）
            if (command in SHELL_WORDS) {
                val fileArg = args.firstOrNull { !it.startsWith("-") }
                if (fileArg != null && fileArg.startsWith("/")) {
                    facts.reasons.add("脚本执行文件：$fileArg")
                }
            }

            // 原文兜底：命令被包进 sh -c / 变量赋值 / case 标签时，词法解析看不到
            // 真实命令，这里按「危险动词 + 受保护包名」的文本特征再扫一遍。
            if (DANGEROUS_RAW.containsMatchIn(statement)) {
                val lowerStatement = statement.lowercase()
                val matched = PolicyConfig.protectedPackages()
                    .filter { lowerStatement.contains(it) }
                    .toMutableSet()
                // 去掉被更长包名包含的短包名（如 com.android.se 与 com.android.settings）
                if (matched.size > 1) {
                    matched.removeAll { p -> matched.any { other -> other != p && other.startsWith(p) } }
                }
                matched.forEach { pkg ->
                    facts.dangerousTargets.add(pkg)
                    facts.protectedTargets.add(pkg)
                }
            }
        }

        // ---------------- 行为链评分 ----------------
        if (dumpSeen && disableSeen) {
            facts.addSignal("枚举组件后批量禁用", 40)
        }
        if (foregroundSeen && forceStopSeen) {
            facts.addSignal("前台应用杀手", 60)
        }
        if (facts.componentTargets.size >= 3) {
            facts.addSignal("组件级批量禁用（${facts.componentTargets.size} 个）", 50)
        } else if (loopSeen && disableSeen) {
            facts.addSignal("循环批量禁用", 50)
        }
        if (facts.unresolvedDangerousTarget) {
            facts.addSignal("危险目标无法静态解析", 20)
        }
        if (script.length > 2048 || statements.size > 8) {
            facts.addSignal("大脚本（一次写入）", 20)
        }

        // 编码/动态执行单独计分（放在最后，避免重复叠加）
        if (facts.encodedOrDynamic && !facts.signals.contains("编码或动态执行")) {
            facts.signals.add("编码或动态执行")
        }
        if (facts.encodedOrDynamic) facts.score += 40

        return facts
    }

    // ------------------------------------------------------------------
    // pm / cmd package
    // ------------------------------------------------------------------

    /** @return 是否出现危险/控制类动词 */
    private fun analyzePm(args: List<String>, facts: ScriptFacts, vars: Map<String, String>): Boolean {
        if (args.isEmpty()) return false
        var sub = args[0]
        // 跳过全局参数，如 pm --user 0 uninstall x（少见但兼容）
        var i = 1
        while (sub.startsWith("-") && i < args.size) {
            sub = args[i]
            i++
        }
        if (sub !in PMCONTROL_PM) return false

        facts.addScope(Capability.PKG_CONTROL)
        if (sub in DANGEROUS_PM) {
            val targets = targetsOf(args.drop(i), vars)
            targets.forEach { addTarget(it, facts) }
            val unresolved = targets.isEmpty() || targets.any { it.contains('$') }
            if (unresolved) {
                // 变量/命令替换导致目标解析不出来：按高风险处理（审批/拦截）
                facts.unresolvedDangerousTarget = true
            }
        }
        return sub in DANGEROUS_PM
    }

    /** 从参数里提取目标（包名 / pkg 组件 / 未展开的变量）。 */
    private fun targetsOf(args: List<String>, vars: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        var skipNext = false
        for (raw in args) {
            if (skipNext) {
                skipNext = false
                continue
            }
            if (raw == "--user" || raw == "-u") {
                skipNext = true
                continue
            }
            if (raw.startsWith("-")) continue
            if (raw.all { it.isDigit() }) continue
            if (!raw.contains('/') && !raw.contains('.') && !raw.contains('$')) continue
            out.add(expand(raw, vars).first)
        }
        return out
    }

    /** 记录一个目标：包/组件、受保护、系统关键。 */
    private fun addTarget(target: String, facts: ScriptFacts) {
        facts.dangerousTargets.add(target)
        val component = COMPONENT_RE.matchEntire(target)
        if (component != null) {
            facts.componentTargets.add(target)
        }
        val pkg = when {
            component != null -> component.groupValues[1]
            PACKAGE_RE.matches(target) -> target
            else -> null
        }
        if (pkg == null) return
        if (PolicyConfig.isProtected(pkg)) {
            facts.protectedTargets.add(pkg)
        } else if (PolicyConfig.isSystemCritical(pkg)) {
            facts.systemTargets.add(pkg)
        }
    }

    // ------------------------------------------------------------------
    // 轻量 shell 处理
    // ------------------------------------------------------------------

    /** 把脚本拆成语句（不处理引号内的分隔符）。 */
    private fun splitStatements(script: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var i = 0

        fun flush() {
            if (cur.isNotBlank()) out.add(cur.toString())
            cur.setLength(0)
        }

        while (i < script.length) {
            val c = script[i]
            if (quote != 0.toChar()) {
                cur.append(c)
                if (c == quote) quote = 0.toChar()
                i++
                continue
            }
            when (c) {
                '\'', '"' -> {
                    quote = c
                    cur.append(c)
                    i++
                }

                '#' -> {
                    // 注释：跳到行尾（不进入语句）
                    while (i < script.length && script[i] != '\n') i++
                }

                '\\' -> {
                    if (i + 1 < script.length && script[i + 1] == '\n') {
                        i += 2
                    } else {
                        cur.append(c)
                        i++
                    }
                }

                '\n', ';', '|' -> {
                    i++
                    if (i < script.length && (script[i] == '|' || script[i] == '&')) i++
                    flush()
                }

                '&' -> {
                    // 兼容重定向写法 `2>&1`、`>&2`
                    val prev = if (cur.isNotEmpty()) cur.last() else ' '
                    if (prev == '>' || prev.isDigit()) {
                        cur.append(c)
                        i++
                    } else {
                        i++
                        if (i < script.length && script[i] == '&') i++
                        flush()
                    }
                }

                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    /** 跳过语句开头的 shell 语法关键字与 case 分支标签（如 `*)`）。 */
    private fun skipSyntax(tokens: List<String>, start: Int): Int {
        var i = start
        while (i < tokens.size) {
            val t = tokens[i]
            if (t in SYNTAX_WORDS) {
                i++
                continue
            }
            // case 分支标签：`*)`、`android|com.x)` 之类，最后一个字符是 ')'
            if (t.length <= 64 && t.endsWith(')') && !t.contains('/')) {
                i++
                continue
            }
            break
        }
        return i
    }

    /** 简单分词：保留引号内容，去掉引号；不展开命令替换。 */
    private fun tokenize(statement: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        for (c in statement) {
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar() else cur.append(c)
                continue
            }
            when (c) {
                '\'', '"' -> quote = c
                ' ', '\t' -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.setLength(0)
                    }
                }

                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /**
     * 变量展开：$VAR / ${VAR}。
     * @return Pair(展开后的文本, 是否出现无法解析的变量)
     */
    private fun expand(token: String, vars: Map<String, String>): Pair<String, Boolean> {
        if (!token.contains('$')) return token to false
        val sb = StringBuilder()
        var unresolved = false
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c == '$' && i + 1 < token.length) {
                if (token[i + 1] == '{') {
                    val end = token.indexOf('}', i + 2)
                    if (end > 0) {
                        val name = token.substring(i + 2, end)
                        val value = vars[name]
                        if (value == null) {
                            unresolved = true
                            sb.append(token, i, end + 1)
                        } else {
                            sb.append(value)
                        }
                        i = end + 1
                        continue
                    }
                } else if (token[i + 1].isLetterOrDigit() || token[i + 1] == '_') {
                    var j = i + 1
                    while (j < token.length && (token[j].isLetterOrDigit() || token[j] == '_')) j++
                    val name = token.substring(i + 1, j)
                    val value = vars[name]
                    if (value == null) {
                        unresolved = true
                        sb.append(token, i, j)
                    } else {
                        sb.append(value)
                    }
                    i = j
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString() to unresolved
    }

    /** 内置特权的策略/快照目录（与 PolicyConfig 共用）。 */
    private val POLICY_DIR = PolicyConfig.DIR.absolutePath.lowercase()

    /**
     * 语句是否在尝试修改策略/快照目录。
     *
     * 只看「写/删/复制/改权限」类动作；单纯 `cat`/`grep` 读取不算。
     * 该目录保存病毒库快照与 policy_mode / trusted_apps / scope_grants，
     * 一旦被改写，整套防御可能被关闭，因此按最高风险处理。
     */
    private fun touchesPolicyStore(statement: String): Boolean {
        val lower = statement.lowercase()
        if (!lower.contains(POLICY_DIR)) return false
        if (lower.contains('>')) return true
        return Regex("""\b(rm|mv|cp|tee|ln|truncate|dd|install|chmod|chown|sed)\b""")
            .containsMatchIn(lower)
    }

    private fun baseName(word: String): String = word.substringAfterLast('/')

    private fun looksEncodedOrDynamic(statement: String): Boolean {
        if (Regex("""\bbase64\b[^\n|;&]*\s(-d|--decode)\b""").containsMatchIn(statement)) return true
        if (Regex("""\bopenssl\s+(enc|aes|des|rsautl|pkeyutl)\b""").containsMatchIn(statement)) return true
        if (Regex("""\bxxd\s+-r\b""").containsMatchIn(statement)) return true
        if (Regex("""\beval\b""").containsMatchIn(statement)) return true
        if (BASE64_BLOB.containsMatchIn(statement)) return true
        if (HEX_BLOB.containsMatchIn(statement)) return true
        return false
    }
}
