package cn.appia.im.feature.chatlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.domain.presence.resolveDirectPeerUserId
import cn.appia.im.feature.chat.ui.presenceBadge
import coil3.compose.AsyncImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

// RN RoomChatItem 徽标/静音/草稿前缀的硬编码色（不走主题色板，index.tsx:101-141 同值）
private val BadgeBlue = Color(0xFF3677F2)
private val MuteUnreadBlue = Color(0xFF1B5BFF)
private val MuteGray = Color(0xFF9CA2A8)
private val TodoRed = Color.Red
private val StarGold = Color(0xFFFFBB00) // RN themeColors.favoriteBackground
private val Hairline = Color(0xFFE5E5E5)

/** RN tunreadLength.ts：tunread JSON 数组字符串 → 长度；空/坏 JSON/非数组 → 0。 */
internal fun tunreadLength(raw: String?): Int {
    if (raw.isNullOrEmpty()) return 0
    val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return 0
    return (el as? JsonArray)?.size ?: 0
}

/** RN chatFields.ts roomTitleFromChat：个人助手直连用 agentLabel，否则 fname → dname → name → _id。 */
internal fun roomTitleFromChat(chat: ChatEntity, currentUserId: String?, agentLabel: String): String {
    if (!currentUserId.isNullOrEmpty() && isSelfDirectAssistantChat(chat, currentUserId)) return agentLabel
    return chat.fname.trim().ifEmpty {
        chat.dname?.trim().orEmpty().ifEmpty { chat.name.trim().ifEmpty { chat._id } }
    }
}

/**
 * 列表行头像 URL（RN getAvatarUrl/formatUrl 会话列表路径等价，size 由调用方按渲染 dp×密度换算 px）：
 * `{server}/avatar/{name}?version=1&format=png&size={px}&rc_token=..&rc_uid=..&v={etag}`。
 * 鉴权参数缺一即省（服务器 `blockUnauthenticatedAccess` 默认 true，裸 URL 必 401——终审 Important-4）；
 * server/name 空白 → null（AsyncImage 不发请求，initial 垫底可见）。
 */
internal fun chatAvatarUrl(
    serverUrl: String,
    name: String,
    avatarEtag: String?,
    userId: String?,
    token: String?,
    sizePx: Int,
): String? {
    if (serverUrl.isBlank() || name.isBlank()) return null
    return buildString {
        append(serverUrl.trimEnd('/')).append("/avatar/").append(name)
        append("?version=1&format=png&size=").append(sizePx)
        if (!userId.isNullOrEmpty() && !token.isNullOrEmpty()) {
            append("&rc_token=").append(token).append("&rc_uid=").append(userId)
        }
        if (!avatarEtag.isNullOrEmpty()) append("&v=").append(avatarEtag)
    }
}

