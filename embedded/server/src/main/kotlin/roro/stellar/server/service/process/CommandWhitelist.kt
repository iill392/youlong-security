// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 指令拦截白名单：管理器界面写入 /data/local/tmp/stellar_guard/command_whitelist.txt，
// 服务端在每条命令判定前读取。
//
// 2026-10-25（用户需求）：外面「白名单管理」与应用内「指令拦截白名单」打通：
//   · 加入了白名单的应用，原本会被 BLOCK 的指令不再自动拦截，改为弹窗询问；
//   · 含 youlong / 加密 / 批量冻结卸载 / 安装应用等高风险指令同样只询问，
//     弹窗里显示风险评估（0–100 分 + 等级 + 原因），由用户决定是否执行。
//
// 2026-10-23（用户需求）：确认弹窗新增「始终允许」——按「应用 + 指令」精确
// 记忆，记录由 CommandAlwaysAllow 管理（另一个文件），与本白名单相互独立。
// ==========================================================================

package roro.stellar.server.service.process

import java.io.File

object CommandWhitelist {

    private const val PATH = "/data/local/tmp/stellar_guard/command_whitelist.txt"

    /** 缓存 1 秒，避免每条命令都读文件；管理器改完后最迟 1 秒生效。 */
    private const val CACHE_MS = 1000L

    private val file = File(PATH)

    @Volatile
    private var cached: Set<String> = emptySet()

    @Volatile
    private var lastRead = 0L

    /**
     * 2026-11-02：旧版本曾把系统应用自动写进 command_whitelist.txt。
     * 新版本在服务端启动时自动清理这些系统包，同时保留用户自己加入的普通应用。
     */
    fun cleanupLegacySystemPackages() {
        try {
            if (!file.isFile) return
            val raw = file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (raw.isEmpty()) return

            val system = systemPackages()
            if (system.isEmpty()) return
            val kept = raw.filterNot { it in system }
            if (kept.size == raw.size) return

            val tmp = File(PATH + ".tmp")
            tmp.writeText(kept.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(kept.joinToString("\n"))
                tmp.delete()
            }
            cached = kept.toSet()
            lastRead = System.currentTimeMillis()
        } catch (_: Throwable) {
            // 清理失败不影响命令拦截；下次启动/管理器同步还会重试。
        }
    }

    /** 通过 shell 的 pm 列出系统应用；失败返回空集合，绝不据此清空白名单。 */
    private fun systemPackages(): Set<String> {
        return try {
            val process = ProcessBuilder("sh", "-c", "pm list packages -s")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun contains(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val now = System.currentTimeMillis()
        if (now - lastRead > CACHE_MS) {
            read()
            lastRead = now
        }
        return cached.contains(packageName)
    }

    private fun read() {
        cached = try {
            if (!file.isFile) {
                emptySet()
            } else {
                file.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }
}
