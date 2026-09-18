package cn.appia.im.core.messaging

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.media.FileUploadProgress
import cn.appia.im.core.media.LocalFileInput
import cn.appia.im.core.messaging.MessageStatus.ERROR
import cn.appia.im.core.messaging.MessageStatus.QUEUED
import cn.appia.im.core.messaging.MessageStatus.SENT
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 文件消息链路（Robolectric + 内存 Room + MockWebServer）：
 * enqueueFileMessage 本地行（attachments JSON 列）、单文件 rooms.upload wire（messageId=tempId）、
 * 多文件 isMultiAttachment→multiAttachments、serverId 迁移复用、进度 keyed by tempId、
 * 失败重试（退避/append）、**成功路径禁写 attachments 形状**（RN SendOrchestrator.ts:384-391 长注释钉）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendOrchestratorFileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java).build()
    private val backoffs = ConcurrentLinkedQueue<Long>()

    /** (path, body) 到达序记录（RecordedRequest.body 只能读一次，dispatcher 统一读）。 */
    private val arrivals = ConcurrentLinkedQueue<Pair<String, String>>()

    @Volatile
    private var respond: (String, String) -> MockResponse = { _, body -> echoUpload(body) }

    private val now = 1_700_000_000_000L
    private val user = CurrentUser(_id = "u1", username = "alice", name = "Alice")

    private lateinit var orchestrator: SendOrchestrator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                arrivals.add(request.path.orEmpty() to body)
                return respond(request.path.orEmpty(), body)
            }
        }
        orchestrator = SendOrchestrator(
            db = db,
            sdk = newSdk(),
            currentUser = user,
            scope = scope,
            sleep = { ms -> backoffs.add(ms) },
            nowMs = { now },
        )
        FileUploadProgress.clear()
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        scope.cancel()
        db.close()
        runCatching { server.shutdown() }
        FileUploadProgress.clear()
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    /** 永不执行的调度器：消费协程冻结（QUEUED 行/初值可确定性断言，SendOrchestratorTest 同法）。 */
    private val frozenDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
    }

    private fun newFrozenOrchestrator(): SendOrchestrator =
        SendOrchestrator(
            db = db,
            sdk = newSdk(),
            currentUser = user,
            scope = CoroutineScope(SupervisorJob() + frozenDispatcher),
            sleep = { backoffs.add(it) },
            nowMs = { now },
        )

    /** 单文件 echo 响应：回显请求 messageId part（实测 Appia 忽略客户端 messageId 之外的分支）。 */
    private fun echoUpload(body: String): MockResponse {
        val mid = Regex("name=\"messageId\".*?\r\n\r\n([^\r\n]+)", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)
        return MockResponse().setBody(
            """{"success":true,"file":{"_id":"f-$mid"},"message":{"_id":"$mid"}}""",
        )
    }

    private fun bodies(path: String) = arrivals.map { it.first }.filter { it == path }

    private fun bodiesOf(path: String) = arrivals.filter { it.first == path }.map { it.second }

    private suspend fun awaitStatus(id: String, expected: Double): MessageEntity {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            db.messageDao().getById(id)?.let { if (it.status == expected) return it }
            delay(10)
        }
        throw AssertionError("row $id never reached status $expected; got ${db.messageDao().getById(id)}")
    }

    private fun file(name: String, path: String) =
        LocalFileInput(name = name, type = "image/jpeg", size = 5L, localPath = path)

    private fun tempUploadFile(tag: String) =
        File.createTempFile("orch-$tag", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    /** attachments JSON 数组第 index 项的字符串字段。 */
    private fun attField(row: MessageEntity, index: Int, key: String): String? =
        ((Json.parseToJsonElement(row.attachments!!) as JsonArray)[index] as JsonObject)[key]
            ?.let { it as? JsonPrimitive }?.content

    // ---- enqueue：QUEUED 行 + attachments JSON 列（RN enqueueFileMessage :128-182） ----

    @Test
    fun `enqueueFileMessage creates QUEUED row with attachments json and md`() = runBlocking<Unit> {
        val frozen = newFrozenOrchestrator()
        val f = tempUploadFile("enq")
        val id = frozen.enqueueFileMessage(
            "r1",
            listOf(file("a.jpg", f.absolutePath)),
            msg = "hello",
            md = buildJsonObject { put("type", "doc") },
        )

        val row = db.messageDao().getById(id)!!
        assertEquals(QUEUED.toDouble(), row.status)
        assertEquals("hello", row.msg)
        assertEquals("""{"type":"doc"}""", row.md)
        assertEquals("""{"_id":"u1","username":"alice","name":"Alice"}""", row.u)
        assertEquals(1, (Json.parseToJsonElement(row.attachments!!) as JsonArray).size)
        assertEquals("a.jpg", attField(row, 0, "name"))
        assertEquals("image/jpeg", attField(row, 0, "type"))
        assertEquals("pending", attField(row, 0, "uploadStatus"))
        assertEquals(f.absolutePath, attField(row, 0, "localPath"))
        assertNotNull(attField(row, 0, "id"))
        // 进度初值（RN enqueueFileMessage :170-174 emitProgress）
        assertEquals(
            FileUploadProgress.Data(totalFiles = 1, completedFiles = 0, currentFileProgress = 0.0),
            FileUploadProgress.flow(id).value,
        )
        f.delete()
        frozen.shutdown()
    }

    // ---- 单文件 happy path：禁写 attachments 形状（红线） ----

    @Test
    fun `single file success keeps attachments column untouched and marks SENT`() = runBlocking<Unit> {
        val f = tempUploadFile("single")
        val id = orchestrator.enqueueFileMessage("r1", listOf(file("a.jpg", f.absolutePath)))
        val jsonBefore = db.messageDao().getById(id)!!.attachments

        val row = awaitStatus(id, SENT.toDouble())

        // 红线：成功路径禁写 attachments 形状（RN :384-391）——列内容与入队时逐字节一致
        assertEquals(jsonBefore, row.attachments)
        assertEquals("pending", attField(row, 0, "uploadStatus"))
        // 单文件 wire：file + messageId(tempId) + ts + localPath，无 isMultiAttachment
        assertEquals(listOf("/api/v1/rooms.upload/r1"), arrivals.map { it.first })
        val raw = arrivals.first().second
        assertTrue(raw.contains("name=\"messageId\""))
        assertTrue(raw.contains(id))
        assertTrue(raw.contains("name=\"ts\""))
        assertTrue(raw.contains("name=\"localPath\""))
        assertTrue(!raw.contains("name=\"isMultiAttachment\""))
        f.delete()
    }

    @Test
    fun `single file wire carries msg and md parts when provided`() = runBlocking<Unit> {
        val f = tempUploadFile("msgmd")
        val id = orchestrator.enqueueFileMessage(
            "r1",
            listOf(file("a.jpg", f.absolutePath)),
            msg = "see attachment",
            md = buildJsonObject { put("type", "quote") },
        )
        awaitStatus(id, SENT.toDouble())

        val raw = arrivals.first().second
        assertTrue(raw.contains("name=\"msg\""))
        assertTrue(raw.contains("see attachment"))
        assertTrue(raw.contains("""{"type":"quote"}"""))
        f.delete()
    }

    // ---- 单文件迁移：服务端忽略 messageId → 复用 M2 migrateToServerId ----

    @Test
    fun `single file migrates temp row to server id preserving attachments column`() = runBlocking<Unit> {
        respond = { _, _ ->
            MockResponse().setBody("""{"success":true,"file":{"_id":"f1"},"message":{"_id":"srv-1"}}""")
        }
        val f = tempUploadFile("mig")
        val id = orchestrator.enqueueFileMessage("r1", listOf(file("a.jpg", f.absolutePath)))
        val jsonBefore = db.messageDao().getById(id)!!.attachments

        val row = awaitStatus("srv-1", SENT.toDouble())

        assertNull(db.messageDao().getById(id)) // temp 行已删（M2 迁移语义）
        assertEquals(jsonBefore, row.attachments) // 迁移全字段搬 attachments；echo 由服务端 DDP 覆盖
        assertEquals(1, db.messageDao().getByRid("r1", 10).size) // 无双条
        f.delete()
    }

    // ---- 进度 keyed by tempId ----

    @Test
    fun `enqueueFileMessage emits initial progress keyed by tempId`() = runBlocking<Unit> {
        val frozen = newFrozenOrchestrator()
        val f = tempUploadFile("prog0")
        val id = frozen.enqueueFileMessage("r1", listOf(file("a.jpg", f.absolutePath)))

        // 消费协程冻结：此刻 flow 值只能是 enqueue 的同步初值（RN :170-174）
        assertEquals(
            FileUploadProgress.Data(totalFiles = 1, completedFiles = 0, currentFileProgress = 0.0),
            FileUploadProgress.flow(id).value,
        )
        frozen.shutdown()
        f.delete()
    }

    @Test
    fun `progress cleared after completion`() = runBlocking<Unit> {
        val f = tempUploadFile("prog")
        val id = orchestrator.enqueueFileMessage("r1", listOf(file("a.jpg", f.absolutePath)))
        awaitStatus(id, SENT.toDouble())
        assertNull(FileUploadProgress.flow(id).value) // RN emitProgress(job.id, null)
        f.delete()
    }

    // ---- 多文件：逐个 isMultiAttachment → multiAttachments fileIds ----

    @Test
    fun `multi file uploads each then multiAttachments with fileIds and migrates`() = runBlocking<Unit> {
        var uploadCount = 0
        respond = { path, _ ->
            when {
                path.contains("rooms.upload") -> {
                    uploadCount += 1
                    MockResponse().setBody("""{"success":true,"file":{"_id":"f$uploadCount"}}""")
                }
                else -> MockResponse().setBody("""{"success":true,"messageId":"srv-multi"}""")
            }
        }
        val f1 = tempUploadFile("m1")
        val f2 = tempUploadFile("m2")
        val id = orchestrator.enqueueFileMessage(
            "r1", listOf(file("1.jpg", f1.absolutePath), file("2.jpg", f2.absolutePath)),
        )

        val row = awaitStatus("srv-multi", SENT.toDouble())

        assertNull(db.messageDao().getById(id))
        assertEquals(3, arrivals.size) // 2 upload + 1 multiAttachments
        // 每个 upload 都带 isMultiAttachment、不带 messageId
        val uploadBodies = bodiesOf("/api/v1/rooms.upload/r1")
        uploadBodies.forEach {
            assertTrue(it.contains("name=\"isMultiAttachment\""))
            assertTrue(!it.contains("name=\"messageId\""))
        }
        assertEquals(
            """{"rid":"r1","fileIds":["f1","f2"]}""",
            bodiesOf("/api/v1/multiAttachments").first(),
        )
        // 红线：迁移搬的是上传循环内的进度列（RN :363-365 每 file 成功后 updateAttachments），
        // multiAttachments 成功后无重写——最终形状与 RN 一致：uploaded+fileId
        assertEquals("uploaded", attField(row, 0, "uploadStatus"))
        assertEquals("f1", attField(row, 0, "fileId"))
        assertEquals("uploaded", attField(row, 1, "uploadStatus"))
        assertEquals("f2", attField(row, 1, "fileId"))
        f1.delete(); f2.delete()
    }

    // ---- 失败：uploadStatus=failed + ERROR + 进度清除 ----

    @Test
    fun `upload failure marks row error sets failed and clears progress`() = runBlocking<Unit> {
        respond = { _, _ -> MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""") }
        val f = tempUploadFile("fail")
        val id = orchestrator.enqueueFileMessage("r1", listOf(file("a.jpg", f.absolutePath)))
        val row = awaitStatus(id, ERROR.toDouble())

        assertEquals("failed", attField(row, 0, "uploadStatus"))
        assertEquals(listOf(1_000L, 2_000L, 4_000L), backoffs.toList()) // M2 retryPolicy 复用
        assertNull(FileUploadProgress.flow(id).value)
        assertEquals(4, bodies("/api/v1/rooms.upload/r1").size) // 首次 + 3 重试
        f.delete()
    }

    @Test
    fun `multi file partial failure keeps uploaded file and marks failed only on failed item`() =
        runBlocking<Unit> {
            var uploadCount = 0
            respond = { path, _ ->
                when {
                    path.contains("rooms.upload") -> {
                        uploadCount += 1
                        if (uploadCount == 1) {
                            MockResponse().setBody("""{"success":true,"file":{"_id":"f1"}}""")
                        } else {
                            MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                        }
                    }
                    else -> MockResponse().setBody("""{"success":true,"messageId":"srv-x"}""")
                }
            }
            val f1 = tempUploadFile("p1")
            val f2 = tempUploadFile("p2")
            val id = orchestrator.enqueueFileMessage(
                "r1", listOf(file("1.jpg", f1.absolutePath), file("2.jpg", f2.absolutePath)),
            )
            val row = awaitStatus(id, ERROR.toDouble())

            assertEquals("uploaded", attField(row, 0, "uploadStatus"))
            assertEquals("f1", attField(row, 0, "fileId"))
            assertEquals("failed", attField(row, 1, "uploadStatus"))
            assertTrue(bodiesOf("/api/v1/multiAttachments").isEmpty()) // 未到 multi 步
            f1.delete(); f2.delete()
        }

    // ---- retryFile：非 append 全量 fileIds / append 带 messageId+失败项 ----

    @Test
    fun `retryFile after multi failure reuploads only failed then multiAttachments all fileIds`() =
        runBlocking<Unit> {
            var uploadCount = 0
            respond = { path, _ ->
                when {
                    path.contains("rooms.upload") -> {
                        uploadCount += 1
                        if (uploadCount == 1) {
                            MockResponse().setBody("""{"success":true,"file":{"_id":"f1"}}""")
                        } else {
                            MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                        }
                    }
                    else -> MockResponse().setBody("""{"success":true,"messageId":"srv-9"}""")
                }
            }
            val f1 = tempUploadFile("r1")
            val f2 = tempUploadFile("r2")
            val id = orchestrator.enqueueFileMessage(
                "r1", listOf(file("1.jpg", f1.absolutePath), file("2.jpg", f2.absolutePath)),
            )
            awaitStatus(id, ERROR.toDouble())
            val failedId = attField(db.messageDao().getById(id)!!, 1, "id")!!

            uploadCount = 1 // f1 已成功：第二轮只应重传 f2 —— 放行后续 upload
            respond = { path, _ ->
                when {
                    path.contains("rooms.upload") -> {
                        uploadCount += 1
                        MockResponse().setBody("""{"success":true,"file":{"_id":"f$uploadCount"}}""")
                    }
                    else -> MockResponse().setBody("""{"success":true,"messageId":"srv-9"}""")
                }
            }
            val uploadsBefore = bodies("/api/v1/rooms.upload/r1").size
            orchestrator.retryFile(id, failedId)
            awaitStatus("srv-9", SENT.toDouble())

            assertEquals(uploadsBefore + 1, bodies("/api/v1/rooms.upload/r1").size) // 只重传 f2
            assertTrue(
                bodiesOf("/api/v1/multiAttachments").first()
                    .contains(""""fileIds":["f1","f2"]"""), // 非 append：全量
            )
            f1.delete(); f2.delete()
        }

    @Test
    fun `retryFile append carries messageId and only failed fileIds`() = runBlocking<Unit> {
        var uploadCount = 0
        respond = { path, _ ->
            when {
                path.contains("rooms.upload") -> {
                    uploadCount += 1
                    if (uploadCount == 1) {
                        MockResponse().setBody("""{"success":true,"file":{"_id":"f1"}}""")
                    } else {
                        MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                    }
                }
                else -> MockResponse().setBody("""{"success":true}""")
            }
        }
        val f1 = tempUploadFile("a1")
        val f2 = tempUploadFile("a2")
        val id = orchestrator.enqueueFileMessage(
            "r1", listOf(file("1.jpg", f1.absolutePath), file("2.jpg", f2.absolutePath)),
        )
        awaitStatus(id, ERROR.toDouble())

        // 前态：服务端已有消息 srv-x（RN serverMessageIdByLocalId 记忆）
        orchestrator.serverMessageIdByLocalId[id] = "srv-x"
        val failedId = attField(db.messageDao().getById(id)!!, 1, "id")!!
        uploadCount = 1 // 第二轮放行 upload（只重传 f2）
        respond = { path, _ ->
            when {
                path.contains("rooms.upload") -> {
                    uploadCount += 1
                    MockResponse().setBody("""{"success":true,"file":{"_id":"f2"}}""")
                }
                else -> MockResponse().setBody("""{"success":true,"messageId":"srv-x"}""") // RN :431-438 append 亦须有 serverId
            }
        }
        orchestrator.retryFile(id, failedId)
        awaitStatus(id, SENT.toDouble()) // append 不迁移，原行 SENT

        assertEquals(
            """{"rid":"r1","fileIds":["f2"],"messageId":"srv-x"}""",
            bodiesOf("/api/v1/multiAttachments").first(),
        )
        assertNull(db.messageDao().getById("srv-x")) // 无新建行
        f1.delete(); f2.delete()
    }

    // ---- resend：文件行复用 attachments 列 ----

    @Test
    fun `file row resend reuses attachments and completes sent`() = runBlocking<Unit> {
        respond = { _, _ -> MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""") }
        val f = tempUploadFile("rs")
        val id = orchestrator.enqueueFileMessage("r1", listOf(file("a.jpg", f.absolutePath)))
        awaitStatus(id, ERROR.toDouble())
        val attachments = db.messageDao().getById(id)!!.attachments

        respond = { _, body -> echoUpload(body) }
        orchestrator.resend(id, "r1", "", attachments = attachments)
        awaitStatus(id, SENT.toDouble())

        assertEquals(attachments, db.messageDao().getById(id)!!.attachments) // 禁写红线同样适用 resend
        f.delete()
    }
}
