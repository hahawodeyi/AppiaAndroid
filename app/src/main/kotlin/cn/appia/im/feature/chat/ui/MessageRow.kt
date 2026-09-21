package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.media.FileUploadProgress
import cn.appia.im.core.messaging.MessageStatus
import cn.appia.im.core.messaging.MdNode
import cn.appia.im.core.messaging.ResolvedEmoji
import cn.appia.im.core.messaging.appendEditedTagToMd
import cn.appia.im.core.messaging.isMessageEdited
import cn.appia.im.core.messaging.resolveMessageMd
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.formatRoomMessageHeaderTime
import cn.appia.im.feature.chat.forward.ForwardMergeCard
import cn.appia.im.feature.chat.forward.MERGE_FORWARD_MSG_TYPE
import coil3.compose.AsyncImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** RN RoomMessageRow AVATAR_SIZE。 */
private val AVATAR_SIZE = 36.dp

/** RN RoomMessageRow/index.tsx bubbleBgColor：自己浅蓝，他人主题背景（不走色板）。 */
private val OwnBubbleColor = Color(0xFFCCE6FF)

/** RN roomMessageRowUtils ANNOUNCEMENT_TYPES：排除后与 RN 同走普通行渲染（非公告样式）。 */
private val ANNOUNCEMENT_TYPES =
    setOf("room_created_announcement", "room_changed_announcement", "room_deleted_announcement")

internal fun isAnnouncementType(t: String?): Boolean = t != null && t in ANNOUNCEMENT_TYPES

/** 该消息走系统消息渲染（有 t 且非 announcement；announcement 走普通行=RN；load_chunk 列表层短路）。 */
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

// ── 正文（md 渲染红线：resolveMessageMd 直读 md/msg 列，勿重 parse msg）──

internal data class MentionUser(val _id: String?, val username: String?, val name: String?)

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

// ── MENTION 显示名解析（总纲 §4.3-1：共享纯函数，M3-T5 行内管线复用同一 helper）──

/** 提及渲染色别（RN AtMention 数据源分支）。 */
internal enum class MentionKind {
    /** @all/@here → mentionGroupColor。 */
    GROUP,

    /** mentions 数组命中自己 → mentionMeColor。 */
    ME,

    /** mentions 数组命中他人 → mentionOtherColor。 */
    OTHER,

    /** mentions 数组未命中 → `@mention` 普通正文（无高亮）。 */
    UNRESOLVED,
}

/** 提及渲染判定产物：[label] 为显示文本（命中的显示名不带 @）。 */
internal data class MentionDisplay(val kind: MentionKind, val label: String)

/**
 * RN AtMention 数据源语义：@all/@here → 群色、label 原样；mentions 数组按 username 命中 →
 * 显示 `name || username || mention`（无 @），mention === 自己 username → mentionMeColor，
 * 否则 mentionOtherColor；未命中 → `@mention` 普通正文（空 mention 渲染为空）。
 * 纯函数、无 Compose 依赖：MessageRow 正文与 M3-T5 新行内管线共用，勿另写一份。
 */
internal fun resolveMentionDisplay(
    mentions: List<MentionUser>,
    mention: String,
    currentUsername: String?,
): MentionDisplay = when {
    mention == "all" || mention == "here" -> MentionDisplay(MentionKind.GROUP, mention)
    else -> mentions.find { it.username == mention }?.let { u ->
        val label = u.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: u.username?.trim().takeUnless { it.isNullOrEmpty() }
            ?: mention
        MentionDisplay(
            kind = if (mention == currentUsername) MentionKind.ME else MentionKind.OTHER,
            label = label,
        )
    } ?: MentionDisplay(MentionKind.UNRESOLVED, if (mention.isEmpty()) "" else "@$mention")
}

/**
 * 行内渲染环境装配（RN MessageBody props：mentions/username/baseUrl/getCustomEmoji）。
 * getCustomEmoji 由 RoomScreen 注入（T3 EmojiResolver 缝）；此处缺省 null 走查表文本。
 */
