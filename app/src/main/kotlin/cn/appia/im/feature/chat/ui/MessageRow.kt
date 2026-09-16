package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.MessageStatus
import cn.appia.im.core.messaging.SystemMessageTexts
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.formatRoomMessageHeaderTime
import coil3.compose.AsyncImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** RN RoomMessageRow AVATAR_SIZE。 */
private val AVATAR_SIZE = 36.dp

/** RN RoomMessageRow/index.tsx bubbleBgColor：自己浅蓝，他人主题背景（不走色板）。 */
private val OwnBubbleColor = Color(0xFFCCE6FF)

/** RN roomMessageRowUtils ANNOUNCEMENT_TYPES：M2 走简化公告样式（SystemMessageText），M3 全渲染。 */
private val ANNOUNCEMENT_TYPES =
    setOf("room_created_announcement", "room_changed_announcement", "room_deleted_announcement")

internal fun isAnnouncementType(t: String?): Boolean = t != null && t in ANNOUNCEMENT_TYPES

/** 该消息走系统消息渲染（有 t 且非 announcement；load_chunk 在列表层短路）。 */
internal fun isSystemMessageRow(message: MessageEntity): Boolean =
    !message.t.isNullOrEmpty() && !isAnnouncementType(message.t)

// ── u JSON 解析（RN messageUserDisplay.parseMessageUserJson 等价）──

internal data class ParsedMessageUser(
    val _id: String? = null,
    val username: String? = null,
    val name: String? = null,
    val avatarEtag: String? = null,
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun parseMessageUser(uRaw: String): ParsedMessageUser =
    runCatching {
        val o = Json.parseToJsonElement(uRaw) as? JsonObject ?: JsonObject(emptyMap())
        ParsedMessageUser(
            _id = o.str("_id") ?: o.str("id"),
            username = o.str("username"),
            name = o.str("name"),
            avatarEtag = o.str("avatarEtag") ?: o.str("avatarETag"),
        )
    }.getOrDefault(ParsedMessageUser())

// ── 头部展示（RN buildMessageHeaderDisplay；M2 useRealName 恒 true＝RN UI_Use_Real_Name 缺省）──

internal data class MessageHeaderDisplay(
    val authorPrimary: String,
    val authorAliasSuffix: String? = null,
    val avatarFallbackLabel: String,
)

internal fun buildMessageHeaderDisplay(message: MessageEntity, useRealName: Boolean = true): MessageHeaderDisplay {
    val parsed = parseMessageUser(message.u)
    val name = parsed.name?.trim().orEmpty()
    val username = parsed.username?.trim().orEmpty()

    // RN resolveMessageHeaderAuthor roomSender 分支：fname || dname || name
    val roomSenderName = message.roomSender?.takeIf { it.isNotBlank() }
        ?.let { raw -> runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
        ?.let { o -> (o.str("fname") ?: o.str("dname") ?: o.str("name"))?.trim()?.ifEmpty { null } }
    if (roomSenderName != null) {
        return MessageHeaderDisplay(authorPrimary = roomSenderName, avatarFallbackLabel = "…")
    }

    // RN：loginName = (useRealName && name) || username || ''
    val loginName = (if (useRealName) name else "").ifEmpty { username }
    val alias = message.alias?.trim().orEmpty()
    return if (alias.isNotEmpty()) {
        MessageHeaderDisplay(
            authorPrimary = alias,
            authorAliasSuffix = loginName.ifEmpty { null }?.let { "@$it" },
            avatarFallbackLabel = avatarFallback(parsed, alias),
        )
    } else {
        // RN：loginName || name || username || '…'
        val primary = loginName.ifEmpty { name.ifEmpty { username } }.ifEmpty { "…" }
        MessageHeaderDisplay(authorPrimary = primary, avatarFallbackLabel = avatarFallback(parsed, primary))
    }
}

/** RN resolveAvatarFallbackLabel：username || name || authorName || '?'。 */
private fun avatarFallback(parsed: ParsedMessageUser, authorName: String): String =
    parsed.username?.trim().takeUnless { it.isNullOrEmpty() }
        ?: parsed.name?.trim().takeUnless { it.isNullOrEmpty() }
        ?: authorName.takeUnless { it.isEmpty() } ?: "?"

/**
 * 头像 URL（RN getMessageSenderAvatarUri 单聊路径等价）：
 * `/avatar/{username}?version=1&format=png&size=36&rc_token=..&rc_uid=..&v={etag}`；无 username → null。
 */
internal fun buildMessageSenderAvatarUrl(
    server: String,
    uRaw: String,
    userId: String?,
    token: String?,
    size: Int = 36,
): String? {
    if (server.isBlank()) return null
    val parsed = parseMessageUser(uRaw)
    val username = parsed.username?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val sb = StringBuilder(server.trimEnd('/'))
        .append("/avatar/").append(username)
        .append("?version=1&format=png&size=").append(size)
    if (!userId.isNullOrEmpty() && !token.isNullOrEmpty()) {
        sb.append("&rc_token=").append(token).append("&rc_uid=").append(userId)
    }
    parsed.avatarEtag?.takeIf { it.isNotEmpty() }?.let { sb.append("&v=").append(it) }
    return sb.toString()
}

// ── 正文 span（RN Markdown AtMention 简化：仅 MENTION 节点高亮，无 md 纯文本不高亮）──

internal data class MentionUser(val _id: String?, val username: String?, val name: String?)

internal sealed class BodySpan {
    data class Plain(val text: String) : BodySpan()

    /** user=null 表示 mentions 数组未命中（RN 渲染 `@mention` 普通正文）。 */
    data class Mention(val mention: String, val user: MentionUser?) : BodySpan()
}

/** RN parseMentions：mentions JSON 数组 → 用户列表；空/坏 JSON → []。 */
internal fun parseMentions(raw: String?): List<MentionUser> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val el = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        el.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            MentionUser(_id = o.str("_id"), username = o.str("username"), name = o.str("name"))
        }
    }.getOrDefault(emptyList())
}

