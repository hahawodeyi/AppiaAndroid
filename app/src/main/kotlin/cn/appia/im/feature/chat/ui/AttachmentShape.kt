package cn.appia.im.feature.chat.ui

import cn.appia.im.core.media.AttachmentUrlFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * attachments 列渲染分流（对照 appiaMobile src/utils/attachment.ts + MessageAttachments 分发）：
 * - 本地形状（RN isLocalAttachmentShape，SendOrchestrator LocalAttachment JSON）→ 行内本地上传预览；
 * - 服务端形状（DDP 回推 title_link/image_url/video_url/...）→ 图片网格/视频块/音频行/文件卡。
 * 纯函数无 Compose 依赖（总纲 §4.3-1）。
 */

/** 引用块内嵌附件标记（RN isQuoteAttachment）；M3 不渲染引用附件（同 RN MessageAttachments skip）。 */
data class ParsedAttachment(
    val title: String? = null,
    val name: String? = null,
    val type: String? = null,
    val description: String? = null,
    val titleLink: String? = null,
    val imageUrl: String? = null,
    val imageType: String? = null,
    val imagePreview: String? = null,
    val imageDimensionsWidth: Int? = null,
    val imageDimensionsHeight: Int? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val thumbUrl: String? = null,
    val fileSize: Long? = null,
    val size: Double? = null,
    val authorName: String? = null,
    val messageLink: String? = null,
    val actions: Int = 0, // actions 数组长度（0 = 无）
    val collapsed: Boolean? = null,
    val appiaBaseUrl: String? = null,
) {
    val imageDimensions: Pair<Int, Int>? get() =
        if (imageDimensionsWidth != null && imageDimensionsHeight != null) {
            imageDimensionsWidth to imageDimensionsHeight
        } else {
            null
        }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
    is JsonPrimitive -> if (v is JsonNull) null else v.contentOrNull?.toDoubleOrNull()?.toInt()
    else -> null
}

