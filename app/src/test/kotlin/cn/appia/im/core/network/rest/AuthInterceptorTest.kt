package cn.appia.im.core.network.rest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 对照 appiaMobile/src/services/sdk/restClient.ts：
 * header 注入、401（组织切换中/外）语义、错误消息优先级链、login/info 端点形态。
 */
class AuthInterceptorTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun api(authProvider: () -> AuthSession?): RocketApi {
        server.start()
        return RetrofitFactory.create(server.url("/").toString(), authProvider)
            .create(RocketApi::class.java)
    }

    /** SessionExpiredBus 订阅者：Unconfined 保证 launch 返回前订阅已生效（无订阅竞态）。 */
    private fun CoroutineScope.subscribeBus(): Pair<Channel<Unit>, Job> {
        val events = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Unconfined) {
            SessionExpiredBus.events.collect { events.trySend(it) }
        }
        return events to job
    }

    // ---- 语义 2：已登录注入 X-Auth-Token / X-User-Id，未登录不注入 ----

    @Test
    fun `injects auth headers when session present`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"info":{"version":"7.9.0"}}"""))
        val api = api { AuthSession(token = "t-1", userId = "u-1") }

        val resp = api.serverInfo()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/info", recorded.path)
        assertEquals("t-1", recorded.getHeader("X-Auth-Token"))
        assertEquals("u-1", recorded.getHeader("X-User-Id"))
        assertEquals("7.9.0", resp.info?.version)
    }

    @Test
    fun `omits auth headers when no session`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val api = api { null }

        api.serverInfo()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-Auth-Token"))
        assertNull(recorded.getHeader("X-User-Id"))
    }

    // ---- 语义 3：401 → 发 SessionExpiredBus + 抛 AuthSessionExpiredException ----

    @Test
    fun `401 emits session expired and throws`() = runBlocking {
        val (events, collector) = subscribeBus()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
            val api = api { AuthSession(token = "t", userId = "u") }

            val ex = runCatching { api.serverInfo() }.exceptionOrNull()

            assertInstanceOf(AuthSessionExpiredException::class.java, ex)
            assertEquals(AUTH_SESSION_EXPIRED_ERROR, ex?.message)
            assertEquals(Unit, withTimeout(5_000) { events.receive() }) // TS:80 authActions.logout()
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `401 during org switch throws without emitting`() = runBlocking {
        val (events, collector) = subscribeBus()
        OrgSwitchState.begin()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val api = api { AuthSession(token = "t", userId = "u") }

            val ex = runCatching { api.serverInfo() }.exceptionOrNull()

            assertInstanceOf(AuthSessionExpiredException::class.java, ex) // 异常照抛（TS:82）
            var emitted = false
            try {
                withTimeout(200) { events.receive() }
                emitted = true
            } catch (_: TimeoutCancellationException) {
                // 预期：换票期间 401 不触发登出事件
            }
            assertFalse(emitted)
        } finally {
            OrgSwitchState.end() // TS resetOrgSwitchInProgressForTests
            collector.cancel()
        }
    }

    // ---- 语义 4：错误消息提取 message ?? error ?? 原始 text ?? HTTP <status>（TS:85-89）----

    @Test
    fun `error message chain prefers message then error then text then status`() = runBlocking {
        val api = api { null }

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"m1","error":"e1"}"""))
        var ex = runCatching { api.serverInfo() }.exceptionOrNull()!!.message!!
        assertTrue(ex.contains("m1"))
        assertFalse(ex.contains("e1"))

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"e2"}"""))
        ex = runCatching { api.serverInfo() }.exceptionOrNull()!!.message!!
        assertTrue(ex.contains("e2"))

        server.enqueue(MockResponse().setResponseCode(503).setBody("boom text"))
        ex = runCatching { api.serverInfo() }.exceptionOrNull()!!.message!!
        assertTrue(ex.contains("boom text"))

        server.enqueue(MockResponse().setResponseCode(504))
        ex = runCatching { api.serverInfo() }.exceptionOrNull()!!.message!!
        assertTrue(ex.contains("HTTP 504"))
    }

    // ---- 评审 Important-3 回归：login 端点不注入鉴权头（换组织/重登录不得携带旧 token）----

    @Test
    fun `login omits auth headers even when session present`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"status":"success","data":{"userId":"u-9","authToken":"t-9"}}"""),
        )
        val api = api { AuthSession(token = "stale-org-token", userId = "stale-org-user") }

        api.login(LoginRequest(username = "bob", password = "secret"))

        val recorded = server.takeRequest()
        assertEquals("/api/v1/login", recorded.path)
        assertNull(recorded.getHeader("X-Auth-Token"), "login must not carry stale org token")
        assertNull(recorded.getHeader("X-User-Id"))
    }

    // ---- 评审 Important-5 回归：错误链 JSON-null 边角 ----

    @Test
    fun `error message falls back to error when message is json null`() = runBlocking {
        val api = api { null }

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":null,"error":"e-null"}"""))
        val ex = runCatching { api.serverInfo() }.exceptionOrNull()!!.message!!

        assertTrue(ex.contains("e-null"), "JsonNull message must fall through to error, got: $ex")
    }

    @Test
    fun `error message falls back instead of crashing when message is an object`() = runBlocking {
        val api = api { null }

        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"message":{"deep":"x"},"error":"e-obj"}"""),
        )
        val ex = runCatching { api.serverInfo() }.exceptionOrNull()!!.message!!

        assertTrue(ex.contains("e-obj"), "non-primitive message must fall through to error, got: $ex")
    }

    // ---- 语义 1（base URL）+ 端点形态：POST login / GET info ----

    @Test
    fun `login posts credentials to login endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"success","data":{"userId":"u-9","authToken":"t-9","me":{"username":"bob"}}}""",
            ),
        )
        val api = api { null }

        val resp = api.login(LoginRequest(username = "bob", password = "secret"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/login", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"username\":\"bob\""))
        assertTrue(body.contains("\"password\":\"secret\""))
        assertEquals("u-9", resp.data?.userId)
        assertEquals("t-9", resp.data?.authToken)
        assertEquals("bob", resp.data?.me?.username)
    }
}
