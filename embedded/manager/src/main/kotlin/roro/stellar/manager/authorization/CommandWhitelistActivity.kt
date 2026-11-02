// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 指令拦截白名单（第 22 轮）+「始终允许」指令管理（2026-10-23）
// +「已授权能力（Shizuku）」管理（2026-10-24 新增）。
//
// 白名单只作用于「已授权应用执行指令」：
//   · 2026-10-25 起：原本会被自动拦截的指令（含 youlong / 加密 / 批量冻结卸载 /
//     安装应用等）不再硬拦截，改为弹窗询问；弹窗显示风险评估（评分 + 等级 + 原因）；
//   · 外面宿主「白名单管理」与这里双向同步，同一份列表。
//   · 弹窗里新增「始终允许」：对「同一个应用 + 同一条指令」记住选择，下次不再
//     询问；同一应用换一条指令仍然会询问。记录可在本页「已始终允许的指令」里
//     查看与撤销。
//
// 2026-10-24 新增「已授权能力（Shizuku）」：内置 Stellar 的 stdin 脚本代理网关
// 会在脚本触碰受保护能力时弹审批框，用户选「始终允许该能力」后服务端把
// 「包名 + 能力 id」记进 scope_grants.txt。本页展示这些授权并支持逐条撤销 /
// 全部清除，撤销后该应用再次请求该能力会重新弹窗询问。
//
// 与管理器的数据交换（都经内置特权 shell 完成，因为文件属 shell 用户）：
//   /data/local/tmp/stellar_guard/command_whitelist.txt      每行一个包名
//   /data/local/tmp/stellar_guard/command_always_allow.txt   每行「包名<TAB>指令」
//   /data/local/tmp/stellar_guard/scope_grants.txt            每行「包名<TAB>能力id」
// 服务端分别由 CommandWhitelist / CommandAlwaysAllow / 能力授权存储读取。
// ==========================================================================

package roro.stellar.manager.authorization

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import roro.stellar.Stellar
import roro.stellar.manager.ui.theme.StellarTheme
import java.util.concurrent.Executors

/** 管理器里展示的应用条目。 */
private data class AppEntry(val pkg: String, val label: String)

/** 一条「始终允许」记录：某个应用 + 某条指令。 */
private data class AlwaysEntry(val pkg: String, val label: String, val command: String)

/**
 * 2026-10-24 新增：一条「能力授权」记录：某个应用 + 某个受保护能力。
 * 对应 scope_grants.txt 里的一行「包名<TAB>能力id」。
 */
private data class ScopeEntry(val pkg: String, val label: String, val scope: String)

/**
 * 2026-10-24 新增：内置特权能力 id → 中文名。
 *
 * <p>能力 id 由服务端 stdin 脚本代理网关定义；服务端下发 scopeLabel 时以服务端
 * 的文案为准（见 CommandGuardActivity.PolicyGuardDialog），这里作为兜底，也用
 * 于本页「已授权能力」列表的展示。未知 id 原样显示，空白 id 显示「未知能力」。
 */
internal fun scopeDisplayName(scope: String): String = when (scope) {
    "shell_basic" -> "基础命令"
    "pkg_control" -> "包管理"
    "settings_secure" -> "系统设置"
    "proc_control" -> "进程控制"
    "persistence" -> "持久化"
    "interactive" -> "交互式终端"
    else -> scope.ifBlank { "未知能力" }
}

/**
 * 2026-10-18（用户需求）：指令拦截白名单。
 * 2026-10-23（用户需求）：新增「始终允许」指令的查看与撤销。
 */
class CommandWhitelistActivity : ComponentActivity() {

