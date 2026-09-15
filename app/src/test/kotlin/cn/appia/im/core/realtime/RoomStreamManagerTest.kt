package cn.appia.im.core.realtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.ddp.DdpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val testJson = Json { ignoreUnknownKeys = true }

private fun parse(raw: String): JsonObject? =
    runCatching { testJson.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun JsonObject?.s(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * RoomStreamManager 集成测试（Robolectric + MockWebServer WebSocket 全栈）：
 * 三订阅帧形态（RN sdk.subscribeRoom index.ts:244-253）、幂等先 unsub、退房退订、
 * 传输断开后活跃流重订、teardown 清活跃表不复活、消息 rid 过滤 → 落库缝 → SharedFlow 发射、
 * parse 助手边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomStreamManagerTest {

    private val server = MockWebServer()
    private val wsListeners = ConcurrentLinkedQueue<RoomWsServer>()
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()
    private val sdks = CopyOnWriteArrayList<RocketSdk>()

    private lateinit var host: String
    private lateinit var sdk: RocketSdk
    private lateinit var manager: RoomStreamManager

    /** 落库缝记录：(raw, rid) 对。 */
    private val persisted = CopyOnWriteArrayList<Pair<JsonObject, String>>()

    @Before
    fun setUp() {
        server.start()
        host = server.url("/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(wsListeners.poll()!!)
        }
        sdk = RocketSdk(client = OkHttpClient()).also { sdks.add(it) }
        sdk.initialize(host)
        manager = RoomStreamManager(
            sdk = sdk,
            persistMessage = { raw, rid -> persisted.add(raw as JsonObject to rid) },
            scope = newScope(),
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        sdks.forEach { s ->
            s.ddp?.let {
                runCatching { it.disconnect() }
                runCatching { it.cancelTransport() }
            }
        }
        runCatching { server.shutdown() }
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }

    private suspend fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timeout waiting for: $desc")
    }

    private fun RoomWsServer.subFrames(): List<JsonObject> =
        frames.mapNotNull { parse(it) }.filter { it.s("msg") == "sub" }

    private fun RoomWsServer.unsubFrames(): List<JsonObject> =
        frames.mapNotNull { parse(it) }.filter { it.s("msg") == "unsub" }

    private fun RoomWsServer.subEvents(): List<String> = subFrames()
        .mapNotNull { f -> (f["params"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content }
        .sorted()

    // ---- 订阅三帧形态 ----

    @Test
    fun `subscribeRoom issues the three RN subscription shapes`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()

        manager.subscribeRoom("rid-1")
        awaitCond("three subs sent") { ws.subFrames().size >= 3 }

        val names = ws.subFrames().mapNotNull { it.s("name") }.sorted()
        assertEquals(listOf("stream-notify-room", "stream-notify-room", "stream-room-messages"), names)
        // params[0]：rid / {rid}/user-activity / {rid}/deleteMessage；params[1].args=[rid]
        assertEquals(listOf("rid-1", "rid-1/deleteMessage", "rid-1/user-activity"), ws.subEvents())
        ws.subFrames().forEach { f ->
            val params = f["params"] as? JsonArray
            val args = (params?.get(1) as? JsonObject)?.get("args") as? JsonArray
            assertEquals("rid-1", (args?.firstOrNull() as? JsonPrimitive)?.content)
        }
    }

    @Test
    fun `subscribeRoom without ddp throws`() = runBlocking {
        val fresh = RocketSdk(client = OkHttpClient()).also { sdks.add(it) } // 未 initialize → 无 ddp
        val lone = RoomStreamManager(fresh, { _, _ -> }, newScope())
        val ex = runCatching { lone.subscribeRoom("rid-x") }.exceptionOrNull()
        assertTrue(ex is DdpException)
    }

    // ---- 幂等：重复 sub 先 unsub ----

    @Test
    fun `re-subscribing same rid unsubscribes the previous three subs`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()

        manager.subscribeRoom("rid-1")
        awaitCond("first subs") { ws.subFrames().size >= 3 }
        manager.subscribeRoom("rid-1") // 幂等重入：先退订旧三条，再订新三条
        awaitCond("unsub old three") { ws.unsubFrames().size >= 3 }
        awaitCond("second subs") { ws.subFrames().size >= 6 }

        val oldIds = ws.subFrames().take(3).mapNotNull { it.s("id") }.toSet()
        val unsubIds = ws.unsubFrames().mapNotNull { it.s("id") }.toSet()
        assertEquals(oldIds, unsubIds)
    }

    // ---- 退房退订 ----

    @Test
    fun `unsubscribeRoom sends unsubs and second call is a no-op`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()

        manager.subscribeRoom("rid-1")
        awaitCond("subs") { ws.subFrames().size >= 3 }

        manager.unsubscribeRoom("rid-1")
        awaitCond("three unsubs") { ws.unsubFrames().size >= 3 }
        val count = ws.unsubFrames().size
        manager.unsubscribeRoom("rid-1") // 未订阅 → no-op，不再发帧
        delay(100)
        assertEquals(count, ws.unsubFrames().size)
    }

    // ---- 消息：rid 过滤 → 落库 → 发射 ----

    @Test
    fun `room message is persisted and rid emitted, other rid filtered out`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()
        manager.subscribeRoom("rid-1")
        awaitCond("subs") { ws.subFrames().size >= 3 }

        val emissions = CopyOnWriteArrayList<String>()
        val subscribed = CompletableDeferred<Unit>()
        // SharedFlow 无 replay：先等本订阅生效再发帧（防 collect 晚于 ws.send 的竞态）
        val job = launch {
            manager.incomingMessages
                .onSubscription { subscribed.complete(Unit) }
                .collect { emissions.add(it) }
        }
        subscribed.await()

        ws.send(streamMessageFrame("rid-1", "m1", "hello"))
        ws.send(streamMessageFrame("rid-2", "m2", "other room"))
        awaitCond("persisted") { persisted.isNotEmpty() }
        awaitCond("rid emitted") { emissions.isNotEmpty() }
        job.cancel()

        assertEquals(1, persisted.size)
        assertEquals("rid-1", persisted[0].second)
        assertEquals("m1", persisted[0].first.s("_id"))
        assertEquals("hello", persisted[0].first.s("msg"))
        assertEquals(listOf("rid-1"), emissions)
    }

    @Test
    fun `message without args is ignored`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()
        manager.subscribeRoom("rid-1")
        awaitCond("subs") { ws.subFrames().size >= 3 }

        ws.send("""{"msg":"changed","collection":"stream-room-messages","fields":{"eventName":"rid-1"}}""")
        delay(200)
        assertEquals(0, persisted.size)
    }

    // ---- 重连后重订全部活跃流 ----

    @Test
    fun `resubscribeAllActiveRoomStreams re-subscribes active rids after transport drop`() = runBlocking {
        val s1 = RoomWsServer().also { wsListeners.add(it) }
        val s2 = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()
        manager.subscribeRoom("rid-a")
        manager.subscribeRoom("rid-b")
        awaitCond("subs on first connection") { s1.subFrames().size >= 6 }

        // MockWebServer 5.3 服务端不能主动发 close：客户端强杀（RealtimeSessionManagerTest 同口径）
        sdk.ddp!!.cancelTransport()

        // 传输断开不清洗活跃表（那是 teardown 挂点的职责）；重连收尾后重订
        sdk.connect()
        assertTrue(sdk.ddp!!.isTransportOpen())
        manager.resubscribeAllActiveRoomStreams()
        awaitCond("six subs on new connection") { s2.subFrames().size >= 6 }
        assertEquals(
            listOf(
                "rid-a",
                "rid-a/deleteMessage",
                "rid-a/user-activity",
                "rid-b",
                "rid-b/deleteMessage",
                "rid-b/user-activity",
            ),
            s2.subEvents(),
        )
    }

    @Test
    fun `teardown hook clears active set so resubscribe does not resurrect rooms`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()
        manager.subscribeRoom("rid-a")
        awaitCond("subs") { ws.subFrames().size >= 3 }

        manager.onSessionTornDown()
        val before = ws.subFrames().size
        manager.resubscribeAllActiveRoomStreams() // 活跃表已空 → 无新订阅
        delay(200)
        assertEquals(before, ws.subFrames().size)
    }

    // ---- parse 助手边界 ----

    @Test
    fun `parse helpers handle malformed frames`() {
        assertNull(RoomStreamManager.parseNotifyRoomRid(testJson.parseToJsonElement("{}")))
        assertEquals(
            "rid-1",
            RoomStreamManager.parseNotifyRoomRid(
                testJson.parseToJsonElement("""{"fields":{"eventName":"rid-1/user-activity"}}"""),
            ),
        )
        // RN :44-45 args 非数组容错：args 本身作为消息对象
        val raw = RoomStreamManager.parseStreamRoomMessageRaw(
            testJson.parseToJsonElement("""{"fields":{"args":{"_id":"m","rid":"r"}}}"""),
        )
        assertEquals("r", raw?.let { RoomStreamManager.roomRidOf(it) })
        // rid 缺失 → null（被过滤）
        val noRid = RoomStreamManager.parseStreamRoomMessageRaw(
            testJson.parseToJsonElement("""{"fields":{"args":[{"_id":"m"}]}}"""),
        )
        assertNull(noRid?.let { RoomStreamManager.roomRidOf(it) })
    }

    // ---- manager 挂点序列联调（teardown → hook 清表；finalize tail → 重订）----

    @Test
    fun `reconnect tail resubscribes active rooms after teardown cleared and new subscribe`() = runBlocking {
        val s1 = RoomWsServer().also { wsListeners.add(it) }
        val s2 = RoomWsServer().also { wsListeners.add(it) }

        val tails = AtomicInteger(0)
        val hooks = AtomicInteger(0)
        val tail: suspend () -> Unit = {
            tails.incrementAndGet()
            manager.resubscribeAllActiveRoomStreams()
        }
        val hook = {
            hooks.incrementAndGet()
            manager.onSessionTornDown()
        }

        sdk.connect()
        manager.subscribeRoom("rid-a")
        awaitCond("subs") { s1.subFrames().size >= 3 }

        hook() // teardown 清活跃表：tail 不复活
        assertEquals(1, hooks.get())
        val subsBefore = s1.subFrames().size
        runCatching { tail() } // 活跃表空 → 无订阅动作
        delay(100)
        assertEquals(subsBefore, s1.subFrames().size)

        // 重新进房后断线重连，tail 在新连接上重订
        manager.subscribeRoom("rid-a")
        awaitCond("re-subscribed") { s1.subFrames().size >= 6 }
        sdk.ddp!!.cancelTransport()
        sdk.connect()
        launch { tail() }
        awaitCond("re-subscribed on new connection") { s2.subFrames().size >= 3 }
        assertEquals(2, tails.get())
    }

    /** 流消息帧（RN fields.args[0] 形态）。 */
    private fun streamMessageFrame(rid: String, id: String, msg: String): String =
        """{"msg":"changed","collection":"stream-room-messages","id":"evt-$id",""" +
            """"fields":{"eventName":"$rid","args":[{"_id":"$id","rid":"$rid","msg":"$msg","ts":1690000000}]}}"""
}

/**
 * 脚本化 DDP 服务端：connect→connected、ping→pong、sub→ready、unsub→nosub（T2 测试坑同口径：
 * connected 必须等服务端收到 connect 帧后再回）。
 */
private class RoomWsServer : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val f = parse(text) ?: return
        val ws = wsRef.get() ?: return
        when (f.s("msg")) {
            "connect" -> ws.send("""{"msg":"connected","session":"s"}""")
            "ping" -> ws.send("""{"msg":"pong"}""")
            "sub" -> ws.send("""{"msg":"ready","subs":["${f.s("id")}"]}""")
            "unsub" -> ws.send("""{"msg":"nosub","id":"${f.s("id")}"}""")
        }
    }

    fun send(text: String) {
        wsRef.get()?.send(text)
    }
}
