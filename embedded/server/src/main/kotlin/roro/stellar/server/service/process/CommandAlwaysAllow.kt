// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：「始终允许」指令记忆（2026-10-23 用户需求）。
//
// 背景：第 19 轮起，所有非管理器的已授权应用，每执行一条指令都要用户在管理器
// 弹窗里确认一次。次数多了非常麻烦。用户要求：给每条指令的确认弹窗增加一个
// 「始终允许」选项 —— 用户对「某个应用 + 某一条指令」选择始终允许后，下次该
// 应用执行**完全相同的这条指令**不再询问；换一条指令（哪怕只差一个参数）仍然
// 要询问。
//
// 存储：/data/local/tmp/stellar_guard/command_always_allow.txt
//   每行一条，格式为：<包名>\t<指令>
//   由服务端（shell 身份）写入，文件权限 0600（只有 shell 能读写，普通应用
//   连握手目录都列不了）。管理器通过内置特权 shell 读取 / 重写该文件，用于
//   在「指令拦截白名单」页展示与撤销。
//
// ⚠️ 安全边界（改动时务必保持）：
//   1. 本文件只决定「要不要弹确认框」，不放宽任何 Block 规则；
//   2. 针对 com.youlong.hd / com.youlong.tool 的冻结、卸载、清数据等命令，
//      在 CommandInterceptor 的**规则 1** 就被直接 Block（命令里必然出现
//      youlong），根本走不到这里 —— 任何「始终允许」记录都不可能让这类命令
//      放行；
//   3. 调用方包名解析不出来（null）时不记忆、不命中，永远逐条询问。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件，不受第 3.4 条
// 「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
// ==========================================================================

package roro.stellar.server.service.process

import roro.stellar.server.util.Logger
import java.io.File

object CommandAlwaysAllow {

    private val LOGGER = Logger("CommandAlwaysAllow")

    /** 与服务端 CommandGuard / 管理器共用的握手目录。 */
    private val DIR = File("/data/local/tmp/stellar_guard")

    /** 记录文件。由服务端创建/写入，管理器经 shell 读取与重写。 */
    private val FILE = File(DIR, "command_always_allow.txt")

    /**
     * 最多保留多少条记录。
     * 记录是「应用 + 指令」的精确匹配，正常使用不会很多；留一个上限防止
     * 某个应用用大量不同指令把文件撑爆（超出后从最早的一条开始淘汰）。
     */
    private const val MAX_ENTRIES = 300

    /** 单条命令的长度上限；超过就不记忆（见 add()）。 */
    private const val MAX_COMMAND_LEN = 4096

    private val lock = Any()

    /** 内存缓存；文件 mtime 变化时自动重读（管理器撤销后立即生效）。 */
    @Volatile
    private var entries: List<Entry>? = null

    @Volatile
    private var loadedMtime = -1L

    private data class Entry(val pkg: String, val command: String)

    /**
     * 这次调用是否已被用户「始终允许」过。
     *
     * @param callerPackage 调用方包名（由 uid 反查得到）；为 null 时一律 false
     * @param command       已经归一化过的命令文本（与 CommandInterceptor.flat 一致）
     */
    fun contains(callerPackage: String?, command: String?): Boolean {
        if (callerPackage.isNullOrEmpty() || command.isNullOrBlank()) return false
        val key = normalize(command)
        if (key.isEmpty()) return false
        return snapshot().any { it.pkg == callerPackage && it.command == key }
    }

    /**
     * 记住「这个应用 + 这条指令」：下次不再弹确认框。
     * 仅在用户明确点了「始终允许」时由 CommandGuard 调用。
     */
    fun add(callerPackage: String?, command: String?) {
        if (callerPackage.isNullOrEmpty() || command.isNullOrBlank()) return
        val pkg = callerPackage
        val key = normalize(command)
        if (key.isEmpty()) return
        // 超长命令不记忆（防止调用方用一条巨大脚本把记录文件撑爆）；
        // 本次仍然会放行，只是下次还会再问一遍。
        if (key.length > MAX_COMMAND_LEN) {
            LOGGER.w("命令过长（%d），不记录「始终允许」", key.length)
            return
        }

        synchronized(lock) {
            val list = ArrayList(snapshot())
            if (list.any { it.pkg == pkg && it.command == key }) return
            list.add(Entry(pkg, key))
            while (list.size > MAX_ENTRIES) {
                list.removeAt(0)
            }
            persist(list)
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 读缓存快照；文件被管理器改动（mtime 变化）时重读。
     *
     * 返回的列表只读使用；add() 内部会先复制一份再修改。
     */
    private fun snapshot(): List<Entry> {
        synchronized(lock) {
            val mtime = if (FILE.isFile) FILE.lastModified() else 0L
            val cached = entries
            if (cached != null && mtime == loadedMtime) return cached

            val list = ArrayList<Entry>()
            if (mtime > 0L) {
                try {
                    FILE.readLines().forEach { line ->
                        val idx = line.indexOf('\t')
                        if (idx > 0) {
                            val pkg = line.substring(0, idx).trim()
                            val cmd = line.substring(idx + 1).trim()
                            if (pkg.isNotEmpty() && cmd.isNotEmpty()) {
                                list.add(Entry(pkg, cmd))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    LOGGER.w(t, "读取「始终允许」记录失败")
                }
            }
            entries = list
            loadedMtime = mtime
            return list
        }
    }

    private fun persist(list: List<Entry>) {
        try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                LOGGER.w("无法创建握手目录 %s", DIR.absolutePath)
                return
            }
            val body = buildString {
                list.forEach { entry ->
                    append(entry.pkg).append('\t').append(entry.command).append('\n')
                }
            }
            // 先写临时文件再重命名，避免写到一半被读到半截内容。
            val tmp = File(DIR, "command_always_allow.txt.tmp")
            tmp.writeText(body)
            FILE.delete()
            if (!tmp.renameTo(FILE)) {
                LOGGER.w("重命名「始终允许」记录文件失败")
                return
            }
            // 0600：只有 shell（属主）能读写；管理器需要时经内置特权 shell 读取。
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("chmod", "0600", FILE.absolutePath))
                    .waitFor()
            }.onFailure { LOGGER.w(it, "设置「始终允许」记录文件权限失败") }

            entries = list
            loadedMtime = FILE.lastModified()
            LOGGER.i("「始终允许」记录已保存，共 %d 条", list.size)
        } catch (t: Throwable) {
            LOGGER.w(t, "写入「始终允许」记录失败")
        }
    }

    /**
     * 归一化命令文本，保证「弹窗里看到的那条」与「下次比对的那条」是同一串：
     * 连续空白压成一个空格、去掉制表符与换行、首尾去空白。
     *
     * 与 CommandInterceptor.flatten() 的归一化规则保持一致（重复归一化是幂等的）。
     */
    private fun normalize(command: String): String =
        command
            .replace('\t', ' ')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
}
