// ==========================================================================
// 游龙安全护盾 —— 新增文件（2026-10-25）
// --------------------------------------------------------------------------
// 「只要执行 sh 文件，就审查文件内容」的统一入口。
//
// 之前的实现只处理 `sh /abs/x.sh` / `sh x.sh`：
//   · env sh /path、busybox sh /path、timeout 5 sh /path 会漏；
//   · sh -c 'sh /path'、sh -c '. /path' 会漏；
//   · 直接执行 /path/x.sh（shebang）会漏；
//   · 脚本里的 `. /path`、`source /path`、`$(cat /path)` 会漏。
//
// 本对象负责“找出本次执行真正会读到的脚本文件”，并做文件级检查：
//   · 超过 1MB → 无法完整审计 → BLOCK（fail-closed）
//   · 不是可读文本（NUL/大量控制字符）→ 按加密/二进制 → BLOCK
//   · 内容命中 DangerousScriptDetector（youlong/批量冻结卸载/删除/安装等）→ BLOCK
//
// 文件内容是“危险”还是“需要用户确认”，最终仍交给 PolicyEngine.evaluate 判定；
// 本对象只负责把文件找出来并做确定性的硬拦截。
// ==========================================================================

package roro.stellar.server.service.process.policy

import java.io.File

object ScriptFileInspector {

    /** 单个脚本文件审计上限；超过直接拒绝（无法保证内容被完整检查）。 */
    const val MAX_FILE_BYTES = 1024 * 1024

    private const val MAX_FILES = 8
    private const val MAX_REF_DEPTH = 2
    private const val MAX_REF_FILES = 8

    private val SHELLS = setOf("sh", "bash", "zsh", "mksh", "ash", "dash", "ksh")

    /** 这些只是“启动器”，真正执行脚本的是它们后面的 shell / 文件。 */
    private val WRAPPERS = setOf(
        "env", "busybox", "toybox", "timeout", "nice", "nohup", "setsid",
        "stdbuf", "command", "exec", "sudo", "doas"
    )

    /** 包装器里需要再吞一个参数的选项（如 env -u NAME）。 */
    private val WRAPPER_OPTIONS_WITH_ARG = setOf("-u", "--unset", "-C", "--chdir")

    private val VAR_ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
    private val TIMEOUT_DURATION = Regex("""^\d+(?:\.\d+)?[smhd]?$""")

    /** 系统目录里的可执行文件不是“用户脚本”，不读也不拦。 */
    private val SYSTEM_PREFIXES = listOf(
        "/system/", "/vendor/", "/apex/", "/product/", "/odm/", "/etc/",
        "/dev/", "/proc/", "/sys/"
    )

    sealed class Inspection {
        data class Text(val file: File, val content: String) : Inspection()
        data class Blocked(val file: File, val reason: String, val analysis: String) : Inspection()
    }

    private data class Ref(val raw: String, val dynamic: Boolean)

    /**
     * 找出本次执行会读到的脚本文件。
     *
     * 覆盖：
     *   sh /path/x.sh、sh x.sh
     *   env sh /path/x.sh、busybox sh /path/x.sh、toybox sh /path/x.sh
     *   timeout 5 sh /path/x.sh、nohup sh /path/x.sh、setsid sh /path/x.sh
     *   sh -c 'sh /path/x.sh'、sh -c '. /path/x.sh'、sh -c '$(cat /path/x.sh)'
     *   直接执行 /path/x.sh（.sh 后缀或 shebang）
     */
    fun filesForExecution(argv: Array<String?>, dir: String?): List<File> {
        val args = argv.filterNotNull()
        if (args.isEmpty()) return emptyList()

        val commandIndex = findCommandIndex(args)
        if (commandIndex < 0) return emptyList()

        val out = LinkedHashSet<File>()
        val command = args[commandIndex]
        val base = command.substringAfterLast('/')

        if (base in SHELLS) {
            parseShellArguments(args, commandIndex, dir, out)
        } else {
            // 直接执行：/data/local/tmp/x.sh（shebang 或 .sh 后缀）
            resolve(command, dir)?.let { file ->
                if (isScriptCandidate(file)) out.add(file)
            }
        }

        return out.take(MAX_FILES)
    }

    /**
     * 检查脚本（argv 或 stdin 文本）里引用的文件：
     *   · `. /path`、`source /path`、`sh /path`、`bash /path`、`exec /path`
     *   · `sh < /path`、`exec 0</path`、`cat /path | sh`
     *   · `$(cat /path)`、`$(< /path)`
     *
     * 引用路径是变量/通配符（无法确认实际文件）→ 直接返回原因（fail-closed）。
     * 引用文件加密/二进制/含危险命令 → 返回原因。
     * 未命中返回 null。
     */
    fun dangerousReferences(text: String?, dir: String?): String? {
        if (text.isNullOrBlank()) return null
        return checkReferences(text, dir, 0, HashSet())
    }

