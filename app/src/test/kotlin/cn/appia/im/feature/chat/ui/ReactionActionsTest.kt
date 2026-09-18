package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.MessageUpsert
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.realtime.RoomStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private val testJson = Json { ignoreUnknownKeys = true }

private fun parse(raw: String): JsonObject? =
    runCatching { testJson.parseToJsonElement(raw).jsonObject }.getOrNull()

/**
 * ReactionActions 乐观更新测试（Robolectric + 内存 Room + MockWebServer）：
 * 乐观翻转先行（只触 reactions 列）→ POST chat.react → 失败回滚原值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReactionActionsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val server = MockWebServer()
    private val restResponses = ConcurrentLinkedQueue<MockResponse>()
    private lateinit var sdk: RocketSdk

    @Before
    fun setUp() {
        server.start()
        // 自定义 dispatcher 后不能再用 enqueue（QueueDispatcher 已被替换）：restResponses 队列代之
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                restResponses.poll() ?: MockResponse().setBody("{}")
        }
        sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
    }

    @After
    fun tearDown() {
        db.close()
        runCatching { server.shutdown() }
    }

    private suspend fun insertMessage(reactions: String? = null) {
        db.messageDao().insert(
            MessageEntity(
                _id = "m1", rid = "r1", ts = 0.0, u = """{"_id":"u1","username":"bob"}""",
                alias = "", parse_urls = "[]", _updated_at = 0.0, reactions = reactions,
            ),
        )
    }

    @Test
    fun `toggle flips reactions column first then posts chat dot react`() = runBlocking {
        insertMessage()

        ReactionActions(sdk, db).toggle("m1", ":tada:", "bob")

        val row = db.messageDao().getById("m1")!!
        val reactions = parseReactions(row.reactions)
        assertEquals(listOf(":tada:"), reactions.map { it.shortname })
        assertEquals(listOf("bob"), reactions[0].usernames)
        // status 零接触（M2 同级纪律）：乐观写只触 reactions 列
        assertNull(row.status)

        val req = server.takeRequest()
        assertEquals("/api/v1/chat.react", req.path)
        assertEquals("""{"emoji":":tada:","messageId":"m1"}""", req.body.readUtf8())
    }

    @Test
    fun `toggle failure rolls back to original reactions`() = runBlocking {
        insertMessage(reactions = """{":fire:":{"usernames":["alice"]}}""")
        restResponses.add(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        assertThrows(Exception::class.java) {
            runBlocking { ReactionActions(sdk, db).toggle("m1", ":tada:", "bob") }
        }

        val row = db.messageDao().getById("m1")!!
        assertEquals(listOf(":fire:"), parseReactions(row.reactions).map { it.shortname })
    }

    @Test
    fun `toggle removal rollback restores removed username`() = runBlocking {
        insertMessage(reactions = """{":fire:":{"usernames":["bob","alice"]}}""")
        restResponses.add(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        assertThrows(Exception::class.java) {
            runBlocking { ReactionActions(sdk, db).toggle("m1", ":fire:", "bob") }
        }

        val row = db.messageDao().getById("m1")!!
        assertEquals(listOf("bob", "alice"), parseReactions(row.reactions).single().usernames)
    }

    @Test
    fun `toggle without username or row is a no-op and never posts`() = runBlocking {
        insertMessage()
        val actions = ReactionActions(sdk, db)

        actions.toggle("m1", ":tada:", null)
        actions.toggle("m1", ":tada:", "")
        actions.toggle("missing", ":tada:", "bob")

        assertEquals(0, server.requestCount)
        assertNull(db.messageDao().getById("m1")!!.reactions)
    }
}

/**
 * 乐观更新 + stream 回推校正时序（MockWebSocket 全栈，Robolectric）：
 * 乐观翻转落库后，`stream-room-messages` 推送完整消息（T6 语义）经 MessageUpsert upsert
 * 把 reactions 覆盖为服务端真值——乐观窗口内回推先到会覆盖乐观态（RN/legacy 同款竞态，可接受）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReactionStreamCorrectionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val server = MockWebServer()
    private val wsListeners = ConcurrentLinkedQueue<ReactionWsServer>()
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()
    private lateinit var sdk: RocketSdk
    private lateinit var manager: RoomStreamManager

    @Before
    fun setUp() {
        server.start()
        // REST 走 chat.react 分支；其余（DDP 传输升级）走 WebSocket
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/v1/chat.react") {
                    MockResponse().setBody("{}")
                } else {
                    MockResponse().withWebSocketUpgrade(wsListeners.poll()!!)
                }
        }
        sdk = RocketSdk(client = OkHttpClient()).also {
            it.initialize(server.url("/").toString())
            it.hydrateRestSession(server.url("/").toString(), "tok", "uid") // chat.react 需 REST 会话
        }
        manager = RoomStreamManager(
            sdk = sdk,
            persistMessage = { raw, rid -> MessageUpsert.persistFromUnknown(db, raw, rid) },
            scope = newScope(),
        )
        runBlocking {
            db.messageDao().insert(
                MessageEntity(
                    _id = "m1", rid = "r1", ts = 0.0, u = """{"_id":"u1","username":"bob"}""",
                    alias = "", parse_urls = "[]", _updated_at = 0.0,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        sdk.ddp?.let {
            runCatching { it.disconnect() }
            runCatching { it.cancelTransport() }
        }
        db.close()
        runCatching { server.shutdown() }
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes.add(it) }

    private suspend fun awaitCond(desc: String, timeoutMs: Long = 5_000, cond: suspend () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            delay(10)
        }
        throw AssertionError("timeout waiting for: $desc")
    }

    @Test
    fun `optimistic flip is corrected by stream push with server truth`() = runBlocking {
        val ws = ReactionWsServer().also { wsListeners.add(it) }
        sdk.connect()
        manager.subscribeRoom("r1")
        awaitCond("three subs sent") {
            ws.frames.count { f -> (parse(f)?.get("msg") as? JsonPrimitive)?.content == "sub" } >= 3
        }

        // 乐观窗口：本地翻转 bob 加 :tada:（REST 成功，服务端真值尚未回推）
        ReactionActions(sdk, db).toggle("m1", ":tada:", "bob")
        awaitCond("optimistic flip landed") {
            parseReactions(db.messageDao().getById("m1")!!.reactions).singleOrNull()?.usernames == listOf("bob")
        }

        // 服务端回推（另一人 alice 也已回应）：upsert 以服务端真值覆盖乐观态
        ws.send(
            """{"msg":"changed","collection":"stream-room-messages","id":"evt-1",""" +
                """"fields":{"eventName":"r1","args":[{"_id":"m1","rid":"r1","msg":"hi","ts":1690000000,""" +
                """"reactions":{":tada:":{"_id":"m1:tada:","emoji":":tada:","usernames":["bob","alice"]}}}]}}""",
        )
        awaitCond("server truth corrected optimistic state") {
            parseReactions(db.messageDao().getById("m1")!!.reactions).singleOrNull()?.usernames ==
                listOf("bob", "alice")
        }
        assertTrue(server.requestCount >= 1) // chat.react 已发出
    }
}

/** 极简 DDP 服务端（RoomStreamManagerTest.RoomWsServer 同构的最小子集）。 */
private class ReactionWsServer : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    private val wsRef = AtomicReference<WebSocket?>(null)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        wsRef.set(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val f = runCatching { testJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when ((f["msg"] as? JsonPrimitive)?.content) {
            "connect" -> webSocket.send("""{"msg":"connected","session":"s"}""")
            "ping" -> webSocket.send("""{"msg":"pong"}""")
            "sub" -> webSocket.send("""{"msg":"ready","subs":["${(f["id"] as? JsonPrimitive)?.content}"]}""")
        }
    }

    fun send(text: String) {
        wsRef.get()?.send(text)
    }
}