internal fun buildInlineEnv(
    mentions: List<MentionUser>,
    currentUsername: String?,
    baseUrl: String?,
    getCustomEmoji: ((String) -> ResolvedEmoji?)? = null,
    onLinkPress: ((String) -> Unit)? = null,
): InlineEnv = InlineEnv(
    mentions = mentions,
    currentUsername = currentUsername,
    getCustomEmoji = getCustomEmoji,
    baseUrl = baseUrl,
    onLinkPress = onLinkPress,
)

/**
 * 普通消息行（RN RoomMessageRow/index.tsx + RoomMessageRowAuthorHeader）：
 * 左头像 36dp 固定列 + 内容列（上排 发送者名 alias>`@loginName`/name/username + 时间
 * MM/DD HH:mm（跨年 YYYY/MM/DD HH:mm）+ `(UTC±x)`；下排 气泡（自己 #CCE6FF）+ 状态徽标：
 * QUEUED/SENDING 菊花、ERROR 红叹号点重发、SENT/null 无；已读回执图标（T10）。
 * T11：整行长按 → 长按菜单（RN Pressable delayLongPress=500 的 Compose combinedClickable
 * 为系统长按时长 ~400ms，行为一致）；多选态下点击切换选中并显示勾选列。
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
    /** 附件点击路由（图片/视频/音频/文档；T7）。 */
    onAttachmentNav: (AttachmentNav) -> Unit = {},
    /** 表情回应 toggle（T8；参数 = shortname。行内反应条点击 + picker 选中共用；长按菜单入口 T11）。 */
    onToggleReaction: (String) -> Unit = {},
    /** 合并转发卡片点击（T9）：(msgData 原文, 标题) → ForwardDetail 路由。 */
    onOpenForwardMerge: (String, String) -> Unit = { _, _ -> },
    /** 房间类型（T10 已读回执）：'d' 为 DM——不可点图标不渲染（绑定裁定#4）。 */
    roomType: String? = null,
    /** 已读回执可点图标（T10）：自己的消息 unread=true（非 DM）→ ReadReceipt 明细路由。 */
    onOpenReadReceipt: (MessageEntity) -> Unit = {},
    /** 长按菜单入口（T11）；多选态下 RoomScreen 不传（RN handleMessageLongPress inMultiSelect 早退）。 */
    onLongPress: (MessageEntity) -> Unit = {},
    /** 行点击（T11 多选态：切换选中；普通态 no-op）。 */
    onClick: (MessageEntity) -> Unit = {},
    /** 多选态选中显示（T11）：null=非多选态不渲染勾选列。 */
    selected: Boolean? = null,
    /** 自定义表情解析（T13/T3）：InlineEnv.getCustomEmoji 注入；null 走查表文本。 */
    getCustomEmoji: ((String) -> ResolvedEmoji?)? = null,
    /** 表格预览点击（T13）：TABLE 段落全屏 overlay 入口。 */
    onTableOpen: ((List<MdNode>) -> Unit)? = null,
    /** KaTeX 降级原式点击（T13）：单式查看 overlay 入口。 */
    onKatexClick: ((String) -> Unit)? = null,
    /** 附件重试（T13）：失败附件点击 → SendOrchestrator.retryFile。 */
    onRetryAttachment: (messageId: String, attachmentId: String) -> Unit = { _, _ -> },
) {
    val colors = LocalAppiaColors.current
    // pointerInput 捕获的是首个组合的 lambda：经 rememberUpdatedState 每次事件读最新回调，
    // 防 RoomScreen 侧守卫（多选态/只读房）变化后长按走旧判定
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnClick by rememberUpdatedState(onClick)
    val header = remember(message) { buildMessageHeaderDisplay(message) }
    val parsed = remember(message) { parseMessageUser(message.u) }
    val isOwn = !currentUserId.isNullOrEmpty() && parsed._id == currentUserId
    // size 请求值 = 渲染 dp×密度取整（RN formatUrl PixelRatio.get()*size 同款；台账 #9 顺带）
    val density = LocalDensity.current
    val avatarUrl = remember(message, serverUrl, token, currentUserId, density) {
        buildMessageSenderAvatarUrl(
            serverUrl,
            message.u,
            userId = currentUserId,
            token = token,
            size = with(density) { AVATAR_SIZE.roundToPx() },
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // pointerInput 而非 combinedClickable：后者会合并行内子节点语义（testTag 不可见，
            // ReactionBar/回执 UI 测试碎）；detectTapGestures 零语义变更。RN delayLongPress=500
            // 对应 Compose 系统长按时长（~400ms），行为一致。
            .pointerInput(message) {
                detectTapGestures(
                    onTap = { currentOnClick(message) },
                    onLongPress = { currentOnLongPress(message) },
                )
            }
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        // 多选勾选列（T11）：非多选态不占位
        if (selected != null) {
            Text(
                text = if (selected) "☑" else "☐",
                color = colors.primary,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 6.dp)
                    .testTag(if (selected) "qa-message-selected" else "qa-message-unselected"),
            )
        }
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
                // 气泡列：正文 + 附件（RN MessageBody：bubble 背景在含附件的容器上）
                Column(
                    Modifier
                        .weight(1f, fill = false) // RN messageContainer：随内容收缩、封顶可用宽
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isOwn) OwnBubbleColor else colors.backgroundColor)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("qa-room-message-body"),
                ) {
                    if (message.msg_type == MERGE_FORWARD_MSG_TYPE) {
                        // 合并转发卡片（T9）：替代正文/附件（RN AppiaMessage msgType 分发同位）
                        ForwardMergeCard(
                            msgData = message.msg_data,
                            onOpen = onOpenForwardMerge,
                        )
                    } else {
                        val context = LocalContext.current
                        // md 渲染红线：resolveMessageMd 直读 md/msg 列（勿重 parse msg）；
                        // 已编辑且 md 列非空 → appendEditedTagToMd 行内尾随（RN MessageBody :53-59，
                        // md 列口径判定——resolveMessageMd 在 md 空时回退 parse msg，根非空 ≠ 有 md）
                        val baseMd = remember(message) { resolveMessageMd(message) }
                        val md = if (isMessageEdited(message) && !message.md.isNullOrEmpty() && baseMd != null) {
                            remember(baseMd) { appendEditedTagToMd(baseMd, " ${context.t("edited")}") }
                        } else {
                            baseMd
                        }
                        val showEditedWithoutMd = isMessageEdited(message) && message.md.isNullOrEmpty()
                        val mentions = remember(message) { parseMentions(message.mentions) }
                        val env = remember(message, currentUsername, serverUrl, getCustomEmoji) {
                            buildInlineEnv(mentions, currentUsername, serverUrl, getCustomEmoji)
                        }
                        if (md != null) {
                            MessageBody(
                                root = md,
                                aiCodeBlock = message.msg_type == "ai_response",
                                onTableOpen = onTableOpen,
                                onKatexClick = onKatexClick,
                                env = env,
                            )
                        }
                        // 无 md 且已编辑：独立 (edited) 标记（RN showEditedWithoutMd = isEdited && !baseMd；
                        // 有 md 只行内尾随，不双渲染——评审 Critical-3）
                        if (showEditedWithoutMd) {
                            Text(
                                context.t("edited"),
                                color = colors.auxiliaryText,
                                fontSize = 13.sp,
                                modifier = Modifier.testTag("qa-message-edited-tag"),
                            )
                        }
                        MessageAttachmentsNode(
                            message = message,
                            currentUserId = currentUserId,
                            token = token,
                            serverUrl = serverUrl,
                            onNav = onAttachmentNav,
                            onRetry = onRetryAttachment,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                // 已读回执挂点（T10 / RN RoomMessageRow:213-229 + MessageReadReceipt）：仅自己消息 +
                // 已成功发送（T13 组装：QUEUED/SENDING/ERROR 未落服务端谈不上已读，RN isSent 门）+
                // 服务端 unread 列非空才渲染（本地产物 unread=null 无图标）；false 蓝色已读对勾；
                // true 可点图标进明细（DM 'd' 不渲染可点图标——绑定裁定#4；系统/公告行无 t 才可能命中）
                val isSent = message.status?.toInt() == null || message.status?.toInt() == MessageStatus.SENT
                if (isSent && isOwn && message.t.isNullOrEmpty() && !roomType.isNullOrEmpty()) {
                    when (message.unread) {
                        false -> MessageReadReceiptIcon(
                            read = true,
                            modifier = Modifier.padding(start = 4.dp).testTag("qa-read-receipt-read"),
                        )
                        true -> if (roomType != "d") {
                            MessageReadReceiptIcon(
                                read = false,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .clickable { onOpenReadReceipt(message) }
                                    .testTag("qa-read-receipt-unread"),
                            )
                        }
                        else -> Unit
                    }
                }
                // T13 组装：文件上传进度环（RN useFileUploadProgress + MessageStatusBadge.progress）
                val uploadProgress by FileUploadProgress.flow(message._id)
                    .collectAsState(initial = null)
                StatusBadge(
                    status = message.status?.toInt(),
                    onResend = { onResend(message) },
                    progress = uploadProgress,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // 行内反应条（T8）：reactions 非空才渲染；点击 chip/picker 项 → onToggleReaction(shortname)
            ReactionBar(
                reactionsJson = message.reactions,
                currentUsername = currentUsername,
                onToggle = onToggleReaction,
            )
        }
    }
}

/**
 * RN MessageStatusBadge：QUEUED/SENDING（文件上传中 [progress] 有值 → 圆形进度环；
 * 文本发送中 → 菊花）、ERROR 红叹号点重发、SENT/null 不渲染。
 */
@Composable
private fun StatusBadge(
    status: Int?,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
    progress: FileUploadProgress.Data? = null,
) {
    when (status) {
        MessageStatus.QUEUED, MessageStatus.SENDING -> if (progress != null) {
            UploadCircularProgress(percent = computeUploadPercent(progress), modifier = modifier)
        } else {
            CircularProgressIndicator(
                modifier = modifier
                    .size(16.dp)
                    .testTag("qa-message-status-loading"),
                strokeWidth = 2.dp,
            )
        }
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

/** RN computePercent：多文件按完成数+当前进度折算，单文件直取。 */
internal fun computeUploadPercent(p: FileUploadProgress.Data): Int =
    if (p.totalFiles > 1) {
        Math.round((p.completedFiles + p.currentFileProgress) / p.totalFiles * 100).toInt()
    } else {
        Math.round(p.currentFileProgress * 100).toInt()
    }

/**
 * RN CircularProgress：24dp 环宽 3dp，#007AFF 弧 + #E0E0E0 轨道，从 12 点方向（-90°）顺时针。
 */
@Composable
private fun UploadCircularProgress(percent: Int, modifier: Modifier = Modifier) {
    val clamped = percent.coerceIn(0, 100)
    Canvas(modifier.size(24.dp).testTag("qa-circular-progress")) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val radius = (size.minDimension - stroke.width) / 2f
        val center = size.minDimension / 2f
        drawCircle(color = Color(0xFFE0E0E0), radius = radius, center = Offset(center, center), style = stroke)
        drawArc(
            color = Color(0xFF007AFF),
            startAngle = -90f,
            sweepAngle = 360f * clamped / 100f,
            useCenter = false,
            topLeft = Offset(center - radius, center - radius),
            size = Size(radius * 2, radius * 2),
            style = stroke,
        )
    }
}

/**
 * 已读回执图标（RN MessageReadIcon 双勾 / MessageUnreadIcon 单勾 16dp 等价，Canvas 描边勾）：
 * read=true → colors.primary（蓝色已读），false → colors.tintColor（可点未读）。
 */
@Composable
private fun MessageReadReceiptIcon(read: Boolean, modifier: Modifier = Modifier) {
    val color = if (read) LocalAppiaColors.current.primary else LocalAppiaColors.current.tintColor
    Canvas(modifier.size(16.dp)) {
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun check(offsetX: Float) {
            drawPath(
                Path().apply {
                    moveTo(offsetX + 2.4.dp.toPx(), 8.2.dp.toPx())
                    lineTo(offsetX + 5.6.dp.toPx(), 11.2.dp.toPx())
                    lineTo(offsetX + 12.5.dp.toPx(), 4.4.dp.toPx())
                },
                color = color,
                style = stroke,
            )
        }
        check(0f)
        if (read) check(3.5.dp.toPx())
    }
}
