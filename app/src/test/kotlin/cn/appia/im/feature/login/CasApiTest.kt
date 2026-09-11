package cn.appia.im.feature.login

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * CAS SSO 三件套（对照 RN LoginScreen:91-108/:214-233 + AuthWebScreen:37-69）：
 * - fetchCasLoginUrl：GET {server}/api/v1/settings.oauth → services[] 中 `service==='cas' && enabled` 的 login_url（trim）
 * - evaluateCasRedirect：先 decodeURIComponent；带 ticket → Allow（服务端消费）；service 参数 host == serverHost → Success；其余 Allow
 * - generateSsoToken / buildCasUrl：17 位随机 base36；`{casLoginUrl}?service={server}/_cas/{ssoToken}` 原样拼接
 */
class CasApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enqueue(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun fetch(host: String = server.url("/").toString()) = runBlocking { CasApi.fetchCasLoginUrl(host) }

    // ---- settings.oauth 探测 ----

    @Test
    fun `gets settings oauth and returns trimmed cas login_url`() = runBlocking {
        enqueue(
            """{"services":[""" +
                """{"service":"oauth","enabled":true,"login_url":"https://x/oauth"},""" +
                """{"service":"cas","enabled":true,"login_url":"  https://sso.example/cas/login  "}]}""",
        )

        assertEquals("https://sso.example/cas/login", fetch())

        val recorded = server.takeRequest()
        assertEquals("/api/v1/settings.oauth", recorded.path)
        assertEquals("GET", recorded.method)
    }

    @Test
    fun `cas disabled yields null`() = runBlocking {
        enqueue("""{"services":[{"service":"cas","enabled":false,"login_url":"https://sso/cas"}]}""")

        assertNull(fetch())
    }

    @Test
    fun `missing cas service yields null`() = runBlocking {
        enqueue("""{"services":[{"service":"oauth","enabled":true,"login_url":"https://x/o"}]}""")

        assertNull(fetch())
    }

    @Test
    fun `missing services field yields null`() = runBlocking {
        enqueue("""{"success":true}""")

        assertNull(fetch())
    }

    @Test
    fun `enabled cas with blank login_url yields null`() = runBlocking {
        enqueue("""{"services":[{"service":"cas","enabled":true,"login_url":"   "}]}""")

        assertNull(fetch())
    }

    @Test
    fun `json null login_url yields null not the string null`() = runBlocking {
        enqueue("""{"services":[{"service":"cas","enabled":true,"login_url":null}]}""")

        assertNull(fetch())
    }

    @Test
    fun `non-json body yields null instead of crashing`() = runBlocking {
        enqueue("<html>404</html>")

        assertNull(fetch())
    }

    @Test
    fun `network failure yields null`() = runBlocking {
        val dead = MockWebServer()
        val url = dead.url("/").toString()
        dead.shutdown()

        assertNull(fetch(url))
    }

    // ---- 回调判定纯函数（RN AuthWebScreen:37-69）----

    @Test
    fun `ticket param allows load even when service matches server`() {
        val url = "https://sso/cas/login?service=https%3A%2F%2Fappia.cn%2F_cas%2Fab&ticket=ST-123"

        assertEquals(CasRedirect.Allow, CasApi.evaluateCasRedirect(url, "appia.cn"))
    }

    @Test
    fun `service host equal to server host succeeds`() {
        val url = "https://sso/cas/login?service=https%3A%2F%2Fappia.cn%2F_cas%2Fab"

        assertEquals(CasRedirect.Success, CasApi.evaluateCasRedirect(url, "appia.cn"))
    }

    @Test
    fun `service host different from server host allows`() {
        val url = "https://sso/cas/login?service=https%3A%2F%2Fother.cn%2F_cas%2Fab"

        assertEquals(CasRedirect.Allow, CasApi.evaluateCasRedirect(url, "appia.cn"))
    }

    @Test
    fun `missing service param allows`() {
        assertEquals(CasRedirect.Allow, CasApi.evaluateCasRedirect("https://sso/cas/login", "appia.cn"))
    }

    @Test
    fun `empty ticket value falls through to service check`() {
        // RN `searchParams.get('ticket')` 真值判定：空串不算 ticket，继续 service 判定
        val url = "https://sso/cas/login?ticket=&service=https%3A%2F%2Fappia.cn%2F_cas%2Fab"

        assertEquals(CasRedirect.Success, CasApi.evaluateCasRedirect(url, "appia.cn"))
    }

    @Test
    fun `double encoded url is decoded before recognition`() {
        // RN AuthWebScreen:40 先 decodeURIComponent：%253A → %3A，service 才能解析出 host
        val url = "https://sso/cas/login?service=https%253A%252F%252Fappia.cn%252F_cas%252Fab"

        assertEquals(CasRedirect.Success, CasApi.evaluateCasRedirect(url, "appia.cn"))
    }

    @Test
    fun `host comparison is case insensitive`() {
        val url = "https://sso/cas/login?service=https%3A%2F%2Fappia.cn%2F_cas%2Fab"

        assertEquals(CasRedirect.Success, CasApi.evaluateCasRedirect(url, "APPIA.CN"))
    }

    @Test
    fun `garbage urls allow without crashing`() {
        for (raw in listOf("", "   ", "not a url", "about:blank")) {
            assertEquals(CasRedirect.Allow, CasApi.evaluateCasRedirect(raw, "appia.cn"))
        }
    }

    @Test
    fun `malformed percent escape allows without crashing`() {
        // decodeURIComponent 对坏转义抛错 → RN catch 后放行
        assertEquals(CasRedirect.Allow, CasApi.evaluateCasRedirect("https://sso/cas?service=100%zz", "appia.cn"))
    }

    // ---- ssoToken / URL 构造（RN LoginScreen:214-233）----

    @Test
    fun `sso token is 17 chars of base36`() {
        repeat(50) {
            val token = CasApi.generateSsoToken()
            assertEquals(17, token.length)
            assertTrue(token.matches(Regex("^[0-9a-z]{17}$")), "must be pure [0-9a-z]: $token")
        }
    }

    @Test
    fun `sso token varies across calls`() {
        assertNotEquals(CasApi.generateSsoToken(), CasApi.generateSsoToken())
    }

    @Test
    fun `cas url is verbatim concat without extra encoding`() {
        val url = CasApi.buildCasUrl("https://sso/cas/login", "https://appia.cn", "abc123")

        assertEquals("https://sso/cas/login?service=https://appia.cn/_cas/abc123", url)
    }
}
