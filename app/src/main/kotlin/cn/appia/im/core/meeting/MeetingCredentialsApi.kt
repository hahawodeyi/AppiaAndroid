package cn.appia.im.core.meeting

import cn.appia.im.core.network.RocketHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * RTC 入会凭据（M8 T1 spike 结论 B）：
 * `GET {meetingServer}/api/v2/video/meeting/credentials?channelName={ch}&uid={uid}`。
 *
 * **响应字段为反向推断**（spike §2.2/§4：appId/token/uid 推自 H5 join 消费侧，尚未抓包实锤）——
 * 因此全字段可空 + [meetingJson] 宽容解（ignoreUnknownKeys），根级无 appId/token 时尝试 `data`
 * 包裹层；抓包回填后收紧。
 */
@Serializable
data class MeetingCredentials(
    val appId: String? = null,
    val token: String? = null,
    val uid: String? = null,
)

/**
 * 会议服务端 base 解析链（spike §2.2 `Le()` 判 `appia.cn|vip` 域）：
 * 登录响应 `appia_domain`/`meeting_api_origin` 覆盖 → 同源 [serverUrl] →
 * 兜底 [DEFAULT_MEETING_API_BASE]。覆盖/同源 host 非 appia.cn|appia.vip 域（私有化/测试环境）
 * 时一律落兜底——与 H5 同规。
 */
fun resolveMeetingApiBase(meetingApiOrigin: String?, serverUrl: String?): String {
    val candidate = meetingApiOrigin?.trim()?.takeIf { it.isNotEmpty() }
        ?: serverUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: return DEFAULT_MEETING_API_BASE
    val host = candidate.toHttpUrlOrNull()?.host ?: return DEFAULT_MEETING_API_BASE
    val appiaDomain = host == "appia.cn" || host == "appia.vip" ||
        host.endsWith(".appia.cn") || host.endsWith(".appia.vip")
    return if (appiaDomain) candidate.trimEnd('/') else DEFAULT_MEETING_API_BASE
}

/** spike §2.2 兜底常量（H5 `Ne`）。 */
const val DEFAULT_MEETING_API_BASE = "https://meeting-server.appia.vip"

/**
 * 凭据 URL（v2 端点；channelName/uid 走 query 编码）。
 * 独立成纯函数供 wire 测试直测（AppReleaseApi.buildAppVersionCheckUrl 先例）。
 */
fun buildMeetingCredentialsUrl(base: String, channelName: String, uid: Int): String {
    val root = (base.toHttpUrlOrNull() ?: return "").newBuilder()
        .addPathSegments("api/v2/video/meeting/credentials")
        .addQueryParameter("channelName", channelName)
        .addQueryParameter("uid", uid.toString())
        .build()
    return root.toString()
}

/**
 * 拉取 RTC 入会凭据（spike §2.2）：IM 会话 token 直连会议服务端——
 * `X-Auth-Token` + `X-User-Id` **两者同发（缺 X-User-Id 实证 401）** + `X-Client-Platform: mobile`。
 *
 * 非 2xx / 网络 / 解析失败 → **null**（调用方据此走失败路径；cert-less 兜底仅在取到 appId
 * 而 token 空/等于 appId 时生效——无 appId 无从建引擎，见 MeetingSessionController）。
 */
suspend fun fetchMeetingCredentials(
    client: OkHttpClient = RocketHttp.client,
    base: String,
    channelName: String,
    uid: Int,
    token: String,
    userId: String,
): MeetingCredentials? {
    val url = buildMeetingCredentialsUrl(base, channelName, uid)
    if (url.isEmpty()) return null
    val request = Request.Builder()
        .url(url)
        .get()
        .header("X-Auth-Token", token)
        .header("X-User-Id", userId)
        .header("X-Client-Platform", "mobile")
        .build()
    val body = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body.string()
            }
        }.getOrNull()
    } ?: return null
    return parseMeetingCredentials(body)
}

/** 宽容解：未知键丢弃；根级无 appId/token 时尝试 `data` 包裹层；解不动 → null。 */
fun parseMeetingCredentials(raw: String): MeetingCredentials? {
    val root = runCatching { meetingJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    meetingJson.decodeFromJsonElement<MeetingCredentials>(root)?.let { c ->
        if (c.appId != null || c.token != null) return c
    }
    val data = root["data"] as? JsonObject ?: return null
    return runCatching { meetingJson.decodeFromJsonElement<MeetingCredentials>(data) }.getOrNull()
}
