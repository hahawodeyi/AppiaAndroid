package cn.appia.im.feature.todo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.TodoItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 待办列表仓库 = react-query 等价缓存语义（RN useTodoListQuery.ts）：
 * 进屏装载 / rid 分槽 / 下拉刷新重拉不清屏 / mutation 成功失效全部槽 / success:false 与
 * 网络错误不失效 / 无 DDP 订阅（坑 1：拉取只发生在 进屏/失效/手动刷新 三时机）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TodoListRepositoryTest {

    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        AppiaDatabase::class.java,
    ).build()
    private val paths = mutableListOf<String>()
    private var respond: MockResponse = MockResponse().setResponseCode(200).setBody("""{"list":[],"total":0}""")
    private lateinit var repo: TodoListRepository

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.requestUrl!!.encodedPath + "?" + request.requestUrl!!.query
                return respond
            }
        }
        val sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        repo = TodoListRepository(sdk, TodoActions(sdk, db))
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() }
    }

    private fun listBody(vararg ids: String, total: Int = ids.size) =
        MockResponse().setResponseCode(200).setBody(
            """{"total":$total,"list":${ids.toList().joinToString(",", "[", "]") { id ->
                """{"id":"$id","createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"x","status":0,"mid":"m-$id"}"""
            }}}""",
        )

    // ---- 装载 / 分槽 ----

    @Test
    fun `fetch loads items total and clears loading`() = runBlocking {
        respond = listBody("t1", "t2", total = 5)

        repo.fetch(null)

        val state = repo.observe(null).value
        assertEquals(listOf("t1", "t2"), state.items.map { it.id })
        assertEquals(5.0, state.total, 0.0)
        assertFalse(state.isLoading)
        assertFalse(state.isError)
    }

    @Test
    fun `fetch appends rid only for room slot`() = runBlocking {
        respond = listBody()

        repo.fetch(null)
        repo.fetch("ROOM1")

        assertEquals("/api/v1/appia/todos?offset=0&count=1000", paths[0])
        assertEquals("/api/v1/appia/todos?offset=0&count=1000&rid=ROOM1", paths[1])
    }

    @Test
    fun `initial load failure sets error state with retry data intact`() = runBlocking {
        respond = listBody("t1")
        repo.fetch(null)
        respond = MockResponse().setResponseCode(500).setBody("boom")

        repo.fetch(null) // 下拉刷新撞上网络错误

        val state = repo.observe(null).value
        assertTrue(state.isError)
        assertEquals(listOf("t1"), state.items.map { it.id }) // 旧数据不清屏（RN refetch 语义）
    }

    // ---- 下拉刷新 ----

    @Test
    fun `manual refresh refetches same rid and replaces items`() = runBlocking {
        respond = listBody("t1")
        repo.fetch("R")
        respond = listBody("t2", "t3")

        repo.fetch("R")

        assertEquals(2, paths.size)
        assertEquals(listOf("t2", "t3"), repo.observe("R").value.items.map { it.id })
    }

    // ---- mutation 失效语义（RN invalidateQueries(todoQueryKeys.all)）----

    @Test
    fun `complete success invalidates all cached slots`() = runBlocking {
        respond = listBody("t1")
        repo.fetch(null)
        repo.fetch("ROOM1")
        paths.clear()
        respond = listBody("t9", total = 1)

        val ok = repo.complete(todoItem("t1", "m1"))

        assertTrue(ok)
        // POST + 两个已存在槽都重拉（坑 1：REST 线 invalidate；无 DDP 订阅触发）
        assertEquals(3, paths.size)
        assertTrue(paths[0].startsWith("/api/v1/appia/update-message-todo-status"))
        assertEquals(listOf("t9"), repo.observe(null).value.items.map { it.id })
        assertEquals(listOf("t9"), repo.observe("ROOM1").value.items.map { it.id })
        assertFalse(repo.isMutating.value)
    }

    @Test
    fun `complete success false does not invalidate`() = runBlocking {
        respond = listBody("t1")
        repo.fetch(null)
        paths.clear()
        respond = MockResponse().setResponseCode(200).setBody("""{"success":false}""")

        val ok = repo.complete(todoItem("t1", "m1"))

        assertFalse(ok)
        assertEquals(1, paths.size) // 仅 POST，无重拉
        assertEquals(listOf("t1"), repo.observe(null).value.items.map { it.id })
    }

    @Test
    fun `complete network failure propagates without invalidation`() = runBlocking {
        respond = listBody("t1")
        repo.fetch(null)
        paths.clear()
        respond = MockResponse().setResponseCode(500).setBody("boom")

        var thrown = false
        try {
            repo.complete(todoItem("t1", "m1"))
        } catch (e: Exception) {
            thrown = true
        }

        assertTrue(thrown)
        assertEquals(1, paths.size) // 仅 POST
        assertFalse(repo.isMutating.value)
        assertFalse(repo.observe(null).value.isError) // 列表态不被 mutation 失败污染
    }

    @Test
    fun `setReminder success invalidates and past rejection skips everything`() = runBlocking {
        respond = listBody("t1")
        repo.fetch(null)
        val item = todoItem("t1", "m1")
        paths.clear()
        respond = listBody("t1", "t2")

        assertTrue(repo.setReminder(item, "2030-01-01T00:00:00.000Z", nowMs = 1_700_000_000_000L))
        assertEquals(2, paths.size) // POST + 重拉
        assertTrue(paths[0].startsWith("/api/v1/appia/update-message-todo"))

        paths.clear()
        assertFalse(repo.setReminder(item, "2015-01-01T00:00:00.000Z", nowMs = 1_700_000_000_000L))
        assertTrue(paths.isEmpty()) // 拒提零请求零失效
    }

    @Test
    fun `unrelated slots are not created by invalidation`() = runBlocking {
        respond = listBody("t1")
        repo.fetch(null)
        paths.clear()
        respond = listBody("t9")

        repo.complete(todoItem("t1", "m1"))

        assertEquals(2, paths.size) // POST + 已存在的 null 槽重拉；"ROOM1" 槽未被动过
        assertTrue(paths[1].startsWith("/api/v1/appia/todos"))
        assertTrue(repo.observe("ROOM1").value.items.isEmpty()) // 未装载槽不被失效触碰（无副作用观察）
    }

    private fun todoItem(id: String, mid: String) = TodoItem(
        id = id, createdAt = "2026-01-01T00:00:00.000Z", updatedAt = "x", status = 0.0, mid = mid,
    )
}
