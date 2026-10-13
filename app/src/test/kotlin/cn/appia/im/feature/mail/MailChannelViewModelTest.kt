package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 收件箱 VM 行为（MailChannelScreen 分页/同步/引导链移植验证）：
 * 会话引导（accounts→folders→drafts→messages 首页）、升序合并、onLoadMore 翻旧页、
 * 下拉刷新 sync 先行、草稿夹切换、搜索口分派、无账户 setupRequired。
 * MockWebServer 全链真 HTTP（MailInitTest 同款）。
 */
class MailChannelViewModelTest {

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private val auth = MailClientAuth("token-1", "user-1")

    /** 请求路径流水（断言 sync 先行/搜索口分派）。 */
    private val paths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return handle(request.path!!, request.body.readUtf8())
            }
        }
        server.start()
        client = MailClient(
            serverUrl = { server.url("/").toString().trimEnd('/') },
            authProvider = { auth },
            baseHttpClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 假服务端 ──

    private var accountsBody = """[{"id":"a1","email":"u@x.com","active":true,"createdAt":"2026"}]"""
    private var foldersBody =
        """{"folders":[{"path":"INBOX","name":"INBOX","specialUse":"\\Inbox"}]}"""
    private var draftsBody = """[]"""

    /** 页表：path → (messages, total)；未登记路径 404。 */
    private val pages = mutableMapOf<String, Pair<String, Int>>()

    private fun messageJson(id: String, receivedAt: String, read: Boolean = false) =
        """{"id":"$id","uid":1,"from":"a@x.com","to":["b@x.com"],"subject":"s-$id",""" +
            """"receivedAt":"$receivedAt","read":$read,"flagged":false,"folder":"INBOX","size":10}"""

    private fun handle(path: String, body: String): MockResponse {
        return when {
            path == "/mail/api/accounts" -> MockResponse().setBody(accountsBody)
            path == "/mail/api/accounts/a1/folders" -> MockResponse().setBody(foldersBody)
            path == "/mail/api/drafts" -> MockResponse().setBody(draftsBody)
            path == "/mail/api/drafts/d1" -> {
                draftsBody = "[]" // 删除即清空（服务端投影）
                MockResponse().setResponseCode(204)
            }
            path == "/mail/api/accounts/a1/sync" -> MockResponse().setBody(
                """{"syncLogId":"l1","status":"ok","newCount":0}""",
            )
            path.endsWith("/messages/search") -> {
                val entry = pages.entries.firstOrNull { it.key.endsWith("/messages/search") && it.key == path }
                entry?.let { MockResponse().setBody(it.value.first) } ?: MockResponse().setResponseCode(404)
            }
            path.contains("/messages") -> {
                val entry = pages[path] ?: return MockResponse().setResponseCode(404)
                MockResponse().setBody(entry.first)
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun pageBody(vararg messages: String, total: Int) =
        """{"messages":[${messages.joinToString(",")}],"total":$total,"page":1,"pageSize":30}"""

    private fun newVm(
        emailProvider: suspend () -> String? = { "u@x.com" },
    ) = MailChannelViewModel(
        client = client,
        user = MailExchangeUser(email = "u@x.com", name = "U", username = "u"),
        authProvider = { auth },
        loadUserEmail = emailProvider,
        // VM 自建子 SupervisorScope（dispose 可取消）；宿主 scope 用 GlobalScope——
        // JUnit 非 runTest 环境，测试以 awaitState 轮询消费 + dispose 收尾
        scope = kotlinx.coroutines.GlobalScope,
    )

    /** 等待 state 满足条件（轮询 state flow，超时失败）。 */
    private fun awaitState(vm: MailChannelViewModel, timeoutMs: Long = 5000, cond: (MailChannelUiState) -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (cond(vm.state.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("state not reached: ${vm.state.value}")
    }

    // ── 会话引导链 ──

    @Test
    fun `session bootstraps folders drafts and first page ascending`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(
                messageJson("m2", "2026-10-12T09:00:00.000Z"),
                messageJson("m1", "2026-10-12T08:00:00.000Z"),
                total = 2,
            ),
            2,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.isNotEmpty() }

        val s = vm.state.value
        assertEquals("a1", s.account?.id)
        assertEquals(listOf("INBOX"), s.folders.map { it.path })
        assertEquals(listOf("m1", "m2"), s.messages.map { it.id }) // 升序（坑 9）
        assertEquals(2, s.total)
        // 引导链顺序：accounts → folders → drafts → messages
        assertEquals(
            listOf(
                "/mail/api/accounts",
                "/mail/api/accounts/a1/folders",
                "/mail/api/drafts",
                "/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30",
            ),
            paths.toList(),
        )
        vm.dispose()
    }

    @Test
    fun `empty accounts sets setupRequired`() {
        accountsBody = "[]"
        val vm = newVm()
        awaitState(vm) { it.setupRequired }
        assertFalse(vm.state.value.loading)
        vm.dispose()
    }

    @Test
    fun `email prefetch failure surfaces loadError`() {
        val vm = newVm(emailProvider = { null })
        awaitState(vm) { it.loadError && !it.loading }
        assertTrue(paths.isEmpty()) // 未触网
        vm.dispose()
    }

    // ── 分页语义（onLoadMore 翻旧页 append 去重）──

    @Test
    fun `loadMore appends older page merged by id ascending`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m2", "2026-10-12T09:00:00.000Z"), total = 4),
            4,
        )
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=2&pageSize=30"] = Pair(
            pageBody(
                messageJson("m0", "2026-10-12T07:00:00.000Z"),
                messageJson("m1", "2026-10-12T08:00:00.000Z"), // 与首页重合（服务端游标重叠）
                messageJson("m3", "2026-10-12T10:00:00.000Z"),
                total = 4,
            ),
            4,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.size == 1 }
        vm.onInitialAnchored() // 屏侧锚定完成放行

        vm.onLoadMore()
        awaitState(vm) { it.messages.size == 4 }

        val ids = vm.state.value.messages.map { it.id }
        assertEquals(listOf("m0", "m1", "m2", "m3"), ids) // 去重 + 升序合并
        assertEquals(2, vm.state.value.page)
        vm.dispose()
    }

    @Test
    fun `loadMore is gated when no more pages`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.size == 1 }
        vm.onInitialAnchored()

        vm.onLoadMore() // messages.size >= total → 不发请求
        Thread.sleep(100)
        assertFalse(paths.any { it.contains("page=2") })
        vm.dispose()
    }

    @Test
    fun `loadMore is gated before initial anchor completes`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 60),
            60,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.size == 1 }

        vm.onLoadMore() // initialAnchorDone=false → 不动
        Thread.sleep(100)
        assertFalse(paths.any { it.contains("page=2") })
        vm.dispose()
    }

    // ── 下拉刷新（sync 先行 + reload）──

    @Test
    fun `refresh triggers sync before reload on message folder`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.isNotEmpty() }
        vm.onInitialAnchored()
        paths.clear()

        vm.onRefresh()
        // anchorRequest 自增（reloadList 完成）为完成信号——refreshing 瞬态可能整体落在轮询间隙
        val anchorBefore = vm.state.value.anchorRequest
        awaitState(vm) { it.anchorRequest > anchorBefore && !it.refreshing && !it.syncing && paths.isNotEmpty() }

        // sync 先于第二次列表请求
        val syncIndex = paths.indexOfFirst { it.endsWith("/sync") }
        val listIndex = paths.indexOfFirst { it.contains("messages?folder=INBOX") }
        assertTrue(syncIndex in 0 until listIndex)
        vm.dispose()
    }

    @Test
    fun `refresh on drafts folder skips sync and refetches drafts`() {
        draftsBody = """[{"id":"d1","accountId":"a1","subject":"wip","createdAt":"2026-10-12T08:00:00.000Z","updatedAt":"2026-10-12T08:00:00.000Z"}]"""
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(total = 0),
            0,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.drafts.isNotEmpty() }
        vm.onSelectFolder("__drafts__")
        awaitState(vm) { it.activeFolder == "__drafts__" && it.drafts.isNotEmpty() }
        paths.clear()

        vm.onRefresh()
        // 草稿夹刷新链极快（单次 /drafts），refreshing 瞬态可能整体落进两次轮询之间——
        // 以 anchorRequest 自增（reloadList 完成）为完成信号，而非 refreshing 瞬态
        val anchorBefore = vm.state.value.anchorRequest
        awaitState(vm) { it.anchorRequest > anchorBefore && !it.refreshing }

        assertTrue(paths.any { it == "/mail/api/drafts" })
        assertFalse(paths.any { it.endsWith("/sync") }) // 草稿夹不触发同步
        vm.dispose()
    }

    @Test
    fun `refresh failure sets loadError`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.isNotEmpty() }
        vm.onInitialAnchored()
        paths.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500).setBody("""{"message":"boom"}""")
        }

        vm.onRefresh()
        awaitState(vm) { it.loadError && !it.refreshing }
        vm.dispose()
    }

    // ── 切夹 / 搜索 ──

    @Test
    fun `selecting folder reloads that folder and clears search on drafts`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        pages["/mail/api/accounts/a1/messages?folder=Sent&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("s1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.isNotEmpty() }

        vm.onSelectFolder("Sent")
        awaitState(vm) { it.activeFolder == "Sent" && it.messages.any { m -> m.id == "s1" } }
        assertTrue(paths.any { it.contains("folder=Sent") })

        // 草稿夹：清搜索 + 不发 messages 请求
        vm.onSearchQueryChanged("q")
        vm.onSelectFolder("__drafts__")
        awaitState(vm) { it.activeFolder == "__drafts__" && it.searchQuery.isEmpty() }
        vm.dispose()
    }

    @Test
    fun `search query routes to search endpoint without unreadOnly param`() {
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        pages["/mail/api/accounts/a1/messages/search?q=hello&folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("h1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.messages.isNotEmpty() }
        vm.onUnreadOnlyChanged(true)
        vm.onSearchQueryChanged("hello")

        vm.onRefresh() // RN 同构：词变更不自动重查，刷新为重查入口
        awaitState(vm) { it.messages.any { m -> m.id == "h1" } }

        // 搜索口不透传 unreadOnly（mailApi.ts:153-169 只 q/folder/page/pageSize）
        assertTrue(paths.any { it == "/mail/api/accounts/a1/messages/search?q=hello&folder=INBOX&page=1&pageSize=30" })
        vm.dispose()
    }

    // ── 草稿删除链 ──

    @Test
    fun `deleting last draft in drafts folder falls back to INBOX`() {
        draftsBody = """[{"id":"d1","accountId":"a1","subject":"wip","createdAt":"2026-10-12T08:00:00.000Z","updatedAt":"2026-10-12T08:00:00.000Z"}]"""
        pages["/mail/api/accounts/a1/messages?folder=INBOX&page=1&pageSize=30"] = Pair(
            pageBody(messageJson("m1", "2026-10-12T08:00:00.000Z"), total = 1),
            1,
        )
        val vm = newVm()
        awaitState(vm) { !it.loading && it.drafts.isNotEmpty() }
        vm.onSelectFolder("__drafts__")
        awaitState(vm) { it.activeFolder == "__drafts__" }

        vm.deleteDraft("d1")
        awaitState(vm) { it.activeFolder == "INBOX" && it.drafts.isEmpty() }
        assertTrue(paths.any { it == "/mail/api/drafts/d1" })
        vm.dispose()
    }
}
