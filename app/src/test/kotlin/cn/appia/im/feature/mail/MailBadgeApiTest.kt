package cn.appia.im.feature.mail

import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import cn.appia.im.core.mail.MailMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 角标/预览域 API（mailApi.ts:278-339 / T6）：buildLatestMailPreview 构造、
 * fetchLatestMailPreview INBOX pageSize=1 取最新、fetchMailUnreadCount 坑 8 天花板
 * （首页 100 条 receivedAt > since 计数）——MockWebServer 全链断言。
 */
class MailBadgeApiTest {

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private val auth = MailClientAuth("token-1", "user-1")
    private val paths = CopyOnWriteArrayList<String>()

    /** since 基线：2026-10-01T00:00:00Z（测试夹具锚点）。 */
    private val sinceMs = 1_790_812_800_000L

    private fun msg(id: String, from: String, subject: String, receivedAt: String = "2026-10-01T00:00:00Z", read: Boolean = false) =
        MailMessage(
            id = id, uid = 1, from = from, fromName = null, to = emptyList(), cc = emptyList(),
            subject = subject, receivedAt = receivedAt, read = read, flagged = false,
            folder = "INBOX", size = 1,
        )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
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

    private fun messagesBody(vararg messages: MailMessage, total: Int = messages.size): String {
        val list = messages.joinToString(",") { m ->
            """{"id":"${m.id}","uid":1,"from":"${m.from}","fromName":null,"to":[],"cc":[],""" +
                """"subject":"${m.subject}","receivedAt":"${m.receivedAt}","read":${m.read},"flagged":false,""" +
                """"folder":"${m.folder}","size":1}"""
        }
        return """{"messages":[$list],"total":$total,"page":1,"pageSize":1}"""
    }

    private fun handle(path: String): MockResponse = when {
        path == "/mail/api/accounts" ->
            MockResponse().setBody("""[{"id":"a1","email":"me@x.com","active":true,"createdAt":"2026-10-01T00:00:00Z"}]""")
        path.startsWith("/mail/api/accounts/a1/messages?") -> {
            // pageSize=1 预览请求 / pageSize=100 未读扫描，同路径按 query 分流
            if (path.contains("pageSize=100")) {
                MockResponse().setBody(
                    messagesBody(
                        msg("m1", "old@x.com", "old", "2026-09-01T00:00:00Z", read = true),
                        msg("m2", "new1@x.com", "new1", "2026-10-02T00:00:00Z"),
                        msg("m3", "new2@x.com", "new2", "2026-10-03T00:00:00Z"),
                        msg("m4", "new3@x.com", "new3", "2026-10-04T00:00:00Z"),
                    ),
                )
            } else {
                MockResponse().setBody(
                    messagesBody(msg("latest", "who@x.com", "latest mail", "2026-10-05T00:00:00Z")),
                )
            }
        }
        else -> MockResponse().setResponseCode(404)
    }

    // ── buildLatestMailPreview 构造（≥4 断言组） ──

    @Test
    fun `build preview prefers resolved internal name over message fromName`() {
        val m = msg("m1", "a@x.com", "s").let { it.copy(fromName = "Server Name") }
        val withResolver = buildLatestMailPreview(m, resolveFromName = { "Internal Name" })
        assertEquals("Internal Name", withResolver.fromName)
        val withoutResolver = buildLatestMailPreview(m)
        assertEquals("Server Name", withoutResolver.fromName)
    }

    @Test
    fun `build preview blank resolved name falls back to message fromName`() {
        val m = msg("m1", "a@x.com", "s").let { it.copy(fromName = "Server Name") }
        assertEquals("Server Name", buildLatestMailPreview(m, resolveFromName = { "  " }).fromName)
    }

    @Test
    fun `build preview maps unread from read flag`() {
        val unread = buildLatestMailPreview(msg("m1", "a@x.com", "s"))
        assertTrue(unread.unread)
        val read = buildLatestMailPreview(msg("m1", "a@x.com", "s", "2026-10-01T00:00:00Z", read = true))
        assertTrue(!read.unread)
    }

    @Test
    fun `build preview carries subject from and receivedAt`() {
        val p = buildLatestMailPreview(msg("m1", "a@x.com", "hello", "2026-10-05T00:00:00Z"))
        assertEquals("hello", p.subject)
        assertEquals("a@x.com", p.from)
        assertEquals("2026-10-05T00:00:00Z", p.receivedAt)
    }

    // ── fetchLatestMailPreview ──

    @Test
    fun `fetch latest preview queries first account inbox page size 1`() = runBlocking {
        val preview = fetchLatestMailPreview(client)
        assertEquals("latest mail", preview?.subject)
        assertEquals("who@x.com", preview?.from)
        assertTrue(paths.last().startsWith("/mail/api/accounts/a1/messages?"))
        assertTrue(paths.last().contains("pageSize=1"))
        assertTrue(paths.last().contains("folder=INBOX"))
    }

    @Test
    fun `fetch latest preview returns null when no accounts`() = runBlocking {
        // dispatcher 换无账户形态
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[]")
        }
        assertNull(fetchLatestMailPreview(client))
    }

    @Test
    fun `fetch latest preview swallows api errors to null`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        assertNull(fetchLatestMailPreview(client))
    }

    // ── fetchMailUnreadCount（坑 8） ──

    @Test
    fun `unread count scans first page and counts messages after since`() = runBlocking {
        val count = fetchMailUnreadCount(client, sinceMs)
        // 100 条扫描窗口：m2/m3/m4 在 since 后（m1 早于 since 且已读）
        assertEquals(3, count)
        assertTrue(paths.last().contains("pageSize=100"))
    }

    @Test
    fun `unread count early returns zero when since missing`() = runBlocking {
        assertEquals(0, fetchMailUnreadCount(client, 0))
        assertEquals(0, fetchMailUnreadCount(client, -5))
        // since<=0 短路不发请求
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `unread count swallows api errors to zero`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        assertEquals(0, fetchMailUnreadCount(client, sinceMs))
    }

    @Test
    fun `unread count with explicit account id skips accounts listing`() = runBlocking {
        fetchMailUnreadCount(client, sinceMs, accountId = "a1")
        assertTrue(paths.none { it == "/mail/api/accounts" })
    }

    @Test
    fun `unread count ceiling is first page only`() = runBlocking {
        // 100 条之外的分页不拉（坑 8 天花板：RN 同构只扫首页）
        fetchMailUnreadCount(client, sinceMs)
        assertTrue(paths.none { it.contains("page=2") })
    }
}
