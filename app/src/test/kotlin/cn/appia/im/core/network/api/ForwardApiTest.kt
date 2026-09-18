package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 转发 wire 对照（RN src/services/api/messages.ts:74-89 forwardMessage）：
 * `POST chat.sendMessage`，body `{message:{forwardUsers, forwardRooms, forwardMessageIds,
 * isForwardMessage, isForwardMerged}}`——**不含 rid/msg**（内容服务端组装，绑定裁定）。
 */
class ForwardApiTest {
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
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private fun takeMessageBody(): String {
        val req = server.takeRequest()
        assertEquals("/api/v1/chat.sendMessage", req.path)
        assertEquals("POST", req.method)
        val outer = jsonObjectSafe(req.body.readUtf8())
        return outer["message"]?.toString().orEmpty()
    }

    @Test
    fun `single forward wire fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        ForwardApi.forwardMessage(
            newSdk(),
            forwardMessageIds = listOf("m1", "m2"),
            forwardUsers = listOf("zhangsan"),
            forwardRooms = listOf("rid1"),
            isForwardMerged = false,
        )
        assertEquals(
            """{"forwardUsers":["zhangsan"],"forwardRooms":["rid1"],""" +
                """"forwardMessageIds":["m1","m2"],"isForwardMessage":true,"isForwardMerged":false}""",
            takeMessageBody(),
        )
    }

    @Test
    fun `merged forward sets isForwardMerged true`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        ForwardApi.forwardMessage(
            newSdk(),
            forwardMessageIds = listOf("m1"),
            forwardUsers = listOf("a", "b"),
            forwardRooms = emptyList(),
            isForwardMerged = true,
        )
        assertEquals(
            """{"forwardUsers":["a","b"],"forwardRooms":[],""" +
                """"forwardMessageIds":["m1"],"isForwardMessage":true,"isForwardMerged":true}""",
            takeMessageBody(),
        )
    }

    @Test
    fun `empty target arrays still present in body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        ForwardApi.forwardMessage(newSdk(), forwardMessageIds = listOf("m1"))
        assertEquals(
            """{"forwardUsers":[],"forwardRooms":[],""" +
                """"forwardMessageIds":["m1"],"isForwardMessage":true,"isForwardMerged":false}""",
            takeMessageBody(),
        )
    }
}

private fun jsonObjectSafe(body: String) =
    kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
