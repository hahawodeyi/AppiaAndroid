package cn.appia.im.feature.chatlist

import cn.appia.im.core.database.entity.ChatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * myAgents 虚拟行（RN useAgentChannelListRow + injectAgentChannelIntoSections 移植）：
 * 行构造形态 / 真实行让位 / 段注入三态 / rid 缓存键。
 */
class AgentChannelRowTest {

    private fun chat(
        _id: String,
        t: String = "c",
        uids: String? = null,
        usernames: String? = null,
        lm: Double = 0.0,
    ) = ChatEntity(
        _id = _id, f = false, t = t, ts = lm, ls = lm, name = _id, fname = "", rid = _id,
        open = true, alert = false, unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
        room_updated_at = lm, ro = false, archived = false, auto_translate_language = "",
        team_id = "", uids = uids, usernames = usernames,
    )

    @Test
    fun `virtual row shape classifies as self direct assistant`() {
        val row = buildAgentChannelChatRow("u-1")
        assertEquals(AGENT_CHANNEL_ID, row._id)
        assertEquals("d", row.t)
        assertEquals("""["u-1"]""", row.uids)
        assertEquals("""["agent.bot"]""", row.usernames)
        assertEquals(0.0, row.unread, 0.0)
        assertFalse(row.f)
        // 标题/分类判定复用段分类：命中自聊助手
        assertTrue(isSelfDirectAssistantChat(row, "u-1"))
        assertTrue(isAgentChannelRow(row))
        assertFalse(isAgentChannelRow(chat("rid-1")))
    }

    @Test
    fun `row is null when a real assistant chat exists or not logged in`() {
        val real = chat("real", t = "d", uids = """["u-1"]""")
        assertNull(agentChannelRowOrNull(listOf(real), "u-1"))
        assertNull(agentChannelRowOrNull(emptyList(), null))
        assertNull(agentChannelRowOrNull(emptyList(), ""))
        val row = agentChannelRowOrNull(listOf(chat("c1")), "u-1")
        assertTrue(row != null && isAgentChannelRow(row))
    }

    @Test
    fun `inject creates assistant section at front when missing`() {
        val sections = listOf(
            ChatListSection(ChatListSectionKey.CHANNELS, listOf(chat("c1"))),
        )
        val out = injectAgentChannelRow(sections, buildAgentChannelChatRow("u-1"))
        assertEquals(listOf(ChatListSectionKey.ASSISTANT, ChatListSectionKey.CHANNELS), out.map { it.key })
        assertEquals(listOf(AGENT_CHANNEL_ID), out[0].chats.map { it._id })
    }

    @Test
    fun `inject appends into existing assistant section without duplication`() {
        val real = chat("real", t = "d", uids = """["u-1"]""", lm = 10.0)
        val sections = listOf(ChatListSection(ChatListSectionKey.ASSISTANT, listOf(real)))
        val out = injectAgentChannelRow(sections, buildAgentChannelChatRow("u-1"))
        // 虚拟行 ts=now，随段排序活动时间最新在前（RN 注入后 compareChatsRoomList 同序）
        assertEquals(listOf(AGENT_CHANNEL_ID, "real"), out[0].chats.map { it._id })

        // 幂等：已含虚拟行不重复（RN 先 filter isAgentChannelChatRow）
        val again = injectAgentChannelRow(out, buildAgentChannelChatRow("u-1"))
        assertEquals(listOf(AGENT_CHANNEL_ID, "real"), again[0].chats.map { it._id })
    }

    @Test
    fun `null row returns sections untouched`() {
        val sections = listOf(ChatListSection(ChatListSectionKey.CHANNELS, listOf(chat("c1"))))
        assertTrue(injectAgentChannelRow(sections, null) === sections)
    }

    @Test
    fun `rid kv key strips trailing server slash`() {
        assertEquals(
            "AGEMNT_ROOM_ID_KEY_https://s1bob",
            agentRoomIdKvKey("https://s1/", "bob"),
        )
    }
}
