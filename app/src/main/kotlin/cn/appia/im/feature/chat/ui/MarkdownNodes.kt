package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.messaging.Code
import cn.appia.im.core.messaging.CodeLine
import cn.appia.im.core.messaging.Emoji
import cn.appia.im.core.messaging.Heading
import cn.appia.im.core.messaging.InlineKaTeX
import cn.appia.im.core.messaging.KaTeX
import cn.appia.im.core.messaging.ListItem
import cn.appia.im.core.messaging.MdBlock
import cn.appia.im.core.messaging.MdInline
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.OrderedList
import cn.appia.im.core.messaging.Paragraph
import cn.appia.im.core.messaging.PlainText
import cn.appia.im.core.messaging.Quote
import cn.appia.im.core.messaging.TableCell
import cn.appia.im.core.messaging.TableRow
import cn.appia.im.core.messaging.UnorderedList
import cn.appia.im.core.messaging.isEmptyQuoteMarkerLink
import cn.appia.im.core.messaging.LineBreak
import cn.appia.im.core.messaging.plainInlineText
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * 块级 md 节点渲染（对照 appiaMobile src/components/markdown 逐节点 + styles.ts 硬编码样式）。
 * 样式对照：inlineCode #f0f0f0、codeBlock #f5f5f5、quote 边框 3px #e0e0e0、link #1d74f5、
 * hr #C9CDD4（暗色不生效是 RN 已知缺口——跟随）。
 *
 * 行内渲染（[inlineText] 纯文本扁平化）为 T4 骨架；T5 换 AnnotatedString 行内管线
 * （LINK 可点/BOLD/ITALIC/MENTION 色/EMOJI 表），届时替换各节点调用点。
 */

/** RN markdown/styles.ts 硬编码色。 */
internal object MarkdownStyle {
    val inlineCodeBg = Color(0xFFF0F0F0)
    val codeBlockBg = Color(0xFFF5F5F5)
    val quoteBorder = Color(0xFFE0E0E0)
    val link = Color(0xFF1D74F5)
    val hr = Color(0xFFC9CDD4)
    val aiCodeHeaderBg = Color(0xFFECECEC)
    val aiCodeLang = Color(0x8C000000) // rgba(0,0,0,0.55)
    val tableBorder = Color(0x26000000) // #00000026
    val tableHeaderBg = Color(0x0F2F343D) // #2F343D0F
}

private const val MONOSPACE_SIZE = 13
private const val MONOSPACE_LINE_HEIGHT = 18

// ── Paragraph（Paragraph.tsx:16-49：空 permalink 剔除 + INLINE_KATEX row 布局降级）──

@Composable
internal fun MarkdownParagraph(
    value: List<MdInline>,
    modifier: Modifier = Modifier,
    onKatexClick: ((String) -> Unit)? = null,
) {
    var forceTrim = false
    if (value.isNotEmpty() && isEmptyQuoteMarkerLink(value[0])) {
        // 对齐 RN Paragraph.tsx:24-30：仅当恰好 size==2 且第二元素空白才整段不渲染（3+ 元素照常渲染）
        if (value.size == 1) return
        val second = value.getOrNull(1)
        if (value.size == 2 && second is PlainText && second.value.trim().isEmpty()) return
        forceTrim = true
    }
    // RN Inline.tsx:36-44 forceTrim：剔除首位 permalink 标记，次位 PLAIN_TEXT 去 trimStart
    val rendered: List<String> = value.mapIndexed { index, inline ->
        when {
            forceTrim && index == 0 -> ""
            forceTrim && index == 1 && inline is PlainText -> inline.value.trimStart()
            else -> inlineText(inline)
        }
    }
    val colors = LocalAppiaColors.current
    if (value.any { it is InlineKaTeX }) {
        FlowRow(modifier, verticalArrangement = Arrangement.Center) {
            value.forEachIndexed { index, inline ->
                when {
                    forceTrim && index == 0 -> Unit
                    forceTrim && index == 1 && inline is PlainText -> {
                        val trimmed = inline.value.trimStart()
                        if (trimmed.isNotEmpty()) {
                            Text(trimmed, fontSize = 16.sp, lineHeight = 22.sp, color = colors.bodyText)
                        }
                    }

                    inline is InlineKaTeX -> KatexDegradeText(inline.value, inline = true, onKatexClick)
                    else -> Text(inlineText(inline), fontSize = 16.sp, lineHeight = 22.sp, color = colors.bodyText)
                }
            }
        }
    } else {
        Text(
            rendered.joinToString(""),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = colors.bodyText,
            modifier = modifier,
        )
    }
}

// ── Heading（Heading.tsx：level 1-4 样式压制，越界回落 heading1）──

