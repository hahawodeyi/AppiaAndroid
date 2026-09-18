package cn.appia.im.core.media

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 支持的文件预览格式白名单（RN useFilePreview.ts SUPPORTED_FORMATS 同名单）。 */
val SUPPORTED_PREVIEW_FORMATS = setOf(
    "pdf", "doc", "docx", "docm",
    "xls", "xlsx", "xltm", "xlsb",
    "ppt", "pptx", "pptm",
    "txt", "csv", "md", "log", "cfg",
    "html", "htm", "xhtml", "mhtml",
)

fun isSupportedPreviewFormat(fileType: String): Boolean =
    fileType.lowercase() in SUPPORTED_PREVIEW_FORMATS

/** rooms.preview 单次响应判定（RN services/api/preview.ts getPreviewUrl 同语义）。 */
sealed interface PreviewResult {
    /** code 0/1：服务器转换中，继续轮询。 */
    data object Pending : PreviewResult

    /** code 2/200 且 data 非空：转换完成。 */
    data class Ready(val url: String) : PreviewResult
}

/** 响应错误（RN throw new Error(res.message ?? 'preview_failed')）。 */
class PreviewFailedException(message: String? = null) : Exception(message ?: "preview_failed")

/**
 * 解析 rooms.preview 响应：code 0/1 → pending；code 2/200 且 data 非空 → ready；其余抛错。
 * RocketSdk.get 已按 `data ?? resp` 平铺，code/data 直接取顶层（RN res 同层）。
 */
fun parsePreviewResponse(json: JsonElement?): PreviewResult {
    val obj = json as? JsonObject
    val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    val data = (obj?.get("data") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull ?: ""
    return when {
        code == 0 || code == 1 -> PreviewResult.Pending
        (code == 2 || code == 200) && data.isNotEmpty() -> PreviewResult.Ready(data)
        else -> throw PreviewFailedException(
            (obj?.get("message") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
        )
    }
}

/**
 * RN pollPreviewUrl：最多 [maxRetry] 次（31 次调用，i=0..30），间隔 [intervalMs]；
 * 首次 retry=0，之后恒 1（RN `i === 0 ? 0 : 1`）；全轮询耗尽抛 preview_failed。
 * @param preview 单次请求缝（生产 = `sdk.get("rooms.preview", ...)`；测试注入假响应/虚拟时间）
 */
suspend fun pollPreviewUrl(
    fileId: String,
    maxRetry: Int = 30,
    intervalMs: Long = 2_000L,
    preview: suspend (fileId: String, retry: Int) -> JsonElement?,
    sleep: suspend (Long) -> Unit = { delay(it) },
): String {
    for (i in 0..maxRetry) {
        when (val result = parsePreviewResponse(preview(fileId, if (i == 0) 0 else 1))) {
            is PreviewResult.Ready -> return result.url
            PreviewResult.Pending -> sleep(intervalMs)
        }
    }
    throw PreviewFailedException()
}

/**
 * RN resolvePreviewPdfUri：rooms.preview 返回的 PDF 地址可能是相对路径或缺鉴权参数，
 * 对齐 downloadUrl 补 rc_uid/rc_token 再交给渲染器。含 rc_token → encodeURI 原样。
 */
fun resolvePreviewPdfUri(previewUrl: String, downloadUrl: String): String {
    val trimmed = previewUrl.trim()
    if (trimmed.isEmpty()) return ""

    if (trimmed.contains("rc_token")) return AttachmentUrlFormatter.encodeUri(trimmed)

    val auth = runCatching {
        val url = java.net.URI(downloadUrl)
        val params = url.rawQuery?.split("&")?.mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }?.toMap() ?: emptyMap()
        params["rc_uid"]?.takeIf { it.isNotEmpty() }?.let { uid ->
            params["rc_token"]?.takeIf { it.isNotEmpty() }?.let { tok -> uid to tok }
        }
    }.getOrNull()

    if (auth == null) return AttachmentUrlFormatter.encodeUri(trimmed)
    val (userId, token) = auth

    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return runCatching {
            val url = java.net.URI(trimmed)
            val existing = url.rawQuery
            val sep = if (existing.isNullOrEmpty()) "?" else "&"
            AttachmentUrlFormatter.encodeUri(
                url.toString() + sep + "rc_uid=$userId&rc_token=$token",
            )
        }.getOrElse { AttachmentUrlFormatter.encodeUri(trimmed) }
    }

    val origin = runCatching {
        val u = java.net.URI(downloadUrl)
        val scheme = u.scheme ?: return@runCatching null // 无 scheme（RN new URL 抛错）→ 无解
        val authority = u.authority ?: return@runCatching null
        "$scheme://$authority"
    }.getOrNull() ?: return ""
    val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    return AttachmentUrlFormatter.encodeUri("$origin$path?rc_uid=$userId&rc_token=$token")
}
