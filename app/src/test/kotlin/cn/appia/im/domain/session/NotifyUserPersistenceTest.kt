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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * notify-user `removed` 删行链（RN notifyUserPersistence.ts:65-87 removeChatByRid 的 T4 切片）：
 * - `subscriptions-changed` removed 事件 → 物理删 chats 行（destroyPermanently 语义）
 * - activeDbMatchesAuth 守卫：active 库 != auth server 时不删
 * - 其余帧（updates / rooms / userData / added / 缺 rid）不触删行
 * - 房间流退订（T6）与 roomAccessLost（T11）留注释标记
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyUserPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var manager: DatabaseManager
    private lateinit var handler: NotifyUserPersistence

    @Before
    fun setUp() {
        manager = DatabaseManager(context)
        manager.switchDatabase("https://chat-a.example.com")
        val auth = AuthSessionStore(InMemoryKvStore())
        auth.save(AuthSession(token = "tok", user = AuthUser(id = "u-1"), serverUrl = "https://chat-a.example.com"))
        handler = NotifyUserPersistence(
            dbManager = manager,
            serverUrlProvider = { auth.load()?.serverUrl.orEmpty() },
            scope = CoroutineScope(Dispatchers.IO),
        )
    }

    @After
    fun tearDown() {
        manager.resetAll()
    }

    private fun parse(raw: String): JsonElement = Json.parseToJsonElement(raw)

    /** RN handleStreamNotifyUserForPersistence 的帧形态：`{msg, fields:{eventName, args:[type, data]}}`。 */
    private fun frame(eventName: String, argsJson: String): String =
        """{"msg":"changed","fields":{"eventName":"$eventName","args":$argsJson}}"""

    private fun removedFrame(rid: String) =
        frame("uid-1/subscriptions-changed", """["removed",{"rid":"$rid","t":"c"}]""")

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

    @Test
    fun `removed event physically deletes chats row`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))

        handler.handleStreamNotifyUser(parse(removedFrame("rid-1")))
        waitUntilDeleted("rid-1")

        assertNull(manager.active.chatDao().getById("rid-1"))
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
    fun `non removed events are ignored`() = runBlocking {
        manager.active.chatDao().insert(chatRow("rid-1"))

        handler.handleStreamNotifyUser(parse(frame("uid-1/subscriptions-changed", """["updated",{"rid":"rid-1"}]""")))
        handler.handleStreamNotifyUser(parse(frame("uid-1/rooms-changed", """["updated",{"_id":"rid-1"}]""")))
        handler.handleStreamNotifyUser(parse(frame("uid-1/userData", """["changed",{"diff":{}}]""")))
        handler.handleStreamNotifyUser(parse("{\"msg\":\"added\"}"))
        expectStillPresent(manager.active.chatDao(), "rid-1")

        assertEquals(1, manager.active.chatDao().getAll().size)
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

    /** handleStreamNotifyUser 是 fire-and-forget：等待异步删行完成再断言。 */
    private suspend fun waitUntilDeleted(id: String) {
        val dao = manager.active.chatDao()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            dao.getById(id) ?: return
            Thread.sleep(20)
        }
    }

    /**
     * 负向用例的反向断言（复用 waitUntilDeleted 轮询思路）：限时内发现行被删立即失败，
     * 时限耗尽仍存在才通过——比固定 sleep 更抗满载（慢 CI 多等，快失败早退）。
     */
    private suspend fun expectStillPresent(dao: ChatDao, id: String, timeoutMs: Long = 1_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (dao.getById(id) == null) throw AssertionError("row $id unexpectedly deleted")
            Thread.sleep(20)
        }
    }
}
