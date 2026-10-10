package cn.appia.im.feature.todo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 待办动作（RN useTodoListQuery mutations + RoomScreen handlers 语义）：
 * 完成双写（坑 12：POST 成功才清本地 appia_todo；success:false / 网络错误都不清）、
 * 消息未落库 no-op 吞错、设待办只 POST 不写本地（坑 3）、改提醒过去时间拒提不发请求（RN :79-82）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TodoActionsTest {

    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppiaDatabase::class.java,
    ).build()
    private val requests = mutableListOf<String>()
    private lateinit var actions: TodoActions

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path} ${request.body.readUtf8()}"
                return respond
            }
        }
        val sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        actions = TodoActions(sdk, db)
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() }
    }

    private var respond: MockResponse = MockResponse().setResponseCode(200).setBody("""{"success":true}""")

    private fun seedMessage(id: String, appiaTodo: String) = runBlocking {
        db.messageDao().insert(
            MessageEntity(
                _id = id, rid = "r1", ts = 1_000.0,
                u = """{"_id":"u1"}""", alias = "", parse_urls = "[]", _updated_at = 1_000.0,
                appia_todo = appiaTodo,
            ),
        )
    }

    private fun todoOf(id: String, mid: String) = cn.appia.im.core.network.api.TodoItem(
        id = id, createdAt = "2026-01-01T00:00:00.000Z", updatedAt = "2026-01-01T00:00:00.000Z",
        status = 0.0, mid = mid,
    )

    // ---- 完成双写（坑 12）----

    @Test
    fun `complete posts status -1 and clears local appiaTodo on success`() = runBlocking {
        seedMessage("m1", """{"status":0,"tid":"t1"}""")

        val ok = actions.complete("t1", "m1")

        assertTrue(ok)
        assertEquals(
            "POST /api/v1/appia/update-message-todo-status {\"id\":\"t1\",\"status\":-1}",
            requests.single(),
        )
        assertEquals("", db.messageDao().getById("m1")!!.appia_todo)
    }

    @Test
    fun `complete success false does not clear local and is not success`() = runBlocking {
        respond = MockResponse().setResponseCode(200).setBody("""{"success":false}""")
        seedMessage("m1", """{"status":0}""")

        val ok = actions.complete("t1", "m1")

        assertFalse(ok)
        assertEquals("""{"status":0}""", db.messageDao().getById("m1")!!.appia_todo)
    }

    @Test
    fun `complete network failure propagates and does not clear local`() = runBlocking {
        respond = MockResponse().setResponseCode(500).setBody("boom")
        seedMessage("m1", """{"status":0}""")

        var thrown = false
        try {
            actions.complete("t1", "m1")
        } catch (e: Exception) {
            thrown = true
        }

        assertTrue(thrown)
        assertEquals("""{"status":0}""", db.messageDao().getById("m1")!!.appia_todo)
    }

    @Test
    fun `complete missing message is a swallowed no-op`() = runBlocking {
        // RN clearMessageAppiaTodo catch：消息未落库（历史窗口外）不阻塞完成动作
        val ok = actions.complete("t1", "no-such-message")

        assertTrue(ok)
        assertEquals(1, requests.size)
        assertNull(db.messageDao().getById("no-such-message"))
    }

    // ---- 设待办（坑 3：本地不写，等 DDP 回流）----

    @Test
    fun `setTodo posts verbatim body and does not touch local message`() = runBlocking {
        seedMessage("m1", "")

        actions.setTodo("m1")

        assertEquals(
            "POST /api/v1/appia/set-message-todo {\"messageId\":\"m1\",\"status\":1,\"tips\":\"\",\"type\":\"d\"}",
            requests.single(),
        )
        // 坑 3：发送 status:1 但本地不写 0/1——回流 status=0 才是进行中
        assertEquals("", db.messageDao().getById("m1")!!.appia_todo)
    }

    // ---- 改提醒（过去时间拒提，RN :79-82）----

    @Test
    fun `setReminder past time rejected without any request`() = runBlocking {
        val now = 1_700_000_000_000L

        val ok = actions.setReminder(todoOf("t1", "m1"), "2015-01-01T00:00:00.000Z", nowMs = now)

        assertFalse(ok)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `setReminder equal to now rejected like RN date le now`() = runBlocking {
        // RN `date <= new Date()`：等点也拒（1_700_000_000_000ms = 2023-11-14T22:13:20Z）
        assertFalse(actions.setReminder(todoOf("t1", "m1"), "2023-11-14T22:13:20.000Z", nowMs = 1_700_000_000_000L))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `setReminder future posts full payload verbatim`() = runBlocking {
        val item = cn.appia.im.core.network.api.TodoItem(
            id = "t1", title = "T", tips = "tip", type = "d",
            createdAt = "2026-01-01T00:00:00.000Z", updatedAt = "2026-01-01T00:00:00.000Z",
            status = 0.0, mid = "m1",
        )

        val ok = actions.setReminder(item, "2030-01-01T00:00:00.000Z", nowMs = 1_700_000_000_000L)

        assertTrue(ok)
        assertEquals(
            "POST /api/v1/appia/update-message-todo " +
                """{"id":"t1","title":"T","tips":"tip","type":"d","reminderTime":"2030-01-01T00:00:00.000Z"}""",
            requests.single(),
        )
    }

    // ---- parseAppiaTodo / 进行中判定（长按菜单门控）----

    @Test
    fun `parseAppiaTodo status zero is in progress`() {
        assertTrue(isTodoInProgress("""{"status":0,"tid":"t1"}"""))
        assertFalse(isTodoInProgress("""{"status":1}"""))
        assertFalse(isTodoInProgress("""{"status":-1}"""))
        assertFalse(isTodoInProgress(null))
        assertFalse(isTodoInProgress("not-json"))
        assertFalse(isTodoInProgress("""{"tid":"t1"}"""))
    }

    @Test
    fun `parseAppiaTodo extracts tid`() {
        assertEquals("t9", parseAppiaTodo("""{"status":0,"tid":"t9"}""")!!.tid)
        assertNull(parseAppiaTodo("""{"status":0}""")!!.tid)
    }
}
