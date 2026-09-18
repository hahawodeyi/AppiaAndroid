package cn.appia.im.core.network

import cn.appia.im.BuildConfig
import cn.appia.im.core.network.ddp.DdpClient
import cn.appia.im.core.network.ddp.DdpLoginResult
import cn.appia.im.core.network.ddp.DdpOptions
import cn.appia.im.core.network.ddp.DdpSubscription
import cn.appia.im.core.network.rest.ApiException
import cn.appia.im.core.network.rest.AuthInterceptor
import cn.appia.im.core.network.rest.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** REST 登录结果：RN sdk/index.ts:149-180 的 `data ?? resp` 平铺字段（brief：{authToken, userId, me?}）。 */
data class LoginResult(
    val authToken: String,
    val userId: String,
    val me: LoginMe? = null,
)

private val sdkJson = Json { ignoreUnknownKeys = true }

private fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/**
 * 内嵌极简 Rocket.Chat SDK（逐行为移植 appiaMobile/src/services/sdk/index.ts，仅取 M1 所需面）：
 * - REST：login（五类凭证）+ get/post（会话后调用）
 * - REST 回退 Meteor 方法：methodCall（RN callMethodRest sdk/index.ts:270-291）
 * - DDP：connect / resume / hasDdpUserId / disconnect（RealtimeSessionManager 在 M1 后续任务接入）
 *
 * 有意不依赖 @rocket.chat/sdk，也不 import DDP 之外的会话层（T1 预检裁定）；
 * 换库/登出等数据库副作用归 M1 会话层。
 */
