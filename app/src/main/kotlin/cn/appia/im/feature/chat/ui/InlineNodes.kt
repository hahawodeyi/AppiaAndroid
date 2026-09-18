package cn.appia.im.feature.chat.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cn.appia.im.core.messaging.Bold
import cn.appia.im.core.messaging.MD_INLINE_MAX_DEPTH
import cn.appia.im.core.messaging.Emoji
import cn.appia.im.core.messaging.InlineCode
import cn.appia.im.core.messaging.InlineKaTeX
import cn.appia.im.core.messaging.Italic
import cn.appia.im.core.messaging.LineBreak
import cn.appia.im.core.messaging.Link
import cn.appia.im.core.messaging.MdInline
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.MentionChannel
import cn.appia.im.core.messaging.MentionUser as MdMentionUser
import cn.appia.im.core.messaging.PlainText
import cn.appia.im.core.messaging.ResolvedEmoji
import cn.appia.im.core.messaging.Strike
import cn.appia.im.core.messaging.getLinkLabelText
import cn.appia.im.core.messaging.plainInlineText
import cn.appia.im.core.messaging.shortnameToUnicode
import cn.appia.im.core.theme.LocalAppiaColors
import java.net.URLEncoder

/**
 * 行内节点 AnnotatedString 渲染（对照 appiaMobile src/components/markdown/Inline.tsx:41-89 +
 * Bold/Italic/Strike/Link/InlineCode/Emoji/AtMention/Hashtag + styles.ts）。
 *
 * 与 RN 的记录在册差异：
 * - BOLD/ITALIC/STRIKE 子节点全量递归（M2 裁定修复 RN Bold.tsx:39-60 只认
 *   PLAIN_TEXT/LINK/STRIKE/ITALIC/BOLD、丢 emoji/mention/inlineCode 的缺口）。
 * - value-only EMOJI（ChatInputBar editorJson 的 custom 形态，RN Emoji.tsx 直接丢弃）按
 *   shortCode 同口径处理——与 T3 EmojiResolver 缝 `shortCode ?? value.value` 一致。
 * - INLINE_CODE 的圆角/水平 padding 无法用 SpanStyle 表达（只保留底色 #f0f0f0 + 等宽 13sp）。
 * - LINK 走 LinkAnnotation（#1d74f5 下划线可点）；label 仍按 RN linkUtils 展平为纯文本。
 * - INLINE_KATEX 在整段含公式时仍由 MarkdownParagraph 走 FlowRow 降级（可点击），
 *   行内串内部降级为等宽 span（不可点）。
 * - 递归深度上限 [MD_INLINE_MAX_DEPTH]：服务端恶意/异常深嵌套超限后按纯文本展平，不炸栈
 *   （展平用的 plainInlineText/getLinkLabelText 同样带深度截断）。
 *
 * mention 显示名解析复用 MessageRow.kt 的 [resolveMentionDisplay] 共享 helper（勿另写）；
 * 纯函数 [buildInlineAnnotated] 不依赖 Compose 状态，便于单测。
 */

/** RN styles.mention fontWeight 600 → SemiBold（未命中 mention 回退宿主样式）。 */
private val MENTION_WEIGHT = FontWeight.SemiBold

/** 行内样式色（composition 外可构造，单测用）。 */
internal data class InlineColors(
    val base: Color,
    val link: Color,
    val mentionMe: Color,
    val mentionGroup: Color,
    val mentionOther: Color,
)

/** 行内渲染环境（mentions/自定义表情/链接点击）：MessageBody 注入，T13 组装接线。 */
internal data class InlineEnv(
    val mentions: List<MentionUser> = emptyList(),
    val currentUsername: String? = null,
    /** 自定义表情解析（RN getCustomEmoji）：命中 Custom 且 baseUrl 非空 → 内联图片。 */
    val getCustomEmoji: ((String) -> ResolvedEmoji?)? = null,
    val baseUrl: String? = null,
    /** 链接点击（RN onLinkPress）；null 时 InlineNodes 兜底系统浏览器。 */
    val onLinkPress: ((String) -> Unit)? = null,
)

/** 构建产物：串 + 待渲染自定义表情（id=shortCode → 信息，Composable 层转 inlineContent）。 */
internal class InlineResult(
    val annotated: AnnotatedString,
    val customEmojis: Map<String, ResolvedEmoji.Custom>,
)

/** RN CustomEmoji.tsx：`baseUrl/emoji-custom/{encodeURIComponent(name)}.{extension}`。 */
internal fun customEmojiUrl(custom: ResolvedEmoji.Custom, baseUrl: String): String =
    "$baseUrl/emoji-custom/${URLEncoder.encode(custom.name, "UTF-8")}.${custom.extension}"

