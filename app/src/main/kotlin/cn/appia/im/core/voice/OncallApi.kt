package cn.appia.im.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * oncallApi.ts:3-7 OncallApiContext。apiHost 为网关域名（VoiceEnv.shengwangApiHost 解析）。
 */
data class OncallContext(
    val org: String,
    val businessToken: String,
    val apiHost: String,
)

/** oncallApi.ts:16-21 OncallTokenExpiredError（code 40002 → Controller 捕获后 invalidate 重试）。 */
class OncallTokenExpiredException : Exception("oncall_token_expired")

/** oncallApi.ts:104-111 `oncall_http_error:${message}`（HTTP ≥400）。 */
class OncallHttpException(message: String) : Exception(message)

/** oncallApi.ts:9-14 OncallJsonResponse：code string/number 双形态保留 raw JsonElement。 */
data class OncallResponse(
    val code: JsonElement? = null,
    val data: JsonElement? = null,
    val message: String? = null,
    val success: Boolean? = null,
)

/** code 字符串化比较（"0" 或 0 均命中；非 primitive 返回 null）。 */
private fun JsonElement?.codeText(): String? =
    (this as? JsonPrimitive)?.content

/**
 * oncallResponse.ts:3-6 isOncallApiSuccess + Controller 裁定 success!=false：
 * code == "0"（string/number 双态）且 success != false。
 */
fun isOncallSuccess(res: OncallResponse): Boolean =
    res.code.codeText() == "0" && res.success != false

/** oncallApi.ts:23-33 InformInitBody（receivers 为 VoiceChatReceiver 列表，序列化字段同 RN）。 */
@Serializable
data class InformInitBody(
    val channelId: String,
    val initiator: String,
    val initiatorName: String,
    val initiatorAppiaId: String,
    val initiatorUid: Long,
    val org: String,
    val receivers: List<VoiceChatReceiver>,
    val roomId: String,
    val roomType: String,
)

/** oncallApi.ts:35-41 JoinBody（userUid/deviceId null → 键省略，对齐 RN undefined 丢键）。 */
data class JoinBody(
    val org: String,
    val recordId: String,
    val username: String,
    val userUid: Long? = null,
    val deviceId: String? = null,
)

/** oncallApi.ts:43-50 AddUsersBody。 */
@Serializable
data class AddUsersBody(
    val receivers: List<VoiceChatReceiver>,
    val recordId: String,
    val org: String,
    val initiatorAppiaId: String,
    val initiator: String,
    val initiatorName: String,
)

/**
 * 独立 oncall 网关 HTTP client（逐行为移植 appiaMobile/src/services/voice/oncallApi.ts）：
 * - 独立 OkHttp，不走 RocketSdk（独立网关域，MailClient 先例）
 * - 基址 `https://{ctx.apiHost}` 逐请求解析（apiHost 可随环境切换）
 * - POST → `token` 头；GET → `X-Auth-Token` 头；notify 端点两者皆不带（:81-94）
 * - 所有请求带 `org` 头
 * - HTTP ≥400 → [OncallHttpException]（message 取 json.message，缺省 HTTP <status>）
 * - code 40002 → [OncallTokenExpiredException]；缓存 invalidate 由调用方（Controller）做
 *   （实现锁定：本类不持有 cache 引用，对齐 RN handleOncallCode invalidate 外部 cache 的语义）
 */