class RocketSdk(
    ddp: DdpClient? = null,
    private val client: OkHttpClient = RocketHttp.client,
) {
    /** 规范化后的当前主体 base URL（RN this.server，sdk/index.ts:60）。 */
    @Volatile
    var server: String? = null
        private set

    /** 当前 DDP 客户端；initialize 前为 null。 */
    @Volatile
    var ddp: DdpClient? = null
        private set

    /** REST 会话（RN currentLogin 的 token/userId；username/result 由 M1 会话层经 LoginResult/AuthUser 另行持有）。 */
    @Volatile
    private var session: AuthSession? = null

    /** RN rocketRestRequest 的拦截器等价：鉴权头注入 + 401 登出事件 + 错误链（T1 restClient 移植复用）。 */
    private val http: OkHttpClient = client.newBuilder()
        .addInterceptor(AuthInterceptor { session })
        .build()

    /**
     * RN initialize sdk/index.ts:64-71：规范化 server 并重建 DdpClient。
     * 只换连接，**不触碰数据库**——RN 换库语义在 session.ts:267-271，归 M1 会话层。
     */
    fun initialize(serverUrl: String) {
        val normalized = ServerUrl.normalizeServer(serverUrl)
        ddp?.close() // 原生加固：被替换实例终态销毁，防旧 socket/定时器滞留（RN 靠 GC 兜底）
        ddp = DdpClient(
            DdpOptions(
                host = normalized,
                // RN sdk/index.ts:66-69：dev 20s 重连间隔防紧循环（__DEV__ → BuildConfig.DEBUG）
                reopenMs = if (BuildConfig.DEBUG) 20_000L else 5_000L,
            ),
            client,
        )
        server = normalized
    }

    /** RN connect sdk/index.ts:82-84。 */
    suspend fun connect() = ddp().connect()

    /** RN hasDdpUserId sdk/index.ts:124-127：DDP login/resume 成功后才有，与 REST 会话独立。 */
    fun hasDdpUserId(): Boolean = ddp?.userId != null

    /**
     * RN login sdk/index.ts:149-180：REST 登录成功 → 设会话 → 立即 DDP `login{resume}`。
     * REST 失败直接上抛、不触碰 DDP（无 connect、无 resume）；resume 失败同样上抛（会话已设，与 RN 一致）。
     * timeoutMs：仅约束 REST 请求（RN restClient 调用点 timeoutMs:30_000 的等价通道，DDP resume 不受限）。
     */
    suspend fun login(credentials: LoginCredentials, timeoutMs: Long? = null): LoginResult {
        val call = LoginRequestFactory.create(credentials)
        val resp = restRequest("POST", call.endpoint, jsonBody(call.body), timeoutMs = timeoutMs)
        val data = flatten(resp) as? JsonObject
        val authToken = data.str("authToken")
            ?: throw ApiException("[rocket] login: missing authToken in response")
        val userId = data.str("userId")
            ?: throw ApiException("[rocket] login: missing userId in response")
        val me = data?.get("me")?.let { value ->
            runCatching { sdkJson.decodeFromJsonElement<LoginMe>(value.jsonObject) }.getOrNull()
        }
        session = AuthSession(token = authToken, userId = userId)
        ddp().loginWithResume(authToken) // RN:179 await this.resume({token})
        return LoginResult(authToken = authToken, userId = userId, me = me)
    }

    /**
     * RN resume sdk/index.ts:129-147：DDP `login{resume}` 成功后把 REST 会话对齐到该 token/userId
     * （冷启动只有 token 时也必须补上 REST 会话，get/post 才可用）。
     */
    suspend fun resume(token: String): DdpLoginResult {
        val login = ddp().loginWithResume(token)
        session = AuthSession(token = token, userId = login.id)
        return login
    }

    /** RN get sdk/index.ts:192-202；响应按 `data ?? resp` 平铺（绑定裁定，login/get/post 统一口径）。 */
    suspend fun get(endpoint: String, params: Map<String, String>? = null): JsonElement {
        requireSession()
        return flatten(restRequest("GET", endpoint, body = null, params = params))
    }

    /** RN post sdk/index.ts:204-214：JSON body；响应平铺同 get。 */
    suspend fun post(endpoint: String, body: JsonElement? = null): JsonElement {
        requireSession()
        return flatten(restRequest("POST", endpoint, jsonBody(body)))
    }

    /**
     * multipart 通道（T6 UploadApi rooms.upload 专用）：同 restRequest 的 URL 规范化/会话拦截器
     * （AuthInterceptor 注入鉴权头 + 非 2xx 抛 ApiException）/响应平铺语义，仅 body 换 multipart。
     */
    suspend fun postMultipart(endpoint: String, body: MultipartBody): JsonElement {
        requireSession()
        return flatten(restRequest("POST", endpoint, body))
    }

    /**
     * RN subscribeRoom sdk/index.ts:244-253（旧版 ios sdk.subscribeRoom 同构）：
     * 同一 `rid` 三条订阅——`stream-room-messages`、`stream-notify-room {rid}/user-activity`（typing）、
     * `stream-notify-room {rid}/deleteMessage`；并发订阅（Promise.all 等价，一败皆败）。
     */
    suspend fun subscribeRoom(rid: String): List<DdpSubscription> {
        val ddp = ddp()
        return coroutineScope {
            listOf(
                async { ddp.subscribe(ROOM_STREAM_MESSAGES, rid, JsonPrimitive(rid)) },
                async { ddp.subscribe(ROOM_NOTIFY_ROOM, "$rid/user-activity", JsonPrimitive(rid)) },
                async { ddp.subscribe(ROOM_NOTIFY_ROOM, "$rid/deleteMessage", JsonPrimitive(rid)) },
            ).map { it.await() }
        }
    }

    /**
     * RN callMethodRest sdk/index.ts:270-291：REST 执行 Meteor 方法——
     * `POST method.call/{encodeURIComponent(method)}`，body `{message: <DDP method 帧 JSON 串>}`，
     * 响应按 method.call 信封解析（parseMethodCallRestResponse）。
     */
    suspend fun methodCall(method: String, params: List<JsonElement> = emptyList()): JsonElement? {
        requireSession()
        val id = "ddp-${System.currentTimeMillis().toString(36)}-${randomId()}"
        val message = buildJsonObject {
            put("msg", "method")
            put("id", id)
            put("method", method)
            put("params", JsonArray(params))
        }.toString()
        val raw = restRequest(
            "POST",
            "method.call/${encodeURIComponent(method)}",
            jsonBody(buildJsonObject { put("message", message) }),
        )
        return parseMethodCallRestResponse(raw)
    }

    /**
     * RN hydrateRestSessionFromAuth sdk/index.ts:97-113：冷启动/换主体时注入 REST 会话。
     * 同 token+userId+server 直接返回；server 变化或未初始化则 initialize（重建 DdpClient）。
     */
    fun hydrateRestSession(serverUrl: String, token: String, userId: String) {
        val normalized = ServerUrl.normalizeServer(serverUrl)
        val current = session
        if (current != null && current.token == token && current.userId == userId && server == normalized) return
        if (server == null || server != normalized) initialize(serverUrl)
        session = AuthSession(token = token, userId = userId)
    }

    /** RN clearRestSession sdk/index.ts:115-117。 */
    fun clearRestSession() {
        session = null
    }

    /**
     * RN `sdk.current?.authToken`（sdk/index.ts currentLogin 读取）：
     * waitSdkRestLogin 的判定信号（50ms 轮询直至 REST 会话 token 就绪）。
     */
    fun currentAuthToken(): String? = session?.token

    /** RN `sdk.current?.userId`：换主体后缓存行回填时优先取连接层确认的 userId。 */
    fun currentUserId(): String? = session?.userId

    /** RN disconnect sdk/index.ts:86-88：断开 DDP（disconnect 可重连，不销毁实例）。 */
    fun disconnect() {
        ddp?.disconnect()
    }

    /** RN checkAndReopenTransport sdk/index.ts:89-91：未连接时立即建连（重连风暴防护在 DdpClient 内）。 */
    fun checkAndReopenTransport() {
        ddp?.checkAndReopen()
    }

    // ---- 内部 ----

    private fun ddp(): DdpClient = ddp ?: throw IllegalStateException("RocketSdk not initialized")

    /** JSON body 编码（null → restRequest POST 分支回退空体，RN `(body ?? '')` 同义）。 */
    private fun jsonBody(body: JsonElement?): RequestBody? =
        body?.toString()?.toRequestBody("application/json".toMediaType())

    /** RN assertLoggedIn sdk/index.ts:183-186。 */
    private fun requireSession() {
        session ?: throw IllegalStateException("Not logged in")
    }

    /** RN `resp?.data ?? resp`（sdk/index.ts:157）：`{data:{...}}` 包裹取内层，裸形态原样返回。 */
    private fun flatten(resp: JsonElement): JsonElement {
        val obj = resp as? JsonObject ?: return resp
        return obj["data"]?.takeUnless { it is JsonNull } ?: resp
    }

    /** RN rocketRestRequest restClient.ts:35-99：URL 组装 + body 编解码；错误链/401 在 AuthInterceptor。 */
    private suspend fun restRequest(
        method: String,
        endpoint: String,
        body: RequestBody?,
        params: Map<String, String>? = null,
        timeoutMs: Long? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val host = server ?: throw IllegalStateException("RocketSdk not initialized")
        val url = buildString {
            append(ServerUrl.normalizeServer(host))
            append("/api/v1/")
            append(endpoint)
            params?.takeIf { it.isNotEmpty() }?.let { ps ->
                append(ps.entries.joinToString("&", "?") { (k, v) ->
                    "${encodeURIComponent(k)}=${encodeURIComponent(v)}"
                })
            }
        }
        val request = Request.Builder().url(url).apply {
            if (method == "GET") {
                get()
            } else {
                post(body ?: "".toRequestBody("application/json".toMediaType()))
            }
        }.build()
        val call = http.newCall(request)
        // RN restClient.ts:44-50 AbortController：per-call 超时落在本次 REST 请求上（Call.timeout 等价 callTimeout）
        timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { resp ->
            val text = resp.body.string()
            if (text.isEmpty()) JsonObject(emptyMap()) else sdkJson.parseToJsonElement(text)
        }
    }

    /** RN `ddp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}` 的随机段。 */
    private fun randomId(): String = Random.nextLong().toString(36).take(7).padEnd(7, '0')

    /** JS encodeURIComponent 等价；URLEncoder 唯一常态差异是空格（+ → %20），RC 方法名不触及其余差异字符。 */
    private fun encodeURIComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** 房间流 topic（RN sdk/index.ts:246 字面量；与全局流 StreamNames 分开——它们是 sdk 层常量）。 */
internal const val ROOM_STREAM_MESSAGES = "stream-room-messages"
internal const val ROOM_NOTIFY_ROOM = "stream-notify-room"

/**
 * RN methodCallRest.ts parseMethodCallRestResponse：method.call 响应的 `message` 信封解析。
 * 成功时外层常含 `message`（DDP result 帧的 JSON 串）；部分网关把载荷放在 `data` 下或直接解析成对象。
 * 返回 null 等价 JS undefined（RN `raw == null` 透传 / inner.result 缺省）。
 */
internal fun parseMethodCallRestResponse(raw: JsonElement?): JsonElement? {
    if (raw == null || raw is JsonNull) return raw
    val top = raw as? JsonObject ?: return raw
    val obj = pickMethodCallEnvelope(top)
    val message = obj["message"]
    return when {
        message is JsonObject -> parseDdpResultPayload(message)
        message is JsonPrimitive && message !is JsonNull && message.isString -> {
            val inner = runCatching { sdkJson.parseToJsonElement(message.content).jsonObject }
                .getOrElse { throw ApiException("[method.call] invalid message JSON") }
            parseDdpResultPayload(inner)
        }
        // RN：无 message → 有 result 字段取之，否则原样（'result' in o && o.result !== undefined）
        obj.containsKey("result") -> obj["result"]
        else -> raw
    }
}

/** RN pickMethodCallEnvelope：message 为串/对象时外层即信封，否则看 data 下有无（数组不算）。 */
private fun pickMethodCallEnvelope(raw: JsonObject): JsonObject {
    if (hasMessage(raw["message"])) return raw
    val data = raw["data"]
    if (data is JsonObject && hasMessage(data["message"])) return data
    return raw
}

private fun hasMessage(value: JsonElement?): Boolean =
    (value is JsonPrimitive && value !is JsonNull) || value is JsonObject

/**
 * RN parseDdpResultPayload：error 存在即抛（reason ?? message ?? error ?? 'Meteor error'，空串跳过）；
 * result 缺省 → null（undefined）；result 为 JSON 串时尝试再解一层，解不动原样返回。
 */
private fun parseDdpResultPayload(inner: JsonObject): JsonElement? {
    val error = inner["error"]
    if (error != null && error !is JsonNull) {
        val err = error as? JsonObject
        val reason = err.str("reason")?.takeIf { it.isNotEmpty() }
        val message = err.str("message")?.takeIf { it.isNotEmpty() }
        val errText = when (val e = err?.get("error")) {
            null, is JsonNull -> "Meteor error"
            is JsonPrimitive -> e.content
            else -> e.toString()
        }
        throw ApiException(reason ?: message ?: errText)
    }
    var payload = inner["result"] // 键缺失 → null（JS undefined）
    if (payload is JsonPrimitive && payload !is JsonNull && payload.isString) {
        payload = runCatching { sdkJson.parseToJsonElement(payload.content) }.getOrNull() ?: payload
    }
    return payload
}
