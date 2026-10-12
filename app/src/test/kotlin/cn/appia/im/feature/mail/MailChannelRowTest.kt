package cn.appia.im.feature.mail

import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.mail.LatestMailPreview
import cn.appia.im.feature.chatlist.ChatListSection
import cn.appia.im.feature.chatlist.ChatListSectionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟邮件行纯函数（buildMailChannelChatRow.ts + useMailChannelListRow.ts 移植）：
 * 预览文案、行字段映射（f/unread/lm/lastMessage）、注入（assistant 段去旧重排 / 无段新建置前）。
 */
class MailChannelRowTest {

    private val preview = LatestMailPreview(
        subject = "Q4 report",
        fromName = "Bob",
        from = "zhangsan@x.com",
        receivedAt = "2026-10-12T08:00:00.000Z",
        unread = true,
    )

    // ── formatMailChannelPreviewText（:12-21）──

    @Test
    fun `preview text joins fromName and subject with full-width colon`() {
        assertEquals("Bob：Q4 report", formatMailChannelPreviewText(preview))
    }

    @Test
    fun `preview text falls back to from address and drops blank names`() {
        assertEquals(
            "zhangsan@x.com：Q4 report",
            formatMailChannelPreviewText(preview.copy(fromName = "  ")),
        )
        assertEquals("Bob", formatMailChannelPreviewText(preview.copy(subject = "  ")))
        assertEquals("", formatMailChannelPreviewText(preview.copy(fromName = " ", from = " ", subject = " ")))
        assertEquals("", formatMailChannelPreviewText(null))
    }

    // ── buildMailChannelChatRow（:36-69 字段映射）──

    @Test
    fun `row maps pinned unread lm and lastMessage fields`() {
        val lm = 1_791_792_000_000.0 // 2026-10-12T08:00:00Z
        val row = buildMailChannelChatRow(pinned = true, unreadCount = 3, preview = preview)

        assertEquals(MAIL_CHANNEL_ID, row._id)
        assertEquals(MAIL_CHANNEL_ID, row.rid)
        assertTrue(row.f)
        assertEquals("c", row.t)
        assertEquals(3.0, row.unread, 0.0)
        assertTrue(row.alert)
        assertEquals(lm, row.lm!!, 1.0)
        assertEquals(lm, row.room_updated_at, 1.0)
        assertEquals("Bob：Q4 report", row.last_message)
        assertTrue(row.open)
    }

    @Test
    fun `row with no preview falls back to now activity and empty preview text`() {
        val before = System.currentTimeMillis()
        val row = buildMailChannelChatRow(pinned = false, unreadCount = 0, preview = null)

        assertEquals("", row.last_message)
        assertTrue(row.lm!! >= before) // activity = lm || now
        assertTrue(!row.alert)
        assertEquals(0.0, row.unread, 0.0)
    }

    @Test
    fun `row clamps negative unread count`() {
        val row = buildMailChannelChatRow(pinned = false, unreadCount = -5, preview = null)
        assertEquals(0.0, row.unread, 0.0)
    }

    @Test
    fun `isMailChannelRow matches only the placeholder id`() {
        assertTrue(isMailChannelRow(buildMailChannelChatRow(false, 0, null)))
        assertTrue(!isMailChannelRow(chatRow(_id = "real-room")))
    }

    // ── injectMailChannelRow（useMailChannelListRow.ts:39-66）──

    @Test
    fun `inject into existing assistant section replaces old mail row and sorts`() {
        val mail = buildMailChannelChatRow(pinned = true, unreadCount = 0, preview = preview)
        val agent = chatRow(_id = "__appia_agent_channel__", lm = 50.0)
        val stale = buildMailChannelChatRow(pinned = false, unreadCount = 9, preview = null) // 旧行
        val sections = listOf(
            ChatListSection(ChatListSectionKey.ASSISTANT, listOf(chatRow(_id = "x", lm = 10.0), stale)),
            ChatListSection(ChatListSectionKey.CHANNELS, listOf(chatRow(_id = "y", lm = 20.0))),
        )

        val out = injectMailChannelRow(sections, mail)

        assertEquals(2, out.size)
        val assistant = out[0]
        assertEquals(2, assistant.chats.size) // x + mail（旧行剔除、agent 不在原段）
        assertEquals(setOf("x", MAIL_CHANNEL_ID), assistant.chats.map { it._id }.toSet())
        assertEquals(MAIL_CHANNEL_ID, assistant.chats.first { it._id == MAIL_CHANNEL_ID }._id)
        // 置顶邮件行排最前
        assertEquals(MAIL_CHANNEL_ID, assistant.chats[0]._id)
    }

    @Test
    fun `inject prepends new assistant section when absent`() {
        val mail = buildMailChannelChatRow(false, 0, null)
        val sections = listOf(
            ChatListSection(ChatListSectionKey.TODO, listOf(chatRow(_id = "t", todoCount = 1.0))),
            ChatListSection(ChatListSectionKey.CHANNELS, listOf(chatRow(_id = "c"))),
        )

        val out = injectMailChannelRow(sections, mail)

        assertEquals(3, out.size)
        assertEquals(ChatListSectionKey.ASSISTANT, out[0].key)
        assertEquals(listOf(MAIL_CHANNEL_ID), out[0].chats.map { it._id })
    }

    @Test
    fun `inject with null row returns sections unchanged`() {
        val sections = listOf(ChatListSection(ChatListSectionKey.CHANNELS, listOf(chatRow(_id = "c"))))
        assertEquals(sections, injectMailChannelRow(sections, null))
        assertNull(null)
    }

    // ── mailPreviewReceivedAtMillis（:23-27）──

    @Test
    fun `receivedAt iso parse covers offset and plain forms`() {
        assertEquals(
            1_791_792_000_000L,
            mailPreviewReceivedAtMillis(preview.copy(receivedAt = "2026-10-12T08:00:00.000Z")),
        )
        assertEquals(
            1_791_792_000_000L,
            mailPreviewReceivedAtMillis(preview.copy(receivedAt = "2026-10-12T08:00:00+00:00")),
        )
        assertEquals(
            1_791_792_000_000L,
            mailPreviewReceivedAtMillis(preview.copy(receivedAt = "2026-10-12T08:00:00")),
        )
        assertEquals(0L, mailPreviewReceivedAtMillis(preview.copy(receivedAt = "not-a-date")))
        assertEquals(0L, mailPreviewReceivedAtMillis(null))
    }

    /** 测试行（ChatListViewModelTest.chatRow 同构的最小构造）。 */
    private fun chatRow(
        _id: String,
        lm: Double? = null,
        todoCount: Double? = null,
    ): ChatEntity = ChatEntity(
        _id = _id,
        f = false,
        t = "c",
        ts = 1.0,
        ls = 1.0,
        name = _id,
        fname = "",
        rid = _id,
        open = true,
        alert = false,
        unread = 0.0,
        user_mentions = 0.0,
        group_mentions = 0.0,
        lm = lm,
        room_updated_at = lm ?: 1.0,
        ro = false,
        archived = false,
        auto_translate_language = "",
        team_id = "",
        todoCount = todoCount,
    )
}
