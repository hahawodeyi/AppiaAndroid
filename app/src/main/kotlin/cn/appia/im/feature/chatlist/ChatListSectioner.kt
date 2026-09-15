package cn.appia.im.feature.chatlist

import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** RN roomListClassification.ts SELF_DIRECT_ASSISTANT_AVATAR_NAME（头像 `/avatar/agent.bot` 同源）。 */
private const val SELF_DIRECT_ASSISTANT_AVATAR_NAME = "agent.bot"

/** RN SubscriptionType.DIRECT：私聊 `t` 值。 */
private const val TYPE_DIRECT = "d"

/** RN RoomListSectionKey（groupChatsForRoomListSections.ts:10）三段互斥。 */
enum class ChatListSectionKey(val titleKey: String) {
    ASSISTANT("roomList_sectionAssistant"),
    TODO("roomList_sectionTodo"),
    CHANNELS("roomList_sectionChannels"),
}

/** 分段模型（RN RoomListSectionModel）：titleKey 沿用 RN i18n key 原名。 */
data class ChatListSection(
    val key: ChatListSectionKey,
    val chats: List<ChatEntity>,
)

/**
 * 会话列表三分段，移植 appiaMobile `src/screens/RoomListScreen/groupChatsForRoomListSections.ts:51-98`：
 * 个人助手（无段头语义的独立段）→ 待办（todoCount>0，段内四分）→ 普通频道；空段不返回。
 * @param currentUserId RN RoomListScreenInner.tsx:56 用 `user.id`（非 username）
 * @param searchText 非空时在分段前过滤（RN matchChatSearch：name/fname/显示标题，大小写不敏感）
 */
fun buildRoomListSections(
    chats: List<ChatEntity>,
    currentUserId: String?,
    searchText: String = "",
): List<ChatListSection> {
    val filtered = if (searchText.isNotBlank()) {
        chats.filter { matchChatSearch(it, searchText) }
    } else {
        chats
    }

    val assistants = filtered.filter { isSelfDirectAssistantChat(it, currentUserId) }
    val assistantIds = assistants.mapTo(HashSet()) { it._id }
    val pool = filtered.filter { it._id !in assistantIds }

    val todo = pool.filter(::hasRoomTodoCount)
    val channels = pool.filterNot(::hasRoomTodoCount)

    val out = mutableListOf<ChatListSection>()
    sortChatsForRoomList(assistants).takeIf { it.isNotEmpty() }?.let {
        out.add(ChatListSection(ChatListSectionKey.ASSISTANT, it))
    }
    sortChatsForTodoSection(todo).takeIf { it.isNotEmpty() }?.let {
        out.add(ChatListSection(ChatListSectionKey.TODO, it))
    }
    sortChatsForRoomList(channels).takeIf { it.isNotEmpty() }?.let {
        out.add(ChatListSection(ChatListSectionKey.CHANNELS, it))
    }
    return out
}

/**
 * RN isAgent（roomListClassification.ts:12-29）：直连且 `uids` 仅含当前用户；
 * 或 `uids` 两人且 `usernames` 含 `agent.bot`。currentUserId 空恒 false。
 */
fun isSelfDirectAssistantChat(chat: ChatEntity, currentUserId: String?): Boolean {
    if (currentUserId.isNullOrEmpty()) return false
    if (chat.t != TYPE_DIRECT) return false
    val uids = parseChatStringArray(chat.uids)
    if (uids.size == 1 && uids[0] == currentUserId) return true
    val usernames = parseChatStringArray(chat.usernames)
    if (uids.size == 2 && SELF_DIRECT_ASSISTANT_AVATAR_NAME in usernames) return true
    return false
}

/** RN hasRoomTodoCount：todoCount > 0（与 isRoomToDo 布尔并存时以计数为准）。 */
fun hasRoomTodoCount(chat: ChatEntity): Boolean = (chat.todoCount ?: 0.0) > 0.0

/**
 * RN matchChatSearch（groupChatsForRoomListSections.ts:24-41）：
 * name/fname 大小写不敏感包含，最后回退显示标题 roomTitleFromChat
 * （fname → dname → name → _id；此路径无 agentLabel 分支）。
 */
private fun matchChatSearch(chat: ChatEntity, query: String): Boolean {
    val lower = query.trim().lowercase()
    if (lower.isEmpty()) return true
    if (chat.name.lowercase().contains(lower)) return true
    if (chat.fname.lowercase().contains(lower)) return true
    val title = chat.fname.trim().ifEmpty {
        chat.dname?.trim().orEmpty().ifEmpty { chat.name.trim().ifEmpty { chat._id } }
    }
    return title.lowercase().contains(lower)
}

/** RN parseChatUids.ts：JSON 数组字符串 → 字符串列表；null/空/坏 JSON/非数组 → 空。 */
internal fun parseChatStringArray(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
