package cn.appia.im.feature.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 附件上限/流式（坑 12）——文件落地临时目录走 buildSendMailAttachments 全链。 */
class MailComposeAttachmentsTest {

    init {
        // android.util.Base64 在 JVM 单测是 stub（返回 null）——注入 java.util 实现
        mailBase64Encoder = { bytes -> java.util.Base64.getEncoder().encodeToString(bytes) }
    }

    private fun tempFile(name: String, bytes: Int): Pair<File, String> {
        val f = File.createTempFile("mail-" + name.substringBeforeLast('.'), "." + name.substringAfterLast('.', "bin"))
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        return f to f.toURI().toString()
    }

    @Test
    fun `empty file list yields empty attachments`() {
        assertTrue(buildSendMailAttachments(emptyList()).isEmpty())
    }

    @Test
    fun `count over 10 rejected`() {
        val files = (1..11).map { i ->
            val (f, uri) = tempFile("f$i.bin", 1)
            MailComposeAttachmentFile(uri = uri, name = "f$i.bin", size = 1L)
        }
        val e = runCatching { buildSendMailAttachments(files) }.exceptionOrNull()
        assertTrue(e is MailComposeAttachmentException)
        assertEquals("Maximum 10 attachments allowed", e!!.message)
    }

    @Test
    fun `total size over 25MB rejected by precheck`() {
        val (f1, uri1) = tempFile("big1.bin", 1024)
        val files = listOf(
            MailComposeAttachmentFile(uri = uri1, name = "big1.bin", size = 13L * 1024 * 1024),
            MailComposeAttachmentFile(uri = "file:///nonexistent.bin", name = "big2.bin", size = 13L * 1024 * 1024),
        )
        val e = runCatching { buildSendMailAttachments(files) }.exceptionOrNull()
        assertTrue(e is MailComposeAttachmentException)
        assertEquals("Total attachment size exceeds 25 MB", e!!.message)
    }

    @Test
    fun `base64 content inline with chunked encoding matches whole-file encoding`() {
        val (f, uri) = tempFile("sample.bin", 3 * 1024 * 4 + 7) // 跨块 + 非整块尾
        val attachments = buildSendMailAttachments(
            listOf(MailComposeAttachmentFile(uri = uri, name = "sample.bin", type = "application/octet-stream", size = f.length())),
        )
        assertEquals(1, attachments.size)
        assertEquals("sample.bin", attachments[0].filename)
        assertEquals("application/octet-stream", attachments[0].contentType)
        val expected = java.util.Base64.getEncoder().encodeToString(f.readBytes())
        assertEquals(expected, attachments[0].content)
        f.delete()
    }

    @Test
    fun `missing size resolves via file and enforces cap`() {
        val (f, uri) = tempFile("nosize.bin", 128)
        val files = listOf(
            // size=null → 走文件实长 + 25MB 累计校验路径
            MailComposeAttachmentFile(uri = uri, name = "nosize.bin", size = null),
        )
        val attachments = buildSendMailAttachments(files)
        assertEquals(1, attachments.size)
        // base64 长度对 128 字节 = ceil(128/3)*4 ≈ 172
        assertEquals(172, attachments[0].content.length)
        f.delete()
    }

    @Test
    fun `estimate bytes from base64 corrects padding`() {
        // 2 字节 → base64 4 字符（1 个 '=' padding）→ 估算 2
        assertEquals(2L, estimateBytesFromBase64(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2))))
        // 1 字节 → 4 字符（2 个 '='）→ 估算 1
        assertEquals(1L, estimateBytesFromBase64(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1))))
        // 3 字节 → 4 字符（无 padding）→ 估算 3
        assertEquals(3L, estimateBytesFromBase64(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `unreadable file surfaces MailComposeAttachmentException`() {
        val e = runCatching {
            buildSendMailAttachments(
                listOf(MailComposeAttachmentFile(uri = "file:///nonexistent-dir/x.bin", name = "x.bin", size = null)),
            )
        }.exceptionOrNull()
        assertTrue(e is MailComposeAttachmentException)
        assertEquals("Failed to read attachment: x.bin", e!!.message)
    }
}
