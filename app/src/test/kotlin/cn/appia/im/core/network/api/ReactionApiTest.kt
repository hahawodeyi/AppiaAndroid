package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `POST chat.react` wire 对照（legacy appiaim-ios restApi.ts:383-385 setReaction，
 * 字段名逐字：`{emoji, messageId}`；toggle 语义在服务端，本地零裁定）。
 */
class ReactionApiTest {
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

    @Test
    fun `react posts emoji and messageId body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        ReactionApi.react(newSdk(), ":tada:", "m1")

        val req = server.takeRequest()
        assertEquals("/api/v1/chat.react", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"emoji":":tada:","messageId":"m1"}""", req.body.readUtf8())
    }

    @Test
    fun `non 2xx response throws`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))
        assertThrows(Exception::class.java) {
            runBlocking { ReactionApi.react(newSdk(), ":tada:", "m1") }
        }
    }
}
