package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 建频道/单聊 wire（RN src/services/api/channels.ts createChannelByMembers +
 * src/services/api/im.ts createDirectRoom）：body 逐位对照 + handleConfirm 响应解析。
 */
class ChannelsApiTest {
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
        RocketSdk(client = OkHttpClient()).also {
            it.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        }

    @Test
    fun `channels create body matches RN bit by bit`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"channel":{"_id":"RID","name":"ch"}}"""))
        ChannelsApi.createChannelByMembers(
            newSdk(),
            users = listOf("me", "bob"),
            depIds = listOf("EMT-1"),
            all = true,
        )

        val req = server.takeRequest()
        assertEquals("/api/v1/channels.create", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        // RN createChannelByMembers（channels.ts）：name 空 + members + depIds + readOnly false +
        // extraData{broadcast/encrypted/federated false, all, rt ''}
        assertEquals("", body["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("me", "bob"), body["members"]!!.jsonArrayStrings())
        assertEquals(listOf("EMT-1"), body["depIds"]!!.jsonArrayStrings())
        assertEquals(false, body["readOnly"]!!.jsonPrimitive.content.toBoolean())
        val extra = body["extraData"]!!.jsonObject
        assertEquals(false, extra["broadcast"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, extra["encrypted"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, extra["federated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, extra["all"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("", extra["rt"]!!.jsonPrimitive.content)
        assertEquals(setOf("name", "members", "depIds", "readOnly", "extraData"), body.keys)
    }

    @Test
    fun `channels create defaults depIds empty and all false`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        ChannelsApi.createChannelByMembers(newSdk(), users = listOf("a"), all = false)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(emptyList<String>(), body["depIds"]!!.jsonArrayStrings())
        assertEquals(false, body["extraData"]!!.jsonObject["all"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `im create posts username body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"room":{"_id":"DID","t":"d"}}"""))
        ChannelsApi.createDirectRoom(newSdk(), "bob")
        val req = server.takeRequest()
        assertEquals("/api/v1/im.create", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("bob", body["username"]!!.jsonPrimitive.content)
        assertEquals(setOf("username"), body.keys)
    }

    // ── parseCreateChannelResult（RN handleConfirm :282-294）──

    @Test
    fun `parse prefers channel id and fname chain`() {
        val raw = Json.parseToJsonElement(
            """{"channel":{"_id":"C1","fname":"F","dname":"D","name":"N"}}""",
        )
        val r = ChannelsApi.parseCreateChannelResult(raw)
        assertEquals("C1", r.rid)
        assertEquals("F", r.name)
    }

    @Test
    fun `parse falls back through fname dname name then group`() {
        // channel 无 name → group dname
        val raw = Json.parseToJsonElement(
            """{"channel":{},"group":{"_id":"G1","dname":"GD"}}""",
        )
        val r = ChannelsApi.parseCreateChannelResult(raw)
        assertEquals("G1", r.rid)
        assertEquals("GD", r.name)
    }

    @Test
    fun `parse returns nulls on missing ids and non-object`() {
        assertNull(ChannelsApi.parseCreateChannelResult(Json.parseToJsonElement("{}")).rid)
        assertNull(ChannelsApi.parseCreateChannelResult(null).rid)
        assertNull(ChannelsApi.parseCreateChannelResult(Json.parseToJsonElement("[]")).name)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArrayStrings(): List<String> =
        jsonArray.map { it.jsonPrimitive.content }
}
