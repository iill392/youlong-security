// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：应用内「开源许可」查看界面（Apache-2.0 第 4(a) 条 /
//           MPL-2.0 第 3.1-3.3 条要求的「向接收者提供许可证副本」入口）。
// 合规相关：本文件不包含任何取自 Stellar 的代码。
// ==========================================================================

// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：应用内「开源许可」查看界面。
//
// 为什么必须有这个界面（开源协议合规）：
//   · Apache-2.0 第 4(a) 条：必须向 Work 的任何接收者提供许可证副本；
//   · Apache-2.0 第 4(b) 条：必须保留 NOTICE 中的署名与修改声明；
//   · MPL-2.0 第 3.1 / 3.2 条：MPL 覆盖的源码须以 Source Code Form 提供。
//   用户拿到的是 APK，他才是「接收者」，所以许可证文本必须真的在 APK 里、
//   而且必须在应用内可查看 —— 只把 LICENSE 放在代码仓库里不算合规。
//
// 文本来源：
//   app/src/main/res/raw/open_source_licenses.txt
//     = Apache License 2.0 全文
//     + Mozilla Public License 2.0 全文
//     + NOTICE（第三方署名、BoringSSL/OpenSSL 声明，以及本工程对
//               Stellar 每一个被修改文件的改动说明）
//   该资源在 :app 模块，:manager 库模块与 :app 同属一个 APK、共用同一张
//   资源表，因此这里可以直接用 R.raw.open_source_licenses 读取。
//   它靠 app/src/main/res/raw/keep.xml 的 tools:keep 躲过资源压缩
//   （openRawResource 这种运行时引用方式，资源压缩器识别不到）。
//
// 关于 MPL-2.0 第 3.2 条「Source Code Form」：
//   本产品把 Stellar 源码**直接内置**在工程里（embedded/ 目录与
//   app/src/main/cpp/stellar/），随产品一同提供，因此接收者可以取得
//   其 Source Code Form；本界面负责把这一点与许可证全文一起告知用户。
// ==========================================================================

package roro.stellar.manager.ui.features.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roro.stellar.manager.R
import roro.stellar.manager.compat.ClipboardUtils
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.AppSpacing
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.manager.util.Logger.Companion.LOGGER

class OpenSourceLicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = rememberText()
        val readError = text == null

        setContent {
            StellarTheme {
                LicensesScreen(
                    text = text ?: "",
                    readError = readError,
                    onBack = { finish() },
                    onCopy = {
                        if (!readError) {
                            ClipboardUtils.put(this, text!!)
                            Toast.makeText(
                                this,
                                getString(R.string.command_guard_ok),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }
    }

    /**
     * 读取 res/raw/open_source_licenses.txt。
     * 读不到时返回 null，界面会给出提示而不是空白页。
     */
    private fun rememberText(): String? {
        return try {
            resources.openRawResource(R.raw.open_source_licenses).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            LOGGER.e("读取开源许可证文本失败", t)
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesScreen(
    text: String,
    readError: Boolean,
    onBack: () -> Unit,
    onCopy: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.open_source_licenses),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy, enabled = !readError) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    start = AppSpacing.screenHorizontalPadding,
                    end = AppSpacing.screenHorizontalPadding,
                    bottom = AppSpacing.screenBottomPadding
                )
        ) {
            // 顶部说明：告诉用户这份文本是什么、为什么在这里
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AppShape.shapes.cardMedium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(AppShape.shapes.iconSmall)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.open_source_licenses),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.open_source_licenses_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.cardSpacing))

            // 正文：等宽字体 + 可选中复制
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = AppShape.shapes.cardMedium,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                if (readError || text.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.licenses_read_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = text,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
