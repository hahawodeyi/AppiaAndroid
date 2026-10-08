package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
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
 * `GET users.externalToken` wire 对照（RN webAuth.ts:29-35 sdk.get 三参
 * {url, source, platform:'APP'}；legacy restApi.ts:1183 getAuthCode 同构）。
 */
class ExternalTokenApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also {
            it.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        }

    @Test
    fun `fetch sends url source platform params`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        fetchExternalToken(newSdk(), "https://survey.appia.cn/q", "")

        val req = server.takeRequest()
        assertEquals("/api/v1/users.externalToken", req.path.orEmpty().substringBefore("?"))
        assertEquals("GET", req.method)
        val query = req.requestUrl!!.queryParameterNames.associateWith { req.requestUrl!!.queryParameter(it) }
        // source 空串编码后为空值参数（queryParameter 返回 ""）
        assertEquals("https://survey.appia.cn/q", query["url"])
        assertEquals("", query["source"])
        assertEquals("APP", query["platform"])
    }

    @Test
    fun `parses accessUrl and token`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"accessUrl":"https://ssc-docs.appia.vip/a?x=1","token":"CODE123"}""",
            ),
        )
        val r = fetchExternalToken(newSdk(), "https://ssc-docs.appia.vip/doc", "")
        assertTrue(r.success)
        assertEquals("https://ssc-docs.appia.vip/a?x=1", r.accessUrl)
        assertEquals("CODE123", r.token)
    }

    @Test
    fun `data envelope flattens like sdk get`() = runBlocking {
        // RocketSdk get 已平铺 `data ?? resp`——裸形态与包裹形态解析同口径
        server.enqueue(MockResponse().setBody("""{"data":{"success":true,"token":"IN_DATA"}}"""))
        val r = fetchExternalToken(newSdk(), "https://lexiang.appia.cn/x", "")
        assertEquals("IN_DATA", r.token)
    }

    @Test
    fun `empty fields parse to null and success false`() {
        val r = parseExternalToken(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"success":false}"""),
        )
        assertNull(r.accessUrl)
        assertNull(r.token)
    }

    @Test
    fun `non 2xx response throws for caller degradation`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        assertThrows(Exception::class.java) {
            runBlocking { fetchExternalToken(newSdk(), "https://survey.appia.cn/q", "") }
        }
    }
}
