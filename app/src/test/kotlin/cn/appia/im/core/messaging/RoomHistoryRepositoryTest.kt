package cn.appia.im.core.messaging

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * RoomHistoryRepository 集成测试（Robolectric + 内存 Room + MockWebServer）：
 * prefix 映射全表、`roomId/count/latest` 参数（latest 为 ISO 串，不用 oldest）、
 * 落库计数、瞬时失败重试 2 次退避 1s/2s、4xx 不重试、失败返回 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomHistoryRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java).build()
    private val backoff = ConcurrentLinkedQueue<Long>()
    private val paths = ConcurrentLinkedQueue<String>()

    /** 按测试注入的响应计划；缺省回空 messages。 */
    private var respond: (RecordedRequest) -> MockResponse = {
        MockResponse().setBody("""{"messages":[]}""")
    }

    private lateinit var repo: RoomHistoryRepository
    private lateinit var host: String

    @Before
    fun setUp() {
        server.start()
        host = server.url("/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add(request.requestUrl!!.encodedPath)
                return respond(request)
            }
        }
        val sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(host, "tok", "uid")
        repo = RoomHistoryRepository(sdk, db) { ms -> backoff.add(ms) }
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() }
    }

    private fun historyBody(vararg ids: String, rid: String = "r1") =
        """{"messages":[${ids.joinToString(",") { id ->
            """{"_id":"$id","rid":"$rid","ts":1767225600000}"""
        }}]}"""

    // ---- prefix 映射全表 ----

    @Test
    fun `prefix maps c l to channels d to im p to groups`() = runBlocking {
        for ((t, prefix) in listOf("c" to "channels", "l" to "channels", "d" to "im", "p" to "groups")) {
            assertEquals(0, repo.loadRoomHistory("r1", t))
        }
        assertEquals(4, paths.size)
        assertEquals(
            listOf("/api/v1/channels.history", "/api/v1/channels.history", "/api/v1/im.history", "/api/v1/groups.history"),
            paths.toList(),
        )
    }

    @Test
    fun `unknown room type returns empty without touching network`() = runBlocking {
        for (t in listOf("x", "e2e", "")) {
            assertEquals(0, repo.loadRoomHistory("r1", t))
        }
        assertTrue(paths.isEmpty())
        assertTrue(db.messageDao().getByRid("r1", 10).isEmpty())
    }

    // ---- 参数：roomId + count + latest（ISO 串，不用 oldest） ----

    @Test
    fun `sends roomId count and latest iso param`() = runBlocking {
        repo.loadRoomHistory("r1", "d", latest = "2026-01-01T00:00:00.000Z")
        val req = server.takeRequest()
        assertEquals("/api/v1/im.history", req.requestUrl!!.encodedPath)
        assertEquals("r1", req.requestUrl!!.queryParameter("roomId"))
        assertEquals("50", req.requestUrl!!.queryParameter("count"))
        assertEquals("2026-01-01T00:00:00.000Z", req.requestUrl!!.queryParameter("latest"))
        assertNull(req.requestUrl!!.queryParameter("oldest"))
    }

    @Test
    fun `omits latest param when absent`() = runBlocking {
        repo.loadRoomHistory("r1", "c")
        val req = server.takeRequest()
        assertEquals("r1", req.requestUrl!!.queryParameter("roomId"))
        assertNull(req.requestUrl!!.queryParameter("latest"))
    }

    // ---- 落库 ----

    @Test
    fun `persists messages and returns count`() = runBlocking {
        respond = { MockResponse().setBody(historyBody("m1", "m2", "m3")) }
        assertEquals(3, repo.loadRoomHistory("r1", "p"))
        val rows = db.messageDao().getByRid("r1", 10)
        assertEquals(listOf("m1", "m2", "m3"), rows.map { it._id })
        assertEquals(1767225600000.0, rows.first().ts, 0.0)
    }

    @Test
    fun `missing or empty messages returns zero`() = runBlocking {
        assertEquals(0, repo.loadRoomHistory("r1", "d")) // 缺 messages 键
        respond = { MockResponse().setBody("""{"messages":[]}""") }
        assertEquals(0, repo.loadRoomHistory("r1", "d"))
        assertTrue(db.messageDao().getByRid("r1", 10).isEmpty())
    }

    // ---- 重试：瞬时失败 2 次退避 1s/2s ----

    @Test
    fun `retries transient 5xx twice with 1s 2s backoff then succeeds`() = runBlocking {
        respond = { req ->
            if (paths.size <= 2) MockResponse().setResponseCode(500) else MockResponse().setBody(historyBody("m1"))
        }
        assertEquals(1, repo.loadRoomHistory("r1", "c"))
        assertEquals(3, paths.size) // 首次 + 重试 2 次
        assertEquals(listOf(1_000L, 2_000L), backoff.toList())
        assertNotNull(db.messageDao().getById("m1"))
    }

    @Test
    fun `gives up after two retries and returns null without persisting`() = runBlocking {
        respond = { MockResponse().setResponseCode(500) }
        assertNull(repo.loadRoomHistory("r1", "c"))
        assertEquals(3, paths.size)
        assertEquals(listOf(1_000L, 2_000L), backoff.toList())
        assertTrue(db.messageDao().getByRid("r1", 10).isEmpty())
    }

    @Test
    fun `does not retry client errors`() = runBlocking {
        respond = { MockResponse().setResponseCode(400).setBody("""{"error":"bad-request"}""") }
        assertNull(repo.loadRoomHistory("r1", "c"))
        assertEquals(1, paths.size)
        assertTrue(backoff.isEmpty())
    }
}
