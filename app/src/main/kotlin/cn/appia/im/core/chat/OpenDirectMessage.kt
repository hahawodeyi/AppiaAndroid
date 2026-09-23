package cn.appia.im.core.chat

import cn.appia.im.core.database.dao.ChatDao
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ChannelsApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 发起 DM 三段链（RN src/lib/chat/openDirectMessage.ts 逐段移植）：
 * 1. knownRid 校验：搜索结果 rid 可能是 spotlight 回退的 username，仅本地存在对应直聊行且
 *    usernames 含目标用户时才采用（跳过全表扫描）
 * 2. 本地 chats 扫描：t='d' 全表按 usernames JSON 含目标用户判定（isGroupChat 排除多人直聊）
 * 3. im.create：POST 建房（ChannelsApi.createDirectRoom——T8 已建，复用勿双实现）
 *
 * 导航（navigateToRoom reset）不在本层：Android 无 RN reset 协议，调用方拿 rid 后自行
 * nav.navigate(RoomRoute)；本层语义 = resolveDirectChatRid + openDirectMessage 的
 * 「解析 rid」半程（RN openDirectMessage 的导航半程归 MainActivity 装配）。
 */

/** RN localDirectChatRid：`_id?.trim() || rid?.trim() || id?.trim()`（ChatEntity 恒有 _id → 取 _id）。 */
private fun localDirectChatRid(chat: ChatEntity): String = chat._id.trim()

/** RN directChatIncludesUsername：usernames JSON 数组含 username；解析失败回退子串匹配。 */
internal fun directChatIncludesUsername(usernamesRaw: String?, username: String): Boolean {
    if (usernamesRaw == null) return false
    val parsed = parseJsonStringArray(usernamesRaw)
    if (parsed.isNotEmpty()) return username in parsed
    return usernamesRaw.contains(username)
}

/**
 * RN isGroupChat（chatFields.ts:47-53）：uids/usernames 任一解析为数组后长度 > 2。
 */
internal fun isGroupDirectChat(chat: ChatEntity): Boolean {
    val uids = parseJsonStringArray(chat.uids)
    if (uids.size > 2) return true
    return parseJsonStringArray(chat.usernames).size > 2
}

/** RN tryResolveKnownDirectRid：knownRid 本地可验证（存在 + t='d' + 非多人 + 含目标用户）才采用。 */
internal fun tryResolveKnownDirectRid(
    directChats: List<ChatEntity>,
    username: String,
    knownRid: String?,
): String? {
    val rid = knownRid?.trim().orEmpty()
    val user = username.trim()
    if (rid.isEmpty() || user.isEmpty() || rid == user) return null
    val chat = directChats.firstOrNull { localDirectChatRid(it) == rid } ?: return null
    if (chat.t != "d" || isGroupDirectChat(chat)) return null
    if (!directChatIncludesUsername(chat.usernames, user)) return null
    return localDirectChatRid(chat).ifEmpty { rid }
}

/** RN findLocalDirectChatRid：t='d' 全表扫 usernames 含目标用户（快速预过滤 raw contains）。 */
internal fun findLocalDirectChatRid(directChats: List<ChatEntity>, username: String): String? {
    for (chat in directChats) {
        val raw = chat.usernames
        if (raw != null && !raw.contains(username)) continue // RN :93 快速过滤
        if (!isGroupDirectChat(chat) && directChatIncludesUsername(raw, username)) {
            return localDirectChatRid(chat).ifEmpty { null }
        }
    }
    return null
}

/** RN im.create 响应取 rid：`result?.room?._id ?? result?.room?.rid`（im.ts createDirectRoom 返回）。 */
internal fun parseDirectRoomRid(raw: kotlinx.serialization.json.JsonElement?): String? {
    val room = (raw as? JsonObject)?.get("room") as? JsonObject ?: return null
    fun str(key: String): String? =
        (room[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.contentOrNull?.trim()
    return str("_id") ?: str("rid")
}

/** RN resolveDirectChatRid（knownRid → 本地 chats → im.create）；解析失败返回 null。 */
suspend fun resolveDirectChatRid(
    dao: ChatDao,
    sdk: RocketSdk,
    username: String,
    knownRid: String? = null,
): String? {
    val trimmed = username.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val directs = dao.getDirects()
        tryResolveKnownDirectRid(directs, trimmed, knownRid)
            ?: findLocalDirectChatRid(directs, trimmed)
            ?: parseDirectRoomRid(ChannelsApi.createDirectRoom(sdk, trimmed))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        // RN catch → console.warn + null（Android 静默；调用方 UI 层展示失败态）
        null
    }
}
