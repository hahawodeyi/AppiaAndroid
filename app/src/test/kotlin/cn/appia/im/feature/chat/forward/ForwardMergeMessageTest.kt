package cn.appia.im.feature.chat.forward

import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并转发纯逻辑（RN lib/message/forwardMergeMessage.ts + parseChatUids + ForwardSelectScreen
 * handleConfirm :356-390）：msgData 解析（含坏 JSON）、标题组装、消息映射、时间戳解析、
 * 选择载荷组装（DM peer / 群 rid / 搜索-only rid）。
 */
class ForwardMergeMessageTest {

    // ── parseForwardMsgData ──

    @Test
    fun `parse msgData extracts originRoom and messages`() {
        val raw = """
            {"originRoom":{"rid":"room1","name":"\u6280\u672f\u8ba8\u8bba","names":["\u7532","\u4e59","\u4e19"]},
             "messages":[
               {"_id":"m1","msg":"hello","u":{"_id":"u1","username":"u1","name":"\u5f20\u4e09"},
                "ts":"2024-06-10T09:05:00.000Z","attachments":[{"title":"a"}]},
               {"msg":"\u7b2c\u4e8c\u6761","ts":1718000000000}
             ]}
        """.trimIndent()
        val data = parseForwardMsgData(raw)!!

        assertEquals("room1", data.originRoomRid)
        assertEquals("\u6280\u672f\u8ba8\u8bba", data.originRoomName)
        assertEquals(listOf("\u7532", "\u4e59", "\u4e19"), data.originRoomNames)
        assertEquals(2, data.messages.size)
        assertEquals("hello", data.messages[0].msg)
        assertTrue(data.messages[0].u.contains("\u5f20\u4e09"))
        assertEquals("""[{"title":"a"}]""", data.messages[0].attachments)
        assertEquals("\u7b2c\u4e8c\u6761", data.messages[1].msg)
    }

    @Test
    fun `parse msgData null empty or broken json returns null`() {
        assertNull(parseForwardMsgData(null))
        assertNull(parseForwardMsgData(""))
        assertNull(parseForwardMsgData("not-json{"))
        assertNull(parseForwardMsgData("[1,2]")) // 非对象
    }

    // ── parseTimestampMs（RN parseTimestamp.ts）──

    @Test
    fun `parseTimestampMs handles seconds millis iso and dollar date`() {
        assertEquals(1_718_000_000_000L, parseTimestampMs(JsonPrimitive(1_718_000_000_000L)))
        assertEquals(1_718_000_000_000L, parseTimestampMs(JsonPrimitive(1_718_000_000.0))) // 秒 ×1000
        assertEquals(1_718_010_300_000L, parseTimestampMs(JsonPrimitive("2024-06-10T09:05:00.000Z")))
        assertEquals(
            1_718_000_000_000L,
            parseTimestampMs(Json.parseToJsonElement("""{"${'$'}date":1718000000000}""")),
        )
        assertNull(parseTimestampMs(JsonPrimitive("garbage")))
        assertNull(parseTimestampMs(null))
    }

    // ── buildForwardMergeTitle ──

    private val t = mapOf(
        "message_forwardmergetitle" to "{{name0}}\u548c{{name1}}\u7684\u804a\u5929\u8bb0\u5f55",
        "message_forwardmergetitlemultiple" to "{{name0}}\u7b49\u7684\u804a\u5929\u8bb0\u5f55",
        "message_forwardmergetitleroom" to "{{roomName}}\u7684\u804a\u5929\u8bb0\u5f55",
        "message_forwardmergetitlefallback" to "\u804a\u5929\u8bb0\u5f55",
    )
    private fun tr(key: String) = t.getValue(key)

    @Test
    fun `title uses two names when originRoom names size 2`() {
        val data = ForwardMsgData("r", null, listOf("\u7532", "\u4e59"), emptyList())
        assertEquals("\u7532\u548c\u4e59\u7684\u804a\u5929\u8bb0\u5f55", buildForwardMergeTitle(data, ::tr))
    }

    @Test
    fun `title uses first name etc when more than two names`() {
        val data = ForwardMsgData("r", null, listOf("\u7532", "\u4e59", "\u4e19"), emptyList())
        assertEquals("\u7532\u7b49\u7684\u804a\u5929\u8bb0\u5f55", buildForwardMergeTitle(data, ::tr))
    }

