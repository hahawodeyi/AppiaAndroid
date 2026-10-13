package cn.appia.im.feature.mail

import android.util.Base64 as AndroidBase64
import cn.appia.im.core.mail.SendMailAttachment
import java.io.File

/**
 * 撰写附件内联（mailComposeAttachments.ts 移植——坑 12）：
 * - ≤10 个附件、总 ≤25MB
 * - base64 内联 JSON 上行（RN readFile('base64') 全量读——Android 用 3KB 分块流式编码，
 *   内存峰值 = 单块；总大小上限已挡住 payload 失控）
 */

const val MAX_MAIL_ATTACHMENT_COUNT = 10
const val MAX_MAIL_ATTACHMENT_TOTAL_BYTES = 25L * 1024 * 1024

/** 屏侧附件条目（选完文件即持 uri/名字；发送时统一转 SendMailAttachment）。 */
data class MailComposeAttachmentFile(
    val uri: String,
    val name: String,
    val type: String? = null,
    val size: Long? = null,
)

/** mailComposeAttachments.ts estimateBytesFromBase64：padding 校正的字节数估算。 */
fun estimateBytesFromBase64(base64: String): Long {
    val padding = when {
        base64.endsWith("==") -> 2L
        base64.endsWith("=") -> 1L
        else -> 0L
    }
    return (base64.length * 3L) / 4L - padding
}

private fun fileForUri(uri: String): File = File(java.net.URI(uri))

/** 上限预检（选文件后即时反馈）：数量 >10 或预估总量 >25MB 抛 [MailComposeAttachmentException]。 */
fun checkMailAttachmentLimits(files: List<MailComposeAttachmentFile>) {
    if (files.size > MAX_MAIL_ATTACHMENT_COUNT) {
        throw MailComposeAttachmentException("Maximum $MAX_MAIL_ATTACHMENT_COUNT attachments allowed")
    }
    val total = files.sumOf { it.size ?: 0L }
    if (total > MAX_MAIL_ATTACHMENT_TOTAL_BYTES) {
        throw MailComposeAttachmentException("Total attachment size exceeds 25 MB")
    }
}

/**
 * 分块编码器接缝（ponytail：android.util.Base64 在 JVM 单测是 stub 返回 null——
 * 测试注 java.util 实现；运行时默认 android.util NO_WRAP，两者输出等价无换行）。
 */
internal var mailBase64Encoder: (ByteArray) -> String = { bytes ->
    AndroidBase64.encodeToString(bytes, AndroidBase64.NO_WRAP)
}

/**
 * mailComposeAttachments.ts buildSendMailAttachments：逐文件 base64 内联。
 * 分块编码（内存峰值单块 3KB 而非全文件；25MB 上限内 payload 已封顶）。
 * IO 异常统一转 [MailComposeAttachmentException]（屏层 toast 单口）。
 */
fun buildSendMailAttachments(files: List<MailComposeAttachmentFile>): List<SendMailAttachment> {
    if (files.isEmpty()) return emptyList()
    checkMailAttachmentLimits(files)

    var totalBytes = files.sumOf { it.size ?: 0L }
    val attachments = mutableListOf<SendMailAttachment>()
    for (file in files) {
        val f = fileForUri(file.uri)
        val resolvedSize = file.size ?: f.length().takeIf { it >= 0 }
        val content = try {
            encodeFileToBase64(f)
        } catch (e: Exception) {
            throw MailComposeAttachmentException("Failed to read attachment: ${file.name}", e)
        }
        if (file.size == null && resolvedSize != null) {
            totalBytes += resolvedSize
            if (totalBytes > MAX_MAIL_ATTACHMENT_TOTAL_BYTES) {
                throw MailComposeAttachmentException("Total attachment size exceeds 25 MB")
            }
        }
        if (resolvedSize == null) {
            totalBytes += estimateBytesFromBase64(content)
            if (totalBytes > MAX_MAIL_ATTACHMENT_TOTAL_BYTES) {
                throw MailComposeAttachmentException("Total attachment size exceeds 25 MB")
            }
        }
        attachments += SendMailAttachment(
            filename = file.name,
            contentType = file.type ?: "application/octet-stream",
            content = content,
        )
    }
    return attachments
}

/** 分块流式 base64（3KB 原始字节/块）。 */
private fun encodeFileToBase64(file: File): String {
    val sb = StringBuilder((file.length() * 4L / 3L + 16).toInt().coerceAtMost(Int.MAX_VALUE - 8))
    val buffer = ByteArray(3 * 1024)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sb.append(mailBase64Encoder(if (read == buffer.size) buffer else buffer.copyOf(read)))
        }
    }
    return sb.toString()
}

/** 附件内联失败统一异常（屏层 toast 单口）。 */
class MailComposeAttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause)
