package cn.appia.im.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件分流纯函数单测（对照 appiaMobile utils/attachment.ts / MessageAttachments 分发 /
 * buildDocPreviewParamsFromFileLink.test.ts / attachmentToMediaItem.test.ts）：
 * 本地形状判定、渲染种类、文件类型表、大小/尺寸计算、DocPreview 参数（URL 全走
 * AttachmentUrlFormatter——rc_uid/rc_token + file-upload→file-proxy）、图片预览条目。
 */
class AttachmentShapeTest {

    // ── isLocalAttachmentShape（RN RoomMessageRowBody 同名判定）──

    @Test
    fun `local attachment shape requires localPath and uploadStatus on every item`() {
        assertTrue(
            isLocalAttachmentShape(
                """[{"name":"a.png","type":"image/png","localPath":"/x/a.png","uploadStatus":"pending"}]""",
            ),
        )
        assertFalse(
            isLocalAttachmentShape("""[{"title":"a","title_link":"/file-upload/1/2/a.png"}]"""),
        )
        assertFalse(isLocalAttachmentShape("[]"))
        assertFalse(isLocalAttachmentShape("not-json"))
        assertFalse(isLocalAttachmentShape(null))
    }

    // ── parseServerAttachments / attachmentKind（RN 分发顺序）──

    @Test
    fun `parses server attachment fields`() {
        val raw = """
            [{"title":"doc.pdf","title_link":"/file-upload/u1/f1/doc.pdf","type":"file",
              "file_size":2048,"image_dimensions":{"width":800,"height":600},
              "thumb_url":"/thumb/t.png","description":"desc"}]
        """.trimIndent()
        val att = parseServerAttachments(raw).single()
        assertEquals("doc.pdf", att.title)
        assertEquals("/file-upload/u1/f1/doc.pdf", att.titleLink)
        assertEquals(2048L, att.fileSize)
        assertEquals(800 to 600, att.imageDimensions)
        assertEquals("/thumb/t.png", att.thumbUrl)
        assertEquals("desc", att.description)
    }

    @Test
    fun `bad attachments json degrades to empty list`() {
        assertTrue(parseServerAttachments("oops").isEmpty())
        assertTrue(parseServerAttachments(null).isEmpty())
    }

    @Test
    fun `kind follows RN dispatch order quote actions collapsible audio video image file`() {
        fun kindOf(json: String): AttachmentKind = attachmentKind(parseServerAttachments("[$json]").single())

        assertEquals(AttachmentKind.QUOTE, kindOf("""{"message_link":"/x"}"""))
        assertEquals(AttachmentKind.ACTIONS, kindOf("""{"actions":[{"type":"button"}]}"""))
        assertEquals(AttachmentKind.COLLAPSIBLE_QUOTE, kindOf("""{"collapsed":true}"""))
        assertEquals(AttachmentKind.AUDIO, kindOf("""{"audio_url":"/a.m4a"}"""))
        assertEquals(AttachmentKind.VIDEO, kindOf("""{"video_url":"/v.mp4"}"""))
        assertEquals(AttachmentKind.VIDEO, kindOf("""{"type":"video/mp4"}""")) // mime 前缀分支
        assertEquals(AttachmentKind.IMAGE, kindOf("""{"image_url":"/i.png"}"""))
        assertEquals(AttachmentKind.FILE, kindOf("""{"title":"a.zip"}"""))
    }

    // ── 文件类型表 / 大小 / 尺寸（RN fileType.ts fileSize.ts computeImageSize）──

    @Test
    fun `file info uses uppercase extension and category colors`() {
        assertEquals(FileInfo("PDF", "#FF7878"), getFileInfo("a.PDF"))
        assertEquals(FileInfo("DOCX", "#53B7F4"), getFileInfo("report.docx"))
        assertEquals(FileInfo("XLSX", "#53D39C"), getFileInfo("t.xlsx"))
        assertEquals(FileInfo("MP4", "#8B72F7"), getFileInfo("clip.mp4"))
        assertEquals(FileInfo("FILE", "#C7DADD"), getFileInfo("noext"))
    }

    @Test
    fun `format file size matches RN output`() {
        assertEquals("0 B", formatFileSize(0.0))
        assertEquals("512 B", formatFileSize(512.0))
        assertEquals("1.5 KB", formatFileSize(1536.0))
        assertEquals("1 MB", formatFileSize(1048576.0))
    }

