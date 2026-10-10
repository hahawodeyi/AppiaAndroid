package cn.appia.im.feature.chatlist

import cn.appia.im.core.chat.parseDirectRoomRid
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ChannelsApi

/**
 * 会话列表 myAgents 虚拟行（RN useAgentChannelListRow + buildAgentChannelChatRow 移植）：
 * 真实自聊 DM 不存在时注入固定「个人助手」行，使列表恒有入口；存在时由真实行承载（null）。
 * 点击 → [ensureAgentRoom]（KV 缓存 rid，缺则 im.create 自聊 DM）→ 携 fromAgent=true 进房。
 */

/** RN buildAgentChannelChatRow.ts AGENT_CHANNEL_ID：虚拟行本地占位 id（不落库、不发服务端）。 */
const val AGENT_CHANNEL_ID = "__appia_agent_channel__"

/** RN isAgentChannelChatRow：占位行判定（id 恒等即可，AA 虚拟行不进 DB 无 isAgentChannel 列）。 */
fun isAgentChannelRow(chat: ChatEntity): Boolean = chat._id == AGENT_CHANNEL_ID

/**
 * RN buildAgentChannelChatRow（buildAgentChannelChatRow.ts:24-53）：`t='d'` + `uids=[自己]` +
 * `usernames=['agent.bot']`，使 isSelfDirectAssistantChat 命中 → 标题走 agentLabel（t('Agent')）；
 * name='agent.bot' 使头像 URL 落 `/avatar/agent.bot`（RN getRoomAvatarShow 同源）。
 * 无未读、无预览、未置顶；uids/usernames 为 JSON 串（parseChatUids 走 JSON.parse）。
 */
fun buildAgentChannelChatRow(currentUserId: String): ChatEntity {
    val now = System.currentTimeMillis().toDouble()
    return ChatEntity(
        _id = AGENT_CHANNEL_ID,
        f = false,
        t = "d",
        ts = now,
        ls = now,
        name = "agent.bot",
        fname = "",
        rid = AGENT_CHANNEL_ID,
        open = true,
        alert = false,
        unread = 0.0,
        user_mentions = 0.0,
        group_mentions = 0.0,
        room_updated_at = now,
        ro = false,
        archived = false,
        auto_translate_language = "",
        team_id = "",
        uids = """["$currentUserId"]""",
        usernames = """["agent.bot"]""",
    )
}

/**
 * RN useAgentChannelListRow.ts:21-31：登录者非空且无真实自聊 DM → 虚拟行；否则 null
 * （真实行承载，避免重复；无登录恒 null）。
 */
fun agentChannelRowOrNull(chats: List<ChatEntity>, currentUserId: String?): ChatEntity? {
    if (currentUserId.isNullOrEmpty()) return null
    if (chats.any { isSelfDirectAssistantChat(it, currentUserId) }) return null
    return buildAgentChannelChatRow(currentUserId)
}

/**
 * RN injectAgentChannelIntoSections：虚拟行并入 assistant 段（先剔除旧行再随段排序）；
 * 无 assistant 段则新建置于最前。agentRow 为 null 时原样返回。
 */
fun injectAgentChannelRow(
    sections: List<ChatListSection>,
    agentRow: ChatEntity?,
): List<ChatListSection> {
    if (agentRow == null) return sections
    val assistantIndex = sections.indexOfFirst { it.key == ChatListSectionKey.ASSISTANT }
    if (assistantIndex >= 0) {
        return sections.mapIndexed { index, section ->
            if (index != assistantIndex) {
                section
            } else {
                ChatListSection(
                    section.key,
                    sortChatsForRoomList(section.chats.filterNot(::isAgentChannelRow) + agentRow),
                )
            }
        }
    }
    return listOf(ChatListSection(ChatListSectionKey.ASSISTANT, listOf(agentRow))) + sections
}

// ── myAgents 房间 rid 缓存（RN lib/chat/getMyLocalAgentRid.ts，MMKV 同款 KvStore）──

/** RN AGEMNT_ROOM_ID_KEY_<server><username>（拼写保留；server 去尾斜杠）。 */
fun agentRoomIdKvKey(serverUrl: String, username: String): String =
    "AGEMNT_ROOM_ID_KEY_" + serverUrl.trimEnd('/') + username

/** RN getMyLocalAgentRid：只读缓存，不 create。 */
fun getMyLocalAgentRid(kv: KvStore, username: String, serverUrl: String): String? {
    if (username.isEmpty() || serverUrl.isEmpty()) return null
    return kv.getString(agentRoomIdKvKey(serverUrl, username), "").ifEmpty { null }
}

/**
 * RN ensureAgentRoom（getMyLocalAgentRid.ts:47-58）：缓存命中直接返回；否则 `im.create`
 * 自聊 DM 并写缓存。失败抛错（调用方 toast roomList_agentCreateFailed）。
 */
suspend fun ensureAgentRoom(kv: KvStore, sdk: RocketSdk, username: String, serverUrl: String): String {
    check(username.isNotBlank() && serverUrl.isNotBlank()) { "ensureAgentRoom: missing username or serverUrl" }
    getMyLocalAgentRid(kv, username, serverUrl)?.let { return it }
    val rid = parseDirectRoomRid(ChannelsApi.createDirectRoom(sdk, username))
        ?: throw IllegalStateException("ensureAgentRoom: im.create returned no rid")
    kv.putString(agentRoomIdKvKey(serverUrl, username), rid)
    return rid
}
