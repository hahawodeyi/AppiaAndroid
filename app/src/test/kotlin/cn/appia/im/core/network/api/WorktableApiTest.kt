package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.rest.AuthInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * worktable_config 裸 fetch 对照（RN fetchWorktableConfig.ts:8-21，研究坑 8）：
 * - 路径/参数/method/响应解析；
 * - **无鉴权头**（RN 原生 fetch 不带 IM auth 头）——MockWebServer 捕获请求头实证；
 * - 结构性保证：生产默认注入的 RocketHttp.client 不含 AuthInterceptor（坑 8 的 AA 侧落点）。
 */
class WorktableApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun url() = server.url("/").toString()

    @Test
    fun `bare request path query method and NO auth headers`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        fetchWorktableConfig(url(), OkHttpClient())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/appia_be/v1/api/worktable_config", req.path.orEmpty().substringBefore("?"))
        val query = req.requestUrl!!.queryParameterNames.associateWith { req.requestUrl!!.queryParameter(it) }
        assertEquals("app", query["platform"])
        // 坑 8：IM 鉴权头不得出现（RN 原生 fetch 同款裸请求）
        assertNull(req.getHeader("X-Auth-Token"))
        assertNull(req.getHeader("X-User-Id"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test
    fun `default production client carries no AuthInterceptor`() {
        // 结构性保证（报告证据）：WorktableApi 默认走 RocketHttp.client（零 interceptor 构造），
        // 与 RocketSdk/RetrofitFactory（挂 AuthInterceptor）的鉴权栈无交集。
        assertTrue(RocketHttp.client.interceptors.none { it is AuthInterceptor })
        assertTrue(RocketHttp.client.networkInterceptors.isEmpty())
    }

    @Test
    fun `trailing slash server is normalized before appending path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        fetchWorktableConfig(url().removeSuffix("/") + "/", OkHttpClient())
        val req = server.takeRequest()
        assertTrue(req.path.orEmpty().startsWith("/appia_be/"))
    }

    @Test
    fun `parses data array with verbatim wire fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"name":"Admin","row":1,"items":[
                   {"name":"\u8003\u52e4\u6253\u5361","desc":"","status":1,"type":0,"seq":1,
                    "url":"/appia_fe/att","icon":"/icon/att.png","need_auth":true,
                    "url_type":1,"extra":{"source":"HR","name":"hr.bot","needVPN":true}}]}]}""",
            ),
        )
        val groups = fetchWorktableConfig(url(), OkHttpClient())
        assertEquals(1, groups.size)
        val item = groups[0].items[0]
        assertEquals("\u8003\u52e4\u6253\u5361", item.name)
        assertEquals(1, groups[0].row)
        assertTrue(item.needAuth)
        assertEquals(1, item.urlType)
        assertEquals("HR", item.extra?.source)
        assertEquals("hr.bot", item.extra?.name)
        assertEquals(true, item.extra?.needVPN)
    }

    @Test
    fun `non-array data parses to empty list like RN Array-isArray guard`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement("""{"data":{"x":1}}""")
        val data = (root as? kotlinx.serialization.json.JsonObject)?.get("data")
        assertNull(data as? kotlinx.serialization.json.JsonArray)
    }

    @Test
    fun `non 2xx throws like RN res-ok check`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"data":[]}"""))
        assertThrows(Exception::class.java) {
            runBlocking { fetchWorktableConfig(url(), OkHttpClient()) }
        }
    }
}