    @Test
    fun `title falls back to room name then generic`() {
        assertEquals("\u6280\u672f\u8ba8\u8bba\u7684\u804a\u5929\u8bb0\u5f55", buildForwardMergeTitle(ForwardMsgData("r", "\u6280\u672f\u8ba8\u8bba", emptyList(), emptyList()), ::tr))
        assertEquals("\u804a\u5929\u8bb0\u5f55", buildForwardMergeTitle(ForwardMsgData("r", null, emptyList(), emptyList()), ::tr))
    }

    // ── mapForwardMessagesToEntities ──

    @Test
    fun `map keeps id and parses ts`() {
        val rows = mapForwardMessagesToEntities(
            listOf(
                ForwardMessageItem(_id = "m1", msg = "\u8ba8\u8bba\u5185\u5bb9", ts = JsonPrimitive("2024-06-10T09:05:00.000Z"), u = "{\"name\":\"\u5f20\u4e09\"}"),
            ),
            "room1",
        )
        assertEquals("m1", rows[0]._id)
        assertEquals(1_718_010_300_000L, rows[0].ts.toLong())
        assertEquals("room1", rows[0].rid)
        assertTrue(rows[0].u.contains("\u5f20\u4e09"))
    }

    @Test
    fun `map falls back id and ts when missing`() {
        val rows = mapForwardMessagesToEntities(
            listOf(ForwardMessageItem(msg = "\u65e0 ts"), ForwardMessageItem(msg = "\u7b2c\u4e8c\u6761")),
            "room1",
        )
        assertEquals("forward-room1-0-0", rows[0]._id)
        assertEquals(0.0, rows[0].ts, 0.0)
        assertEquals("forward-room1-1-1", rows[1]._id) // RN ts 回退 index
    }

    // ── 发送侧 ──

    private fun chat(_id: String, t: String, uids: String? = null) = ChatEntity(
        _id = _id, f = false, t = t, ts = 0.0, ls = 0.0, name = _id, fname = "", rid = _id,
        open = true, alert = false, unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
        room_updated_at = 0.0, ro = false, archived = false, auto_translate_language = "en",
        team_id = "", uids = uids,
    )

    @Test
    fun `parseChatUids and dm peer`() {
        assertEquals(listOf("a", "b"), parseChatUids("""["a","b"]"""))
        assertTrue(parseChatUids("not-json").isEmpty())
        assertTrue(parseChatUids(null).isEmpty())
        assertEquals("b", resolveDmPeerUid(chat("r1", "d", """["me","b"]"""), "me"))
        assertNull(resolveDmPeerUid(chat("r1", "d", """["me"]"""), "me"))
    }

    @Test
    fun `buildForwardTargets splits dm group and search only rids`() {
        val chats = listOf(
            chat("dm1", "d", """["me","peer1"]"""),
            chat("gp1", "p"),
        )
        val targets = buildForwardTargets(
            selectedRids = listOf("dm1", "gp1", "search-rid"),
            selectedUserIds = listOf("zhangsan"),
            chats = chats,
            currentUserId = "me",
        )
        // RN :373-374：users = 已选 username 在前 + DM peer；rooms = 群 rid + 本地无订阅 rid
        assertEquals(listOf("zhangsan", "peer1"), targets.users)
        assertEquals(listOf("gp1", "search-rid"), targets.rooms)
    }

    @Test
    fun `dm rid without peer id is dropped like RN`() {
        val targets = buildForwardTargets(listOf("self"), emptyList(), listOf(chat("self", "d", """["me"]""")), "me")
        assertEquals(emptyList<String>(), targets.users)
        assertEquals(emptyList<String>(), targets.rooms)
    }

    @Test
    fun `isGroupChat is p or c only`() {
        assertTrue(isGroupChat(chat("r", "p")))
        assertTrue(isGroupChat(chat("r", "c")))
        assertTrue(!isGroupChat(chat("r", "d")))
        assertTrue(!isGroupChat(chat("r", "l")))
    }

    @Test
    fun `interpolate replaces placeholders`() {
        assertEquals("\u5df2\u9009 3/10", interpolate("\u5df2\u9009 {{count}}/{{max}}", mapOf("count" to "3", "max" to "10")))
    }
}
