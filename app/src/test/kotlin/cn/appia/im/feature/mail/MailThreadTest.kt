package cn.appia.im.feature.mail

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 线程三级降级（mailThread.ts loadReplyThreadMessages 移植验证）：
 * 1. thread 端点非空 → 升序返回
 * 2. thread 端点 404/空 → threadId/conversationId 列表查询映射
 * 3. 全空 → 仅锚点单条
 * 含：排序、subject 解析、preview 提取、thread link 判定、HTML 剥除。
 */
class MailThreadTest {

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private val auth = MailClientAuth("token-1", "user-1")
    private val paths = CopyOnWriteArrayList<String>()

    /** 假服务端形态开关：thread 响应体 / 列表查询响应体（null → 404）。 */
    private var threadBody: String? = null
    private var listBody: String? = null
    private var detailBody: String =
        """{"id":"mail-m1","uid":1,"from":"a@x.com","fromName":"A","to":["b@x.com"],""" +
            """"subject":"anchor subject","receivedAt":"2026-10-02T10:00:00Z","read":true,"flagged":false,""" +
            """"folder":"INBOX","size":10,"threadId":"t-9","replyCount":2,"bodyText":"anchor text"}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                val path = request.path!!
                return when {
                    path == "/mail/api/accounts/a1/messages/mail-m1/thread" ->
                        threadBody?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    // 锚点详情：剥前缀后路径
                    path == "/mail/api/messages/m1" -> MockResponse().setBody(detailBody)
                    path.startsWith("/mail/api/accounts/a1/messages?") -> {
                        // threadId/conversationId 查询降级口（RN：此口失败上抛屏幕，不静默）
                        MockResponse().setBody(listBody ?: """{"messages":[],"total":0,"page":1,"pageSize":100}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
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

    private fun load() = runBlocking {
        loadReplyThreadMessages(client, "a1", "mail-m1", "fallback subject")
    }

    // ── 一级：thread 端点 ──

    @Test
    fun `level 1 thread endpoint returns sorted messages and anchor subject wins`() {
        threadBody = """{"subject":"thread subject","messages":[
            {"id":"m3","from":"c@x.com","fromName":"C","subject":"s","receivedAt":"2026-10-03T10:00:00Z","bodyText":"third"},
            {"id":"m1","from":"a@x.com","fromName":"A","subject":"s","receivedAt":"2026-10-01T10:00:00Z","bodyText":"first"},
            {"id":"m2","from":"b@x.com","fromName":"B","subject":"s","receivedAt":"2026-10-02T10:00:00Z","bodyText":"second"}
        ]}"""
        val result = load()
        assertEquals(listOf("m1", "m2", "m3"), result.messages.map { it.id }) // 升序
        assertEquals("anchor subject", result.subject) // 锚点 subject 优先于端点 subject
        assertEquals("mail-m1", result.anchorMessageId)
        assertEquals("/mail/api/messages/m1", paths.first()) // 详情先取（剥前缀）
        assertTrue(paths.contains("/mail/api/accounts/a1/messages/mail-m1/thread")) // id 原样不剥
    }

    @Test
    fun `level 1 empty thread body falls through to list query`() {
        threadBody = """{"subject":"s","messages":[]}"""
        listBody = """{"messages":[
            {"id":"lm1","uid":1,"from":"a@x.com","fromName":"A","to":["b@x.com"],"subject":"s","receivedAt":"2026-10-01T00:00:00Z","read":true,"flagged":false,"folder":"INBOX","size":1},
            {"id":"lm2","uid":2,"from":"b@x.com","fromName":"B","to":["a@x.com"],"subject":"s","receivedAt":"2026-10-05T00:00:00Z","read":true,"flagged":false,"folder":"INBOX","size":1}
        ],"total":2,"page":1,"pageSize":100}"""
        val result = load()
        assertEquals(listOf("lm1", "lm2"), result.messages.map { it.id })
        // 降级口上行 threadId（列表行只有 id——conversationId null 跳过）
        val listCall = paths.first { it.startsWith("/mail/api/accounts/a1/messages?") }
        assertTrue(listCall.contains("threadId=t-9"))
    }

    // ── 二级：threadId/conversationId 列表查询 ──

