package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.appia.im.core.messaging.BigEmoji
import cn.appia.im.core.messaging.Code
import cn.appia.im.core.messaging.Heading
import cn.appia.im.core.messaging.HorizontalRule
import cn.appia.im.core.messaging.InlineKaTeX
import cn.appia.im.core.messaging.KaTeX
import cn.appia.im.core.messaging.LineBreak
import cn.appia.im.core.messaging.MdBlock
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.OrderedList
import cn.appia.im.core.messaging.Paragraph
import cn.appia.im.core.messaging.Quote
import cn.appia.im.core.messaging.Root
import cn.appia.im.core.messaging.UnorderedList

/**
 * 消息正文块级分发（对照 appiaMobile src/components/markdown/renderMarkdownBlock.tsx:37-73 逐项）：
 * HORIZONTAL_RULE → TABLE 段落（isTableParagraph 优先于普通 PARAGRAPH）→ PARAGRAPH/HEADING/
 * QUOTE/CODE/UNORDERED_LIST/ORDERED_LIST/BIG_EMOJI/LINE_BREAK/KATEX，未知块型不渲染（RN default null）。
 *
 * 解析链入口 [cn.appia.im.core.messaging.resolveMessageMd]；行内渲染走 [InlineNodes]
 * （T5 AnnotatedString 管线）；组装进 MessageRow 归 T13。
 */
@Composable
internal fun MessageBody(
    root: Root?,
    modifier: Modifier = Modifier,
    /** AI 代码块样式（语言标签+复制按钮）。判定字段对照 RN resolveAiMsgKind：msgType === 'ai_response'，T13 组装传入。 */
    aiCodeBlock: Boolean = false,
    /** 表格预览点击回调（全屏 MarkdownTableScreen 等价接线归 T13）。 */
    onTableOpen: ((List<MdNode>) -> Unit)? = null,
    /** KaTeX 降级原式点击回调（单条 WebView 渲染入口，T13 接线）。 */
    onKatexClick: ((String) -> Unit)? = null,
    /** 行内渲染环境：mentions/自定义表情/链接点击（T13 组装注入；缺省纯文本回退）。 */
    env: InlineEnv = InlineEnv(),
) {
    if (root == null) return
    Column(modifier) {
        root.blocks.forEach { block -> RenderMarkdownBlock(block, aiCodeBlock, onTableOpen, onKatexClick, env) }
    }
}

@Composable
private fun RenderMarkdownBlock(
    block: MdBlock,
    aiCodeBlock: Boolean,
    onTableOpen: ((List<MdNode>) -> Unit)?,
    onKatexClick: ((String) -> Unit)?,
    env: InlineEnv,
) {
    // RN 分发顺序：isHorizontalRuleBlock → isTableParagraph → switch(type)
    when {
        block is HorizontalRule -> MarkdownHorizontalRule()

        block is Paragraph && block.subType == "TABLE" && block.data != null ->
            MarkdownTable(block.data, onTableOpen)

        else -> when (block) {
            is Paragraph -> MarkdownParagraph(block.value, env = env, onKatexClick = onKatexClick)
            is Heading -> MarkdownHeading(block, env = env)
            is Quote -> MarkdownQuote(block, env = env, onKatexClick = onKatexClick)
            is Code -> MarkdownCode(block, aiCodeBlock)
            is UnorderedList -> MarkdownUnorderedList(block.value, block.level ?: 0, env = env, onKatexClick = onKatexClick)
            is OrderedList -> MarkdownOrderedList(block.value, block.level ?: 0, env = env, onKatexClick = onKatexClick)
            is BigEmoji -> MarkdownBigEmoji(block.value, env = env)
            is LineBreak -> MarkdownLineBreakSpacer()
            is KaTeX -> KatexDegradeText(block.value, inline = false, onKatexClick)
            else -> Unit // RN default: return null（未知块型不渲染）
        }
    }
}
