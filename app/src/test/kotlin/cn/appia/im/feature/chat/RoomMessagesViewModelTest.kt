package cn.appia.im.feature.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.RoomHistoryRepository
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * RoomMessagesViewModel 分页行为测试（Robolectric + 内存 Room + MockWebServer）：
 * 进房并发与 5s 兜底、loadEarlier 三分支（0/short/noGrowth×2）+ 失败不关 hasMore、
 * 游标 = 本地最旧 ts 的 ISO 串、refresh upsert 去重、换房间全量重置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomMessagesViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ConcurrentLinkedQueue<HttpUrl>()

    /** 按测试注入的响应计划（读 URL 的 roomId/latest 路由）；缺省回空 messages。 */
    private var respond: (HttpUrl) -> MockResponse = { MockResponse().setBody("""{"messages":[]}""") }

    private lateinit var repo: RoomHistoryRepository
    private lateinit var vm: RoomMessagesViewModel
    private lateinit var host: String

    @Before
    fun setUp() {
        server.start()
        host = server.url("/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request.requestUrl!!)
                return respond(request.requestUrl!!)
            }
        }
        val sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(host, "tok", "uid")
        // 退避睡眠注入 no-op：VM 用例不压真实等待（重试语义归 RoomHistoryRepositoryTest）
        repo = RoomHistoryRepository(sdk, db) { }
        vm = RoomMessagesViewModel(repo, db, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        runCatching { server.shutdown() }
    }

    // ---- 夹具 ----

    private fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("timeout: $desc")
    }

    private fun openRoomAndSettled(respondBody: (HttpUrl) -> MockResponse = respond) {
        respond = respondBody
        vm.openRoom(RID, "d")
        awaitCond("initial load settled") { !vm.state.value.isInitialLoading }
    }

    /** 本地种子：`seed-0..n` ts 递减（DESC 窗口：首条最新、末条最旧 = BASE - (n-1)s）。 */
    private fun seedLocal(count: Int, rid: String = RID, idPrefix: String = "seed") = runBlocking {
        repeat(count) { i ->
            db.messageDao().insert(
                MessageEntity(
                    _id = "$idPrefix-$i",
                    rid = rid,
                    ts = BASE - i * 1_000.0,
                    msg = "local",
                    u = "{}",
                    alias = "",
                    parse_urls = "",
                    _updated_at = 0.0,
                ),
            )
        }
    }

    /** 服务端历史：`$idPrefix-0..count` ts 从 [startAt] 递减。 */
    private fun history(count: Int, idPrefix: String = "srv", startAt: Long = BASE - 50_000L) =
        """{"messages":[${(0 until count).joinToString(",") { i ->
            """{"_id":"$idPrefix-$i","rid":"$RID","ts":${startAt - i * 1_000}}"""
        }}]}"""

    private val empty get() = MockResponse().setBody("""{"messages":[]}""")
    private val broken get() = MockResponse().setResponseCode(500)

    // ---- 进房（本地 + 远程并发）----

    @Test
    fun `initial load keeps local window when remote empty`() {
        seedLocal(3)
        openRoomAndSettled()
        val s = vm.state.value
        assertEquals(3, s.messages.size)
        assertFalse(s.isInitialLoading)
        assertEquals(RID, s.rid)
    }

    @Test
    fun `initial load fetches remote 50 concurrently and re-queries`() {
        openRoomAndSettled { MockResponse().setBody(history(50)) }
        val s = vm.state.value
        assertEquals(50, s.messages.size)
        assertFalse(s.isInitialLoading)
        assertTrue(s.messages.all { it._id.startsWith("srv-") })
    }

    @Test
    fun `initial load settles via fallback timeout while remote hangs`() {
        val fastVm = RoomMessagesViewModel(repo, db, scope, initialLoadTimeoutMs = 200)
        respond = {
            MockResponse().setBody(history(50)).setBodyDelay(1, TimeUnit.SECONDS)
        }
        fastVm.openRoom(RID, "d")
        awaitCond("fallback timeout drops loading flag by ~200ms") { !fastVm.state.value.isInitialLoading }
        awaitCond("remote data lands after hang") { fastVm.state.value.messages.size == 50 }
    }

    @Test
    fun `initial load failure with empty local still settles`() {
        openRoomAndSettled { broken }
        val s = vm.state.value
        assertFalse(s.isInitialLoading)
        assertTrue(s.messages.isEmpty())
    }

    // ---- loadEarlier 三分支 + 失败 ----

    @Test
    fun `loadEarlier uses oldest local ts as iso cursor and grows window`() {
        seedLocal(50)
        openRoomAndSettled { empty } // 进房远程空，只看本地窗口
        respond = { url ->
            if (url.queryParameter("latest") != null) MockResponse().setBody(history(50)) else empty
        }
        vm.loadEarlier()
        awaitCond("window grew to 100") { vm.state.value.messages.size == 100 }
        val s = vm.state.value
        assertTrue(s.hasMoreEarlier)
        assertEquals(100, s.windowSize)

        // 游标 = 本地最旧一条 ts 的 ISO（UTC Z 格式），不是 oldest
        val wire = requests.last().queryParameter("latest")
        assertNotNull(wire)
        assertTrue(wire!!.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
        assertEquals((BASE - 49_000L), Instant.parse(wire).toEpochMilli())
        assertNull(requests.last().queryParameter("oldest"))
    }

    @Test
    fun `loadEarlier short page disables hasMore`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        respond = { url ->
            if (url.queryParameter("latest") != null) MockResponse().setBody(history(10)) else empty
        }
        vm.loadEarlier()
        awaitCond("short page closes hasMore") { !vm.state.value.hasMoreEarlier }
        assertEquals(60, vm.state.value.messages.size)
    }

    @Test
    fun `loadEarlier zero results disables hasMore`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        respond = { url -> if (url.queryParameter("latest") != null) empty else empty }
        vm.loadEarlier()
        awaitCond("zero page closes hasMore") { !vm.state.value.hasMoreEarlier }
        assertEquals(50, vm.state.value.messages.size)
        assertEquals(0, vm.state.value.noGrowthStreak)
    }

    @Test
    fun `loadEarlier failure keeps hasMore for retry`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        respond = { url -> if (url.queryParameter("latest") != null) broken else empty }
        vm.loadEarlier()
        awaitCond("loadEarlier settled") {
            val s = vm.state.value
            !s.isLoadingEarlier && requests.count { it.queryParameter("latest") != null } >= 3
        }
        val s = vm.state.value
        assertTrue(s.hasMoreEarlier) // 失败不置 hasMore=false
        assertEquals(50, s.messages.size)
        assertNull(s.pendingEarlier)
    }

    // ---- noGrowthStreak ----

    @Test
    fun `two consecutive no-growth pages disable hasMore`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        // 服务端无视 latest 恒回同 50 条（已在本地）：窗口涨但行数不涨
        respond = { url ->
            if (url.queryParameter("latest") != null) {
                MockResponse().setBody(history(50, idPrefix = "seed", startAt = BASE))
            } else {
                empty
            }
        }
        vm.loadEarlier()
        awaitCond("first miss counted") { vm.state.value.noGrowthStreak == 1 }
        assertTrue(vm.state.value.hasMoreEarlier)
        vm.loadEarlier()
        awaitCond("second miss closes hasMore") { !vm.state.value.hasMoreEarlier }
        assertEquals(2, vm.state.value.noGrowthStreak)
    }

    @Test
    fun `growth resets noGrowth streak`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        val same50 = MockResponse().setBody(history(50, idPrefix = "seed", startAt = BASE))
        val older50 = MockResponse().setBody(history(50))
        var miss = true
        respond = { url ->
            when {
                url.queryParameter("latest") == null -> empty
                miss -> same50
                else -> older50
            }
        }
        vm.loadEarlier()
        awaitCond("first miss counted") { vm.state.value.noGrowthStreak == 1 }
        miss = false
        vm.loadEarlier()
        awaitCond("window grew after real page") { vm.state.value.messages.size == 100 }
        val s = vm.state.value
        assertEquals(0, s.noGrowthStreak) // 成功增长清零
        assertTrue(s.hasMoreEarlier)
    }

    // ---- refresh ----

    @Test
    fun `refresh re-pulls latest 50 dedupes via upsert`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        // 同 id 同 ts、msg 变更：refresh 全靠 upsert 去重
        val withMsg = """{"messages":[${(0 until 50).joinToString(",") { i ->
            """{"_id":"seed-$i","rid":"$RID","ts":${(BASE - i * 1_000)},"msg":"from-server"}"""
        }}]}"""
        respond = { url ->
            if (url.queryParameter("latest") == null) MockResponse().setBody(withMsg) else empty
        }
        vm.refresh()
        awaitCond("refresh landed via upsert") {
            val s = vm.state.value
            !s.isRefreshing && s.messages.size == 50 && s.messages.first().msg == "from-server"
        }
        val s = vm.state.value
        assertEquals(50, s.messages.size) // upsert 去重，不翻倍
        assertTrue(s.hasMoreEarlier)
        assertEquals(2, requests.count { it.queryParameter("latest") == null }) // 初始 + 刷新各一次，均无 latest
    }

    // ---- 换房间 ----

    @Test
    fun `switch room fully resets pagination state`() {
        seedLocal(50)
        seedLocal(3, rid = "roomB", idPrefix = "b")
        openRoomAndSettled { url ->
            when {
                url.queryParameter("roomId") == "roomB" -> empty
                url.queryParameter("latest") != null -> MockResponse().setBody(history(50))
                else -> MockResponse().setBody(history(50, idPrefix = "srvA"))
            }
        }
        vm.loadEarlier()
        awaitCond("room A window grew") { vm.state.value.messages.size == 100 }

        vm.openRoom("roomB", "c")
        awaitCond("room B settled") {
            val s = vm.state.value
            s.rid == "roomB" && !s.isInitialLoading && s.messages.size == 3
        }
        val s = vm.state.value
        assertEquals("c", s.roomType)
        assertEquals(50, s.windowSize) // 窗口重置
        assertTrue(s.hasMoreEarlier) // 游标/hasMore 重置
        assertEquals(0, s.noGrowthStreak)
        assertNull(s.pendingEarlier)
        assertEquals(2, s.loadGen)
        assertTrue(s.messages.all { it._id.startsWith("b-") })
    }

    @Test
    fun `openRoom empty rid clears without network`() {
        vm.openRoom("", "d")
        val s = vm.state.value
        assertEquals("", s.rid)
        assertFalse(s.isInitialLoading)
        assertTrue(s.messages.isEmpty())
        assertTrue(requests.isEmpty())
    }

    // ---- 防抖 ----

    @Test
    fun `concurrent loadEarlier requests once`() {
        seedLocal(50)
        openRoomAndSettled { empty }
        respond = { url ->
            if (url.queryParameter("latest") != null) MockResponse().setBody(history(50)) else empty
        }
        vm.loadEarlier()
        vm.loadEarlier() // isLoadingEarlier 已同步置位，第二次直接被挡
        awaitCond("single page landed") { vm.state.value.messages.size == 100 }
        assertEquals(1, requests.count { it.queryParameter("latest") != null })
    }

    companion object {
        private const val RID = "room-1"

        /** 2026-01-01T00:00:00Z。 */
        private const val BASE = 1_767_225_600_000L
    }
}
