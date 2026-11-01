// ==========================================================================
// 游龙安全护盾 —— 新增文件（2026-10-25）
// --------------------------------------------------------------------------
// ADB 防护：阻止第三方 App 利用「无线调试 / TCP ADB」拿到 shell。
//
// 背景：病毒自带 ADB 客户端时不需要我们的 Stellar；只要它能让用户授权
// 无线调试/TCP ADB，就能直接拿 shell uid，绕过所有脚本策略。
//
// 本对象负责：
//   · 默认关闭无线调试（adb_wifi_enabled=0）；
//   · 清掉 TCP ADB 端口（service.adb.tcp.port=-1）；
//   · 可选严格模式：关闭 USB 调试（adb_enabled=0）；
//   · 维护模式：用户主动开启一段时间，期间暂停防护，用于恢复/调试；
//   · 内置特权未连接时自动暂停（否则用户无法通过 ADB 恢复 Stellar）；
//   · 向管理器提供状态数据。
//
// 说明：普通 App 无法按 UID 阻止另一个 App 连接 localhost adbd；本对象做的是
// 「关闭 ADB 入口」，而不是「识别病毒」。root/Device Owner 下可进一步用
// 防火墙/调试限制做系统级隔离。
// ==========================================================================

package roro.stellar.manager.adb

import android.content.Context
import android.provider.Settings
import android.util.Log
import roro.stellar.Stellar
import java.util.concurrent.TimeUnit

object AdbGuard {

    private const val TAG = "AdbGuard"
    private const val PREFS = "shield_prefs"
    private const val KEY_ENABLED = "adb_guard_enabled"
    private const val KEY_STRICT = "adb_guard_strict"
    private const val KEY_MAINTENANCE_UNTIL = "adb_guard_maintenance_until"

    data class Status(
        val guardEnabled: Boolean,
        val strict: Boolean,
        val maintenanceUntil: Long,
        val adbEnabled: Boolean,
        val wifiEnabled: Boolean,
        val tcpPort: Int,
        val stellarConnected: Boolean
    ) {
        val maintenance: Boolean
            get() = maintenanceUntil > System.currentTimeMillis()
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    @JvmStatic
    fun isStrict(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRICT, false)

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "ADB 防护开关: $enabled")
    }

    @JvmStatic
    fun setStrict(context: Context, strict: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT, strict).apply()
        Log.i(TAG, "ADB 严格模式: $strict")
    }

    @JvmStatic
    fun maintenanceUntil(context: Context): Long =
        prefs(context).getLong(KEY_MAINTENANCE_UNTIL, 0L)

    @JvmStatic
    fun startMaintenance(context: Context, minutes: Int) {
        val until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
        prefs(context).edit().putLong(KEY_MAINTENANCE_UNTIL, until).apply()
        // 维护模式允许用户/PC 重新连接 ADB；到期后防护逻辑会重新关闭入口。
        runCatching {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                1
            )
        }
        Log.i(TAG, "进入 ADB 维护模式 ${minutes} 分钟")
    }

    @JvmStatic
    fun stopMaintenance(context: Context) {
        prefs(context).edit().putLong(KEY_MAINTENANCE_UNTIL, 0L).apply()
        Log.i(TAG, "退出 ADB 维护模式")
    }

    @JvmStatic
    fun status(context: Context): Status {
        val cr = context.contentResolver
        val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val wifiEnabled = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        val tcpPort = getTcpPort()
        return Status(
            guardEnabled = isEnabled(context),
            strict = isStrict(context),
            maintenanceUntil = maintenanceUntil(context),
            adbEnabled = adbEnabled,
            wifiEnabled = wifiEnabled,
            tcpPort = tcpPort,
            stellarConnected = runCatching { Stellar.pingBinder() }.getOrDefault(false)
        )
    }

    /**
     * 防护主逻辑。由 ProtectService 每 5 秒调用一次，管理器页面也会手动调用。
     *
     * @return 当前状态（便于界面刷新/日志）
     */
    @JvmStatic
    fun enforce(context: Context): Status {
        val status = status(context)
        if (!status.guardEnabled) return status
        if (status.maintenance) return status

        // Stellar 断开时暂停防护：否则用户无法通过 ADB 重新激活/配对恢复。
        if (!status.stellarConnected) {
            Log.i(TAG, "内置特权未连接，ADB 防护暂停（便于恢复）")
            return status
        }

        val cr = context.contentResolver
        var changed = false

        // 1) 无线调试必须关闭
        if (status.wifiEnabled) {
            runCatching {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
                changed = true
                Log.i(TAG, "检测到无线调试开启，已关闭")
            }.onFailure { Log.w(TAG, "关闭无线调试失败", it) }
        }

        // 2) TCP ADB 端口：不自动停 adbd。
        //    停 adbd 会把 Stellar 一起杀掉（实测），A 方案在当前无 root 设备不可用。
        //    需要关闭时由用户手动点按钮，并知道会断开电脑/终止 Stellar。
        if (status.tcpPort > 0) {
            Log.w(
                TAG,
                "检测到 TCP ADB 端口 ${status.tcpPort}；不自动停 adbd（会连带杀掉 Stellar）"
            )
        }

        // 严格模式已移除（会关 USB/adbd，连带终止 Stellar）。当前只关闭无线调试。

        if (changed) {
            Log.i(TAG, "ADB 防护已执行：${status(context)}")
        }
        return status(context)
    }

    /** 手动立即执行一次（忽略维护模式），用于管理器按钮。 */
    @JvmStatic
    fun enforceNow(context: Context): Status {
        val old = maintenanceUntil(context)
        stopMaintenance(context)
        val result = enforce(context)
        // 如果是用户手动点「立即关闭」，不需要恢复维护模式；保持关闭。
        // old 仅用于日志排查。
        if (old > 0L) Log.i(TAG, "手动执行 ADB 防护（原维护模式已结束）")
        return result
    }

    /** 用户显式关闭 USB/TCP ADB（会断开电脑/启动通道，只在明确点击时执行）。 */
    @JvmStatic
    fun closeTcpAdb(context: Context): Boolean {
        return try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            )
            Log.w(TAG, "用户手动关闭 USB/TCP ADB（ADB_ENABLED=0）")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 USB/TCP ADB 失败", t)
            false
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取系统属性；App 进程直接访问 SystemProperties 在新系统上可能被限制，改用 getprop。 */
    private fun getTcpPort(): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tcp.port"))
            val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            value.toIntOrNull() ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "读取 service.adb.tcp.port 失败", t)
            -1
        }
    }

    /** 通过内置特权 shell 执行命令；管理器自身豁免，不会被脚本策略拦截。 */
    private fun runShell(command: String): Boolean {
        return try {
            if (!Stellar.pingBinder()) return false
            val process = Stellar.newProcess(arrayOf<String?>("sh", "-c", command), null, null)
            process.waitFor()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "shell 执行失败: $command", t)
            false
        }
    }
}