internal fun parseBodySpans(msg: String?, md: String?, mentions: List<MentionUser>): List<BodySpan> {
    if (md.isNullOrBlank()) return listOf(BodySpan.Plain(msg ?: ""))
    val out = mutableListOf<BodySpan>()
    runCatching { walkMd(Json.parseToJsonElement(md), mentions, out) }
    return out.ifEmpty { listOf(BodySpan.Plain(msg ?: "")) }
}

private fun walkMd(el: JsonElement, mentions: List<MentionUser>, out: MutableList<BodySpan>) {
    when (el) {
        is JsonArray -> el.forEach { walkMd(it, mentions, out) }
        is JsonObject -> when (el.str("type")) {
            "MENTION_USER", "MENTION_CHANNEL" -> {
                val mention = el["value"]?.textContent().orEmpty()
                out += BodySpan.Mention(mention, mentions.find { it.username == mention })
            }
            "PLAIN_TEXT" -> out += BodySpan.Plain(el["value"]?.textContent() ?: "")
            // 结构节点（PARAGRAPH 等）按序展开内联 children
            else -> el["value"]?.let { walkMd(it, mentions, out) }
        }
        else -> Unit
    }
}

private fun JsonElement.textContent(): String = when (this) {
    is JsonPrimitive -> content
    is JsonObject -> this["value"]?.textContent().orEmpty()
    is JsonArray -> joinToString("") { it.textContent() }
}

/**
 * 正文 AnnotatedString。RN AtMention 数据源语义：@all/@here → mentionGroupColor；
 * mentions 数组命中自己（mention===username）→ mentionMeColor、他人 → mentionOtherColor
 * （label 显示 name||username，不带 @）；未命中 → `@mention` 普通正文。
 */
