package roro.stellar.manager.startup.worker

import android.util.Log
import kotlinx.coroutines.delay
import roro.stellar.Stellar
import roro.stellar.manager.AppConstants
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.adb.AdbWirelessHelper
import roro.stellar.manager.adb.AdbClient
import roro.stellar.manager.adb.AdbKey
import roro.stellar.manager.adb.PreferenceAdbKeyStore
import roro.stellar.manager.startup.command.Starter
import java.net.InetSocketAddress
import java.net.Socket

object AdbStarter {

    private const val TAG = AppConstants.TAG

    /**
     * 连接到 ADB 并执行 starter 命令。
     * 带指数退避重试，最多 [maxRetries] 次。
     *
     * 2026-10-31 修复：先用当前端口启动，成功后再切换自定义 TCP 端口。
     * 此前先 tcpip 切端口，部分 ROM 上新端口 adbd 未完成授权/重连，
     * 启动命令发不进去，最终表现为“等待服务启动超时”。
     */
    suspend fun startAdb(host: String, port: Int, maxRetries: Int = 5): Boolean {
        val key = AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), "youlong")
        val (shouldChange, newPort) = AdbWirelessHelper().shouldChangePort(port)

        // 1. 先使用当前已授权端口启动
        if (startAt(host, port, key, maxRetries)) {
            // 2. 服务起来后再切换自定义端口；失败也不影响已启动的服务
            if (shouldChange && newPort > 0) {
                switchPortBestEffort(host, port, newPort, key)
            }
            return true
        }

        // 3. 当前端口确实无法启动时，才尝试切换端口后再启动
        if (shouldChange && newPort in 1..65535) {
            switchPortBestEffort(host, port, newPort, key)
            if (startAt(host, newPort, key, maxRetries)) {
                return true
            }
        }
        return false
    }

    private suspend fun startAt(
        host: String,
        port: Int,
        key: AdbKey,
        maxRetries: Int
    ): Boolean {
        repeat(maxRetries) { attempt ->
            try {
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    client.shellCommand(Starter.detachedWirelessDebuggingCommand) { /* consume output */ }
                }
                return true
            } catch (_: java.io.EOFException) {
                // shell stream 关闭是正常的（服务进程已 fork）
                return true
            } catch (e: Exception) {
                Log.w(TAG, "ADB 启动尝试 ${attempt + 1}/$maxRetries 失败", e)
                if (attempt < maxRetries - 1) {
                    delay(1000L * (1 shl attempt)) // 1s, 2s, 4s, 8s
                }
            }
        }
        return false
    }

    private suspend fun switchPortBestEffort(host: String, oldPort: Int, newPort: Int, key: AdbKey) {
        try {
            AdbClient(host, oldPort, key).use { client ->
                client.connect()
                client.tcpip(newPort) { }
            }
            delay(800)
            Log.i(TAG, "服务启动后端口已切换: $oldPort -> $newPort")
        } catch (e: Exception) {
            Log.w(TAG, "服务启动后切换端口失败（不影响服务）: $oldPort -> $newPort", e)
        }
    }

    suspend fun prepareTcpip(host: String, wirelessPort: Int, tcpipPort: Int, maxRetries: Int = 5): Boolean {
        if (tcpipPort !in 1..65535) return false

        val key = AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), "youlong")
        repeat(maxRetries) { attempt ->
            try {
                AdbClient(host, wirelessPort, key).use { client ->
                    client.connect()
                    client.tcpip(tcpipPort) {}
                }
                return waitForTcpipPort(host, tcpipPort)
            } catch (_: java.io.EOFException) {
                if (waitForTcpipPort(host, tcpipPort)) return true
            } catch (e: Exception) {
                Log.w(TAG, "ADB TCP/IP prepare attempt ${attempt + 1}/$maxRetries failed", e)
            }

            if (attempt < maxRetries - 1) {
                delay(1000L * (1 shl attempt))
            }
        }
        return false
    }

    private suspend fun waitForTcpipPort(host: String, port: Int, timeoutMs: Long = 15_000): Boolean {
        val interval = 500L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 2000)
                }
                return true
            } catch (_: Exception) {
                delay(interval)
                elapsed += interval
            }
        }
        return false
    }

    /**
     * 轮询等待 Stellar Binder 可用。
     */
    suspend fun waitForBinder(timeoutMs: Long = 15_000): Boolean {
        val interval = 300L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (Stellar.pingBinder()) return true
            delay(interval)
            elapsed += interval
        }
        return false
    }
}
