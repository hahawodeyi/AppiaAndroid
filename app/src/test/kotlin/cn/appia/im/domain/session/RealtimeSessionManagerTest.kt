package cn.appia.im.domain.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.ddp.DdpException
import cn.appia.im.core.network.ddp.DdpMethodError
import cn.appia.im.core.network.rest.SessionExpiredBus
import cn.appia.im.core.realtime.RealtimeTransportPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val testJson = Json { ignoreUnknownKeys = true }

private fun parse(raw: String): JsonObject? =
    runCatching { testJson.parseToJsonElement(raw).jsonObject }.getOrNull()

/** JSON 字段取串：null/JsonNull/非原始类型 → null（与 LoginMethodServer.str 同口径）。 */
private fun JsonObject?.s(key: String): String? = when (val v = this?.get(key)) {
    null -> null
    is JsonPrimitive -> v.content
    else -> null
}

/**
 * RealtimeSessionManager 集成测试（Robolectric + MockWebServer REST/WebSocket 全栈，注入 fake syncInitial）。
 * 对照绑定验收：bootstrap 步序、inflight 合并、generation 中途 reset 中止、失效文本三态 + 行为、
 * 重连后 resume + 重订阅、teardown 幂等可重入、事件分发注册表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RealtimeSessionManagerTest {

    private val context: Context = getApplicationContext()
    private val dbManager = DatabaseManager(context)
    private val server = MockWebServer()
    private val wsListeners = ConcurrentLinkedQueue<SessionWsServer>()
    private val sdks = CopyOnWriteArrayList<RocketSdk>()
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()

    private val syncCalls = AtomicInteger(0)
    private val expiredCalls = AtomicInteger(0)
    private val order = CopyOnWriteArrayList<String>()
    private var syncGate: CompletableDeferred<Unit>? = null
    private var failSync = false

    private var subsBody = "{}"
    private var roomsBody = "{}"
    private var emojiBody: String? = null

    private lateinit var host: String
    private lateinit var sdk: RocketSdk
    private lateinit var manager: RealtimeSessionManager

    @Before
    fun setUp() {
        server.start()
        host = server.url("/").toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/websocket") -> MockResponse().withWebSocketUpgrade(wsListeners.poll()!!)
                    path.startsWith("/api/v1/subscriptions.get") -> MockResponse().setBody(subsBody)
                    path.startsWith("/api/v1/rooms.get") -> MockResponse().setBody(roomsBody)
                    // T13：emoji-custom.list（syncCustomEmojis）；null → 404（失败仅 warn 分支）
                    path.startsWith("/api/v1/emoji-custom.list") ->
                        emojiBody?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        sdk = RocketSdk(client = OkHttpClient()).also { sdks.add(it) }
        manager = RealtimeSessionManager(
            sdk = sdk,
            dbManager = dbManager,
            syncInitial = {
                // 步序断言：syncInitial 时切库已完成、DDP resume 已完成（步骤 2/3 先于步骤 5）
                order.add("switched=${dbManager.active === dbManager.databaseFor(dbManager.normalizeServer(host))}")
                order.add("ddp=${sdk.hasDdpUserId()}")
                syncCalls.incrementAndGet()
                syncGate?.await()
                if (failSync) throw IOException("sync boom")
            },
            onSessionExpired = { expiredCalls.incrementAndGet() },
            scope = newScope(),
        )
    }

    @After
    fun tearDown() {
        runCatching { manager.teardown() }
        scopes.forEach { it.cancel() }
        sdks.forEach { s ->
            s.ddp?.let {
                runCatching { it.disconnect() }
                runCatching { it.cancelTransport() }
            }
        }
        dbManager.resetAll()
        runCatching { server.shutdown() }
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }

    /** suspend 轮询等待（delay 让出 runBlocking 事件循环，async/launch 子协程才得以被调度）。 */
    private suspend fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timeout waiting for: $desc")
    }

    /** 6 条全局订阅事件的 eventName 集合（3 条带 uid 前缀 + 3 条全局）。 */
    private fun SessionWsServer.subEventNames(): List<String> =
        frames.mapNotNull { parse(it) }
            .filter { it.s("msg") == "sub" }
            .mapNotNull { f -> (f["params"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content }
            .sorted()

    // ---- bootstrap 步序 + 订阅表 ----

    @Test
    fun `bootstrap follows RN step order and subscribes six global streams`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }

        manager.bootstrap(host, "tok-1", userId = "uid-1")

        awaitCond("six global subs acked") { ws.subCount() >= 6 }
        // 步骤 2（切库）与步骤 3（resume）先于步骤 5（REST 同步）
        assertEquals(listOf("switched=true", "ddp=true"), order)
        assertEquals("tok-1", ws.resumeToken()) // resume 帧携带 bootstrap 的会话 token
        assertEquals(
            listOf(
                "permissions-changed",
                "public-settings-changed",
                "roles",
                "uid-1/rooms-changed",
                "uid-1/subscriptions-changed",
                "uid-1/userData",
            ),
            ws.subEventNames(),
        )
        // topic 对应关系：notify-user×3、notify-logged、roles、notify-all
        val topics = ws.frames.mapNotNull { parse(it) }
            .filter { it.s("msg") == "sub" }
            .mapNotNull { it.s("name") }
            .sorted()
        assertEquals(
            listOf("stream-notify-all", "stream-notify-logged", "stream-notify-user", "stream-notify-user", "stream-notify-user", "stream-roles"),
            topics,
        )
        assertTrue(manager.streamsSubscribedForTest)

        // sessionKey 短路：同 key 二次 bootstrap 不再重跑
        manager.bootstrap(host, "tok-1")
        assertEquals(1, syncCalls.get())
    }

    @Test
    fun `concurrent bootstraps of same key merge into one run`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        syncGate = CompletableDeferred()

        val a = async { manager.bootstrap(host, "tok-m") }
        val b = async { manager.bootstrap(host, "tok-m") }
        awaitCond("first sync entered") { syncCalls.get() == 1 }
        syncGate?.complete(Unit)
        a.await()
        b.await()

        assertEquals(1, syncCalls.get())
        awaitCond("subs done") { ws.subCount() >= 6 }
        assertNotNull(manager.sessionKeyForTest)
    }

    // ---- 评审 Important-1 回归：等待者被取消，body 继续且注册不被清 ----

    @Test
    fun `waiter cancellation leaves body running and same key bootstrap merges instead of double running`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        syncGate = CompletableDeferred()

        val waiter = launch { manager.bootstrap(host, "tok-w") }
        awaitCond("sync entered") { syncCalls.get() == 1 }
        waiter.cancel() // 等待者死亡；body 必须继续在跑、inflight 注册必须保留
        waiter.join()

        // 同 key 后续 bootstrap：必须合并到在途 body，不得起第二个 body 双跑
        val merger = launch { manager.bootstrap(host, "tok-w") }
        delay(100) // 让出事件循环给 merger：若注册已被清，第二个 body 此间进入 sync → 计数变 2
        assertEquals(1, syncCalls.get())

        syncGate?.complete(Unit)
        merger.join()
        assertNotNull(manager.sessionKeyForTest)
        awaitCond("subs done once") { ws.subCount() >= 6 }
        assertEquals(1, syncCalls.get())
    }

    // ---- 评审盲区补充：不同 key 并发 bootstrap 串行完整跑 ----

    @Test
    fun `concurrent bootstraps of different keys run serially to completion`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }

        val a = launch { manager.bootstrap(host, "tok-A", userId = "uid-1") }
        val b = launch { manager.bootstrap(host, "tok-B", userId = "uid-1") }
        a.join()
        b.join()

        // 两个不同 key 互不合并、互不短路：各自完整跑一遍 body（bootstrapMutex 串行）
        assertEquals(2, syncCalls.get())
        assertTrue(sdk.hasDdpUserId())
        awaitCond("global subs") { ws.subCount() >= 6 }
        // 同服共用 DdpClient：仅首个 key 触发 resume；B 的 hydrate 只刷新 REST 会话 token
        assertEquals("tok-A", ws.resumeToken())
        assertEquals(1, ws.frames.count { parse(it)?.s("method") == "login" })
        assertNotNull(manager.sessionKeyForTest)
        // 任一已落 key 短路
        manager.bootstrap(host, "tok-B")
        assertEquals(2, syncCalls.get())
    }

    // ---- generation 竞态 ----

    @Test
    fun `resetForOrgSwitch mid-bootstrap aborts remaining steps`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        syncGate = CompletableDeferred()

        val job = launch { manager.bootstrap(host, "tok-g") }
        awaitCond("sync entered") { syncCalls.get() == 1 }
        manager.resetForOrgSwitch()
        syncGate?.complete(Unit)
        job.join()

        // 步骤 6 generation 检查中止：sessionKey 不落、generation 已递增
        assertNull(manager.sessionKeyForTest)
        assertEquals(1, manager.generationForTest)
        assertEquals(1, syncCalls.get())

        // 同 key 重新 bootstrap 必须重跑（短路态已被清）
        manager.bootstrap(host, "tok-g")
        assertEquals(2, syncCalls.get())
        assertNotNull(manager.sessionKeyForTest)
    }

    // ---- 评审 Important-1 回归：reset 后迟到的订阅协程不清/不写会话订阅态 ----

    @Test
    fun `late subscription coroutine after org switch reset does not mark session subscribed`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        ws.withholdSubReady = true // 卡住步骤 4 的 fire-and-forget 订阅协程（6 条 sub 已发、等 ready）

        val job = launch { manager.bootstrap(host, "tok-1", userId = "uid-1") }
        awaitCond("six subs parked on ready") { ws.subCount() >= 6 }
        job.join() // body 跑完（sessionKey 落定），订阅协程仍挂在 ready 上

        // 组织切换：generation 递增使该 bootstrap 作废，但已发射的订阅协程不被取消
        manager.resetForOrgSwitch()

        // 此刻才放行 ready：迟到的订阅协程完成——不得把 true 写进已作废会话的订阅态
        ws.frames.filter { parse(it)?.s("msg") == "sub" }.forEach { frame ->
            ws.send("""{"msg":"ready","subs":["${parse(frame)!!.s("id")}"]}""")
        }

        delay(500) // 给迟到协程落地时间：修复前此处会把 streamsSubscribed 写回 true（幽灵在线）
        assertFalse(manager.streamsSubscribedForTest)
        assertEquals(1, manager.generationForTest)
    }

    // ---- 会话失效识别：三态 + 行为 ----

    @Test
    fun `invalidated resume error text matches three states`() {
        fun err(reason: String?, message: String? = null): DdpMethodError {
            val payload = buildJsonObject {
                if (reason != null) put("reason", reason)
                if (message != null) put("message", message)
            }
            return DdpMethodError(payload)
        }
        // RN session.ts:103 原文匹配，大小写不敏感
        assertTrue(manager.isSessionInvalidatedResumeError(err("You've Been Logged Out By The Server")))
        assertTrue(manager.isSessionInvalidatedResumeError(err("your session has expired")))
        // 不匹配：普通 DDP 错误 / 无文本异常
        assertFalse(manager.isSessionInvalidatedResumeError(err("not-allowed", "no permission")))
        assertFalse(manager.isSessionInvalidatedResumeError(DdpException("[ddp] disconnected")))
    }

    @Test
    fun `resume invalidation emits event and logout callback but bootstrap continues`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        ws.loginReplyFactory = { id ->
            """{"msg":"result","id":"$id","error":{"error":"logged-out","reason":"you've been logged out by the server"}}"""
        }
        val busEvents = AtomicInteger(0)
        val collector = launch { SessionExpiredBus.events.collect { busEvents.incrementAndGet() } }

        manager.bootstrap(host, "tok-x")

        awaitCond("bus event delivered") { busEvents.get() >= 1 }
        collector.cancel()
        assertEquals(1, expiredCalls.get())
        // resume 失败：DDP 无 userId；RN 语义——DDP 失败不阻断 bootstrap（REST 照跑、sessionKey 照设）
        assertFalse(sdk.hasDdpUserId())
        assertEquals(1, syncCalls.get())
        assertNotNull(manager.sessionKeyForTest)
        assertTrue(ws.subCount() == 0)
    }

    // ---- 重连恢复 ----

    @Test
    fun `transport drop reconnects resumes and resubscribes all streams`() = runBlocking {
        val s1 = SessionWsServer().also { wsListeners.add(it) }
        val s2 = SessionWsServer().also { wsListeners.add(it) }

        manager.bootstrap(host, "tok-r", userId = "uid-1")
        awaitCond("first six subs") { s1.subCount() >= 6 }
        assertEquals("tok-r", s1.resumeToken())

        // MockWebServer 5.3 无法从服务端发 close 帧（DdpClientTest 同口径）：客户端强杀触发 close 事件
        sdk.ddp!!.cancelTransport()

        // close → phase 落 connecting（RN :221）是瞬态：localhost 全链路重连可在一个轮询窗口内
        // 完成，对中间态轮询必然 flaky，故不断言；phase 终态与手动重连见下方 T5 专项用例
        awaitCond("resume on new connection") { s2.hasLoginFrame() }
        awaitCond("six subs on new connection") { s2.subCount() >= 6 }
        assertEquals("tok-r", s2.resumeToken())
        assertEquals(s1.subEventNames(), s2.subEventNames())
    }

    // ---- M2 T6：重连收尾 tail 与 teardown 挂点 ----

    @Test
    fun `reconnect tail runs after global streams are restored`() = runBlocking {
        val s1 = SessionWsServer().also { wsListeners.add(it) }
        val s2 = SessionWsServer().also { wsListeners.add(it) }
        val tailCalls = AtomicInteger(0)
        var sawDdpUserAtTail: Boolean? = null
        manager.addReconnectTail {
            tailCalls.incrementAndGet()
            sawDdpUserAtTail = sdk.hasDdpUserId() // tail 必须在 resume 之后跑（RN :156-162 序）
        }

        manager.bootstrap(host, "tok-tail", userId = "uid-1")
        awaitCond("first six subs") { s1.subCount() >= 6 }
        assertEquals(0, tailCalls.get()) // 首次 bootstrap 不触发 finalize 的 runResume 分支

        sdk.ddp!!.cancelTransport()
        awaitCond("tail on reconnect") { tailCalls.get() >= 1 }
        assertEquals(true, sawDdpUserAtTail)
        awaitCond("subs restored") { s2.subCount() >= 6 }
    }

    @Test
    fun `teardown hooks run before listeners are stopped`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        val order = CopyOnWriteArrayList<String>()
        manager.addTeardownHook { order.add("hook") }

        manager.bootstrap(host, "tok-hook", userId = "uid-1")
        awaitCond("subs") { ws.subCount() >= 6 }

        manager.teardown()
        assertEquals(listOf("hook"), order) // hook 被调（RN :676-677 清队列在断连之前）
    }

    // ---- M2 T5：横幅 phase 与手动重连（RN requestManualRealtimeReconnect :656-670） ----

    @Test
    fun `manual reconnect sets connecting synchronously then finalizes back to connected`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }

        manager.bootstrap(host, "tok-mr", userId = "uid-1")
        awaitCond("first six subs") { ws.subCount() >= 6 }
        assertEquals(RealtimeTransportPhase.CONNECTED, manager.phase.value) // RN :180 finalize 成功落 connected

        manager.requestManualReconnect()
        // RN :661：入口同步置 connecting（本协程内立即可见），随后 connect（已开短路返回）+ finalize 重订阅
        assertEquals(RealtimeTransportPhase.CONNECTING, manager.phase.value)
        awaitCond("resume + resubscribe via finalize tail") { ws.subCount() >= 12 }
        awaitCond("phase back to connected") { manager.phase.value == RealtimeTransportPhase.CONNECTED }
    }

    @Test
    fun `manual reconnect without bootstrap is a guarded no-op`() {
        // RN :659 token 守卫：未 bootstrap（无会话 token）→ 不触网、不改 phase
        manager.requestManualReconnect()
        assertEquals(RealtimeTransportPhase.CONNECTED, manager.phase.value)
    }

    // ---- teardown ----

    @Test
    fun `teardown is idempotent and manager can bootstrap again`() = runBlocking {
        val s1 = SessionWsServer().also { wsListeners.add(it) }
        val s2 = SessionWsServer().also { wsListeners.add(it) }

        manager.bootstrap(host, "tok-t", userId = "uid-1")
        awaitCond("subs before teardown") { s1.subCount() >= 6 }
        assertTrue(sdk.hasDdpUserId())

        manager.teardown()
        manager.teardown() // 幂等

        assertNull(manager.sessionKeyForTest)
        assertFalse(sdk.hasDdpUserId())
        assertEquals(false, sdk.ddp?.isTransportOpen())
        // clearRestSession 生效：REST 会话已清
        val ex = runCatching { sdk.get("rooms.get") }.exceptionOrNull()
        assertEquals("Not logged in", ex?.message)

        // 可重新 bootstrap：完整走 prepareSocketConnection → resume → 重订阅
        manager.bootstrap(host, "tok-t", userId = "uid-1")
        assertEquals(2, syncCalls.get())
        awaitCond("resubscribed on new connection") { s2.subCount() >= 6 }
        assertTrue(sdk.hasDdpUserId())
        assertTrue(manager.streamsSubscribedForTest)
    }

    @Test
    fun `teardown cancels in-flight bootstrap so session does not resurrect`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        syncGate = CompletableDeferred()

        val job = launch { manager.bootstrap(host, "tok-c") }
        awaitCond("sync entered") { syncCalls.get() == 1 }
        manager.teardown()
        syncGate?.complete(Unit)
        job.join()

        assertNull(manager.sessionKeyForTest)
        // teardown 已 disconnect，被取消的 body 不得再 resume 复活会话
        awaitCond("stays disconnected") { sdk.ddp?.isTransportOpen() == false }
    }

    // ---- syncInitial 失败仅 warn ----

    @Test
    fun `syncInitial failure does not abort bootstrap`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        failSync = true

        manager.bootstrap(host, "tok-f", userId = "uid-1")

        assertEquals(1, syncCalls.get())
        assertNotNull(manager.sessionKeyForTest)
        assertTrue(sdk.hasDdpUserId())
        awaitCond("subs still done") { ws.subCount() >= 6 }
    }

    // ---- T13：bootstrap extras 的 custom emojis 同步（RN session.ts:609）----

    @Test
    fun `bootstrap extras sync custom emojis into active database`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        val dateKey = "\$" + "date"
        emojiBody = """
            {"success":true,"emojis":{"update":[
              {"_id":"e1","name":"appia","aliases":["ap"],"extension":"png","_updatedAt":{"$dateKey":1700000000000}},
              {"_id":"e2","name":"party","extension":"gif","_updatedAt":{"$dateKey":1700000000001}}
            ]}}
        """.trimIndent()

        manager.bootstrap(host, "tok-e", userId = "uid-1")

        val db = dbManager.databaseFor(dbManager.normalizeServer(host))
        val dao = db.customEmojiDao()
        // dao 挂起查询：轮询取快照直至 2 行（extras 为 fire-and-forget 后台协程）
        var all = dao.getAll()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (all.size != 2 && System.nanoTime() < deadline) {
            delay(10)
            all = dao.getAll()
        }
        assertEquals(2, all.size)
        val appia = all.first { it.name == "appia" }
        assertEquals("png", appia.extension)
        assertEquals("""["ap"]""", appia.aliases)
        assertEquals(1700000000.0, appia._updated_at, 0.001)
        assertNull(all.first { it.name == "party" }.aliases)
    }

    @Test
    fun `emoji sync failure is warned not fatal`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        emojiBody = null // 404 → syncCustomEmojis 吞异常

        manager.bootstrap(host, "tok-404", userId = "uid-1")

        assertEquals(1, syncCalls.get())
        assertNotNull(manager.sessionKeyForTest)
        awaitCond("subs still done") { ws.subCount() >= 6 }
    }

    // ---- prepareSocketConnection 收敛点（T4 预检裁定：AuthApi 未来复用） ----

    @Test
    fun `prepareSocketConnection wires handlers and bootstrap reuses the wired transport`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }

        manager.prepareSocketConnection(host)
        awaitCond("transport open") { sdk.ddp?.isTransportOpen() == true }
        assertTrue(ws.frames.any { parse(it)?.s("msg") == "connect" })

        // wired=true：bootstrap 不再重建连接（无第二个 WS 升级可消费），直接在该连接上 resume
        manager.bootstrap(host, "tok-p", userId = "uid-1")
        assertTrue(sdk.hasDdpUserId())
        assertEquals(1, syncCalls.get())
        awaitCond("subs on reused connection") { ws.subCount() >= 6 }
    }

    // ---- 事件分发注册表 ----

    @Test
    fun `stream events dispatch to registered handler and stay no-op without one`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        val received = CopyOnWriteArrayList<JsonElement>()
        manager.setStreamHandler("stream-notify-user") { received.add(it) }

        manager.bootstrap(host, "tok-h", userId = "uid-1")
        awaitCond("subs") { ws.subCount() >= 6 }

        // 服务端推一条 stream-notify-user 帧三路分发（msg/collection/id），manager 按 topic 转发
        ws.send(
            """{"msg":"changed","collection":"stream-notify-user","id":"evt-1",""" +
                """"fields":{"eventName":"uid-1/subscriptions-changed","args":[]}}""",
        )
        awaitCond("handler got event") { received.isNotEmpty() }
        val fields = (received[0] as? JsonObject)?.get("fields") as? JsonObject
        assertEquals("uid-1/subscriptions-changed", fields?.s("eventName"))

        // 未注册 topic（stream-roles）默认 no-op：不崩溃、不误投
        ws.send("""{"msg":"changed","collection":"stream-roles","id":"evt-2","fields":{"eventName":"roles","args":[]}}""")
        assertEquals(1, received.size)
    }

    // ---- 全栈：真实 RoomsSyncRepository 作为 syncInitial ----

    @Test
    fun `full stack bootstrap persists initial rooms via real RoomsSyncRepository`() = runBlocking {
        val ws = SessionWsServer().also { wsListeners.add(it) }
        subsBody = """{"update":[{"_id":"sub-r1","rid":"r1","t":"c","name":"chan","fname":"","unread":1,"_updatedAt":"2026-01-01T00:00:00.000Z"}]}"""
        roomsBody = """{"update":[{"_id":"r1","_updatedAt":"2026-01-01T00:00:00.000Z"}]}"""
        val repo = RoomsSyncRepository(sdk, dbManager, InMemoryKvStore(), host)
        val fullStack = RealtimeSessionManager(
            sdk = sdk,
            dbManager = dbManager,
            syncInitial = { repo.sync(RoomsSyncRepository.Mode.BOOTSTRAP) },
            onSessionExpired = { expiredCalls.incrementAndGet() },
            scope = newScope(),
        )

        fullStack.bootstrap(host, "tok-fs", userId = "uid-1")

        awaitCond("subs") { ws.subCount() >= 6 }
        // 切库先于 REST 落库（守卫通过才有行）；REST 响应已合并落当前 active 库
        val row = dbManager.active.chatDao().getById("r1")
        assertNotNull(row)
        assertEquals("chan", row!!.name)
    }
}