/**
 * 会话列表行（RN RoomChatItem/index.tsx 的 M2 最小版）：
 * - 头像 48dp：initial 垫底 + Coil AsyncImage（URL 由 [chatAvatarUrl] 构造：鉴权参数+etag v+size×密度；
 *   RN DirectAvatar 双人合成/appiaUsage 分支未移植）；置顶 `f` 叠金色星标。
 * - 首行：标题（`alert && !hideUnread` 加粗，alert = alert || tunread>0）+ 相对时间（未读主题色）。
 * - 二行：草稿前缀 > 提及前缀（user/group mentions>0）> 预览 > 静音图标（未读深蓝否则灰）> 未读徽标
 *   （hideUnread || unread<=0 不渲染；宽三档 16/24/28；>99 显 `99+`）。
 * 双身份参数：`currentUserId`（user.id，标题/自直接助手判定）与 `currentUsername`
 * （user.username，预览前缀判自己）不是同一个值（resolveLastMessagePreview KDoc 同注）。
 * RN 侧滑快捷（SwipeableChatRow 壳）、无障碍串、待办角标不在本行内（壳见 ChatRowActions.kt）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    chat: ChatEntity,
    currentUserId: String?,
    currentUsername: String?,
    avatarUrl: String?,
    onPress: (ChatEntity) -> Unit = {},
    onLongPress: (ChatEntity) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 提及 label 真名（M5-T4 / RN RoomItemLastMessage:44 UI_Use_Real_Name，缺行 true）。 */
    useRealName: Boolean = true,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current

    val hideUnread = chat.hide_unread_status == true || chat.disable_notifications == true
    val unread = chat.unread.toInt()
    val alert = chat.alert || tunreadLength(chat.tunread) > 0
    val highlighted = alert && !hideUnread
    val draft = chat.draft_message_plain?.takeIf { it.isNotEmpty() } ?: chat.draft_message
    val title = roomTitleFromChat(chat, currentUserId, context.t("Agent"))
    val date = formatRoomListTime(chatListActivityMillis(chat).toLong()) { key, args ->
        args.entries.fold(context.t(key)) { acc, (k, v) -> acc.replace("{{$k}}", v) }
    }
    val previewText = when (val p = resolveLastMessagePreview(chat, currentUsername, useRealName = useRealName)) {
        is PreviewResult.Text -> p.text
        is PreviewResult.Template -> {
            var s = context.t(p.key)
            // 仅 translateArgs 列出的占位是 i18n key（RN 构造处 t(kind) 的等价）；
            // jitsi 用户名、docCloud 文件名等原文原样，正文撞资源名（如 "agent"）也不会被误译
            for ((k, v) in p.args) {
                s = s.replace("{{$k}}", if (k in p.translateArgs) context.t(v) else v)
            }
            if (p.brackets) s = "[$s]"
            p.prefix + s
        }
    }

    Row(
        modifier = modifier
            .heightIn(min = 75.dp)
            .combinedClickable(onClick = { onPress(chat) }, onLongClick = { onLongPress(chat) })
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            // initial 垫底：Coil 网络模块就绪前头像位不空白；加载成功后被 AsyncImage 覆盖
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.chatComponentBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.trim().take(1).uppercase(),
                    color = colors.auxiliaryText,
                    fontSize = 18.sp,
                )
            }
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
            if (chat.f) {
                Text("★", Modifier.align(Alignment.TopEnd), color = StarGold, fontSize = 14.sp)
            }
            // M5-T3：单聊绿点（RN RoomAvatar 直连分支——resolveDirectPeerUserId 链：
            // uids 剔除自己取 peer；仅 store 链，无 fallback/username）
            presenceBadge(
                userId = if (chat.t == "d") resolveDirectPeerUserId(chat.uids, currentUserId) else null,
                username = null,
                fallbackStatus = null,
                avatarSize = 48.dp,
            )()
        }

        Column(Modifier.weight(1f).padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f, fill = false),
                    color = colors.titleText,
                    fontSize = 18.sp,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.weight(1f))
                if (date.isNotEmpty()) {
                    Text(
                        date,
                        color = if (highlighted) colors.tintColor else colors.auxiliaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // RN draftOrMentionPrefix：草稿 > 提及，红色 textTodo（16sp）
                val prefixText = when {
                    !draft.isNullOrEmpty() -> "[${context.t("roomItem_draft")}]"
                    chat.group_mentions > 0 || chat.user_mentions > 0 ->
                        "[${context.t("roomItem_someoneCalled")}] "
                    else -> null
                }
                if (prefixText != null) {
                    Text(prefixText, color = TodoRed, fontSize = 16.sp)
                }
                Text(
                    previewText,
                    Modifier.weight(1f),
                    color = colors.auxiliaryText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hideUnread) {
                    MuteGlyph(
                        color = if (unread > 0) MuteUnreadBlue else MuteGray,
                        modifier = Modifier.padding(start = 5.dp, end = 6.dp),
                    )
                }
                if (!hideUnread && unread > 0) {
                    UnreadBadge(unread)
                }
            }
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = Hairline,
        modifier = Modifier.padding(start = 14.dp + 58.dp, end = 16.dp),
    )
}

/** RN unreadPill（index.tsx:101-125）：宽三档 16/24/28，高 16 圆角 8，>99 显 `99+`。 */
@Composable
private fun UnreadBadge(unread: Int) {
    val width = when {
        unread > 99 -> 28
        unread > 9 -> 24
        else -> 16
    }
    Box(
        Modifier
            .padding(start = 5.dp, end = 10.dp)
            .size(width = width.dp, height = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BadgeBlue),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (unread > 99) "99+" else unread.toString(),
            color = Color.White,
            fontSize = 10.sp,
        )
    }
}

/** M2 占位图形（RN MuteIcon SVG 移植前）：圆 + 斜杠示意静音，颜色按未读态切换。 */
@Composable
private fun MuteGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val r = size.minDimension / 2 - stroke.width
        drawCircle(color = color, radius = r, style = stroke)
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.minDimension * 0.25f, size.minDimension * 0.25f),
            end = androidx.compose.ui.geometry.Offset(size.minDimension * 0.75f, size.minDimension * 0.75f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
