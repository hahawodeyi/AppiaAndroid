package cn.appia.im.core.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.SubscriptionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ReadStateWriter 双表写字段组对照（RN readMessages.ts:20-34 applyLocalReadStateToRow）：
 * `open=true, alert=false, unread=0, userMentions=0, groupMentions=0, ls=now`，
 * `updateLastOpen=true` 另写 `lastOpen=now`。行不存在静默跳过（RN :55-58/:76-78 catch 同义）。
 * 双表写的原因：RN :61「只更 subscription 会被 DDP 增量把 open 写没」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadStateWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseManager
    private lateinit var writer: ReadStateWriter

    @Before
    fun setUp() {
        manager = DatabaseManager(context)
        manager.switchDatabase("https://chat-a.example.com")
        writer = ReadStateWriter(manager.active)
    }

    @After
    fun tearDown() {
        manager.resetAll()
    }

    private fun subRow(id: String, rid: String = id) = SubscriptionEntity(
        _id = id,
        f = false,
        t = "c",
        ts = 0.0,
        ls = 111.0,
        name = "n",
        fname = "",
        rid = rid,
        open = true,
        alert = true,
        unread = 7.0,
        user_mentions = 2.0,
        group_mentions = 3.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        last_open = 55.0,
        team_id = "",
    )

    private fun chatRow(id: String) = cn.appia.im.core.database.entity.ChatEntity(
        _id = id,
        f = false,
        t = "c",
        ts = 0.0,
        ls = 222.0,
        name = "n",
        fname = "",
        rid = id,
        open = true,
        alert = true,
        unread = 9.0,
        user_mentions = 4.0,
        group_mentions = 5.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        last_open = 66.0,
        team_id = "",
    )

    @Test
    fun `applies read state field group to both tables`() = kotlinx.coroutines.runBlocking {
        val db = manager.active
        db.subscriptionDao().insert(subRow("rid-1"))
        db.chatDao().insert(chatRow("rid-1"))

        writer.applyReadState("rid-1", now = 1_000L)

        val sub = db.subscriptionDao().getById("rid-1")!!
        assertEquals(true, sub.open)
        assertEquals(false, sub.alert)
        assertEquals(0.0, sub.unread, 0.0)
        assertEquals(0.0, sub.user_mentions, 0.0)
        assertEquals(0.0, sub.group_mentions, 0.0)
        assertEquals(1_000.0, sub.ls, 0.0)
        assertEquals(55.0, sub.last_open!!, 0.0) // 未开 updateLastOpen 不动 lastOpen
        val chat = db.chatDao().getById("rid-1")!!
        assertEquals(true, chat.open)
        assertEquals(false, chat.alert)
        assertEquals(0.0, chat.unread, 0.0)
        assertEquals(0.0, chat.user_mentions, 0.0)
        assertEquals(0.0, chat.group_mentions, 0.0)
        assertEquals(1_000.0, chat.ls, 0.0)
        assertEquals(66.0, chat.last_open!!, 0.0)
    }

    @Test
    fun `updateLastOpen also writes lastOpen`() = kotlinx.coroutines.runBlocking {
        val db = manager.active
        db.subscriptionDao().insert(subRow("rid-2"))
        db.chatDao().insert(chatRow("rid-2"))

        writer.applyReadState("rid-2", now = 2_000L, updateLastOpen = true)

        assertEquals(2_000.0, db.subscriptionDao().getById("rid-2")!!.last_open!!, 0.0)
        assertEquals(2_000.0, db.chatDao().getById("rid-2")!!.last_open!!, 0.0)
    }

    @Test
    fun `missing rows are skipped silently`() = kotlinx.coroutines.runBlocking {
        // 双双不存在：不抛（RN find(rid) catch 静默跳过同义）
        writer.applyReadState("ghost", now = 3_000L)
        writer.applyReadState("ghost", now = 3_000L, updateLastOpen = true)
    }

    @Test
    fun `subscription keyed by sub id not rid is skipped like RN`() = kotlinx.coroutines.runBlocking {
        // RN 按主键 find(rid)：subscriptions 行主键不是 rid 时静默跳过（本仓库现网 subscriptions 表未启用）
        val db = manager.active
        db.subscriptionDao().insert(subRow(id = "sub-9", rid = "rid-9"))
        db.chatDao().insert(chatRow("rid-9"))

        writer.applyReadState("rid-9", now = 4_000L)

        val sub = db.subscriptionDao().getById("sub-9")!!
        assertEquals(7.0, sub.unread, 0.0) // 未被改写
        assertEquals(0.0, db.chatDao().getById("rid-9")!!.unread, 0.0) // chats 正常写入
        assertNotNull(sub)
    }
}
