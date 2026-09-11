package cn.appia.im.core.network.ddp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** 取 JsonObject 字段的字符串值（JsonPrimitive.content）。 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal val JsonElement.textOrNull: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

/**
 * 协议层纯函数测试（无 WS 传输）：URL 推导、消息构造、三路分发键提取。
 * 逐行为对照 appiaMobile/src/services/realtime/ddpClient.ts。
 */
class DdpProtocolTest {

    // ---- 语义 1：WS URL 推导（TS hostToWs ddpClient.ts:62-69）----

    @Test
    fun `hostToWs upgrades https to wss`() {
        assertEquals("wss://example.com/websocket", hostToWs("https://example.com", null))
    }

    @Test
    fun `hostToWs keeps http as ws`() {
        assertEquals("ws://example.com/websocket", hostToWs("http://example.com", null))
    }

    @Test
    fun `hostToWs strips trailing slash`() {
        assertEquals("wss://example.com/websocket", hostToWs("https://example.com/", null))
    }

    @Test
    fun `hostToWs respects explicit useSsl override`() {
        assertEquals("ws://example.com/websocket", hostToWs("https://example.com", false))
        assertEquals("wss://example.com/websocket", hostToWs("http://example.com", true))
    }

    @Test
    fun `hostToWs is case-insensitive on protocol`() {
        assertEquals("wss://Example.com/websocket", hostToWs("HTTPS://Example.com", null))
    }

    @Test
    fun `hostToWs preserves path prefix`() {
        assertEquals("wss://example.com/prefix/websocket", hostToWs("https://example.com/prefix", null))
    }

    // ---- 语义 2/6：connect/ping/pong 消息构造（不带 id 字段）----

    @Test
    fun `connect message carries version and support but no id`() {
        val m = ddpConnectMessage()
        assertEquals("connect", m.str("msg"))
        assertEquals("1", m.str("version"))
        assertEquals(listOf("1", "pre2", "pre1"), m["support"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(m.containsKey("id"))
    }

    @Test
    fun `ping and pong messages carry no id`() {
        assertEquals("ping", ddpPingMessage().str("msg"))
        assertEquals("pong", ddpPongMessage().str("msg"))
        assertFalse(ddpPingMessage().containsKey("id"))
        assertFalse(ddpPongMessage().containsKey("id"))
    }

    // ---- 语义 6/9/10：method/sub/unsub 消息构造 ----

    @Test
    fun `method message carries method and params`() {
        val m = ddpMethodMessage("login", listOf(buildJsonObject { put("resume", "tok") }))
        assertEquals("method", m.str("msg"))
        assertEquals("login", m.str("method"))
        assertEquals("tok", m["params"]!!.jsonArray[0].jsonObject.str("resume"))
        // id 由 sendRaw 统一补挂（TS ddpClient.ts:306）
        assertFalse(m.containsKey("id"))
    }

    @Test
    fun `sub message shape matches rocket chat sdk`() {
        val m = ddpSubMessage(
            "sub-0",
            "stream-notify-user",
            JsonArray(
                listOf(
                    JsonPrimitive("uid/x-changed"),
                    buildJsonObject { put("useCollection", false); put("args", JsonArray(emptyList())) },
                ),
            ),
        )
        assertEquals("sub", m.str("msg"))
        assertEquals("sub-0", m.str("id"))
        assertEquals("stream-notify-user", m.str("name"))
        assertEquals("uid/x-changed", m["params"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(false, m["params"]!!.jsonArray[1].jsonObject["useCollection"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `unsub message shape`() {
        val m = ddpUnsubMessage("sub-3")
        assertEquals("unsub", m.str("msg"))
        assertEquals("sub-3", m.str("id"))
    }

    // ---- 语义 11：事件三路分发（TS handleMessage ddpClient.ts:293-295）----

    @Test
    fun `eventKeys dispatch on msg collection and id separately`() {
        val data = ddpJson.parseToJsonElement("""{"msg":"changed","collection":"stream-room-messages","id":"e9"}""").jsonObject
        assertEquals(listOf("changed", "stream-room-messages", "e9"), eventKeys(data))
    }

    @Test
    fun `eventKeys same name on msg and collection emits twice`() {
        val data = ddpJson.parseToJsonElement("""{"msg":"stream-x","collection":"stream-x","id":"i"}""").jsonObject
        assertEquals(listOf("stream-x", "stream-x", "i"), eventKeys(data))
    }

    @Test
    fun `eventKeys skips missing keys and empty message`() {
        assertEquals(listOf("connected"), eventKeys(ddpJson.parseToJsonElement("""{"msg":"connected"}""").jsonObject))
        assertEquals(listOf("users"), eventKeys(ddpJson.parseToJsonElement("""{"collection":"users"}""").jsonObject))
        assertEquals(listOf("ddp-1"), eventKeys(ddpJson.parseToJsonElement("""{"id":"ddp-1"}""").jsonObject))
        assertEquals(emptyList<String>(), eventKeys(ddpJson.parseToJsonElement("{}").jsonObject))
    }
}
