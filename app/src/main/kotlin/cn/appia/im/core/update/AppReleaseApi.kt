package cn.appia.im.core.update

import cn.appia.im.core.network.RocketHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 应用版本检查接口 host（`GET /provider/api/v1/version`，不含路径）。
 * 私有化或多环境构建时在此改默认值（RN src/constants/appRelease.ts:1-11 逐行）。
 */
const val APP_RELEASE_CHECK_BASE_URL = "https://appia.cn"

/**
 * 接口 `url` 为逗号分隔多条链接时使用的下标（RN APP_RELEASE_URL_COMMA_INDEX :7-11）。
 * 默认取第一条；内测包可在构建配置中改为 `1`。
 */
const val APP_RELEASE_URL_COMMA_INDEX = 0

/** `GET /provider/api/v1/version` 返回的 `data` 对象（RN :17-31；camelCase 随服务端）。 */
@Serializable
data class ProviderVersionDto(
    val platform: String? = null,
    val version: String? = null,
    val url: String? = null,
    @SerialName("isForceUpdate") val isForceUpdate: Boolean? = null,
    val fileHash: String? = null,
    val fileSize: String? = null,
    val notes: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ProviderVersionResponse(
    val data: ProviderVersionDto? = null,
    val success: Boolean = false,
)

/** 应用内统一使用的版本行（snake_case 字段名逐行对齐 RN services/appRelease.ts:7-14）。 */
data class AppReleaseRow(
    val platform: String,
    val version: String,
    val url: String,
    val is_force_update: Boolean,
    val file_hash: String?,
    val notes: String,
)

private val releaseJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** RN buildAppVersionCheckUrl :41-49（URLSearchParams 等价的 percent-encode；尾斜杠只去一个，同 replace(/\/$/, '')）。 */
fun buildAppVersionCheckUrl(baseUrl: String, platform: String, localVersion: String): String {
    val root = baseUrl.removeSuffix("/")
    val q = "platform=${enc(platform)}&versionName=${enc(localVersion)}"
    return "$root/provider/api/v1/version?$q"
}

private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

/** RN mapProviderVersionToAppReleaseRow :51-58（?? '' → ?: ""，Boolean() → == true）。 */
fun mapProviderVersionToAppReleaseRow(dto: ProviderVersionDto): AppReleaseRow = AppReleaseRow(
    platform = dto.platform ?: "",
    version = dto.version ?: "",
    url = dto.url ?: "",
    is_force_update = dto.isForceUpdate == true,
    file_hash = dto.fileHash,
    notes = dto.notes ?: "",
)

/** RN parseProviderVersionResponse :60-63：success=false 或无 data → null（属**成功**响应，缓存进 stale 窗口）。 */
fun parseProviderVersionResponse(body: ProviderVersionResponse): AppReleaseRow? {
    if (!body.success || body.data == null) return null
    return mapProviderVersionToAppReleaseRow(body.data)
}

/** 解析逗号分隔的 `url`，按 [APP_RELEASE_URL_COMMA_INDEX] 取一条（RN resolveAppReleaseOpenUrl :65-74 逐行）。 */
fun resolveAppReleaseOpenUrl(rawUrl: String): String {
    val parts = rawUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return rawUrl.trim()
    val idx = APP_RELEASE_URL_COMMA_INDEX.coerceIn(0, parts.size - 1)
    return parts[idx]
}

/**
 * GET `/provider/api/v1/version`（无 IM token，与 Rocket.Chat 无关——RN :81-95 逐行；
 * 用共享裸 [RocketHttp] client，不带任何会话头）。非 2xx 抛 `app_release_http_<code>`（RN :90-92）。
 * localVersion 对应 RN `DeviceInfo.getVersion()`（营销版本号，非 versionCode）。
 */
suspend fun fetchLatestAppRelease(
    client: OkHttpClient = RocketHttp.client,
    baseUrl: String = APP_RELEASE_CHECK_BASE_URL,
    platform: String = "android",
    localVersion: String,
): AppReleaseRow? {
    val url = buildAppVersionCheckUrl(baseUrl, platform, localVersion)
    val request = Request.Builder().url(url).get().build()
    val body = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("app_release_http_${resp.code}")
            resp.body.string()
        }
    }
    return parseProviderVersionResponse(releaseJson.decodeFromString<ProviderVersionResponse>(body))
}