    @Test
    fun `level 2 list query maps mail messages and keeps anchor subject`() {
        threadBody = null // 404
        listBody = """{"messages":[
            {"id":"lm2","uid":2,"from":"b@x.com","fromName":"B","to":["a@x.com"],"subject":"s","receivedAt":"2026-10-05T00:00:00Z","read":true,"flagged":false,"folder":"INBOX","size":1},
            {"id":"lm1","uid":1,"from":"a@x.com","fromName":"A","to":["b@x.com"],"subject":"s","receivedAt":"2026-10-01T00:00:00Z","read":true,"flagged":false,"folder":"INBOX","size":1}
        ],"total":2,"page":1,"pageSize":100}"""
        val result = load()
        assertEquals(listOf("lm1", "lm2"), result.messages.map { it.id }) // 时间升序
        assertEquals("anchor subject", result.subject)
    }

    // ── 三级：仅锚点 ──

    @Test
    fun `level 3 falls back to anchor single message with route subject`() {
        threadBody = null
        listBody = null
        val result = load()
        assertEquals(listOf("mail-m1"), result.messages.map { it.id })
        assertEquals("anchor subject", result.subject)
        assertEquals("anchor text", result.messages.first().bodyText)
    }

    @Test
    fun `level 3 blank anchor subject falls to route fallback subject`() {
        threadBody = null
        listBody = null
        detailBody = detailBody.replace("\"subject\":\"anchor subject\"", "\"subject\":\"\"")
        val result = load()
        assertEquals("fallback subject", result.subject)
    }

    @Test
    fun `level 3 conversation id query when thread id absent`() {
        detailBody = detailBody.replace("\"threadId\":\"t-9\"", "\"threadId\":null,\"conversationId\":\"cv-7\"")
        threadBody = null
        listBody = """{"messages":[
            {"id":"cm1","uid":1,"from":"a@x.com","fromName":"A","to":["b@x.com"],"subject":"s","receivedAt":"2026-10-01T00:00:00Z","read":true,"flagged":false,"folder":"INBOX","size":1}
        ],"total":1,"page":1,"pageSize":100}"""
        val result = load()
        assertEquals(listOf("cm1"), result.messages.map { it.id })
        val listCall = paths.first { it.startsWith("/mail/api/accounts/a1/messages?") }
        assertTrue(listCall.contains("conversationId=cv-7"))
    }

    // ── 纯函数面 ──

    @Test
    fun `thread subject resolution order anchor route then no subject`() {
        assertEquals("a", resolveThreadSubject("a", "b", "(No subject)"))
        assertEquals("b", resolveThreadSubject(" ", "b", "(No subject)"))
        assertEquals("b", resolveThreadSubject(null, "b", "(No subject)"))
        assertEquals("(No subject)", resolveThreadSubject("", null, "(No subject)"))
    }

    @Test
    fun `strip html to preview removes script style tags entities and whitespace`() {
        assertEquals(
            "Hello world",
            stripHtmlToPreview("<p>Hello</p><script>evil()</script><style>.x{}</style> world&nbsp;"),
        )
        assertEquals("", stripHtmlToPreview(null))
        assertEquals("", stripHtmlToPreview("   "))
    }

    @Test
    fun `thread message preview prefers body text over html`() {
        fun msg(text: String?, html: String?) = cn.appia.im.core.mail.MailThreadMessage(
            id = "x", from = "f", fromName = null, subject = "s",
            receivedAt = "2026-10-01T00:00:00Z", bodyText = text, bodyHtml = html,
        )
        assertEquals("plain", resolveThreadMessagePreview(msg("plain", "<b>rich</b>")))
        assertEquals("rich", resolveThreadMessagePreview(msg("  ", "<b>rich</b>")))
        assertEquals("", resolveThreadMessagePreview(msg(null, null)))
    }

    @Test
    fun `should show thread link requires thread or conversation or replies`() {
        fun detail(threadId: String?, conversationId: String?, replyCount: Int?) =
            cn.appia.im.core.mail.MessageDetail(
                id = "x", uid = 0, from = "", fromName = null, to = emptyList(), cc = emptyList(),
                subject = "", receivedAt = "", read = false, flagged = false, folder = "",
                size = 0, threadId = threadId, conversationId = conversationId, replyCount = replyCount,
            )
        assertTrue(shouldShowMailThreadLink(detail("t", null, null)))
        assertTrue(shouldShowMailThreadLink(detail(null, "c", null)))
        assertTrue(shouldShowMailThreadLink(detail(null, null, 1)))
        assertTrue(shouldShowMailThreadLink(detail(null, null, 5)))
        assertEquals(false, shouldShowMailThreadLink(detail(null, null, 0)))
        assertEquals(false, shouldShowMailThreadLink(detail(null, null, null)))
    }
}
