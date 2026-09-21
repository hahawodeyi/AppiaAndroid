package cn.appia.im.feature.chatlist

import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.messaging.resolveMdFromMsgFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val KEY_NO_MESSAGE = "roomItem_noMessage"
private const val KEY_SENT_ATTACHMENT = "roomItem_sentAttachment"

/** RN lastMessageTypes.ts `LastMessageUser`（预览只用 username/name）。 */
data class LastMessageUser(val username: String? = null, val name: String? = null)

/**
 * RN lastMessageTypes.ts `LastMessageShape` 的预览子集：
 * `attachments` 只保留「有没有 / 首个是否 image_url」（RN formatSpecialMsg 的分图片/文件判定），
 * `md` 存原文 JsonElement（字符串或 AST 数组，RN parseMd 语义）。
 */
data class LastMessageShape(
    val msg: String? = null,
    val md: JsonElement? = null,
    val t: String? = null,
    val pinned: Boolean = false,
    val u: LastMessageUser? = null,
    val hasAttachments: Boolean = false,
    val firstAttachmentIsImage: Boolean = false,
    val msgType: String? = null,
)

/** RN parseLastMessageField.ts：lastMessage JSON 列 → shape；空白/坏 JSON/非对象 → null。 */
fun parseLastMessageField(raw: String?): LastMessageShape? {
    if (raw.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null

    fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull

    val attachments = obj["attachments"]
    var hasAttachments = false
    var firstIsImage = false
    when (attachments) {
        is JsonArray -> if (attachments.isNotEmpty()) {
            hasAttachments = true
            firstIsImage = (attachments[0] as? JsonObject)?.containsKey("image_url") == true
        }
        is JsonObject -> if (attachments.isNotEmpty()) {
            hasAttachments = true
            firstIsImage = attachments.containsKey("image_url")
        }
        else -> Unit
    }

    return LastMessageShape(
        msg = str("msg"),
        md = obj["md"]?.takeIf { it !is JsonNull },
        t = str("t"),
        pinned = (obj["pinned"] as? JsonPrimitive)?.booleanOrNull == true,
        u = (obj["u"] as? JsonObject)?.let {
            LastMessageUser(
                username = (it["username"] as? JsonPrimitive)?.contentOrNull,
                name = (it["name"] as? JsonPrimitive)?.contentOrNull,
            )
        },
        hasAttachments = hasAttachments,
        firstAttachmentIsImage = firstIsImage,
        msgType = str("msgType"),
    )
}

/**
 * 预览渲染结果（RN LastMessagePreviewResult 的 M2 落法）：
 * - [Text]：纯文本已定（草稿/普通消息/md 拼接/特殊原文），发送人前缀已拼入。
 * - [Template]：待 UI 经 t() 渲染；key 对照 RN i18n 原名（t() 大小写不敏感），
 *   args 为 `{{x}}` 占位替换表，其中属 i18n key 的占位值列在 [translateArgs]
 *   （RN 在构造处 `t('roomItem_attachmentImage')` 解析，UI 侧等价翻译），
 *   其余占位值（jitsi 用户名、docCloud 文件名等原文）一律原样，杜绝误译；
 *   [brackets] 对应 RN oncall 的 `[语音通话]` 包裹。
 * M3 完整 markdown 渲染将扩展 inlines 形态；M2 一律展平为纯文本。
 */
sealed interface PreviewResult {
    data class Text(val text: String) : PreviewResult

    data class Template(
        val prefix: String,
        val key: String,
        val args: Map<String, String> = emptyMap(),
        val translateArgs: Set<String> = emptySet(),
        val brackets: Boolean = false,
    ) : PreviewResult
}

/** formatSpecialMsg 的内部判定产物：Raw 原文直出（meeting_room），Templated 走 i18n。 */
private sealed interface Special {
    data class Raw(val text: String) : Special

    data class Templated(
        val key: String,
        val args: Map<String, String> = emptyMap(),
        val translateArgs: Set<String> = emptySet(),
        val brackets: Boolean = false,
        val withPrefix: Boolean,
    ) : Special
}

/** RN otherSenderPrefix：`name || username`（各自 trim 后取非空者）+ 全角冒号。 */
private fun otherSenderPrefix(u: LastMessageUser): String {
    val label = u.name?.trim().orEmpty().ifEmpty { u.username?.trim().orEmpty() }
    return label.takeIf { it.isNotEmpty() }?.plus("：") ?: ""
}

/** RN senderPrefixFor：rollback 无前缀；自己的消息无前缀；其余拼发送人前缀。 */
private fun senderPrefixFor(lastMessage: LastMessageShape, currentUserId: String?): String {
    if (lastMessage.t == "rollback-message") return ""
    if (lastMessage.u?.username != null && lastMessage.u.username == currentUserId) return ""
    return lastMessage.u?.let(::otherSenderPrefix) ?: ""
}

/** RN formatSpecialMsg（resolveLastMessagePreview.ts:20-78）逐条同序移植。 */
private fun formatSpecialMsg(m: LastMessageShape): Special? {
    if (m.pinned) return Special.Templated(KEY_NO_MESSAGE, withPrefix = false)

    if (m.t == "jitsi_call_started") {
        return Special.Templated(
            "roomItem_startedCall",
            args = mapOf("user" to m.u?.username.orEmpty()),
            withPrefix = false,
        )
    }

    if (m.msg.isNullOrEmpty() && m.hasAttachments) {
        return Special.Templated(
            KEY_SENT_ATTACHMENT,
            args = mapOf(
                "kind" to if (m.firstAttachmentIsImage) "roomItem_attachmentImage" else "roomItem_attachmentFile",
            ),
            // RN 构造处即 t(kind)；docCloud 的 kind 是文件名原文不译，故此处标记可译占位
            translateArgs = setOf("kind"),
            withPrefix = true,
        )
    }

    if (m.msgType == "docCloud" && !m.msg.isNullOrEmpty()) {
        return Special.Templated(KEY_SENT_ATTACHMENT, args = mapOf("kind" to m.msg), withPrefix = true)
    }

    if (m.msgType == "oncall") return Special.Templated("roomItem_voiceCall", brackets = true, withPrefix = false)

    if (m.msgType == "meeting_room" && !m.msg.isNullOrEmpty()) return Special.Raw(m.msg)

    if (m.msgType == "forwardMergeMessage") return Special.Templated("roomItem_forwardRecord", withPrefix = true)

    return null
}

/**
 * 预览行内展平（T13 / RN lastMessagePreviewInlines.ts :20-65 逐分支）：经
 * [resolveMdFromMsgFields]（md 列直读 + stale 回退 msg 解析——md 渲染红线的共享单点），
 * 类型化 AST 上取首个「可见」块：
 * - PARAGRAPH subType=TABLE + 非空 label → label 直出；空白 label → 跳块（RN :24-31）
 * - PARAGRAPH/BIG_EMOJI → value 内联展平
 * - UNORDERED_LIST 首项 → `• ` + 首段内联；ORDERED_LIST 首项 → `N) ` + 首段内联
 * - 可见性（RN hasVisiblePreviewInlines :9-18）：PLAIN_TEXT 非空白 / 非空引用标记 LINK / 其余恒可见
 */
internal fun previewInlineText(md: JsonElement?, msg: String?, previewTableLabel: String?): String? {
    val mdRaw = when (md) {
        null, is JsonNull -> null
        // md 存量两种形态（RN parseMd 语义）：字符串化的 JSON 原文，或 AST 数组本体
        is JsonPrimitive -> if (md.isString) md.content else null
        else -> md.toString()
    }
    val root = resolveMdFromMsgFields(mdRaw, msg) ?: return null
    for (block in root.blocks) {
        val inlines: List<cn.appia.im.core.messaging.MdInline> = when (block) {
            is cn.appia.im.core.messaging.Paragraph -> {
                if (block.subType == "TABLE" && !previewTableLabel.isNullOrEmpty()) {
                    if (previewTableLabel.isNotBlank()) return previewTableLabel
                    continue
                }
                block.value
            }
            is cn.appia.im.core.messaging.BigEmoji -> block.value
            is cn.appia.im.core.messaging.UnorderedList ->
                (block.value.firstOrNull() as? cn.appia.im.core.messaging.ListItem)?.let {
                    listOf(cn.appia.im.core.messaging.PlainText("• ")) + it.value.filterIsInstance<cn.appia.im.core.messaging.MdInline>()
                } ?: continue
            is cn.appia.im.core.messaging.OrderedList -> {
                (block.value.firstOrNull() as? cn.appia.im.core.messaging.ListItem)?.let { first ->
                    val num = first.number ?: 1
                    listOf(cn.appia.im.core.messaging.PlainText("$num) ")) + first.value.filterIsInstance<cn.appia.im.core.messaging.MdInline>()
                } ?: continue
            }
            else -> continue
        }
        if (hasVisiblePreviewInlines(inlines)) return inlinesToPreviewText(inlines)
    }
    return null
}

/** RN hasVisiblePreviewInlines :9-18：PLAIN_TEXT 非空白 / LINK 非空引用标记 / 其余可见。 */
private fun hasVisiblePreviewInlines(inlines: List<cn.appia.im.core.messaging.MdInline>): Boolean =
    inlines.any { inline ->
        when (inline) {
            is cn.appia.im.core.messaging.PlainText -> inline.value.trim().isNotEmpty()
            is cn.appia.im.core.messaging.Link ->
                inline.value.label.joinToString("") { inlineToPreviewText(it) }.trim().isNotEmpty()
            else -> true
        }
    }

/** 行内 → 预览文本（RN RoomItemLastMessage plainMode 口径：mention 出 @name，katex 出原文）。 */
private fun inlinesToPreviewText(inlines: List<cn.appia.im.core.messaging.MdInline>): String =
    inlines.joinToString("") { inlineToPreviewText(it) }

private fun inlineToPreviewText(node: cn.appia.im.core.messaging.MdInline): String = when (node) {
    is cn.appia.im.core.messaging.PlainText -> node.value
    is cn.appia.im.core.messaging.Emoji -> node.unicode ?: node.value?.let { inlineToPreviewText(it) }.orEmpty()
    is cn.appia.im.core.messaging.Link -> node.value.label.joinToString("") { inlineToPreviewText(it) }
    is cn.appia.im.core.messaging.MentionUser -> "@" + inlineToPreviewText(node.value)
    is cn.appia.im.core.messaging.MentionChannel -> "#" + inlineToPreviewText(node.value)
    is cn.appia.im.core.messaging.InlineCode -> inlineToPreviewText(node.value)
    is cn.appia.im.core.messaging.InlineKaTeX -> node.value
    is cn.appia.im.core.messaging.Bold -> node.value.joinToString("") { inlineToPreviewText(it) }
    is cn.appia.im.core.messaging.Italic -> node.value.joinToString("") { inlineToPreviewText(it) }
    is cn.appia.im.core.messaging.Strike -> node.value.joinToString("") { inlineToPreviewText(it) }
    cn.appia.im.core.messaging.LineBreak -> ""
}

/**
 * 会话列表「最后一条消息」预览（RN resolveLastMessagePreview.ts:92-124 同序）：
 * 1. 草稿优先（plain 非空取 plain，否则 draft_message）
 * 2. 无 lastMessage / 无 `u` → roomItem_noMessage
 * 3. 特殊消息（pinned/jitsi/attachments/docCloud/oncall/meeting_room/forwardMergeMessage）
 * 4. 普通消息：发送人前缀（自己/rollback 无前缀）+ md 首个可见 block（RN inlinesFromFirstBlock
 *    语义：多行 msg 解析为多段，预览只取首段）；md 全程不可见时回退 `msg` 纯文本（换行替换为空格）；
 *    前后缀拼空 → roomItem_noMessage
 *
 * @param currentUserId RN 语义为 `user.username`（RoomListScreenInner.tsx:55，前缀按 username 判自己）；
 *   与分段/助手的 `user.id` 判定（ChatListViewModel）不是同一个值，UI 接线时注意分开取。
 * @param previewTableLabel RN lastMessagePreviewInlines 同名参数（总纲 §4.3-2）：表格段落（subType=TABLE）
 *   的预览替换文案；null/空 = 现行为（落穿取表格内联文本）。M3 表格预览接线时由 UI 传 i18n 文案。
 */
fun resolveLastMessagePreview(
    chat: ChatEntity,
    currentUserId: String?,
    previewTableLabel: String? = null,
): PreviewResult {
    val draft = chat.draft_message_plain?.takeIf { it.isNotEmpty() } ?: chat.draft_message
    if (!draft.isNullOrEmpty()) return PreviewResult.Text(draft)

    val lastMessage = parseLastMessageField(chat.last_message)
        ?: return PreviewResult.Template(prefix = "", key = KEY_NO_MESSAGE)
    if (lastMessage.u == null) return PreviewResult.Template(prefix = "", key = KEY_NO_MESSAGE)

    when (val special = formatSpecialMsg(lastMessage)) {
        is Special.Raw -> return PreviewResult.Text(special.text)
        is Special.Templated -> return PreviewResult.Template(
            prefix = if (special.withPrefix) senderPrefixFor(lastMessage, currentUserId) else "",
            key = special.key,
            args = special.args,
            translateArgs = special.translateArgs,
            brackets = special.brackets,
        )
        null -> Unit
    }

    val prefix = senderPrefixFor(lastMessage, currentUserId)
    // T13：预览走 resolveMdFromMsgFields（md 列直读 + stale 回退 msg 解析）+ RN 展平分支
    val mdText = previewInlineText(lastMessage.md, lastMessage.msg, previewTableLabel)
    if (mdText != null) return PreviewResult.Text(prefix + mdText)

    val body = lastMessage.msg?.replace("\n", " ") ?: ""
    val text = (prefix + body).trim()
    return if (text.isEmpty()) {
        PreviewResult.Template(prefix = "", key = KEY_NO_MESSAGE)
    } else {
        PreviewResult.Text(text)
    }
}