    @Test
    fun `compute image size fits within 200x150`() {
        assertEquals(200 to 150, computeImageSize(null))
        assertEquals(200 to 150, computeImageSize(400 to 300))
        assertEquals(200 to 112, computeImageSize(800 to 450)) // 宽图
        assertEquals(112 to 150, computeImageSize(450 to 600)) // 竖图
    }

    // ── DocPreview 参数（URL 全走 AttachmentUrlFormatter；RN buildDocPreviewParamsFromFileLink）──

    @Test
    fun `doc preview params format url via formatter with file-proxy rewrite`() {
        val p = buildDocPreviewParamsFromFileLink(
            title = " ignored.docx ",
            fileLink = "https://server.com/file-upload/u1/f1/report.docx",
            fileUrl = null,
            userId = "u1",
            token = "tk",
            server = "https://server.com",
        )
        assertEquals(
            "https://server.com/file-proxy/u1/f1/report.docx?rc_uid=u1&rc_token=tk",
            p.downloadUrl,
        )
        assertEquals("docx", p.fileType)
        assertEquals("f1", p.fileId) // tail >= 3 → tail[1]
    }

    @Test
    fun `doc preview params fall back to title extension and link relative`() {
        val p = buildDocPreviewParamsFromFileLink(
            title = "meeting-notes.pdf",
            fileLink = "/file-upload/f1/meeting-notes.pdf",
            fileUrl = null,
            userId = "u1",
            token = "tk",
            server = "https://server.com",
        )
        assertEquals("pdf", p.fileType)
        assertEquals("f1", p.fileId) // tail 恰 2 段（fileId/name）→ tail[0]
        assertTrue(p.downloadUrl.contains("rc_uid=u1"))
    }

    @Test
    fun `cross origin link sets appiaBaseUrl and proxies`() {
        val p = buildDocPreviewParamsFromFileLink(
            title = "external.doc",
            fileLink = "https://appia.example.com/file-upload/u1/f1/a.doc",
            fileUrl = null,
            userId = "u1",
            token = "tk",
            server = "https://server.com",
        )
        assertEquals("https://appia.example.com/file-proxy/u1/f1/a.doc?rc_uid=u1&rc_token=tk", p.downloadUrl)
    }

    @Test
    fun `cross origin keeps port in origin comparison`() {
        // RN URL.origin 含 port：downloadUrl 必须保留非默认端口（请求打到正确 host:port）
        val p = buildDocPreviewParamsFromFileLink(
            title = "external.doc",
            fileLink = "https://appia.example.com:8443/file-upload/u1/f1/a.doc",
            fileUrl = null,
            userId = "u1",
            token = "tk",
            server = "https://appia.example.com",
        )
        assertEquals(
            "https://appia.example.com:8443/file-proxy/u1/f1/a.doc?rc_uid=u1&rc_token=tk",
            p.downloadUrl,
        )

        // 同源同端口：downloadUrl 仍走 RN 无条件 file-proxy 重写
        val same = buildDocPreviewParamsFromFileLink(
            title = "internal.doc",
            fileLink = "https://appia.example.com:8443/file-upload/u1/f1/b.doc",
            fileUrl = null,
            userId = "u1",
            token = "tk",
            server = "https://appia.example.com:8443",
        )
        assertEquals(
            "https://appia.example.com:8443/file-proxy/u1/f1/b.doc?rc_uid=u1&rc_token=tk",
            same.downloadUrl,
        )
    }

    // ── 图片网格 ↔ 预览页同源（评审 Critical：混合附件不得错位）──

    @Test
    fun `image grid aligns cells with viewer list in mixed attachments`() {
        val attachments = parseServerAttachments(
            """[
                {"title":"report.pdf","title_link":"/file-upload/u1/f1/report.pdf","type":"file"},
                {"title":"img.png","title_link":"/file-upload/u1/f1/img.png","image_url":"/file-upload/u1/f1/img.png"}
            ]""",
        )
        val images = attachments.filter { attachmentKind(it) == AttachmentKind.IMAGE }
        val grid = buildImageViewerGrid(images, "u1", "tk", "https://server.com")

        assertEquals(1, grid.cells.size)
        // 网格格子渲染的是图片 URL（评审前会取到 PDF 的 title_link）
        assertEquals(
            "https://server.com/file-upload/u1/f1/img.png?rc_uid=u1&rc_token=tk",
            grid.cells[0]?.url,
        )
        assertEquals(listOf(0), grid.cellToItem)
        assertEquals(1, grid.items.size)
        // 点击第 0 格 → 预览页起始项就是该图片
        assertEquals(
            "https://server.com/file-upload/u1/f1/img.png?rc_uid=u1&rc_token=tk",
            grid.items[grid.cellToItem[0]].url,
        )
    }

