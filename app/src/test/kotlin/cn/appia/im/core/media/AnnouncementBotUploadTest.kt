package cn.appia.im.core.media

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * announcement.bot 专用上传 wire（RN uploadAnnouncementFile.test.ts 移植 + Android 侧补充）：
 * 端点 `/api/v1/admin/file/upload/announcement.bot` multipart（file part：filename/type）；
 * 响应 url 取 `url ?? file.url`；`/file-upload` → `/file-proxy` 改写；缺 url 抛。
 */
class AnnouncementBotUploadTest {

    private val server = MockWebServer()
    private lateinit var sdk: RocketSdk
    private lateinit var uploadFile: File

    @Before
    fun setUp() {
        server.start()
        sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
        uploadFile = File.createTempFile("ann", ".pdf")
        uploadFile.writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        uploadFile.delete()
    }

    @Test
    fun `uploads to announcement bot endpoint and returns file-proxy url`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"url":"${server.url("/").toString().trimEnd('/')}/file-upload/abc/file.pdf"}"""),
        )

        val url = UploadApi.uploadAnnouncementBot(sdk, uploadFile.absolutePath, "x.pdf", "application/pdf")

        val req = server.takeRequest()
        assertEquals("/api/v1/admin/file/upload/announcement.bot", req.path)
        assertEquals("tok", req.getHeader("X-Auth-Token"))
        assertEquals("uid", req.getHeader("X-User-Id"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"x.pdf\""))
        assertTrue(body.contains("application/pdf"))
        assertTrue(url.endsWith("/file-proxy/abc/file.pdf"))
    }

    @Test
    fun `falls back to nested file url`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"file":{"url":"https://s.test/file-upload/nested.png"}}"""))
        val url = UploadApi.uploadAnnouncementBot(sdk, uploadFile.absolutePath, "n.png", "image/png")
        assertEquals("https://s.test/file-proxy/nested.png", url)
    }

    @Test
    fun `missing url throws`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        try {
            UploadApi.uploadAnnouncementBot(sdk, uploadFile.absolutePath, "x.pdf", "application/pdf")
            fail("expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("url"))
        }
    }

    @Test
    fun `empty file throws`() = runBlocking {
        val empty = File.createTempFile("empty", ".txt")
        try {
            UploadApi.uploadAnnouncementBot(sdk, empty.absolutePath, "e.txt", "text/plain")
            fail("expected exception")
        } catch (e: Exception) {
            // RN uploadAnnouncementBotFromUri size===0 抛 'empty file'
            assertTrue(e.message!!.contains("empty"))
        } finally {
            empty.delete()
        }
    }

    @Test
    fun `multipart body carries file bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"url":"https://s.test/file-proxy/f.bin"}"""))
        UploadApi.uploadAnnouncementBot(sdk, uploadFile.absolutePath, "x.pdf", "application/pdf")
        val req = server.takeRequest()
        // multipart 解析：boundary 后 part 头 + 原始字节（UploadApiTest 同口径）
        val rawStr = req.body.readByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(rawStr.contains(String(byteArrayOf(1, 2, 3), Charsets.ISO_8859_1)))
    }
}
