package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 待办 wire 对照（RN todos.ts:12-38 + messages.ts:54-62 + W1 清单 verbatim）：
 * GET appia/todos 参数与响应逐字段（含 attachments 数组、data 信封、total 回落、四段排序）、
 * 三个 POST 的 body 原样（status 语义不对称照抄：设 1 / 完成 -1）。
 */
class TodosApiTest {

    private val server = MockWebServer()
    private lateinit var sdk: RocketSdk

    @BeforeEach
    fun setUp() {
        server.start()
        sdk = RocketSdk().also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun itemJson(
        id: String,
        type: String? = null,
        reminderTime: String? = null,
        createdAt: String = "2026-01-01T00:00:00.000Z",
    ) = buildString {
        append("""{"id":"$id","createdAt":"$createdAt","updatedAt":"2026-01-02T00:00:00.000Z","status":0,"mid":"m-$id"""")
        type?.let { append(""","type":"$it"""") }
        reminderTime?.let { append(""","reminderTime":"$it"""") }
        append("}")
    }

    // ---- GET appia/todos ----

    @Test
    fun `fetchTodos GETs fixed offset and count`() = runBlocking {
        enqueue("""{"list":[],"total":0}""")

        fetchTodos(sdk)

        assertEquals("/api/v1/appia/todos?offset=0&count=1000", server.takeRequest().path)
    }

    @Test
    fun `fetchTodos appends rid only when provided`() = runBlocking {
        enqueue("""{"list":[],"total":0}""")
        enqueue("""{"list":[],"total":0}""")

        fetchTodos(sdk)
        fetchTodos(sdk, rid = "GENERAL")

        assertEquals("/api/v1/appia/todos?offset=0&count=1000", server.takeRequest().path)
        assertEquals("/api/v1/appia/todos?offset=0&count=1000&rid=GENERAL", server.takeRequest().path)
    }

    @Test
    fun `fetchTodos parses item fields verbatim including attachments`() = runBlocking {
        enqueue(
            """{"count":1,"total":1,"list":[{"id":"t1","title":"<b>title</b>","createdAt":"2026-01-01T00:00:00.000Z",""" +
                """"updatedAt":"2026-01-02T00:00:00.000Z","status":0,"mid":"m1","tips":"tip text",""" +
                """"attachments":[{"ts":"1700000000000","title_link":"https://f.example.com/a.pdf","title":"a.pdf",""" +
                """"image_url":"https://i.example.com/a.png","title_link_download":"true","format":"pdf","type":"file"}],""" +
                """"type":"h","reminderTime":"2026-02-01T08:30:00.000Z","isOvertime":true,"rid":"rid1","t":"p",""" +
                """"isMessageDeleted":false,"name":"room name"}]}""",
        )

        val result = fetchTodos(sdk)

        assertEquals(1.0, result.total)
        val item = result.items.single()
        assertEquals("t1", item.id)
        assertEquals("<b>title</b>", item.title)
        assertEquals("2026-01-01T00:00:00.000Z", item.createdAt)
        assertEquals("2026-01-02T00:00:00.000Z", item.updatedAt)
        assertEquals(0.0, item.status)
        assertEquals("m1", item.mid)
        assertEquals("tip text", item.tips)
        assertEquals("h", item.type)
        assertEquals("2026-02-01T08:30:00.000Z", item.reminderTime)
        assertEquals(true, item.isOvertime)
        assertEquals("rid1", item.rid)
        assertEquals("p", item.t)
        assertEquals(false, item.isMessageDeleted)
        assertEquals("room name", item.name)
        val att = item.attachments!!.single()
        assertEquals("1700000000000", att.ts)
        assertEquals("https://f.example.com/a.pdf", att.titleLink)
        assertEquals("a.pdf", att.title)
        assertEquals("https://i.example.com/a.png", att.imageUrl)
        assertEquals("true", att.titleLinkDownload)
        assertEquals("pdf", att.format)
        assertEquals("file", att.type)
    }

    @Test
    fun `fetchTodos tolerates unknown keys and missing optionals`() = runBlocking {
        // is* 布尔经 @SerialName 原名解析（kotlinx 默认剥 is 前缀的坑）；未知字段忽略
        enqueue("""{"list":[{"id":"t2","createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"x","status":1,"mid":"m2","isNewField":1}],"total":1}""")

        val item = fetchTodos(sdk).items.single()

        assertEquals("t2", item.id)
        assertNull(item.title)
        assertNull(item.type)
        assertNull(item.reminderTime)
        assertNull(item.isOvertime)
        assertNull(item.attachments)
        assertEquals(1.0, item.status)
    }

    @Test
    fun `fetchTodos unwraps data envelope like RN res dot data`() = runBlocking {
        // RN：res.data 即 TodoModel；sdk.get 已平铺 data 信封，wrapped/裸两形态等价
        enqueue("""{"data":{"list":[],"total":7}}""")

        assertEquals(7.0, fetchTodos(sdk).total)
    }

    @Test
    fun `fetchTodos total falls back to items length when missing`() = runBlocking {
        enqueue("""{"list":[${itemJson("a")},${itemJson("b")}]}""")

        val result = fetchTodos(sdk)

        assertEquals(2.0, result.total)
    }

    @Test
    fun `fetchTodos missing list yields empty items`() = runBlocking {
        enqueue("""{"total":5}""")

        val result = fetchTodos(sdk)

        assertEquals(0, result.items.size)
        assertEquals(5.0, result.total)
    }

    @Test
    fun `fetchTodos applies four-segment sort`() = runBlocking {
        // 段序：高优+提醒时间 ↑ → 高优无时间（createdAt ↑）→ 普通+时间 ↑ → 普通无时间 ↑
        val lowNoTime = itemJson("lowNo", createdAt = "2026-01-04T00:00:00.000Z")
        val lowWithTime = itemJson("lowTime", reminderTime = "2026-03-02T00:00:00.000Z")
        val highNoTime = itemJson("highNo", type = "h", createdAt = "2026-01-02T00:00:00.000Z")
        val highTime2 = itemJson("highTime2", type = "h", reminderTime = "2026-03-01T00:00:00.000Z")
        val highTime1 = itemJson("highTime1", type = "h", reminderTime = "2026-02-01T00:00:00.000Z")
        val lowNoTime2 = itemJson("lowNo2", createdAt = "2026-01-01T00:00:00.000Z")
        enqueue("""{"list":[$lowNoTime,$lowWithTime,$highNoTime,$highTime2,$highTime1,$lowNoTime2],"total":6}""")

        val ids = fetchTodos(sdk).items.map { it.id }

        assertEquals(listOf("highTime1", "highTime2", "highNo", "lowTime", "lowNo2", "lowNo"), ids)
    }

    // ---- POST set-message-todo ----

    @Test
    fun `toggleTodoMessage posts verbatim set body`() = runBlocking {
        enqueue("""{"success":true}""")

        toggleTodoMessage(sdk, "msg-1")

        val request = server.takeRequest()
        assertEquals("/api/v1/appia/set-message-todo", request.path)
        assertEquals("POST", request.method)
        assertEquals("""{"messageId":"msg-1","status":1,"tips":"","type":"d"}""", request.body.readUtf8())
    }

    // ---- POST update-message-todo-status ----

    @Test
    fun `completeTodo posts id and negative status`() = runBlocking {
        enqueue("""{"success":true}""")

        completeTodo(sdk, "t1")

        val request = server.takeRequest()
        assertEquals("/api/v1/appia/update-message-todo-status", request.path)
        assertEquals("""{"id":"t1","status":-1}""", request.body.readUtf8())
    }

    // ---- POST update-message-todo ----

    @Test
    fun `updateTodo posts all provided fields`() = runBlocking {
        enqueue("""{"success":true}""")

        updateTodo(sdk, "t1", title = "T", tips = "tip", type = "d", reminderTime = "2026-03-01T00:00:00.000Z")

        assertEquals(
            """{"id":"t1","title":"T","tips":"tip","type":"d","reminderTime":"2026-03-01T00:00:00.000Z"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `updateTodo omits null fields like JS undefined`() = runBlocking {
        enqueue("""{"success":true}""")

        updateTodo(sdk, "t1", reminderTime = "2026-03-01T00:00:00.000Z")

        assertEquals(
            """{"id":"t1","reminderTime":"2026-03-01T00:00:00.000Z"}""",
            server.takeRequest().body.readUtf8(),
        )
    }
}