    @Test
    fun `image grid skips author attachments without displacing indices`() {
        val grid = buildImageViewerGrid(
            parseServerAttachments(
                """[
                    {"author_name":"bot","image_url":"/a.png"},
                    {"image_url":"/b.png"}
                ]""",
            ),
            "u1",
            "tk",
            "https://server.com",
        )
        assertNull(grid.cells[0])
        assertEquals(-1, grid.cellToItem[0])
        assertEquals(1, grid.items.size)
        assertEquals(0, grid.cellToItem[1]) // 跳过项不占 items 位 → b.png 是 items[0]
        assertEquals(
            "https://server.com/b.png?rc_uid=u1&rc_token=tk",
            grid.items[grid.cellToItem[1]].url,
        )
    }

    @Test
    fun `fileId falls back through downloadUrl then explicit fallback`() {
        val viaDownloadUrl = buildDocPreviewParamsFromFileLink(
            title = "a.doc",
            fileLink = null,
            fileUrl = "https://server.com/file-proxy/u9/f9/a.doc",
            userId = "u1",
            token = "tk",
            server = "https://server.com",
        )
        assertEquals("f9", viaDownloadUrl.fileId)

        val explicit = buildDocPreviewParamsFromFileLink(
            title = "a.doc",
            fileLink = "",
            fileUrl = "",
            userId = "u1",
            token = "tk",
            server = "https://server.com",
            fileIdFallback = "fallback-id",
        )
        assertEquals("fallback-id", explicit.fileId)
    }

    // ── 图片预览条目（RN attachmentToMediaItem）──

    private fun viewerImages(json: String): List<ViewerImage> =
        buildViewerImages(parseServerAttachments(json), "u1", "tk", "https://server.com")

    @Test
    fun `viewer image prefers title_link and appends auth params`() {
        val items = viewerImages("""[{"title_link":"/file-upload/1/2/a.png","image_url":"/other.png"}]""")
        assertEquals(1, items.size)
        assertEquals("https://server.com/file-upload/1/2/a.png?rc_uid=u1&rc_token=tk", items[0].url)
    }

    @Test
    fun `viewer image uses thumb_url else base64 preview data uri`() {
        val withThumb = viewerImages("""[{"image_url":"/a.png","thumb_url":"/t.png","image_preview":"QUJD"}]""")
        assertEquals("https://server.com/t.png?rc_uid=u1&rc_token=tk", withThumb[0].thumbnailUrl)
        assertNull(withThumb[0].previewDataUri)

        val withPreview = viewerImages("""[{"image_url":"/a.png","image_preview":"QUJD","image_type":"image/png"}]""")
        assertEquals("data:image/png;base64,QUJD", withPreview[0].previewDataUri)
        assertNull(withPreview[0].thumbnailUrl)
    }

    @Test
    fun `viewer image skips author attachments and url-less entries`() {
        val items = buildViewerImages(
            parseServerAttachments(
                """[{"author_name":"bot"},{"image_url":"/a.png"},{"title":"no urls"}]""",
            ),
            "u1",
            "tk",
            "https://server.com",
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `viewer video formats video url and thumb`() {
        val att = parseServerAttachments("""[{"video_url":"/file-upload/1/2/v.mp4","thumb_url":"/t.png","title":"clip"}]""")
            .single()
        val video = buildViewerVideo(att, "u1", "tk", "https://server.com")!!
        assertEquals("https://server.com/file-upload/1/2/v.mp4?rc_uid=u1&rc_token=tk", video.url)
        assertEquals("https://server.com/t.png?rc_uid=u1&rc_token=tk", video.thumbnailUrl)
        assertEquals("clip", video.title)
    }
}
