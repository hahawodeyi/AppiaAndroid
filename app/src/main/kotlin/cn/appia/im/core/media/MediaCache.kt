package cn.appia.im.core.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import cn.appia.im.core.network.RocketHttp
import java.io.File
import java.security.MessageDigest

/**
 * 附件媒体文件缓存（RN resolveCachedImage 的替代形态，brief 裁定）：
 * - key = sha256(query 剥离后的 URL)——RN 取 `encodeURIComponent(无 query url).slice(0, 80)`，
 *   80 字符截断存在截断碰撞天花板，这里换全长摘要；query 剥离语义保留（rc_token/v=etag 轮换
 *   不打爆缓存，与 RN 一致；内容更新依赖 URL 路径变化，服务端 file-upload 路径含 fileId 满足）；
 * - in-flight 去重：同 key 并发请求复用同一下载（RN inFlight Map 同义）；
 * - 失败抛错（调用方决定降级，如 DocPreview 落 error 态）。
 */
object MediaCache {

    /** RN stripQuery：query 不进 key（含 rc_uid/rc_token/v）。 */
    fun stripQuery(url: String): String = url.substringBefore("?")

    fun cacheKey(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(stripQuery(url).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 缓存目录：`<cacheDir>/media-cache/<key>`（RN CacheDir/image-cache 的等价位）。 */
    fun cachedFile(cacheDir: File, url: String): File =
        File(File(cacheDir, "media-cache"), cacheKey(url))

    /**
     * 取文件：命中直接返回；未命中下载落盘。同 key 并发只下载一次
     * （LAZY 竞争者不启动：putIfAbsent 输者直接 await 赢者，RN inFlight Map 同语义）。
     * @param fetch 缝：默认 OkHttp GET（附件 URL 已带 rc_uid/rc_token 鉴权 query，无需头）。
     */
    suspend fun fetch(
        cacheDir: File,
        url: String,
        client: OkHttpClient = RocketHttp.client,
        fetchImpl: suspend (String, File) -> Unit = { u, dst ->
            downloadTo(client, u, dst)
        },
    ): File = coroutineScope {
        val dst = cachedFile(cacheDir, url)
        if (dst.isFile && dst.length() > 0) return@coroutineScope dst
        val created = async(start = CoroutineStart.LAZY) {
            if (!(dst.isFile && dst.length() > 0)) {
                dst.parentFile?.mkdirs()
                fetchImpl(url, dst)
            }
            dst
        }
        val winner = inFlight.putIfAbsent(dst.absolutePath, created)
        if (winner == null) {
            created.start() // 赢者：启动下载
            try {
                created.await()
            } finally {
                inFlight.remove(dst.absolutePath, created)
            }
        } else {
            // 输者：未启动的 LAZY 子协程立即取消（否则 New 态子协程会挂住本 scope 完成），
            // 改等赢者的下载结果（RN inFlight 共享 Promise 同义；赢者失败则同错上抛）。
            created.cancel()
            winner.await()
        }
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<File>>()

    private suspend fun downloadTo(client: OkHttpClient, url: String, dst: File): Unit =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("media fetch failed: HTTP ${resp.code}")
                val body = resp.body ?: error("media fetch failed: empty body")
                val tmp = File(dst.absolutePath + ".part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (!tmp.renameTo(dst) && !(dst.isFile && dst.length() > 0)) {
                    error("media fetch failed: rename to ${dst.name}")
                }
            }
        }

    /** 测试缝：单例 in-flight 跨用例残留清理。 */
    fun clearInFlight() = inFlight.clear()
}

/**
 * RN fileDownload.ts sanitizeDownloadFileName：仅去掉 query/hash，保留 title 主体。
 */
fun sanitizeDownloadFileName(fileName: String): String {
    val trimmed = fileName.trim()
    if (trimmed.isEmpty()) return "document"
    return trimmed.substringBefore("?").substringBefore("#").trim().ifEmpty { "document" }
}

/**
 * 附件下载（RN downloadFile + DownloadManager 语义，binding 裁定 4）：
 * DownloadManager 落 `/storage/emulated/0/Download/appia/<name>` + 完成通知
 * （RN addAndroidDownloads.notification/useDownloadManager 同义）。enqueue 即返回（系统托管重试/通知）。
 */
object AttachmentDownloader {

    fun enqueue(context: Context, url: String, fileName: String): Long {
        require(url.isNotBlank()) { "download url is blank" }
        val name = sanitizeDownloadFileName(fileName)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "appia/$name")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }
}
