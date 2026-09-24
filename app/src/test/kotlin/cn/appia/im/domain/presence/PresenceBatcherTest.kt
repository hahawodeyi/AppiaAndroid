package cn.appia.im.domain.presence

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * PresenceBatcher 对照 RN batchRequestPresence（2s 防抖合并 + users.presence 逗号串 +
 * subscribeRaw added 增量去重 + 静默失败）。MockWebServer REST + WS 全栈
 * （RoleRefresherTest/RealtimeSessionManagerTest 先例）。
 */
class PresenceBatcherTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var sdk: RocketSdk

    /** WS 帧（sub added 形态断言——binding #4）。 */
    private class PresenceWsServer : WebSocketListener() {
        val frames = CopyOnWriteArrayList<String>()
        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.add(text)
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (obj["msg"]?.jsonPrimitive?.content) {
                "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
                "ping" -> webSocket.send("""{"msg":"pong"}""")
                "sub" -> webSocket.send("""{"msg":"ready","subs":["${obj["id"]?.jsonPrimitive?.content}"]}""")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    private val ws = PresenceWsServer()
    private val presenceRequests = AtomicInteger(0)
    private var presenceBody = """{"success":true,"users":[{"_id":"u1","status":"online","statusText":"mtg"}]}"""

    @BeforeEach
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/websocket") -> MockResponse().withWebSocketUpgrade(ws)
                    path.startsWith("/api/v1/users.presence") -> {
                        presenceRequests.incrementAndGet()
                        MockResponse().setBody(presenceBody)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        sdk = RocketSdk(client = OkHttpClient())
            .also {
                it.initialize(server.url("/").toString())
                it.hydrateRestSession(server.url("/").toString(), "tok", "me")
            }
        PresenceBatcher.attach(sdk, scope)
    }

    @AfterEach
    fun tearDown() {
        PresenceBatcher.reset()
        runCatching { sdk.ddp?.disconnect() }
        runCatching { sdk.ddp?.cancelTransport() }
        scope.cancel()
        runCatching { server.shutdown() }
    }

    /** 等待条件（真时轮询；RealtimeSessionManagerTest awaitCond 先例）。 */
    private suspend fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timeout waiting for: $desc")
    }

    private fun subFrames() = ws.frames.mapNotNull {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }.filter { it["msg"]?.jsonPrimitive?.content == "sub" && it["name"]?.jsonPrimitive?.content == "stream-user-presence" }

    @Test
    fun `debounces ids into one users presence call with comma string`() = runBlocking {
        PresenceBatcher.requestUserPresence("u1")
        PresenceBatcher.requestUserPresence("u2")
        // 防抖窗口内合并：两条请求共享 pending 集（未 flush）
        assertEquals(setOf("u1", "u2"), PresenceBatcher.pendingIdsForTest())

        PresenceBatcher.flushNow()
        awaitCond("users.presence called") { presenceRequests.get() == 1 }

        val req = server.takeRequest()
        assertEquals("ids=u1,u2", req.requestUrl?.query) // 逗号串（RN ids.join(',')）
        // 请求批次全量落 store：响应缺失的 u2 → OFFLINE（RN reduce 缺省）
        assertEquals(TUserStatus.ONLINE, PresenceStore.snapshot("u1")?.status)
        assertEquals("mtg", PresenceStore.snapshot("u1")?.statusText)
        assertEquals(TUserStatus.OFFLINE, PresenceStore.snapshot("u2")?.status)
    }

    @Test
    fun `subscribes raw added array form and dedupes across flushes`() = runBlocking {
        PresenceBatcher.requestUserPresence("u1")
        PresenceBatcher.requestUserPresence("u2")
        PresenceBatcher.flushNow()
        awaitCond("first sub frame") { subFrames().size == 1 }

        // binding #4：['', {added: ids}]（非 eventName 形态）——params 原样透传
        val first = subFrames().first()
        val params = first["params"]!!.jsonArray
        assertEquals("", params[0].jsonPrimitive.content)
        assertEquals("""{"added":["u1","u2"]}""", params[1].toString())
        assertEquals(setOf("u1", "u2"), PresenceBatcher.subscribedIdsForTest())

        // 第二批：u1 已订阅 → added 只含增量
        PresenceBatcher.requestUserPresence("u1")
        PresenceBatcher.requestUserPresence("u3")
        PresenceBatcher.flushNow()
        awaitCond("second sub frame") { subFrames().size == 2 }
        val second = subFrames()[1]
        assertEquals("""{"added":["u3"]}""", second["params"]!!.jsonArray[1].toString())
    }

    @Test
    fun `failed presence fetch is silent and store untouched`() = runBlocking {
        presenceBody = """{"success":false}"""
        PresenceBatcher.requestUserPresence("u1")
        PresenceBatcher.flushNow()
        awaitCond("fetch attempted") { presenceRequests.get() == 1 }
        assertEquals(null, PresenceStore.snapshot("u1")) // 静默失败不落 store
    }
}
