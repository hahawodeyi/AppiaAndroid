package cn.appia.im.feature.mail

import android.content.Context
import android.net.Uri
import android.os.Environment
import cn.appia.im.core.mail.AttachmentMeta
import cn.appia.im.core.mail.MailClient
import cn.appia.im.core.mail.normalizeMailMessageId
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 附件下载/缓存控制器（mailAttachmentLimits + mailAttachmentCache + downloadMailAttachment +
 * saveMailAttachmentToDevice 移植）：
 * - 缓存：`cacheDir/mail_attachments/{normalizedMsgId}_{part}/{filename}`（RN DocumentDir 同构）
 * - 超时：30s + size/512KB 每档 1s，上限 120s（resolveMailAttachmentFetchTimeoutMs）
 * - 预览分级：>25MB 仅下载 / >5MB 确认 / ≤5MB 直接（resolveMailAttachmentSizePolicy）
 * - 保存设备：DownloadManager → Download/appia + 通知（RN addAndroidDownloads 同义，
 *   复用 core AttachmentDownloader 落位）；缓存命中则直接复制
 */

/** mailAttachmentLimits.ts:1-2。 */
const val MAIL_ATTACHMENT_PREVIEW_WARN_BYTES = 5L * 1024 * 1024
const val MAIL_ATTACHMENT_PREVIEW_MAX_BYTES = 25L * 1024 * 1024

/** mailAttachmentLimits.ts:7-16 三档策略。 */
enum class MailAttachmentSizePolicy { ALLOW_PREVIEW, CONFIRM_PREVIEW, DOWNLOAD_ONLY }

fun resolveMailAttachmentSizePolicy(sizeBytes: Long): MailAttachmentSizePolicy = when {
    sizeBytes > MAIL_ATTACHMENT_PREVIEW_MAX_BYTES -> MailAttachmentSizePolicy.DOWNLOAD_ONLY
    sizeBytes > MAIL_ATTACHMENT_PREVIEW_WARN_BYTES -> MailAttachmentSizePolicy.CONFIRM_PREVIEW
    else -> MailAttachmentSizePolicy.ALLOW_PREVIEW
}

/** mailAttachmentLimits.ts:18-21：30s + size/512KB 档位秒数，上限 120s。 */
fun resolveMailAttachmentFetchTimeoutMs(sizeBytes: Long): Long {
    val sizeBonus = (sizeBytes / (512L * 1024)) * 1000
    return minOf(120_000L, 30_000L + sizeBonus)
}

/** mailAttachmentCache.ts:8-9 buildMailAttachmentCacheKey：`{normalizedMsgId}_{part}`。 */
fun buildMailAttachmentCacheKey(messageId: String, part: String): String =
    "${normalizeMailMessageId(messageId)}_$part"

/** mailAttachmentCache.ts:11-18：cacheDir/mail_attachments/{key}/{sanitized filename}。 */
fun buildMailAttachmentCachePath(cacheRoot: File, messageId: String, part: String, filename: String): File {
    val cacheKey = buildMailAttachmentCacheKey(messageId, part)
    return File(File(cacheRoot, "mail_attachments/$cacheKey"), sanitizeMailDownloadFileName(filename))
}

/** mailAttachmentCache.ts:20-36 resolveCachedMailAttachmentPath：存在且 size>0 才命中。 */
fun resolveCachedMailAttachmentPath(cacheRoot: File, messageId: String, attachment: AttachmentMeta): File? {
    val path = buildMailAttachmentCachePath(cacheRoot, messageId, attachment.part, attachment.filename)
    if (!path.isFile || path.length() <= 0L) return null
    return path
}

/** utils/fileDownload.ts sanitizeDownloadFileName：剥 query/hash，空 → 'document'。 */
fun sanitizeMailDownloadFileName(fileName: String): String {
    val trimmed = fileName.trim()
    if (trimmed.isEmpty()) return "document"
    return trimmed.substringBefore("?").substringBefore("#").trim().ifEmpty { "document" }
}

/** downloadMailAttachment.ts buildMailAuthHeaders：X-Auth-Token/X-User-Id（空跳过）。 */
fun buildMailAuthHeaders(client: MailClient): Map<String, String> {
    val auth = client.authProvider() ?: return emptyMap()
    return buildMap {
        if (auth.authToken.isNotBlank()) put("X-Auth-Token", auth.authToken)
        if (auth.userId.isNotBlank()) put("X-User-Id", auth.userId)
    }
}

/**
 * downloadMailAttachment.ts:23-58：缓存命中直返；否则 GET（带 auth 头 + 分级超时）落缓存。
 * 非缓存复用的独立 OkHttpClient（大文件流式落盘，不占 MailClient 的短超时口）。
 */
