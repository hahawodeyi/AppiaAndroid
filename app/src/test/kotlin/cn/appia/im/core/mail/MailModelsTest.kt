package cn.appia.im.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MailModels 全量映射（types/mail.ts 逐字段）+ 详情归一化（normalizeMailMessageDetail.ts 五组别名/前缀剥离）。
 */
class MailModelsTest {

    @Test
    fun `account - full payload parses with optional lastSyncAt`() {
        val a = mailJson.decodeFromString<MailAccount>(
            """{"id":"acc1","email":"u@x.com","active":true,"lastSyncAt":"2026-10-01T00:00:00Z","createdAt":"2026-09-01T00:00:00Z"}""",
        )
        assertEquals("acc1", a.id)
        assertEquals(true, a.active)
        assertEquals("2026-10-01T00:00:00Z", a.lastSyncAt)

        val b = mailJson.decodeFromString<MailAccount>(
            """{"id":"acc2","email":"v@x.com","active":false,"createdAt":"2026-09-01T00:00:00Z"}""",
        )
        assertNull(b.lastSyncAt)
    }

    @Test
    fun `message - list row parses all fields including optional thread ids`() {
        val m = mailJson.decodeFromString<MailMessage>(
            """
            {"id":"mail-m1","uid":42,"from":"a@x.com","fromName":"Alice","to":["b@x.com"],"cc":[],
             "subject":"hi","receivedAt":"2026-10-01T01:02:03Z","read":true,"flagged":false,
             "folder":"INBOX","size":123,"threadId":"t1","conversationId":"c9","replyCount":3}
            """.trimIndent(),
        )
        assertEquals("mail-m1", m.id)
        assertEquals(42L, m.uid)
        assertEquals(listOf("b@x.com"), m.to)
        assertEquals("t1", m.threadId)
        assertEquals(3, m.replyCount)
    }

    @Test
    fun `message - unknown server fields ignored`() {
        val m = mailJson.decodeFromString<MailMessage>(
            """{"id":"m1","uid":1,"from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
               "read":false,"flagged":false,"folder":"INBOX","size":0,"futureField":{"nested":true}}""",
        )
        assertEquals("m1", m.id)
        assertNull(m.fromName)
    }

    @Test
    fun `draft - server null body fields tolerated`() {
        val d = mailJson.decodeFromString<MailDraft>(
            """{"id":"d1","accountId":"acc1","to":["x@y.com"],"cc":[],"bcc":[],"subject":"s",
               "bodyText":null,"bodyHtml":null,"createdAt":"c","updatedAt":"u"}""",
        )
        assertNull(d.bodyText)
        assertNull(d.replyToMessageId)
    }

    @Test
    fun `send request - serializes optional arrays as absent when null`() {
        val encoded = mailJson.encodeToString(
            SendMailRequest.serializer(),
            SendMailRequest(to = listOf("b@x.com"), subject = "s", bodyText = "hello"),
        )
        assertTrue(encoded.contains("\"to\":[\"b@x.com\"]"))
        assertTrue(!encoded.contains("cc"))
        assertTrue(!encoded.contains("bcc"))
        assertTrue(!encoded.contains("attachments"))
    }

    @Test
    fun `contact suggestion and thread response parse`() {
        val c = mailJson.decodeFromString<ContactSuggestion>(
            """{"name":"Bob","email":"bob@x.com","department":"Dev","phone":"123","source":"qin"}""",
        )
        assertEquals("qin", c.source)

        val t = mailJson.decodeFromString<MailThreadResponse>(
            """{"messages":[{"id":"m1","from":"a@x.com","subject":"Re: hi","receivedAt":"r","bodyText":"text"}],"subject":"Re: hi"}""",
        )
        assertEquals(1, t.messages.size)
        assertEquals("text", t.messages[0].bodyText)
    }

    @Test
    fun `normalize - attachment five alias groups collapse`() {
        // part|partId|partNumber × filename|name × contentType|mimeType × size|bytes
        val raw = mailJson.decodeFromString<RawMessageDetail>(
            """
            {"id":"mail-m9","from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
             "read":false,"flagged":false,"folder":"INBOX","accountId":"acc1",
             "attachments":[
               {"part":"1.1","filename":"a.pdf","contentType":"application/pdf","size":10},
               {"partId":"2.1","name":"b.png","mimeType":"image/png","bytes":20},
               {"partNumber":3,"filename":"c.txt","contentType":"text/plain","size":30}
             ]}
            """.trimIndent(),
        )
        val detail = normalizeMailMessageDetail(raw)

        assertEquals(
            listOf(
                AttachmentMeta("1.1", "a.pdf", "application/pdf", 10),
                AttachmentMeta("2.1", "b.png", "image/png", 20),
                AttachmentMeta("3", "c.txt", "text/plain", 30),
            ),
            detail.attachments,
        )
    }

