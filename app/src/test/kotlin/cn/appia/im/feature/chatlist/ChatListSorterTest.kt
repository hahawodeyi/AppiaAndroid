package cn.appia.im.feature.chatlist

import cn.appia.im.core.database.entity.ChatEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 移植 RN `src/lib/chat/sortRoomListChats.test.ts` + `chatListActivity.test.ts` 行为。
 * 比较器四层：置顶 f（不看 like）→ 草稿 → 未读（双方都有未读落时间）→ 活动时间倒序。
 */
class ChatListSorterTest {

    private fun ids(chats: List<ChatEntity>) = chats.map { it._id }

    @Test
    fun `pin only checks f, like is follow not pin`() {
        val plain = chatRow(_id = "plain", lm = 200.0)
        val liked = chatRow(_id = "liked", like = true, lm = 100.0)
        assertEquals("plain", ids(sortChatsForRoomList(listOf(liked, plain)))[0])
    }

    @Test
    fun `pinned f first even when plain is newer`() {
        val plain = chatRow(_id = "p", lm = 999.0)
        val pinned = chatRow(_id = "f", f = true, lm = 1.0)
        assertEquals(listOf("f", "p"), ids(sortChatsForRoomList(listOf(plain, pinned))))
    }

    @Test
    fun `draft before plain when f ties even if newer`() {
        val withDraft = chatRow(_id = "d", draft_message = "hi", lm = 1.0)
        val noDraft = chatRow(_id = "n", lm = 999.0)
        assertEquals(listOf("d", "n"), ids(sortChatsForRoomList(listOf(noDraft, withDraft))))
    }

    @Test
    fun `unread before read when pinned and no draft`() {
        val read = chatRow(_id = "r", f = true, lm = 999.0)
        val unread = chatRow(_id = "u", f = true, unread = 1.0, lm = 1.0)
        assertEquals(listOf("u", "r"), ids(sortChatsForRoomList(listOf(read, unread))))
    }

    @Test
    fun `draft ranks before unread`() {
        val draft = chatRow(_id = "d", f = true, draft_message = "x", lm = 1.0)
        val unread = chatRow(_id = "u", f = true, unread = 10.0, lm = 999.0)
        assertEquals(listOf("d", "u"), ids(sortChatsForRoomList(listOf(unread, draft))))
    }

    @Test
    fun `unread decides when only one side has unread`() {
        val read = chatRow(_id = "r", lm = 100.0)
        val unread = chatRow(_id = "u", unread = 3.0, lm = 1.0)
        assertEquals(listOf("u", "r"), ids(sortChatsForRoomList(listOf(read, unread))))
    }

    @Test
    fun `both sides unread falls to activity not count`() {
        val manyOld = chatRow(_id = "many", unread = 5.0, lm = 100.0)
        val fewNew = chatRow(_id = "few", unread = 1.0, lm = 200.0)
        assertEquals(listOf("few", "many"), ids(sortChatsForRoomList(listOf(manyOld, fewNew))))
    }

    @Test
    fun `activity uses max of lm chain and tSearch bump`() {
        val older = chatRow(_id = "older", lm = 100.0)
        val bumped = chatRow(_id = "bump", lm = 50.0, tSearch = 200.0)
        assertEquals(listOf("bump", "older"), ids(sortChatsForRoomList(listOf(older, bumped))))
    }

    @Test
    fun `activity follows lm chain not stale ts`() {
        val staleLm = chatRow(_id = "old", lm = 10.0, ts = 9_999_999.0)
        val freshLm = chatRow(_id = "new", lm = 500.0, ts = 1.0)
        assertEquals(listOf("new", "old"), ids(sortChatsForRoomList(listOf(staleLm, freshLm))))
    }

    @Test
    fun `missing lm falls back to ts`() {
        val later = chatRow(_id = "later", ts = 50.0)
        val earlier = chatRow(_id = "earlier", ts = 20.0)
        assertEquals(listOf("later", "earlier"), ids(sortChatsForRoomList(listOf(earlier, later))))
    }

    @Test
    fun `ts zero shortcircuits chain before subscriptionUpdatedAt`() {
        // RN ts 为必填 number，链上 updatedAt 仅在 ts 缺省时可达；Android ts 非空 Double，行为一致
        val zeroTs = chatRow(_id = "zero", ts = 0.0, subscription_updated_at = 30.0)
        val byTs = chatRow(_id = "ts", ts = 10.0)
        assertEquals(listOf("ts", "zero"), ids(sortChatsForRoomList(listOf(zeroTs, byTs))))
    }

    @Test
    fun `lm zero counts as present not missing`() {
        val zeroLm = chatRow(_id = "zero", lm = 0.0, ts = 999.0)
        val fallback = chatRow(_id = "fb", ts = 50.0)
        assertEquals(listOf("fb", "zero"), ids(sortChatsForRoomList(listOf(zeroLm, fallback))))
    }

    @Test
    fun `hideUnreadStatus zeroes unread then time decides`() {
        val muted = chatRow(_id = "m", hide_unread_status = true, unread = 99.0, lm = 10.0)
        val read = chatRow(_id = "r", lm = 20.0)
        assertEquals(listOf("r", "m"), ids(sortChatsForRoomList(listOf(muted, read))))
    }

    @Test
    fun `blank trim is not draft and plain alone counts`() {
        val whitespaceOnly = chatRow(_id = "ws", draft_message = "   ", lm = 5.0)
        val plainDraft = chatRow(_id = "pd", draft_message = "", draft_message_plain = "draft text", lm = 1.0)
        assertEquals(listOf("pd", "ws"), ids(sortChatsForRoomList(listOf(whitespaceOnly, plainDraft))))
    }

    @Test
    fun `sort returns new list without mutating input`() {
        val first = chatRow(_id = "first", lm = 1.0)
        val second = chatRow(_id = "second", lm = 2.0)
        val input = mutableListOf(first, second)
        sortChatsForRoomList(input)
        assertEquals(listOf("first", "second"), ids(input))
    }

    @Test
    fun `todo four buckets highDraft high defaultDraft default`() {
        val defaultOnly = chatRow(_id = "d", lm = 1.0, highTodoCount = 0.0, todoCount = 1.0)
        val highDraft = chatRow(_id = "hd", draft_message = "x", lm = 1.0, highTodoCount = 1.0)
        val highPlain = chatRow(_id = "h", lm = 9.0, highTodoCount = 2.0)
        val out = sortChatsForTodoSection(listOf(defaultOnly, highPlain, highDraft))
        assertEquals(listOf("hd", "h", "d"), ids(out))
    }

    @Test
    fun `default bucket draft before plain`() {
        val defaultPlain = chatRow(_id = "dp", lm = 9.0, highTodoCount = 0.0)
        val defaultDraft = chatRow(_id = "dd", draft_message = "y", lm = 1.0, highTodoCount = 0.0)
        assertEquals(
            listOf("dd", "dp"),
            ids(sortChatsForTodoSection(listOf(defaultPlain, defaultDraft))),
        )
    }

    @Test
    fun `todo bucket inner sort applies same comparator`() {
        val highPlain = chatRow(_id = "ha", lm = 1.0, highTodoCount = 1.0)
        val highPinned = chatRow(_id = "hb", f = true, lm = 1.0, highTodoCount = 1.0)
        assertEquals(
            listOf("hb", "ha"),
            ids(sortChatsForTodoSection(listOf(highPlain, highPinned))),
        )
    }
}
