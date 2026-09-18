package cn.appia.im.feature.chat

import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T12 编辑链：buildOrderedFileIds 顺序与短路、服务端 files 水合、updateMessage/replace 双路 wire。 */
class MessageEditControllerTest {

    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** 无 DDP 的 REST 会话（RecallActionsTest 同款）：updateMessage 走 HTTP method.call 回退路。 */
    private fun newController(): MessageEditController {
        server.start()
        val sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
        return MessageEditController(sdk)
    }

    private fun item(id: String, ready: Boolean = true, localPath: String? = "/tmp/f", fileId: String? = null) =
        PendingAttachment(
            id = id, name = "$id.png", type = "image/png", sourceUri = "file:///$id",
            source = "photo", localPath = localPath,
            prepareStatus = if (ready) PrepareStatus.READY else PrepareStatus.PREPARING,
            fileId = fileId,
        )

    // ── buildOrderedFileIds（RN orderedAttachmentFileIds.ts 移植）──

    @Test
    fun `ordered file ids reuses fileId skips upload keeps order`() = runBlocking {
        var uploads = 0
        val result = buildOrderedFileIds(
            listOf(item("a", fileId = "srv-a"), item("b"), item("c", fileId = "srv-c")),
        ) { uploads++; "up-$uploads" }
        assertTrue(result is OrderedFileIdsResult.Ok)
        assertEquals(listOf("srv-a", "up-1", "srv-c"), (result as OrderedFileIdsResult.Ok).fileIds)
        assertEquals(1, result.uploadedCount)
    }

    @Test
    fun `ordered file ids short circuits on not ready item`() = runBlocking {
        val result = buildOrderedFileIds(listOf(item("a"), item("b", ready = false))) { "up" }
        assertTrue(result is OrderedFileIdsResult.Failed)
        assertEquals("b", (result as OrderedFileIdsResult.Failed).failedItemId)
    }

    @Test
    fun `ordered file ids upload failure reports failed item`() = runBlocking {
        val result = buildOrderedFileIds(listOf(item("a"), item("b"))) { if (it.name == "b.png") error("boom") else "up" }
        assertTrue(result is OrderedFileIdsResult.Failed)
        assertEquals("b", (result as OrderedFileIdsResult.Failed).failedItemId)
        assertEquals(1, (result as OrderedFileIdsResult.Failed).uploadedCount)
    }

    // ── 服务端 files 水合（编辑态附件条回填）──

    @Test
    fun `server files hydrate to pending rows with fileId`() {
        val message = MessageEntity(
            _id = "m1", rid = "r1", ts = 1.0, u = "{}", alias = "", parse_urls = "[]", _updated_at = 1.0,
            files = """[{"_id":"f1","name":"a.png","type":"image/png","size":123},{"bad":1}]""",
        )
        val rows = serverMessageToEditableFiles(message)
        assertEquals(1, rows.size)
        assertEquals("f1", rows[0].fileId)
        assertEquals("f1", rows[0].id)
        assertEquals("a.png", rows[0].name)
        assertEquals(123L, rows[0].size)
        assertEquals(PrepareStatus.READY, rows[0].prepareStatus)
        assertTrue(serverMessageToEditableFiles(message.copy(files = null)).isEmpty())
        assertTrue(serverMessageToEditableFiles(message.copy(files = "broken")).isEmpty())
    }

    // ── 提交双路 wire ──

    @Test
    fun `text edit calls updateMessage directly bypassing orchestrator`() = runBlocking {
        val controller = newController()
        server.enqueue(MockResponse().setBody("""{"result":{}}"""))
        val md = Json.parseToJsonElement("""{"blocks":[]}""")
        val result = controller.submit("r1", "m1", "new text", md, fileIds = null)
        assertTrue(result.saved)
        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/updateMessage", req.path)
        // REST 包 DDP 帧（RecallApiTest 同构）：{message: "<json 串>"}
        val frame = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["message"]!!
        .jsonPrimitive.content.let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("updateMessage", frame["method"]!!.jsonPrimitive.content)
        val arg = frame["params"]!!.jsonArray[0].jsonObject
        assertEquals("r1", arg["rid"]!!.jsonPrimitive.content)
        assertEquals("m1", arg["_id"]!!.jsonPrimitive.content)
        assertEquals("new text", arg["msg"]!!.jsonPrimitive.content)
        assertEquals("""{"blocks":[]}""", arg["md"].toString())
    }

    @Test
    fun `attachment edit posts multiAttachments replace with ordered fileIds`() = runBlocking {
        val controller = newController()
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        val result = controller.submit("r1", "m1", "txt", null, fileIds = listOf("f1", "f2"))
        assertTrue(result.saved)
        val req = server.takeRequest()
        assertEquals("/api/v1/multiAttachments.replace", req.path)
        val json = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("m1", json["messageId"]!!.jsonPrimitive.content)
        assertEquals("r1", json["rid"]!!.jsonPrimitive.content)
        assertEquals(listOf("f1", "f2"), json["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("txt", json["msg"]!!.jsonPrimitive.content)
    }
}