@Composable
internal fun MarkdownHeading(block: Heading, modifier: Modifier = Modifier) {
    val colors = LocalAppiaColors.current
    val (size, lineHeight, vertical) = when (block.level) {
        2 -> Triple(18, 24, 4)
        3 -> Triple(16, 22, 2)
        4 -> Triple(14, 20, 2)
        else -> Triple(22, 28, 4) // RN headingStyles[level-1] ?? heading1：1 与越界均落 heading1
    }
    Text(
        block.value.joinToString("") { inlineText(it) },
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = FontWeight.Bold,
        color = colors.bodyText,
        modifier = modifier.padding(vertical = vertical.dp),
    )
}

// ── Quote（Quote.tsx：styles.quote 仅 borderLeftWidth 3 #e0e0e0 左侧竖条，段落逐个走 Paragraph）──

@Composable
internal fun MarkdownQuote(block: Quote, modifier: Modifier = Modifier, onKatexClick: ((String) -> Unit)? = null) {
    Column(
        modifier
            .padding(vertical = 2.dp)
            .drawBehind {
                // 仅左侧竖条（RN borderLeftWidth 3）：中线对齐 RN 边框语义
                val stroke = 3.dp.toPx()
                drawLine(MarkdownStyle.quoteBorder, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), strokeWidth = stroke)
            }
            .padding(start = 8.dp),
    ) {
        block.value.filterIsInstance<Paragraph>().forEach { paragraph ->
            MarkdownParagraph(paragraph.value, onKatexClick = onKatexClick)
        }
    }
}

// ── Code（Code.tsx 普通；AI 消息 AiCodeBlock：语言标签 + 复制按钮）──
// AI 判定字段对照 RN resolveAiMsgKind：msgType === 'ai_response'（T13 组装时传入 aiCodeBlock）。

@Composable
internal fun MarkdownCode(block: Code, aiCodeBlock: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val codeText = block.value.filterIsInstance<CodeLine>().joinToString("\n") { line -> inlineText(line.value) }

    Column(
        modifier
            .padding(vertical = 4.dp)
            .background(MarkdownStyle.codeBlockBg, RoundedCornerShape(4.dp)),
    ) {
        if (aiCodeBlock) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MarkdownStyle.aiCodeHeaderBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // RN codeLang 样式带 textTransform: lowercase（CSS 小写显示）
                    block.language?.lowercase() ?: "",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MarkdownStyle.aiCodeLang,
                    modifier = Modifier.testTag("qa-ai-code-lang"),
                )
                Text(
                    if (copied) context.t("ai_codecopied") else context.t("ai_codecopy"),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MarkdownStyle.link,
                    modifier = Modifier
                        .clickable {
                            clipboard.setText(AnnotatedString(codeText))
                            copied = true
                        }
                        .testTag("qa-ai-code-copy"),
                )
            }
        }
        Column(Modifier.padding(8.dp)) {
            block.value.forEach { line ->
                if (line is CodeLine) {
                    Text(
                        inlineText(line.value),
                        fontFamily = FontFamily.Monospace,
                        fontSize = MONOSPACE_SIZE.sp,
                        lineHeight = MONOSPACE_LINE_HEIGHT.sp,
                        color = LocalAppiaColors.current.bodyText,
                    )
                }
            }
        }
    }
}

// ── List（List.tsx：嵌套递归、marker 1) a) i) I)、start=number 偏移）──

private val ROMAN_PAIRS = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")

private fun toRoman(n: Int): String {
    var rest = n
    var out = ""
    for ((value, symbol) in ROMAN_PAIRS) {
        while (rest >= value) {
            out += symbol
            rest -= value
        }
    }
    return out
}

/** web 端 gazzondown ORDERED_NUMBERS 同款：按层级循环 1..100 / a..z / I..XX / i..xx。 */
private val ORDERED_MARKERS = listOf(
    List(100) { "${it + 1}" },
    List(26) { ('a' + it).toString() },
    List(20) { toRoman(it + 1) },
    List(20) { toRoman(it + 1).lowercase() },
)

internal fun orderedMarker(level: Int, n: Int): String {
    val tokens = ORDERED_MARKERS[level % ORDERED_MARKERS.size]
    return "${tokens[(n - 1) % tokens.size]}) "
}

