package cn.appia.im.domain.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * RoomsSyncRepository 集成测试（Robolectric + 文件库 + MockWebServer，按 path 分发应对并发两请求）。
 * 覆盖：响应三形态、create/update/delete 三分、全量 prune / 增量绝不 prune、增量游标、
 * 增量空包自动全量重拉一次、500 分批、activeDbMatchesAuth 守卫、游标推进规则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomsSyncRepositoryTest {

    private val context: Context = getApplicationContext()
    private val manager = DatabaseManager(context)
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()
    private val requestCount = AtomicInteger(0)

    private lateinit var sdk: RocketSdk
    private lateinit var repo: RoomsSyncRepository
    private lateinit var host: String

    /** 按 path 分发；前 `switchAfter` 个请求回 emptyBody，之后回 fullBody（空包重拉用）。 */
    private var subsBody = "{}"
    private var roomsBody = "{}"
    private var emptySubsBody: String? = null
    private var emptyRoomsBody: String? = null

    @Before
    fun setUp() {
        server.start()
        host = server.url("/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val i = requestCount.getAndIncrement()
                val subs = if (i < 2) emptySubsBody ?: subsBody else subsBody
                val rooms = if (i < 2) emptyRoomsBody ?: roomsBody else roomsBody
                return when {
                    request.path!!.startsWith("/api/v1/subscriptions.get") -> MockResponse().setBody(subs)
                    request.path!!.startsWith("/api/v1/rooms.get") -> MockResponse().setBody(rooms)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(host, "tok", "uid")
        manager.switchDatabase(host)
        repo = RoomsSyncRepository(sdk, manager, kv, host)
    }

    @After
    fun tearDown() {
        manager.resetAll()
        runCatching { server.shutdown() }
    }

    private fun dao() = manager.active.chatDao()

    private fun subJson(rid: String, name: String = "chan-$rid", unread: Int = 0) =
        """{"_id":"sub-$rid","rid":"$rid","t":"c","name":"$name","fname":"","unread":$unread,"_updatedAt":"2026-01-01T00:00:00.000Z"}"""

    private fun roomJson(rid: String) =
        """{"_id":"$rid","_updatedAt":"2026-01-01T00:00:00.000Z","description":"desc-$rid"}"""

    private fun envelope(update: String) = """{"update":[$update]}"""

    // ---- create + 响应形态 ----

    @Test
    fun `full sync creates merged row from update envelope`() = runBlocking {
        subsBody = envelope(subJson("r1", unread = 5))
        roomsBody = envelope(roomJson("r1"))

        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))

        val row = dao().getById("r1")
        assertNotNull(row)
        row!!
        assertEquals("r1", row._id)
        assertEquals("sub-r1", row.subscription_doc_id) // M0 语义：subscription_doc_id = sub._id
        assertEquals("chan-r1", row.name)
        assertEquals(5.0, row.unread, 0.0)
        assertEquals("desc-r1", row.description) // room 侧覆盖
        assertEquals(
            Instant.parse("2026-01-01T00:00:00.000Z").toEpochMilli().toDouble(),
            row.subscription_updated_at!!,
            0.0,
        )
        // 游标已推进且为可解析 ISO
        val cursor = kv.getString("roomsUpdatedAt:${host.trimEnd('/')}", "")
        assertTrue(cursor.isNotEmpty())
        assertNotNull(RoomsSyncCursor.parseIsoToMillis(cursor))
    }

    @Test
    fun `accepts subscriptions envelope and bare array shapes`() = runBlocking {
        // 形态 2：subscriptions 包裹（全量：r1 被服务端不再返回而 prune，见 prune 用例）
        subsBody = """{"subscriptions":[${subJson("r2")}]}"""
        roomsBody = """{"rooms":[${roomJson("r2")}]}"""
        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertNotNull(dao().getById("r2"))
        // 形态 3：裸数组
        subsBody = "[${subJson("r3")}]"
        roomsBody = "[${roomJson("r3")}]"
        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertNotNull(dao().getById("r3"))
        assertNull(dao().getById("r2")) // 全量 prune 只留服务端仍有的 r3
    }

    // ---- delete / prune ----

    @Test
    fun `remove list deletes by rid`() = runBlocking {
        subsBody = envelope(subJson("r1"))
        roomsBody = envelope(roomJson("r1"))
        repo.sync(RoomsSyncRepository.Mode.PULL)

        subsBody = """{"update":[],"remove":[{"_id":"sub-r1","rid":"r1"}]}"""
        roomsBody = """{"update":[]}"""
        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertNull(dao().getById("r1"))
    }

    @Test
    fun `full sync prunes local rows missing from server but incremental never prunes`() = runBlocking {
        subsBody = envelope("${subJson("r1")},${subJson("r2")}")
        roomsBody = envelope("${roomJson("r1")},${roomJson("r2")}")
        repo.sync(RoomsSyncRepository.Mode.PULL)
        assertNotNull(dao().getById("r2"))

        // 有游标 → 增量：只含 r1 变更，r2 不得被 prune
        subsBody = envelope(subJson("r1", unread = 9))
        roomsBody = envelope(roomJson("r1"))
        assertTrue(repo.sync(RoomsSyncRepository.Mode.BACKGROUND))
        assertEquals(9.0, dao().getById("r1")!!.unread, 0.0)
        assertNotNull(dao().getById("r2"))

        // 清游标 → 全量：服务端已无 r2，本地裁剪
        kv.remove("roomsUpdatedAt:${host.trimEnd('/')}")
        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertNull(dao().getById("r2"))
        assertNotNull(dao().getById("r1"))
    }

    // ---- 增量空包 → 自动全量重拉一次 ----

    @Test
    fun `incremental empty payload triggers exactly one full refetch`() = runBlocking {
        subsBody = envelope("${subJson("r1")},${subJson("r2")}")
        roomsBody = envelope("${roomJson("r1")},${roomJson("r2")}")
        repo.sync(RoomsSyncRepository.Mode.PULL)

        // 前 2 个请求（增量对）回空包，之后（重拉对）回只含 r1 的全量
        requestCount.set(0)
        emptySubsBody = """{"update":[]}"""
        emptyRoomsBody = """{"update":[]}"""
        subsBody = envelope(subJson("r1", unread = 7))
        roomsBody = envelope(roomJson("r1"))

        assertTrue(repo.sync(RoomsSyncRepository.Mode.BACKGROUND))

        // 从重置点起：增量一对 + 重拉一对，共 4 个请求（自动全量只重拉一次，不循环）
        assertEquals(4, requestCount.get())
        repeat(2) { server.takeRequest() } // 丢弃首次全量对
        val incSub = server.takeRequest()
        assertNotNull(incSub.requestUrl!!.queryParameter("updatedSince")) // 第一对走增量
        server.takeRequest()
        val retrySub = server.takeRequest()
        assertNull(retrySub.requestUrl!!.queryParameter("updatedSince")) // 重拉为全量
        assertEquals(7.0, dao().getById("r1")!!.unread, 0.0)
        // 重拉 isFullFetch=true：服务端已无 r2 → prune 生效（证明空包重拉确实切换为全量语义）
        assertNull(dao().getById("r2"))
    }

    // ---- 无变更不写库、不推游标 ----

    @Test
    fun `identical payload persists nothing and keeps cursor untouched`() = runBlocking {
        subsBody = envelope(subJson("r1"))
        roomsBody = envelope(roomJson("r1"))
        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))

        val cursorKey = "roomsUpdatedAt:${host.trimEnd('/')}"
        kv.putString(cursorKey, "2000-01-01T00:00:00Z") // 哨兵：同数据重放不应推进游标
        assertFalse(repo.sync(RoomsSyncRepository.Mode.PULL)) // 列级 diff 无变化 → false
        assertEquals("2000-01-01T00:00:00Z", kv.getString(cursorKey, ""))
    }

    @Test
    fun `empty update payload persists nothing`() = runBlocking {
        subsBody = """{"update":[]}"""
        roomsBody = """{"update":[]}"""
        assertFalse(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertTrue(dao().getAll().isEmpty())
        assertEquals("", kv.getString("roomsUpdatedAt:${host.trimEnd('/')}", ""))
    }

    // ---- 500 分批 ----

    @Test
    fun `large sync batches beyond 500 rows`() = runBlocking {
        val total = 1200
        val subs = (0 until total).joinToString(",") { subJson("r$it") }
        val rooms = (0 until total).joinToString(",") { roomJson("r$it") }
        subsBody = envelope(subs)
        roomsBody = envelope(rooms)

        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertEquals(total, dao().getAll().size)
        assertEquals("chan-r42", dao().getById("r42")!!.name)
    }

    // ---- 守卫 ----

    @Test
    fun `skips persist when active db does not match bound server`() = runBlocking {
        manager.switchDatabase("https://other.example.com") // active ≠ repo 绑定的 host 库
        subsBody = envelope(subJson("r1"))
        roomsBody = envelope(roomJson("r1"))

        assertFalse(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertEquals("", kv.getString("roomsUpdatedAt:${host.trimEnd('/')}", ""))

        manager.switchDatabase(host) // 恢复 active 后同数据可落库
        assertTrue(repo.sync(RoomsSyncRepository.Mode.PULL))
        assertNotNull(dao().getById("r1"))
    }

    // ---- 游标 ----

    @Test
    fun `cursor key format and invalid value parsing`() {
        val cursor = RoomsSyncCursor(kv)
        cursor.set("https://a.example.com///", 1_767_225_600_000L) // 2026-01-01T00:00:00.000Z
        assertEquals("2026-01-01T00:00:00.000Z", kv.getString("roomsUpdatedAt:https://a.example.com", ""))
        assertEquals(1_767_225_600_000L, RoomsSyncCursor.parseIsoToMillis(cursor.get("https://a.example.com")!!)!!)

        kv.putString("roomsUpdatedAt:b", "not-a-date")
        assertNull(cursor.get("b"))
        cursor.clear("b")
        assertNull(cursor.get("b"))
    }
}
