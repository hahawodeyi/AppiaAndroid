package cn.appia.im.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** 对照 appiaMobile/src/utils/formatAttachmentUrl.test.ts 逐用例转录。 */
class AttachmentUrlFormatterTest {

    @Test
    fun `returns empty string for null url`() {
        assertEquals("", AttachmentUrlFormatter.format(null, "user1", "token1", "https://server.com"))
    }

    @Test
    fun `removes hash from file-upload urls`() {
        assertEquals(
            "https://server.com/file-upload/abc123?rc_uid=user1&rc_token=token1",
            AttachmentUrlFormatter.format(
                "https://server.com/file-upload/abc#123", "user1", "token1", "https://server.com",
            ),
        )
    }

    @Test
    fun `keeps url already carrying auth params`() {
        assertEquals(
            "https://server.com/img.png?rc_uid=user1&rc_token=token1",
            AttachmentUrlFormatter.format(
                "https://server.com/img.png?rc_uid=user1&rc_token=token1", "user2", "token2", "https://server.com",
            ),
        )
    }

    @Test
    fun `adds auth params to absolute url`() {
        assertEquals(
            "https://server.com/img.png?rc_uid=user1&rc_token=token1",
            AttachmentUrlFormatter.format(
                "https://server.com/img.png", "user1", "token1", "https://server.com",
            ),
        )
    }

    @Test
    fun `adds auth params to relative url without proxy replace`() {
        // RN：无 appiaBaseUrl 的相对路径只补参数，不替换 file-upload
        assertEquals(
            "https://server.com/file-upload/abc?rc_uid=user1&rc_token=token1",
            AttachmentUrlFormatter.format("/file-upload/abc", "user1", "token1", "https://server.com"),
        )
    }

    @Test
    fun `uses appia base url for external relative file links`() {
        assertEquals(
            "https://external.test/file-proxy/room1/f1/report.docx?rc_uid=user1&rc_token=token1",
            AttachmentUrlFormatter.format(
                "/file-upload/room1/f1/report.docx", "user1", "token1", "https://local.test", "https://external.test",
            ),
        )
    }

    @Test
    fun `replaces file-upload with file-proxy for external absolute links`() {
        assertEquals(
            "https://external.test/file-proxy/room1/f1/report.docx?rc_uid=user1&rc_token=token1",
            AttachmentUrlFormatter.format(
                "https://external.test/file-upload/room1/f1/report.docx",
                "user1", "token1", "https://local.test", "https://external.test",
            ),
        )
    }

    @Test
    fun `formatAppiaUrl prefixes https to bare domain`() {
        assertEquals("https://appia.example.com", AttachmentUrlFormatter.formatAppiaUrl("appia.example.com"))
        assertEquals("http://intranet", AttachmentUrlFormatter.formatAppiaUrl("http://intranet"))
    }

    @Test
    fun `encodeUri matches JS encodeURI semantics`() {
        assertEquals("a%20b", AttachmentUrlFormatter.encodeUri("a b"))
        // 非 ASCII 按 UTF-8 百分号编码；URI 保留字符原样（用例字面量为「文件.png」的转义形态）
        val cjkName = "\u6587\u4EF6.png"
        assertEquals(
            "%E6%96%87%E4%BB%B6.png",
            AttachmentUrlFormatter.encodeUri(cjkName),
        )
        assertEquals("a=b&c:d?e/f;g,h", AttachmentUrlFormatter.encodeUri("a=b&c:d?e/f;g,h"))
    }
}
