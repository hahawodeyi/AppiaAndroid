package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailComposeMode
import cn.appia.im.core.mail.MailDraft
import cn.appia.im.core.mail.MessageDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 撰写纯逻辑（mailComposeForm.ts + replyRecipients.ts + mailComposeAttachments.ts 移植验证）：
 * - replyAll 全进 to 去重去自己**无 cc**（坑 13）
 * - Re:/Fwd: 前缀去重
 * - 附件 10 个/25MB 上限 + 流式 base64
 * - dirty 判定/联想追加
 */
class MailComposeRecipientsTest {

    private fun detail(
        from: String = "a@x.com",
        to: List<String> = listOf("b@x.com"),
        cc: List<String> = emptyList(),
        subject: String = "hello",
    ) = MessageDetail(
        id = "m1", uid = 1, from = from, fromName = null, to = to, cc = cc,
        subject = subject, receivedAt = "2026-10-01T00:00:00Z", read = false, flagged = false,
        folder = "INBOX", size = 1,
    )

    // ── 坑 13：replyAll 收件人规则 ──

    @Test
    fun `replyAll merges from to cc into to deduped excluding self with empty cc`() {
        val r = buildReplyAllRecipients(
            detail(from = "a@x.com", to = listOf("b@x.com", "A@X.com"), cc = listOf("c@x.com", "b@x.com")),
            accountEmail = "me@x.com",
        )
        assertEquals(listOf("a@x.com", "b@x.com", "c@x.com"), r.to) // 大小写归一去重
        assertTrue(r.cc.isEmpty()) // 坑 13：cc 恒空
    }

    @Test
    fun `replyAll excludes self case-insensitively`() {
        val r = buildReplyAllRecipients(
            detail(from = "ME@x.com", to = listOf("me@x.com", "b@x.com"), cc = listOf("Me@X.com")),
            accountEmail = "me@x.com",
        )
        assertEquals(listOf("b@x.com"), r.to)
    }

    @Test
    fun `replyAll preserves original casing of first occurrence`() {
        val r = buildReplyAllRecipients(
            detail(from = "A@x.com", to = listOf("a@x.com")),
            accountEmail = null,
        )
        assertEquals(listOf("A@x.com"), r.to) // 首见原样，后续同 key 去重
    }

    @Test
    fun `replyAll skips blank entries`() {
        val r = buildReplyAllRecipients(
            detail(from = "", to = listOf("  ", "b@x.com"), cc = listOf("")),
            accountEmail = null,
        )
        assertEquals(listOf("b@x.com"), r.to)
    }

    @Test
    fun `replyAll empty account email keeps all`() {
        val r = buildReplyAllRecipients(
            detail(from = "a@x.com", to = listOf("a@x.com", "b@x.com")),
            accountEmail = "",
        )
        assertEquals(listOf("a@x.com", "b@x.com"), r.to) // exclude 空 → 不排任何人
    }

    @Test
    fun `reply uses from only with empty cc`() {
        val r = buildReplyRecipients(detail(from = "a@x.com", to = listOf("b@x.com"), cc = listOf("c@x.com")))
        assertEquals(listOf("a@x.com"), r.to)
        assertTrue(r.cc.isEmpty())
    }

    @Test
    fun `reply with blank from yields empty to`() {
        val r = buildReplyRecipients(detail(from = ""))
        assertTrue(r.to.isEmpty())
    }

    @Test
    fun `reply compose prefill routes by mode`() {
        val d = detail(from = "a@x.com", to = listOf("me@x.com", "b@x.com"), cc = listOf("c@x.com"))
        val reply = buildReplyComposePrefill(MailComposeMode.REPLY, d, "me@x.com")
        assertEquals("a@x.com", reply.toText)
        assertEquals("", reply.ccText)
        assertEquals("Re: hello", reply.subject)

        val replyAll = buildReplyComposePrefill(MailComposeMode.REPLY_ALL, d, "me@x.com")
        assertEquals("a@x.com, b@x.com, c@x.com", replyAll.toText) // 去自己
        assertEquals("", replyAll.ccText) // 坑 13
        assertEquals("Re: hello", replyAll.subject)
        assertEquals("", replyAll.body)
    }

    // ── 前缀去重 ──

