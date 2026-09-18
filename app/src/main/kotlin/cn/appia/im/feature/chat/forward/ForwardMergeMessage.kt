package cn.appia.im.feature.chat.forward

import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 合并转发接收链纯逻辑（RN src/lib/message/forwardMergeMessage.ts 逐条移植）：
 * msgData 解析 / 标题组装 / msgData.messages → MessageEntity 映射（内层复用 M2 渲染行），
 * 以及转发选择页发送侧载荷组装（RN ForwardSelectScreen handleConfirm :356-390）。
 * 客户端**只解析不构造** msgData——内容由服务端组装。
 */

/** RN types/appiaMessage.ts:50-62 IForwardMsgData。 */
data class ForwardMsgData(
    val originRoomRid: String,
    val originRoomName: String?,
    val originRoomNames: List<String>,
    val messages: List<ForwardMessageItem>,
)

/** RN ForwardMessageItem（forwardMergeMessage.ts:7-14）：u/attachments 预序列化为 JSON 串供行列直用。 */
data class ForwardMessageItem(
    val _id: String? = null,
    val msg: String? = null,
    val u: String = "{}",
    val ts: JsonElement? = null,
    val attachments: String? = null,
    val msgType: String? = null,
    val msgData: String? = null,
    val md: String? = null,
    val t: String? = null,
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** RN parseForwardMsgData :16-23：null/空/坏 JSON → null。 */
fun parseForwardMsgData(raw: String?): ForwardMsgData? {
    if (raw.isNullOrEmpty()) return null
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val origin = obj["originRoom"] as? JsonObject
    val names = (origin?.get("names") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull && p.isString }?.content }
        .orEmpty()
    return ForwardMsgData(
        originRoomRid = origin?.str("rid") ?: "",
        originRoomName = origin?.str("name"),
        originRoomNames = names,
        messages = (obj["messages"] as? JsonArray)?.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            ForwardMessageItem(
                _id = o.str("_id"),
                msg = o.str("msg"),
                u = (o["u"] as? JsonObject)?.toString() ?: "{}",
                ts = o["ts"]?.takeIf { it !is JsonNull },
                attachments = (o["attachments"] as? JsonArray)?.toString(),
                msgType = o.str("msgType"),
                msgData = o.str("msgData"),
                md = o["md"]?.let { if (it is JsonNull) null else it.toString() },
                t = o.str("t"),
            )
        }.orEmpty(),
    )
}

/** {{x}} 占位替换（t() 的占位由调用方替换，见 I18n KDoc）。 */
internal fun interpolate(template: String, args: Map<String, String>): String =
    args.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }

/**
 * RN buildForwardMergeTitle :25-40：names 两名 →「{{name0}}和{{name1}}的聊天记录」、多名 →
 * 「{{name0}}等的聊天记录」、仅房名 →「{{roomName}}的聊天记录」、皆无 →「聊天记录」。
 * [t] 为 i18n 取词函数（key 小写，见 strings.xml）。
 */
fun buildForwardMergeTitle(data: ForwardMsgData, t: (String) -> String): String {
    val names = data.originRoomNames
    if (names.isNotEmpty()) {
        return if (names.size == 2) {
            interpolate(t("message_forwardmergetitle"), mapOf("name0" to names[0], "name1" to names[1]))
        } else {
            interpolate(t("message_forwardmergetitlemultiple"), mapOf("name0" to names[0]))
        }
    }
    val roomName = data.originRoomName
    if (!roomName.isNullOrEmpty()) {
        return interpolate(t("message_forwardmergetitleroom"), mapOf("roomName" to roomName))
    }
    return t("message_forwardmergetitlefallback")
}

/**
 * RN parseTimestampMs（lib/message/parseTimestamp.ts）：数值 <1e12 视为秒 ×1000、
 * 字符串走 ISO 解析、`{$date}` 取内层；不可解析 → null（映射时回退 index）。
 */
