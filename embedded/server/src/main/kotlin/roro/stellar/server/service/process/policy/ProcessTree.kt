// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 进程树回收：判定阻断后，把这次会话产生的子进程一并清理（含 nohup 看门狗）。
//
// 实现说明：不按进程组 kill —— Runtime.exec 出来的子进程通常与本服务端同组，
// `kill -9 -<pgid>` 会把服务端自己杀掉。这里改为扫 /proc 找子孙 PID 后逐个 kill。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

import roro.stellar.server.util.Logger
import java.io.File

object ProcessTree {

    private val LOGGER = Logger("StellarProcessTree")

    /** 一次性回收：先杀子孙，再强杀根进程。 */
    fun killSession(process: Process?) {
        if (process == null) return
        val rootPid = pidOf(process)
        if (rootPid != null) {
            runCatching {
                descendantsOf(rootPid).asReversed().forEach { kill(it) }
            }.onFailure { LOGGER.w(it, "回收子进程失败") }
        }
        runCatching { process.destroyForcibly() }
    }

    private fun pidOf(process: Process): Int? {
        // Android 的 java.lang.Process 在编译 stub 里不一定有 pid()，这里用反射兜底：
        // 先找方法 pid()（Java 9+/Android O+ 的 ProcessImpl 有），再找字段 pid。
        runCatching {
            val method = Process::class.java.getMethod("pid")
            return (method.invoke(process) as? Int)
        }
        runCatching {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            return field.get(process) as? Int
        }
        return null
    }

    private fun descendantsOf(root: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val ppid = readPpid(pid) ?: return@forEach
            children.getOrPut(ppid) { ArrayList() }.add(pid)
        }

        val out = ArrayList<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 2000) {
            val cur = queue.removeFirst()
            children[cur]?.forEach {
                out.add(it)
                queue.add(it)
            }
        }
        return out
    }

    private fun readPpid(pid: Int): Int? {
        val text = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val end = text.lastIndexOf(')')
        if (end < 0 || end + 1 >= text.length) return null
        val rest = text.substring(end + 1).trim().split(Regex("""\s+"""))
        // 字段：pid (comm) state ppid ...
        return rest.getOrNull(1)?.toIntOrNull()
    }

    private fun kill(pid: Int) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
        }.onFailure { LOGGER.w(it, "kill -9 %d 失败", pid) }
    }
}
