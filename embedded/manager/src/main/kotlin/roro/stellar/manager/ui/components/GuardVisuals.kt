// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：「ADB护盾版」管理器界面的品牌视觉组件。
//
//   把「盾牌标识 + 品牌渐变 + 状态卡」这类重复出现的视觉元素集中在这里，
//   供首页 / 应用列表 / 设置 / 终端四个页面复用，避免各页面各写一套。
//   上游 Stellar 没有这个文件，页面里用到它的地方都已按 MPL-2.0 第 3.4 条
//   在文件内标注改动。
// ==========================================================================

package roro.stellar.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import roro.stellar.manager.R
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.AppSpacing

/**
 * 品牌标识块：圆角方块 + 一个 Material 图标。
 *
 * ⚠️ 本工程改动：**不再绘制 Stellar 图标**。
 * 上游管理器到处使用 R.drawable.ic_stellar 作为品牌标识，本工程按需求
 * 「把 Stellar 的图标全部去除」，因此：
 *   · 本函数默认不画任何图标，只保留一块品牌底色（纯装饰，用于页头留白）；
 *   · 需要图标的场景改为传 Material 图标（如 Icons.Default.Security）。
 *
 * @param icon      要显示的 Material 图标；为 null 时只画底色块
 * @param size      外框尺寸
 * @param container 底色
 * @param tint      图标颜色
 */
@Composable
fun ShieldMark(
    icon: ImageVector? = null,
    size: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(AppShape.shapes.iconSmall)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * 0.56f)
            )
        }
    }
}

/**
 * 品牌顶部应用栏：标题（标题右侧可放操作按钮）。
 *
 * ⚠️ 本工程改动：标题前**不再显示 Stellar 图标**（原为 ShieldMark + 标题）。
 * 与上游 StandardLargeTopAppBar 的差别只剩配色与 trailing 插槽，
 * 滚动收起行为完全一致。
 *
 * @param titleContent 自定义标题内容；为 null 时显示 title
 * @param trailing 标题行右侧的紧凑操作区（如搜索/多选图标），可为 null
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandLargeTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable () -> Unit = {},
    titleContent: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    // 2026-11-01：标准 Material TopAppBar，原生设置风格；不再使用大标题占位。
    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        },
        actions = {
            if (trailing != null) trailing()
            actions()
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * 首页主状态卡：品牌渐变底 + 状态标识 + 运行状态。
 *
 * <p>取代上游首页那张纯色 ModernStatusCard 作为「门面」——
 * 上游版本在运行/未运行时整卡换成 primaryContainer / errorContainer 纯色，
 * 视觉上偏「系统默认」；这里改成从 primaryContainer 向 surface 过渡的渐变，
 * 并用一个圆形状态点表达运行与否，信息层级更清楚。
 *
 * ⚠️ 本工程改动：左侧标识由 Stellar 图标改为 Material 的 Security 图标
 * （见 ShieldMark 的说明）。
 */
@Composable
fun ShieldHeroCard(
    appName: String,
    isRunning: Boolean,
    statusText: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    val accent = if (isRunning) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    // 2026-11-01：原生卡片：纯色 surface + 细描边，不再使用渐变。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.shapes.cardLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShieldMark(
                    icon = Icons.Default.Security,
                    size = 52.dp,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    tint = accent
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (trailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailing()
                }
            }

            content()
        }
    }
}

/**
 * 分节头：一小段强调色竖条 + 标题 + 副标题。
 *
 * ⚠️ 本工程改动：左侧由 Stellar 图标块改为一条品牌色竖条
 * （见 ShieldMark 的说明：不再绘制 Stellar 图标）。
 */
@Composable
fun BrandSectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = AppSpacing.iconContainerSize)
                .clip(AppShape.shapes.tag)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(AppSpacing.iconTextSpacing))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 状态徽标（应用列表里用）：状态点 + 文案，圆角胶囊。
 *
 * @param positive true = 允许（品牌主色）；false 且 neutral = 询问（中性色）；
 *                 false 且 !neutral = 拒绝（告警色）
 */
@Composable
fun ShieldStatusTag(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
    neutral: Boolean = false
) {
    val container = when {
        positive -> MaterialTheme.colorScheme.primaryContainer
        neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = when {
        positive -> MaterialTheme.colorScheme.onPrimaryContainer
        neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val dotColor = when {
        positive -> MaterialTheme.colorScheme.primary
        neutral -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = modifier
            .clip(AppShape.shapes.tag)
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            fontWeight = FontWeight.Medium
        )
    }
}
