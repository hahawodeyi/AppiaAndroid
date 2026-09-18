package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 撤回/编辑端点 wire 对照（RN src/services/api/messages.ts:41-50 + :95-106）：
 * `POST message.recall {id}`、`POST message.batch.recall {ids}`、
 * DDP callMethod `updateMessage {rid,_id,msg[,md]}`（REST method.call 包 DDP 帧）。
 */
class RecallApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** hydrateRestSession 直接注入 REST 会话（无登录/无 DDP，同 SubscriptionsApiTest）。 */
    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    /** method.call 响应信封（message 字段 = DDP 帧 JSON 串，同 SpotlightApiTest.envelope）。 */
    private fun envelope(resultJson: String): String {
        val inner = Json.parseToJsonElement(
            """{"jsonrpc":"2.0","message":"mid","result":$resultJson}""",
        ).toString()
        return """{"message":${JsonPrimitive(inner).toString()}}"""
    }

    @Test
    fun `recall posts message recall with id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RecallApi.recallMessage(newSdk(), "m1")

        val req = server.takeRequest()
        assertEquals("/api/v1/message.recall", req.path)
        assertEquals("""{"id":"m1"}""", req.body.readUtf8())
    }

    @Test
    fun `batch recall posts message batch recall with ids array`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RecallApi.messageBatchRecall(newSdk(), listOf("a", "b"))

        val req = server.takeRequest()
        assertEquals("/api/v1/message.batch.recall", req.path)
        assertEquals("""{"ids":["a","b"]}""", req.body.readUtf8())
    }

    @Test
    fun `editMessage calls updateMessage via method call rest envelope`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("{}")))
        RecallApi.editMessage(newSdk(), rid = "r1", messageId = "m1", msg = "edited")

        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/updateMessage", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val message = body["message"]!!.jsonPrimitive.content
        val frame = Json.parseToJsonElement(message).jsonObject
        assertEquals("method", frame["msg"]!!.jsonPrimitive.content)
        assertEquals("updateMessage", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonArray
        val arg = params[0].jsonObject
        assertEquals("r1", arg["rid"]!!.jsonPrimitive.content)
        assertEquals("m1", arg["_id"]!!.jsonPrimitive.content)
        assertEquals("edited", arg["msg"]!!.jsonPrimitive.content)
        assertNull(arg["md"])
    }

    @Test
    fun `editMessage includes md only when provided`() = runBlocking {
        server.enqueue(MockResponse().setBody(envelope("{}")))
        RecallApi.editMessage(newSdk(), "r1", "m1", "edited", md = Json.parseToJsonElement("""[{"type":"PARAGRAPH"}]"""))

        val req = server.takeRequest()
        val frame = Json.parseToJsonElement(
            Json.parseToJsonElement(req.body.readUtf8()).jsonObject["message"]!!.jsonPrimitive.content,
        ).jsonObject
        val arg = frame["params"]!!.jsonArray[0].jsonObject
        assertEquals("""[{"type":"PARAGRAPH"}]""", arg["md"].toString())
    }
}
