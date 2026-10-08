package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 服务器信息 / 应用版本检查（M5-T9，RN services/api/serverInfo.ts + appRelease.ts 对照）：
 * 两端点均为**免登录**（无 IM token、非 `/api/v1` 前缀）——不走 RocketSdk 会话通道，
 * 直接以 [RocketHttp] 裸客户端请求（MyCard 二维码位图拉取同款通道）。
 */
object ServerInfoApi {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `GET {base}/api/info`（RN fetchServerInfo serverInfo.ts:11-21）：尾部 slash 剥离；
     * 非 2xx / `success:false` / 异常 → null。返回 `version.trim()` 非空值。
     */
    suspend fun fetchServerVersion(serverUrl: String, client: OkHttpClient = RocketHttp.client): String? {
        val base = serverUrl.trim().replace(Regex("/+$"), "")
        if (base.isEmpty()) return null
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("$base/api/info").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body.string()
                    if (body.isEmpty()) return@withContext null
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@withContext null
                    if (obj["success"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.contentOrNull } == "false") {
                        return@withContext null
                    }
                    (obj["version"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                        ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 应用版本检查（RN constants/appRelease.ts + services/appRelease.ts）：
 * `GET {base}/provider/api/v1/version?platform=android&versionName={local}`。
 */
object AppReleaseApi {

    /** RN APP_RELEASE_CHECK_BASE_URL（constants/appRelease.ts:6）。 */
    const val APP_RELEASE_CHECK_BASE_URL = "https://appia.cn"

    /** RN APP_RELEASE_URL_COMMA_INDEX（constants/appRelease.ts:13）：逗号分隔多链接取第一条。 */
    const val APP_RELEASE_URL_COMMA_INDEX = 0

    /** 与旧版 `SettingsView`「检查更新」跳转一致（constants/legalUrls.ts:11）。 */
    const val APP_DOWNLOAD_URL_ANDROID = "https://appia.cn/appia_fe/download"

    private val json = Json { ignoreUnknownKeys = true }

    /** RN AppReleaseRow（snake_case 与 UI/存储约定一致）。 */
    data class AppReleaseRow(
        val platform: String,
        val version: String,
        val url: String,
        val isForceUpdate: Boolean,
        val notes: String,
    )

    /** RN buildAppVersionCheckUrl（appRelease.ts:45-50）：`?platform=&versionName=` 双参。 */
    fun buildAppVersionCheckUrl(baseUrl: String, localVersion: String, platform: String = "android"): String =
        "${baseUrl.replace(Regex("/$"), "")}/provider/api/v1/version" +
            "?platform=${platform}&versionName=${localVersion}"

    /**
     * `GET /provider/api/v1/version`（无 IM token）。非 2xx → 抛 `app_release_http_{status}`
     * （RN :97-99 同款错误串，调用方 catch → 检查更新失败提示）；`success:false`/缺 data → null。
     */
    suspend fun fetchLatestAppRelease(
        baseUrl: String = APP_RELEASE_CHECK_BASE_URL,
        localVersion: String,
        platform: String = "android",
        client: OkHttpClient = RocketHttp.client,
    ): AppReleaseRow? {
        val url = buildAppVersionCheckUrl(baseUrl, localVersion, platform)
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("app_release_http_${resp.code}")
                    val body = resp.body.string()
                    if (body.isEmpty()) return@withContext null
                    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                        ?: return@withContext null
                    parseProviderVersionResponse(obj)
                }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** RN parseProviderVersionResponse：`!success || !data` → null；否则映射 snake 行。 */
    fun parseProviderVersionResponse(body: JsonObject): AppReleaseRow? {
        if (body.str("success") != "true") return null
        val data = body["data"] as? JsonObject ?: return null
        return AppReleaseRow(
            platform = data.str("platform").orEmpty(),
            version = data.str("version").orEmpty(),
            url = data.str("url").orEmpty(),
            isForceUpdate = data.str("isForceUpdate") == "true",
            notes = data.str("notes").orEmpty(),
        )
    }

    /**
     * RN resolveAppReleaseOpenUrl（appRelease.ts:70-75）：逗号分隔多链接按
     * [APP_RELEASE_URL_COMMA_INDEX] 取一条；全空白回退原文 trim。
     */
    fun resolveAppReleaseOpenUrl(rawUrl: String): String {
        val parts = rawUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return rawUrl.trim()
        val idx = APP_RELEASE_URL_COMMA_INDEX.coerceIn(0, parts.size - 1)
        return parts[idx]
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
