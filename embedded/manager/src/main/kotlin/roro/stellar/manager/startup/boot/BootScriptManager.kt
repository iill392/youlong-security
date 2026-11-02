// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar （MPL-2.0 + Apache-2.0）
//   manager/src/main/kotlin/roro/stellar/manager/startup/boot/BootScriptManager.kt
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：开机自启脚本里的 `pm path roro.stellar.manager` 改为
//          `pm path com.youlong.hd`。
//   原因：该脚本写入 /data/adb/service.d/stellar.sh，开机后需要按包名定位
//         APK 并直接执行其中的 libstellar.so。管理器被内置进「游龙安全护盾」，
//         应用包名是 com.youlong.hd；写成上游包名会定位不到 APK，开机自启失效。
//   除这一处字符串外，本文件其余内容与上游一致（package 声明保持上游的
//   roro.stellar.manager.*，因为管理器源码的包名整体未变）。
// ==========================================================================

package roro.stellar.manager.startup.boot

import com.topjohnwu.superuser.Shell

object BootScriptManager {
    const val SCRIPT_PATH = "/data/adb/service.d/stellar.sh"

    private const val INSTALL_SCRIPT_COMMAND =
        "printf '#!/system/bin/sh\\nwhile [ \"${'$'}(getprop sys.boot_completed)\" != \"1\" ]; do sleep 3; done\\nS=${'$'}(ls ${'$'}(pm path com.youlong.hd | cut -d: -f2 | sed \"s/base.apk//\")lib/*/libstellar.so | head -n 1); [ -n \"${'$'}S\" ] && \"${'$'}S\"' > /data/adb/service.d/stellar.sh && chmod 755 /data/adb/service.d/stellar.sh"

    private const val REMOVE_SCRIPT_COMMAND = "rm -f $SCRIPT_PATH"

    data class Result(
        val success: Boolean,
        val message: String
    )

    fun hasRootPermission(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (_: Exception) {
            false
        }
    }

    fun isScriptInstalled(): Boolean {
        if (!hasRootPermission()) return false
        return try {
            val result = Shell.cmd("[ -x \"$SCRIPT_PATH\" ]").exec()
            result.code == 0
        } catch (_: Exception) {
            false
        }
    }

    fun installScript(): Result {
        if (!hasRootPermission()) {
            return Result(success = false, message = "No root permission")
        }
        return exec(INSTALL_SCRIPT_COMMAND)
    }

    fun removeScript(): Result {
        if (!hasRootPermission()) {
            return Result(success = false, message = "No root permission")
        }
        return exec(REMOVE_SCRIPT_COMMAND)
    }

    private fun exec(command: String): Result {
        return try {
            val result = Shell.cmd(command).exec()
            if (result.code == 0) {
                Result(success = true, message = "")
            } else {
                val error = result.err.joinToString("\n").ifEmpty { "exit code: ${result.code}" }
                Result(success = false, message = error)
            }
        } catch (e: Exception) {
            Result(success = false, message = e.message ?: "Unknown error")
        }
    }
}
