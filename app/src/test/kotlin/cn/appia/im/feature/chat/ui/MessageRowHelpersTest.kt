package cn.appia.im.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MessageRow 纯函数测试：u JSON 解析、发送者名/头像 URL（鉴权参数）、
 * MENTION 显示名解析（md 行内管线共用 helper）。
 */
class MessageRowHelpersTest {

    private fun message(
        u: String = """{"_id":"u2","username":"bob","name":"Bob"}""",
        alias: String = "",
        roomSender: String? = null,
    ) = cn.appia.im.core.database.entity.MessageEntity(
        _id = "m1", rid = "r1", ts = 0.0, u = u, alias = alias, parse_urls = "[]",
        _updated_at = 0.0, roomSender = roomSender,
    )

    // ── 头部展示（RN resolveMessageHeaderAuthor）──

    @Test
    fun `alias wins with at-loginName suffix`() {
        val h = buildMessageHeaderDisplay(message(alias = "Bobby"))
        assertEquals("Bobby", h.authorPrimary)
        assertEquals("@Bob", h.authorAliasSuffix)
    }

    @Test
    fun `no alias uses name then username then ellipsis`() {
        assertEquals("Bob", buildMessageHeaderDisplay(message()).authorPrimary)
        assertEquals("bob", buildMessageHeaderDisplay(message(u = """{"_id":"1","username":"bob"}""")).authorPrimary)
        assertEquals("…", buildMessageHeaderDisplay(message(u = """{"_id":"1"}""")).authorPrimary)
    }

    @Test
    fun `roomSender shows room name`() {
        val h = buildMessageHeaderDisplay(message(roomSender = """{"fname":"Team X"}"""))
        assertEquals("Team X", h.authorPrimary)
    }

    // ── 头像 URL（RN getMessageSenderAvatarUri 单聊路径）──

    @Test
    fun `avatar url carries auth and etag params`() {
        val url = buildMessageSenderAvatarUrl(
            server = "https://s1",
            uRaw = """{"_id":"1","username":"bob","avatarETag":"e1"}""",
            userId = "me",
            token = "tok",
        )
        assertEquals("https://s1/avatar/bob?version=1&format=png&size=36&rc_token=tok&rc_uid=me&v=e1", url)
    }

    @Test
    fun `avatar url without username or blank server is null`() {
        assertNull(buildMessageSenderAvatarUrl("https://s1", """{"_id":"1"}""", "me", "tok"))
        assertNull(buildMessageSenderAvatarUrl("", """{"username":"bob"}""", "me", "tok"))
    }

    @Test
    fun `own detection parses u id`() {
        val m = message(u = """{"_id":"me","username":"me"}""")
        assertEquals("me", parseMessageUser(m.u)._id)
    }

    // ── MENTION 显示名解析（resolveMentionDisplay，总纲 §4.3-1；T5 行内管线共用）──

    private val mentions = listOf(
        MentionUser(_id = "u2", username = "bob", name = "Bob"),
        MentionUser(_id = "u3", username = "carol", name = null),
    )

    @Test
    fun `mention all and here keep group color with raw label`() {
        assertEquals(MentionDisplay(MentionKind.GROUP, "all"), resolveMentionDisplay(mentions, "all", "me"))
        assertEquals(MentionDisplay(MentionKind.GROUP, "here"), resolveMentionDisplay(mentions, "here", "me"))
    }

    @Test
    fun `hit mention shows name without at and colors me`() {
        assertEquals(
            MentionDisplay(MentionKind.ME, "Bob"),
            resolveMentionDisplay(mentions, "bob", "bob"), // mention === 自己 username
        )
        assertEquals(
            MentionDisplay(MentionKind.OTHER, "Bob"),
            resolveMentionDisplay(mentions, "bob", "me"),
        )
    }

    @Test
    fun `hit mention falls back to username when name blank`() {
        assertEquals(
            MentionDisplay(MentionKind.OTHER, "carol"),
            resolveMentionDisplay(mentions, "carol", "me"),
        )
    }

    @Test
    fun `unresolved mention renders at-username plain text`() {
        assertEquals(
            MentionDisplay(MentionKind.UNRESOLVED, "@zed"),
            resolveMentionDisplay(mentions, "zed", "me"),
        )
        assertEquals(MentionDisplay(MentionKind.UNRESOLVED, ""), resolveMentionDisplay(mentions, "", "me"))
    }

    @Test
    fun `bad md json degrades via resolver to msg parse`() {
        // md 列坏 JSON → resolveMdFromMsgFields 返回 null（渲染层走无 md 独立 edited 标记），
        // 不再重 parse msg（md 渲染红线）
        assertNull(cn.appia.im.core.messaging.resolveMdFromMsgFields("{oops", "plain"))
    }

    @Test
    fun `mentions json parser tolerates empty and broken input`() {
        assertTrue(parseMentions(null).isEmpty())
        assertTrue(parseMentions("").isEmpty())
        assertTrue(parseMentions("{oops").isEmpty())
        assertEquals("bob", parseMentions("""[{"_id":"u2","username":"bob","name":"Bob"}]""")[0].username)
    }

    // ── T13 组装：上传进度百分比（RN computePercent 逐条）──

    @Test
    fun `upload percent single file takes current progress`() {
        val p = cn.appia.im.core.media.FileUploadProgress.Data(
            totalFiles = 1, completedFiles = 0, currentFileProgress = 0.47,
        )
        assertEquals(47, computeUploadPercent(p))
    }

    @Test
    fun `upload percent multi file folds completed count`() {
        val p = cn.appia.im.core.media.FileUploadProgress.Data(
            totalFiles = 3, completedFiles = 2, currentFileProgress = 0.5,
        )
        assertEquals(83, computeUploadPercent(p)) // (2+0.5)/3 = 83%
    }

    // ── T13 组装：HTML 反转义（M9 rider）──

    @Test
    fun `unescape html inverts edit content escape table`() {
        assertEquals("a<b>&\"'c", cn.appia.im.core.messaging.unescapeHtml("a&lt;b&gt;&amp;&quot;&#39;c"))
    }
}
