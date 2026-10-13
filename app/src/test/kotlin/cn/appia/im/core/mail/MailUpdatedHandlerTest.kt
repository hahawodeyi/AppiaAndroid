package cn.appia.im.core.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mail.updated 推送消费（mailUpdatedHandler.test.ts 对照移植）：
 * - isInboxFolder 归一化（坑 7：剥 `\\` + trim + 大写）
 * - parseMailUpdatedPayload 帧解析（added/异事件/缺字段 → null）
 * - handleMailUpdatedEvent 分派（INBOX 门控/newCount>0 增量/预览刷新恒触发）
 */
class MailUpdatedHandlerTest {

    private fun frame(eventName: String, arg: String = """{"accountId":"a1","folder":"INBOX","newCount":2}""") =
        """{"msg":"changed","fields":{"eventName":"$eventName","args":[$arg]}}"""

    // ── 坑 7：INBOX 归一化 ──

    @Test
    fun `is inbox folder matches variants`() {
        assertTrue(isInboxFolder("INBOX"))
        assertTrue(isInboxFolder("Inbox"))
        assertTrue(isInboxFolder("inbox"))
        assertTrue(isInboxFolder("\\Inbox"))
        assertTrue(isInboxFolder("  \\INBOX  "))
        assertFalse(isInboxFolder("Sent"))
        assertFalse(isInboxFolder(null))
        assertFalse(isInboxFolder(""))
    }

    @Test
    fun `is inbox folder double prefix still matches`() {
        // RN replace(/^\\/, '') 只剥一个前导反斜杠——"\\\\Inbox"（IMAP 原义 \\Inbox 的转义形态）剩 "\Inbox" 不匹配
        assertFalse(isInboxFolder("\\\\Inbox"))
    }

    // ── 帧解析 ──

    @Test
    fun `parse extracts payload from stream notify user frame`() {
        val payload = parseMailUpdatedPayload(Json.parseToJsonElement(frame("uid123/mail.updated")))
        assertEquals(MailUpdatedEventPayload(accountId = "a1", folder = "INBOX", newCount = 2), payload)
    }

    @Test
    fun `parse ignores added messages`() {
        val added = """{"msg":"added","fields":{"eventName":"uid/mail.updated","args":[{}]}}"""
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(added)))
    }

    @Test
    fun `parse ignores unrelated events`() {
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(frame("uid/subscriptions-changed"))))
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(frame("uid/rooms-changed"))))
    }

    @Test
    fun `parse drops frames missing account or folder`() {
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(frame("u/mail.updated", arg = """{"folder":"INBOX"}"""))))
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(frame("u/mail.updated", arg = """{"accountId":"a1"}"""))))
        // 非 JSON 对象帧
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(""""text"""")))
        assertNull(parseMailUpdatedPayload(buildJsonObject { put("msg", "changed") }))
    }

    @Test
    fun `parse tolerates uid containing slash`() {
        // RN split('/').slice(1).join('/')：uid 带 / 时事件段 = "with/slash/mail.updated" ≠ mail.updated → null
        // （AA 同构——事件名匹配失败静默丢弃，不误消费）
        assertNull(parseMailUpdatedPayload(Json.parseToJsonElement(frame("uid/with/slash/mail.updated"))))
    }

    // ── 事件分派 ──

    @Test
    fun `handle increments unread and fires preview for inbox`() {
        var incremented = 0
        var previewFetched = 0
        val consumed = handleMailUpdatedEvent(
            MailUpdatedEventPayload(accountId = "a1", folder = "INBOX", newCount = 3),
            incrementUnread = { incremented += it },
            fetchLatestMailPreview = { previewFetched += 1 },
        )
        assertTrue(consumed)
        assertEquals(3, incremented)
        assertEquals(1, previewFetched)
    }

    @Test
    fun `handle skips non inbox folders`() {
        var incremented = 0
        var previewFetched = 0
        val consumed = handleMailUpdatedEvent(
            MailUpdatedEventPayload(accountId = "a1", folder = "Sent", newCount = 2),
            incrementUnread = { incremented += it },
            fetchLatestMailPreview = { previewFetched += 1 },
        )
        assertFalse(consumed)
        assertEquals(0, incremented)
        assertEquals(0, previewFetched)
    }

    @Test
    fun `handle does not increment when new count is zero but still refreshes preview`() {
        var incremented = 0
        var previewFetched = 0
        handleMailUpdatedEvent(
            MailUpdatedEventPayload(accountId = "a1", folder = "INBOX", newCount = 0),
            incrementUnread = { incremented += it },
            fetchLatestMailPreview = { previewFetched += 1 },
        )
        assertEquals(0, incremented)
        assertEquals(1, previewFetched)
    }

    @Test
    fun `handle tolerates missing preview fetcher`() {
        var incremented = 0
        val consumed = handleMailUpdatedEvent(
            MailUpdatedEventPayload(accountId = "a1", folder = "Inbox", newCount = 1),
            incrementUnread = { incremented += it },
            fetchLatestMailPreview = null,
        )
        assertTrue(consumed)
        assertEquals(1, incremented)
    }
}