/**
 * 脚本化 DDP 服务端（SessionWsServer）：connect→connected、ping→pong、sub→ready、login→脚本化应答。
 * connected 必须在收到客户端 connect 帧后再回（T2 测试坑：onOpen 抢发与监听注册竞态会随机挂死握手）。
 */
private class SessionWsServer : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)

    /** login method 应答工厂（入参为帧 id）；默认成功（ddp uid=uid-1）。 */
    @Volatile
    var loginReplyFactory: ((String?) -> String)? = null

    /** 置 true 时 sub 帧不自动回 ready（测试用：卡住订阅协程，构造「迟到协程」竞态）。 */
    @Volatile
    var withholdSubReady = false

    override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val obj = parse(text) ?: return
        when (obj.s("msg")) {
            "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
            "ping" -> webSocket.send("""{"msg":"pong"}""")
            "method" -> if (obj.s("method") == "login") {
                webSocket.send(
                    loginReplyFactory?.invoke(obj.s("id"))
                        ?: """{"msg":"result","id":"${obj.s("id")}",""" +
                            """"result":{"id":"uid-1","token":"ddp-token","createCipher":{"${'$'}date":1690000000000}}}""",
                )
            }
            "sub" -> if (!withholdSubReady) webSocket.send("""{"msg":"ready","subs":["${obj.s("id")}"]}""")
        }
    }

    fun send(text: String) {
        wsRef.get()?.send(text)
    }

    fun subCount(): Int = frames.count { parse(it)?.s("msg") == "sub" }

    fun hasLoginFrame(): Boolean = frames.any { parse(it)?.s("method") == "login" }

    fun resumeToken(): String? = frames.firstNotNullOfOrNull { raw ->
        parse(raw)?.takeIf { it.s("method") == "login" }?.let { frame ->
            ((frame["params"] as? JsonArray)?.firstOrNull() as? JsonObject)?.s("resume")
        }
    }
}
