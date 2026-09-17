package cn.appia.im.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MessageRow 纯函数测试：u JSON 解析、发送者名/头像 URL（鉴权参数）、
 * 正文 span（md MENTION 节点 + mentions 匹配、无 md 纯文本）。
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

    // ── 正文 span（RN AtMention 数据源语义）──

    private val mentions = listOf(
        MentionUser(_id = "u2", username = "bob", name = "Bob"),
        MentionUser(_id = "u3", username = "carol", name = null),
    )

    @Test
    fun `no md falls back to plain msg`() {
        assertEquals(listOf<BodySpan>(BodySpan.Plain("hi")), parseBodySpans("hi", null, mentions))
    }

    @Test
    fun `mention node matched by username from mentions array`() {
        val md = """[{"type":"PARAGRAPH","value":[
            {"type":"PLAIN_TEXT","value":"hi "},
            {"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"bob"}},
            {"type":"PLAIN_TEXT","value":" and "},
            {"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"zed"}}
        ]}]"""
        val spans = parseBodySpans("hi @bob and @zed", md, mentions)
        assertEquals(
            listOf(
                BodySpan.Plain("hi "),
                BodySpan.Mention("bob"),
                BodySpan.Plain(" and "),
                BodySpan.Mention("zed"), // 未命中 → 由 resolver 判 UNRESOLVED
            ),
            spans,
        )
    }

    // ── MENTION 显示名解析（resolveMentionDisplay，总纲 §4.3-1；T5 行内管线共用）──

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
    fun `bad md json degrades to plain msg`() {
        val spans = parseBodySpans("plain", "{oops", mentions)
        assertEquals(listOf<BodySpan>(BodySpan.Plain("plain")), spans)
    }

    @Test
    fun `mentions json parser tolerates empty and broken input`() {
        assertTrue(parseMentions(null).isEmpty())
        assertTrue(parseMentions("").isEmpty())
        assertTrue(parseMentions("{oops").isEmpty())
        assertEquals("bob", parseMentions("""[{"_id":"u2","username":"bob","name":"Bob"}]""")[0].username)
    }
}