/** EMOJI 短名：shortCode 优先，编辑器 custom 形态回退 value（RN Emoji.tsx 丢弃该形态，见 KDoc）。 */
internal fun emojiShortCode(emoji: Emoji): String? =
    emoji.shortCode ?: (emoji.value as? PlainText)?.value?.takeIf { it.isNotEmpty() }

/** EMOJI 节点 unicode 文本：unicode 优先；shortCode 查表（miss 原样 ':code:'）；全缺 null。 */
internal fun emojiUnicodeText(emoji: Emoji): String? =
    emoji.unicode ?: emojiShortCode(emoji)?.let { shortnameToUnicode(":$it:") }

/** RN 色串 '#rrggbb'/'#aarrggbb' 解析（Bold FontColor）；坏串 null 不套色。 */
internal fun parseHexColor(hex: String): Color? = runCatching {
    val s = hex.removePrefix("#").removePrefix("0x")
    when (s.length) {
        6 -> Color((s.toLong(16).toInt() or 0xFF000000.toInt()))
        8 -> Color(s.toLong(16).toInt())
        else -> null
    }
}.getOrNull()

/**
 * 行内节点 → AnnotatedString 纯函数（Inline.tsx:41-89 分发 + Bold.tsx 样式继承）。
 * [forceTrim] 对照 Inline.tsx:31-39：首位 permalink 空链接剔除、次位 PLAIN_TEXT trimStart。
 */
internal fun buildInlineAnnotated(
    value: List<MdInline>,
    colors: InlineColors,
    env: InlineEnv,
    forceTrim: Boolean = false,
): InlineResult {
    val custom = mutableMapOf<String, ResolvedEmoji.Custom>()
    val annotated = buildAnnotatedString {
        value.forEachIndexed { index, node ->
            when {
                forceTrim && index == 0 -> Unit
                forceTrim && index == 1 && node is PlainText -> {
                    val trimmed = node.value.trimStart()
                    if (trimmed.isNotEmpty()) appendNode(PlainText(trimmed), SpanStyle(), colors, env, custom, 0)
                }

                else -> appendNode(node, SpanStyle(), colors, env, custom, 0)
            }
        }
    }
    return InlineResult(annotated, custom)
}

/** 空累积样式不包裹 span（避免无谓区间污染 spanStyles）。 */
private inline fun AnnotatedString.Builder.withAcc(acc: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    if (acc == SpanStyle()) block() else withStyle(acc) { block() }
}

/** 叶子文本追加。 */
private fun AnnotatedString.Builder.appendLeaf(text: String, acc: SpanStyle) {
    if (text.isEmpty()) return
    withAcc(acc) { append(text) }
}

/** 单节点追加：[acc] 为 Bold/Italic/Strike 累积样式（RN 嵌套 Text 样式继承语义）。 */
private fun AnnotatedString.Builder.appendNode(
    node: MdNode,
    acc: SpanStyle,
    colors: InlineColors,
    env: InlineEnv,
    custom: MutableMap<String, ResolvedEmoji.Custom>,
    depth: Int,
) {
    if (depth > MD_INLINE_MAX_DEPTH) {
        appendLeaf(plainInlineText(node), acc)
        return
    }
    when (node) {
        is PlainText -> appendLeaf(node.value, acc)

        is Bold ->
            // RN isMarkdownBoldNode：带 color/size 的 BOLD 是 FontColor/FontSize，不加粗只套样式
            appendContainer(node.value, acc, colors, env, custom, depth) { style ->
                var child = style
                if (node.color == null && node.size == null) child = child.copy(fontWeight = FontWeight.Bold)
                node.color?.let { hex -> parseHexColor(hex)?.let { c -> child = child.copy(color = c) } }
                node.size?.let { child = child.copy(fontSize = (it + 2).sp) }
                child
            }

        is Italic ->
            appendContainer(node.value, acc, colors, env, custom, depth) { it.copy(fontStyle = FontStyle.Italic) }

        is Strike ->
            appendContainer(node.value, acc, colors, env, custom, depth) {
                it.copy(textDecoration = TextDecoration.LineThrough)
            }

        is Link -> {
            // RN Link：label 展平纯文本 + trim，空 label 不渲染
            val label = getLinkLabelText(node.value.label).trim()
            if (label.isEmpty()) return
            val href = node.value.src.value
            val styles = TextLinkStyles(
                SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline),
            )
            val annotation = LinkAnnotation.Url(href, styles) { env.onLinkPress?.invoke(href) }
            withAcc(acc) { withLink(annotation) { append(label) } }
        }

        is InlineCode -> {
            val text = (node.value as? PlainText)?.value.orEmpty()
            withStyle(
                acc.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = MarkdownStyle.inlineCodeBg,
                ),
            ) { append(text) }
        }

        is MdMentionUser -> {
            val mention = (node.value as? PlainText)?.value.orEmpty()
            val display = resolveMentionDisplay(env.mentions, mention, env.currentUsername)
            val style = when (display.kind) {
                MentionKind.GROUP -> acc.copy(color = colors.mentionGroup, fontWeight = MENTION_WEIGHT)
                MentionKind.ME -> acc.copy(color = colors.mentionMe, fontWeight = MENTION_WEIGHT)
                MentionKind.OTHER -> acc.copy(color = colors.mentionOther, fontWeight = MENTION_WEIGHT)
                MentionKind.UNRESOLVED -> acc
            }
            withAcc(style) { append(display.label) }
        }

        is MentionChannel -> {
            // RN Hashtag：'#'+channel，styles.hashtag 只改色（无字重）
            val channel = (node.value as? PlainText)?.value.orEmpty()
            withStyle(acc.copy(color = colors.link)) { append("#$channel") }
        }

        is Emoji -> appendEmoji(node, acc, env, custom)

        is InlineKaTeX ->
            withStyle(acc.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(node.value.trim()) }

        is LineBreak -> appendLeaf("\n", acc)

        else -> Unit // RN default: return null（未知行内型不渲染）
    }
}