@Composable
internal fun MarkdownUnorderedList(
    value: List<MdNode>,
    level: Int,
    modifier: Modifier = Modifier,
    onKatexClick: ((String) -> Unit)? = null,
) {
    Column(modifier.padding(start = (level * 16).dp)) {
        value.forEachIndexed { index, item ->
            val li = item as? ListItem ?: return@forEachIndexed
            when (val first = li.value.firstOrNull()) {
                // RN nestedRow：嵌套列表整体再缩进 16
                is UnorderedList -> MarkdownUnorderedList(first.value, first.level ?: 0, Modifier.padding(start = 16.dp), onKatexClick)
                is OrderedList -> MarkdownOrderedList(first.value, first.level ?: 0, Modifier.padding(start = 16.dp), onKatexClick)
                else -> ListItemRow("• ", li, onKatexClick)
            }
        }
    }
}

@Composable
internal fun MarkdownOrderedList(
    value: List<MdNode>,
    level: Int,
    modifier: Modifier = Modifier,
    onKatexClick: ((String) -> Unit)? = null,
) {
    Column(modifier.padding(start = (level * 16).dp)) {
        value.forEachIndexed { index, item ->
            val li = item as? ListItem ?: return@forEachIndexed
            when (val first = li.value.firstOrNull()) {
                is UnorderedList -> MarkdownUnorderedList(first.value, first.level ?: 0, Modifier.padding(start = 16.dp), onKatexClick)
                is OrderedList -> MarkdownOrderedList(first.value, first.level ?: 0, Modifier.padding(start = 16.dp), onKatexClick)
                else -> ListItemRow(orderedMarker(level, li.number ?: index + 1), li, onKatexClick)
            }
        }
    }
}

@Composable
private fun ListItemRow(marker: String, item: ListItem, onKatexClick: ((String) -> Unit)?) {
    val colors = LocalAppiaColors.current
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(marker, fontSize = 14.sp, lineHeight = 20.sp, color = colors.bodyText)
        Text(
            item.value.joinToString("") { inlineText(it) },
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = colors.bodyText,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// ── BigEmoji（BigEmoji.tsx：不放大仅布局特判；shortCode 待 T5 接表情表，先按原样文本）──

@Composable
internal fun MarkdownBigEmoji(value: List<MdInline>, modifier: Modifier = Modifier) {
    val colors = LocalAppiaColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        value.forEach { inline ->
            val emoji = inline as? Emoji ?: return@forEach
            // ponytail: shortCode 无 shortnameToUnicode 表时按 ':code:' 原样（RN miss 路径同款）；T5 接表后消除
            val text = emoji.unicode ?: emoji.shortCode?.let { ":$it:" } ?: return@forEach
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = colors.bodyText)
        }
    }
}

// ── HorizontalRule / LineBreak / KaTeX 降级 ──

@Composable
internal fun MarkdownHorizontalRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MarkdownStyle.hr)
            .testTag("markdown-horizontal-rule"),
    )
}

@Composable
internal fun MarkdownLineBreakSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.height(8.dp)) // RN styles.lineBreak height 8
}

/** KaTeX 首版降级：原式等宽文本 + 可点击（T13 接单条 WebView 渲染入口，已知差异入册）。 */
@Composable
internal fun KatexDegradeText(
    value: String,
    inline: Boolean,
    onKatexClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val math = value.trim()
    if (math.isEmpty()) return
    val colors = LocalAppiaColors.current
    Text(
        math,
        fontFamily = FontFamily.Monospace,
        fontSize = MONOSPACE_SIZE.sp,
        lineHeight = MONOSPACE_LINE_HEIGHT.sp,
        color = colors.bodyText,
        modifier = modifier
            .then(if (inline) Modifier.padding(horizontal = 2.dp) else Modifier.padding(vertical = 4.dp))
            .then(if (onKatexClick != null) Modifier.clickable { onKatexClick(math) } else Modifier),
    )
}

// ── Table 入口（Table/index.tsx：TABLE_PREVIEW_MAX_HEIGHT 300 截断 + 点击回调；全屏预览 T13）──

private const val TABLE_PREVIEW_MAX_HEIGHT = 300
private const val MIN_COL_WIDTH = 72
private const val MAX_WEIGHT_CHARS = 20

internal data class ColumnWidthsResult(val columnWidths: List<Dp>, val tableWidth: Dp, val needsHorizontalScroll: Boolean)

/** Table/plainTextFromTableBlock.ts 移植（列宽权重与全屏预览共用）。 */
internal fun plainTextFromTableBlock(block: MdNode): String = when (block) {
    is Paragraph -> if (block.subType != null) "" else block.value.joinToString("") { plainInlineText(it) }
    is Heading -> block.value.joinToString("") { plainInlineText(it) }
    is Code -> block.value.filterIsInstance<CodeLine>().joinToString("\n") { plainInlineText(it.value) }
    is KaTeX -> block.value
    is LineBreak -> "\n"
    is Quote -> block.value.filterIsInstance<Paragraph>()
        .joinToString("\n") { paragraph -> paragraph.value.joinToString("") { plainInlineText(it) } }
    is OrderedList -> block.value.joinToString("\n") { item -> (item as? ListItem)?.value?.joinToString("") { plainInlineText(it) }.orEmpty() }
    is UnorderedList -> block.value.joinToString("\n") { item -> (item as? ListItem)?.value?.joinToString("") { plainInlineText(it) }.orEmpty() }
    else -> ""
}

