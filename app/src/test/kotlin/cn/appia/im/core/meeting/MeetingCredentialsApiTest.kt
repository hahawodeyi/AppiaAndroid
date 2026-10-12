package cn.appia.im.core.meeting

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * RTC 凭据 wire 对照（M8 T1 spike §2.2，结论 B）：URL 形态/query、X-Auth-Token + X-User-Id
 * **两者同发**（缺 X-User-Id 实证 401）、base 解析链（覆盖→同源→兜底，非 appia 域落兜底）、
 * 宽容解（反向推断字段 + data 包裹 + 未知键）、非 2xx → null。
 */
class MeetingCredentialsApiTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun fetch(base: String = server.url("/").toString(), channel: String = "ch-1") = runBlocking {
        fetchMeetingCredentials(
            client = OkHttpClient(),
            base = base,
            channelName = channel,
            uid = 42,
            token = "tok",
            userId = "u-9",
        )
    }

    // ---- URL / headers ----

    @Test
    fun `sends GET to v2 credentials endpoint with channelName and uid`() = runBlocking {
        enqueue("""{"appId":"app","token":"007xyz","uid":"42"}""")
        fetch()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v2/video/meeting/credentials", recorded.path?.substringBefore('?'))
        val query = recorded.path?.substringAfter('?').orEmpty()
        assertEquals("channelName=ch-1", query.substringBefore("&"))
        assertEquals("uid=42", query.substringAfter("&"))
    }

    @Test
    fun `sends both X-Auth-Token and X-User-Id plus platform header`() = runBlocking {
        enqueue("""{"appId":"app","token":"t","uid":"1"}""")
        fetch()
        val recorded = server.takeRequest()
        assertEquals("tok", recorded.getHeader("X-Auth-Token"))
        assertEquals("u-9", recorded.getHeader("X-User-Id"))
        assertEquals("mobile", recorded.getHeader("X-Client-Platform"))
    }

    @Test
    fun `url builder encodes channelName`() {
        val url = buildMeetingCredentialsUrl("https://m.appia.vip/", "café A/2", 7)
        assertEquals(
            "https://m.appia.vip/api/v2/video/meeting/credentials?channelName=${"caf%C3%A9%20A%2F2"}&uid=7",
            url,
        )
    }

    // ---- base resolution chain（spike §2.2）----

    @Test
    fun `base resolution - override wins when appia domain`() {
        assertEquals(
            "https://meeting.example.appia.vip",
            resolveMeetingApiBase("https://meeting.example.appia.vip/", "https://appia.cn"),
        )
    }

    @Test
    fun `base resolution - same-origin appia server used when no override`() {
        assertEquals("https://appia.cn", resolveMeetingApiBase(null, "https://appia.cn"))
        assertEquals("https://x.appia.cn/base", resolveMeetingApiBase("", "https://x.appia.cn/base"))
    }

    @Test
    fun `base resolution - non-appia same-origin falls back to meeting-server`() {
        assertEquals(DEFAULT_MEETING_API_BASE, resolveMeetingApiBase(null, "https://chat.internal.corp"))
        assertEquals(DEFAULT_MEETING_API_BASE, resolveMeetingApiBase(null, null))
        assertEquals(DEFAULT_MEETING_API_BASE, resolveMeetingApiBase("not a url", ""))
    }

    // ---- lenient parse（字段反向推断，抓包前宽容）----

    @Test
    fun `parses inferred fields and ignores unknown keys`() = runBlocking {
        enqueue("""{"appId":"app","token":"007xyz","uid":"42","extra":1,"channelName":"ch-1"}""")
        val creds = fetch()
        assertEquals("app", creds?.appId)
        assertEquals("007xyz", creds?.token)
        assertEquals("42", creds?.uid)
    }

    @Test
    fun `parses data-wrapped body when root lacks fields`() = runBlocking {
        enqueue("""{"success":true,"data":{"appId":"app","token":"tk"}}""")
        val creds = fetch()
        assertEquals("app", creds?.appId)
        assertEquals("tk", creds?.token)
    }

    @Test
    fun `missing fields yield nulls not failure`() {
        val creds = parseMeetingCredentials("""{"ok":true,"data":{}}""")
        assertNull(creds?.appId)
        assertNull(creds?.token)
        assertNull(creds?.uid)
    }

    @Test
    fun `garbage body yields null`() {
        assertNull(parseMeetingCredentials("not json"))
        assertNull(parseMeetingCredentials("[]"))
    }

    // ---- failure path ----

    @Test
    fun `non-2xx returns null`() = runBlocking {
        enqueue("""{"message":"unauthorized"}""", code = 401)
        assertNull(fetch())
    }

    @Test
    fun `unreachable server returns null`() {
        val dead = server.url("/").toString()
        server.shutdown()
        runBlocking { assertNull(fetch(base = dead)) }
    }
}
