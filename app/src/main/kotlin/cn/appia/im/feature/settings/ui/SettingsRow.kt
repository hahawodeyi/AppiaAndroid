package cn.appia.im.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.theme.AppiaColors
import cn.appia.im.core.theme.Colors
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * RN components/SettingsRow/index.tsx 的 Compose 等价（M5-T9 设置页族公共行件）：
 * NavRow/ValueRow/DangerRow/ToggleRow/SegmentedRow + 卡片容器/节标题/分隔线。
 * RN styles 硬编码 iOS 分组灰（#f2f2f7 底 / #fff 卡 / #c00 危险）——对照 RN settings 样式
 * 保留字面色（新屏色板 token 承担可主题化部分：标题/正文走 [LocalAppiaColors]）。
 */

/** RN styles.card：16dp 边距白卡 10dp 圆角。 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalAppiaColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.backgroundColor),
    ) { content() }
}

/** RN styles.sectionTitle：13sp 半粗灰大写节标题（首节 marginTop 8 否则 24）。 */
@Composable
internal fun SectionTitle(text: String, first: Boolean = false, tag: String? = null) {
    val colors = LocalAppiaColors.current
    Text(
        text,
        color = Colors.textTertiary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp, end = 16.dp,
                top = if (first) 8.dp else 24.dp, bottom = 8.dp,
            )
            .let { m -> tag?.let { m.testTag(it) } ?: m },
    )
}

/** RN styles.sep：hairline 左缩进 16 分隔线。 */
@Composable
internal fun SettingsSep() {
    HorizontalDivider(
        Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = Colors.border,
    )
}

/**
 * 设置页族公共头部（RoomHeader 同款文本箭头形态——internal 可见性限包故复制最小形态）：
 * 左返回 + 左对齐标题（RN StackHeader 路由标题）。
 */
@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit, tag: String? = null) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "←",
            color = colors.headerTintColor,
            fontSize = 22.sp,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(40.dp)
                .wrapContentSize(Alignment.Center)
                .clickable(onClick = onBack)
                .testTag(tag ?: "qa-settings-header-back"),
        )
        Text(
            title,
            color = colors.headerTitleColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

private val RowTitleSize = 16.sp
private val RowValueSize = 15.sp

/** RN styles.row 公共修饰：min 48dp、横 16 纵 10 padding。 */
private fun Modifier.rowModifier(tag: String? = null): Modifier =
    fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .let { m -> tag?.let { m.testTag(it) } ?: m }

/** RN SettingsNavRow：左标题 + 右 chevron。 */
@Composable
internal fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    tag: String? = null,
    showChevron: Boolean = true,
) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier.rowModifier(tag).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = colors.titleText,
            fontSize = RowTitleSize,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        if (showChevron) Text("›", color = Colors.textDisabled, fontSize = 18.sp)
    }
}

/** RN SettingsValueRow：左标题 + 右多行值（最多 4 行，右对齐）。 */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
    valuePlaceholder: Boolean = false,
) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier.rowModifier(tag).let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            color = colors.titleText,
            fontSize = RowTitleSize,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            value,
            color = if (valuePlaceholder) Colors.textDisabled else Colors.textTertiary,
            fontSize = RowValueSize,
            textAlign = TextAlign.End,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
        )
    }
}

/** RN SettingsDangerRow：红色居中（loading 时转圈且禁点）。 */
@Composable
internal fun SettingsDangerRow(
    title: String,
    onClick: () -> Unit,
    tag: String? = null,
    loading: Boolean = false,
) {
    Row(
        Modifier.rowModifier(tag)
            .clickable(enabled = !loading, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Colors.danger,
            fontSize = RowTitleSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(end = 10.dp),
        )
        if (loading) CircularProgressIndicator(Modifier.width(18.dp).padding(0.dp), strokeWidth = 2.dp)
    }
}

/** RN SettingsToggleRow：左标题 + 右开关（RN ui/Switch 轨色对照 switchTrack）。 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    tag: String? = null,
) {
    val colors = LocalAppiaColors.current
    Row(Modifier.rowModifier(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = colors.titleText,
            fontSize = RowTitleSize,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        Switch(
            checked = value,
            onCheckedChange = onValueChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AppiaColors.switchTrack.`true`,
                uncheckedTrackColor = AppiaColors.switchTrack.`false`,
            ),
        )
    }
}

/**
 * RN SettingsSegmentedRow 两段横排形态（fill=false 时不撑满）：标题 + 选中态胶囊双段。
 * 语言三段（stacked 上下布局）走 [SettingsStackedSegmentedRow]——本页唯一 >2 选项行。
 */
@Composable
internal fun SettingsSegmentedRow(
    title: String,
    value: String,
    options: List<Pair<String, String>>, // value to label
    onChange: (String) -> Unit,
    tag: String? = null,
) {
    val colors = LocalAppiaColors.current
    Row(Modifier.rowModifier(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = colors.titleText,
            fontSize = RowTitleSize,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        SegmentPills(value, options, onChange)
    }
}

/** RN SettingsSegmentedRow stacked 形态：标题在上，分段均分宽度在下（>2 选项默认）。 */
@Composable
internal fun SettingsStackedSegmentedRow(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    tag: String? = null,
) {
    val colors = LocalAppiaColors.current
    Column(Modifier.rowModifier(tag)) {
        Text(title, color = colors.titleText, fontSize = RowTitleSize)
        Box(Modifier.padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (v, label) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (v == value) Colors.primary else colors.chatComponentBackground)
                            .clickable { onChange(v) }
                            .defaultMinSize(minHeight = 36.dp)
                            .testTag("qa-settings-segment-${v}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (v == value) Colors.textWhite else colors.bodyText,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentPills(value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    val colors = LocalAppiaColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (v == value) Colors.primary else colors.chatComponentBackground)
                    .clickable { onChange(v) }
                    .defaultMinSize(minHeight = 32.dp)
                    .padding(horizontal = 14.dp)
                    .testTag("qa-settings-segment-${v}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (v == value) Colors.textWhite else colors.bodyText,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