fun parseTimestampMs(ts: JsonElement?): Long? = when (ts) {
    null, is JsonNull, is JsonArray -> null
    is JsonObject -> (ts["\$date"] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && !it.isString }
        ?.content?.toDoubleOrNull()?.takeIf { !it.isNaN() }
        ?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() }
    is JsonPrimitive -> if (ts.isString) {
        // ChatMerger.parseIsoMillis：ISO 字符串解析（minSdk 24 无 java.time，repo 同裁定）
        ts.content.takeIf { it.isNotEmpty() }?.let { cn.appia.im.domain.chat.ChatMerger.parseIsoMillis(it) }
            ?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() }
    } else {
        ts.content.toDoubleOrNull()?.takeIf { !it.isNaN() }
            ?.let { if (it < 1e12) (it * 1000).toLong() else it.toLong() }
    }
}

/**
 * RN mapForwardMessagesToIMessages :50-73 → Android MessageEntity（复用 M2 渲染行）：
 * ts 回退 index、`_id` 回退 `forward-{originRid}-{index}-{ts}`、u 序列化串直传。
 */
fun mapForwardMessagesToEntities(
    messages: List<ForwardMessageItem>,
    originRid: String,
): List<MessageEntity> = messages.mapIndexed { index, item ->
    val ts = parseTimestampMs(item.ts) ?: index.toLong()
    MessageEntity(
        _id = item._id ?: "forward-$originRid-$index-$ts",
        rid = originRid,
        ts = ts.toDouble(),
        u = item.u,
        alias = "",
        parse_urls = "",
        _updated_at = ts.toDouble(),
        msg = item.msg,
        attachments = item.attachments,
        msg_type = item.msgType,
        msg_data = item.msgData,
        md = item.md,
        t = item.t,
    )
}

// ── 发送侧（RN ForwardSelectScreen :77-83/:356-390）──

const val FORWARD_MAX_SELECT = 10

/** RN isGroupChat :77-78：'p'（组）/ 'c'（频道）为群聊，其余（'d' 等）按 DM 处理。 */
fun isGroupChat(chat: ChatEntity): Boolean = chat.t == "p" || chat.t == "c"

/** RN parseChatUids（lib/chat/parseChatUids.ts）：uids JSON 列 → 字符串数组；坏 JSON → []。 */
fun parseChatUids(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

/** RN getDmPeerUserId :80-83：uids 中第一个非本人 id；找不到 → null。 */
fun resolveDmPeerUid(chat: ChatEntity, currentUserId: String?): String? =
    parseChatUids(chat.uids).firstOrNull { it != currentUserId }

/** 转发载荷目标（RN handleConfirm 的 forwardUsers/forwardRooms）。 */
data class ForwardTargets(val users: List<String>, val rooms: List<String>)

/**
 * RN handleConfirm :360-374 逐条：
 * 本地订阅内 DM → 对方 uid 并入 users、群聊 → rid 并入 rooms；本地无订阅的选中 rid
 * （服务端搜到）→ 直接并入 rooms 尾部；users = 已选 username 在前、DM peer 在后。
 */
fun buildForwardTargets(
    selectedRids: Collection<String>,
    selectedUserIds: Collection<String>,
    chats: List<ChatEntity>,
    currentUserId: String?,
): ForwardTargets {
    val byRid = chats.associateBy { it.rid }
    val selectedChats = selectedRids.mapNotNull(byRid::get)
    val dmPeerUserIds = selectedChats.filterNot(::isGroupChat)
        .mapNotNull { resolveDmPeerUid(it, currentUserId) }
    val groupRoomIds = selectedChats.filter(::isGroupChat).map { it.rid }
    val localRids = chats.mapTo(mutableSetOf()) { it.rid }
    val searchOnlyRoomIds = selectedRids.filterNot(localRids::contains)
    return ForwardTargets(
        users = selectedUserIds.toList() + dmPeerUserIds,
        rooms = groupRoomIds + searchOnlyRoomIds,
    )
}
