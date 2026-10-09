package cn.appia.im.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cn.appia.im.core.network.RocketHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val DOWNLOADING_FILE_NAME = "appia-release-downloading.apk"
private const val RELEASE_FILE_PREFIX = "appia-release-"
private const val APK_SUFFIX = ".apk"

/**
 * 自更新 APK 下载/安装（RN services/appReleaseInstaller.ts 逐行移植）：
 * - 目录 `getExternalFilesDir(null)/Download`（应用专属外部存储，免存储权限；RN
 *   `RNFS.ExternalDirectoryPath/Download` 的平台等价）。
 * - 缓存命中 `appia-release-{version}.apk` 直接装跳下载（坑 13）；下载先清旧临时名
 *   `appia-release-downloading.apk`，完成后改名到正式名（RN moveFile 语义）。
 * - OkHttp GET + 进度回调 0..1（仅整数百分比变化时回调——RN 展示口径即整数百分比，
 *   去抖防大文件逐块回调淹没 UI 态写入）；非 2xx 抛 `app_release_download_http_<code>`。
 * - FileProvider content URI + ACTION_VIEW 拉起系统安装器。
 *   **authorities 分歧**：RN 走 react-native-blob-util AAR 的硬编码 `cn.appia.im.provider`；
 *   AA 用 `${applicationId}.provider`（debug 包 `.debug` 后缀可用），清单与 [install] 保持一致。
 */
class AppReleaseInstaller(
    private val context: Context,
    private val client: OkHttpClient = RocketHttp.client,
) {

    /** 下载目录（RN getDownloadDir）；外置不可用时回落内置 files（file_paths.xml 双路径覆盖）。 */
    fun downloadDir(): File {
        val ext = context.getExternalFilesDir(null)
        val dir = if (ext != null) File(ext, "Download") else File(context.filesDir, "Download")
        dir.mkdirs()
        return dir
    }

    /**
     * RN getApkPath :14-17：版本 encodeURIComponent 后拼正式名（URLEncoder 差异仅空格
     * `+` vs `%20` 与 `!'()*` 不编码——版本号不含这些字符，无实际分歧）。
     */
    fun apkFileFor(version: String): File =
        File(downloadDir(), RELEASE_FILE_PREFIX + URLEncoder.encode(version, "UTF-8") + APK_SUFFIX)

    /**
     * 下载并拉起安装器（RN downloadAndInstallAppRelease :39-81）：缓存命中 → 直接装；
     * 否则清临时名 → 流式下载（进度 0..1）→ 改名 → 装。失败抛错由调用方（Host）告警。
     */
    suspend fun downloadAndInstall(
        release: AppReleaseRow,
        onProgress: ((Float) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val dir = downloadDir()
        val apk = apkFileFor(release.version)
        val downloading = File(dir, DOWNLOADING_FILE_NAME)

        if (apk.exists()) { // 缓存命中：同版本 APK 已在 → 跳过下载（RN :51-54，坑 13）
            install(apk)
            return@withContext
        }

        downloading.delete() // RN removeFileIfExists(downloadingPath) :56

        val request = Request.Builder().url(resolveAppReleaseOpenUrl(release.url)).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) { // RN :73-75 非 2xx 抛错
                throw IllegalStateException("app_release_download_http_${resp.code}")
            }
            val body = resp.body
            val total = body.contentLength()
            val sink = downloading.outputStream()
            var received = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                sink.use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (onProgress != null) {
                            // RN :67：total>0 → min(received/total, 1)，否则恒 0；按整数百分比去抖
                            val fraction = if (total > 0) {
                                minOf(received.toDouble() / total, 1.0).toFloat()
                            } else {
                                0f
                            }
                            val percent = (fraction * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(fraction)
                            }
                        }
                    }
                }
            }
        }

        if (!downloading.renameTo(apk)) { // RN moveFile :79（改名即完成态标记）
            downloading.delete()
            throw IllegalStateException("app_release_download_rename_failed")
        }
        install(apk)
    }

    /**
     * content URI 构造缝（repo 惯例注入缝）：默认 FileProvider（authorities
     * `${applicationId}.provider`，与清单占位符经 packageName 对上）。生产勿动。
     * Robolectric 下 FileProvider 的静态策略缓存按类共享、会钉死首个测试方法的临时根，
     * 且 androidx.core 对 getFilesDir 根不做符号链接规范化（macOS /var → /private/var），
     * 测试注入假 Uri 规避（两者均为平台测试歧义，非生产缺陷）。
     */
    internal var uriForFile: (File) -> Uri =
        { apk -> FileProvider.getUriForFile(context, context.packageName + ".provider", apk) }

    /** FileProvider content URI + 系统安装器（RN actionViewIntent 等价；双 flag 与 RN 一致）。 */
    private fun install(apk: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uriForFile(apk), APK_MIME_TYPE)
        }
        context.startActivity(intent)
    }

    /**
     * 挂载时清理（RN cleanupInstalledAppRelease :83-106）：删临时文件 + 删**不严格新于**
     * 本地版本的正式 APK（新版本未安装的下载缓存保留）；目录读失败静默返回。
     */
    suspend fun cleanupInstalled(localVersion: String): Unit = withContext(Dispatchers.IO) {
        val files = downloadDir().listFiles() ?: return@withContext // RN readDir catch → return
        for (file in files) {
            if (file.name == DOWNLOADING_FILE_NAME) {
                file.delete()
                continue
            }
            val version = parseReleaseVersion(file.name)
            if (version != null && !isServerVersionNewer(version, localVersion)) {
                file.delete()
            }
        }
    }

    /** RN parseReleaseVersion :24-37：前缀+后缀解析版本段，URL 解码失败回 null。 */
    private fun parseReleaseVersion(fileName: String): String? {
        if (!fileName.startsWith(RELEASE_FILE_PREFIX) || !fileName.endsWith(APK_SUFFIX)) return null
        val encoded = fileName.removePrefix(RELEASE_FILE_PREFIX).removeSuffix(APK_SUFFIX)
        if (encoded.isEmpty()) return null
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
    }
}