    @Test
    fun `normalize - drops attachments missing part or blank filename`() {
        val raw = mailJson.decodeFromString<RawMessageDetail>(
            """
            {"id":"m1","from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
             "read":false,"flagged":false,"folder":"INBOX","accountId":"acc1",
             "attachments":[
               {"filename":"no-part.pdf"},
               {"part":"1.2","filename":"   "},
               {"part":"","filename":"empty-part.pdf"},
               {"part":"1.3","filename":"kept.pdf"}
             ]}
            """.trimIndent(),
        )
        val detail = normalizeMailMessageDetail(raw)
        assertEquals(listOf(AttachmentMeta("1.3", "kept.pdf", "application/octet-stream", 0)), detail.attachments)
    }

    @Test
    fun `normalize - attachmentList alias used when attachments absent`() {
        val raw = mailJson.decodeFromString<RawMessageDetail>(
            """
            {"id":"m1","from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
             "read":false,"flagged":false,"folder":"INBOX","accountId":"acc1",
             "attachmentList":[{"part":"7","filename":"via-alias.bin"}]}
            """.trimIndent(),
        )
        assertEquals(listOf(AttachmentMeta("7", "via-alias.bin", "application/octet-stream", 0)), normalizeMailMessageDetail(raw).attachments)
    }

    @Test
    fun `normalize - attachments null falls back to attachmentList then empty`() {
        val viaList = mailJson.decodeFromString<RawMessageDetail>(
            """{"id":"m1","from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
               "read":false,"flagged":false,"folder":"INBOX","accountId":"a",
               "attachments":null,"attachmentList":[{"part":"1","filename":"f"}]}""",
        )
        assertEquals(1, normalizeMailMessageDetail(viaList).attachments.size)

        val bothEmpty = mailJson.decodeFromString<RawMessageDetail>(
            """{"id":"m2","from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
               "read":false,"flagged":false,"folder":"INBOX","accountId":"a"}""",
        )
        assertEquals(0, normalizeMailMessageDetail(bothEmpty).attachments.size)
    }

    @Test
    fun `normalize - id passed through untouched, prefix strip stays at URL-construction sites`() {
        // RN `...raw` 透传原样 id；剥前缀只发生在 detail/附件 URL 构造点（评审 Action 1）
        val raw = mailJson.decodeFromString<RawMessageDetail>(
            """{"id":"mail-m9","from":"a@x.com","to":[],"cc":[],"subject":"s","receivedAt":"r",
               "read":false,"flagged":false,"folder":"INBOX","accountId":"acc1"}""",
        )
        assertEquals("mail-m9", normalizeMailMessageDetail(raw).id)

        // 函数本体保留供 URL 构造点使用
        assertEquals("abc123", normalizeMailMessageId("mail-abc123"))
        assertEquals("plain", normalizeMailMessageId("plain"))
        assertEquals("mailx", normalizeMailMessageId("mailx"))
    }

    @Test
    fun `normalize - mail prefix strip matrix for URL-construction sites`() {
        // T4 钉死（坑 4）：detail/附件 URL 构造点的剥前缀语义全矩阵
        assertEquals("abc123", normalizeMailMessageId("mail-abc123")) // 标准前缀剥
        assertEquals("abc123", normalizeMailMessageId("abc123")) // 无前缀原样
        assertEquals("mailx", normalizeMailMessageId("mailx")) // mail 但非 mail- 不剥
        assertEquals("", normalizeMailMessageId("mail-")) // 前缀 + 空 → 空串
        assertEquals("mail-1", normalizeMailMessageId("mail-mail-1")) // 只剥一层（removePrefix 单趟）
        assertEquals("", normalizeMailMessageId("")) // 空串原样
        assertEquals("abc 123", normalizeMailMessageId("mail-abc 123")) // 剥后内容原样（含空格）
    }

    @Test
    fun `list response and sync response parse`() {
        val list = mailJson.decodeFromString<MessageListResponse>(
            """{"messages":[{"id":"m1","uid":1,"from":"a@x.com","to":[],"cc":[],"subject":"s",
                "receivedAt":"r","read":false,"flagged":false,"folder":"INBOX","size":5}],
               "total":1,"page":1,"pageSize":30}""",
        )
        assertEquals(1, list.total)
        assertEquals(30, list.pageSize)

        val sync = mailJson.decodeFromString<SyncResponse>(
            """{"syncLogId":"log1","status":"completed","newCount":4}""",
        )
        assertEquals(4, sync.newCount)
    }

    @Test
    fun `delete response and credential public key parse`() {
        val del = mailJson.decodeFromString<DeleteMailMessageResponse>(
            """{"permanent":true}""",
        )
        assertTrue(del.permanent)
        assertNull(del.trashFolder)

        val key = mailJson.decodeFromString<CredentialPublicKeyResponse>(
            """{"algorithm":"RSA-OAEP","hash":"SHA-256","publicKey":"-----BEGIN PUBLIC KEY-----","keyVersion":2}""",
        )
        assertEquals(2, key.keyVersion)
    }
}
