package cn.appia.im.core.network.ddp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// ==== 测试辅助：脚本化 WS 服务端 ====

/**
 * 服务端 WS 脚本：记录客户端发来的帧、按测试脚本回帧。
 * 对照简报的 EchoWsListener 要求（记录收帧、按脚本回 connected/ping/ready/nosub/stream 事件）。
 */
private open class ScriptedWsServer : WebSocketListener() {
    val received = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)
    private val closeCodeRef = AtomicReference<Int?>(null)

    /** 服务端看到的客户端关闭码（disconnect(4000) 断言用）。 */
    val clientCloseCode: Int? get() = closeCodeRef.get()

    fun send(text: String) {
        wsRef.get()?.send(text)
    }

    fun clearFrames() = received.clear()

    final override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
        onOpened(webSocket)
    }

    final override fun onMessage(webSocket: WebSocket, text: String) {
        received.add(text)
        onFrame(text, webSocket)
    }

    final override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closeCodeRef.compareAndSet(null, code)
    }

    final override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        closeCodeRef.compareAndSet(null, code)
    }

    final override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit

    open fun onOpened(webSocket: WebSocket) = Unit

    open fun onFrame(text: String, webSocket: WebSocket) = Unit

    /** 轮询等待满足条件的帧（真实时间，超时失败并打印已收帧）。 */
    fun awaitFrame(desc: String, timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            for (raw in received) {
                val obj = runCatching { ddpJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                if (predicate(obj)) return obj
            }
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for frame [$desc], received=$received")
    }
}

