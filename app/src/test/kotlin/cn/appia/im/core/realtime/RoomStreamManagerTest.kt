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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
            // M4-T10 RN :82-84：hint 记录缝（生产 DI 接 RoomAccessLoss，此处同款直连断言链路）
            recordAccessHint = { raw, username ->
                cn.appia.im.domain.session.RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw, username)
            },
            currentUsernameProvider = { "bob" },
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

    // ---- M4-T10：stream-room-messages → 访问丢失 hint 记录（RN roomStreams.ts:82-84）----

    @Test
    fun `ul and ru-me frames record access loss hints`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()
        manager.subscribeRoom("rid-1")
        awaitCond("subs") { ws.subFrames().size >= 3 }
        cn.appia.im.domain.session.RoomAccessLoss.clearHints()
        try {
            // ul（有人退房）先到——hint 后写胜：ru msg==bob（我被移出）覆盖 → KICKED（RN hints.set 同键覆盖）
            ws.send(
                """{"msg":"changed","collection":"stream-room-messages","id":"evt-h1",""" +
                    """"fields":{"eventName":"rid-1","args":[{"_id":"h1","rid":"rid-1","t":"ul"}]}}""",
            )
            ws.send(
                """{"msg":"changed","collection":"stream-room-messages","id":"evt-h2",""" +
                    """"fields":{"eventName":"rid-1","args":[{"_id":"h2","rid":"rid-1","t":"ru","msg":"bob"}]}}""",
            )
            awaitCond("ru hint recorded") {
                // infer 消费即删：仅一次判定机会（第二次起 UNKNOWN）——失败重试用 KICKED 判等
                cn.appia.im.domain.session.RoomAccessLoss.inferReason("rid-1") ==
                    cn.appia.im.domain.session.RoomAccessLoss.Reason.KICKED
            }
        } finally {
            cn.appia.im.domain.session.RoomAccessLoss.clearHints()
        }
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
        // RN :80-82 持久化提取无非数组回退：裸 args 对象即丢弃（评审 Important 修复回归钉）
        val nonArray = RoomStreamManager.parseStreamRoomMessageRaw(
            testJson.parseToJsonElement("""{"fields":{"args":{"_id":"m","rid":"r"}}}"""),
        )
        assertNull(nonArray)
        // 数组 args：取首元素；rid 缺失 → null（被过滤）
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

    // ---- teardown 与 sub/unsub 互斥（总纲 §4.3-4 陈旧条目复活竞态）----

    /**
     * teardown 落在 subscribeRoom「已订网、未落表」窗口内时必须等待 opMutex：
     * 无互斥则 teardown 清的是空表，subscribe 随后落表复活旧会话条目，重连收尾即重订已拆会话的流。
     * 服务器扣住 ready 不回 → subscribeRoom 悬在锁内；放行后 subscribe 落表、teardown 才清表。
     */
    @Test
    fun `teardown waits for in-flight subscribe so the landed entry is cleared`() = runBlocking {
        val holding = HoldingWsServer().also { wsListeners.add(it) }
        sdk.connect() // 本测试的连接升级到 holding：sub 的 ready 被扣住

        val subscribeJob = launch(Dispatchers.IO) { runCatching { manager.subscribeRoom("rid-x") } }
        awaitCond("three sub frames sent and subscribe still held") {
            holding.frames.count { parse(it)?.s("msg") == "sub" } >= 3 && !subscribeJob.isCompleted
        }

        val teardownDone = AtomicBoolean(false)
        val teardownJob = launch(Dispatchers.IO) { manager.onSessionTornDown(); teardownDone.set(true) }
        delay(300) // 给 teardown 跑出 bug 的机会：持锁窗口内它必须仍在等待
        assertFalse("teardown must block while subscribe holds opMutex", teardownDone.get())

        holding.releaseHeld() // 放行 ready → subscribe 落表完成并释放锁
        awaitCond("teardown runs after lock released") { teardownDone.get() }
        awaitCond("subscribe settled") { subscribeJob.isCompleted }

        // 锁序保证：subscribe 落表先于 teardown 清表 → 活跃表空，重订不得复活
        val subsBefore = holding.frames.count { parse(it)?.s("msg") == "sub" }
        manager.resubscribeAllActiveRoomStreams()
        delay(200)
        assertEquals(subsBefore, holding.frames.count { parse(it)?.s("msg") == "sub" })
    }

    /**
     * M3 终审 T1（主线程 ANR 上界）：锁被在途 subscribe 长期持有时，teardown 1s 超时**返回**
     * 而非无界阻塞——登出主线程不被 DDP 25s 订阅超时窗拖至 ANR。清场跳过由随后的
     * disconnect 服务端全量退订兜底（终态一致）。
     */
    @Test
    fun `teardown returns within timeout when lock is held by in-flight subscribe`() = runBlocking {
        val holding = HoldingWsServer().also { wsListeners.add(it) }
        sdk.connect() // sub 的 ready 被扣住 → subscribeRoom 悬在 opMutex 锁内

        val subscribeJob = launch(Dispatchers.IO) { runCatching { manager.subscribeRoom("rid-x") } }
        awaitCond("subscribe holds opMutex") {
            holding.frames.count { parse(it)?.s("msg") == "sub" } >= 3 && !subscribeJob.isCompleted
        }

        val start = System.nanoTime()
        manager.onSessionTornDown() // 无主线程可用（测试直调）：必须 1s 上界内返回
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("teardown must return within ${RoomStreamManager.TEARDOWN_LOCK_TIMEOUT_MS}ms, took ${elapsedMs}ms",
            elapsedMs < RoomStreamManager.TEARDOWN_LOCK_TIMEOUT_MS + 2_000)
        assertTrue(!subscribeJob.isCompleted) // 锁仍在 subscribe 手里（teardown 未插队）

        holding.releaseHeld()
        awaitCond("subscribe settled after release") { subscribeJob.isCompleted }
    }

    // ---- 同房快速退/进：sub/unsub 串行化（M2-T11 评审 Minor-1）----
    @Test
    fun `interleaved subscribe and unsubscribe serialize and stay balanced`() = runBlocking {
        val ws = RoomWsServer().also { wsListeners.add(it) }
        sdk.connect()

        // 多线程并发轰炸同一 rid 的 sub/unsub：互斥下每个操作原子，帧数收支必须平衡
        //（任何交错穿插都会留下「sub 帧无对应 unsub 帧」的悬挂活跃流，收尾清场后无法归零）
        val jobs = ArrayList<kotlinx.coroutines.Job>()
        repeat(20) {
            jobs += launch(Dispatchers.IO) { runCatching { manager.subscribeRoom("rid-1") } }
            jobs += launch(Dispatchers.IO) { runCatching { manager.unsubscribeRoom("rid-1") } }
        }
        jobs.forEach { it.join() }
        manager.unsubscribeRoom("rid-1") // 收尾清场：一次 unsub 必须能清掉全部活跃订阅
        awaitCond("sub/unsub frames balanced after cleanup") {
            ws.subFrames().size >= 3 && ws.subFrames().size == ws.unsubFrames().size
        }
        val count = ws.unsubFrames().size
        delay(100)
        manager.unsubscribeRoom("rid-1") // 活跃表已空 → no-op 不再发帧
        assertEquals(count, ws.unsubFrames().size)
    }

    /** 流消息帧（RN fields.args[0] 形态）。 */
    private fun streamMessageFrame(rid: String, id: String, msg: String): String =
        """{"msg":"changed","collection":"stream-room-messages","id":"evt-$id",""" +
            """"fields":{"eventName":"$rid","args":[{"_id":"$id","rid":"$rid","msg":"$msg","ts":1690000000}]}}"""
}

/**
 * 脚本化 DDP 服务端：connect→connected、ping→pong、sub→ready、unsub→nosub（T2 测试坑同口径：
 * connected 必须等服务端收到 connect 帧后再回）。子类覆写 [onFrame] 可扣帧（HoldingWsServer）。
 */
private open class RoomWsServer : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val f = parse(text) ?: return
        val ws = wsRef.get() ?: return
        onFrame(f, ws)
    }

    protected open fun onFrame(f: JsonObject, ws: WebSocket) {
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

/** 扣住 sub 的 ready 的服务端：钉 subscribeRoom 在 opMutex 锁内（teardown 互斥测试用）。 */
private class HoldingWsServer : RoomWsServer() {
    private val heldSubIds = CopyOnWriteArrayList<String>()

    override fun onFrame(f: JsonObject, ws: WebSocket) {
        if (f.s("msg") == "sub") {
            heldSubIds.add(f.s("id")!!)
            return // 扣住 ready：subscribe 停在锁内
        }
        super.onFrame(f, ws)
    }

    /** 放行全部扣住的 sub（逐 id 回 ready）。 */
    fun releaseHeld() {
        heldSubIds.forEach { id -> send("""{"msg":"ready","subs":["$id"]}""") }
    }
}
