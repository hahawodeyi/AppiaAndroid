package cn.appia.im.feature.chatlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 移植 RN `src/screens/RoomListScreen/groupChatsForRoomListSections.test.ts` 行为 +
 * 绑定裁定补充用例（搜索过滤/uids 判定/currentUserId 兜底）。
 */
class ChatListSectionerTest {

    private fun keys(sections: List<ChatListSection>) = sections.map { it.key }
    private fun chatIds(section: ChatListSection) = section.chats.map { it._id }

    @Test
    fun `empty input yields empty sections`() {
        assertEquals(emptyList<ChatListSection>(), buildRoomListSections(emptyList(), ""))
    }

    @Test
    fun `todoCount above zero goes todo rest channels`() {
        val todo = chatRow(_id = "1", todoCount = 2.0, lm = 100.0)
        val channel = chatRow(_id = "2", todoCount = 0.0, lm = 200.0)
        val sections = buildRoomListSections(listOf(todo, channel), "")
        assertEquals(listOf(ChatListSectionKey.TODO, ChatListSectionKey.CHANNELS), keys(sections))
        assertEquals(listOf("1"), chatIds(sections[0]))
        assertEquals(listOf("2"), chatIds(sections[1]))
    }

    @Test
    fun `missing todoCount treated as zero`() {
        val channel = chatRow(_id = "c", lm = 1.0)
        val sections = buildRoomListSections(listOf(channel), "")
        assertEquals(listOf(ChatListSectionKey.CHANNELS), keys(sections))
    }

    @Test
    fun `two uids with agent bot username go assistant`() {
        val uid = "user-x"
        val agent = chatRow(
            _id = "agent",
            t = "d",
            lm = 10.0,
            uids = """["$uid","other"]""",
            usernames = """["$uid","agent.bot"]""",
            name = "Real Name",
            fname = "Real Name",
        )
        val channel = chatRow(_id = "ch", lm = 20.0)
        val sections = buildRoomListSections(listOf(agent, channel), uid)
        assertEquals(listOf(ChatListSectionKey.ASSISTANT, ChatListSectionKey.CHANNELS), keys(sections))
        assertEquals(listOf("agent"), chatIds(sections[0]))
        assertEquals("roomList_sectionAssistant", sections[0].key.titleKey)
    }

    @Test
    fun `self direct single uid goes assistant`() {
        val uid = "user-x"
        val selfDm = chatRow(_id = "self", t = "d", lm = 5.0, uids = """["$uid"]""")
        val channel = chatRow(_id = "ch", lm = 10.0)
        val sections = buildRoomListSections(listOf(selfDm, channel), uid)
        assertEquals(listOf(ChatListSectionKey.ASSISTANT, ChatListSectionKey.CHANNELS), keys(sections))
        assertEquals(listOf("self"), chatIds(sections[0]))
    }

    @Test
    fun `two uids without agent bot stay channels`() {
        val uid = "user-x"
        val dm = chatRow(
            _id = "dm",
            t = "d",
            lm = 5.0,
            uids = """["$uid","alice"]""",
            usernames = """["$uid","alice"]""",
        )
        val sections = buildRoomListSections(listOf(dm), uid)
        assertEquals(listOf(ChatListSectionKey.CHANNELS), keys(sections))
    }

    @Test
    fun `direct with empty uids stays channels`() {
        val dm = chatRow(_id = "dm", t = "d", lm = 5.0)
        val sections = buildRoomListSections(listOf(dm), "user-x")
        assertEquals(listOf(ChatListSectionKey.CHANNELS), keys(sections))
    }

    @Test
    fun `missing currentUserId suppresses assistant section`() {
        val selfDm = chatRow(_id = "self", t = "d", lm = 5.0, uids = """["user-x"]""")
        val sections = buildRoomListSections(listOf(selfDm), null)
        assertEquals(listOf(ChatListSectionKey.CHANNELS), keys(sections))
    }

    @Test
    fun `assistant with todoCount stays assistant section`() {
        val uid = "user-x"
        val agentTodo = chatRow(
            _id = "agent",
            t = "d",
            todoCount = 3.0,
            lm = 1.0,
            uids = """["$uid"]""",
        )
        val sections = buildRoomListSections(listOf(agentTodo), uid)
        assertEquals(listOf(ChatListSectionKey.ASSISTANT), keys(sections))
    }

    @Test
    fun `search filters by fname case insensitive before grouping`() {
        val hit = chatRow(_id = "hit", fname = "Alpha Team", lm = 10.0)
        val miss = chatRow(_id = "miss", fname = "Beta", lm = 20.0)
        val sections = buildRoomListSections(listOf(hit, miss), "", "alpha")
        assertEquals(listOf(ChatListSectionKey.CHANNELS), keys(sections))
        assertEquals(listOf("hit"), chatIds(sections[0]))
    }

    @Test
    fun `search matches by name`() {
        val hit = chatRow(_id = "hit", name = "general", fname = "", lm = 10.0)
        val sections = buildRoomListSections(listOf(hit), "", "GENERAL")
        assertEquals(listOf("hit"), chatIds(sections[0]))
    }

    @Test
    fun `search with no match returns empty list`() {
        val chat = chatRow(_id = "c", fname = "Alpha", lm = 10.0)
        assertEquals(emptyList<ChatListSection>(), buildRoomListSections(listOf(chat), "", "zzz"))
    }

    @Test
    fun `channels section sorted by unified comparator`() {
        val pinnedOld = chatRow(_id = "pinned", f = true, lm = 1.0)
        val plainNew = chatRow(_id = "plain", lm = 100.0)
        val sections = buildRoomListSections(listOf(plainNew, pinnedOld), "")
        assertEquals(listOf("pinned", "plain"), chatIds(sections[0]))
    }
}
