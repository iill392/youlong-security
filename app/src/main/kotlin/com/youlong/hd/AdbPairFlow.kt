package com.youlong.hd

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.lifecycle.Observer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import roro.stellar.manager.StellarSettings
import roro.stellar.manager.adb.AdbClient
import roro.stellar.manager.adb.AdbKey
import roro.stellar.manager.adb.AdbMdns
import roro.stellar.manager.adb.AdbPairingClient
import roro.stellar.manager.adb.PreferenceAdbKeyStore
import roro.stellar.manager.startup.worker.AdbStarter
import roro.stellar.manager.util.EnvironmentUtils


@SuppressLint("NewApi")
object AdbPairFlow {

    
    private const val KEY_NAME = "stellar"

    
    fun adbKey(): AdbKey =
        AdbKey(PreferenceAdbKeyStore(StellarSettings.getPreferences()), KEY_NAME)

    
    @SuppressLint("NewApi")
    fun pair(port: Int, code: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return AdbPairingClient("127.0.0.1", port, code, adbKey()).use { client ->
            client.start()
        }
    }

    
    fun grantSecureSettings(port: Int, pkg: String, onOutput: (String) -> Unit) {
        AdbClient("127.0.0.1", port, adbKey()).use { client ->
            client.connect()
            client.shellCommand("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS") { out ->
                onOutput(String(out).trim())
            }
        }
    }

    
    suspend fun discover(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
        onStatus: (String) -> Unit = {}
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<Int>()
        var mdns: AdbMdns? = null
        return try {
            val observer = Observer<Int> { port ->
                if (port > 0 && !deferred.isCompleted) deferred.complete(port)
            }
            mdns = AdbMdns(
                context,
                serviceType,
                observer,
                { if (!deferred.isCompleted) deferred.complete(-1) },
                { status -> if (status.isNotEmpty()) onStatus(status) },
                AdbMdns.MAX_REFRESH_COUNT,
                // 修复：onPermissionRequired 此前未接线，权限缺失时静默空转等满超时；
                // 现在立即结束并给出明确提示
                {
                    onStatus("缺少本地网络权限，无法搜索无线调试服务")
                    if (!deferred.isCompleted) deferred.complete(-1)
                }
            )
            mdns.start()
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            result?.takeIf { it > 0 }
        } catch (t: Throwable) {
            onStatus("搜索服务出错：" + (t.message ?: t.javaClass.simpleName))
            null
        } finally {
            try {
                mdns?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    
    suspend fun startService(port: Int): Boolean =
        AdbStarter.startAdb("127.0.0.1", port)

    
    suspend fun waitBinder(timeoutMs: Long = 20_000L): Boolean =
        AdbStarter.waitForBinder(timeoutMs)

    
    fun systemPort(): Int = try {
        EnvironmentUtils.getAdbTcpPort()
    } catch (t: Throwable) {
        -1
    }
}
