package roro.stellar.manager.adb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import roro.stellar.manager.AppConstants
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.StellarSettings.TCPIP_PORT
import roro.stellar.manager.StellarSettings.TCPIP_PORT_ENABLED
import roro.stellar.manager.diagnostics.FullDiagnostics
import roro.stellar.manager.startup.command.Starter
import java.net.Socket
import javax.net.ssl.SSLException

class AdbWirelessHelper {
    fun hasAdbPermission(host: String, port: Int): Boolean {
        FullDiagnostics.log("ADB-PERM", "hasAdbPermission host=$host port=$port")
        if (port !in 1..65535) return false

        val key = try {
            AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), "youlong")
        } catch (_: Throwable) {
            return false
        }

        return try {
            AdbClient(host, port, key).use { client ->
                client.connect()
            }
            FullDiagnostics.log("ADB-PERM", "hasAdbPermission OK host=$host port=$port")
            true
        } catch (_: SSLException) {
            FullDiagnostics.log("ADB-PERM", "hasAdbPermission SSLException host=$host port=$port")
            false
        } catch (_: java.net.ConnectException) {
            FullDiagnostics.log("ADB-PERM", "hasAdbPermission ConnectException host=$host port=$port")
            false
        } catch (_: Throwable) {
            FullDiagnostics.log("ADB-PERM", "hasAdbPermission Throwable host=$host port=$port")
            false
        }
    }

    private fun changeTcpipPortIfNeeded(
        host: String,
        port: Int,
        newPort: Int,
        key: AdbKey,
        commandOutput: StringBuilder,
        onOutput: (String) -> Unit
    ): Boolean {
        if (newPort !in 1..65535) {
            Log.w(AppConstants.TAG, "无效的TCP/IP端口: $newPort")
            FullDiagnostics.log("PORT", "invalid newPort=$newPort")
            return false
        }

        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            FullDiagnostics.log("PORT", "change tcpip attempt=$attempt host=$host from=$port to=$newPort")
            try {
                var success = false
                AdbClient(host, port, key).use { client ->
                    client.connect()

                    client.tcpip(newPort) {
                        val output = String(it)
                        commandOutput.append(output).append("\n")
                        onOutput(commandOutput.toString())
                        if (output.contains(Regex("restarting in TCP mode port: [0-9]*"))) {
                            success = true
                        }
                    }
                }
                if (success) {
                    Thread.sleep(500)
                    FullDiagnostics.log("PORT", "tcpip command success $port -> $newPort")
                    return true
                }
            } catch (e: Exception) {
                // 检查输出是否包含成功消息
                if (commandOutput.contains("restarting in TCP mode port:")) {
                    Log.i(AppConstants.TAG, "端口切换成功（连接已断开，这是正常的）")
                    return true
                }

                // 连接断开可能意味着 tcpip 命令已执行，检查新端口是否可用
                Log.d(AppConstants.TAG, "连接断开，等待检查新端口 $newPort 是否可用...")
                Thread.sleep(1000)

                if (waitForAdbPortAvailable(host, newPort, timeoutMs = 5000L)) {
                    Log.i(AppConstants.TAG, "端口切换成功: 新端口 $newPort 已可用")
                    return true
                }

                Log.w(AppConstants.TAG, "切换端口尝试 $attempt/$maxRetries 失败: ${e.message ?: "连接断开"}")
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * attempt)
                }
            }
        }
        return false
    }

    private fun waitForAdbPortAvailable(
        host: String,
        port: Int,
        timeoutMs: Long = 15000L
    ): Boolean {
        val intervalMs = 500L
        var elapsed = 0L
        FullDiagnostics.log("PORT", "waitForAdbPort host=$host port=$port timeout=$timeoutMs")
        while (elapsed < timeoutMs) {
            try {
                Socket(host, port).use { socket ->
                    socket.soTimeout = 2000
                    Log.i(AppConstants.TAG, "ADB 端口 ${host}:${port} 已可用")
                    FullDiagnostics.log("PORT", "ADB port available host=$host port=$port elapsed=$elapsed")
                    return true
                }
            } catch (e: Exception) {
                Log.v(AppConstants.TAG, "端口 $port 尚未就绪 (已等待 ${elapsed}ms): ${e.message}")
                if (elapsed % 3000L == 0L) {
                    FullDiagnostics.log("PORT", "port not ready host=$host port=$port elapsed=$elapsed error=${e.message}")
                }
                Thread.sleep(intervalMs)
                elapsed += intervalMs
            }
        }
        FullDiagnostics.log("PORT", "waitForAdbPort TIMEOUT host=$host port=$port")
        Log.w(AppConstants.TAG, "等待 ADB 端口 ${host}:${port} 超时")
        return false
    }

    fun startStellarViaAdb(
        host: String,
        port: Int,
        coroutineScope: CoroutineScope,
        onOutput: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        FullDiagnostics.log("START", "startStellarViaAdb host=$host port=$port")
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(AppConstants.TAG, "尝试通过ADB在${host}:${port}启动Stellar")

                val key = try {
                    AdbKey(
                        PreferenceAdbKeyStore(StellarSettings.getPreferences()), "youlong"
                    )
                } catch (e: Throwable) {
                    Log.e(AppConstants.TAG, "ADB密钥错误", e)
                    onError(AdbKeyException(e))
                    return@launch
                }

                if (!waitForAdbPortAvailable(host, port, timeoutMs = 15000L)) {
                    Log.w(AppConstants.TAG, "等待ADB端口${port}可用超时")
                    FullDiagnostics.log("START", "port not available host=$host port=$port")
                    onError(Exception("等待ADB端口${port}可用超时"))
                    return@launch
                }

                try {
                    AdbClient(host, port, key).use { client ->
                        client.connect()
                        Log.i(AppConstants.TAG, "ADB已连接到${host}:${port}。正在执行启动命令...")
                        FullDiagnostics.log("START", "ADB connected host=$host port=$port command=${Starter.detachedWirelessDebuggingCommand}")

                        client.shellCommand(Starter.detachedWirelessDebuggingCommand) { output ->
                            val outputString = String(output)
                            onOutput(outputString)
                            FullDiagnostics.log("START-OUT", outputString)
                            Log.d(AppConstants.TAG, "Stellar启动输出片段: $outputString")
                        }
                    }
                } catch (_: java.io.EOFException) {
                    FullDiagnostics.log("START", "ADB shell EOF (normal after fork)")
                    Log.i(AppConstants.TAG, "ADB shell 流已关闭（服务进程已 fork，这是正常的）")
                } catch (e: Throwable) {
                    FullDiagnostics.log("START-ERR", "ADB connection/command failed: ${e.javaClass.name}: ${e.message}")
                    Log.e(AppConstants.TAG, "ADB连接/命令执行失败", e)
                    onError(e)
                    return@launch
                }

                // 2026-10-31：读回 /data/local/tmp 下的 launcher 日志。
                // 这样即使 libstellar 在进入 Kotlin 前就退出，也能知道退出原因。
                try {
                    Thread.sleep(700)
                    AdbClient(host, port, key).use { client ->
                        client.connect()
                        client.shellCommand("cat ${Starter.LAUNCHER_LOG} 2>/dev/null") { output ->
                            val out = String(output)
                            FullDiagnostics.log("LAUNCHER", out)
                            onOutput(out)
                        }
                    }
                } catch (t: Throwable) {
                    FullDiagnostics.log("LAUNCHER-ERR", "read launcher log failed: ${t.javaClass.name}: ${t.message}")
                }

                FullDiagnostics.log("START", "ADB start command completed, waiting Binder")
                Log.i(AppConstants.TAG, "通过ADB启动Stellar成功完成")
                onSuccess()
            } catch (e: Throwable) {
                FullDiagnostics.log("START-ERR", "startStellarViaAdb exception: ${e.javaClass.name}: ${e.message}")
                Log.e(AppConstants.TAG, "startStellarViaAdb中出错", e)
                onError(e)
            }
        }
    }

    fun changeTcpipPortAfterStart(
        host: String,
        port: Int,
        newPort: Int,
        coroutineScope: CoroutineScope,
        onOutput: (String) -> Unit,
        onError: (Throwable) -> Unit,
        onSuccess: () -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (newPort !in 1..65535 || newPort == port) {
                    Log.i(AppConstants.TAG, "无需切换端口")
                    onSuccess()
                    return@launch
                }

                val key = try {
                    AdbKey(
                        PreferenceAdbKeyStore(StellarSettings.getPreferences()), "youlong"
                    )
                } catch (e: Throwable) {
                    Log.e(AppConstants.TAG, "ADB密钥错误", e)
                    onError(AdbKeyException(e))
                    return@launch
                }

                val commandOutput = StringBuilder()
                val success = changeTcpipPortIfNeeded(host, port, newPort, key, commandOutput, onOutput)

                if (success) {
                    Log.i(AppConstants.TAG, "端口切换成功: $port -> $newPort")
                    onSuccess()
                } else {
                    onError(Exception("无法切换到端口 $newPort，请检查端口是否被占用"))
                }
            } catch (e: Throwable) {
                Log.e(AppConstants.TAG, "changeTcpipPortAfterStart出错", e)
                onError(e)
            }
        }
    }

    fun shouldChangePort(currentPort: Int): Pair<Boolean, Int> {
        val portEnabled = StellarSettings.getPreferences().getBoolean(TCPIP_PORT_ENABLED, true)
        if (!portEnabled) return false to -1

        val portStr = StellarSettings.getPreferences().getString(TCPIP_PORT, "")
        if (portStr.isNullOrEmpty()) return false to -1

        return try {
            val newPort = portStr.toInt()
            (newPort != currentPort && newPort in 1..65535) to newPort
        } catch (_: NumberFormatException) {
            false to -1
        }
    }
}
