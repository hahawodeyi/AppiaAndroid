package cn.appia.im.domain.session

import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * RoleRefresher 对照 RN services/auth/syncCurrentUserRoles.ts（M5-T2）：
 * users.info 自查 → parseUserRoles → store.mergeUserRoles（整体替换）；
 * 30s 节流 / inflight 去重 / force 绕过 / 失败静默（:43-44）。
 */
class RoleRefresherTest {
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newRefresher(gated: CompletableDeferred<Unit>? = null): RoleRefresher {
        val store = AuthSessionStore(kv)
        store.save(
            AuthSession(
                token = "tok",
                user = AuthUser(id = "u-1", username = "bob", roles = listOf("stale")),
                serverUrl = server.url("/").toString(),
            ),
        )
        val sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "u-1") }
        return RoleRefresher(
            sdk = sdk,
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ).also { ref ->
            if (gated != null) {
                server.dispatcher = object : Dispatcher() {
                    var passed = false
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (!passed) {
                            runBlocking { gated.await() }
                            passed = true
                        }
                        return rolesResponse()
                    }
                }
            }
        }
    }

    private fun rolesResponse(roles: String = """["admin","user"]""") =
        MockResponse().setBody("""{"user":{"id":"u-1","roles":$roles}}""")

    /** 成功路径：users.info wire + mergeUserRoles 整体替换（RN :40-41）。 */
    @Test
    fun `refresh fetches users info and replaces roles`() = runBlocking {
        val refresher = newRefresher()
        server.enqueue(rolesResponse())

        refresher.refresh()

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/users.info", req.requestUrl?.encodedPath)
        assertEquals("u-1", req.requestUrl?.queryParameter("userId"))
        assertEquals(listOf("admin", "user"), AuthSessionStore(kv).load()?.user?.roles)
    }

    /** 节流（RN :25-28）：30s 窗口内第二次调用不再发请求。 */
    @Test
    fun `second refresh within window is throttled to one request`() = runBlocking {
        val refresher = newRefresher()
        server.enqueue(rolesResponse())

        refresher.refresh()
        refresher.refresh()

        assertEquals(1, server.requestCount)
    }

    /** 节流分支等 inflight（RN :27）：慢请求在途时，节流调用方 join 它（不另发请求），返回时角色已合并。 */
    @Test
    fun `throttled caller joins gated inflight fetch`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val refresher = newRefresher(gate)
        var rolesAtJoinerReturn: List<String>? = null

        val launcher = async { refresher.refresh() }
        val joiner = async {
            refresher.refresh()
            rolesAtJoinerReturn = AuthSessionStore(kv).load()?.user?.roles
        }
        yield() // launcher 先行：占用 inflight 停在门控 fetch；joiner 随后停在节流 join

        assertFalse(joiner.isCompleted) // 门未开：joiner 在等 inflight，不另发请求

        gate.complete(Unit)
        launcher.await()
        joiner.await()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("admin", "user"), rolesAtJoinerReturn)
    }

    /** force（RN :25 `!options?.force`）：绕过节流，再发一次并覆盖角色。 */
    @Test
    fun `force bypasses throttle and fetches again`() = runBlocking {
        val refresher = newRefresher()
        server.enqueue(rolesResponse())
        server.enqueue(rolesResponse("""["leader"]"""))

        refresher.refresh()
        refresher.refresh(force = true)

        assertEquals(2, server.requestCount)
        assertEquals(listOf("leader"), AuthSessionStore(kv).load()?.user?.roles)
    }

    /** force 撞在途（RN :30-33 等价时序）：等当次完成后自己再发一次（mutex 串行化，force 放行）。 */
    @Test
    fun `force while inflight waits then issues second fetch`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val refresher = newRefresher(gate) // 门控 dispatcher 对每个请求回 canned 响应

        val launcher = async { refresher.refresh() } // 第一次请求，被门挡住
        val forced = async { refresher.refresh(force = true) }
        yield()

        assertFalse(forced.isCompleted) // 第一次未完成前 force 调用不返回

        gate.complete(Unit)
        launcher.await()
        forced.await()

        assertEquals(2, server.requestCount)
        assertEquals(listOf("admin", "user"), AuthSessionStore(kv).load()?.user?.roles)
    }

    /** 无会话（RN :21-22 `if (!user?.id) return`）：零请求。 */
    @Test
    fun `refresh without session is a no-op`() = runBlocking {
        val store = AuthSessionStore(kv)
        val sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "u-1") }
        val refresher = RoleRefresher(sdk, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        refresher.refresh()

        assertEquals(0, server.requestCount)
    }

    /** 失败静默（RN :43-44 空 catch）：不抛、角色保持原值。 */
    @Test
    fun `failed fetch is swallowed and roles untouched`() = runBlocking {
        val refresher = newRefresher()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        runCatching { refresher.refresh() }.getOrThrow() // 不抛即过

        assertEquals(listOf("stale"), AuthSessionStore(kv).load()?.user?.roles)
    }

    /** force 撞在途时后续普通调用不重复发（inflight 清理：launcher finally 身份检查）。 */
    @Test
    fun `inflight is cleared after completion so throttle branch sees null`() = runBlocking {
        val refresher = newRefresher()
        server.enqueue(rolesResponse())
        server.enqueue(rolesResponse())

        refresher.refresh()
        // 首次调用已完成：强制走 force 发第二次（若 inflight 未清理，会先 join 已完成的旧 job——
        // 行为相同，但 requestCount 必须恰为 2，验证无幽灵第三次）
        refresher.refresh(force = true)
        refresher.refresh()

        assertEquals(2, server.requestCount)
    }
}
