package cn.appia.im.core.network.api

import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.feature.chatlist.ensureAgentRoom
import cn.appia.im.feature.chatlist.getMyLocalAgentRid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Claw agents 管理 wire（RN services/api/clawAgents.ts 四 POST 逐位）+
 * ensureAgentRoom 缓存/建私聊链（RN getMyLocalAgentRid.ts）。
 */
class ClawAgentsApiTest {
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

    private fun payload() = ClawAgentPayload(name = "My Agent", agentId = "AG-1", url = "https://u", apiKey = " k ")

    private fun body(req: okhttp3.mockwebserver.RecordedRequest): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    @Test
    fun `create body matches RN and returns generated username`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        val username = ClawAgentsApi.createClawAgent(newSdk(), payload(), timestamp = 12345678L)

        val req = server.takeRequest()
        assertEquals("/api/v1/appia.createClawAgents", req.path)
        val body = body(req)
        // RN buildClawBotUsername：body 取 agentId 优先（AG-1 → ag-1）
        assertTrue(body["username"]!!.jsonPrimitive.content.startsWith("claw.ag-1."))
        assertTrue(body["username"]!!.jsonPrimitive.content.endsWith(".bot"))
        assertEquals("My Agent", body["appiaOpenClawName"]!!.jsonPrimitive.content)
        assertEquals("https://u", body["appiaOpenClawBaseurl"]!!.jsonPrimitive.content)
        assertEquals("k", body["appiaOpenClawApiSecret"]!!.jsonPrimitive.content)
        assertEquals("AG-1", body["appiaOpenClawAgentId"]!!.jsonPrimitive.content)
        assertTrue(username.startsWith("claw.") && username.endsWith(".bot"))
    }

    @Test
    fun `update omits secret when blank and posts id`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        ClawAgentsApi.updateClawAgent(newSdk(), "ID-1", payload().copy(apiKey = "  "))

        val req = server.takeRequest()
        assertEquals("/api/v1/appia.updateClawAgent", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("ID-1", body["_id"]!!.jsonPrimitive.content)
        assertNull(body["appiaOpenClawApiSecret"])
    }

    @Test
    fun `disable and restore post delete and restore endpoints`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        val sdk = newSdk()
        ClawAgentsApi.disableClawAgent(sdk, "ID-1")
        ClawAgentsApi.restoreClawAgent(sdk, "ID-1")
        assertEquals("/api/v1/appia.deleteClawAgent", server.takeRequest().path)
        assertEquals("/api/v1/appia.restoreClawAgent", server.takeRequest().path)
    }

    @Test
    fun `success false throws server message for duplicate detection`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":false,"error":"Error: appiaOpenClawAgentId already exists"}""",
            ),
        )
        val err = runCatching { ClawAgentsApi.createClawAgent(newSdk(), payload()) }
            .exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            "actual error: $err",
            cn.appia.im.feature.agents.isDuplicateClawAgentIdError(err!!.message.orEmpty()),
        )
    }

    // ── ensureAgentRoom（RN ensureAgentRoom + MMKV 缓存链）──

    @Test
    fun `ensureAgentRoom creates self DM then caches rid`() = runBlocking {
        val kv = InMemoryKvStore()
        val sdk = newSdk()
        server.enqueue(MockResponse().setBody("""{"room":{"_id":"RID-9"}}"""))

        val rid = ensureAgentRoom(kv, sdk, "bob", "https://s1/")
        assertEquals("RID-9", rid)
        assertEquals("/api/v1/im.create", server.takeRequest().path)
        assertEquals("RID-9", getMyLocalAgentRid(kv, "bob", "https://s1"))

        // 二次命中缓存：无新请求（requestCount 累计，仍为首次的 1）
        server.enqueue(MockResponse().setBody("""{"room":{"_id":"OTHER"}}"""))
        assertEquals("RID-9", ensureAgentRoom(kv, sdk, "bob", "https://s1"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ensureAgentRoom throws when no rid in response`() = runBlocking {
        val kv = InMemoryKvStore()
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        val err = runCatching { ensureAgentRoom(kv, newSdk(), "bob", "https://s1") }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertNull(getMyLocalAgentRid(kv, "bob", "https://s1"))
    }
}