private fun cellTextLength(cell: MdNode): Int =
    ((cell as? TableCell)?.value ?: emptyList()).sumOf { plainTextFromTableBlock(it).length }

/** Table/columnWidths.ts 水填法（water-filling）移植：份额不足 MIN_COL_WIDTH 的列钳定后余宽再分配。 */
internal fun computeColumnWidths(rows: List<MdNode>, availableWidth: Dp): ColumnWidthsResult {
    val tableRows = rows.filterIsInstance<TableRow>()
    val colCount = tableRows.firstOrNull()?.value?.size ?: 1
    val weights = List(colCount) { col ->
        var maxLen = 1
        tableRows.forEach { row ->
            (row.value.getOrNull(col) as? TableCell)?.let { maxLen = max(maxLen, cellTextLength(it)) }
        }
        minOf(maxLen, MAX_WEIGHT_CHARS)
    }
    val totalWeight = weights.sum()

    if (colCount * MIN_COL_WIDTH > availableWidth.value) {
        val columnWidths = weights.map { weight ->
            max(MIN_COL_WIDTH.toFloat(), (availableWidth.value * weight) / totalWeight).dp
        }
        return ColumnWidthsResult(columnWidths, columnWidths.reduce { a, b -> a + b }, needsHorizontalScroll = true)
    }

    val columnWidths = MutableList(colCount) { 0f.dp }
    val clamped = BooleanArray(colCount)
    var remainingWidth = availableWidth.value
    var remainingWeight = totalWeight.toFloat()
    var activeCount = colCount
    for (pass in 0 until colCount) {
        var clampedAny = false
        weights.forEachIndexed { col, weight ->
            if (!clamped[col] && remainingWidth * weight / remainingWeight < MIN_COL_WIDTH) {
                columnWidths[col] = MIN_COL_WIDTH.dp
                clamped[col] = true
                remainingWidth -= MIN_COL_WIDTH
                remainingWeight -= weight
                activeCount -= 1
                clampedAny = true
            }
        }
        if (!clampedAny) break
    }
    if (activeCount > 0) {
        weights.forEachIndexed { col, weight ->
            if (!clamped[col]) columnWidths[col] = (remainingWidth * weight / remainingWeight).dp
        }
    }
    return ColumnWidthsResult(columnWidths, columnWidths.reduce { a, b -> a + b }, needsHorizontalScroll = false)
}

@Composable
internal fun MarkdownTable(
    rows: List<MdNode>,
    onOpenTable: ((List<MdNode>) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    Column(
        modifier
            .testTag("markdown-table-preview")
            .then(if (onOpenTable != null) Modifier.clickable { onOpenTable(rows) } else Modifier),
    ) {
        BoxWithConstraints {
            val availableWidth = maxWidth
            val layout = if (availableWidth > 0.dp) computeColumnWidths(rows, availableWidth - 2.dp) else null
            Box(
                Modifier
                    .heightIn(max = TABLE_PREVIEW_MAX_HEIGHT.dp)
                    .border(1.dp, MarkdownStyle.tableBorder, RoundedCornerShape(4.dp)),
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (layout != null) {
                        Column(Modifier.width(layout.tableWidth)) {
                            rows.filterIsInstance<TableRow>().forEach { row ->
                                Row {
                                    row.value.filterIsInstance<TableCell>().forEachIndexed { cellIndex, cell ->
                                        val header = cell.isHeader == true
                                        Text(
                                            cell.value.joinToString("\n") { block -> plainTextFromTableBlock(block) },
                                            fontSize = 16.sp,
                                            lineHeight = 22.sp,
                                            fontWeight = if (header) FontWeight.SemiBold else null,
                                            color = colors.bodyText,
                                            modifier = Modifier
                                                // GFM 行 cells 数可不齐（RN 越界得 undefined 不崩）：缺列按最小列宽兜底
                                                .width(layout.columnWidths.getOrNull(cellIndex) ?: MIN_COL_WIDTH.dp)
                                                .background(if (header) MarkdownStyle.tableHeaderBg else Color.Transparent)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 预览边框左右各占 1px，计算列宽时让出（RN Table onLayout 同款）
        Text(
            context.t("markdown_viewfulltable"),
            fontSize = 13.sp,
            color = colors.auxiliaryText,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
