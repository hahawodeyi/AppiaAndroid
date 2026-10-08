package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 消息跳转 wire 对照（M5-T6 / RN）：
 * - loadSurrounding：Meteor method 双元素参数 `{_id, rid}, 50`（RN
 *   src/lib/message/jumpToMessage/loadSurroundingMessages.ts :44-48）；
 * - getChatMessage：REST `GET chat.getMessage {msgId}`（RN services/api/messages.ts :7-20）。
 */
class MessageJumpApiTest {
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
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    /** method.call 响应信封（message 字段 = DDP 帧 JSON 串，同 RoomSettingsApiTest.envelope）。 */
    private fun envelope(resultJson: String): String {
        val inner = Json.parseToJsonElement(
            """{"jsonrpc":"2.0","message":"mid","result":$resultJson}""",
        ).toString()
        return """{"message":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(inner))}}"""
    }

    private fun methodFrame(requestBody: String) =
        Json.parseToJsonElement(requestBody).jsonObject.let { outer ->
            Json.parseToJsonElement(outer["message"]!!.jsonPrimitive.content).jsonObject
        }

    @Test
    fun `loadSurrounding posts {_id,rid} and count 50 as two-element params`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope("""{"messages":[{"_id":"m1","rid":"r1","msg":"a"}],"moreBefore":true,"moreAfter":false}"""),
            ),
        )
        val raw = MessageJumpApi.loadSurrounding(newSdk(), "m1", "r1")

        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/loadSurroundingMessages", req.path)
        val frame = methodFrame(req.body.readUtf8())
        assertEquals("loadSurroundingMessages", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonArray
        assertEquals(2, params.size) // RN callMethod(method, {_id, rid}, 50)
        assertEquals("m1", params[0].jsonObject["_id"]!!.jsonPrimitive.content)
        assertEquals("r1", params[0].jsonObject["rid"]!!.jsonPrimitive.content)
        assertEquals("50", params[1].jsonPrimitive.content)

        assertEquals(1, raw.apiMessages.size)
        assertTrue(raw.moreBefore)
        assertEquals(false, raw.moreAfter)
    }

    @Test
    fun `loadSurrounding filters non-string _id entries`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                envelope("""{"messages":[{"rid":"r1"},{"_id":"m1","rid":"r1"}],"moreBefore":false,"moreAfter":true}"""),
            ),
        )
        val raw = MessageJumpApi.loadSurrounding(newSdk(), "m1", "r1")
        assertEquals(1, raw.apiMessages.size) // RN isApiMessage 门：无字符串 _id 不入列
        assertEquals("m1", raw.apiMessages[0]["_id"]!!.jsonPrimitive.content)
        assertEquals(false, raw.moreBefore)
        assertTrue(raw.moreAfter)
    }

    @Test
    fun `getChatMessage returns message object or null`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"message":{"_id":"m1","rid":"r1","msg":"a"},"success":true}"""),
        )
        val sdk = newSdk()
        assertEquals("m1", MessageJumpApi.getChatMessage(sdk, "m1")?.get("_id")?.jsonPrimitive?.content)
        assertEquals("chat.getMessage?msgId=m1", server.takeRequest().path?.removePrefix("/api/v1/"))

        server.enqueue(MockResponse().setBody("""{"success":false}""")) // 无 message → null（RN isApiMessage）
        assertNull(MessageJumpApi.getChatMessage(sdk, "m2"))
    }
}
