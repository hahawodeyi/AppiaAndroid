package cn.appia.im.feature.chatlist

import cn.appia.im.core.database.entity.ChatEntity

/**
 * 会话列表排序纯函数，逐条移植 appiaMobile `src/lib/chat/sortRoomListChats.ts` +
 * `src/lib/chat/chatListActivity.ts`（与 Web `roomSort` 一致）：
 * 1. 置顶 `f`（**不看 like**——like 是关注频道，roomListClassification.ts:47）
 * 2. 有草稿（draft_message_plain || draft_message trim 非空）
 * 3. 未读（hide_unread_status → 0；**双方都有未读落时间比较，单方有才按未读**）
 * 4. `max(lm ?? ts ?? subscription_updated_at, tSearch)` 倒序
 */
fun compareChatsRoomList(a: ChatEntity, b: ChatEntity): Int {
    if (a.f != b.f) return if (b.f) 1 else -1

    val aDraft = hasDraftForSort(a)
    val bDraft = hasDraftForSort(b)
    if (aDraft != bDraft) return if (aDraft) -1 else 1

    val aUnread = effectiveUnread(a)
    val bUnread = effectiveUnread(b)
    // sortRoomListChats.ts:51：!(both>0) && (any!=0) 才按未读，否则落时间
    if (!(aUnread > 0 && bUnread > 0) && (aUnread != 0.0 || bUnread != 0.0)) {
        return bUnread.compareTo(aUnread)
    }

    return chatListActivityMillis(b).compareTo(chatListActivityMillis(a))
}

/** RN sortChatsForRoomList：不改动入参（`[...chats].sort`）。 */
fun sortChatsForRoomList(chats: List<ChatEntity>): List<ChatEntity> =
    chats.sortedWith(::compareChatsRoomList)

/**
 * 待办分区（sortRoomListChats.ts:62-78）：先按旧版四段
 * 高优+草稿 > 高优 > 普通+草稿 > 普通，每段内再按 compareChatsRoomList 排序。
 */
fun sortChatsForTodoSection(todos: List<ChatEntity>): List<ChatEntity> {
    val (high, default) = todos.partition { (it.highTodoCount ?: 0.0) > 0.0 }
    val (highDraft, highPlain) = high.partition(::hasDraftForSort)
    val (defaultDraft, defaultPlain) = default.partition(::hasDraftForSort)
    return listOf(highDraft, highPlain, defaultDraft, defaultPlain)
        .flatMap { it.sortedWith(::compareChatsRoomList) }
}

/**
 * RN chatListActivity.ts：`lm ?? ts ?? updatedAt`（updatedAt 即列 subscription_updated_at，
 * 见 types/subscription.ts:30），0 不是缺失（`??` 非 `||`）。
 */
fun chatListActivityMillis(chat: ChatEntity): Double =
    maxOf(
        chat.lm ?: chat.ts ?: chat.subscription_updated_at ?: 0.0,
        chat.tSearch ?: 0.0,
    )

/** RN hasDraftForSort + 派单裁定：plain || message trim 非空（useDraft 两者同时写，plain 优先取展示语义）。 */
internal fun hasDraftForSort(chat: ChatEntity): Boolean =
    !(chat.draft_message_plain?.takeIf { it.isNotBlank() } ?: chat.draft_message).isNullOrBlank()

/** RN effectiveUnread：hideUnreadStatus → 0。 */
private fun effectiveUnread(chat: ChatEntity): Double =
    if (chat.hide_unread_status == true) 0.0 else chat.unread