private fun parse(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

/** 标准回帧脚本：connect→connected、ping→pong、sub→ready、unsub→nosub；method/sub/unsub 可覆写。 */
private open class DdpServer : ScriptedWsServer() {
    override fun onFrame(text: String, webSocket: WebSocket) {
        when (parse(text)?.str("msg")) {
            "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
            "ping" -> webSocket.send("""{"msg":"pong"}""")
            "method" -> onMethod(parse(text)!!, webSocket)
            "sub" -> onSub(parse(text)!!, webSocket)
            "unsub" -> onUnsub(parse(text)!!, webSocket)
        }
    }

    open fun onMethod(obj: JsonObject, webSocket: WebSocket) = Unit

    open fun onSub(obj: JsonObject, webSocket: WebSocket) {
        val id = obj.str("id") ?: return
        webSocket.send("""{"msg":"ready","subs":["$id"]}""")
    }

    open fun onUnsub(obj: JsonObject, webSocket: WebSocket) {
        val id = obj.str("id") ?: return
        webSocket.send("""{"msg":"nosub","id":"$id"}""")
    }
}

/** 只回 connected，忽略一切其它消息（ping 无 pong / method / sub 无响应）。 */
private open class SilentServer : ScriptedWsServer() {
    override fun onFrame(text: String, webSocket: WebSocket) {
        if (parse(text)?.str("msg") == "connect") webSocket.send("""{"msg":"connected","session":"s"}""")
    }
}

/** sub 照常 ready，unsub 静默（验证 unsubscribe 超时 resolve）。 */
private class SilentUnsubServer : DdpServer() {
    override fun onUnsub(obj: JsonObject, webSocket: WebSocket) = Unit
}

/** method 回 result（登录结果形状）。 */
private open class MethodResultServer : DdpServer() {
    override fun onMethod(obj: JsonObject, webSocket: WebSocket) {
        val id = obj.str("id")
        webSocket.send("""{"msg":"result","id":"$id","result":{"id":"user-42","token":"tok-1","createCipher":{"${'$'}date":1690000000000}}}""")
    }
}

/** method 回 error。 */
private class MethodErrorServer : DdpServer() {
    override fun onMethod(obj: JsonObject, webSocket: WebSocket) {
        webSocket.send("""{"msg":"result","id":"${obj.str("id")}","error":{"error":"invalid-token","reason":"unauthorized"}}""")
    }
}

/** sub 回 nosub。 */
private class NosubServer(private val withError: Boolean) : DdpServer() {
    override fun onSub(obj: JsonObject, webSocket: WebSocket) {
        val id = obj.str("id") ?: return
        webSocket.send(
            if (withError) """{"msg":"nosub","id":"$id","error":{"error":"not-allowed","reason":"no"}}"""
            else """{"msg":"nosub","id":"$id"}""",
        )
    }
}

/**
 * DDP 客户端传输层集成测试（MockWebServer WebSocket，纯 JVM）。
 * 逐条对照 must-preserve 语义 1-14 与 ddpClient.ts 源行号。
 */
class DdpClientTest {
    private val server = MockWebServer()
    private val clients = CopyOnWriteArrayList<DdpClient>()

    @AfterEach
    fun tearDown() {
        // MockWebServer 5.3 的服务端 WS 一旦收到 close 帧即永久卡死（不下发 close、cancel NPE），
        // 这类连接会让 shutdown 等待超时。先停客户端循环、强杀全部 socket，再容忍 shutdown 失败。
        clients.forEach {
            runCatching { it.disconnect() }
            runCatching { it.cancelTransport() }
        }
        clients.clear()
        // 收到过 close 帧的连接会让 shutdown 抛 "Gave up waiting"（MockWebServer 5.3 缺陷），容忍之
        runCatching { server.shutdown() }
    }

    private fun startServer(vararg listeners: ScriptedWsServer): String {
        server.start()
        listeners.forEach {
            server.enqueue(MockResponse().withWebSocketUpgrade(it))
        }
        return server.url("/websocket").toString()
    }

    private fun testClient(
        host: String,
        reopenMs: Long = 200,
        pingMs: Long = 20_000,
        connectTimeoutMs: Long = 2_000,
        responseTimeoutMs: Long = 2_000,
    ): DdpClient {
        val client = DdpClient(
            // 语义 1 靠 hostToWs：测试 URL 是 http:// → ws://
            DdpOptions(
                host = host,
                reopenMs = reopenMs,
                pingMs = pingMs,
                connectTimeoutMs = connectTimeoutMs,
                responseTimeoutMs = responseTimeoutMs,
            ),
            OkHttpClient(),
        )
        clients.add(client)
        return client
    }

    private fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for: $desc")
    }

    // ---- 语义 2：连接握手 ----

    @Test
    fun `connect sends DDP connect with version support and no id`() {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        runBlocking { client.connect() }

        val frame = srv.awaitFrame("connect") { it.str("msg") == "connect" }
        assertEquals("1", frame.str("version"))
        assertEquals(listOf("1", "pre2", "pre1"), frame["support"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(frame.containsKey("id"), "semantics 6: connect frame carries no id")
        assertTrue(client.isTransportOpen())
    }

    // 语义 16/补充：握手失败（服务端拒绝升级）

    @Test
    fun `connect rejects when server refuses websocket upgrade`() {
        server.start()
        server.enqueue(MockResponse().setResponseCode(500))
        val client = testClient(server.url("/websocket").toString())
        val ex = assertThrows<DdpException> { runBlocking { client.connect() } }
        assertTrue(ex.message!!.contains("connection failed"))
        assertFalse(client.isTransportOpen())
    }

    // 语义 2：建连+握手超时（注入 connectTimeoutMs=400ms，对齐 TS [ddp] connection timeout）

    @Test
    fun `connect times out when server never completes handshake`() {
        val socket = ServerSocket(0)
        thread(isDaemon = true) { runCatching { socket.accept() } } // 接受 TCP 但不做 WS 握手
        try {
            val client = DdpClient(DdpOptions(host = "http://127.0.0.1:${socket.localPort}", connectTimeoutMs = 400))
            clients.add(client)
            val ex = assertThrows<DdpException> { runBlocking { client.connect() } }
            assertEquals("[ddp] connection timeout", ex.message)
        } finally {
            socket.close()
        }
    }

    // ---- 语义 4：自动应答 ping ----

    @Test
    fun `auto replies pong without id to server ping`() {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        runBlocking { client.connect() }

        srv.clearFrames()
        srv.send("""{"msg":"ping"}""")
        val pong = srv.awaitFrame("pong") { it.str("msg") == "pong" }
        assertFalse(pong.containsKey("id"), "semantics 4/6: auto pong carries no id")
    }

    // ---- 语义 3：心跳 —— 收到 pong 后才排下一轮 ----

    @Test
    fun `heartbeat schedules next ping only after pong`() {
        val srv = DdpServer()
        val client = testClient(startServer(srv), pingMs = 250)
        runBlocking { client.connect() }
        // 若不等 pong 就不会出现第二轮 ping，这里超时即失败
        awaitCond("second ping after pong") { srv.received.count { parse(it)?.str("msg") == "ping" } >= 2 }
    }

    // ---- 语义 3：ping 发送失败（等 pong 超时）→ disconnect + tryReopen ----

    @Test
    fun `ping without pong drops transport and reopens`() {
        val srv = SilentServer()
        // 重连需要第二个升级响应
        val client = testClient(startServer(srv, SilentServer()), pingMs = 200, responseTimeoutMs = 400)
        runBlocking { client.connect() }
        awaitCond("reconnect after ping timeout") { server.requestCount >= 2 }
        awaitCond("transport open again") { client.isTransportOpen() }
    }

    // ---- 语义 9：subscribe ready ack + 流事件三路分发（collection 路）----

    @Test
    fun `subscribe resolves on ready sends sub frame and emits stream events`() = runBlocking {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        client.connect()

        val sub = client.subscribe("stream-notify-user", "uid1/subscriptions-changed")
        assertEquals("sub-1", sub.id, "semantics 6: connect already burned ddp-0, first sub is sub-1")

        val subFrame = srv.awaitFrame("sub") { it.str("msg") == "sub" }
        assertEquals("stream-notify-user", subFrame.str("name"))
        assertEquals("uid1/subscriptions-changed", subFrame["params"]!!.jsonArray[0].jsonPrimitive.content)
        val paramObj = subFrame["params"]!!.jsonArray[1].jsonObject
        assertEquals(false, paramObj["useCollection"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(0, paramObj["args"]!!.jsonArray.size)

        val got = AtomicReference<JsonElement?>(null)
        val disposable = client.onStreamData("stream-notify-user") { got.set(it) }
        try {
            srv.send("""{"msg":"changed","collection":"stream-notify-user","id":"e1","fields":{}}""")
            awaitCond("stream event delivered") { got.get() != null }
            assertEquals("e1", (got.get() as JsonObject).str("id"))
        } finally {
            disposable.stop()
        }
    }

    @Test
    fun `stream event reaches msg and id routes too`() = runBlocking {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        client.connect()

        // 语义 11：同一 handler 注册同名 key 时，msg 路与 collection 路各投递一次
        val msgCount = java.util.concurrent.atomic.AtomicInteger(0)
        val idPayload = AtomicReference<JsonElement?>(null)
        val d1 = client.onStreamData("stream-x") { msgCount.incrementAndGet() }
        val d2 = client.onStreamData("evt-9") { idPayload.set(it) }
        try {
            srv.send("""{"msg":"stream-x","collection":"stream-x","id":"evt-9"}""")
            awaitCond("id route delivered") { idPayload.get() != null }
            assertEquals(2, msgCount.get(), "same-name msg+collection route twice, total 2")
        } finally {
            d1.stop()
            d2.stop()
        }
    }

    // ---- 语义 15（补充）：onStreamData 返回 Disposable，stop 后不再收到 ----

    @Test
    fun `onStreamData disposable stop removes handler`() = runBlocking {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        client.connect()
        val got = AtomicReference<JsonElement?>(null)
        val d = client.onStreamData("stream-x") { got.set(it) }
        d.stop()
        srv.send("""{"msg":"changed","collection":"stream-x","id":"e"}""")
        Thread.sleep(300)
        assertNull(got.get())
    }

    // ---- 语义 9：nosub 拒绝订阅 ----

    @Test
    fun `nosub rejects subscribe with server error`() {
        val srv = NosubServer(withError = true)
        val client = testClient(startServer(srv))
        val ex = assertThrows<DdpException> { runBlocking { client.subscribe("stream-notify-user", "uid1/x") } }
        assertTrue(ex.message!!.contains("not-allowed"))
    }

    @Test
    fun `plain nosub rejects subscribe`() {
        val srv = NosubServer(withError = false)
        val client = testClient(startServer(srv))
        val ex = assertThrows<DdpException> { runBlocking { client.subscribe("stream-notify-user", "uid1/x") } }
        assertTrue(ex.message!!.contains("subscription rejected"))
    }

    // ---- 语义 9：订阅 ack 超时（25s 硬编码，测试注入 300ms）----

    @Test
    fun `subscribe times out when neither ready nor nosub arrives`() {
        val srv = SilentServer()
        val client = testClient(startServer(srv))
        client.subscribeTimeoutMs = 300
        val ex = assertThrows<DdpException> { runBlocking { client.subscribe("t", "e") } }
        assertTrue(ex.message!!.contains("subscribe timeout"))
    }

    // ---- 语义 8：loginWithResume ----

    @Test
    fun `loginWithResume stores userId and returns login result`() = runBlocking {
        val srv = MethodResultServer()
        val client = testClient(startServer(srv))
        val result = client.loginWithResume("tok-resume")

        assertEquals("user-42", result.id)
        assertEquals("tok-1", result.token)
        assertEquals(1690000000000L, result.createCipherDate)
        assertEquals("user-42", client.userId, "semantics 8: userId comes from response .id")

        val loginFrame = srv.awaitFrame("login method") { it.str("method") == "login" }
        assertEquals("login", loginFrame.str("method"))
        assertEquals("tok-resume", loginFrame["params"]!!.jsonArray[0].jsonObject.str("resume"))
        // 语义 6 + TS:304：connect 消息虽不带 id，但序列号已被消耗（ddp-0），首个 method 为 ddp-1
        assertEquals("ddp-1", loginFrame.str("id"))
    }

    // ---- 语义 6：method/sub 共用一个自增序列 ----

    @Test
    fun `method and sub ids share one increasing sequence`() = runBlocking {
        val srv = MethodResultServer()
        val client = testClient(startServer(srv))
        client.connect()
        client.callMethod("spotlight", JsonPrimitive("q"))
        val sub = client.subscribe("room", "e1")
        assertEquals("sub-2", sub.id)
        val mFrame = srv.awaitFrame("spotlight method") { it.str("method") == "spotlight" }
        assertEquals("ddp-1", mFrame.str("id"))
    }

    // ---- 语义 7：callMethod 响应取 result；带 error 则 reject ----

    @Test
    fun `callMethod returns result field`() = runBlocking {
        val srv = MethodResultServer()
        val client = testClient(startServer(srv))
        val r = client.callMethod("spotlight", JsonPrimitive("q"))
        assertEquals("user-42", (r as JsonObject).str("id"))
    }

    @Test
    fun `method response with error rejects`() {
        val srv = MethodErrorServer()
        val client = testClient(startServer(srv))
        val ex = assertThrows<DdpException> {
            runBlocking { client.callMethod("login", kotlinx.serialization.json.buildJsonObject { put("resume", "x") }) }
        }
        assertTrue(ex.message!!.contains("invalid-token"))
    }

    // ---- 语义 5：responseTimeoutMs 超时 ----

    @Test
    fun `method response timeout rejects`() {
        val srv = SilentServer()
        val client = testClient(startServer(srv), responseTimeoutMs = 300)
        val ex = assertThrows<DdpException> { runBlocking { client.callMethod("noop") } }
        assertTrue(ex.message!!.contains("response timeout"))
    }

    // ---- 语义 10：unsubscribe 等 nosub；15s 超时后 resolve 而非 reject ----

    @Test
    fun `unsubscribe resolves when nosub received`() = runBlocking {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        val sub = client.subscribe("room", "e1")
        val res = sub.unsubscribe()
        val unsubFrame = srv.awaitFrame("unsub") { it.str("msg") == "unsub" }
        assertEquals(sub.id, unsubFrame.str("id"))
        assertEquals(sub.id, (res as JsonObject).str("id"))
    }

    @Test
    fun `unsubscribe resolves instead of rejecting on timeout`() = runBlocking {
        val srv = SilentUnsubServer()
        val client = testClient(startServer(srv))
        client.unsubscribeTimeoutMs = 300
        val sub = client.subscribe("room", "e1")
        assertNull(sub.unsubscribe(), "timeout must resolve(null) not reject (TS ddpClient.ts:502-505)")
    }

    // ---- 语义 12：断线重连（清 userId、reopenMs 后重连、风暴防护）----

    @Test
    fun `server close clears userId and reopens once after reopenMs`() {
        val first = MethodResultServer()
        val second = DdpServer()
        val client = testClient(startServer(first, second), reopenMs = 200)
        runBlocking { client.loginWithResume("tok") }
        assertEquals("user-42", client.userId)

        // MockWebServer 5.3 服务端发不出 close 帧也无法 cancel（NPE），
        // 断线改为从客户端强杀 socket：客户端同样走 onFailure → 清 userId → reopen
        client.cancelTransport()
        awaitCond("userId cleared on drop") { client.userId == null }
        awaitCond("reconnected") { client.isTransportOpen() }
        awaitCond("second connect frame") { second.received.any { parse(it)?.str("msg") == "connect" } }

        // 风暴防护：reopenTimer 已存在则不重复排 —— 一个 reopen 周期后仍只有 2 条连接
        Thread.sleep(800)
        assertEquals(2, server.requestCount, "reopen storm guard: exactly one reconnect")
    }

    // ---- 语义 12：connect 并发调用合并（connectInflight）----

    @Test
    fun `concurrent connect calls merge into one handshake`() = runBlocking {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        val jobs = listOf(
            async { client.connect() },
            async { client.connect() },
            async { client.connect() },
        )
        jobs.awaitAll()
        assertEquals(1, server.requestCount, "concurrent connects merge into one connection")
        assertTrue(client.isTransportOpen())
    }

    @Test
    fun `connect returns immediately when already open`() = runBlocking {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        client.connect()
        client.connect()
        assertEquals(1, server.requestCount)
    }

    // ---- 语义 12：checkAndReopen 未连接时 fire-and-forget ----

    @Test
    fun `checkAndReopen connects when transport closed`() {
        val srv = DdpServer()
        val client = testClient(startServer(srv))
        client.checkAndReopen()
        awaitCond("connected via checkAndReopen") { client.isTransportOpen() }
    }

    // ---- 语义 13：disconnect —— close 4000，清 userId，不再自动重连 ----

    @Test
    fun `disconnect closes with 4000 and does not reopen`() {
        val srv = DdpServer()
        val client = testClient(startServer(srv), reopenMs = 150)
        runBlocking { client.connect() }
        client.disconnect()

        awaitCond("server saw close code 4000") { srv.clientCloseCode == 4000 }
        assertFalse(client.isTransportOpen())
        assertNull(client.userId)
        Thread.sleep(600)
        assertEquals(1, server.requestCount, "semantics 13: no auto reopen after disconnect")
    }

    // ---- 语义 5/补充：断线使 pending 响应快速失败（不等 responseTimeout）----

    @Test
    fun `pending method call fails fast when transport drops`() {
        val srv = DdpServer()
        val client = testClient(startServer(srv), responseTimeoutMs = 5_000)
        runBlocking { client.connect() }

        val err = AtomicReference<Throwable?>(null)
        val t0 = System.currentTimeMillis()
        val job = thread {
            runBlocking { runCatching { client.callMethod("slow") }.onFailure { err.set(it) } }
        }
        Thread.sleep(200)
        client.cancelTransport()
        job.join(3_000)

        val e = err.get()
        assertNotNull(e, "dropped transport must fail pending fast")
        assertTrue(e is DdpException)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue(elapsed < 3_000, "must fail before responseTimeout(5s), took ${elapsed}ms")
        assertTrue(e!!.message!!.contains("connection closed"))
    }
}
