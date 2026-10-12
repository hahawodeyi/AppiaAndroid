package cn.appia.im.core.mail

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * MailClient 基座行为（mailClient.ts 逐项）：基址拼装/X-Auth 头/CookieJar 收发/
 * 401→/auth/refresh→重试一次/节流判定/错误 message 提取。
 */
class MailClientTest {

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private var auth: MailClientAuth? = MailClientAuth("token-1", "user-1")

    @Before
    fun setUp() {
        server.start()
        client = MailClient(
            serverUrl = { server.url("/").toString().trimEnd('/') },
            authProvider = { auth },
            baseHttpClient = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `url resolves to server + mail-api base with leading-slash normalization`() {
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/mail/api/accounts", client.url("/accounts"))
        assertEquals("$base/mail/api/accounts", client.url("accounts"))
        assertEquals("$base/mail/api/auth/refresh", client.url("/auth/refresh"))
    }

    @Test
    fun `url skips null and empty params, keeps others`() {
        val base = server.url("/").toString().trimEnd('/')
        assertEquals(
            "$base/mail/api/accounts/acc1/messages?folder=INBOX&page=2&unreadOnly=true",
            client.url(
                "/accounts/acc1/messages",
                mapOf("folder" to "INBOX", "page" to 2, "unreadOnly" to true, "threadId" to null, "conversationId" to ""),
            ),
        )
    }

    @Test
    fun `both X-Auth headers present on request`() {
        server.enqueue(MockResponse().setBody("[]"))
        client.request<List<MailAccount>>("/accounts")

        val req = server.takeRequest()
        assertEquals("token-1", req.getHeader("X-Auth-Token"))
        assertEquals("user-1", req.getHeader("X-User-Id"))
    }

    @Test
    fun `empty auth omits headers`() {
        auth = null
        server.enqueue(MockResponse().setBody("[]"))
        client.request<List<MailAccount>>("/accounts")

        val req = server.takeRequest()
        assertEquals(null, req.getHeader("X-Auth-Token"))
        assertEquals(null, req.getHeader("X-User-Id"))
    }

    @Test
    fun `post body serialized as json with content type`() {
        server.enqueue(MockResponse().setBody("""{"success":true,"message":"ok"}"""))
        val res = client.request<TestConnectionResponse>(
            "/accounts/test-connection",
            method = "POST",
            body = buildJsonObject {
                put("email", JsonPrimitive("user@x.com"))
                put("encryptedPassword", JsonPrimitive("ZmFrZQ=="))
            },
        )
        assertTrue(res.success)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertTrue(req.body.readUtf8().contains("\"encryptedPassword\":\"ZmFrZQ==\""))
    }

    @Test
    fun `cookie jar sends received cookies on subsequent requests`() {
        server.enqueue(MockResponse().setBody("[]").addHeader("Set-Cookie", "mail_session=abc123; Path=/"))
        server.enqueue(MockResponse().setBody("[]"))
        client.request<List<MailAccount>>("/accounts")
        client.request<List<MailAccount>>("/accounts")

        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("mail_session=abc123", second.getHeader("Cookie"))
    }

    @Test
    fun `401 then refresh ok retries original request once with refreshed session cookies`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}""").addHeader("Set-Cookie", "mail_session=fresh; Path=/"))
        server.enqueue(MockResponse().setBody("""[{"id":"a1","email":"u@x.com","active":true,"createdAt":"2026"}]"""))

        val accounts = client.request<List<MailAccount>>("/accounts")

        assertEquals(1, accounts.size)
        assertEquals("a1", accounts[0].id)
        assertEquals("/mail/api/accounts", server.takeRequest().path)
        val refresh = server.takeRequest()
        assertEquals("/mail/api/auth/refresh", refresh.path)
        assertEquals("POST", refresh.method)
        assertEquals("/mail/api/accounts", server.takeRequest().path) // 重试原路径
    }

    @Test
    fun `401 with failing refresh surfaces original 401 error`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"session expired"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))

        val err = runCatching { client.request<List<MailAccount>>("/accounts") }.exceptionOrNull()

        assertTrue(err is MailApiException)
        assertEquals(401, (err as MailApiException).status)
        assertEquals("session expired", err.message)
    }

    @Test
    fun `401 with skipRefresh does not call refresh endpoint`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"expired"}"""))

        val err = runCatching { client.request<List<MailAccount>>("/accounts", skipRefresh = true) }.exceptionOrNull()

        assertTrue(err is MailApiException)
        assertEquals(1, server.requestCount) // 只有原请求——无 refresh、无重试
    }

    @Test
    fun `non-2xx throws MailApiException with extracted message`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"mailbox unreachable"}"""))

        val err = runCatching { client.request<List<MailAccount>>("/accounts") }.exceptionOrNull()

        assertTrue(err is MailApiException)
        assertEquals(500, (err as MailApiException).status)
        assertEquals("mailbox unreachable", err.message)
    }

    @Test
    fun `parseErrorMessage handles array message, error field, plain text and empty body`() {
        assertEquals("a, b", MailClient.parseErrorMessage("""{"message":["a","b"]}""", 400))
        assertEquals("boom", MailClient.parseErrorMessage("""{"error":"boom"}""", 400))
        assertEquals("plain text", MailClient.parseErrorMessage("plain text", 400))
        assertEquals("Mail request failed: 502", MailClient.parseErrorMessage("", 502))
    }

    @Test
    fun `throttle detection - 429 status or message strings (case-sensitive, RN parity)`() {
        assertTrue(MailClient.isMailThrottleError(MailApiException(429, "anything")))
        assertTrue(MailClient.isMailThrottleError(MailApiException(400, "too many login attempts, wait a bit")))
        assertTrue(MailClient.isMailThrottleError(MailApiException(400, "operation too frequent")))
        assertTrue(!MailClient.isMailThrottleError(MailApiException(400, "bad credentials")))
        // RN includes 串为大小写敏感子串匹配（mailApi.ts:37-38）——大写服务端文案不命中属移植保真
        assertTrue(!MailClient.isMailThrottleError(MailApiException(400, "Too many login attempts")))
        assertTrue(!MailClient.isMailThrottleError(IllegalStateException("too frequent")))
    }

    @Test
    fun `serverUrl resolved per call - later requests hit updated base`() {
        var host = server.url("/").toString().trimEnd('/')
        val dynamic = MailClient({ host }, { MailClientAuth("t", "u") })
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        dynamic.request<List<MailAccount>>("/accounts")
        host = "http://10.0.0.2:9999"
        // 第二次不再打到 MockWebServer——只验证 url 拼装取的是新值
        assertEquals("http://10.0.0.2:9999/mail/api/accounts", dynamic.url("/accounts"))
        assertEquals("/mail/api/accounts", server.takeRequest().path)
    }

    @Test
    fun `cookie jar clear drops session cookies`() {
        server.enqueue(MockResponse().setBody("[]").addHeader("Set-Cookie", "mail_session=abc; Path=/"))
        client.request<List<MailAccount>>("/accounts")
        client.cookieJar.clear()
        server.enqueue(MockResponse().setBody("[]"))
        client.request<List<MailAccount>>("/accounts")

        server.takeRequest()
        assertEquals(null, server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `429 throttle error surfaces through request with status`() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("""{"message":"too frequent"}"""),
        )

        val err = runCatching { client.request<List<MailAccount>>("/accounts") }.exceptionOrNull()

        assertTrue(MailClient.isMailThrottleError(err!!))
    }

    @Test
    fun `dispatcher script - multiple sequential requests keep session cookies across refresh cycle`() {
        // 组合链：exchange 落 cookie → 401 → refresh 换 cookie → 重试带上新 cookie
        server.enqueue(MockResponse().setBody("[]").addHeader("Set-Cookie", "sess=old; Path=/"))
        client.request<List<MailAccount>>("/accounts")
        server.takeRequest()

        server.dispatcher = object : Dispatcher() {
            var refreshed = false
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (!refreshed) {
                    refreshed = true
                    MockResponse().setResponseCode(401).setBody("""{"error":"stale"}""")
                } else if (request.path == "/mail/api/auth/refresh") {
                    MockResponse().setBody("{}").addHeader("Set-Cookie", "sess=new; Path=/")
                } else {
                    MockResponse().setBody("[]")
                }
        }
        client.request<List<MailAccount>>("/accounts")

        server.takeRequest() // 401 原请求
        server.takeRequest() // refresh
        val retried = server.takeRequest()
        assertEquals("sess=new", retried.getHeader("Cookie"))
    }
}
