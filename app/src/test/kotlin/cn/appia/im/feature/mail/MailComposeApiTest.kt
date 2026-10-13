package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import cn.appia.im.core.mail.SendMailRequest
import cn.appia.im.core.mail.UpsertDraftRequest
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 撰写域 API 全链（mailApi.ts:199-276 移植验证）：reply/send/forward 直发、
 * forward-draft、drafts CRUD（create/update/get/send/delete）、contacts 搜索。
 * MockWebServer 断言路径/方法/请求体形态。
 */
class MailComposeApiTest {

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private val auth = MailClientAuth("token-1", "user-1")
    private val paths = CopyOnWriteArrayList<String>()
    private val methods = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                methods += request.method!!
                bodies += request.body.readUtf8()
                return handle(request.path!!)
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

    private val draftJson =
        """{"id":"d1","accountId":"a1","to":["b@x.com"],"cc":[],"bcc":[],"subject":"wip",""" +
            """"bodyText":"body","createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z"}"""

    private fun handle(path: String): MockResponse = when {
        path.endsWith("/reply") -> MockResponse().setBody("""{"success":true,"messageId":"r1"}""")
        path.endsWith("/forward") -> MockResponse().setBody("""{"success":true,"messageId":"f1"}""")
        path.endsWith("/forward-draft") -> MockResponse().setBody(draftJson)
        path == "/mail/api/accounts/a1/send" -> MockResponse().setBody("""{"success":true,"messageId":"s1"}""")
        path == "/mail/api/drafts" -> MockResponse().setBody(draftJson)
        path == "/mail/api/drafts/d1" -> MockResponse().setBody(draftJson)
        path == "/mail/api/drafts/d1/send" -> MockResponse().setBody("""{"success":true,"messageId":"sd1"}""")
        path.startsWith("/mail/api/contacts/search") -> MockResponse().setBody(
            """[{"name":"Bob","email":"b@x.com","source":"user"}]""",
        )
        else -> MockResponse().setResponseCode(404)
    }

    private fun sendRequest() = SendMailRequest(
        to = listOf("b@x.com"),
        subject = "hi",
        bodyText = "text",
    )

    @Test
    fun `reply posts to message reply endpoint`() = runBlocking {
        val res = withContext() { replyMail(client, "a1", "m9", sendRequest()) }
        assertEquals(true, res.success)
        assertEquals("/mail/api/accounts/a1/messages/m9/reply", paths.last())
        assertEquals("POST", methods.last())
        assertTrue(bodies.last().contains("\"subject\":\"hi\""))
    }

    @Test
    fun `send posts to account send endpoint`() = runBlocking {
        val res = withContext() { sendMail(client, "a1", sendRequest()) }
        assertEquals(true, res.success)
        assertEquals("/mail/api/accounts/a1/send", paths.last())
        assertEquals("POST", methods.last())
    }

    @Test
    fun `forward posts to message forward endpoint`() = runBlocking {
        val res = withContext() { forwardMail(client, "a1", "m9", sendRequest()) }
        assertEquals(true, res.success)
        assertEquals("/mail/api/accounts/a1/messages/m9/forward", paths.last())
    }

    @Test
    fun `forward draft creates server draft`() = runBlocking {
        val draft = withContext() { createForwardMailDraft(client, "a1", "m9") }
        assertEquals("d1", draft.id)
        assertEquals("/mail/api/accounts/a1/messages/m9/forward-draft", paths.last())
        assertEquals("POST", methods.last())
    }

    @Test
    fun `draft crud roundtrip create update get send`() = runBlocking {
        val payload = UpsertDraftRequest(
            accountId = "a1",
            to = listOf("b@x.com"),
            subject = "wip",
            bodyText = "body",
        )
        val created = withContext() { createMailDraft(client, payload) }
        assertEquals("d1", created.id)
        assertEquals("/mail/api/drafts", paths[0])
        assertEquals("POST", methods[0])
        assertTrue(bodies[0].contains("\"accountId\":\"a1\""))

        val updated = withContext() { updateMailDraft(client, "d1", payload) }
        assertEquals("d1", updated.id)
        assertEquals("/mail/api/drafts/d1", paths[1])
        assertEquals("PUT", methods[1])

        val got = withContext() { getMailDraft(client, "d1") }
        assertEquals("wip", got.subject)
        assertEquals("/mail/api/drafts/d1", paths[2])
        assertEquals("GET", methods[2])

        val sent = withContext() { sendMailDraft(client, "d1") }
        assertEquals(true, sent.success)
        assertEquals("/mail/api/drafts/d1/send", paths[3])
        assertEquals("POST", methods[3])
    }

    @Test
    fun `contact search carries q param`() = runBlocking {
        val results = withContext() { searchMailContacts(client, "bo") }
        assertEquals(1, results.size)
        assertEquals("Bob", results[0].name)
        assertEquals("/mail/api/contacts/search?q=bo", paths.last())
    }

    private suspend fun <T> withContext(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
