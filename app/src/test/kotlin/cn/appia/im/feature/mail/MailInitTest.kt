package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailAccount
import cn.appia.im.core.mail.MailApiException
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import kotlinx.coroutines.runBlocking
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
 * bootstrapMailSession 引导链（mailApi.ts:71-107 坑 6 钉死）+ setup-required 判定：
 * 成功直返 / 403 静默空 / 401→exchange→重试 / exchange setup-required→空 / 其他错照抛。
 */
class MailInitTest {

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private var auth: MailClientAuth? = MailClientAuth("token-1", "user-1")

    @Before
    fun setUp() {
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

    private val user = MailExchangeUser(email = "u@x.com", name = "U", username = "u")

    private fun accountsBody(vararg ids: String) =
        ids.joinToString(",", prefix = "[", postfix = "]") {
            """{"id":"$it","email":"u@x.com","active":true,"createdAt":"2026"}"""
        }

    @Test
    fun `accounts ok returns directly without exchange`() = runBlocking {
        server.enqueue(MockResponse().setBody(accountsBody("a1")))

        val accounts = bootstrapMailSession(client, user) { auth }

        assertEquals(listOf("a1"), accounts.map { it.id })
        assertEquals("/mail/api/accounts", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `403 mail disabled returns empty silently`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))

        val accounts = bootstrapMailSession(client, user) { auth }

        assertTrue(accounts.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `401 then exchange ok then accounts retry succeeds`() = runBlocking {
        // MailClient 内建 401→/auth/refresh→重试：refresh 也 401 才把原 401 抛给 bootstrap 链
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("refresh denied"))
        server.enqueue(MockResponse().setBody("""{"accessToken":"t","refreshToken":"r"}"""))
        server.enqueue(MockResponse().setBody(accountsBody("a1", "a2")))

        val accounts = bootstrapMailSession(client, user) { auth }

        assertEquals(listOf("a1", "a2"), accounts.map { it.id })
        assertEquals("/mail/api/accounts", server.takeRequest().path) // 原 401
        assertEquals("/mail/api/auth/refresh", server.takeRequest().path) // 内建 refresh（失败）
        val exchange = server.takeRequest()
        assertEquals("/mail/api/auth/appiaim-exchange", exchange.path)
        assertEquals("POST", exchange.method)
        val body = exchange.body.readUtf8()
        assertTrue(body.contains("\"appiaimUserId\":\"user-1\""))
        assertTrue(body.contains("\"appiaimToken\":\"token-1\""))
        assertTrue(body.contains("\"email\":\"u@x.com\""))
        assertEquals("/mail/api/accounts", server.takeRequest().path) // bootstrap 重试
    }

    @Test
    fun `401 with blank user email returns empty without exchange`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("refresh denied"))

        val accounts = bootstrapMailSession(client, MailExchangeUser(email = "  ")) { auth }

        assertTrue(accounts.isEmpty())
        assertEquals(2, server.requestCount) // 原请求 + 内建 refresh；无 exchange、无重试
    }

    @Test
    fun `401 and exchange rejected as setup-required returns empty`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("refresh denied"))
        // exchange 也 401（未绑定邮箱）→ setup-required → 静默空
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"not bound"}"""))

        val accounts = bootstrapMailSession(client, user) { auth }

        assertTrue(accounts.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `401 exchange succeeds but accounts still 403 returns empty`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("refresh denied"))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))

        val accounts = bootstrapMailSession(client, user) { auth }

        assertTrue(accounts.isEmpty())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `500 on initial accounts propagates`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        val err = runCatching { bootstrapMailSession(client, user) { auth } }.exceptionOrNull()

        assertTrue(err is MailApiException)
        assertEquals(500, (err as MailApiException).status)
    }

    @Test
    fun `401 with missing auth provider surfaces setup-required empty`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("refresh denied"))
        auth = null

        val accounts = bootstrapMailSession(client, user) { auth }

        assertTrue(accounts.isEmpty()) // requireMailAuth → 401 语义 → setup-required → []
    }

    @Test
    fun `isMailSetupRequiredError - 401, 403 and message marker`() {
        assertTrue(isMailSetupRequiredError(MailApiException(401, "unauthorized")))
        assertTrue(isMailSetupRequiredError(MailApiException(403, "forbidden")))
        assertTrue(isMailSetupRequiredError(MailApiException(400, "Incomplete user info for mail exchange")))
        assertTrue(!isMailSetupRequiredError(MailApiException(500, "boom")))
        assertTrue(!isMailSetupRequiredError(IllegalStateException("401")))
    }
}
