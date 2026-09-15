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
 * 三个列表操作端点的 wire 对照（RN subscriptions.ts:4-13 + brief 绑定裁定 1/2/3）：
 * - `POST subscriptions.read` body `{rid}`（readMessages.ts:90 先 REST 后双表）
 * - `POST subscriptions.unread` body `{roomId}`（本地零改动等回推）
 * - `POST rooms.favorite` body `{roomId, favorite}`
 * 非 2xx 抛 ApiException（restClient.ts:76-91；AuthInterceptor 已实现）——服务端失败不写本地。
 */
class SubscriptionsApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** hydrateRestSession 直接注入 REST 会话（无登录/无 DDP，同 RoomsSyncRepositoryTest）。 */
    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    @Test
    fun `postSubscriptionsRead sends rid body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        SubscriptionsApi.postSubscriptionsRead(newSdk(), "room-1")

        val req = server.takeRequest()
        assertEquals("/api/v1/subscriptions.read", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"rid":"room-1"}""", req.body.readUtf8())
    }

    @Test
    fun `postSubscriptionsUnread sends roomId body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        SubscriptionsApi.postSubscriptionsUnread(newSdk(), "room-2")

        val req = server.takeRequest()
        assertEquals("/api/v1/subscriptions.unread", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"roomId":"room-2"}""", req.body.readUtf8())
    }

    @Test
    fun `postRoomsFavorite sends roomId and favorite body`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        SubscriptionsApi.postRoomsFavorite(newSdk(), "room-3", true)

        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.favorite", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"roomId":"room-3","favorite":true}""", req.body.readUtf8())
    }

    @Test
    fun `non 2xx response throws`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))
        val sdk = newSdk()
        assertThrows(Exception::class.java) {
            runBlocking { SubscriptionsApi.postSubscriptionsRead(sdk, "room-1") }
        }
    }
}