/** Bold/Italic/Strike 容器递归（M2 裁定：子节点全量走行内渲染，勿仿 RN 只认四种）。 */
private fun AnnotatedString.Builder.appendContainer(
    value: List<MdInline>,
    acc: SpanStyle,
    colors: InlineColors,
    env: InlineEnv,
    custom: MutableMap<String, ResolvedEmoji.Custom>,
    depth: Int,
    nest: (SpanStyle) -> SpanStyle,
) {
    val child = nest(acc)
    value.forEach { appendNode(it, child, colors, env, custom, depth + 1) }
}

/** Emoji.tsx：unicode 直出 → custom 图片（resolver+baseUrl）→ 查表文本（miss 原样 ':code:'）。 */
private fun AnnotatedString.Builder.appendEmoji(
    node: Emoji,
    acc: SpanStyle,
    env: InlineEnv,
    custom: MutableMap<String, ResolvedEmoji.Custom>,
) {
    node.unicode?.let { appendLeaf(it, acc); return }
    val code = emojiShortCode(node) ?: return
    val resolved = env.getCustomEmoji?.invoke(code) as? ResolvedEmoji.Custom
    if (resolved != null && !env.baseUrl.isNullOrEmpty()) {
        custom[code] = resolved
        // alternateText 占位：无图/复制/a11y 场景回退 unicode 文本
        withAcc(acc) { appendInlineContent(code, emojiUnicodeText(node) ?: ":$code:") }
    } else {
        appendLeaf(shortnameToUnicode(":$code:"), acc)
    }
}

/**
 * 行内节点 Composable（Paragraph/Heading/List 等调用）：默认正文 16sp/22sp、bodyText 色，
 * 自定义表情经 InlineTextContent 内联图片（RN inline 20x14）。
 */
@Composable
internal fun InlineNodes(
    value: List<MdInline>,
    env: InlineEnv,
    modifier: Modifier = Modifier,
    forceTrim: Boolean = false,
    fontSize: TextUnit = 16.sp,
    lineHeight: TextUnit = 22.sp,
    fontWeight: FontWeight? = null,
) {
    val theme = LocalAppiaColors.current
    val colors = InlineColors(
        base = theme.bodyText,
        link = MarkdownStyle.link,
        mentionMe = theme.mentionMeColor,
        mentionGroup = theme.mentionGroupColor,
        mentionOther = theme.mentionOtherColor,
    )
    val context = LocalContext.current
    // env.onLinkPress 未注入时兜底系统浏览器（RN openLink）
    val effectiveEnv = if (env.onLinkPress == null) {
        env.copy(
            onLinkPress = { href ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, href.toUri()))
                } catch (_: ActivityNotFoundException) {
                }
            },
        )
    } else {
        env
    }
    val result = buildInlineAnnotated(value, colors, effectiveEnv, forceTrim)
    val inlineContents = result.customEmojis.mapValues { (_, custom) ->
        InlineTextContent(
            Placeholder(20.sp, 14.sp, PlaceholderVerticalAlign.TextCenter),
        ) {
            AsyncImage(
                model = customEmojiUrl(custom, env.baseUrl.orEmpty()),
                contentDescription = custom.name,
                modifier = Modifier.size(width = 20.dp, height = 14.dp).testTag("custom-emoji"),
            )
        }
    }
    Text(
        text = result.annotated,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        color = colors.base,
        inlineContent = inlineContents,
    )
}
