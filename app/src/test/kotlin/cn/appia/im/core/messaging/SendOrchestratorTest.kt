package cn.appia.im.core.messaging

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.dao.MessageDao
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.MessageStatus.ERROR
import cn.appia.im.core.messaging.MessageStatus.QUEUED
import cn.appia.im.core.messaging.MessageStatus.SENDING
import cn.appia.im.core.messaging.MessageStatus.SENT
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * SendOrchestrator 状态机（Robolectric + 内存 Room + MockWebServer）：
 * QUEUED 行字段、wire body（md 省略）、QUEUED→SENDING→SENT、serverId 迁移两序
 * （echo 后到迁建 / echo 先到合并 status）、success:false / 4xx 直败、IO/5xx 重试退避
 * 1s/2s/4s、resend、per-rid 串行、单例 reset。时钟/退避缝注入固定值，免真实等待。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java).build()
    private val backoffs = ConcurrentLinkedQueue<Long>()
    private val bodies = ConcurrentLinkedQueue<String>()
    private val arrivals = ConcurrentLinkedQueue<Long>()

    /**
     * 按测试注入的响应计划；参数 = 已读出的 request body（RecordedRequest.body 只能读一次，
     * dispatcher 统一读取后传入）。缺省回 echo 客户端 _id 的成功响应。
     */
    @Volatile
    private var respond: (String) -> MockResponse = ::echoSuccess

    private val now = 1_700_000_000_000L
    private val user = CurrentUser(_id = "u1", username = "alice", name = "Alice")
    private val userJson = """{"_id":"u1","username":"alice","name":"Alice"}"""

    private lateinit var orchestrator: SendOrchestrator

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals.add(System.currentTimeMillis())
                val body = request.body.readUtf8()
                bodies.add(body)
                return respond(body)
            }
        }
        orchestrator = newOrchestrator()
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        db.close()
        runCatching { server.shutdown() }
    }

    /** 标准编排器：消费协程挂 IO；退避/时钟注入。 */
    private fun newOrchestrator(sdk: RocketSdk = newSdk(), scope: CoroutineScope? = null): SendOrchestrator =
        SendOrchestrator(
            db = db,
            sdk = sdk,
            currentUser = user,
            scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sleep = { ms -> backoffs.add(ms) },
            nowMs = { now },
        )

    private fun newSdk(hydrated: Boolean = true): RocketSdk =
        RocketSdk(client = OkHttpClient()).also {
            if ( hydrated) it.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        }

    private fun echoSuccess(body: String): MockResponse {
        val id = msgField(body, "_id")
        return MockResponse().setBody("""{"success":true,"message":{"_id":"$id"}}""")
    }

    private fun msgOf(body: String): JsonObject =
        Json.parseToJsonElement(body).jsonObject["message"]?.jsonObject ?: JsonObject(emptyMap())

    private fun msgField(body: String, key: String): String? =
        (msgOf(body)[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 轮询直至行 status 达到期望值（消费协程异步，10ms 步进，5s 超时）。 */
    private suspend fun awaitStatus(dao: MessageDao, id: String, expected: Double): MessageEntity {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            dao.getById(id)?.let { if (it.status == expected) return it }
            delay(10)
        }
        throw AssertionError("row $id never reached status $expected; got ${dao.getById(id)}")
    }

    private suspend fun awaitStatus(id: String, expected: Double): MessageEntity =
        awaitStatus(db.messageDao(), id, expected)

    private fun rid() = "r1"

    // ---- randomMessageId ----

    @Test
    fun `randomMessageId is 17 alphanumeric like RN`() {
        repeat(50) {
            val id = randomMessageId()
            assertEquals(17, id.length)
            assertTrue(id.all { it.isLetterOrDigit() })
        }
    }

    // ---- enqueue：QUEUED 行字段（RN enqueueTextMessage :102-118 create） ----

    /** 永不执行的调度器：block 入队不跑（消费协程冻结，QUEUED 行可确定性断言）。 */
    private val frozenDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    @Test
    fun `enqueue creates QUEUED row with RN fields before sending`() = runBlocking {
        val frozen = newOrchestrator(scope = CoroutineScope(SupervisorJob() + frozenDispatcher))
        val id = frozen.enqueueTextMessage(rid(), "hello")

        val row = db.messageDao().getById(id)
        assertNotNull(row)
        row!!
        assertEquals(QUEUED.toDouble(), row.status)
        assertEquals("hello", row.msg)
        assertEquals(rid(), row.rid)
        assertEquals(now, row.ts.toLong())
        assertEquals(now, row._updated_at.toLong())
        assertEquals(userJson, row.u)
        assertEquals("[]", row.mentions)
        assertEquals("", row.alias)
        assertEquals("[]", row.parse_urls)
        assertNull(row.md) // RN rec.md = '' → 可空列落 null

        frozen.shutdown()
    }

    // ---- wire：POST /api/v1/chat.sendMessage {message:{_id,rid,msg}}，md 省略 ----

    @Test
    fun `request body is RN chat sendMessage with md omitted`() = runBlocking {
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, SENT.toDouble())

        val req = server.takeRequest()
        assertEquals("/api/v1/chat.sendMessage", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"message":{"_id":"$id","rid":"r1","msg":"hello"}}""", req.body.readUtf8())
    }

    // ---- happy path：QUEUED→SENDING→SENT（同 id echo） ----

    @Test
    fun `happy path marks SENDING at request time then SENT`() = runBlocking {
        var sendingSeen: Double? = null
        respond = { body ->
            // 响应前此刻：行必须已标 SENDING（RN sendOneText :277 先标再调 API）
            val rowId = msgField(body, "_id")!!
            val deadline = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < deadline) {
                val status = runBlocking { db.messageDao().getById(rowId) }?.status
                if (status == SENDING.toDouble()) {
                    sendingSeen = status
                    break
                }
                Thread.sleep(5)
            }
            echoSuccess(body)
        }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, SENT.toDouble())

        assertEquals(SENDING.toDouble(), sendingSeen)
        assertTrue(backoffs.isEmpty())
    }

    // ---- serverId 迁移（序一：echo 后到，temp 全字段迁建） ----

    @Test
    fun `server ignores client id migrates temp row to serverId`() = runBlocking {
        respond = { _ -> MockResponse().setBody("""{"success":true,"message":{"_id":"srv-1"}}""") }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus("srv-1", SENT.toDouble())

        assertNull(db.messageDao().getById(id)) // temp 行已删
        val row = db.messageDao().getById("srv-1")!!
        assertEquals("hello", row.msg)
        assertEquals(rid(), row.rid)
        assertEquals(now, row.ts.toLong()) // 全字段迁建：ts 沿用 temp 行
        assertEquals(now, row._updated_at.toLong()) // messageUpdatedAt 刷新（RN :539）
        assertEquals(userJson, row.u)
        assertEquals(SENT.toDouble(), row.status)
        assertEquals(1, db.messageDao().getByRid(rid(), 10).size) // 无双条
    }

    // ---- serverId 迁移（序二：DDP echo 先到，复制 status 删 temp） ----

    @Test
    fun `ddp echo first merges status into existing row without duplicate`() = runBlocking {
        respond = { _ ->
            // echo 在响应前先到：走 T6 MessageUpsert 建 serverId 行（status 不写，恒 null）
            runBlocking {
                MessageUpsert.persistFromUnknown(
                    db,
                    Json.parseToJsonElement(
                        """{"_id":"srv-1","rid":"r1","msg":"hello","ts":1767225600000,"u":{"_id":"srv-user"}}""",
                    ),
                    rid(),
                )
            }
            // 服务端忽略客户端 _id，返回自己的 _id=srv-1
            MockResponse().setBody("""{"success":true,"message":{"_id":"srv-1"}}""")
        }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus("srv-1", SENT.toDouble())

        assertNull(db.messageDao().getById(id)) // temp 行已删
        val row = db.messageDao().getById("srv-1")!!
        assertEquals(SENT.toDouble(), row.status) // 迁移负责把状态带到 serverId 行
        assertEquals("hello", row.msg)
        assertEquals(1, db.messageDao().getByRid(rid(), 10).size) // 无双条
    }

    // ---- 直败：success:false / 4xx ----

    @Test
    fun `success false marks ERROR without retry`() = runBlocking {
        respond = { MockResponse().setBody("""{"success":false}""") }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, ERROR.toDouble())

        assertEquals(1, arrivals.size)
        assertTrue(backoffs.isEmpty())
    }

    @Test
    fun `http 400 marks ERROR without retry`() = runBlocking {
        respond = { MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}""") }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, ERROR.toDouble())

        assertEquals(1, arrivals.size)
        assertTrue(backoffs.isEmpty())
    }

    // ---- 重试：退避 1s/2s/4s，MAX_ATTEMPTS=3 ----

    @Test
    fun `io failure backs off 1s 2s 4s then recovers`() = runBlocking {
        var calls = 0
        respond = { body ->
            if (calls++ < 3) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else echoSuccess(body)
        }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, SENT.toDouble())

        assertEquals(4, arrivals.size) // 首次 + 3 次重试
        assertEquals(listOf(1_000L, 2_000L, 4_000L), backoffs.toList())
    }

    @Test
    fun `server 5xx exhausts retries then ERROR`() = runBlocking {
        respond = { MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""") }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, ERROR.toDouble())

        assertEquals(4, arrivals.size) // 首次 + MAX_ATTEMPTS(3) 重试后终态
        assertEquals(listOf(1_000L, 2_000L, 4_000L), backoffs.toList())
    }

    // ---- resend：同 id 复用原 msg 推新 job ----

    @Test
    fun `resend reuses id and original msg as new job`() = runBlocking {
        respond = { MockResponse().setBody("""{"success":false}""") }
        val id = orchestrator.enqueueTextMessage(rid(), "hello")
        awaitStatus(id, ERROR.toDouble())

        respond = ::echoSuccess
        orchestrator.resend(id, rid(), "hello")
        awaitStatus(id, SENT.toDouble())

        assertEquals(2, arrivals.size)
        assertEquals("""{"message":{"_id":"$id","rid":"r1","msg":"hello"}}""", bodies.toList().last())
    }

    // ---- per-rid 串行 ----

    @Test
    fun `same rid messages are sent serially in enqueue order`() = runBlocking {
        respond = { body ->
            if (msgField(body, "msg") == "m1") {
                Thread.sleep(150) // 拖住第一条：第二条必须等它完成
            }
            echoSuccess(body)
        }
        val id1 = orchestrator.enqueueTextMessage(rid(), "m1")
        val id2 = orchestrator.enqueueTextMessage(rid(), "m2")
        awaitStatus(id1, SENT.toDouble())
        awaitStatus(id2, SENT.toDouble())

        assertEquals(listOf("m1", "m2"), bodies.map { msgField(it, "msg") })
        // 第二条请求到达不早于第一条响应拖工期：串行非并发
        assertTrue(arrivals.toList()[1] - arrivals.toList()[0] >= 100)
    }

    // ---- 单例 + reset ----

    @Test
    fun `singleton is reused, reset rebuilds, anonymous fallback`() = runBlocking {
        val dbManager = DatabaseManager(context)
        dbManager.switchDatabase(DatabaseManager.PRELOGIN_NORMALIZED)
        val store = AuthSessionStore(InMemoryKvStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val s1 = getSendOrchestrator(dbManager, newSdk(), store, scope)
        assertTrue(s1 === getSendOrchestrator(dbManager, newSdk(), store, scope))

        resetSendOrchestrator()
        assertFalse(s1 === getSendOrchestrator(dbManager, newSdk(), store, scope))

        // 未登录兜底 anonymous（RN getSendOrchestrator :564-567）：经 active 库行 u JSON 验证
        val anon = getSendOrchestrator(dbManager, newSdk(), store, scope)
        val id = anon.enqueueTextMessage(rid(), "hi")
        val row = awaitStatus(dbManager.active.messageDao(), id, SENT.toDouble())
        assertEquals("""{"_id":"anonymous","username":"anonymous"}""", row.u)

        // 已登录会话 → u 取持久化用户（authStore 等价）
        resetSendOrchestrator()
        store.save(AuthSession(token = "t", user = AuthUser(id = "u9", username = "bob", name = "Bob"), serverUrl = "s"))
        val s2 = getSendOrchestrator(dbManager, newSdk(), store, scope)
        val id2 = s2.enqueueTextMessage(rid(), "hi")
        val row2 = awaitStatus(dbManager.active.messageDao(), id2, SENT.toDouble())
        assertEquals("""{"_id":"u9","username":"bob","name":"Bob"}""", row2.u)

        anon.shutdown()
        s2.shutdown()
        resetSendOrchestrator()
        dbManager.resetAll()
    }
}