    @Test
    fun `reply subject prefix dedup matrix`() {
        assertEquals("Re: hello", buildReplySubject("hello"))
        assertEquals("re: hello", buildReplySubject("re: hello")) // 小写已带前缀 → 原样透传
        assertEquals("RE: hello", buildReplySubject("RE: hello")) // 大写已带前缀 → 原样透传
        assertEquals("Re:  spaced", buildReplySubject("Re:  spaced")) // re: 后多空格
        assertEquals("Re: ", buildReplySubject("")) // 空 → 骨架
        assertEquals("Re: ", buildReplySubject("   "))
        assertEquals("Re: Fwd: hello", buildReplySubject("Fwd: hello")) // Fwd 前缀不算 Re
    }

    @Test
    fun `forward subject prefix dedup matrix`() {
        assertEquals("Fwd: hello", buildForwardSubject("hello"))
        assertEquals("fwd: hello", buildForwardSubject("fwd: hello")) // 已带前缀原样
        assertEquals("FWD: hello", buildForwardSubject("FWD: hello"))
        assertEquals("Fwd: ", buildForwardSubject(""))
        assertEquals("Fwd: Re: hello", buildForwardSubject("Re: hello")) // Re 前缀不算 Fwd
    }

    @Test
    fun `forward prefill from message keeps body and empty recipients`() {
        val p = buildForwardComposePrefillFromMessage(
            detail(subject = "Fwd: already", ).let { it.copy(bodyText = "original text") },
        )
        assertEquals("", p.toText)
        assertEquals("Fwd: already", p.subject) // 已带前缀不再叠
        assertEquals("original text", p.body)
    }

    @Test
    fun `draft prefill maps all three recipient lists`() {
        val draft = MailDraft(
            id = "d1", accountId = "a1",
            to = listOf("b@x.com"), cc = listOf("c@x.com"), bcc = listOf("e@x.com"),
            subject = "wip", bodyText = "draft body",
            createdAt = "2026-10-01T00:00:00Z", updatedAt = "2026-10-01T00:00:00Z",
        )
        val p = buildDraftComposePrefill(draft)
        assertEquals("b@x.com", p.toText)
        assertEquals("c@x.com", p.ccText)
        assertEquals("e@x.com", p.bccText)
        assertEquals("wip", p.subject)
        assertEquals("draft body", p.body)
    }

    // ── 文本域解析 ──

    @Test
    fun `parse email list splits on comma and semicolon with trim`() {
        assertEquals(listOf("a@x.com", "b@x.com"), parseEmailList("a@x.com, b@x.com"))
        assertEquals(listOf("a@x.com", "b@x.com"), parseEmailList("a@x.com;b@x.com"))
        assertEquals(listOf("a@x.com"), parseEmailList(" a@x.com "))
        assertTrue(parseEmailList("").isEmpty())
        assertTrue(parseEmailList(" , ; ").isEmpty())
    }

    @Test
    fun `append contact email respects trailing separators`() {
        assertEquals("a@x.com", appendContactEmail("", "a@x.com"))
        assertEquals("a@x.com, b@x.com", appendContactEmail("a@x.com", "b@x.com"))
        assertEquals("a@x.com, b@x.com", appendContactEmail("a@x.com,", "b@x.com")) // 尾缀直连
        assertEquals("a@x.com; b@x.com", appendContactEmail("a@x.com; ", "b@x.com"))
    }

    // ── dirty 判定 ──

    @Test
    fun `is dirty compares five fields and attachment count`() {
        val init = ComposeSnapshot("to", "cc", "bcc", "subj", "body")
        assertFalse(
            isComposeDirty(ComposeSnapshot("to", "cc", "bcc", "subj", "body"), init, 1, 1),
        )
        assertTrue(isComposeDirty(ComposeSnapshot("to2", "cc", "bcc", "subj", "body"), init, 1, 1))
        assertTrue(isComposeDirty(ComposeSnapshot("to", "cc", "bcc", "subj", "body"), init, 2, 1)) // 附件数变
        assertFalse(isComposeDirty(ComposeSnapshot("to", "cc", "bcc", "subj", "body"), null, 0, 0)) // 无初始 → 恒净
    }

    @Test
    fun `upsert request parses text fields and carries reply forward marks`() {
        val req = buildUpsertDraftRequest(
            "a1",
            ComposeSnapshot("a@x.com, b@x.com", "c@x.com", "", " subj ", "body"),
            replyToMessageId = "m9",
            forwardMessageId = null,
        )
        assertEquals("a1", req.accountId)
        assertEquals(listOf("a@x.com", "b@x.com"), req.to)
        assertEquals(listOf("c@x.com"), req.cc)
        assertTrue(req.bcc!!.isEmpty())
        assertEquals("subj", req.subject) // trim
        assertEquals("body", req.bodyText)
        assertEquals("m9", req.replyToMessageId)
        assertEquals(null, req.forwardMessageId)
    }
}
