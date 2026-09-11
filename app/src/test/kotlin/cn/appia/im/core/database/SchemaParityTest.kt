package cn.appia.im.core.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.database.entity.CustomEmojiEntity
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.database.entity.RoomEntity
import cn.appia.im.core.database.entity.SettingEntity
import cn.appia.im.core.database.entity.SubscriptionEntity
import cn.appia.im.core.database.entity.UploadEntity
import cn.appia.im.core.database.entity.UserEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 官方仅支持 JUnit4 runner，经 vintage 引擎混跑在 JUnit Platform 上；
// SDK 36 沙箱要 Java 21，工程是 Java 17，故钉在 SDK 34（同 I18nTest）
@RunWith(RobolectricTestRunner::class)
// application=plain Application：AppiaApplication.onCreate 会初始化 MMKV，native .so 在 JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class SchemaParityTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppiaDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun tearDown() {
        db.close()
    }

    private data class Col(val name: String, val sqliteType: String, val notNull: Boolean, val pk: Boolean)

    /**
     * 全量对照 appiaMobile `src/database/schema.ts`（WatermelonDB v6）逐列转录。
     * string→TEXT、number→REAL、boolean→INTEGER；notNull = !isOptional；pk 为主键列。
     * messages/settings/uploads 的 `_id` 为 Android 侧补充的自然键主键（RN schema 无此列，见任务报告）。
     */
    private val expectedColumns: Map<String, List<Col>> = mapOf(
        "rooms" to listOf(
            Col("_id", "TEXT", true, true),
            Col("custom_fields", "TEXT", true, false),
            Col("broadcast", "INTEGER", true, false),
            Col("encrypted", "INTEGER", true, false),
            Col("ro", "INTEGER", true, false),
            Col("v", "TEXT", false, false),
            Col("department_id", "TEXT", false, false),
            Col("served_by", "TEXT", false, false),
            Col("livechat_data", "TEXT", false, false),
            Col("tags", "TEXT", false, false),
            Col("e2e_key_id", "TEXT", false, false),
            Col("avatar_etag", "TEXT", false, false),
            Col("federated", "INTEGER", false, false),
            Col("rt", "TEXT", false, false),
            Col("rooms", "TEXT", false, false),
            Col("onCallStatus", "INTEGER", false, false),
            Col("callMsg", "TEXT", false, false),
            Col("bot", "INTEGER", false, false),
            Col("showAppiaTag", "REAL", false, false),
            Col("appiaUsage", "TEXT", false, false),
        ),
        "subscriptions" to listOf(
            Col("_id", "TEXT", true, true),
            Col("f", "INTEGER", true, false),
            Col("t", "TEXT", true, false),
            Col("ts", "REAL", true, false),
            Col("ls", "REAL", true, false),
            Col("name", "TEXT", true, false),
            Col("fname", "TEXT", true, false),
            Col("rid", "TEXT", true, false),
            Col("open", "INTEGER", true, false),
            Col("alert", "INTEGER", true, false),
            Col("roles", "TEXT", false, false),
            Col("unread", "REAL", true, false),
            Col("user_mentions", "REAL", true, false),
            Col("group_mentions", "REAL", true, false),
            Col("tunread", "TEXT", false, false),
            Col("tunread_user", "TEXT", false, false),
            Col("tunread_group", "TEXT", false, false),
            Col("lm", "REAL", false, false),
            Col("room_updated_at", "REAL", true, false),
            Col("subscription_updated_at", "REAL", false, false),
            Col("ro", "INTEGER", true, false),
            Col("last_open", "REAL", false, false),
            Col("last_message", "TEXT", false, false),
            Col("description", "TEXT", false, false),
            Col("announcement", "TEXT", false, false),
            Col("announcements", "TEXT", false, false),
            Col("room_value_proposition", "TEXT", false, false),
            Col("banner_closed", "INTEGER", false, false),
            Col("topic", "TEXT", false, false),
            Col("blocked", "INTEGER", false, false),
            Col("blocker", "INTEGER", false, false),
            Col("react_when_read_only", "INTEGER", false, false),
            Col("archived", "INTEGER", true, false),
            Col("join_code_required", "INTEGER", false, false),
            Col("muted", "TEXT", false, false),
            Col("ignored", "TEXT", false, false),
            Col("broadcast", "INTEGER", false, false),
            Col("prid", "TEXT", false, false),
            Col("draft_message", "TEXT", false, false),
            Col("draft_message_plain", "TEXT", false, false),
            Col("draft_reply_msg_id", "TEXT", false, false),
            Col("last_thread_sync", "REAL", false, false),
            Col("jitsi_timeout", "REAL", false, false),
            Col("auto_translate", "INTEGER", false, false),
            Col("auto_translate_language", "TEXT", true, false),
            Col("hide_unread_status", "INTEGER", false, false),
            Col("disable_notifications", "INTEGER", false, false),
            Col("sys_mes", "TEXT", false, false),
            Col("uids", "TEXT", false, false),
            Col("usernames", "TEXT", false, false),
            Col("visitor", "TEXT", false, false),
            Col("department_id", "TEXT", false, false),
            Col("served_by", "TEXT", false, false),
            Col("livechat_data", "TEXT", false, false),
            Col("tags", "TEXT", false, false),
            Col("e2e_key", "TEXT", false, false),
            Col("e2e_suggested_key", "TEXT", false, false),
            Col("encrypted", "INTEGER", false, false),
            Col("e2e_key_id", "TEXT", false, false),
            Col("avatar_etag", "TEXT", false, false),
            Col("team_id", "TEXT", true, false),
            Col("team_main", "INTEGER", false, false),
            Col("on_hold", "INTEGER", false, false),
            Col("source", "TEXT", false, false),
            Col("hide_mention_status", "INTEGER", false, false),
            Col("users_count", "REAL", false, false),
            Col("federated", "INTEGER", false, false),
            Col("rt", "TEXT", false, false),
            Col("todoCount", "REAL", false, false),
            Col("dname", "TEXT", false, false),
            Col("onCallStatus", "INTEGER", false, false),
            Col("callMsg", "TEXT", false, false),
            Col("bot", "INTEGER", false, false),
            Col("welcomeMsg", "TEXT", false, false),
            Col("memberName", "TEXT", false, false),
            Col("memberNumber", "REAL", false, false),
            Col("showAppiaTag", "REAL", false, false),
            Col("isRoomToDo", "INTEGER", false, false),
            Col("highTodoCount", "REAL", false, false),
            Col("defaultTodoCount", "REAL", false, false),
            Col("like", "INTEGER", false, false),
            Col("tSearch", "REAL", false, false),
            Col("appiaUsage", "TEXT", false, false),
        ),
        "chats" to listOf(
            Col("_id", "TEXT", true, true),
            Col("subscription_doc_id", "TEXT", false, false),
            Col("f", "INTEGER", true, false),
            Col("t", "TEXT", true, false),
            Col("ts", "REAL", true, false),
            Col("ls", "REAL", true, false),
            Col("name", "TEXT", true, false),
            Col("fname", "TEXT", true, false),
            Col("rid", "TEXT", true, false),
            Col("open", "INTEGER", true, false),
            Col("alert", "INTEGER", true, false),
            Col("roles", "TEXT", false, false),
            Col("unread", "REAL", true, false),
            Col("user_mentions", "REAL", true, false),
            Col("group_mentions", "REAL", true, false),
            Col("tunread", "TEXT", false, false),
            Col("tunread_user", "TEXT", false, false),
            Col("tunread_group", "TEXT", false, false),
            Col("lm", "REAL", false, false),
            Col("room_updated_at", "REAL", true, false),
            Col("subscription_updated_at", "REAL", false, false),
            Col("ro", "INTEGER", true, false),
            Col("last_open", "REAL", false, false),
            Col("last_message", "TEXT", false, false),
            Col("description", "TEXT", false, false),
            Col("announcement", "TEXT", false, false),
            Col("announcements", "TEXT", false, false),
            Col("room_value_proposition", "TEXT", false, false),
            Col("banner_closed", "INTEGER", false, false),
            Col("topic", "TEXT", false, false),
            Col("blocked", "INTEGER", false, false),
            Col("blocker", "INTEGER", false, false),
            Col("react_when_read_only", "INTEGER", false, false),
            Col("archived", "INTEGER", true, false),
            Col("join_code_required", "INTEGER", false, false),
            Col("muted", "TEXT", false, false),
            Col("ignored", "TEXT", false, false),
            Col("broadcast", "INTEGER", false, false),
            Col("prid", "TEXT", false, false),
            Col("draft_message", "TEXT", false, false),
            Col("draft_message_plain", "TEXT", false, false),
            Col("draft_reply_msg_id", "TEXT", false, false),
            Col("last_thread_sync", "REAL", false, false),
            Col("jitsi_timeout", "REAL", false, false),
            Col("auto_translate", "INTEGER", false, false),
            Col("auto_translate_language", "TEXT", true, false),
            Col("hide_unread_status", "INTEGER", false, false),
            Col("disable_notifications", "INTEGER", false, false),
            Col("sys_mes", "TEXT", false, false),
            Col("uids", "TEXT", false, false),
            Col("usernames", "TEXT", false, false),
            Col("visitor", "TEXT", false, false),
            Col("department_id", "TEXT", false, false),
            Col("served_by", "TEXT", false, false),
            Col("livechat_data", "TEXT", false, false),
            Col("tags", "TEXT", false, false),
            Col("e2e_key", "TEXT", false, false),
            Col("e2e_suggested_key", "TEXT", false, false),
            Col("encrypted", "INTEGER", false, false),
            Col("e2e_key_id", "TEXT", false, false),
            Col("avatar_etag", "TEXT", false, false),
            Col("team_id", "TEXT", true, false),
            Col("team_main", "INTEGER", false, false),
            Col("on_hold", "INTEGER", false, false),
            Col("source", "TEXT", false, false),
            Col("hide_mention_status", "INTEGER", false, false),
            Col("users_count", "REAL", false, false),
            Col("federated", "INTEGER", false, false),
            Col("rt", "TEXT", false, false),
            Col("todoCount", "REAL", false, false),
            Col("dname", "TEXT", false, false),
            Col("onCallStatus", "INTEGER", false, false),
            Col("callMsg", "TEXT", false, false),
            Col("bot", "INTEGER", false, false),
            Col("welcomeMsg", "TEXT", false, false),
            Col("memberName", "TEXT", false, false),
            Col("memberNumber", "REAL", false, false),
            Col("showAppiaTag", "REAL", false, false),
            Col("isRoomToDo", "INTEGER", false, false),
            Col("highTodoCount", "REAL", false, false),
            Col("defaultTodoCount", "REAL", false, false),
            Col("like", "INTEGER", false, false),
            Col("tSearch", "REAL", false, false),
            Col("appiaUsage", "TEXT", false, false),
            Col("draft_attachments", "TEXT", false, false),
        ),
        "messages" to listOf(
            Col("_id", "TEXT", true, true),
            Col("msg", "TEXT", false, false),
            Col("t", "TEXT", false, false),
            Col("rid", "TEXT", true, false),
            Col("ts", "REAL", true, false),
            Col("u", "TEXT", true, false),
            Col("roomSender", "TEXT", false, false),
            Col("rollbacker", "TEXT", false, false),
            Col("alias", "TEXT", true, false),
            Col("parse_urls", "TEXT", true, false),
            Col("groupable", "INTEGER", false, false),
            Col("avatar", "TEXT", false, false),
            Col("emoji", "TEXT", false, false),
            Col("attachments", "TEXT", false, false),
            Col("files", "TEXT", false, false),
            Col("urls", "TEXT", false, false),
            Col("_updated_at", "REAL", true, false),
            Col("status", "REAL", false, false),
            Col("pinned", "INTEGER", false, false),
            Col("starred", "INTEGER", false, false),
            Col("edited_by", "TEXT", false, false),
            Col("reactions", "TEXT", false, false),
            Col("role", "TEXT", false, false),
            Col("role_name", "TEXT", false, false),
            Col("drid", "TEXT", false, false),
            Col("dcount", "REAL", false, false),
            Col("dlm", "REAL", false, false),
            Col("tmid", "TEXT", false, false),
            Col("tcount", "REAL", false, false),
            Col("tlm", "REAL", false, false),
            Col("replies", "TEXT", false, false),
            Col("mentions", "TEXT", false, false),
            Col("channels", "TEXT", false, false),
            Col("unread", "INTEGER", false, false),
            Col("auto_translate", "INTEGER", false, false),
            Col("translations", "TEXT", false, false),
            Col("tmsg", "TEXT", false, false),
            Col("blocks", "TEXT", false, false),
            Col("e2e", "TEXT", false, false),
            Col("tshow", "INTEGER", false, false),
            Col("md", "TEXT", false, false),
            Col("comment", "TEXT", false, false),
            Col("msg_type", "TEXT", false, false),
            Col("msg_data", "TEXT", false, false),
            Col("survey_status", "INTEGER", false, false),
            Col("appia_todo", "TEXT", false, false),
            Col("local_record_path", "TEXT", false, false),
            Col("show_image_summary", "TEXT", false, false),
            Col("show_document_summary", "TEXT", false, false),
            Col("appia_quick_replies", "TEXT", false, false),
            Col("original_content", "TEXT", false, false),
        ),
        "users" to listOf(
            Col("_id", "TEXT", true, true),
            Col("name", "TEXT", true, false),
            Col("username", "TEXT", true, false),
            Col("avatar_etag", "TEXT", false, false),
        ),
        "settings" to listOf(
            Col("_id", "TEXT", true, true),
            Col("value_as_string", "TEXT", false, false),
            Col("value_as_boolean", "INTEGER", false, false),
            Col("value_as_number", "REAL", false, false),
            Col("_updated_at", "REAL", false, false),
        ),
        "uploads" to listOf(
            Col("_id", "TEXT", true, true),
            Col("rid", "TEXT", true, false),
            Col("path", "TEXT", true, false),
            Col("name", "TEXT", true, false),
            Col("size", "REAL", true, false),
            Col("type", "TEXT", true, false),
            Col("progress", "REAL", true, false),
            Col("error", "INTEGER", true, false),
        ),
        "custom_emojis" to listOf(
            Col("name", "TEXT", true, true),
            Col("aliases", "TEXT", false, false),
            Col("extension", "TEXT", true, false),
            Col("_updated_at", "REAL", true, false),
        ),
    )

    private fun rawTables(): Set<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
        }

    /** PRAGMA table_info 列序：cid/name/type/notnull/dflt/pk */
    private data class RawColumn(val type: String, val notNull: Int, val pk: Int)

    private fun rawColumns(table: String): Map<String, RawColumn> =
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
            generateSequence {
                if (c.moveToNext()) c.getString(1) to RawColumn(c.getString(2), c.getInt(3), c.getInt(5)) else null
            }.toMap()
        }

    @Test
    fun `all RN tables exist`() {
        val tables = rawTables()
        // schema.ts v6 共 8 表（无 threads：Thread.ts 为空文件且未注册，见报告）
        listOf(
            "rooms", "subscriptions", "chats", "messages",
            "users", "settings", "uploads", "custom_emojis",
        ).forEach { assertTrue("missing table $it", it in tables) }
    }

    @Test
    fun `every table column set matches schema ts`() {
        expectedColumns.forEach { (table, expected) ->
            val actual = rawColumns(table)
            assertEquals("column count mismatch for $table", expected.size, actual.size)
            expected.forEach { col ->
                val a = actual[col.name] ?: throw AssertionError("missing column ${col.name} in $table")
                assertEquals("$table.${col.name} type", col.sqliteType, a.type)
                assertEquals("$table.${col.name} notnull", if (col.notNull) 1 else 0, a.notNull)
                assertEquals("$table.${col.name} pk", if (col.pk) 1 else 0, a.pk)
            }
        }
    }

    // ---- round-trip：insert → getById → data class 全字段相等（覆盖可空与 JSON 字符串列） ----

    @Test
    fun `subscription round trip`() = runTest {
        val e = SubscriptionEntity(
            _id = "sub-1", f = false, t = "c", ts = 1700000000000.0, ls = 1700000001000.0,
            name = "general", fname = "General", rid = "rid-1", open = true, alert = true,
            unread = 3.0, user_mentions = 1.0, group_mentions = 0.0, room_updated_at = 1700000002000.0,
            ro = false, archived = false, auto_translate_language = "en", team_id = "",
            // JSON 列存原文
            roles = """["owner","moderator"]""",
            tunread = """["msg-1","msg-2"]""",
            last_message = """{"_id":"m1","msg":"hi"}""",
            uids = """["u1","u2"]""",
            usernames = """["alice","bob"]""",
            e2e_key_id = "key-1",
            prid = null, // 可空列保持 null
        )
        db.subscriptionDao().insert(e)
        assertEquals(e, db.subscriptionDao().getById("sub-1"))
        assertEquals(listOf(e), db.subscriptionDao().getAll())
    }

    @Test
    fun `chat round trip`() = runTest {
        val e = ChatEntity(
            _id = "rid-1", subscription_doc_id = "sub-doc-1",
            f = true, t = "p", ts = 1700000000000.0, ls = 1700000001000.0,
            name = "team", fname = "Team", rid = "rid-1", open = true, alert = false,
            unread = 0.0, user_mentions = 0.0, group_mentions = 0.0, room_updated_at = 1700000002000.0,
            ro = false, archived = false, auto_translate_language = "zh", team_id = "team-1",
            last_message = """{"_id":"m2","msg":"yo"}""",
            draft_message = """{"text":"draft"}""",
            draft_attachments = """["file://a.png"]""",
            muted = null,
        )
        db.chatDao().insert(e)
        assertEquals(e, db.chatDao().getById("rid-1"))
        assertEquals(listOf(e), db.chatDao().getAll())
    }

    @Test
    fun `room round trip`() = runTest {
        val e = RoomEntity(
            _id = "rid-1", custom_fields = """{"k":"v"}""",
            broadcast = false, encrypted = true, ro = false,
            v = "visitor-1", livechat_data = null, appiaUsage = """{"usage":1}""",
        )
        db.roomDao().insert(e)
        assertEquals(e, db.roomDao().getById("rid-1"))
        assertEquals(listOf(e), db.roomDao().getAll())
    }

    @Test
    fun `message round trip`() = runTest {
        val e = MessageEntity(
            _id = "msg-1", msg = "hello", t = "text", rid = "rid-1", ts = 1700000000000.0,
            u = """{"_id":"u1","username":"alice"}""",
            alias = "Alice", parse_urls = "true",
            attachments = """[{"title":"a"}]""",
            reactions = """{":thumbsup:":{"usernames":["alice"]}}""",
            tmid = "thread-1", // 线程消息 = tmid 指针（RN 无 threads 表）
            md = null, _updated_at = 1700000005000.0,
        )
        db.messageDao().insert(e)
        assertEquals(e, db.messageDao().getById("msg-1"))
        assertEquals(listOf(e), db.messageDao().getAll())
    }

    @Test
    fun `user round trip`() = runTest {
        val e = UserEntity(_id = "u1", name = "Alice", username = "alice", avatar_etag = null)
        db.userDao().insert(e)
        assertEquals(e, db.userDao().getById("u1"))
        assertEquals(listOf(e), db.userDao().getAll())
    }

    @Test
    fun `setting round trip`() = runTest {
        // 行 id 即 setting key（RN Setting.ts 注释）
        val e = SettingEntity(
            _id = "Site_Name", value_as_string = "Appia",
            value_as_boolean = null, value_as_number = null, _updated_at = 1700000000000.0,
        )
        db.settingDao().insert(e)
        assertEquals(e, db.settingDao().getById("Site_Name"))
        assertEquals(listOf(e), db.settingDao().getAll())
    }

    @Test
    fun `upload round trip`() = runTest {
        val e = UploadEntity(
            _id = "up-1", rid = "rid-1", path = "/tmp/f.png", name = "f.png",
            size = 1024.0, type = "image/png", progress = 50.0, error = false,
        )
        db.uploadDao().insert(e)
        assertEquals(e, db.uploadDao().getById("up-1"))
        assertEquals(listOf(e), db.uploadDao().getAll())
    }

    @Test
    fun `custom emoji round trip`() = runTest {
        val e = CustomEmojiEntity(
            name = "party_parrot", aliases = """["parrot"]""", extension = "png", _updated_at = 1700000000000.0,
        )
        db.customEmojiDao().insert(e)
        assertEquals(e, db.customEmojiDao().getById("party_parrot"))
        assertEquals(listOf(e), db.customEmojiDao().getAll())
    }

    @Test
    fun `dao update delete and observe work`() = runTest {
        val dao = db.subscriptionDao()
        val e = sampleSubscription()
        dao.insert(e)

        // observe 首发即当前快照
        assertEquals(1, dao.observe().first().size)

        val updated = e.copy(unread = 9.0, last_message = """{"_id":"m9","msg":"new"}""")
        dao.update(updated)
        assertEquals(updated, dao.getById("sub-1"))

        dao.delete(updated)
        assertNull(dao.getById("sub-1"))
        assertTrue(dao.observe().first().isEmpty())
    }

    private fun sampleSubscription() = SubscriptionEntity(
        _id = "sub-1", f = false, t = "c", ts = 1700000000000.0, ls = 1700000001000.0,
        name = "general", fname = "General", rid = "rid-1", open = true, alert = true,
        unread = 3.0, user_mentions = 1.0, group_mentions = 0.0, room_updated_at = 1700000002000.0,
        ro = false, archived = false, auto_translate_language = "en", team_id = "",
    )

    @Test
    fun `brief sample spot check on subscriptions columns`() {
        // 简报抽查列：PRAGMA 直接对 subscriptions 列名断言
        val cols = rawColumns("subscriptions").keys
        listOf(
            "_id", "f", "t", "name", "fname", "rid", "open", "alert",
            "unread", "user_mentions", "group_mentions",
        ).forEach { assertTrue("missing column $it", it in cols) }
    }
}
