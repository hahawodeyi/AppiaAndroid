package cn.appia.im.domain.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.datastore.LoginSwitchCandidatesCache
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.datastore.OrgSessionCacheRow
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.rest.OrgSwitchState
import cn.appia.im.core.push.PushTokenRegistrar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val coordJson = Json { ignoreUnknownKeys = true }

private fun parse(raw: String): JsonObject? =
    runCatching { coordJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

private fun JsonObject?.s(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

/**
 * OrgSwitchCoordinator 集成测试（Robolectric + MockWebServer REST/WebSocket 全栈，模式同
 * RealtimeSessionManagerTest）。对照绑定验收：换票 wire body（指定 host + 三字段 + 无鉴权头）、
 * 缓存命中/失败清除路径、bootstrap 失败回滚快照（不 logout）、互斥串行与 flag 挡 logout。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OrgSwitchCoordinatorTest {

    private val context: Context = getApplicationContext()
    private val dbManager = DatabaseManager(context)
    private val server = MockWebServer()
    private val wsListeners = ConcurrentLinkedQueue<CoordWsServer>()
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()
    private val syncCalls = AtomicInteger()

    /** 换票响应体；null 时 /api/v1/login 走 404（未到换票路径的断言用）。 */
    @Volatile
    private var ticketBody: String? = null

    /** bootstrap 门闩：非 null 时 syncInitial 挂起（占住 mutex 用）。 */
    @Volatile
    private var syncGate: CompletableDeferred<Unit>? = null

    private val kv = InMemoryKvStore()
    private val store = AuthSessionStore(kv)
    private val orgCache = OrgSessionCache(InMemoryKvStore())

    private lateinit var host: String
    private lateinit var sdk: RocketSdk
    private lateinit var manager: RealtimeSessionManager
    private lateinit var auth: AuthRepository
    private lateinit var coordinator: OrgSwitchCoordinator

    @Before
    fun setUp() {
        server.start()
        host = server.url("/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/websocket") -> MockResponse().withWebSocketUpgrade(wsListeners.poll()!!)
                    path.startsWith("/api/v1/login") && !path.startsWith("/api/v1/login.") ->
                        ticketBody?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    // push.token / subscriptions.get / rooms.get 等空对象兜底
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        sdk = RocketSdk(client = OkHttpClient())
        manager = newManager()
        auth = AuthRepository(
            store = store,
            push = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { "device-1" }),
            kv = kv,
            orgCache = orgCache,
            dbManager = dbManager,
            backgroundScope = CoroutineScope(Dispatchers.Unconfined),
        )
        coordinator = newCoordinator()
    }

    @After
    fun tearDown() {
        runCatching { manager.teardown() }
        scopes.forEach { it.cancel() }
        runCatching { sdk.ddp?.cancelTransport() }
        OrgSwitchFlag.end()
        dbManager.resetAll()
        runCatching { server.shutdown() }
    }

    private fun newManager(): RealtimeSessionManager = RealtimeSessionManager(
        sdk = sdk,
        dbManager = dbManager,
        syncInitial = {
            syncCalls.incrementAndGet()
            syncGate?.await()
        },
        onSessionExpired = {},
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) },
    )

    /** bootstrap 缝：failBootstrap 时抛错，验证回滚；否则透传 manager.bootstrap。 */
    private fun newCoordinator(): OrgSwitchCoordinator = OrgSwitchCoordinator(
        sdk = sdk,
        manager = manager,
        auth = auth,
        store = store,
        orgCache = orgCache,
        dbManager = dbManager,
        bootstrap = { serverUrl, token, userId ->
            if (failBootstrap) throw IOException("bootstrap boom")
            manager.bootstrap(serverUrl, token, userId)
        },
    )

    @Volatile
    private var failBootstrap = false

    private fun loginOld() = store.save(
        AuthSession(
            "tok-old",
            AuthUser(
                id = "u-old",
                username = "oldu",
                name = "Old Co",
                statusText = "busy",
                emails = listOf(cn.appia.im.core.network.AuthUserEmail(address = "old@a.cn", verified = true)),
                roles = listOf("admin", "user"),
            ),
            host,
        ),
    )

    private fun ticketBodyOk() =
        """{"data":{"authToken":"t-new","userId":"u-new","me":{"username":"newu","name":"New Co"}}}"""

    private suspend fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timeout waiting for: $desc")
    }

    private fun recordedRequests(): List<RecordedRequest?> {
        val out = ArrayList<RecordedRequest?>()
        repeat(server.requestCount) { out.add(server.takeRequest(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) }
        return out
    }

    // ---- 换票 wire body（指定 host、三字段顺序、无鉴权头）----

    @Test
    fun `switch via ticket posts three-field login body without auth headers`() = runBlocking {
        loginOld()
        ticketBody = ticketBodyOk()
        wsListeners.add(CoordWsServer(replyUserId = "u-new"))

        coordinator.switchTo(host)

        val requests = recordedRequests()
        val ticket = requests.firstOrNull { (it?.path ?: "").startsWith("/api/v1/login") }
            ?: throw AssertionError("no ticket request recorded: ${requests.map { it?.path }}")
        assertEquals("POST", ticket.method)
        assertEquals(
            """{"userId":"u-old","userToken":"tok-old","url":"${host.trimEnd('/')}"}""",
            ticket.body.readUtf8(),
        )
        assertNull("ticket must not carry old org token", ticket.getHeader("X-Auth-Token"))
        assertNull("ticket must not carry old org user", ticket.getHeader("X-User-Id"))

        // applySession：新会话落 store + 写缓存 + 切库；bootstrap 已跑（M1 绑定版无条件）
        val session = store.load()!!
        assertEquals("t-new", session.token)
        assertEquals("u-new", session.user.id)
        assertEquals("newu", session.user.username)
        assertEquals("New Co", session.user.name)
        assertEquals(host.trimEnd('/'), session.serverUrl) // RN applyOrgSwitchSession 规范化后落库
        assertEquals("t-new", orgCache.get(host)!!.token)
        assertEquals("u-new", sdk.currentUserId())
        assertTrue(
            dbManager.active === dbManager.databaseFor(dbManager.normalizeServer(host.trimEnd('/'))),
        )
        awaitCond("bootstrap sync ran") { syncCalls.get() >= 1 }
    }

    // ---- 缓存命中：不发换票，直接 resume 缓存 token ----

    @Test
    fun `cached org connects via resume without ticket request`() = runBlocking {
        loginOld()
        orgCache.set(host, OrgSessionCacheRow(token = "tok-cache", userId = "u-cache", username = "cacheu", name = "Cache"))
        wsListeners.add(CoordWsServer(replyUserId = "u-cache"))

        coordinator.switchTo(host)

        val requests = recordedRequests()
        assertTrue(
            "cache hit must skip ticket: ${requests.map { it?.path }}",
            requests.none { (it?.path ?: "").startsWith("/api/v1/login") },
        )
        assertEquals("tok-cache", store.load()!!.token)
        assertEquals("u-cache", sdk.currentUserId())
        assertEquals("tok-cache", orgCache.get(host)!!.token) // 未失败不清行
    }

    // ---- 缓存路径失败：clear(target) 落换票（RN auth.ts:206）----

    @Test
    fun `cached resume failure clears row and falls through to ticket`() = runBlocking {
        loginOld()
        orgCache.set(host, OrgSessionCacheRow(token = "tok-dead", userId = "u-dead", username = "deadu"))
        // 第一个 WS 实例让 resume 失败（非 socket not open）→ 缓存路径抛错 → 清行 → 换票
        wsListeners.add(CoordWsServer(replyUserId = "u-dead", failLogin = true))
        ticketBody = ticketBodyOk()
        wsListeners.add(CoordWsServer(replyUserId = "u-new"))

        coordinator.switchTo(host)

        assertEquals("t-new", orgCache.get(host)!!.token) // 清行后按新会话重写
        assertEquals("t-new", store.load()!!.token)
    }

    // ---- 回滚：bootstrap 抛错后恢复旧 store + 旧连接，不 logout ----

    @Test
    fun `bootstrap failure rolls back to previous session without logout`() = runBlocking {
        loginOld()
        orgCache.set(host, OrgSessionCacheRow(token = "tok-cache", userId = "u-cache", username = "cacheu"))
        wsListeners.add(CoordWsServer(replyUserId = "u-cache")) // 缓存路径连接
        wsListeners.add(CoordWsServer(replyUserId = "u-old")) // 回滚重连旧主体
        failBootstrap = true

        val ex = assertThrows(IOException::class.java) { runBlocking { coordinator.switchTo(host) } }
        assertEquals("bootstrap boom", ex.message)

        val restored = store.load()!!
        assertEquals("tok-old", restored.token)
        assertEquals("u-old", restored.user.id)
        assertEquals("oldu", restored.user.username)
        // 回滚恢复完整用户资料（RN 快照 ...user 全量展开，评审 Important：不丢 statusText/emails/roles）
        assertEquals("Old Co", restored.user.name)
        assertEquals("busy", restored.user.statusText)
        assertEquals(
            listOf(cn.appia.im.core.network.AuthUserEmail(address = "old@a.cn", verified = true)),
            restored.user.emails,
        )
        assertEquals(listOf("admin", "user"), restored.user.roles)
        assertEquals(host, restored.serverUrl)
        assertEquals("tok-old", sdk.currentAuthToken()) // 旧 REST 会话恢复（绑定裁定#2 旧连接回拨）
        assertEquals("tok-cache", orgCache.get(host)!!.token) // RN 回滚不触碰 orgSessionByHost
        assertFalse(OrgSwitchState.isInProgress())
    }

    // ---- 互斥串行 + flag 挡 logout（RN withOrgSwitchMutex / authStore:139-141）----

    @Test
    fun `mutex serializes concurrent switches and flag guards logout`() = runBlocking {
        loginOld()
        ticketBody = ticketBodyOk()
        syncGate = CompletableDeferred()
        wsListeners.add(CoordWsServer(replyUserId = "u-new"))
        wsListeners.add(CoordWsServer(replyUserId = "u-new")) // 第二次切换（缓存命中路径）

        val first = launch { coordinator.switchTo(host) }
        awaitCond("first switch entered sync") { syncCalls.get() == 1 }
        assertTrue(OrgSwitchState.isInProgress())

        val second = async { coordinator.switchTo(host) }
        delay(100)
        assertEquals("second switch must wait for mutex", 1, syncCalls.get())
        // 切换中 logout 直接 return（RN :139-141）：此时 applySession 已落库（bootstrap 挂在门闩上），
        // logout 若未挡住会 clearAll/clear——以缓存与候选缓存是否幸存作判据
        LoginSwitchCandidatesCache(kv).write("bob", listOf(LoginSwitchCandidate(userId = "u-new", appiaUrl = host)))
        auth.logout()
        assertEquals("t-new", store.load()!!.token)
        assertEquals("t-new", orgCache.get(host)!!.token)
        assertTrue(LoginSwitchCandidatesCache(kv).read("bob") != null)

        syncGate?.complete(Unit)
        first.join()
        second.await()
        assertFalse(OrgSwitchState.isInProgress())
        assertEquals("mutex serializes", 2, syncCalls.get())
        assertEquals("t-new", store.load()!!.token)
    }

    // ---- 无活跃会话快速失败 ----

    @Test
    fun `switch without active session fails fast`() {
        assertThrows(IllegalStateException::class.java) { runBlocking { coordinator.switchTo(host) } }
    }

    /** 脚本化 DDP WS 服务端（RealtimeSessionManagerTest.SessionWsServer 同款，加 replyUserId/failLogin）。 */
    private class CoordWsServer(
        private val replyUserId: String = "u-1",
        private val failLogin: Boolean = false,
    ) : WebSocketListener() {
        val frames = CopyOnWriteArrayList<String>()
        private val wsRef = AtomicReference<WebSocket?>(null)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            wsRef.set(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.add(text)
            val obj = parse(text) ?: return
            when (obj.s("msg")) {
                "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
                "ping" -> webSocket.send("""{"msg":"pong"}""")
                "method" -> if (obj.s("method") == "login") {
                    val id = obj.s("id")
                    webSocket.send(
                        if (failLogin) {
                            """{"msg":"result","id":"$id","error":{"reason":"cached token rejected"}}"""
                        } else {
                            """{"msg":"result","id":"$id",""" +
                                """"result":{"id":"$replyUserId","token":"ddp-token","createCipher":{"${'$'}date":1690000000000}}}"""
                        },
                    )
                }
            }
        }
    }
}