    companion object {
        private const val PREFS = "stellar_command_whitelist"
        private const val KEY = "pkgs"
        private const val SERVER_DIR = "/data/local/tmp/stellar_guard"
        /** 服务端受信通道允许写入的策略文件名。 */
        private const val SERVER_FILE_NAME = "command_whitelist.txt"
        /** 与服务端 CommandAlwaysAllow 约定的记录文件。 */
        private const val SERVER_ALWAYS_FILE = "$SERVER_DIR/command_always_allow.txt"

        /**
         * 2026-10-24 新增：与服务端能力授权存储约定的记录文件。
         * 每行「包名<TAB>能力id」，由服务端在用户选择「始终允许该能力」时写入；
         * 本页经内置特权 shell 读取与重写（写后 chmod 0600）。
         */
        private const val SERVER_SCOPE_FILE = "$SERVER_DIR/scope_grants.txt"
    }

    private val selected = mutableStateOf<Set<String>>(emptySet())
    private val apps = mutableStateOf<List<AppEntry>>(emptyList())
    private val loading = mutableStateOf(true)
    private val alwaysAllowed = mutableStateOf<List<AlwaysEntry>>(emptyList())
    // 2026-10-24 新增：已授权能力（Shizuku）记录
    private val scopeGrants = mutableStateOf<List<ScopeEntry>>(emptyList())
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "") ?: ""
        val outerRaw = getSharedPreferences("shield_prefs", MODE_PRIVATE)
            .getString("whitelist_pkgs", "") ?: ""
        selected.value = (raw.split(",") + outerRaw.split(","))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        setContent {
            StellarTheme {
                CommandWhitelistScreen(
                    selected = selected.value,
                    apps = apps.value,
                    loading = loading.value,
                    alwaysAllowed = alwaysAllowed.value,
                    // 2026-10-24 新增：已授权能力（Shizuku）记录
                    scopeGrants = scopeGrants.value,
                    onBack = { finish() },
                    onToggle = { pkg, checked ->
                        val next = selected.value.toMutableSet()
                        if (checked) next.add(pkg) else next.remove(pkg)
                        selected.value = next
                        persist(next)
                        worker.execute { syncToServer(next) }
                    },
                    onRevokeAlways = { entry -> revokeAlways(entry) },
                    onClearAlways = { clearAlways() },
                    // 2026-10-24 新增：撤销 / 清除能力授权
                    onRevokeScope = { entry -> revokeScope(entry) },
                    onClearScope = { clearScopeGrants() }
                )
            }
        }

        // 打开页面时同步一次，确保服务端文件存在（例如刚重装应用）。
        val initialSelected = selected.value
        worker.execute {
            loadApps(initialSelected)
            syncToServer(initialSelected)
            loadAlwaysAllowed()
            // 2026-10-24 新增：一并加载已授权能力（Shizuku）
            loadScopeGrants()
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun loadApps(selectedSnapshot: Set<String>) {
        val pm = packageManager
        val result = ArrayList<AppEntry>()
        try {
            for (ai in pm.getInstalledApplications(0)) {
                if (ai.packageName == packageName) continue
                if (ai.packageName == "com.youlong.tool") continue
                // 只列第三方应用
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                ) {
                    continue
                }
                // 只列有桌面入口的应用，避免系统插件/后台服务刷屏
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
                val label = try {
                    ai.loadLabel(pm).toString()
                } catch (_: Throwable) {
                    ai.packageName
                }
                result.add(AppEntry(ai.packageName, label))
            }
        } catch (_: Throwable) {
        }

        // 已加入但当前未安装/无入口的包也列出来，方便移除
        val installed = result.map { it.pkg }.toSet()
        for (pkg in selectedSnapshot) {
            if (pkg !in installed) {
                result.add(AppEntry(pkg, pkg))
            }
        }

        result.sortBy { it.label.lowercase() }
        runOnUiThread {
            apps.value = result
            loading.value = false
        }
    }

    private fun persist(pkgs: Set<String>) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY, pkgs.joinToString(","))
            .apply()
        // 2026-10-25：与外面「白名单管理」双向同步；两边看到同一份列表。
        getSharedPreferences("shield_prefs", MODE_PRIVATE)
            .edit()
            .putString("whitelist_pkgs", pkgs.joinToString(","))
            .apply()
    }

    private fun syncToServer(pkgs: Set<String>) {
        // 2026-11-02（安全问题修复）：不再用 shell 直接覆盖服务端白名单文件，
        // 也不再 chmod 0666。改走 Stellar 的受信 Binder 通道：
        // 服务端会校验调用方必须是管理器，并做包名 schema 校验 + 临时文件原子 rename。
        val content = pkgs.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString("\n")
        val ok = try {
            Stellar.writePolicyFile(SERVER_FILE_NAME, content)
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "写入指令白名单失败：内置特权未连接或策略校验未通过",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // 「始终允许」记录（2026-10-23 新增）
    // ------------------------------------------------------------------

    /** 读取服务端记录文件并刷新列表。必须在 worker 线程调用。 */
    private fun loadAlwaysAllowed() {
        val list = try {
            parseAlwaysEntries(readServerFile(SERVER_ALWAYS_FILE))
        } catch (t: Throwable) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "读取「始终允许」记录失败：${t.message ?: "内置特权未连接"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            emptyList()
        }
        runOnUiThread { alwaysAllowed.value = list }
    }

    /** 用内置特权 shell 读文件（文件属 shell，app 进程本身没有读权限）。 */
    private fun readServerFile(path: String): String {
        val process = Stellar.newProcess(
            arrayOf("sh", "-c", "cat ${shellQuote(path)} 2>/dev/null"),
            null,
            null
        )
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return text
    }

    /** 解析「包名<TAB>指令」行；格式不对的行直接忽略。 */
    private fun parseAlwaysEntries(text: String): List<AlwaysEntry> =
        text.lineSequence().mapNotNull { line ->
            val idx = line.indexOf('\t')
            if (idx <= 0) {
                null
            } else {
                val pkg = line.substring(0, idx).trim()
                val cmd = line.substring(idx + 1).trim()
                if (pkg.isEmpty() || cmd.isEmpty()) null
                else AlwaysEntry(pkg, resolveLabel(pkg), cmd)
            }
        }.toList()

    private fun resolveLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    /** 撤销一条记录（UI 线程调用）。 */
    private fun revokeAlways(entry: AlwaysEntry) {
        val next = alwaysAllowed.value.filterNot {
            it.pkg == entry.pkg && it.command == entry.command
        }
        if (next.size == alwaysAllowed.value.size) return
        alwaysAllowed.value = next
        val snapshot = next
        worker.execute { writeAlwaysEntries(snapshot) }
    }

    /** 清除全部记录（UI 线程调用）。 */
    private fun clearAlways() {
        alwaysAllowed.value = emptyList()
        worker.execute { writeAlwaysEntries(emptyList()) }
    }

    /**
     * 把剩余记录重写回服务端文件。
     * 用 printf '%s\t%s\n' + 逐条引用，避免命令里的引号/空格/反斜杠破坏格式。
     */
    private fun writeAlwaysEntries(list: List<AlwaysEntry>) {
        val body = if (list.isEmpty()) {
            ": > '$SERVER_ALWAYS_FILE'; "
        } else {
            buildString {
                append("printf '%s\\t%s\\n' ")
                list.forEach { entry ->
                    append(shellQuote(entry.pkg)).append(' ')
                    append(shellQuote(entry.command)).append(' ')
                }
                append("> '$SERVER_ALWAYS_FILE'; ")
            }
        }
        // 0600：与服务端写入时保持一致，只有 shell 能读写。
        val cmd = "mkdir -p '$SERVER_DIR'; $body chmod 0600 '$SERVER_ALWAYS_FILE'"
        try {
            Stellar.newProcess(arrayOf("sh", "-c", cmd), null, null).waitFor()
        } catch (t: Throwable) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "更新「始终允许」记录失败：${t.message ?: "内置特权未连接"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------
    // 「已授权能力（Shizuku）」记录（2026-10-24 新增）
    //
    // 数据文件 /data/local/tmp/stellar_guard/scope_grants.txt，每行
    // 「包名<TAB>能力id」。文件由服务端（shell 身份）在用户选「始终允许该能力」
    // 时写入；管理器这边只做展示 / 撤销 / 全部清除，所以读取与重写全部经内置
    // 特权 shell 完成，写完 chmod 0600（与服务端保持一致）。
    // ------------------------------------------------------------------

    /** 读取能力授权文件并刷新列表。必须在 worker 线程调用。 */
    private fun loadScopeGrants() {
        val list = try {
            parseScopeEntries(readServerFile(SERVER_SCOPE_FILE))
        } catch (t: Throwable) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "读取「已授权能力」记录失败：${t.message ?: "内置特权未连接"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            emptyList()
        }
        runOnUiThread { scopeGrants.value = list }
    }

    /** 解析「包名<TAB>能力id」行；格式不对的行直接忽略。 */
    private fun parseScopeEntries(text: String): List<ScopeEntry> =
        text.lineSequence().mapNotNull { line ->
            val idx = line.indexOf('\t')
            if (idx <= 0) {
                null
            } else {
                val pkg = line.substring(0, idx).trim()
                val scope = line.substring(idx + 1).trim()
                if (pkg.isEmpty() || scope.isEmpty()) null
                else ScopeEntry(pkg, resolveLabel(pkg), scope)
            }
        }.toList()

    /** 撤销一条能力授权（UI 线程调用）。撤销后该应用再请求该能力会重新询问。 */
    private fun revokeScope(entry: ScopeEntry) {
        val next = scopeGrants.value.filterNot {
            it.pkg == entry.pkg && it.scope == entry.scope
        }
        if (next.size == scopeGrants.value.size) return
        scopeGrants.value = next
        val snapshot = next
        worker.execute { writeScopeEntries(snapshot) }
    }

    /** 清除全部能力授权（UI 线程调用；调用前由界面做过二次确认）。 */
    private fun clearScopeGrants() {
        scopeGrants.value = emptyList()
        worker.execute { writeScopeEntries(emptyList()) }
    }

    /**
     * 把剩余能力授权重写回服务端文件。
     *
     * <p>格式与「始终允许」指令文件一致，用 printf '%s\t%s\n' + 逐条引用，
     * 避免包名/能力 id 里的特殊字符破坏格式；写后 chmod 0600。
     */
    private fun writeScopeEntries(list: List<ScopeEntry>) {
        val body = if (list.isEmpty()) {
            ": > '$SERVER_SCOPE_FILE'; "
        } else {
            buildString {
                append("printf '%s\\t%s\\n' ")
                list.forEach { entry ->
                    append(shellQuote(entry.pkg)).append(' ')
                    append(shellQuote(entry.scope)).append(' ')
                }
                append("> '$SERVER_SCOPE_FILE'; ")
            }
        }
        // 0600：与服务端写入时保持一致，只有 shell 能读写。
        val cmd = "mkdir -p '$SERVER_DIR'; $body chmod 0600 '$SERVER_SCOPE_FILE'"
        try {
            Stellar.newProcess(arrayOf("sh", "-c", cmd), null, null).waitFor()
        } catch (t: Throwable) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "更新「已授权能力」记录失败：${t.message ?: "内置特权未连接"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandWhitelistScreen(
    selected: Set<String>,
    apps: List<AppEntry>,
    loading: Boolean,
    alwaysAllowed: List<AlwaysEntry>,
    // 2026-10-24 新增：已授权能力（Shizuku）记录
    scopeGrants: List<ScopeEntry>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRevokeAlways: (AlwaysEntry) -> Unit,
    onClearAlways: () -> Unit,
    // 2026-10-24 新增：撤销 / 清除能力授权
    onRevokeScope: (ScopeEntry) -> Unit,
    onClearScope: () -> Unit
) {
    var showAlwaysDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // 2026-10-24 新增：能力授权的管理弹窗 / 全部清除二次确认
    var showScopeDialog by remember { mutableStateOf(false) }
    var confirmClearScope by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("指令拦截白名单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "白名单应用执行指令时只询问是否放行；弹窗里可选「始终允许」，" +
                            "同一应用再执行同一条指令就不再询问（换指令仍询问）。" +
                            "含 youlong 的指令仍会直接拦截。仅对已授权应用生效，开关实时同步给内置特权服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // 2026-10-23：已「始终允许」的指令入口（可查看 / 撤销 / 全部清除）
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已始终允许的指令",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = if (alwaysAllowed.isEmpty()) {
                                "暂无记录"
                            } else {
                                "共 ${alwaysAllowed.size} 条 · 仅对同一应用的同一条指令生效"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    TextButton(
                        onClick = { showAlwaysDialog = true },
                        enabled = alwaysAllowed.isNotEmpty()
                    ) { Text("管理") }
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = alwaysAllowed.isNotEmpty()
                    ) { Text("清除") }
                }
            }

            // 2026-10-24 新增：已授权能力（Shizuku）入口（可查看 / 撤销 / 全部清除）。
            // 数据来自内置特权 stdin 脚本代理网关审批弹窗的「始终允许该能力」。
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已授权能力（Shizuku）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = if (scopeGrants.isEmpty()) {
                                "暂无记录"
                            } else {
                                "共 ${scopeGrants.size} 项 · 这些应用请求对应能力不再询问"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    TextButton(
                        onClick = { showScopeDialog = true },
                        enabled = scopeGrants.isNotEmpty()
                    ) { Text("管理") }
                    TextButton(
                        onClick = { confirmClearScope = true },
                        enabled = scopeGrants.isNotEmpty()
                    ) { Text("清除") }
                }
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = "已选 ${selected.size} 个应用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(apps, key = { it.pkg }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.3.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = selected.contains(app.pkg),
                            onCheckedChange = { onToggle(app.pkg, it) }
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showAlwaysDialog) {
        AlwaysAllowDialog(
            entries = alwaysAllowed,
            onRevoke = onRevokeAlways,
            onDismiss = { showAlwaysDialog = false }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部「始终允许」记录？") },
            text = { Text("清除后，所有应用执行指令都会重新逐条询问。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearAlways()
                    }
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    // 2026-10-24 新增：已授权能力管理弹窗 + 全部清除的二次确认
    if (showScopeDialog) {
        ScopeGrantDialog(
            entries = scopeGrants,
            onRevoke = onRevokeScope,
            onDismiss = { showScopeDialog = false }
        )
    }

    if (confirmClearScope) {
        AlertDialog(
            onDismissRequest = { confirmClearScope = false },
            title = { Text("清除全部「已授权能力」记录？") },
            text = {
                Text("清除后，所有应用请求内置特权能力都会重新弹窗询问。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearScope = false
                        onClearScope()
                    }
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearScope = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 「始终允许」记录管理弹窗：逐条展示并提供撤销。
 */
@Composable
private fun AlwaysAllowDialog(
    entries: List<AlwaysEntry>,
    onRevoke: (AlwaysEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "已始终允许的指令（${entries.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "点「撤销」后，该应用再执行这条指令会重新询问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (entries.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(entries, key = { "${it.pkg}|${it.command}" }) { entry ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = entry.pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { onRevoke(entry) }) {
                                        Text("撤销")
                                    }
                                }
                                Text(
                                    text = entry.command,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("关闭") }
            }
        }
    }
}

/**
 * 2026-10-24 新增：「已授权能力（Shizuku）」记录管理弹窗。
 *
 * <p>逐条展示「应用名 + 包名 + 能力中文名」并提供撤销。撤销后服务端会重新对该
 * 应用请求该能力弹审批框。视觉与 [AlwaysAllowDialog] 保持一致。
 */
@Composable
private fun ScopeGrantDialog(
    entries: List<ScopeEntry>,
    onRevoke: (ScopeEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "已授权的内置特权能力（${entries.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "点「撤销」后，该应用再次请求这个能力会重新弹窗询问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (entries.isEmpty()) {
                    Text(
                        text = "暂无记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(entries, key = { "${it.pkg}|${it.scope}" }) { entry ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = entry.pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { onRevoke(entry) }) {
                                        Text("撤销")
                                    }
                                }
                                Text(
                                    text = "能力：${scopeDisplayName(entry.scope)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("关闭") }
            }
        }
    }
}