    /**
     * 检查 `sh -c '...'` 里的内层命令引用到的文件。
     * 例如 `sh -c 'sh /path/x.sh'`、`sh -c '. /path/x.sh'`、`sh -c 'sh $X'`。
     */
    fun dangerousShellCommandReferences(argv: Array<String?>, dir: String?): String? {
        val args = argv.filterNotNull()
        val commandIndex = findCommandIndex(args)
        if (commandIndex < 0) return null
        if (args[commandIndex].substringAfterLast('/') !in SHELLS) return null

        var i = commandIndex + 1
        while (i < args.size) {
            val arg = args[i]
            val hasCommand = arg == "-c" ||
                    (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains('c'))
            if (hasCommand) {
                val command = args.getOrNull(i + 1) ?: return null
                return dangerousReferences(command, dir)
            }
            if (arg == "-o" || arg == "+o" || arg == "--rcfile" || arg == "--init-file") {
                i += 2
                continue
            }
            i++
        }
        return null
    }

    /** 读取并检查单个脚本文件。 */
    fun inspect(file: File): Inspection {
        if (!file.isFile) {
            return Inspection.Blocked(
                file,
                "脚本文件不存在或不是普通文件，无法审计：${file.path}",
                "脚本文件无法读取，不能确认其中是否包含 youlong、批量操作或加密内容，已直接阻止。"
            )
        }
        if (file.length() > MAX_FILE_BYTES) {
            return Inspection.Blocked(
                file,
                "脚本文件过大（>${MAX_FILE_BYTES / 1024}KB），无法完整审计：${file.path}",
                "脚本文件超过审计上限，无法确认其中是否包含 youlong、批量操作或加密内容，已直接阻止。"
            )
        }

        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return Inspection.Blocked(
                file,
                "脚本文件无法读取，无法审计：${file.path}",
                "脚本文件读取失败，不能确认其中是否包含危险命令或加密内容，已直接阻止。"
            )

        if (looksBinary(bytes)) {
            return Inspection.Blocked(
                file,
                "脚本文件疑似加密/二进制，无法审计：${file.path}",
                "脚本文件不是可读文本（可能被加密、编码或打包），无法检查真实内容，已直接阻止。"
            )
        }

        val content = String(bytes, Charsets.UTF_8)
        val hard = HardBlockRules.detect(content)
        if (hard != null) {
            return Inspection.Blocked(
                file,
                "脚本文件包含硬危险命令：$hard（${file.path}）",
                "脚本文件内容命中硬拦截规则（$hard），已直接阻止。"
            )
        }
        val dangerous = DangerousScriptDetector.detect(content)
        if (dangerous != null) {
            return Inspection.Blocked(
                file,
                "脚本文件包含危险命令：$dangerous（${file.path}）",
                "脚本文件内容命中危险操作特征（$dangerous），已直接阻止。"
            )
        }

        return Inspection.Text(file, content)
    }

