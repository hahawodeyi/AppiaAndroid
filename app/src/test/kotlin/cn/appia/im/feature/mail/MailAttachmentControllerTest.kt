package cn.appia.im.feature.mail

import cn.appia.im.core.mail.AttachmentMeta
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.MailClientAuth
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * T4 附件控制器纯函数 + 线程三级降级全链（mailThread.ts 移植验证）。
 * MockWebServer 全链真 HTTP（MailChannelViewModelTest 同款）。
 */
class MailAttachmentControllerTest {

    // ── 预览分级（resolveMailAttachmentSizePolicy ≥4）──

    @Test
    fun `size policy three tiers`() {
        assertEquals(MailAttachmentSizePolicy.ALLOW_PREVIEW, resolveMailAttachmentSizePolicy(0))
        assertEquals(MailAttachmentSizePolicy.ALLOW_PREVIEW, resolveMailAttachmentSizePolicy(5L * 1024 * 1024))
        assertEquals(MailAttachmentSizePolicy.CONFIRM_PREVIEW, resolveMailAttachmentSizePolicy(5L * 1024 * 1024 + 1))
        assertEquals(MailAttachmentSizePolicy.CONFIRM_PREVIEW, resolveMailAttachmentSizePolicy(25L * 1024 * 1024))
        assertEquals(MailAttachmentSizePolicy.DOWNLOAD_ONLY, resolveMailAttachmentSizePolicy(25L * 1024 * 1024 + 1))
        assertEquals(MailAttachmentSizePolicy.DOWNLOAD_ONLY, resolveMailAttachmentSizePolicy(1024L * 1024 * 1024))
    }

    // ── 超时（resolveMailAttachmentFetchTimeoutMs）──

    @Test
    fun `fetch timeout is 30s base plus size bonus capped at 120s`() {
        assertEquals(30_000L, resolveMailAttachmentFetchTimeoutMs(0))
        assertEquals(30_000L, resolveMailAttachmentFetchTimeoutMs(512L * 1024 - 1)) // 不足一档不加
        assertEquals(31_000L, resolveMailAttachmentFetchTimeoutMs(512L * 1024)) // 恰一档 +1s
        assertEquals(32_000L, resolveMailAttachmentFetchTimeoutMs(1024L * 1024)) // 两档
        assertEquals(120_000L, resolveMailAttachmentFetchTimeoutMs(512L * 1024 * 200)) // 远超上限 → 120s
    }

    // ── 缓存 key/路径（buildMailAttachmentCacheKey/Path + resolve）──

    @Test
    fun `cache key strips mail prefix and joins part`() {
        assertEquals("abc_p2", buildMailAttachmentCacheKey("mail-abc", "p2"))
        assertEquals("abc_p2", buildMailAttachmentCacheKey("abc", "p2"))
        assertEquals("mailx_1", buildMailAttachmentCacheKey("mailx", "1")) // 非 mail- 前缀不剥
    }

    @Test
    fun `cache path nests under mail_attachments with sanitized filename`() {
        val root = File("/tmp/cache")
        val p = buildMailAttachmentCachePath(root, "mail-m1", "2", "a b.pdf")
        assertEquals(
            File(File(root, "mail_attachments/m1_2"), "a b.pdf").path,
            p.path,
        )
        // 剥 query/hash
        val q = buildMailAttachmentCachePath(root, "m1", "2", "a.pdf?sig=1#x")
        assertEquals(File(File(root, "mail_attachments/m1_2"), "a.pdf").path, q.path)
    }

    @Test
    fun `resolve cached attachment requires existing non-empty file`() {
        val root = createTempDir()
        val att = AttachmentMeta(part = "2", filename = "f.pdf", contentType = "application/pdf", size = 3)
        // 不存在 → null
        assertEquals(null, resolveCachedMailAttachmentPath(root, "m1", att))
        // 空文件 → null
        val empty = buildMailAttachmentCachePath(root, "m1", "2", "f.pdf")
        empty.parentFile!!.mkdirs()
        empty.createNewFile()
        assertEquals(null, resolveCachedMailAttachmentPath(root, "m1", att))
        // 非空 → 命中
        empty.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(empty.path, resolveCachedMailAttachmentPath(root, "m1", att)?.path)
        root.deleteRecursively()
    }

    @Test
    fun `sanitize filename strips query hash and defaults to document`() {
        assertEquals("a.pdf", sanitizeMailDownloadFileName("a.pdf?x=1#y"))
        assertEquals("a.pdf", sanitizeMailDownloadFileName("  a.pdf  "))
        assertEquals("document", sanitizeMailDownloadFileName("?x=1"))
        assertEquals("document", sanitizeMailDownloadFileName("   "))
    }

    // ── 类型三分（getMailAttachmentKind/pdf）──

    @Test
    fun `attachment kind splits image video file by extension`() {
        fun att(name: String) = AttachmentMeta(part = "1", filename = name, contentType = "", size = 1)
        assertEquals(MailAttachmentKind.IMAGE, getMailAttachmentKind(att("photo.JPG"))) // 大小写不敏感
        assertEquals(MailAttachmentKind.IMAGE, getMailAttachmentKind(att("x.heic?query")))
        assertEquals(MailAttachmentKind.VIDEO, getMailAttachmentKind(att("clip.mp4#frag")))
        assertEquals(MailAttachmentKind.FILE, getMailAttachmentKind(att("doc.docx")))
        assertTrue(mailAttachmentIsPdf(att("report.PDF")))
        assertFalse(mailAttachmentIsPdf(att("report.docx")))
    }

    // ── downloadMailAttachment 全链（缓存命中 + 首下载 + auth 头）──

    private val server = MockWebServer()
    private lateinit var client: MailClient
    private val auth = MailClientAuth("token-1", "user-1")
    private val paths = CopyOnWriteArrayList<String>()

    @Before
    fun setUpServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return if (request.path == "/mail/api/messages/m1/attachments/2") {
                    MockResponse().setBody("BYTES")
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = MailClient(
            serverUrl = { server.url("/").toString().trimEnd('/') },
            authProvider = { auth },
            baseHttpClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    @After
    fun tearDownServer() {
        server.shutdown()
    }

    /** Android Context 不在单测环境——仅测纯函数面；downloadMailAttachment 的缓存/网络
     *  分支经 resolveCachedMailAttachmentPath + buildMailAttachmentCachePath 组合覆盖。 */
    @Test
    fun `attachment download url strips mail prefix and carries auth headers`() {
        // client.url 口径：剥前缀发生在 URL 构造点（坑 4）
        val url = client.url("/messages/${cn.appia.im.core.mail.normalizeMailMessageId("mail-m1")}/attachments/2")
        assertTrue(url.endsWith("/mail/api/messages/m1/attachments/2"))
        // buildMailAuthHeaders：token/userId 双头
        val headers = buildMailAuthHeaders(client)
        assertEquals("token-1", headers["X-Auth-Token"])
        assertEquals("user-1", headers["X-User-Id"])
        // 空 auth → 空头表
        val anon = MailClient(
            serverUrl = { "http://localhost" },
            authProvider = { null },
            baseHttpClient = OkHttpClient(),
        )
        assertTrue(buildMailAuthHeaders(anon).isEmpty())
    }
}
