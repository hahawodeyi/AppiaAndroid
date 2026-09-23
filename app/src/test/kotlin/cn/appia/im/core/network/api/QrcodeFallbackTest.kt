package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * qrcode.query 7 级回退（RN qrPayload.test.ts + fetchUserQrcode.ts 次序对照）：
 * 解析全形态（qrPayload 移植）+ 回退逐级推进（前 N 级失败 → 第 N+1 级命中）。
 */
class QrcodeFallbackTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private fun qrJson(imgUrl: String, expire: Long = 99): String =
        """{"imgUrl":"$imgUrl","expire":$expire}"""

    // ── 解析形态（qrPayload.test.ts 移植）──

    @Test
    fun `toQrImageUri keeps data and http urls wraps raw base64`() {
        assertEquals("data:image/png;base64,abc", toQrImageUri("data:image/png;base64,abc"))
        assertEquals("https://x/y.png", toQrImageUri("https://x/y.png"))
        assertEquals("data:image/png;base64,SGVsbG8=", toQrImageUri("SGVsbG8="))
    }

    @Test
    fun `parse returns null for non objects`() {
        assertNull(parseQrMethodResult(null))
        assertNull(parseQrMethodResult(Json.parseToJsonElement("[]")))
    }

    @Test
    fun `parse imgUrl and expire`() {
        val q = parseQrMethodResult(Json.parseToJsonElement(qrJson("data:image/png;base64,QQ==", 99)))
        assertEquals(QrData("data:image/png;base64,QQ==", 99), q)
    }

    @Test
    fun `parse accepts imgURL alias and string expire`() {
        val q = parseQrMethodResult(Json.parseToJsonElement("""{"imgURL":"AAA","expire":"1"}"""))
        assertEquals(QrData("data:image/png;base64,AAA", 1), q)
    }

    @Test
    fun `parse null when no image field`() {
        assertNull(parseQrMethodResult(Json.parseToJsonElement("""{"expire":1}""")))
    }

    @Test
    fun `parse whole string payload`() {
        assertEquals(QrData("data:image/png;base64,AAA", 0), parseQrMethodResult(JsonPrimitive("AAA")))
    }

    @Test
    fun `parse unwraps result and data nesting`() {
        assertEquals(
            QrData("data:image/png;base64,QQ==", 2),
            parseQrMethodResult(Json.parseToJsonElement("""{"result":{"imgUrl":"data:image/png;base64,QQ==","expire":2}}""")),
        )
        assertEquals(
            QrData("data:image/png;base64,QQ==", 0),
            parseQrMethodResult(Json.parseToJsonElement("""{"data":{"base64":"QQ=="}}""")),
        )
    }

    @Test
    fun `parse accepts qrCode and base64 keys`() {
        assertEquals(
            QrData("data:image/png;base64,SGVsbG8=", 0),
            parseQrMethodResult(Json.parseToJsonElement("""{"qrCode":"SGVsbG8="}""")),
        )
    }

    @Test
    fun `parse success data envelope`() {
        assertEquals(
            QrData("data:image/png;base64,QQ==", 3),
            parseQrMethodResult(
                Json.parseToJsonElement("""{"success":true,"data":{"imgUrl":"data:image/png;base64,QQ==","expire":3}}"""),
            ),
        )
    }

    @Test
    fun `parse nested success data shells`() {
        val nested = buildJsonObject {
            put("success", true)
            put(
                "data",
                buildJsonObject {
                    put("success", true)
                    put("data", buildJsonObject { put("imgUrl", "AAA") })
                },
            )
        }
        assertEquals(QrData("data:image/png;base64,AAA", 0), parseQrMethodResult(nested))
    }

    @Test
    fun `parse json string object`() {
        assertEquals(
            QrData("data:image/png;base64,QQ==", 5),
            parseQrMethodResult(JsonPrimitive("""{"imgUrl":"QQ==","expire":5}""")),
        )
    }

    @Test
    fun `parse array tuple with numeric and string expire`() {
        assertEquals(
            QrData("data:image/png;base64,QQ==", 9),
            parseQrMethodResult(Json.parseToJsonElement("""["QQ==",9]""")),
        )
        assertEquals(
            QrData("data:image/png;base64,QQ==", 2),
            parseQrMethodResult(Json.parseToJsonElement("""["QQ==","2"]""")),
        )
    }

    @Test
    fun `parse ejson binary wrapper`() {
        assertEquals(
            QrData("data:image/png;base64,QQ==", 0),
            parseQrMethodResult(Json.parseToJsonElement("""{"imgUrl":{"${'$'}binary":"QQ=="}}""")),
        )
    }

    // ── 回退次序（fetchUserQrcode.ts attempts 数组逐级）──

    private class SeqDispatcher(private val responses: List<MockResponse>) : Dispatcher() {
        private val n = AtomicInteger()
        override fun dispatch(request: RecordedRequest): MockResponse =
            responses.getOrNull(n.getAndIncrement()) ?: MockResponse().setResponseCode(500)
    }

    @Test
    fun `level1 get without params hits first`() = runBlocking {
        server.dispatcher = SeqDispatcher(listOf(MockResponse().setBody(qrJson("data:image/png;base64,L1=="))))
        val q = fetchUserQrcodePayload(newSdk(), null)
        assertEquals("data:image/png;base64,L1==", q?.imgUrl)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `falls through to method call rest when gets fail`() = runBlocking {
        // 1/2 GET 500 → 3 method.call REST 命中（DDP 未连接，5/6 自然跳过）
        server.dispatcher = SeqDispatcher(
            listOf(
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                methodCallRestBody(qrJson("data:image/png;base64,L3==")),
            ),
        )
        val q = fetchUserQrcodePayload(newSdk(), null)
        assertEquals("data:image/png;base64,L3==", q?.imgUrl)
        val paths = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest()).map { it.path }
        assertEquals(
            listOf("/api/v1/qrcode.query", "/api/v1/qrcode.query", "/api/v1/method.call/qrcode.query"),
            paths,
        )
    }

    @Test
    fun `userId variants tried in order`() = runBlocking {
        // 1/2 GET 500 → 3 无参 REST 500 → 4 [userId] REST 命中
        server.dispatcher = SeqDispatcher(
            listOf(
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                methodCallRestBody(qrJson("data:image/png;base64,L4==")),
            ),
        )
        val q = fetchUserQrcodePayload(newSdk(), "u-123")
        assertEquals("data:image/png;base64,L4==", q?.imgUrl)
        repeat(4) { server.takeRequest() }
    }

    @Test
    fun `userId levels skipped when absent`() = runBlocking {
        // userId null：attempt 4/6 跳过——1/2/3 失败后 5（DDP 未连接）也跳过 → null，仅 3 请求
        server.dispatcher = SeqDispatcher(
            listOf(
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
            ),
        )
        assertNull(fetchUserQrcodePayload(newSdk(), null))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `returns null when all levels fail`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        assertNull(fetchUserQrcodePayload(newSdk(), "u-1"))
        // 1/2 GET + 3/4 REST method.call（5/6 DDP 未连接跳过）
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `unparseable success does not short circuit fallback`() = runBlocking {
        // 命中体无图片字段 → 该级解析 null 继续（RN `if (parsed?.imgUrl)` 同义）
        server.dispatcher = SeqDispatcher(
            listOf(
                MockResponse().setBody("""{"success":true,"expire":1}"""),
                MockResponse().setResponseCode(500),
                MockResponse().setResponseCode(500),
                methodCallRestBody(qrJson("data:image/png;base64,L4==")),
            ),
        )
        val q = fetchUserQrcodePayload(newSdk(), "u-1")
        assertEquals("data:image/png;base64,L4==", q?.imgUrl)
        assertEquals(4, server.requestCount)
    }

    /** method.call REST 响应（message 信封：DDP result 帧的 JSON 串包 data URI——冒号/斜杠转义）。 */
    private fun methodCallRestBody(qrJson: String): MockResponse {
        val inner = buildJsonObject {
            put("msg", "result")
            put("result", Json.parseToJsonElement(qrJson))
        }
        return MockResponse().setBody("""{"message":${Json.encodeToString(JsonObject.serializer(), inner)}}""")
    }
}
