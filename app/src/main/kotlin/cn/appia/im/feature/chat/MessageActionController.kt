package cn.appia.im.feature.chat

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.MessageStatus
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RecallApi
import cn.appia.im.feature.chat.ui.parseMessageUser
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** rollback-message 系统消息类型（RN message.t === 'rollback-message'）。 */
const val ROLLBACK_MESSAGE_TYPE = "rollback-message"

// ── 长按菜单判定（appiaMobile/src/services/messageActions.tsx 逐条移植）──

/**
 * RN MessageActionContext :36-44。默认值 = RN RoomScreen:423-427 的硬编码启发式
 * `{allowEditing:true, allowDeleting:true, editBlockMinutes:0, deleteBlockMinutes:0,
 * noOtherUserMessagesAfter:true}`（服务端权限开关 M7 前恒此值）。
 */
data class MessageActionContext(
    val currentUserId: String,
    val allowEditing: Boolean = true,
    val allowDeleting: Boolean = true,
    val editBlockMinutes: Int? = 0,
    val deleteBlockMinutes: Int? = 0,
    val noOtherUserMessagesAfter: Boolean = true,
)

/**
 * 菜单动作全集（RN getOptions :129-245 顺序）。待办（RN #3/4 setTodo/finishTodo）与摘要
 * （RN #8 summary）留 M7 占位——不进本判定集；表情动作 RN 菜单本就没有（T8 反应条在行内）。
 */
enum class MessageAction(
    val titleKey: String,
    val testId: String,
    val danger: Boolean = false,
) {
    REPLY("messageaction_reply", "reply"),
    EDIT("messageaction_edit", "edit"),
    COPY("messageaction_copy", "copy"),
    FORWARD("messageaction_forward", "forward"),
    MULTI_SELECT("messageaction_multiselect", "multi-select"),
    RECALL("messageaction_recall", "recall", danger = true),
    RESEND("messageaction_resend", "resend", danger = true),
}

/** RN isTimedOut :85-89：`blockMinutes<=0` 恒 false；否则 `now-ts > minutes*60s`。 */
fun isTimedOut(tsMs: Double, blockMinutes: Int?, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (blockMinutes == null || blockMinutes <= 0) return false
    return nowMs - tsMs.toLong() > blockMinutes * 60_000L
}

/** RN canRecallMessage :91-103：自己 + allowDeleting + 未超时 + 之后无他人消息。 */
fun canRecallMessage(
    message: MessageEntity,
    context: MessageActionContext,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    val senderId = parseMessageUser(message.u)._id
    val isOwn = senderId != null && senderId == context.currentUserId
    return isOwn &&
        context.allowDeleting &&
        !isTimedOut(message.ts, context.deleteBlockMinutes, nowMs) &&
        context.noOtherUserMessagesAfter
}