class OncallApi(
    private val baseUrlProvider: () -> String,
    baseHttpClient: OkHttpClient = OkHttpClient(),
) {
    private val http = baseHttpClient

    /**
     * oncallApi.ts:58-59 oncallBaseUrl：`https://{apiHost}`。apiHost 空串时落
     * baseUrlProvider（测试/环境注入通道）；RN encode 语义（`+` 会被服务端解码为空格）用 `%20`。
     */
    private fun baseUrl(ctx: OncallContext): String =
        (if (ctx.apiHost.isBlank()) baseUrlProvider() else "https://${ctx.apiHost}").trimEnd('/')

    /**
     * oncallApi.ts:68-114 requestOncall。useBusinessTokenHeader=false 时 notify 端点省略鉴权头。
     * 路径 verbatim 追加（含尾问号等字面量），query 编码由调用方路径字符串承载（RN 同构）。
     */
    suspend fun request(
        ctx: OncallContext,
        method: String,
        path: String,
        body: JsonElement? = null,
        useBusinessTokenHeader: Boolean = true,
    ): OncallResponse {
        val builder = Request.Builder()
            .url(baseUrl(ctx) + path)
            .header("org", ctx.org)
        if (useBusinessTokenHeader) {
            if (method == "GET") builder.header("X-Auth-Token", ctx.businessToken)
            else builder.header("token", ctx.businessToken)
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(
                method,
                body.toString().toRequestBody("application/json".toMediaType()),
            )
        } else {
            builder.method(method, null)
        }
        // OkHttp execute() + body 读取均为阻塞——Dispatchers.IO 承接（T4 遗留修复：Main 调用方
        // NetworkOnMainThreadException 防线收在本层，调用方无需各自切线程；测试见 OncallApiTest 不受影响）。
        return withContext(Dispatchers.IO) {
            val res = http.newCall(builder.build()).execute()
            readResponse(res)
        }
    }

    /** oncallApi.ts:102-113：HTTP ≥400 → oncall_http_error；code 40002 → token expired。 */
    private fun readResponse(res: Response): OncallResponse = res.use {
        val text = it.body?.string().orEmpty()
        val json = parseTolerant(text)
        if (!it.isSuccessful) {
            val msg = json?.message ?: "HTTP ${it.code}"
            throw OncallHttpException("oncall_http_error:$msg")
        }
        if (json?.code.codeText() == "40002") throw OncallTokenExpiredException()
        return json ?: OncallResponse()
    }

    /** 宽容解析：空体/非 JSON → null（RN res.json() 失败即崩，AA 收窄为空响应）。 */
    private fun parseTolerant(text: String): OncallResponse? {
        if (text.isBlank()) return null
        val obj = runCatching { voiceJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return OncallResponse(
            code = obj["code"],
            data = obj["data"],
            message = (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            success = (obj["success"] as? JsonPrimitive)?.let { p ->
                runCatching { p.content.toBooleanStrict() }.getOrNull()
            },
        )
    }

    // ── oncallApi.ts:116-285 全端点 ──

    /** :116-125 POST `/api/v1/oncall/init?`（尾问号 verbatim）。 */
    suspend fun informInit(ctx: OncallContext, body: InformInitBody) = request(
        ctx, "POST", "/api/v1/oncall/init?",
        voiceJson.encodeToJsonElement(serializer<InformInitBody>(), body),
    )

    /** :127-136 GET `/api/v2/token?channelName=…&uid=…`。 */
    suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long) = request(
        ctx, "GET",
        "/api/v2/token?channelName=${enc(channelName)}&uid=${enc(uid.toString())}",
    )

    /** :138-148 POST `/api/v1/oncall/record/status/update?`（尾问号 verbatim）。 */
    suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String) = request(
        ctx, "POST", "/api/v1/oncall/record/status/update?",
        obj("recordId" to recordId, "status" to status),
    )

    /** :150-158 GET `/api/v1/oncall/record/status?recordId=…`。 */
    suspend fun getRecordStatus(ctx: OncallContext, recordId: String) = request(
        ctx, "GET", "/api/v1/oncall/record/status?recordId=${enc(recordId)}",
    )

    /** :160-169 GET `/api/v2/oncall/record/status?recordId=…&org=…`。 */
    suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String) = request(
        ctx, "GET",
        "/api/v2/oncall/record/status?recordId=${enc(recordId)}&org=${enc(recordOrg)}",
    )

    /** :182-190 GET `/api/v2/user/current/record?username=…`（v1 :171-180 无调用方，未移植）。 */
    suspend fun getCurrentRecordV2(ctx: OncallContext, username: String) = request(
        ctx, "GET", "/api/v2/oncall/user/current/record?username=${enc(username)}",
    )

    /** :192-207 POST `/api/v1/oncall/user/join`（无尾问号 verbatim）；userUid/deviceId null 丢键。 */
    suspend fun join(ctx: OncallContext, payload: JoinBody) = request(
        ctx, "POST", "/api/v1/oncall/user/join",
        buildJsonObject {
            put("org", payload.org)
            put("recordId", payload.recordId)
            put("username", payload.username)
            payload.userUid?.let { put("userUid", it) }
            payload.deviceId?.let { put("deviceId", it) }
        },
    )

    /** :209-224 POST `/api/v1/oncall/user/reject`；body.org = notificationOrg ?? ctx.org。 */
    suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String? = null) = request(
        ctx, "POST", "/api/v1/oncall/user/reject",
        obj(
            "org" to (notificationOrg ?: ctx.org),
            "recordId" to recordId,
            "username" to username,
        ),
    )

    /** :226-240 POST `/api/v1/oncall/user/leave`；body.org = ctx.org。 */
    suspend fun leave(ctx: OncallContext, recordId: String, username: String) = request(
        ctx, "POST", "/api/v1/oncall/user/leave",
        obj("org" to ctx.org, "recordId" to recordId, "username" to username),
    )

    /** :242-251 POST `/api/v1/oncall/add/user`。 */
    suspend fun addUsers(ctx: OncallContext, body: AddUsersBody) = request(
        ctx, "POST", "/api/v1/oncall/add/user",
        voiceJson.encodeToJsonElement(serializer<AddUsersBody>(), body),
    )

    /** :253-268 POST `/api/v1/oncall/user/get/notify`，无鉴权头；body.org = recordOrg ?? org。 */
    suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String? = null) = request(
        ctx, "POST", "/api/v1/oncall/user/get/notify",
        obj(
            "org" to (recordOrg ?: org),
            "recordId" to recordId,
            "username" to username,
        ),
        useBusinessTokenHeader = false,
    )

    /** :271-276 被叫展示来电时通知主叫（legacy Server.notifyHost）。 */
    suspend fun notifyHost(ctx: OncallContext, recordId: String, username: String) =
        getNotify(ctx, org = ctx.org, recordId = recordId, username = username)

    /** :279-285 跨组织来电：body.org = recordOrg，头 org 仍 ctx.org。 */
    suspend fun notifyHost2(ctx: OncallContext, recordOrg: String, recordId: String, username: String) =
        getNotify(ctx, org = ctx.org, recordId = recordId, username = username, recordOrg = recordOrg)

    private fun enc(value: String): String =
        // String-charset 重载（AppReleaseInstaller.kt:50 先例）——Charset 重载 API 33
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }
}