fun downloadMailAttachment(
    context: Context,
    client: MailClient,
    messageId: String,
    attachment: AttachmentMeta,
): File {
    resolveCachedMailAttachmentPath(context.cacheDir, messageId, attachment)?.let { return it }

    val localPath = buildMailAttachmentCachePath(context.cacheDir, messageId, attachment.part, attachment.filename)
    localPath.parentFile?.mkdirs()

    val url = client.url("/messages/${normalizeMailMessageId(messageId)}/attachments/${attachment.part}")
    val timeoutMs = resolveMailAttachmentFetchTimeoutMs(attachment.size)
    val http = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(url).apply {
        buildMailAuthHeaders(client).forEach { (name, value) -> header(name, value) }
    }.build()

    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw MailAttachmentException("Mail attachment download failed: ${response.code}")
        }
        val body = response.body ?: throw MailAttachmentException("Mail attachment download failed: empty body")
        body.byteStream().use { input ->
            localPath.outputStream().use { output -> input.copyTo(output) }
        }
    }
    return localPath
}

/** saveMailAttachmentToDevice.ts：Download/appia 落盘（DownloadManager 通知）；缓存命中复制。 */
fun saveMailAttachmentToDevice(
    context: Context,
    client: MailClient,
    messageId: String,
    attachment: AttachmentMeta,
): File {
    val safeName = sanitizeMailDownloadFileName(attachment.filename)
    val deviceDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "appia")
    deviceDir.mkdirs()
    val devicePath = File(deviceDir, safeName)

    resolveCachedMailAttachmentPath(context.cacheDir, messageId, attachment)?.let { cached ->
        cached.copyTo(devicePath, overwrite = true)
        return devicePath
    }

    val url = client.url("/messages/${normalizeMailMessageId(messageId)}/attachments/${attachment.part}")
    val timeoutMs = resolveMailAttachmentFetchTimeoutMs(attachment.size)
    val http = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    val request = Request.Builder().url(url).apply {
        buildMailAuthHeaders(client).forEach { (name, value) -> header(name, value) }
    }.build()

    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw MailAttachmentException("Mail attachment download failed: ${response.code}")
        }
        val body = response.body ?: throw MailAttachmentException("Mail attachment download failed: empty body")
        body.byteStream().use { input ->
            devicePath.outputStream().use { output -> input.copyTo(output) }
        }
    }
    return devicePath
}

/** 附件下载失败统一异常（屏幕层 toast 文案用）。 */
class MailAttachmentException(message: String) : Exception(message)

/**
 * getMailAttachmentKind.ts + announcementAttachmentKind.ts：image/video/file 三分
 * （文件扩展名白名单；image → 应用内图片预览，video → 播放器，其余系统打开/文档预览）。
 */
enum class MailAttachmentKind { IMAGE, VIDEO, FILE }

private val MAIL_IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "bmp", "gif", "webp", "psd", "svg", "tiff", "heic", "apng",
)
private val MAIL_VIDEO_EXTS = setOf(
    "mp4", "mp3", "avi", "wmv", "mpg", "mpeg", "mov", "rm", "ram", "swf", "flv", "wma", "rmvb", "mkv", "webm",
)

private fun mailFileExtension(filename: String): String {
    val clean = filename.substringBefore("?").substringBefore("#")
    return clean.substringAfterLast('.').trim().lowercase()
}

fun getMailAttachmentKind(attachment: AttachmentMeta): MailAttachmentKind = when {
    mailFileExtension(attachment.filename) in MAIL_IMAGE_EXTS -> MailAttachmentKind.IMAGE
    mailFileExtension(attachment.filename) in MAIL_VIDEO_EXTS -> MailAttachmentKind.VIDEO
    else -> MailAttachmentKind.FILE
}

/** openMailAttachment.ts fileExtensionFrom（pdf 判定复用）。 */
fun mailAttachmentIsPdf(attachment: AttachmentMeta): Boolean = mailFileExtension(attachment.filename) == "pdf"

/**
 * openMailAttachment.ts toFileUri：File → content:// Uri（FileProvider）。
 * RN FileViewer 直接吃 file://，但 targetSdk 24+ FileUriExposedException 禁裸 file://
 * 出栈——缓存目录已挂 provider 路径（file_paths.xml cache-path mail_attachments/）。
 */
fun mailAttachmentFileUri(context: Context, file: File): Uri =
    androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file,
    )

/**
 * openMailAttachment.ts 移植：附件预览分发（image/video → 应用内路由由调用方处理；
 * 此处兜底 ACTION_VIEW 系统打开，RN FileViewer.open showOpenWithDialog 等义）。
 * mime 用附件 contentType（服务端判定）；无接收方返回 false 由屏层 toast。
 */
fun openMailAttachment(
    context: Context,
    file: File,
    attachment: AttachmentMeta,
): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(mailAttachmentFileUri(context, file), attachment.contentType.ifBlank { "*/*" })
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(android.content.Intent.createChooser(intent, attachment.filename))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
}