/** RN hasCopyableText :115-123：msg 非空，或 attachments[0].description 真值（非空串）。 */
fun hasCopyableText(message: MessageEntity): Boolean {
    if (!message.msg.isNullOrEmpty()) return true
    return runCatching {
        val arr = Json.parseToJsonElement(message.attachments.orEmpty()) as? JsonArray ?: return false
        val first = arr.firstOrNull() as? JsonObject ?: return false
        (first["description"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true
    }.getOrDefault(false)
}

/** RN onCopy :840-855：复制文本 = attachments[0].description 优先，否则 formatCopyText(msg)。 */
fun copyableText(message: MessageEntity): String =
    runCatching {
        val arr = Json.parseToJsonElement(message.attachments.orEmpty()) as? JsonArray
        ((arr?.firstOrNull() as? JsonObject)?.get("description") as? JsonPrimitive)
            ?.contentOrNull?.takeUnless { it.isEmpty() }
    }.getOrNull() ?: formatCopyText(message.msg)

/**
 * RN formatCopyText（lib/message/formatCopyText.ts 逐条）：
 * `<http://url|Text>` 链接取 Text、`[size-N:x]`/`[color-#hex:x]` 取 x、引用前缀 `[ ](url)` 剥除、
 * 空白折叠 trim。
 */
fun formatCopyText(msg: String?): String {
    if (msg.isNullOrEmpty()) return ""
    var text = msg
    text = text.replace(Regex("(?:<|<)((?:https|http)://[^|]+)\\|(.+?)(?=>|>)(?:>|>)")) { m -> m.groupValues[2] }
    text = text.replace(Regex("\\[size-\\d+:([^\\]]+)]")) { m -> m.groupValues[1].trim() }
    text = text.replace(Regex("\\[color-#[0-9A-Fa-f]{6}:([^\\]]+)]")) { m -> m.groupValues[1].trim() }
    text = text.replace(Regex("^\\[\\s*]\\(([^)]*)\\)\\s"), "")
    return text.replace(Regex("\\s+"), " ").trim()
}

/**
 * RN getOptions :129-245 全集（M7 占位项除外）。顺序：回复恒有 → 编辑（自己+允许+未超时
 * +之后无他人+非 videoconf）→ 复制（有可复制文本）→ 转发 → 多选 → 撤回（canRecall）→
 * 重发（自己 + ERROR 态）。
 */
fun getOptions(
    message: MessageEntity,
    context: MessageActionContext,
    nowMs: Long = System.currentTimeMillis(),
): List<MessageAction> {
    val options = mutableListOf<MessageAction>()
    val senderId = parseMessageUser(message.u)._id
    val isOwn = senderId != null && senderId == context.currentUserId
    val videoconf = message.t == "videoconf"

    options += MessageAction.REPLY

    if (isOwn &&
        context.allowEditing &&
        !isTimedOut(message.ts, context.editBlockMinutes, nowMs) &&
        context.noOtherUserMessagesAfter &&
        !videoconf
    ) {
        options += MessageAction.EDIT
    }

    if (hasCopyableText(message)) options += MessageAction.COPY

    options += MessageAction.FORWARD
    options += MessageAction.MULTI_SELECT

    if (canRecallMessage(message, context, nowMs)) options += MessageAction.RECALL

    if (isOwn && message.status?.toInt() == MessageStatus.ERROR) options += MessageAction.RESEND

    return options
}

/** RN isRoomReadOnly（lib/chat/isRoomReadOnly.ts）：`archived || ro` 拦长按菜单。 */
fun isRoomReadOnly(archived: Boolean?, ro: Boolean?): Boolean = archived == true || ro == true

// ── 撤回快照（lib/chat/recallOriginalContent.ts 逐字段）──

/** RN IOriginalContent :7-16：8 键恒在（空/缺省 → null），JSON.stringify 同形。 */
@Serializable
data class OriginalContent(
    val msg: String? = null,
    val md: String? = null,
    val attachments: String? = null,
    val files: String? = null,
    val mentions: String? = null,
    val tmid: String? = null,
    val tmsg: String? = null,
    val msgType: String? = null,
)

private val originalContentJson = Json { encodeDefaults = true; explicitNulls = true }

/** RN strOrNull :18-19：非空串才有值。 */
private fun strOrNull(v: String?): String? = v?.takeIf { it.isNotEmpty() }

/** RN serializeOriginalContent :25-37：8 键全量快照 → JSON 串。 */
fun serializeOriginalContent(message: MessageEntity): String = originalContentJson.encodeToString(
    OriginalContent(
        msg = strOrNull(message.msg),
        md = strOrNull(message.md),
        attachments = strOrNull(message.attachments),
        files = strOrNull(message.files),
        mentions = strOrNull(message.mentions),
        tmid = strOrNull(message.tmid),
        tmsg = strOrNull(message.tmsg),
        msgType = strOrNull(message.msg_type),
    ),
)

/** RN deserializeOriginalContent :43-69：originalContent 空/坏 JSON → null；否则最小快照。 */
fun deserializeOriginalContent(message: MessageEntity): OriginalContent? {
    val raw = message.original_content
    if (raw.isNullOrEmpty()) return null
    return runCatching { originalContentJson.decodeFromString<OriginalContent>(raw) }.getOrNull()
}

/** RN isReeditableRollback（SystemMessage/index.tsx:25-33）：rollback + 有快照 + 自己撤回。 */
fun isReeditableRollback(message: MessageEntity, currentUserId: String?): Boolean {
    if (message.t != ROLLBACK_MESSAGE_TYPE) return false
    if (message.original_content.isNullOrEmpty()) return false
    val senderId = parseMessageUser(message.u)._id
    return !senderId.isNullOrEmpty() && !currentUserId.isNullOrEmpty() && senderId == currentUserId
}

/**
 * 撤回动作（RN onRecall :863-889 doRecall 顺序）：**先**把原文快照写入 `original_content`
 * （RN captureRecalledOriginalContent 吞错不阻塞，:82-99），**再** POST message.recall；
 * 本地不硬删——等 DDP t='rollback-message' 回推由 MessageUpsert 落库。
 */
class RecallActions(private val sdk: RocketSdk, private val db: AppiaDatabase) {

    suspend fun recall(message: MessageEntity) {
        runCatching { db.messageDao().updateOriginalContent(message._id, serializeOriginalContent(message)) }
        RecallApi.recallMessage(sdk, message._id)
    }

    /** RN handleBatchRecall :981-1005：批量撤回不快照（RN 同），失败上抛由 UI 提示。 */
    suspend fun batchRecall(ids: List<String>) {
        RecallApi.messageBatchRecall(sdk, ids)
    }
}

// ── rollback 分组渲染数据（lib/message/applyDisplayMessageTransforms.ts :15-53）──

/** RN DisplayMessage :4-7：组首条带全组、组内其余条标 hidden。 */
data class DisplayMessage(
    val message: MessageEntity,
    val rollbackGroup: List<MessageEntity>? = null,
    val hiddenInRollbackGroup: Boolean = false,
)

/** RN rollbackerIdOf :9-13：rollbacker JSON `_id`，缺省回退 u `_id`。 */
private fun rollbackerIdOf(message: MessageEntity): String? =
    parseMessageUser(message.rollbacker.orEmpty())._id
        ?: parseMessageUser(message.u)._id

/**
 * RN applyDisplayMessageTransforms :15-49：连续 t='rollback-message' 且同 rollbacker 的消息
 * 归组——组首条带 `_rollbackGroup`（=组内全条），其余标 hidden；单条不成组。
 */
fun applyDisplayMessageTransforms(messages: List<MessageEntity>): List<DisplayMessage> {
    if (messages.isEmpty()) return emptyList()
    val result = messages.map { DisplayMessage(it) }.toMutableList()

    var i = 0
    while (i < messages.size) {
        if (messages[i].t != ROLLBACK_MESSAGE_TYPE) {
            i += 1
            continue
        }
        val rollbackerId = rollbackerIdOf(messages[i])
        var end = i
        while (end + 1 < messages.size &&
            messages[end + 1].t == ROLLBACK_MESSAGE_TYPE &&
            rollbackerIdOf(messages[end + 1]) == rollbackerId
        ) {
            end += 1
        }
        if (end > i) {
            val group = messages.subList(i, end + 1)
            result[i] = DisplayMessage(messages[i], rollbackGroup = group)
            for (j in i + 1..end) result[j] = DisplayMessage(messages[j], hiddenInRollbackGroup = true)
        }
        i = end + 1
    }
    return result
}

/** RN filterVisibleDisplayMessages :52-53。 */
fun filterVisibleDisplayMessages(messages: List<DisplayMessage>): List<DisplayMessage> =
    messages.filter { !it.hiddenInRollbackGroup }

// ── 回复引用（lib/message/composeQuotedMessage.ts 逐字）──

/**
 * RN composeQuotedMessageText :21-40：`[ ](permalink) [@sender ]plainText`。
 * permalink = `{server}/{group|channel|direct}/{rid}?msg={id}`（roomType p→group、c→channel、
 * d→direct、其余 group）；needMention = 他人消息 + 非 DM + 有 sender name（RN parseSenderName
 * name??username）。
 */
fun composeQuotedMessageText(
    plainText: String,
    replyingMessage: MessageEntity?,
    serverUrl: String,
    rid: String,
    roomType: String?,
    authUserId: String?,
): String {
    if (replyingMessage == null) return plainText

    val path = when (roomType) {
        "p" -> "group"
        "c" -> "channel"
        "d" -> "direct"
        else -> "group"
    }
    val permalink = "$serverUrl/$path/$rid?msg=${replyingMessage._id}"
    val sender = parseMessageUser(replyingMessage.u)
    val senderName = sender.name ?: sender.username ?: ""
    val needMention = sender._id != authUserId && roomType != "d" && senderName.isNotEmpty()

    return "[ ]($permalink) ${if (needMention) "@$senderName " else ""}$plainText"
}

// ── 批量撤回确认文案（lib/message/summarizeSenders.ts + buildBatchRecallTip.ts）──

/** RN SenderSummary :3-10。 */
data class SenderSummary(
    val allSenders: List<String>,
    val hasMe: Boolean,
    val selfName: String?,
    val total: Int,
)

/** RN buildBatchRecallTip :17-52 三态 key + 占位参数（含自己时固定显示「您」）。 */
data class BatchRecallTip(
    val key: String,
    val params: Map<String, String>,
)

/** RN summarizeSenders :21-46：按首次出现去重的发送者 name 列表 + 是否含自己。 */
fun summarizeSenders(messages: List<MessageEntity>, currentUserName: String?): SenderSummary {
    val seen = mutableSetOf<String>()
    val allSenders = mutableListOf<String>()
    var hasMe = false
    var selfName: String? = null
    for (m in messages) {
        val name = parseMessageUser(m.u).name ?: continue
        if (seen.add(name)) allSenders += name
        if (name == currentUserName) {
            hasMe = true
            selfName = name
        }
    }
    return SenderSummary(allSenders, hasMe, selfName, messages.size)
}

/** RN buildBatchRecallTip :17-52：≤1 人 / 2 人 / 3+ 人三种 key；user1/user2 同规则取「您」。 */
fun buildBatchRecallTip(summary: SenderSummary): BatchRecallTip {
    val (allSenders, hasMe, selfName, total) = summary
    val others = if (hasMe) allSenders.filter { it != selfName } else allSenders

    if (allSenders.size <= 1) {
        return BatchRecallTip(
            "multiselect_batchrecalltip1",
            mapOf("user" to (if (hasMe) "\u60a8" else allSenders.firstOrNull() ?: ""), "num" to total.toString()),
        )
    }
    if (allSenders.size == 2) {
        return BatchRecallTip(
            "multiselect_batchrecalltip2",
            mapOf(
                "user1" to (if (hasMe) "\u60a8" else allSenders[0]),
                "user2" to (if (hasMe) others.firstOrNull() ?: "" else allSenders[1]),
                "num" to total.toString(),
            ),
        )
    }
    return BatchRecallTip(
        "multiselect_batchrecalltip3",
        mapOf(
            "user1" to (if (hasMe) "\u60a8" else allSenders[0]),
            "user2" to (if (hasMe) others.firstOrNull() ?: "" else allSenders[1]),
            "userNum" to allSenders.size.toString(),
            "num" to total.toString(),
        ),
    )
}

// ── 多选 store（stores/messageMultiSelectStore.ts → StateFlow）──

/** RN MessageMultiSelectState（senderCounts 无消费者、RN 侧也是测试专用死态，不移植）。 */
data class MultiSelectState(
    val active: Boolean = false,
    val rid: String? = null,
    /** 选中序（RN append 语义）。 */
    val selectedIds: List<String> = emptyList(),
    val selectedMap: Map<String, MessageEntity> = emptyMap(),
)

/** RN useMessageMultiSelectStore :33-76 的 StateFlow 等价（enter/toggle/exit 同守卫）。 */
class MessageMultiSelectStore {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(MultiSelectState())
    val state: kotlinx.coroutines.flow.StateFlow<MultiSelectState> = _state

    /** RN enter :36-45：带初始一条进多选态。 */
    fun enter(rid: String, initial: MessageEntity) {
        _state.value = MultiSelectState(
            active = true,
            rid = rid,
            selectedIds = listOf(initial._id),
            selectedMap = mapOf(initial._id to initial),
        )
    }

    /** RN toggle :47-73：未激活/跨房忽略；已选移除（保持顺序），未选追加。 */
    fun toggle(msg: MessageEntity) {
        val s = _state.value
        if (!s.active) return
        if (s.rid != null && msg.rid != s.rid) return

        if (msg._id in s.selectedIds) {
            _state.value = s.copy(
                selectedIds = s.selectedIds - msg._id,
                selectedMap = s.selectedMap - msg._id,
            )
        } else {
            _state.value = s.copy(
                selectedIds = s.selectedIds + msg._id,
                selectedMap = s.selectedMap + (msg._id to msg),
            )
        }
    }

    /** RN exit :75。 */
    fun exit() {
        _state.value = MultiSelectState()
    }
}
