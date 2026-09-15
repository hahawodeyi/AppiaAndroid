package cn.appia.im.core.messaging

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 消息落库字段收敛表（RN persistMessagesFromRocketApi.ts:66-121 applyApiFields 逐字段对照）：
 * 每个字段项的类型回退/undefined 化、ts 三态解析、status 不写（T8 SendOrchestrator 专写）、
 * lastWinsByKey 去重、批量单事务、按 rid 查询。
 * `nowMs` 注入固定值：ts 回退与 messageUpdatedAt（`_updated_at` 列）可精确断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageUpsertTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseManager

    /** 固定「当前时间」：ts 回退 / _updated_at 的确定性断言。 */
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        manager = DatabaseManager(context)
        manager.switchDatabase("https://chat-a.example.com")
    }

    @After
    fun tearDown() {
        manager.resetAll()
    }

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    /** 全字段齐备的消息（各字段值唯一，便于逐字段断言）。 */
    private val fullMessage = obj(
        """{"_id":"m1","rid":"room-1","msg":"hello","ts":1690000000,"u":{"_id":"u1","username":"alice"},
        |"t":"ej","alias":"ali","md":[{"type":"PARAGRAPH"}],"attachments":[{"title":"a"}],
        |"files":[{"name":"f"}],"msgType":"e2e","msgData":"d","reactions":{":smile:":{"usernames":["u1"]}},
        |"emoji":":smile:","avatar":"https://a.png","groupable":false,"role":"admin","roleName":"Admin",
        |"roomSender":"s1","rollbacker":"r1","urls":[{"url":"https://x"}],"mentions":[{"_id":"u2"}],
        |"channels":[{"_id":"c2"}],"pinned":true,"starred":false,
        |"editedBy":{"_id":"u1"},"drid":"d-1","dcount":3,"dlm":1690000001,"tmid":"t-1",
        |"tcount":2,"tlm":1690000002,"replies":["u2"],"unread":true,"autoTranslate":true,
        |"translations":{"en":"hi"},"tmsg":"tm","blocks":[],"e2e":"k","tshow":true,"comment":"c",
        |"appiaTodo":{"status":1,"tid":"todo-1"},"surveyStatus":false,
        |"showImageSummary":"img","showDocumentSummary":"doc","appiaQuickReplies":"qr"}""".trimMargin(),
    )

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    // ---- 全字段齐备：逐字段正向 ----

    @Test
    fun `full message maps every field of the RN table`() {
        val row = MessageUpsert.applyApiFields(null, fullMessage, "room-1", now)

        assertEquals("m1", row._id)
        assertEquals("hello", row.msg)
        assertEquals("room-1", row.rid)
        assertEquals(1_690_000_000_000.0, row.ts, 0.0) // 秒级数字 ×1000
        assertEquals("""{"_id":"u1","username":"alice"}""", row.u)
        assertEquals("ej", row.t)
        assertEquals("ali", row.alias)
        assertEquals("""[{"type":"PARAGRAPH"}]""", row.md)
        assertEquals("""[{"title":"a"}]""", row.attachments)
        assertEquals("""[{"name":"f"}]""", row.files)
        assertEquals("e2e", row.msg_type)
        assertEquals("d", row.msg_data)
        assertEquals("""{":smile:":{"usernames":["u1"]}}""", row.reactions)
        assertEquals(":smile:", row.emoji)
        assertEquals("https://a.png", row.avatar)
        assertEquals(false, row.groupable)
        assertEquals("admin", row.role)
        assertEquals("Admin", row.role_name)
        assertEquals("s1", row.roomSender)
        assertEquals("r1", row.rollbacker)
        assertEquals("""[{"url":"https://x"}]""", row.urls)
        assertEquals("""[{"_id":"u2"}]""", row.mentions)
        assertEquals("""[{"_id":"c2"}]""", row.channels)
        assertEquals(true, row.pinned)
        assertEquals(false, row.starred)
        assertEquals("""{"_id":"u1"}""", row.edited_by)
        assertEquals("d-1", row.drid)
        assertEquals(3.0, row.dcount!!, 0.0)
        assertEquals(1_690_000_001.0, row.dlm!!, 0.0) // 仅 ts 走秒级换算；其余数字字段原样收（RN 同）
        assertEquals("t-1", row.tmid)
        assertEquals(2.0, row.tcount!!, 0.0)
        assertEquals(1_690_000_002.0, row.tlm!!, 0.0)
        assertNull(row.replies) // RN :104 replies 仅收字符串，数组 → '' → 可选列 null
        assertEquals(true, row.unread)
        assertEquals(true, row.auto_translate)
        assertNull(row.translations) // RN :107 translations 仅收字符串；对象 → '' → null
        assertEquals("tm", row.tmsg)
        assertNull(row.blocks) // RN :109 blocks 仅收字符串；数组 → '' → null
        assertEquals("k", row.e2e)
        assertEquals(true, row.tshow)
        assertEquals("c", row.comment)
        assertEquals("""{"status":1,"tid":"todo-1"}""", row.appia_todo)
        assertEquals(false, row.survey_status)
        assertEquals("img", row.show_image_summary)
        assertEquals("doc", row.show_document_summary)
        assertEquals("qr", row.appia_quick_replies)
        assertEquals("[]", row.parse_urls) // 恒 '[]'
        assertEquals(now.toDouble(), row._updated_at, 0.0) // messageUpdatedAt
        assertNull(row.status) // 不在字段表：create 为 null
        assertNull(row.local_record_path)
        assertNull(row.original_content)
    }

    // ---- 缺失/类型不符：回退值逐字段 ----

    @Test
    fun `missing fields fall back exactly like the RN ternaries`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"m2"}"""), "fb-rid", now)

        assertNull(row.msg) // 回 '' → Watermelon 可选列落 null（下同）
        assertEquals("fb-rid", row.rid) // data.rid 非串 → fallbackRid
        assertEquals(now.toDouble(), row.ts, 0.0) // ts 缺失 → Date.now()
        assertEquals("{}", row.u) // JSON.stringify(u ?? {})
        assertNull(row.t)
        assertEquals("", row.alias) // 非空列：'' 原样
        assertNull(row.msg_type)
        assertNull(row.msg_data)
        assertNull(row.emoji)
        assertNull(row.avatar)
        assertEquals("[]", row.parse_urls)
        assertEquals(now.toDouble(), row._updated_at, 0.0)
        // truthy 判定的 JSON 字段缺失 → '' → null
        assertNull(row.md)
        assertNull(row.attachments)
        assertNull(row.files)
        assertNull(row.reactions)
        assertNull(row.urls)
        assertNull(row.mentions)
        assertNull(row.channels)
        // boolean 之外 undefined 化（Watermelon 可选列 null）
        assertNull(row.groupable)
        assertNull(row.pinned)
        assertNull(row.starred)
        assertNull(row.unread)
        assertNull(row.auto_translate)
        assertNull(row.tshow)
        assertNull(row.survey_status)
        // 数字回 0
        assertEquals(0.0, row.dcount!!, 0.0)
        assertEquals(0.0, row.dlm!!, 0.0)
        assertEquals(0.0, row.tcount!!, 0.0)
        assertEquals(0.0, row.tlm!!, 0.0)
        // editedBy / appiaTodo 兜底 '' → null
        assertNull(row.edited_by)
        assertNull(row.appia_todo)
    }

    @Test
    fun `non string scalars fall back to empty like RN typeof checks`() {
        val row = MessageUpsert.applyApiFields(
            null,
            obj("""{"_id":"m3","msg":42,"t":true,"alias":1.5,"emoji":false,"avatar":0}"""),
            "rid",
            now,
        )
        assertNull(row.msg) // 数字 msg → '' → 可选列 null
        assertNull(row.t)
        assertEquals("", row.alias) // 非空列
        assertNull(row.emoji)
        assertNull(row.avatar)
    }

    @Test
    fun `boolean strings are rejected like RN typeof checks`() {
        val row = MessageUpsert.applyApiFields(
            null,
            obj("""{"_id":"m4","groupable":"true","pinned":"false","unread":1,"autoTranslate":"1","tshow":null}"""),
            "rid",
            now,
        )
        assertNull(row.groupable) // "true" 是串不是 boolean
        assertNull(row.pinned)
        assertNull(row.unread) // 数字 1 也不是 boolean
        assertNull(row.auto_translate)
        assertNull(row.tshow) // null → undefined
    }

    @Test
    fun `numeric strings are rejected for count fields`() {
        val row = MessageUpsert.applyApiFields(
            null,
            obj("""{"_id":"m5","dcount":"3","dlm":"x","tcount":null}"""),
            "rid",
            now,
        )
        assertEquals(0.0, row.dcount!!, 0.0)
        assertEquals(0.0, row.dlm!!, 0.0)
        assertEquals(0.0, row.tcount!!, 0.0)
    }

    @Test
    fun `falsy json fields stringify to empty not to json`() {
        // "" / 0 / false 均为 JS falsy → ''（不 JSON.stringify）
        val row = MessageUpsert.applyApiFields(
            null,
            obj("""{"_id":"m6","md":"","urls":0,"reactions":false}"""),
            "rid",
            now,
        )
        assertNull(row.md)
        assertNull(row.urls)
        assertNull(row.reactions)
    }

    @Test
    fun `empty json object is truthy and gets stringified`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"m7","md":{},"mentions":[]}"""), "rid", now)
        assertEquals("{}", row.md) // JS 对象恒 truthy（空对象也是）
        assertEquals("[]", row.mentions)
    }

    // ---- editedBy / appiaTodo 的三态 ----

    @Test
    fun `editedBy string passes through and non string non object falls back`() {
        val asString = MessageUpsert.applyApiFields(null, obj("""{"_id":"a","editedBy":"u9"}"""), "rid", now)
        assertEquals("u9", asString.edited_by)
        val asNumber = MessageUpsert.applyApiFields(null, obj("""{"_id":"b","editedBy":5}"""), "rid", now)
        assertNull(asNumber.edited_by)
        // RN isRecord 匹配数组：数组同样 JSON 化（评审 Minor-2 对齐）
        val asArray = MessageUpsert.applyApiFields(null, obj("""{"_id":"c","editedBy":["u1"]}"""), "rid", now)
        assertEquals("""["u1"]""", asArray.edited_by)
        val missing = MessageUpsert.applyApiFields(null, obj("""{"_id":"d"}"""), "rid", now)
        assertNull(missing.edited_by)
    }

    @Test
    fun `appiaTodo accepts raw string and json-serializes any non-null value`() {
        val asString = MessageUpsert.applyApiFields(null, obj("""{"_id":"a","appiaTodo":"raw"}"""), "rid", now)
        assertEquals("raw", asString.appia_todo)
        val asObject = MessageUpsert.applyApiFields(null, obj("""{"_id":"b","appiaTodo":{"status":2}}"""), "rid", now)
        assertEquals("""{"status":2}""", asObject.appia_todo)
        val asNumber = MessageUpsert.applyApiFields(null, obj("""{"_id":"c","appiaTodo":7}"""), "rid", now)
        assertEquals("7", asNumber.appia_todo)
        val asNull = MessageUpsert.applyApiFields(null, obj("""{"_id":"d","appiaTodo":null}"""), "rid", now)
        assertNull(asNull.appia_todo)
    }

    @Test
    fun `u string is json quoted like JSON stringify`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"m8","u":"plain"}"""), "rid", now)
        assertEquals("\"plain\"", row.u)
        val nullU = MessageUpsert.applyApiFields(null, obj("""{"_id":"m9","u":null}"""), "rid", now)
        assertEquals("{}", nullU.u) // null ?? {}
    }

    // ---- ts 三态解析（RN parseTimestamp）----

    @Test
    fun `ts seconds number is multiplied by 1000`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"t1","ts":1690000000}"""), "rid", now)
        assertEquals(1_690_000_000_000.0, row.ts, 0.0)
    }

    @Test
    fun `ts milliseconds number passes through unchanged`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"t2","ts":1690000000123}"""), "rid", now)
        assertEquals(1_690_000_000_123.0, row.ts, 0.0)
    }

    @Test
    fun `ts iso string is parsed via Date parse`() {
        val row = MessageUpsert.applyApiFields(
            null,
            obj("""{"_id":"t3","ts":"2026-01-15T10:30:00.000Z"}"""),
            "rid",
            now,
        )
        assertEquals(ChatMergerProbe.parse("2026-01-15T10:30:00.000Z"), row.ts, 0.0)
    }

    @Test
    fun `ts ejson dollar date object is unwrapped`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"t4","ts":{"${'$'}date":1690000000123}}"""), "rid", now)
        assertEquals(1_690_000_000_123.0, row.ts, 0.0)
    }

    @Test
    fun `ts unparseable falls back to now`() {
        val garbage = MessageUpsert.applyApiFields(null, obj("""{"_id":"t5","ts":"not-a-date"}"""), "rid", now)
        assertEquals(now.toDouble(), garbage.ts, 0.0)
        val noDollarDate = MessageUpsert.applyApiFields(null, obj("""{"_id":"t6","ts":{"other":1}}"""), "rid", now)
        assertEquals(now.toDouble(), noDollarDate.ts, 0.0)
        val array = MessageUpsert.applyApiFields(null, obj("""{"_id":"t7","ts":[]}"""), "rid", now)
        assertEquals(now.toDouble(), array.ts, 0.0)
    }

    // ---- status / 客户端独有列不写 ----

    @Test
    fun `status in payload is never written and stays null on create`() {
        val row = MessageUpsert.applyApiFields(null, obj("""{"_id":"s1","status":3,"msg":"x"}"""), "rid", now)
        assertNull(row.status) // 字段表无 status：载荷里的同名键被丢弃
    }

    @Test
    fun `update preserves status and client only columns from previous row`() {
        val prev = MessageEntity(
            _id = "s2", msg = "old", rid = "rid", ts = 1.0, u = "{}", alias = "",
            parse_urls = "[]", _updated_at = 1.0, status = 2.0,
            local_record_path = "/rec/a.m4a", original_content = "voice text",
        )
        val row = MessageUpsert.applyApiFields(prev, fullMessage, "rid", now)
        assertEquals(2.0, row.status) // 只归 SendOrchestrator（T8）写
        assertEquals("/rec/a.m4a", row.local_record_path) // 客户端独有：录音路径
        assertEquals("voice text", row.original_content) // 客户端独有：转写
        assertEquals("hello", row.msg) // 其余字段照常覆盖
    }

    // ---- 批量落库：isApiMessage / lastWinsByKey / 事务 ----

    @Test
    fun `persist skips entries without string _id and keeps last win per id`() = runBlocking {
        val db = manager.active
        val wrote = MessageUpsert.persist(
            db,
            listOf(
                obj("""{"_id":"dup","msg":"first","rid":"room-1","ts":1690000000}"""),
                obj("""{"msg":"no id"}"""),
                obj("""{"_id":42,"msg":"id not string"}"""),
                obj("""{"_id":"m-ok","msg":"ok","rid":"room-1","ts":1690000001}"""),
                obj("""{"_id":"dup","msg":"second","rid":"room-1","ts":1690000002}"""),
            ),
            "fallback-rid",
            now,
        )
        assertTrue(wrote)
        val dao = db.messageDao()
        assertEquals(2, dao.getAll().size)
        val dup = dao.getById("dup")!!
        assertEquals("second", dup.msg) // lastWinsByKey：同 _id 保留最后一次
        assertNotNull(dao.getById("m-ok"))
    }

    @Test
    fun `persist updates existing row preserving status and returns false on empty batch`() = runBlocking {
        val db = manager.active
        val dao = db.messageDao()
        MessageUpsert.persist(db, listOf(obj("""{"_id":"e1","msg":"v1","rid":"room-1","ts":1690000000}""")), "room-1", now)
        // T8 视角的既有行：模拟 SendOrchestrator 已写 status
        dao.insert(dao.getById("e1")!!.copy(status = 1.0))

        MessageUpsert.persist(db, listOf(obj("""{"_id":"e1","msg":"v2","rid":"room-1","ts":1690000000}""")), "room-1", now)

        val row = dao.getById("e1")!!
        assertEquals("v2", row.msg)
        assertEquals(1.0, row.status) // upsert 全行覆盖也不丢 status

        val empty = MessageUpsert.persist(db, listOf(obj("""{"msg":"no id"}""")), "room-1", now)
        assertEquals(false, empty)
    }

    @Test
    fun `persistFromUnknown persists single stream message with fallback rid`() = runBlocking {
        val db = manager.active
        assertTrue(
            MessageUpsert.persistFromUnknown(
                db,
                obj("""{"_id":"p1","msg":"stream","ts":1690000000}"""),
                "stream-rid",
                now,
            ),
        )
        assertEquals("stream-rid", db.messageDao().getById("p1")!!.rid)
        assertEquals(false, MessageUpsert.persistFromUnknown(db, null, "stream-rid", now))
    }

    // ---- 消息 DAO：按 rid 倒序 + limit ----

    @Test
    fun `getByRid returns newest first limited rows only for that rid`() = runBlocking {
        val db = manager.active
        MessageUpsert.persist(
            db,
            listOf(
                obj("""{"_id":"q1","rid":"room-1","ts":1000.0,"msg":"a"}"""),
                obj("""{"_id":"q2","rid":"room-1","ts":3000.0,"msg":"c"}"""),
                obj("""{"_id":"q3","rid":"room-1","ts":2000.0,"msg":"b"}"""),
                obj("""{"_id":"q4","rid":"room-2","ts":4000.0,"msg":"other"}"""),
            ),
            "room-1",
            now,
        )
        val rows = db.messageDao().getByRid("room-1", limit = 2)
        assertEquals(listOf("q2", "q3"), rows.map { it._id }) // ts 倒序 + LIMIT
    }

    /** parse 期望值的独立对照（ChatMerger ISO 解析的复用口径）。 */
    private object ChatMergerProbe {
        fun parse(iso: String): Double = cn.appia.im.domain.chat.ChatMerger.parseIsoMillis(iso)!!
    }
}
