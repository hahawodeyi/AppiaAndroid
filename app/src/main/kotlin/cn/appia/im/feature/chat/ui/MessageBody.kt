package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.appia.im.core.messaging.BigEmoji
import cn.appia.im.core.messaging.Bold
import cn.appia.im.core.messaging.Code
import cn.appia.im.core.messaging.CodeLine
import cn.appia.im.core.messaging.Emoji
import cn.appia.im.core.messaging.Heading
import cn.appia.im.core.messaging.HorizontalRule
import cn.appia.im.core.messaging.InlineCode
import cn.appia.im.core.messaging.InlineKaTeX
import cn.appia.im.core.messaging.Italic
import cn.appia.im.core.messaging.KaTeX
import cn.appia.im.core.messaging.Link
import cn.appia.im.core.messaging.LineBreak
import cn.appia.im.core.messaging.MdBlock
import cn.appia.im.core.messaging.MdInline
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.MentionChannel
import cn.appia.im.core.messaging.MentionUser
import cn.appia.im.core.messaging.OrderedList
import cn.appia.im.core.messaging.Paragraph
import cn.appia.im.core.messaging.PlainText
import cn.appia.im.core.messaging.Quote
import cn.appia.im.core.messaging.Root
import cn.appia.im.core.messaging.Strike
import cn.appia.im.core.messaging.UnorderedList

/**
 * 消息正文块级分发（对照 appiaMobile src/components/markdown/renderMarkdownBlock.tsx:37-73 逐项）：
 * HORIZONTAL_RULE → TABLE 段落（isTableParagraph 优先于普通 PARAGRAPH）→ PARAGRAPH/HEADING/
 * QUOTE/CODE/UNORDERED_LIST/ORDERED_LIST/BIG_EMOJI/LINE_BREAK/KATEX，未知块型不渲染（RN default null）。
 *
 * 解析链入口 [cn.appia.im.core.messaging.resolveMessageMd]；组装进 MessageRow 归 T13。
 */
@Composable
fun MessageBody(
    root: Root?,
    modifier: Modifier = Modifier,
    /** AI 代码块样式（语言标签+复制按钮）。判定字段对照 RN resolveAiMsgKind：msgType === 'ai_response'，T13 组装传入。 */
    aiCodeBlock: Boolean = false,
    /** 表格预览点击回调（全屏 MarkdownTableScreen 等价接线归 T13）。 */
    onTableOpen: ((List<MdNode>) -> Unit)? = null,
    /** KaTeX 降级原式点击回调（单条 WebView 渲染入口，T13 接线）。 */
    onKatexClick: ((String) -> Unit)? = null,
) {
    if (root == null) return
    Column(modifier) {
        root.blocks.forEach { block -> RenderMarkdownBlock(block, aiCodeBlock, onTableOpen, onKatexClick) }
    }
}

@Composable
private fun RenderMarkdownBlock(
    block: MdBlock,
    aiCodeBlock: Boolean,
    onTableOpen: ((List<MdNode>) -> Unit)?,
    onKatexClick: ((String) -> Unit)?,
) {
    // RN 分发顺序：isHorizontalRuleBlock → isTableParagraph → switch(type)
    when {
        block is HorizontalRule -> MarkdownHorizontalRule()

        block is Paragraph && block.subType == "TABLE" && block.data != null ->
            MarkdownTable(block.data, onTableOpen)

        else -> when (block) {
            is Paragraph -> MarkdownParagraph(block.value, onKatexClick = onKatexClick)
            is Heading -> MarkdownHeading(block)
            is Quote -> MarkdownQuote(block, onKatexClick = onKatexClick)
            is Code -> MarkdownCode(block, aiCodeBlock)
            is UnorderedList -> MarkdownUnorderedList(block.value, block.level ?: 0, onKatexClick = onKatexClick)
            is OrderedList -> MarkdownOrderedList(block.value, block.level ?: 0, onKatexClick = onKatexClick)
            is BigEmoji -> MarkdownBigEmoji(block.value)
            is LineBreak -> MarkdownLineBreakSpacer()
            is KaTeX -> KatexDegradeText(block.value, inline = false, onKatexClick)
            else -> Unit // RN default: return null（未知块型不渲染）
        }
    }
}

/**
 * 行内节点纯文本扁平化——T4 块级骨架用（保证各块型可见）。
 * T5 换 AnnotatedString 行内管线（Inline.tsx:41-89 + Bold.tsx:39-60 对照）后删除本函数。
 */
internal fun inlineText(node: MdNode): String = when (node) {
    is PlainText -> node.value
    is Emoji -> node.unicode ?: node.shortCode?.let { ":$it:" }.orEmpty()
    is InlineCode -> inlineText(node.value)
    is Link -> node.value.label.joinToString("") { inlineText(it) }
    is Bold -> node.value.joinToString("") { inlineText(it) }
    is Italic -> node.value.joinToString("") { inlineText(it) }
    is Strike -> node.value.joinToString("") { inlineText(it) }
    is MentionUser -> inlineText(node.value)
    is MentionChannel -> inlineText(node.value)
    is InlineKaTeX -> node.value
    is LineBreak -> "\n"
    is Paragraph -> node.value.joinToString("") { inlineText(it) }
    is CodeLine -> inlineText(node.value)
    else -> ""
}