@Composable
internal fun buildMessageBody(message: MessageEntity, currentUsername: String?): AnnotatedString {
    val colors = LocalAppiaColors.current
    val spans = parseBodySpans(message.msg, message.md, parseMentions(message.mentions))
    return buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is BodySpan.Plain -> append(span.text)
                is BodySpan.Mention -> when {
                    span.mention == "all" || span.mention == "here" ->
                        withStyle(SpanStyle(color = colors.mentionGroupColor, fontWeight = FontWeight.Medium)) {
                            append(span.mention)
                        }
                    span.user != null -> {
                        val label = span.user.name?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: span.user.username?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: span.mention
                        val color =
                            if (span.mention == currentUsername) colors.mentionMeColor else colors.mentionOtherColor
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) { append(label) }
                    }
                    span.mention.isNotEmpty() -> append("@${span.mention}")
                }
            }
        }
    }
}

/**
 * 普通消息行（RN RoomMessageRow/index.tsx + RoomMessageRowAuthorHeader）：
 * 左头像 36dp 固定列 + 内容列（上排 发送者名 alias>`@loginName`/name/username + 时间
 * MM/DD HH:mm（跨年 YYYY/MM/DD HH:mm）+ `(UTC±x)`；下排 气泡（自己 #CCE6FF）+ 状态徽标：
 * QUEUED/SENDING 菊花、ERROR 红叹号点重发、SENT/null 无；已读回执占位 M3）。
 */
@Composable
fun MessageRow(
    message: MessageEntity,
    currentUserId: String?,
    currentUsername: String?,
    serverUrl: String,
    token: String?,
    onResend: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val header = remember(message) { buildMessageHeaderDisplay(message) }
    val parsed = remember(message) { parseMessageUser(message.u) }
    val isOwn = !currentUserId.isNullOrEmpty() && parsed._id == currentUserId
    val avatarUrl = remember(message, serverUrl, token, currentUserId) {
        buildMessageSenderAvatarUrl(serverUrl, message.u, userId = currentUserId, token = token)
    }

    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        // 头像固定列：initial 垫底 + Coil AsyncImage（鉴权 rc_token/rc_uid + etag v）
        Box(Modifier.padding(top = 4.dp).size(AVATAR_SIZE), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(AVATAR_SIZE).clip(CircleShape).background(colors.chatComponentBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    header.avatarFallbackLabel.take(1).uppercase(),
                    color = colors.auxiliaryText,
                    fontSize = 15.sp,
                )
            }
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(AVATAR_SIZE).clip(CircleShape),
                )
            }
        }

        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    header.authorPrimary,
                    color = colors.titleText,
                    fontSize = 13.sp,
                    lineHeight = 22.sp,
                    maxLines = 1,
                    modifier = Modifier.testTag("qa-room-message-author"),
                )
                header.authorAliasSuffix?.let { suffix ->
                    Text(
                        suffix,
                        color = colors.auxiliaryText,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        maxLines = 1,
                    )
                }
                Text(
                    formatRoomMessageHeaderTime(message.ts.toLong()),
                    color = colors.auxiliaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .testTag("qa-room-message-time"),
                )
            }

            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = buildMessageBody(message, currentUsername),
                    color = colors.bodyText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .weight(1f, fill = false) // RN messageContainer：随内容收缩、封顶可用宽
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isOwn) OwnBubbleColor else colors.backgroundColor)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("qa-room-message-body"),
                )
                StatusBadge(
                    status = message.status?.toInt(),
                    onResend = { onResend(message) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** RN MessageStatusBadge：QUEUED/SENDING 菊花、ERROR 红叹号点重发、SENT/null 不渲染。 */
@Composable
private fun StatusBadge(status: Int?, onResend: () -> Unit, modifier: Modifier = Modifier) {
    when (status) {
        MessageStatus.QUEUED, MessageStatus.SENDING -> CircularProgressIndicator(
            modifier = modifier
                .size(16.dp)
                .testTag("qa-message-status-loading"),
            strokeWidth = 2.dp,
        )
        MessageStatus.ERROR -> Text(
            "!",
            color = Color(0xFFF5455C),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = modifier
                .size(20.dp)
                .testTag("qa-message-status-error")
                .clickable(onClick = onResend),
        )
        else -> Unit
    }
}
