package cn.appia.im.core.media

import cn.appia.im.core.messaging.RoomHistoryRepository
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * UploadApi wire 测试（MockWebServer，multipart 请求体逐 part 断言）：
 * 单文件带 messageId/ts/localPath（msg/md 条件上 wire）、多文件 isMultiAttachment 形态、
 * 响应 fileId 三形态解析、进度序列、multiAttachments body（含 append messageId）。
 * multipart 解析用 ISO-8859-1 保字节回读（文件内容二进制安全）。
 */
class UploadApiTest {

    private val server = MockWebServer()

    @Volatile
    private var respond: (RecordedRequest) -> MockResponse = { ok() }

    private lateinit var sdk: RocketSdk
    private lateinit var uploadFile: File

    private val fileBytes = byteArrayOf(1, 2, 3, 4, 5)

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
        uploadFile = File.createTempFile("upload-test", ".jpg").apply { writeBytes(fileBytes) }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        uploadFile.delete()
        FileUploadProgress.clear()
    }

    private fun ok(body: String = """{"success":true,"file":{"_id":"f1","size":5,"type":"image/jpeg","name":"a.jpg"},"message":{"_id":"srv-1"}}""") =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // ---- multipart 解析 ----

    private data class Part(
        val name: String,
        val filename: String?,
        val contentType: String?,
        val text: String,
    ) {
        val bytes: ByteArray get() = text.toByteArray(Charsets.ISO_8859_1)
    }

    private fun RecordedRequest.parts(): List<Part> {
        val raw = body.readByteArray().toString(Charsets.ISO_8859_1)
        val boundary = getHeader("Content-Type")!!.substringAfter("boundary=")
        return raw.split("--$boundary").drop(1).dropLast(1).map { chunk ->
            val section = chunk.removePrefix("\r\n").removeSuffix("\r\n")
            val headerEnd = section.indexOf("\r\n\r\n")
            val headers = section.substring(0, headerEnd)
            val disposition = headers.lineSequence().first { it.startsWith("Content-Disposition") }
            Part(
                name = disposition.substringAfter("name=\"").substringBefore("\""),
                filename = if (disposition.contains("filename=\"")) {
                    disposition.substringAfter("filename=\"").substringBefore("\"")
                } else {
                    null
                },
                contentType = headers.lineSequence()
                    .firstOrNull { it.startsWith("Content-Type") }?.substringAfter(": ")?.trim(),
                text = section.substring(headerEnd + 4),
            )
        }
    }

    private fun names(parts: List<Part>) = parts.map { it.name }

    // ---- 单文件 wire（RN upload.ts:284-313 单文件分支） ----

    @Test
    fun `single file sends file messageId ts localPath parts without msg md`() = runBlocking {
        val res = UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a.jpg", type = "image/jpeg", size = 5L, localPath = uploadFile.absolutePath),
            messageId = "temp-1",
            nowIso = { "2026-09-18T00:00:00.000Z" },
        )

        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.upload/r1", req.path)
        val parts = req.parts()
        assertEquals(listOf("file", "messageId", "ts", "localPath"), names(parts))
        val file = parts[0]
        assertEquals("a.jpg", file.filename)
        assertEquals("image/jpeg", file.contentType)
        assertTrue(file.bytes.contentEquals(fileBytes))
        assertEquals("temp-1", parts[1].text)
        assertEquals("2026-09-18T00:00:00.000Z", parts[2].text)
        assertEquals(uploadFile.absolutePath, parts[3].text)
        // 响应解析：file._id + message._id
        assertEquals("f1", res.fileId)
        assertEquals("srv-1", res.messageId)
    }

    @Test
    fun `single file with msg adds msg and md parts`() = runBlocking {
        UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a.jpg", type = "image/jpeg", localPath = uploadFile.absolutePath),
            messageId = "temp-1",
            msg = "hi",
            md = buildJsonObject { put("type", "doc") },
            nowIso = { "2026-09-18T00:00:00.000Z" },
        )

        val parts = server.takeRequest().parts()
        assertEquals(listOf("file", "messageId", "ts", "localPath", "msg", "md"), names(parts))
        assertEquals("hi", parts[4].text)
        assertEquals("""{"type":"doc"}""", parts[5].text)
    }

    @Test
    fun `single file md omitted when msg empty like RN`() = runBlocking {
        UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a.jpg", type = "image/jpeg", localPath = uploadFile.absolutePath),
            messageId = "temp-1",
            msg = null,
            md = buildJsonObject { put("type", "doc") },
            nowIso = { "2026-09-18T00:00:00.000Z" },
        )

        assertEquals(
            listOf("file", "messageId", "ts", "localPath"),
            names(server.takeRequest().parts()),
        )
    }

    // ---- 多文件 wire（RN :298-299 isMultiAttachment 分支） ----

    @Test
    fun `multi attachment sends file plus isMultiAttachment only`() = runBlocking {
        // 纯 file 形态响应（RN UploadResponse 第二分支）：无 message → messageId null
        respond = { ok("""{"success":true,"file":{"_id":"f2","size":5,"type":"image/png","name":"b.png"}}""") }
        val res = UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "b.png", type = "image/png", localPath = uploadFile.absolutePath),
            isMultiAttachment = true,
        )

        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.upload/r1", req.path)
        val parts = req.parts()
        assertEquals(listOf("file", "isMultiAttachment"), names(parts))
        assertEquals("true", parts[1].text)
        assertEquals("b.png", parts[0].filename)
        assertNull(res.messageId)
        assertEquals("f2", res.fileId)
    }

    // ---- 响应解析三形态（RN :339-368） ----

    @Test
    fun `fileId falls back to top level _id then message file forms`() = runBlocking {
        // 顶层 _id 形态
        respond = { ok("""{"success":true,"_id":"top-1"}""") }
        val r1 = UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
            messageId = "m",
        )
        assertEquals("top-1", r1.fileId)

        // message.file._id 形态（服务端忽略客户端 messageId 时 file 挂 message 下）
        respond = { ok("""{"success":true,"message":{"_id":"srv-9","file":{"_id":"mf-1"}}}""") }
        val r2 = UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
            messageId = "m",
        )
        assertEquals("mf-1", r2.fileId)
        assertEquals("srv-9", r2.messageId)

        // message.files[0]._id 形态
        respond = { ok("""{"success":true,"message":{"_id":"srv-8","files":[{"_id":"fs-1"}]}}""") }
        val r3 = UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
            messageId = "m",
        )
        assertEquals("fs-1", r3.fileId)
    }

    @Test
    fun `single file missing fileId returns empty fileId with messageId like RN`() = runBlocking {
        respond = { ok("""{"success":true,"message":{"_id":"srv-7"}}""") }
        val res = UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
            messageId = "m",
        )
        assertEquals("", res.fileId)
        assertEquals("srv-7", res.messageId)
    }

    @Test
    fun `multi attachment missing fileId throws like RN`() = runBlocking {
        respond = { ok("""{"success":true,"message":{"_id":"srv-7"}}""") }
        try {
            UploadApi.uploadFileForOrchestrator(
                sdk, "r1",
                LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
                isMultiAttachment = true,
            )
            throw AssertionError("expected ApiException")
        } catch (e: cn.appia.im.core.network.rest.ApiException) {
            assertEquals("[upload] response missing fileId", e.message)
            assertFalse(RoomHistoryRepository.isRetryableError(e)) // 无 status → 不重试（RN 同）
        }
    }

    // ---- HTTP 错误走 M1 拦截器：5xx 重试 / 4xx 直败 ----

    @Test
    fun `http errors surface as ApiException with retryable status semantics`() = runBlocking {
        respond = { MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""") }
        try {
            UploadApi.uploadFileForOrchestrator(
                sdk, "r1",
                LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
                messageId = "m",
            )
            throw AssertionError("expected ApiException")
        } catch (e: cn.appia.im.core.network.rest.ApiException) {
            assertEquals(500, e.status)
            assertTrue(RoomHistoryRepository.isRetryableError(e))
        }

        respond = { MockResponse().setResponseCode(400).setBody("""{"error":"bad"}""") }
        try {
            UploadApi.uploadFileForOrchestrator(
                sdk, "r1",
                LocalFileInput(name = "a", type = "t", localPath = uploadFile.absolutePath),
                messageId = "m",
            )
            throw AssertionError("expected ApiException")
        } catch (e: cn.appia.im.core.network.rest.ApiException) {
            assertEquals(400, e.status)
            assertFalse(RoomHistoryRepository.isRetryableError(e))
        }
    }

    // ---- 进度 ----

    @Test
    fun `progress ratios are monotonic ending at 1`() {
        // 4 块写完：8192*3 + 432/30000 —— 直接驱动 ProgressRequestBody 免网络时序
        val big = File.createTempFile("progress", ".bin").apply { writeBytes(ByteArray(30_000)) }
        val ratios = mutableListOf<Double>()
        val body = ProgressRequestBody(big, "application/octet-stream") { written, total ->
            ratios.add(written.toDouble() / total)
        }
        body.writeTo(Buffer())
        assertEquals(listOf(8192.0, 16384.0, 24576.0, 30000.0).map { it / 30000.0 }, ratios)
        big.delete()
    }

    @Test
    fun `upload reports progress ending at 1 through wire`() = runBlocking {
        val ratios = ConcurrentLinkedQueue<Double>()
        respond = { req ->
            // 响应前请求体已写完：进度应已达 1.0（RN uploadProgress 语义）
            assertTrue(ratios.lastOrNull() == null || ratios.lastOrNull()!! <= 1.0)
            ok()
        }
        UploadApi.uploadFileForOrchestrator(
            sdk, "r1",
            LocalFileInput(name = "a.jpg", type = "image/jpeg", localPath = uploadFile.absolutePath),
            messageId = "m",
            onProgress = { ratios.add(it) },
        )
        assertEquals(1.0, ratios.lastOrNull()!!, 1e-9)
    }

    // ---- multiAttachments（RN SendOrchestrator sdk.post 形态） ----

    @Test
    fun `multiAttachments posts rid fileIds msg md and append messageId`() = runBlocking {
        respond = { ok("""{"success":true,"messageId":"srv-9"}""") }
        val md = buildJsonObject { put("type", "doc") }
        val res = UploadApi.multiAttachments(
            sdk, "r1", listOf("f1", "f2"),
            msg = "hi", md = md, messageId = "srv-9",
        )

        val req = server.takeRequest()
        assertEquals("/api/v1/multiAttachments", req.path)
        assertEquals(
            """{"rid":"r1","fileIds":["f1","f2"],"msg":"hi","md":{"type":"doc"},"messageId":"srv-9"}""",
            req.body.readUtf8(),
        )
        // serverId 解析两形态
        assertEquals("srv-9", UploadApi.multiAttachmentsServerId(res))
        assertEquals(
            "m2",
            UploadApi.multiAttachmentsServerId(
                Json.parseToJsonElement("""{"message":{"_id":"m2"}}""").jsonObject,
            ),
        )
        assertNull(UploadApi.multiAttachmentsServerId(null))
        assertNull(
            UploadApi.multiAttachmentsServerId(
                Json.parseToJsonElement("""{"success":true}""").jsonObject,
            ),
        )
    }

    @Test
    fun `multiAttachments omits optional fields when absent like RN`() = runBlocking {
        respond = { ok("""{"success":true,"messageId":"m1"}""") }
        UploadApi.multiAttachments(sdk, "r1", listOf("f1"))
        assertEquals(
            """{"rid":"r1","fileIds":["f1"]}""",
            server.takeRequest().body.readUtf8(),
        )
    }
}
