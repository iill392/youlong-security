// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 内置特权管理器 → 底部导航「病毒库」独立页面（2026-10-24 用户需求）：
//   · 展示特征库版本、上次同步时间、上次检查时间、自动同步周期、同步状态；
//   · 「立即检查更新」按钮手动触发一次同步（强制，忽略 30 秒节流）；
//   · 每秒刷新状态，同步完成后自动更新。
//
// 数据来自宿主 App 注入的 VirusDbAccess（roro.stellar.manager.virusdb），
// manager 模块不反向依赖 app 模块。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.manager.ui.features.virusdb

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import roro.stellar.manager.R
import roro.stellar.manager.ui.components.BrandLargeTopAppBar
import roro.stellar.manager.ui.navigation.components.createTopAppBarScrollBehavior
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.virusdb.VirusDbAccess
import roro.stellar.manager.virusdb.VirusDbSyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirusDbScreen(topAppBarState: TopAppBarState) {
    val scrollBehavior = createTopAppBarScrollBehavior(topAppBarState)
    val context = LocalContext.current
    var status by remember { mutableStateOf<VirusDbSyncStatus?>(VirusDbAccess.status()) }

    // 每秒刷新状态（同步在后台线程进行，完成后自动反映到 UI）
    LaunchedEffect(Unit) {
        while (true) {
            status = VirusDbAccess.status()
            delay(1000L)
        }
    }

    val s = status
    val never = stringResource(R.string.virus_db_sync_never)
    val stateText = when {
        s == null -> stringResource(R.string.virus_db_state_unavailable)
        s.syncing -> stringResource(R.string.virus_db_state_syncing)
        s.error.isNotBlank() -> stringResource(R.string.virus_db_state_failed)
        s.ready -> stringResource(R.string.virus_db_state_ready)
        else -> stringResource(R.string.virus_db_state_not_ready)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            BrandLargeTopAppBar(
                title = stringResource(R.string.virus_db_page_title),
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------------- 顶部 hero：版本 + 状态 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShape.shapes.cardMedium24,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.virus_db_page_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = stringResource(R.string.virus_db_page_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = s?.version?.ifBlank { "-" } ?: "-",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (s != null && s.error.isNotBlank() && !s.syncing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    if (s != null && s.syncing) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ---------------- 详情 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShape.shapes.cardMedium24,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoRow(
                        label = stringResource(R.string.virus_db_sync_version),
                        value = s?.version?.ifBlank { "-" } ?: "-"
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_sync_last),
                        value = formatTime(s?.lastSyncAt ?: 0L, never)
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_last_attempt),
                        value = formatTime(s?.lastAttemptAt ?: 0L, never)
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_blacklist_count),
                        value = (s?.blacklistCount ?: 0).toString()
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_rule_count),
                        value = (s?.ruleCount ?: 0).toString()
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_snapshot),
                        value = if (s?.snapshotPushed == true) {
                            stringResource(R.string.virus_db_snapshot_pushed)
                        } else {
                            stringResource(R.string.virus_db_snapshot_not_pushed)
                        }
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_sync_auto),
                        value = stringResource(R.string.virus_db_sync_auto_value)
                    )
                    InfoRow(
                        label = stringResource(R.string.virus_db_status),
                        value = stateText
                    )
                    if (s != null && !s.syncing && s.error.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.virus_db_sync_error, s.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---------------- 手动检查更新 ----------------
            Button(
                onClick = {
                    val started = VirusDbAccess.requestSync(true)
                    Toast.makeText(
                        context,
                        if (started) R.string.virus_db_sync_started else R.string.virus_db_sync_busy,
                        Toast.LENGTH_SHORT
                    ).show()
                    status = VirusDbAccess.status()
                },
                enabled = s?.syncing != true,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.virus_db_check_now))
            }

            Text(
                text = stringResource(R.string.virus_db_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 时间戳 → 本地时间文本；0/异常返回「从未同步」。 */
private fun formatTime(timestamp: Long, never: String): String {
    if (timestamp <= 0L) return never
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    } catch (_: Throwable) {
        never
    }
}