    /** 与旧 ProcessManager.looksBinary 保持一致的加密/二进制判定。 */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var nul = 0
        var control = 0
        for (b in bytes) {
            val v = b.toInt() and 0xff
            if (v == 0) {
                nul++
            } else if (v < 9 || (v in 14..31)) {
                control++
            }
        }
        return nul > 0 || control * 100 / bytes.size >= 10
    }

    // ------------------------------------------------------------------
    // argv 解析
    // ------------------------------------------------------------------

    /** 跳过 env/busybox/timeout 等包装器与 VAR=value，返回真正命令的下标。 */
    private fun findCommandIndex(args: List<String>): Int {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val base = arg.substringAfterLast('/')

            if (base !in SHELLS && VAR_ASSIGN.matches(arg)) {
                i++
                continue
            }

            if (base in WRAPPERS) {
                i++
                if (base == "timeout" && i < args.size && TIMEOUT_DURATION.matches(args[i])) {
                    i++
                }
                while (i < args.size && args[i].startsWith("-")) {
                    val opt = args[i]
                    i++
                    if (opt in WRAPPER_OPTIONS_WITH_ARG && i < args.size) i++
                }
                if (base == "timeout" && i < args.size && TIMEOUT_DURATION.matches(args[i])) {
                    i++
                }
                continue
            }

            return i
        }
        return -1
    }

    private fun parseShellArguments(
        args: List<String>,
        shellIndex: Int,
        dir: String?,
        out: MutableSet<File>
    ) {
        var i = shellIndex + 1
        while (i < args.size) {
            val arg = args[i]

            if (arg == "-") {
                i++
                continue
            }

            if (arg.startsWith("-")) {
                val hasCommand = arg == "-c" ||
                        (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains('c'))
                if (hasCommand) {
                    val command = args.getOrNull(i + 1) ?: return
                    for (ref in extractReferences(command)) {
                        if (ref.dynamic) continue
                        resolve(ref.raw, dir)?.let { out.add(it) }
                    }
                    return
                }
                if (arg == "-o" || arg == "+o" || arg == "--rcfile" || arg == "--init-file") {
                    i += 2
                    continue
                }
                i++
                continue
            }

            if (VAR_ASSIGN.matches(arg)) {
                i++
                continue
            }

            resolve(arg, dir)?.let { out.add(it) }
            return
        }
    }

    private fun isScriptCandidate(file: File): Boolean {
        if (file.name.endsWith(".sh") || file.name.endsWith(".bash")) return true
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 && head[0] == 35.toByte() && head[1] == 33.toByte()
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 引用解析
    // ------------------------------------------------------------------

    private fun checkReferences(
        text: String,
        dir: String?,
        depth: Int,
        seen: MutableSet<String>
    ): String? {
        if (depth > MAX_REF_DEPTH) return null

        // stdin 里的 sh -c '...' 内层命令也要看（外层引号会干扰引用正则）。
        for (inlineCommand in extractInlineCommands(text)) {
            checkReferences(inlineCommand, dir, depth + 1, seen)?.let { return it }
        }

        var checked = 0
        for (ref in extractReferences(text)) {
            if (ref.dynamic) {
                return "脚本通过变量/通配符引用文件，无法确认实际内容：${ref.raw.take(120)}"
            }
            val file = resolve(ref.raw, dir) ?: continue
            val key = file.absolutePath
            if (!seen.add(key)) continue
            if (checked++ >= MAX_REF_FILES) break

            when (val inspection = inspect(file)) {
                is Inspection.Blocked -> return inspection.reason
                is Inspection.Text -> {
                    checkReferences(inspection.content, file.parent, depth + 1, seen)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractReferences(text: String): List<Ref> {
        val refs = LinkedHashSet<Ref>()

        for (regex in REFERENCE_PATTERNS) {
            for (match in regex.findAll(text)) {
                val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isEmpty()) continue
                refs.add(Ref(raw, isDynamic(raw)))
            }
        }
        return refs.toList()
    }

    private fun isDynamic(raw: String): Boolean =
        raw.contains('$') || raw.contains('*') || raw.contains('?') ||
                raw.contains('`') || raw.contains('(') || raw.contains(')')

    private fun extractInlineCommands(text: String): List<String> {
        val commands = ArrayList<String>()
        for (match in INLINE_COMMAND_PATTERN.findAll(text)) {
            val command = match.groupValues.getOrNull(2)?.trim().orEmpty()
            if (command.isNotEmpty()) commands.add(command)
        }
        return commands
    }

    private val INLINE_COMMAND_PATTERN = Regex(
        """(?s)\b(?:sh|bash|zsh|mksh|ash|dash|ksh)\s+-[a-zA-Z]*c[a-zA-Z]*\s+(['"])(.*?)\1"""
    )

    private fun resolve(raw: String, dir: String?): File? {
        var path = raw.trim().trim('\'', '"')
        if (path.isEmpty() || path == "-" || path == "/dev/null") return null
        path = path.substringBefore('>').substringBefore('<').substringBefore(';')
        if (path.isEmpty() || path.startsWith("-")) return null
        if (path.contains('$') || path.contains('*') || path.contains('?') ||
            path.contains('`') || path.contains('(') || path.contains(')')
        ) {
            return null
        }

        val file = when {
            path.startsWith("/") -> File(path)
            dir != null -> File(dir, path)
            else -> return null
        }
        if (!file.isFile) return null

        val absolute = file.absolutePath
        if (SYSTEM_PREFIXES.any { absolute.startsWith(it) }) return null
        return file
    }

    private val REFERENCE_PATTERNS = listOf(
        // . /path | source /path | sh /path | bash /path | exec /path
        Regex(
            """(?:^|[;&|()\n]|${'$'}\(\s*|`)\s*(?:\.|source|sh|bash|zsh|mksh|ash|dash|ksh|exec)\s+([^\s;&|()`]+)"""
        ),
        // sh < /path
        Regex("""\b(?:sh|bash|zsh|mksh|ash|dash|ksh)\s+<\s*([^\s;&|()`]+)"""),
        // exec 0</path
        Regex("""\bexec\s+\d*<\s*([^\s;&|()`]+)"""),
        // cat /path | sh
        Regex("""\bcat\s+([^\s;&|()`]+)\s*\|\s*(?:sh|bash|zsh|mksh|ash|dash|ksh)\b"""),
        // $(cat /path)
        Regex("""${'$'}\(\s*cat\s+([^\s;&|()`]+)"""),
        // $(< /path)
        Regex("""${'$'}\(\s*<\s*([^\s;&|()`]+)"""),
        // 直接写出的 ./x.sh / /path/x.sh
        Regex("""(?:^|[;&|()\n])\s*([^\s;&|()`]+\.sh)\b""")
    )
}
