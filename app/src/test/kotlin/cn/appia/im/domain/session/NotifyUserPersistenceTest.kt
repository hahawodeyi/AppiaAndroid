package cn.appia.im.domain.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.dao.ChatDao
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.network.AuthUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * notify-user 持久化链（RN notifyUserPersistence.ts 全语义）：
 * - `removed` 删行不回归（T4 切片）+ removed 退订房间流缝 + 撤销待 flush 补丁（:66）
 * - `subscriptions-changed` updates / `rooms-changed` updated|inserted 入队，[flushMs] 合并窗口后
 *   经 ChatMerger 合并 upsert 进 `chats`（RN queue* 与 flushPendingToDatabase）
 * - activeDbMatchesAuth 守卫：active 库 != auth server 时不删不写
 * - clearQueue（RN clearNotifyUserPersistenceQueue）、userData 占位、rooms 非 updated|inserted 不入队
 *
 * `flushMs = 60` 短窗口注入（RN FLUSH_MS=500 的测试等价通道）；定时竞态一律轮询断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyUserPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseManager
    private lateinit var auth: AuthSessionStore
    private lateinit var handler: NotifyUserPersistence
    private val scopes = ConcurrentLinkedQueue<CoroutineScope>()
    private val unsubscribed = ConcurrentLinkedQueue<String>()

    @Before
    fun setUp() {
        manager = DatabaseManager(context)
        manager.switchDatabase("https://chat-a.example.com")
        auth = AuthSessionStore(InMemoryKvStore())
        auth.save(AuthSession(token = "tok", user = AuthUser(id = "u-1"), serverUrl = "https://chat-a.example.com"))
        handler = newHandler()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        manager.resetAll()
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO).also { scopes.add(it) }

    private fun newHandler(flushMs: Long = 60): NotifyUserPersistence = NotifyUserPersistence(
        dbManager = manager,
        serverUrlProvider = { auth.load()?.serverUrl.orEmpty() },
        scope = newScope(),
        flushMs = flushMs,
        unsubscribeRoom = { rid -> unsubscribed.add(rid) },
    )

    private fun parse(raw: String): JsonElement = Json.parseToJsonElement(raw)

    /** RN handleStreamNotifyUserForPersistence 的帧形态：`{msg, fields:{eventName, args:[type, data]}}`。 */
    private fun frame(eventName: String, argsJson: String): String =
        """{"msg":"changed","fields":{"eventName":"$eventName","args":$argsJson}}"""

    private fun removedFrame(rid: String) =
        frame("uid-1/subscriptions-changed", """["removed",{"rid":"$rid","t":"c"}]""")

    private fun subUpdatedFrame(rid: String, unread: Int) = frame(
        "uid-1/subscriptions-changed",
        """["updated",{"rid":"$rid","name":"chan-$rid","unread":$unread,"_updatedAt":"2026-01-01T00:00:00.000Z"}]""",
    )

    private fun roomUpdatedFrame(rid: String, lastMsg: String) = frame(
        "uid-1/rooms-changed",
        """["updated",{"_id":"$rid","t":"p","_updatedAt":"2026-01-01T00:00:00.000Z",""" +
            """"lastMessage":{"msg":"$lastMsg","ts":"2026-01-01T00:00:00.000Z"}}]""",
    )

    private fun chatRow(id: String) = ChatEntity(
        _id = id,
        f = false,
        t = "c",
        ts = 0.0,
        ls = 0.0,
        name = "n",
        fname = "",
        rid = id,
        open = true,
        alert = false,
        unread = 0.0,
        user_mentions = 0.0,
        group_mentions = 0.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        team_id = "",
    )

    // ---- removed 删行（T4 回归钉）----

    @Test
    fun `removed event physically deletes chats row`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))

        handler.handleStreamNotifyUser(parse(removedFrame("rid-1")))
        waitUntilDeleted("rid-1")

        assertNull(manager.active.chatDao().getById("rid-1"))
    }

    @Test
    fun `removed triggers room stream unsubscribe hook`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))

        handler.handleStreamNotifyUser(parse(removedFrame("rid-1")))
        waitUntilDeleted("rid-1")

        assertEquals(listOf("rid-1"), unsubscribed.toList()) // RN :85 unsubscribeRoomStreams
    }

    @Test
    fun `delete is skipped when active db does not match auth server`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))
        // 换主体：active 指向 B，auth 仍在 A（构造时保存）→ 守卫拦截
        manager.switchDatabase("https://chat-b.example.com")

        handler.handleStreamNotifyUser(parse(removedFrame("rid-1")))
        // A 库（auth 主体对应的库）不该被误删：反向轮询限时确认未被删（防 sleep 定时竞态）
        expectStillPresent(manager.databaseFor(manager.normalizeServer("https://chat-a.example.com")).chatDao(), "rid-1")
        assertNotNull(
            manager.databaseFor(manager.normalizeServer("https://chat-a.example.com")).chatDao().getById("rid-1"),
        )
    }

    @Test
    fun `removed without rid is ignored`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))
        handler.handleStreamNotifyUser(
            parse(frame("uid-1/subscriptions-changed", """["removed",{"t":"c"}]""")),
        )
        expectStillPresent(manager.active.chatDao(), "rid-1")
        assertEquals(1, manager.active.chatDao().getAll().size)
    }

    // ---- updates 入队 + 500ms 合并落库 ----

    @Test
    fun `subscription update patch is merged upserted into chats after flush window`() = runBlocking {
        handler.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 5)))

        waitUntilAppears("rid-1")
        val row = manager.active.chatDao().getById("rid-1")!!
        assertEquals("chan-rid-1", row.name)
        assertEquals(5.0, row.unread, 0.0)
    }

    @Test
    fun `second patch for same rid within window wins (last write)`() = runBlocking {
        handler.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 1)))
        handler.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 7)))

        waitUntilAppears("rid-1")
        assertEquals(7.0, manager.active.chatDao().getById("rid-1")!!.unread, 0.0)
        // 合并窗口：同 rid 两帧只落一行
        assertEquals(1, manager.active.chatDao().getAll().size)
    }

    @Test
    fun `sub and room patches for same rid merge into one row (room covers room side fields)`() = runBlocking {
        handler.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 3)))
        handler.handleStreamNotifyUser(parse(roomUpdatedFrame("rid-1", lastMsg = "hello")))

        waitUntilAppears("rid-1")
        val row = manager.active.chatDao().getById("rid-1")!!
        assertEquals(3.0, row.unread, 0.0) // sub 侧
        assertEquals("p", row.t) // room 侧 t（sub 无 t）
        assertNotNull(row.last_message)
        assertTrue(row.last_message!!.contains("hello")) // room lastMessage（sub 无真值时不覆盖）
    }

    @Test
    fun `room only patch creates row via rid placeholder subscription`() = runBlocking {
        handler.handleStreamNotifyUser(parse(roomUpdatedFrame("rid-9", lastMsg = "room only")))

        waitUntilAppears("rid-9")
        val row = manager.active.chatDao().getById("rid-9")!!
        assertEquals("rid-9", row._id) // RN mergeSubscriptionAndRoom(sub ?? {rid}, room) 的 _id
        assertEquals("p", row.t)
    }

    @Test
    fun `rooms changed with non updated type is not queued`() = runBlocking {
        handler.handleStreamNotifyUser(parse(frame("uid-1/rooms-changed", """["removed",{"_id":"rid-1"}]""")))
        handler.handleStreamNotifyUser(parse(frame("uid-1/rooms-changed", """["changed",{"_id":"rid-1"}]""")))
        expectStillAbsent(manager.active.chatDao(), "rid-1")
    }

    @Test
    fun `update patch without rid is not queued`() = runBlocking {
        handler.handleStreamNotifyUser(parse(frame("uid-1/subscriptions-changed", """["updated",{"name":"x"}]""")))
        handler.handleStreamNotifyUser(parse(frame("uid-1/rooms-changed", """["updated",{"name":"x"}]""")))
        expectStillAbsent(manager.active.chatDao(), "rid-1")
        assertEquals(0, manager.active.chatDao().getAll().size)
    }

    @Test
    fun `flush is skipped when active db does not match auth server`() = runBlocking {
        manager.switchDatabase("https://chat-b.example.com") // active=B，auth=A
        handler.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 1)))

        // flush 窗口过后（守卫跳过）：A 库（auth 主体）不落行
        expectStillAbsent(
            manager.databaseFor(manager.normalizeServer("https://chat-a.example.com")).chatDao(),
            "rid-1",
            timeoutMs = 800,
        )
    }

    // ---- removed 撤销待 flush 补丁（RN :66 pending.delete，handler 同步段）----

    @Test
    fun `removed discards queued patch so flush does not resurrect the row`() = runBlocking {
        // 长窗口：入队后手动 flush，验证 pending 已被 removed 清除
        val slow = newHandler(flushMs = 60_000)
        manager.active.chatDao().insert(chatRow("rid-1"))

        slow.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 2))) // 入队
        slow.handleStreamNotifyUser(parse(removedFrame("rid-1"))) // 删行 + 撤销补丁
        waitUntilDeleted("rid-1")

        slow.flushPendingToDatabase() // 若 pending 未被撤销，这里会把行写回
        assertNull(manager.active.chatDao().getById("rid-1"))
    }

    /**
     * 撤销在 handler **同步段**（RN async fn 首个 await 前等价；总纲 §4.3-4）：
     * scope 冻结使 launch 体永不执行——removed 若把撤销放进协程，入队补丁不会被清，flush 即写回。
     */
    @Test
    fun `removed discards queued patch synchronously before any coroutine runs`() = runBlocking {
        val frozenDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
        }
        val frozen = NotifyUserPersistence(
            dbManager = manager,
            serverUrlProvider = { auth.load()?.serverUrl.orEmpty() },
            scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + frozenDispatcher),
            flushMs = 60_000,
            unsubscribeRoom = { rid -> unsubscribed.add(rid) },
        )

        frozen.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 2))) // 入队
        frozen.handleStreamNotifyUser(parse(removedFrame("rid-1"))) // 同步段撤销（launch 体未跑）

        frozen.flushPendingToDatabase() // pending 已空 → 不得写回
        expectStillAbsent(manager.active.chatDao(), "rid-1", timeoutMs = 300)
    }

    // ---- clearQueue（teardown 挂点）----

    @Test
    fun `clearQueue cancels scheduled flush`() = runBlocking {
        handler.handleStreamNotifyUser(parse(subUpdatedFrame("rid-1", unread = 4)))
        handler.clearQueue() // RN clearNotifyUserPersistenceQueue
        expectStillAbsent(manager.active.chatDao(), "rid-1", timeoutMs = 800)
    }

    // ---- userData 占位 / added 短路 ----

    @Test
    fun `userData and added frames do not touch chats`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))

        handler.handleStreamNotifyUser(parse(frame("uid-1/userData", """["changed",{"diff":{}}]""")))
        handler.handleStreamNotifyUser(parse("""{"msg":"added","fields":{"eventName":"uid-1/subscriptions-changed","args":[]}}"""))
        handler.handleStreamNotifyUser(parse(frame("uid-1/subscriptions-changed", """["updated"]""")))
        handler.handleStreamNotifyUser(parse(frame("no-slash", """["updated",{"rid":"rid-1"}]""")))

        expectStillPresent(manager.active.chatDao(), "rid-1", timeoutMs = 300)
        assertEquals(1, manager.active.chatDao().getAll().size) // 无写入
    }

    /** handleStreamNotifyUser 的写路径是异步的：轮询等行出现。 */
    private suspend fun waitUntilAppears(id: String, timeoutMs: Long = 5_000) {
        val dao = manager.active.chatDao()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dao.getById(id)?.let { return }
            Thread.sleep(20)
        }
        throw AssertionError("row $id never appeared")
    }

    /** handleStreamNotifyUser 是 fire-and-forget：等待异步删行完成再断言。 */
    private suspend fun waitUntilDeleted(id: String) {
        val dao = manager.active.chatDao()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            dao.getById(id) ?: return
            Thread.sleep(20)
        }
    }

    /** 负向用例的反向断言：限时内行被删/出现即失败（防 sleep 定时竞态）。 */
    private suspend fun expectStillPresent(dao: ChatDao, id: String, timeoutMs: Long = 1_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (dao.getById(id) == null) throw AssertionError("row $id unexpectedly deleted")
            Thread.sleep(20)
        }
    }

    private suspend fun expectStillAbsent(dao: ChatDao, id: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (dao.getById(id) != null) throw AssertionError("row $id unexpectedly created")
            Thread.sleep(20)
        }
    }
}
