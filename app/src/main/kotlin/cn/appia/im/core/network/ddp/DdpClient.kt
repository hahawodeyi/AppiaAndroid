package cn.appia.im.core.network.ddp

import cn.appia.im.core.network.RocketHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.coroutineContext

private fun textOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

/** 内部 scope 兜底（评审 Important-3）：fire-and-forget 协程（建队/ping/reopen/pong）抛非取消异常只落日志。 */
private val ddpScopeHandler = CoroutineExceptionHandler { _, e ->
    if (e is CancellationException) throw e
    android.util.Log.w("ddp", "uncaught coroutine failure in DdpClient scope", e)
}

/**
 * Minimal DDP/WebSocket client for Rocket.Chat streams.
 * 逐行为移植 appiaMobile/src/services/realtime/ddpClient.ts（536 行），
 * 仅覆盖应用所需：connect + login/resume + subscribe + onStreamData。
 *
 * JS 单线程语义到协程的映射：
 * - Emitter(on/off/once) → 回调注册表（ConcurrentHashMap + CopyOnWriteArraySet）
 * - Promise/once 监听 → CompletableDeferred + 注册即挂监听
 * - setTimeout 定时器 → scope 协程 delay
 * - connectInflight → CompletableDeferred 复用（并发 connect 合并）
 */
