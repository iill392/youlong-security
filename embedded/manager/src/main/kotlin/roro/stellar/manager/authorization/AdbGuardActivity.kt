// ==========================================================================
// 游龙安全护盾 —— 新增文件（2026-10-25）
// ADB 防护设置页：状态、防护开关、严格模式、维护模式、立即清理、恢复入口。
// ==========================================================================

package roro.stellar.manager.authorization

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import roro.stellar.manager.adb.AdbGuard
import roro.stellar.manager.ui.theme.StellarTheme
import java.util.concurrent.Executors

class AdbGuardActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StellarTheme {
                AdbGuardScreen(
                    loadStatus = {
                        runCatching { AdbGuard.status(applicationContext) }.getOrNull()
                    },
                    setEnabled = { enabled ->
                        AdbGuard.setEnabled(applicationContext, enabled)
                    },
                    startMaintenance = { minutes ->
                        AdbGuard.startMaintenance(applicationContext, minutes)
                    },
                    stopMaintenance = {
                        worker.execute {
                            AdbGuard.stopMaintenance(applicationContext)
                            AdbGuard.enforceNow(applicationContext)
                        }
                    },
                    enforceNow = {
                        AdbGuard.enforceNow(applicationContext)
                    },
                    openHome = {
                        packageManager.getLaunchIntentForPackage(packageName)?.let {
                            startActivity(it)
                        }
                    },
                    onBack = { finish() },
                    worker = worker
                )
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdbGuardScreen(
    loadStatus: () -> AdbGuard.Status?,
    setEnabled: (Boolean) -> Unit,
    startMaintenance: (Int) -> Unit,
    stopMaintenance: () -> Unit,
    enforceNow: () -> Unit,
    openHome: () -> Unit,
    onBack: () -> Unit,
    worker: java.util.concurrent.ExecutorService
) {
    var status by remember { mutableStateOf<AdbGuard.Status?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        worker.execute {
            val s = loadStatus()
            Handler(Looper.getMainLooper()).post { status = s }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ADB 防护") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "阻止第三方 App 通过无线调试/TCP ADB 拿到 shell；默认只关无线调试，TCP/USB ADB 不自动关闭（关闭会连带终止内置特权），内置特权断开时自动暂停。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow("内置特权", if (status?.stellarConnected == true) "已连接" else "未连接（防护暂停）")
                    StatusRow("USB 调试", if (status?.adbEnabled == true) "开启" else "关闭")
                    StatusRow("无线调试", if (status?.wifiEnabled == true) "开启（将被关闭）" else "关闭")
                    StatusRow(
                        "TCP ADB 端口",
                        when {
                            status == null -> "读取中"
                            status!!.tcpPort > 0 -> "${status!!.tcpPort}（不会自动关闭）"
                            else -> "未开启"
                        }
                    )
                    StatusRow("防护状态", if (status?.maintenance == true) "维护模式中" else if (status?.guardEnabled == true) "已开启" else "已关闭")
                    status?.let {
                        if (it.maintenance) {
                            val mins = ((it.maintenanceUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                            Text("维护剩余：约 $mins 分钟", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ADB 防护")
                    Text(
                        "默认开启；只关闭无线调试。TCP 8056 保留（已选择开放），不会自动/手动关 adbd，避免终止内置特权。ADB 授权弹窗只提示、不自动取消，避免误伤自己的电脑/产品 key。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status?.guardEnabled ?: true,
                    onCheckedChange = {
                        setEnabled(it)
                        refresh()
                    }
                )
            }

            HorizontalDivider()

            Button(
                onClick = {
                    if (status?.maintenance == true) {
                        stopMaintenance()
                        message = "已结束维护模式，ADB 防护已恢复"
                    } else {
                        startMaintenance(10)
                        message = "维护模式 10 分钟；期间不会关闭 ADB"
                    }
                    refresh()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (status?.maintenance == true) "结束维护模式" else "维护模式（10 分钟）")
            }

            Button(
                onClick = {
                    worker.execute {
                        enforceNow()
                        Handler(Looper.getMainLooper()).post {
                            message = "已尝试关闭无线调试"
                            refresh()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("立即关闭无线调试")
            }

            Button(
                onClick = openHome,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开首页重新激活内置特权")
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "内置特权断开后，本页防护会自动暂停；用户可在内置特权页打开无线调试/配对码重新激活，恢复后防护自动继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