/** RN parseAttachments：attachments 列 JSON → 数组；空/坏 JSON → []。 */
fun parseServerAttachments(raw: String?): List<ParsedAttachment> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val el = Json.parseToJsonElement(raw)
        (el as? JsonArray ?: return emptyList()).mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            ParsedAttachment(
                title = o.str("title"),
                name = o.str("name"),
                type = o.str("type"),
                description = o.str("description"),
                titleLink = o.str("title_link"),
                imageUrl = o.str("image_url"),
                imageType = o.str("image_type"),
                imagePreview = o.str("image_preview"),
                imageDimensionsWidth = (o["image_dimensions"] as? JsonObject)?.int("width"),
                imageDimensionsHeight = (o["image_dimensions"] as? JsonObject)?.int("height"),
                videoUrl = o.str("video_url"),
                audioUrl = o.str("audio_url"),
                thumbUrl = o.str("thumb_url"),
                fileSize = o.int("file_size")?.toLong(),
                size = (o["size"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toDoubleOrNull(),
                authorName = o.str("author_name"),
                messageLink = o.str("message_link"),
                actions = (o["actions"] as? JsonArray)?.size ?: 0,
                collapsed = (o["collapsed"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull,
                appiaBaseUrl = o.str("appiaBaseUrl"),
            )
        }
    }.getOrDefault(emptyList()) // Json 解析抛错兜底为空表（RN try/catch 同义）
}

/**
 * RN RoomMessageRowBody isLocalAttachmentShape：数组非空且每项 `localPath`/`uploadStatus`
 * 均为 string（SendOrchestrator LocalAttachment 本地形状，上传中行内预览专用）。
 */
fun isLocalAttachmentShape(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    return runCatching {
        val arr = Json.parseToJsonElement(raw) as? JsonArray ?: return false
        arr.isNotEmpty() && arr.all { item ->
            val o = item as? JsonObject ?: return@all false
            o.str("localPath") != null && o.str("uploadStatus") != null
        }
    }.getOrDefault(false)
}

/** 渲染分流种类（RN MessageAttachments others 分发顺序：quote→actions→collapsible→audio→video→file）。 */
enum class AttachmentKind { QUOTE, ACTIONS, COLLAPSIBLE_QUOTE, AUDIO, VIDEO, IMAGE, FILE }

fun attachmentKind(a: ParsedAttachment): AttachmentKind = when {
    a.messageLink != null -> AttachmentKind.QUOTE
    a.actions > 0 -> AttachmentKind.ACTIONS
    a.collapsed != null -> AttachmentKind.COLLAPSIBLE_QUOTE
    !a.audioUrl.isNullOrEmpty() -> AttachmentKind.AUDIO
    // RN isFileVideoAttachment：video_url 非空 || mime type video/ 前缀
    !a.videoUrl.isNullOrEmpty() || a.type?.lowercase()?.startsWith("video/") == true -> AttachmentKind.VIDEO
    !a.imageUrl.isNullOrEmpty() -> AttachmentKind.IMAGE
    else -> AttachmentKind.FILE
}

// ── 文件类型（RN utils/fileType.ts 逐项转录）──

data class FileInfo(val label: String, val color: String)

private val FILE_EXTENSION_MAP: Map<String, String> = buildMap {
    listOf(
        "excel" to "xls,xlsx,spreadsheet,csv",
        "pdf" to "pdf",
        "ppt" to "ppt,pptx,presentation",
        "txt" to "txt,document,text,md",
        "word" to "doc,docx,documentPro,rtf",
        "zip" to "zip,rar,tar.gz,7z,apk",
        "audio" to "m4a,mp3,aac,wav,ogg,flac,audio/aac",
        "image" to "png,jpg,jpeg,gif,bmp,webp,svg,heic,heif",
        "video" to "mp4,mov,mkv,avi,wmv,flv,webm,3gp",
        "folder" to "folder",
    ).forEach { (kind, names) -> names.split(",").forEach { put(it, kind) } }
}

private val FILE_COLOR_MAP = mapOf(
    "pdf" to "#FF7878", "ppt" to "#F98950", "word" to "#53B7F4", "excel" to "#53D39C",
    "txt" to "#4FD397", "zip" to "#FFC757", "audio" to "#FF7878", "video" to "#8B72F7",
    "image" to "#FFC757", "folder" to "#FFBA53", "unknown" to "#C7DADD",
)

private val FILE_DEFAULT_LABEL = mapOf(
    "pdf" to "PDF", "ppt" to "PPTX", "word" to "DOC", "excel" to "XLS", "txt" to "TXT",
    "zip" to "ZIP", "audio" to "AUDIO", "video" to "VIDEO", "image" to "IMG",
    "folder" to "DIR", "unknown" to "FILE",
)

/** RN getFileType：label = 大写后缀（未知类型用类别缺省标签）。 */
fun getFileInfo(fileName: String): FileInfo {
    val ext = fileName.trim().substringAfterLast('.', "").lowercase()
    val type = FILE_EXTENSION_MAP[ext] ?: "unknown"
    return FileInfo(
        label = ext.ifEmpty { null }?.uppercase() ?: FILE_DEFAULT_LABEL.getValue(type),
        color = FILE_COLOR_MAP.getValue(type),
    )
}

/** RN formatFileSize：B/KB/MB/GB/TB，JS parseFloat(x.toFixed(2)) 去尾零（512.0 → "512"）。 */
fun formatFileSize(bytes: Double): String {
    if (bytes == 0.0) return "0 B"
    val k = 1024.0
    val sizes = listOf("B", "KB", "MB", "GB", "TB")
    val i = (Math.log(bytes) / Math.log(k)).toInt().coerceIn(0, sizes.lastIndex)
    val v = bytes / Math.pow(k, i.toDouble())
    val rounded = kotlin.math.round(v * 100) / 100
    val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return "$text ${sizes[i]}"
}

/** RN MessageImage computeImageSize：按宽高比适配 maxW×maxH（无尺寸 → 恰好 maxW×maxH）。 */
fun computeImageSize(dimensions: Pair<Int, Int>?, maxWidth: Int = 200, maxHeight: Int = 150): Pair<Int, Int> {
    val (w, h) = dimensions ?: return maxWidth to maxHeight
    if (w <= 0 || h <= 0) return maxWidth to maxHeight
    val ratio = w.toDouble() / h
    val maxRatio = maxWidth.toDouble() / maxHeight
    return if (ratio > maxRatio) {
        maxWidth to (maxWidth / ratio).toInt()
    } else {
        (maxHeight * ratio).toInt() to maxHeight
    }
}

// ── DocPreview 路由参数（RN buildDocPreviewParamsFromFileLink 逐行为移植）──

data class DocPreviewParams(
    val title: String,
    val fileId: String,
    val downloadUrl: String,
    val fileType: String,
)

private fun fileExtensionFrom(value: String): String =
    value.substringBefore("?").substringBefore("#").substringAfterLast('.', "").lowercase()

/** RN extractFileIdFromFileLink：`/file-upload|file-proxy/...` 路径解析 rooms.preview 所需 fileId。 */
fun extractFileIdFromFileLink(fileLink: String): String {
    val clean = fileLink.substringBefore("?").substringBefore("#").trim()
    if (clean.isEmpty()) return ""

    var pathname = clean
    if (clean.startsWith("http://") || clean.startsWith("https://")) {
        pathname = runCatching { java.net.URI(clean).path }.getOrNull() ?: clean
    }

    val segments = pathname.split("/").filter { it.isNotEmpty() }
    val markerIdx = segments.indexOfFirst { it == "file-upload" || it == "file-proxy" }
    if (markerIdx == -1) return ""

    val tail = segments.drop(markerIdx + 1)
    return when {
        tail.size >= 3 -> tail.getOrNull(1).orEmpty()
        tail.size >= 2 -> tail[0]
        else -> tail.firstOrNull().orEmpty()
    }
}

/**
 * RN buildDocPreviewParamsFromFileLink：fileType = 链接后缀 || 标题后缀；
 * 链接 origin ≠ baseUrl origin 时该 origin 作 appiaBaseUrl（外链代理）；
 * downloadUrl = formatAttachmentUrl 输出再全量 `/file-upload → /file-proxy`；
 * fileId = 链接解析 || downloadUrl 解析 || fallback。
 */
fun buildDocPreviewParamsFromFileLink(
    title: String,
    fileLink: String?,
    fileUrl: String?,
    userId: String,
    token: String,
    server: String,
    fileIdFallback: String? = null,
    attachmentAppiaBaseUrl: String? = null,
): DocPreviewParams {
    val resolvedLink = fileLink?.trim().takeUnless { it.isNullOrEmpty() }
        ?: fileUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: ""
    val fileType = fileExtensionFrom(resolvedLink).ifEmpty { fileExtensionFrom(title) }

    var appia = attachmentAppiaBaseUrl?.trim()?.takeUnless { it.isNullOrEmpty() }
    if (appia == null && resolvedLink.startsWith("http")) {
        appia = runCatching {
            // RN URL.origin 含 port（同主机不同端口的自建/测试环境常见）→ 用 authority（host:port）
            val linkOrigin = java.net.URI(resolvedLink).let { u -> u.scheme?.let { s -> "$s://${u.authority}" } }
            val baseOrigin = java.net.URI(server).let { u -> u.scheme?.let { s -> "$s://${u.authority}" } }
            if (!linkOrigin.isNullOrEmpty() && linkOrigin != baseOrigin) linkOrigin else null
        }.getOrNull()
    }

    val rawUrl = AttachmentUrlFormatter.format(resolvedLink, userId, token, server, appia)
    val downloadUrl = rawUrl.replace("/file-upload", "/file-proxy")

    val fileId = extractFileIdFromFileLink(resolvedLink).ifEmpty {
        extractFileIdFromFileLink(downloadUrl).ifEmpty { fileIdFallback?.trim().orEmpty() }
    }
    return DocPreviewParams(title = title, fileId = fileId, downloadUrl = downloadUrl, fileType = fileType)
}

// ── 图片预览页条目（RN attachmentToMediaItem 语义）──

/** @Serializable：MediaViewerRoute 导航参数 JSON 串回环（LoginRoute.serversJson 同裁定）。 */
@kotlinx.serialization.Serializable
data class ViewerImage(
    val url: String,
    val thumbnailUrl: String? = null,
    /** 服务端 image_preview base64 占位（`data:<mime>;base64,<...>`）。 */
    val previewDataUri: String? = null,
)

/**
 * 单个附件 → 预览条目（RN attachmentToMediaItem 图片面）：author_name 附件跳过（公告作者行）；
 * url = formatAttachmentUrl(title_link || image_url)；缩略 = thumb_url（格式化）否则
 * image_preview → data URI（image_type || image/jpeg）。url 空 → null。
 */
fun buildViewerImage(
    a: ParsedAttachment,
    userId: String,
    token: String,
    server: String,
): ViewerImage? {
    if (a.authorName != null) return null
    val raw = a.titleLink?.takeUnless { it.isEmpty() } ?: a.imageUrl ?: return null
    val url = AttachmentUrlFormatter.format(raw, userId, token, server)
    if (url.isEmpty()) return null
    val thumbnailUrl = a.thumbUrl?.takeUnless { it.isEmpty() }
        ?.let { AttachmentUrlFormatter.format(it, userId, token, server) }
    val previewDataUri = if (thumbnailUrl == null && !a.imagePreview.isNullOrEmpty()) {
        "data:${a.imageType ?: "image/jpeg"};base64,${a.imagePreview}"
    } else {
        null
    }
    return ViewerImage(url = url, thumbnailUrl = thumbnailUrl, previewDataUri = previewDataUri)
}

/** 便捷形态：逐项 [buildViewerImage] 过滤 null。 */
fun buildViewerImages(
    attachments: List<ParsedAttachment>,
    userId: String,
    token: String,
    server: String,
): List<ViewerImage> = attachments.mapNotNull { buildViewerImage(it, userId, token, server) }

/**
 * 图片网格 ↔ 预览页对齐数据（评审修复：网格与预览列表必须同源，防混合附件错位）。
 * [cells] 逐格条目（可空 = 该格无预览，仅占位底色）；[items] 预览页列表（去 null）；
 * [cellToItem] 格 → items 下标（-1 = 无），点击导航 initialIndex 由此取。
 */
data class ImageViewerGrid(
    val cells: List<ViewerImage?>,
    val cellToItem: List<Int>,
    val items: List<ViewerImage>,
)

/** 图片分区（已按 kind==IMAGE 过滤）逐格建条目；跳过项不占 items 位。 */
fun buildImageViewerGrid(
    images: List<ParsedAttachment>,
    userId: String,
    token: String,
    server: String,
): ImageViewerGrid {
    val items = mutableListOf<ViewerImage>()
    val cells = mutableListOf<ViewerImage?>()
    val cellToItem = mutableListOf<Int>()
    images.forEach { att ->
        val img = buildViewerImage(att, userId, token, server)
        cells += img
        if (img == null) {
            cellToItem += -1
        } else {
            cellToItem += items.size
            items += img
        }
    }
    return ImageViewerGrid(cells = cells, cellToItem = cellToItem, items = items)
}

/** 视频播放页参数（RN attachmentToMediaItem 视频面：url=video_url，缩略=thumb_url）。 */
data class ViewerVideo(val url: String, val thumbnailUrl: String?, val title: String?)

fun buildViewerVideo(a: ParsedAttachment, userId: String, token: String, server: String): ViewerVideo? {
    val url = AttachmentUrlFormatter.format(a.videoUrl, userId, token, server)
    if (url.isEmpty()) return null
    val thumb = a.thumbUrl?.takeUnless { it.isEmpty() }
        ?.let { AttachmentUrlFormatter.format(it, userId, token, server) }
    return ViewerVideo(url = url, thumbnailUrl = thumb, title = a.title ?: a.name)
}