class DdpClient(
    private val options: DdpOptions,
    private val client: OkHttpClient = RocketHttp.client, // 进程级共享连接池/调度器
    private val eventListener: DdpEventListener? = null,
) {
    @Volatile
    var userId: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ddpScopeHandler)

    // ---- 事件注册表（TS Emitter ddpClient.ts:3-32）----
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<(JsonElement) -> Unit>>()

    // ---- 连接状态（TS ddpClient.ts:77-84）----
    @Volatile
    private var socket: WebSocket? = null

    private var reopenJob: Job? = null
    private var pingJob: Job? = null
    private val seq = AtomicInteger(0) // TS sentSeq

    @Volatile
    private var connected = false

    @Volatile
    private var lastPing = 0L // TS 同名字段仅记录、未参与逻辑（ddpClient.ts:82）
    private var connectInflight: CompletableDeferred<Unit>? = null

    /** 建队互斥：非 suspend 的 disconnect/close 也要与 connect 的建队段互斥，故用 ReentrantLock。 */
    private val connectLock = ReentrantLock()
    private val reopenLock = Any()

    /**
     * 连接代次：disconnect/close 时自增，作废在途 connect 的握手回调——
     * 断开落在建连中途时，随后完成的 onOpen/握手被整体丢弃（原生版加固，TS 无此保护）。
     */
    private val generation = AtomicLong(0)

    /** close() 后的终态：不可再用（connect 快速失败），disconnect 可重连、close 不行。 */
    @Volatile
    private var closed = false

    /** 当前活跃连接；finishReject/detach 的等价物是把它置 null（过期 socket 回调全部忽略）。 */
    @Volatile
    private var active: Connection? = null

    /** 全部在途物理 socket（disconnect 置空 socket 后仍可强杀；连接结束即在 gone/fail 摘除）。 */
    private val allWs = CopyOnWriteArrayList<WebSocket>()

    /** 存活物理连接数（测试观测 allWs 摘除用）。 */
    internal val liveTransportCount: Int get() = allWs.size

    /** 是否有在途 connect（测试观测 disconnect 竞态用）。 */
    internal fun isConnecting(): Boolean = connectLock.withLock { connectInflight != null }

    /** TS 硬编码 25s（ddpClient.ts:388）；internal 以便测试注入短超时。 */
    internal var subscribeTimeoutMs = 25_000L

    /** TS 硬编码 15s（ddpClient.ts:501）；internal 以便测试注入短超时。 */
    internal var unsubscribeTimeoutMs = 15_000L

    /** pending 响应表：TS 靠 emitter once + timeout 自然过期；此处登记以便断线时快速失败（补全 deferred）。 */
    private class Pending(
        val event: String,
        val wrapper: (JsonElement) -> Unit,
        val deferred: CompletableDeferred<JsonElement>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    private val wsUrl: String
        get() = hostToWs(options.host, options.useSsl) // TS ddpClient.ts:112-114

    // ---- 公共 API ----

    /** TS isTransportOpen ddpClient.ts:116-118。 */
    fun isTransportOpen(): Boolean = connected && socket != null

    /** TS onStreamData ddpClient.ts:99-102。 */
    fun onStreamData(event: String, handler: (JsonElement) -> Unit): Disposable {
        addListener(event, handler)
        return Disposable { removeListener(event, handler) }
    }

    /**
     * TS connect ddpClient.ts:120-131：已连接直接返回；并发调用合并到同一 in-flight；
     * 启动前清掉未触发的 reopen 定时器。
     */
    suspend fun connect() {
        if (closed) throw DdpException("[ddp] client closed")
        if (isTransportOpen()) return
        // TS:125 clearTimeout(reopenTimer)；reopen 协程自身调 connect 时不能自杀
        val currentJob = coroutineContext[Job]
        synchronized(reopenLock) {
            reopenJob?.takeIf { it !== currentJob }?.let {
                it.cancel()
                if (reopenJob === it) reopenJob = null
            }
        }

        val deferred = connectLock.withLock {
            if (closed) throw DdpException("[ddp] client closed")
            if (isTransportOpen()) return
            connectInflight ?: CompletableDeferred<Unit>().also { d ->
                connectInflight = d
                val genAtStart = generation.get()
                scope.launch {
                    try {
                        // disconnect 抢在建队与执行之间到达：不建 socket，直接以「已断开」失败
                        if (generation.get() != genAtStart) throw DdpException("[ddp] disconnected")
                        openConnection()
                        d.complete(Unit)
                    } catch (e: Throwable) {
                        // 外层取消包装成 DdpException，避免无辜等待者被误判为自身取消
                        d.completeExceptionally(if (e is CancellationException) DdpException("[ddp] connect cancelled", e) else e)
                    } finally {
                        // TS:127-129 finally { connectInflight = null }
                        connectLock.withLock { if (connectInflight === d) connectInflight = null }
                    }
                }
            }
        }
        deferred.await()
    }

    /** TS disconnect ddpClient.ts:221-235：close code 4000，清 userId/reopenTimer/ping，不再自动重连。 */
    fun disconnect() = disconnectInternal()

    /**
     * 终态销毁（M1 session 层持有）：disconnect + 取消内部 scope。
     * 与 disconnect 不同：此后 connect 等方法快速失败，实例不可复用。
     */
    fun close() {
        if (closed) return
        closed = true
        disconnectInternal()
        scope.cancel()
    }

    /** TS checkAndReopen ddpClient.ts:237-242：未连接时 fire-and-forget（close 后无副作用）。 */
    fun checkAndReopen() {
        if (closed) return
        if (!isTransportOpen()) {
            scope.launch { runCatching { connect() } }
        }
    }

    /** TS callMethod ddpClient.ts:368-371：先 connect 再 call。 */
    suspend fun callMethod(method: String, vararg params: JsonElement): JsonElement {
        connect()
        return call(method, params.toList())
    }

    /** TS loginWithResume ddpClient.ts:373-378：login method + {resume}，成功后 userId = 响应 .id。 */
    suspend fun loginWithResume(token: String): DdpLoginResult {
        connect()
        val login = call("login", listOf(buildJsonObject { put("resume", token) }))
        val obj = login as? JsonObject
        userId = textOf(obj?.get("id"))?.takeIf { it.isNotEmpty() }
        return DdpLoginResult(
            id = userId ?: "",
            token = textOf(obj?.get("token")) ?: "",
            createCipherDate = ((obj?.get("createCipher") as? JsonObject)?.get("\$date") as? JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * TS subscribe ddpClient.ts:380-436：params = [eventName, {useCollection:false, args}]
     * （对齐 @rocket.chat/sdk ddp driver）；ack 等 ready（subs 含本 id）或 nosub，25s 超时。
     */
    suspend fun subscribe(topic: String, eventName: String, vararg args: JsonElement): DdpSubscription {
        val params = JsonArray(
            listOf(
                JsonPrimitive(eventName),
                buildJsonObject {
                    put("useCollection", false)
                    put("args", JsonArray(args.toList()))
                },
            ),
        )
        return subscribeInternal(topic, params, "$topic/$eventName")
    }

    /** TS subscribeRaw ddpClient.ts:439-492：params 原样作为 DDP sub.params 发送。 */
    suspend fun subscribeRaw(topic: String, params: List<JsonElement>): DdpSubscription =
        subscribeInternal(topic, JsonArray(params.toList()), topic)

    /** TS unsubscribe ddpClient.ts:494-496。 */
    suspend fun unsubscribe(subscriptionId: String): JsonElement? = unsubscribeRaw(subscriptionId)

    /**
     * 不做 close 握手直接断 TCP（等价 OkHttp cancel）。
     * 仅内部/测试用：MockWebServer 5.3 服务端 WS 既不下发也不应答 close 帧，优雅关闭无法在测试中收敛。
     */
    internal fun cancelTransport() {
        allWs.forEach { ws -> runCatching { ws.cancel() } }
    }

    // ---- 内部：连接 ----

    // TS openConnection ddpClient.ts:133-219
    private suspend fun openConnection() {
        emit("connecting", DDP_EMPTY_OBJECT) // TS:134
        eventListener?.onConnecting()

        val conn = Connection(generation.get())
        active = conn
        val ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), conn)
        socket = ws
        allWs.add(ws)

        try {
            // TS:170-172 建连+握手计时器
            withTimeout(options.connectTimeoutMs) { conn.handshake.await() }
        } catch (e: TimeoutCancellationException) {
            val err = DdpException("[ddp] connection timeout", e)
            conn.fail(ws, err)
            throw err
        } catch (e: CancellationException) {
            conn.fail(ws, DdpException("[ddp] connect cancelled", e))
            throw e
        } catch (e: DdpException) {
            // conn.gone 已完成清理，直接上抛
            throw e
        } catch (e: Throwable) {
            val err = DdpException("[ddp] connect failed", e)
            conn.fail(ws, err)
            throw err
        }
    }

    private fun disconnectInternal() {
        stopPing()
        cancelReopen()
        // 作废在途 connect（与 connect 的建队段同锁互斥）：断开落在建连中途时，
        // 迟到的握手整体丢弃，等在建连上的调用方快速失败（supersede，原生版加固）
        connectLock.withLock {
            generation.incrementAndGet()
            connectInflight?.completeExceptionally(DdpException("[ddp] disconnected"))
        }
        connected = false
        userId = null
        failPending("[ddp] disconnected")
        val ws = socket
        socket = null // 旧 socket 的 onClosed 由此走「过期连接」早退，不会触发重连
        runCatching { ws?.close(4000, "client disconnect") }
        // 兜底：onOpen 尚未到达的在建 socket 也一并关闭（close 对未开连接等同 fail）
        allWs.forEach { candidate ->
            if (candidate !== ws) runCatching { candidate.close(4000, "client disconnect") }
        }
    }

    // TS tryReopen ddpClient.ts:244-250：reopenTimer 已存在则不重复排
    private fun tryReopen() {
        synchronized(reopenLock) {
            if (reopenJob?.isActive == true) return
            reopenJob = scope.launch {
                delay(options.reopenMs)
                synchronized(reopenLock) {
                    if (reopenJob === coroutineContext[Job]) reopenJob = null
                }
                try {
                    connect()
                } catch (e: Throwable) {
                    tryReopen()
                }
            }
        }
    }

    private fun cancelReopen() {
        synchronized(reopenLock) {
            reopenJob?.cancel()
            reopenJob = null
        }
    }

    // TS startPing ddpClient.ts:252-268：每 pingMs 发 ping，收到 pong 后才排下一轮
    private fun startPing() {
        stopPing()
        pingJob = scope.launch {
            while (true) {
                delay(options.pingMs)
                if (!isTransportOpen()) return@launch // TS:255-256
                try {
                    sendRaw(ddpPingMessage())
                } catch (e: Throwable) {
                    // TS:262-264 发送失败（含等 pong 超时）→ disconnect + tryReopen
                    disconnectInternal()
                    tryReopen()
                    return@launch
                }
            }
        }
    }

    // TS stopPing ddpClient.ts:270-273
    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    // ---- 内部：消息收发 ----

    // TS handleMessage ddpClient.ts:275-296
    private fun handleMessage(raw: String) {
        val data = try {
            ddpJson.parseToJsonElement(raw)
        } catch (e: Exception) {
            // TS: console.warn('[ddp] JSON parse error') —— 移植版静默丢弃坏帧
            return
        }
        val obj = data as? JsonObject ?: return

        // TS:286-291 自动应答 DDP ping（fire-and-forget，失败忽略），且不再三路分发
        if (textOf(obj["msg"]) == "ping") {
            lastPing = System.currentTimeMillis()
            scope.launch { runCatching { sendRaw(ddpPongMessage()) } }
            emit("ping", obj)
            eventListener?.onPing(obj)
            return
        }

        // TS:293-295 三路分发
        for (key in eventKeys(obj)) emit(key, obj)
    }

    // TS sendRaw ddpClient.ts:298-357
    private suspend fun sendRaw(obj: JsonObject): JsonElement? {
        if (!isTransportOpen()) {
            connect() // TS:299-301
        }
        val ws = socket ?: throw DdpException("[ddp] socket not open")
        val msg = textOf(obj["msg"])
        // TS:304 —— 注意：connect/ping/pong 不带 id，但 `??` 右侧照样求值，序列号被消耗（逐行为对齐保留）
        val id = textOf(obj["id"]) ?: "ddp-${seq.getAndIncrement()}"
        // TS:306 —— connect/ping/pong 不挂 id，其余消息都带
        val dataToSend: JsonObject = if (msg == "connect" || msg == "ping" || msg == "pong") {
            obj
        } else {
            JsonObject(obj.toMap() + ("id" to JsonPrimitive(id)))
        }
        // TS:315-322 —— connect→connected、ping→pong、pong→不等、其余→自增 id 事件
        val expectedEvent: String? = when (msg) {
            "connect" -> "connected"
            "ping" -> "pong"
            "pong" -> null
            else -> id
        }
        val payload = dataToSend.toString()

        if (expectedEvent == null) {
            // TS:349-350 —— pong 发完即 resolve
            if (!ws.send(payload)) throw DdpException("[ddp] socket not open")
            return null
        }

        // TS:334-345 先注册 once 监听再发送（响应可能先于 send 返回）
        val deferred = CompletableDeferred<JsonElement>()
        val wrapper = addOnce(expectedEvent) { result ->
            val o = result as? JsonObject
            val err = o?.get("error")
            if (err == null || err is JsonNull) {
                deferred.complete(result) // TS:338
            } else {
                deferred.completeExceptionally(DdpMethodError(err)) // TS:337
            }
        }
        val entry = Pending(expectedEvent, wrapper, deferred)
        pending.put(expectedEvent, entry)?.let { old ->
            removeListener(old.event, old.wrapper)
            old.deferred.completeExceptionally(DdpException("[ddp] superseded"))
        }
        try {
            if (!ws.send(payload)) throw DdpException("[ddp] socket not open") // TS:310-312 readyState 检查
            return try {
                withTimeout(options.responseTimeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw DdpException("[ddp] response timeout ($msg)", e) // TS:343
            }
        } finally {
            removeListener(expectedEvent, wrapper)
            // 按引用删：同 key 的新 pending（如重连后的 connect 握手）不能被旧请求的 finally 误删
            pending.remove(expectedEvent, entry)
        }
    }

    // TS call ddpClient.ts:359-363：method 响应取 .result，缺省回原响应
    private suspend fun call(method: String, params: List<JsonElement>): JsonElement {
        val result = sendRaw(ddpMethodMessage(method, params))
        val body = result ?: return JsonNull
        if (body is JsonObject) {
            body["result"]?.takeUnless { it is JsonNull }?.let { return it }
        }
        return body
    }

    // TS subscribe/subscribeRaw 公共主体 ddpClient.ts:387-435 / 443-491
    private suspend fun subscribeInternal(topic: String, params: JsonArray, label: String): DdpSubscription {
        connect() // TS:381
        val id = "sub-${seq.getAndIncrement()}" // TS:382

        val ack = CompletableDeferred<Unit>()
        // ready/nosub 用 on + id 过滤（非 once）：并发订阅时别的 ready 事件不能被误消费（TS:394-406）
        val onReady: (JsonElement) -> Unit = { data ->
            val o = data as? JsonObject
            if (o != null && textOf(o["msg"]) == "ready" && o["subs"] is JsonArray) {
                if ((o["subs"] as JsonArray).any { textOf(it) == id }) ack.complete(Unit)
            }
        }
        val onNosub: (JsonElement) -> Unit = { data ->
            val o = data as? JsonObject
            if (o != null && textOf(o["msg"]) == "nosub" && textOf(o["id"]) == id) {
                val err = o["error"]
                if (err == null || err is JsonNull) {
                    ack.completeExceptionally(DdpException("[ddp] subscription rejected (nosub)"))
                } else {
                    ack.completeExceptionally(DdpMethodError(err))
                }
            }
        }
        addListener("ready", onReady)
        addListener("nosub", onNosub)
        ack.invokeOnCompletion {
            removeListener("ready", onReady)
            removeListener("nosub", onNosub)
        }

        try {
            // TS:417-421 —— 先挂监听再检查 socket
            val ws = socket
            if (ws == null || !isTransportOpen()) throw DdpException("[ddp] socket not open")
            if (!ws.send(ddpSubMessage(id, topic, params).toString())) throw DdpException("[ddp] socket not open")
            try {
                withTimeout(subscribeTimeoutMs) { ack.await() }
            } catch (e: TimeoutCancellationException) {
                throw DdpException("[ddp] subscribe timeout ($label)", e) // TS:391
            }
        } finally {
            // 兜底触发 invokeOnCompletion，保证监听在任何路径下都被摘除
            ack.complete(Unit)
        }
        return DdpSubscription(id = id, client = this)
    }

    // TS unsubscribeRaw ddpClient.ts:498-534：15s 超时后 resolve 而非 reject
    internal suspend fun unsubscribeRaw(subscriptionId: String): JsonElement? {
        connect() // TS:499
        val ack = CompletableDeferred<JsonElement?>()
        val onNosub: (JsonElement) -> Unit = { data ->
            val o = data as? JsonObject
            if (o != null && textOf(o["msg"]) == "nosub" && textOf(o["id"]) == subscriptionId) {
                val err = o["error"]
                if (err == null || err is JsonNull) ack.complete(data) else ack.completeExceptionally(DdpMethodError(err))
            }
        }
        addListener("nosub", onNosub)
        ack.invokeOnCompletion { removeListener("nosub", onNosub) }
        try {
            val ws = socket
            if (ws == null || !isTransportOpen()) return null // TS:518-524 resolve(undefined)
            if (!ws.send(ddpUnsubMessage(subscriptionId).toString())) throw DdpException("[ddp] socket not open")
            return try {
                withTimeout(unsubscribeTimeoutMs) { ack.await() }
            } catch (e: TimeoutCancellationException) {
                null // TS:502-505 —— 超时 resolve(undefined)
            }
        } finally {
            ack.complete(null)
        }
    }

    // ---- 内部：事件注册表（TS Emitter ddpClient.ts:3-32）----

    private fun addListener(event: String, cb: (JsonElement) -> Unit) {
        listeners.getOrPut(event) { CopyOnWriteArraySet() }.add(cb)
    }

    private fun removeListener(event: String, cb: (JsonElement) -> Unit) {
        listeners[event]?.remove(cb)
    }

    /** TS Emitter.once（ddpClient.ts:19-25）。 */
    private fun addOnce(event: String, cb: (JsonElement) -> Unit): (JsonElement) -> Unit {
        val self = arrayOfNulls<(JsonElement) -> Unit>(1)
        val wrapper: (JsonElement) -> Unit = { payload ->
            self[0]?.let { removeListener(event, it) }
            cb(payload)
        }
        self[0] = wrapper
        addListener(event, wrapper)
        return wrapper
    }

    // TS Emitter.emit（ddpClient.ts:27-31）；handler 异常吞掉，不影响其它监听
    private fun emit(event: String, payload: JsonElement) {
        listeners[event]?.forEach { cb -> runCatching { cb(payload) } }
    }

    private fun failPending(message: String) {
        for (p in pending.values) {
            removeListener(p.event, p.wrapper)
            p.deferred.completeExceptionally(DdpException(message))
        }
        pending.clear()
    }

    // ---- 内部：单条连接的 WS 监听（per-connection，等价 TS 每次重挂 handler）----

    private inner class Connection(private val gen: Long) : WebSocketListener() {
        val handshake = CompletableDeferred<Unit>()

        @Volatile
        private var ws: WebSocket? = null

        /** 当前连接且未被 disconnect/close 作废（代次匹配）才允许触碰共享状态。 */
        private fun isCurrent(): Boolean = active === this && gen == this@DdpClient.generation.get()

        // TS onopen ddpClient.ts:178-193
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) return
            ws = webSocket
            socket = webSocket
            connected = true // TS:179
            lastPing = System.currentTimeMillis() // TS:180
            scope.launch {
                try {
                    sendRaw(ddpConnectMessage()) // TS:182-186
                    startPing() // TS:187
                    emit("connected", DDP_EMPTY_OBJECT) // TS:188
                    eventListener?.onConnected()
                    handshake.complete(Unit)
                } catch (e: Throwable) {
                    fail(null, if (e is DdpException) e else DdpException("[ddp] connect handshake failed", e))
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            handleMessage(text)
        }

        // TS onclose ddpClient.ts:203-214
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            gone(webSocket, DdpCloseEvent(code, reason, null))
        }

        // TS onerror ddpClient.ts:199-201
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            gone(webSocket, DdpCloseEvent(0, "", t))
        }

        private fun gone(webSocket: WebSocket, event: DdpCloseEvent) {
            val current = isCurrent()
            allWs.remove(webSocket) // 过期连接也要从注册表摘除，防 allWs 无界滞留
            if (!current) return
            active = null
            val wasSettled = connected
            socket = null
            connected = false
            emit("close", buildJsonObject {
                put("code", event.code)
                put("reason", event.reason)
                event.cause?.let { put("error", it.message ?: it.toString()) }
            })
            eventListener?.onClose(event)
            stopPing()
            failPending("[ddp] connection closed")
            if (!wasSettled) {
                handshake.completeExceptionally(
                    if (event.cause != null) {
                        DdpException("[ddp] websocket connection failed", event.cause)
                    } else {
                        DdpException("[ddp] websocket closed before connect")
                    },
                )
            } else {
                userId = null // TS:211
                tryReopen() // TS:212
            }
        }

        // TS finishReject ddpClient.ts:148-168：detach handlers + close + 置空
        // 守卫：本连接已被 supersede（超时/断开后重连出 B）时，残留握手协程的 fail 不得污染 B 的状态；
        // 但自己的 socket 仍要强杀 + 从 allWs 摘除（disconnect 落在建连中途的孤儿连接）。
        fun fail(webSocket: WebSocket?, err: Throwable) {
            val current = isCurrent()
            if (current) {
                active = null
                connected = false
            }
            val target = webSocket ?: ws
            if (current && socket === target) socket = null
            target?.let {
                allWs.remove(it)
                runCatching { it.cancel() }
            }
            if (current) handshake.completeExceptionally(err)
        }
    }
}
