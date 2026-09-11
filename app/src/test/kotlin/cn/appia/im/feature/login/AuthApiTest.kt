package cn.appia.im.feature.login

import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginMethodServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * 对照 RN auth.ts（loginSendCode :67-81 / loginGetAreaCodes :37-62 / login :87-90）+ AreaCodeScreen:25-47：
 * - sendCode：POST /api/v1/login.sendCode，body `{phone, areaCode, ic: ic ?? {}}`；`raw.success !== false` 即成功
 * - getAreaCode：GET /api/v1/getAreaCode?locale=，模块级缓存 key=`host|locale`；失败/空回落 +86 且**回落不入缓存**
 * - login 编排（T2 预检裁定：sdk 原语 initialize→connect→login，不监听 wire）+ 30s 超时落到 login REST 调用
 */
class AuthApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
        AuthApi.clearAreaCodeCacheForTest()
    }

    @AfterEach
    fun tearDown() {
        // AuthApi 单例 sdk 的 DDP 连接须随用例销毁（与 RocketSdkTest 同口径）
        AuthApi.sdkForTest.let {
            it.ddp?.let { ddp ->
                runCatching { ddp.disconnect() }
                runCatching { ddp.cancelTransport() }
            }
        }
        runCatching { server.shutdown() }
    }

    private fun host(): String = server.url("/").toString()

    private fun enqueue(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    // ---- login.sendCode wire ----

    @Test
    fun `sendCode posts phone areaCode and default empty ic`() = runBlocking {
        enqueue("""{"success":true}""")

        val result = AuthApi.loginSendCode(host(), "13800000000", "+86")

        val request = server.takeRequest()
        assertEquals("/api/v1/login.sendCode", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"phone":"13800000000","areaCode":"+86","ic":{}}""", request.body.readUtf8())
        assertEquals(true, result.success)
        assertNull(result.message)
    }

    @Test
    fun `sendCode passes caller ic through verbatim`() = runBlocking {
        enqueue("""{"success":true}""")

        AuthApi.loginSendCode(
            host(),
            "13800000000",
            "+86",
            ic = buildJsonObject { put("token", "slider-ticket") },
        )

        assertEquals(
            """{"phone":"13800000000","areaCode":"+86","ic":{"token":"slider-ticket"}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `sendCode missing success field still counts as success`() = runBlocking {
        // RN `raw?.success !== false`：{} / success 非布尔 false 都放行
        enqueue("""{"message":"queued"}""")

        val result = AuthApi.loginSendCode(host(), "138", "+86")

        assertEquals(true, result.success)
        assertEquals("queued", result.message)
    }

    @Test
    fun `sendCode false success surfaces message`() = runBlocking {
        enqueue("""{"success":false,"message":"too many requests"}""")

        val result = AuthApi.loginSendCode(host(), "138", "+86")

        assertEquals(false, result.success)
        assertEquals("too many requests", result.message)
    }

    @Test
    fun `sendCode string false is not boolean false so success`() = runBlocking {
        enqueue("""{"success":"false"}""")

        assertEquals(true, AuthApi.loginSendCode(host(), "138", "+86").success)
    }

    @Test
    fun `sendCode http failure throws with body message like RN restClient`() = runBlocking {
        enqueue("""{"message":"server busy"}""", status = 500)

        val ex = assertThrows<Exception> { AuthApi.loginSendCode(host(), "138", "+86") }

        assertTrue(ex.message!!.contains("login.sendCode failed: server busy"))
    }

    // ---- getAreaCode wire + 模块级缓存 + 回落 ----

    @Test
    fun `getAreaCode fetches with locale param and caches per host and locale`() = runBlocking {
        enqueue("""{"data":[{"label":"China","areaCode":"+86","code":"CN"},{"label":"US","areaCode":"+1","code":"US"}]}""")

        val first = AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")
        val second = AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")

        assertEquals("/api/v1/getAreaCode?locale=zh-CN", server.takeRequest().path)
        assertEquals(1, server.requestCount, "cache hit must not fire a second request")
        assertEquals(
            listOf(LoginAreaCodeOption("China", "+86", "CN"), LoginAreaCodeOption("US", "+1", "US")),
            first,
        )
        assertEquals(first, second)
    }

    @Test
    fun `cache key normalizes trailing slashes like RN normalizeLoginHost`() = runBlocking {
        enqueue("""{"data":[{"label":"China","areaCode":"+86","code":"CN"}]}""")

        AuthApi.loginGetAreaCodes(host() + "///", "zh-CN", fallbackLabel = "China")
        AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `different locale bypasses cache`() = runBlocking {
        enqueue("""{"data":[{"label":"China","areaCode":"+86","code":"CN"}]}""")
        enqueue("""{"data":[{"label":"United States","areaCode":"+1","code":"US"}]}""")

        AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")
        val en = AuthApi.loginGetAreaCodes(host(), "en-US", fallbackLabel = "China")

        assertEquals(2, server.requestCount)
        assertEquals("United States", en.single().label)
    }

    @Test
    fun `empty list falls back to plus86 and is not cached`() = runBlocking {
        enqueue("""{"data":[]}""")
        enqueue("""{"data":[{"label":"Japan","areaCode":"+81","code":"JP"}]}""")

        val fallback = AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")
        val refetched = AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")

        assertEquals(listOf(LoginAreaCodeOption("China", "+86", "CN")), fallback)
        assertEquals(2, server.requestCount, "fallback must not be cached; next call refetches")
        assertEquals("Japan", refetched.single().label)
    }

    @Test
    fun `fetch failure falls back and does not poison cache`() = runBlocking {
        enqueue("""{"error":"boom"}""", status = 500)
        enqueue("""{"data":[{"label":"Japan","areaCode":"+81","code":"JP"}]}""")

        val fallback = AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")
        val refetched = AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China")

        assertEquals(listOf(LoginAreaCodeOption("China", "+86", "CN")), fallback)
        assertEquals(2, server.requestCount)
        assertEquals("Japan", refetched.single().label)
    }

    @Test
    fun `non-json body falls back like RN json parse guard`() = runBlocking {
        // RN restClient：text 解析失败 → undefined → raw?.data 为空 → list=[] → 回落
        enqueue("<html>502</html>")

        assertEquals(
            listOf(LoginAreaCodeOption("China", "+86", "CN")),
            AuthApi.loginGetAreaCodes(host(), "zh-CN", fallbackLabel = "China"),
        )
    }

    // ---- login 编排（T2 裁定：initialize→connect→login，wire 序断言）----

    @Test
    fun `login connects websocket first then posts sms rest without ic`() = runBlocking {
        val ws = LoginMethodServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        enqueue("""{"data":{"userId":"u-1","authToken":"t-1"}}""")

        val result = AuthApi.login(host(), LoginCredentials.Sms("13800000000", "1234", "+86"))

        // 编排顺序：connect（GET /websocket 升级）先于 REST login
        val wsRequest = server.takeRequest()
        assertEquals("/websocket", wsRequest.path)
        val rest = server.takeRequest()
        assertEquals("/api/v1/login", rest.path)
        assertEquals(
            """{"smsCode":true,"phone":"13800000000","code":"1234","areaCode":"+86"}""",
            rest.body.readUtf8(),
        )
        assertNull(rest.getHeader("X-Auth-Token"), "login must not carry stale session token")
        assertEquals("t-1", result.authToken)
        assertEquals("u-1", result.userId)
    }

    @Test
    fun `login default timeout is 30s and propagates to rest call`() {
        assertEquals(30_000L, AuthApi.LOGIN_TIMEOUT_MS)

        val ws = LoginMethodServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        // header 延迟 2s：若超时未传播，请求 2s 后成功 → assertThrows 落空；传播了则 250ms 即抛
        server.enqueue(
            MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("""{"data":{}}"""),
        )

        assertTimeoutPreemptively(java.time.Duration.ofMillis(5_000)) {
            val ex = runCatching {
                runBlocking {
                    AuthApi.login(host(), LoginCredentials.Sms(phone = "138", code = "1", areaCode = "+86"), timeoutMs = 250)
                }
            }.exceptionOrNull()
            assertTrue(ex is java.io.IOException, "expected timeout IOException, got $ex")
        }
    }
}
