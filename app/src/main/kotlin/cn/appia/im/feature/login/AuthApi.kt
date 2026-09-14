package cn.appia.im.feature.login

import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.core.network.LoginRequestFactory
import cn.appia.im.core.network.LoginMe
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.ServerUrl
import cn.appia.im.core.network.rest.ApiException
import cn.appia.im.core.network.rest.AuthInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** RN auth.ts:25 `LoginAreaCodeOption`；响应条目多余字段忽略（ignoreUnknownKeys）。 */
@Serializable
data class LoginAreaCodeOption(
    val label: String = "",
    val areaCode: String = "",
    val code: String? = null,
)

/** RN auth.ts:70 sendCode 结果 `{success, message?}` 透传给调用方展示。 */
data class SendCodeResult(val success: Boolean, val message: String? = null)

/**
 * 短信发码 / 区号列表 / 登录编排（逐行为移植 appiaMobile/src/services/api/auth.ts:21-90）。
 * 三端点均为预登录调用：OkHttp 直调共享 RocketHttp 连接池，挂 AuthInterceptor{null}
 * 只为复用 restClient 错误链（401 会话过期 / 其余非 2xx → ApiException，TS:76-90）。
 */
object AuthApi {
    /** 登录类 REST 调用点超时（RN restClient 调用点 timeoutMs:30_000，T2 评审指针）。 */
    const val LOGIN_TIMEOUT_MS = 30_000L

    /**
     * 进程级 sdk 单例：login 编排复用（T2 预检裁定；session 层 wire 监听归 T8 收敛）。
     * T11 DI 收口：SessionModule 复用本实例作为全 app 唯一 RocketSdk（RN 单 sdk 语义——
     * 登录建连与 bootstrap 重连同实例，避免双 socket 残留；internal 仅供装配，业务仍走 AuthApi 门面）。
     */
    internal val sdk = RocketSdk()

    /** RN auth.ts:28 模块级缓存：key=`host|locale`；只存成功非空列表，回落不入缓存。 */
    private val areaCodeCache = ConcurrentHashMap<String, List<LoginAreaCodeOption>>()

    private val rest = RocketHttp.client.newBuilder().addInterceptor(AuthInterceptor { null }).build()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * RN loginSendCode auth.ts:67-81：POST {host}/api/v1/login.sendCode，
     * body `{phone, areaCode, ic: ic ?? {}}`（ic 为滑块票据，无 WebView 时空对象）；
     * `raw.success !== false` 即成功（缺省/非布尔 false 都放行），message 透传展示。
     */
    suspend fun loginSendCode(
        host: String,
        phone: String,
        areaCode: String,
        ic: JsonElement? = null,
    ): SendCodeResult {
        val raw = restCall(
            host,
            "login.sendCode",
            body = buildJsonObject {
                put("phone", phone)
                put("areaCode", areaCode)
                put("ic", if (ic == null || ic is JsonNull) JsonObject(emptyMap()) else ic)
            },
        )
        val obj = raw as? JsonObject
        return SendCodeResult(
            // JS `raw?.success !== false`：仅布尔 false 判失败；缺省 / "false" 字符串都放行
            success = (obj?.get("success") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != false,
            message = obj.textField("message"),
        )
    }

    /**
     * RN loginGetAreaCodes auth.ts:37-62：GET {host}/api/v1/getAreaCode?locale=，
     * 模块级缓存 key=`host|locale`（RN normalizeLoginHost 去尾斜杠后拼）；
     * 失败/空列表回落 +86 中国（RN AreaCodeScreen:25-47，绑定裁定收敛进 API），回落不写缓存。
     */
    suspend fun loginGetAreaCodes(host: String, locale: String, fallbackLabel: String): List<LoginAreaCodeOption> {
        val key = "${ServerUrl.normalizeServer(host)}|$locale"
        areaCodeCache[key]?.let { return it }
        val list = try {
            val raw = restCall(host, "getAreaCode", method = "GET", params = mapOf("locale" to locale))
            val data = (raw as? JsonObject)?.get("data")
            if (data is JsonArray) json.decodeFromJsonElement<List<LoginAreaCodeOption>>(data) else emptyList()
        } catch (e: CancellationException) {
            throw e // 取消不是“拉取失败”，不得被回落 +86 吞掉（Minor 统一修复）
        } catch (_: Exception) {
            emptyList()
        }
        if (list.isEmpty()) {
            return listOf(LoginAreaCodeOption(label = fallbackLabel, areaCode = "+86", code = "CN"))
        }
        areaCodeCache[key] = list
        return list
    }

    /**
     * RN login auth.ts:87-90（T2 预检裁定的原生等价）：initialize → connect → sdk.login，
     * 30s 超时落在 login 的 REST 请求上（sdk.login timeoutMs → per-call Call.timeout）。
     * RN 的 prepareSocketConnection（停监听→disconnect→wire→connect，不切库）归 T8 prepareSocketConnection。
     */
    suspend fun login(
        host: String,
        credentials: LoginCredentials,
        timeoutMs: Long = LOGIN_TIMEOUT_MS,
    ): LoginResult {
        sdk.initialize(host)
        sdk.connect()
        return sdk.login(credentials, timeoutMs)
    }

    /**
     * RN switchOrgLoginViaRest auth.ts:131-146：向目标 host 发三字段换票 REST
     * （`POST login` `{userId, userToken, url: 旧主体}`，30s 超时），**不依赖 sdk.server 已指向目标**；
     * 预登录通道（AuthInterceptor{null}）→ 不携带任何旧组织鉴权头。
     * 响应按 parseSwitchOrgLoginResponse（auth.ts:124-129）`raw?.data ?? raw` 取会话字段，
     * 缺 authToken/userId 抛 ApiException（RN 'Switch response missing authToken/userId'）。
     */
    suspend fun switchOrgLoginViaRest(
        targetHost: String,
        userId: String,
        userToken: String,
        url: String,
    ): LoginResult {
        val call = LoginRequestFactory.create(LoginCredentials.SwitchOrg(userId, userToken, url))
        val raw = restCall(targetHost, call.endpoint, body = call.body, timeoutMs = LOGIN_TIMEOUT_MS)
        val data = when (val wrapper = (raw as? JsonObject)?.get("data")) {
            null, is JsonNull -> raw
            else -> wrapper
        }
        val obj = data as? JsonObject
        val authToken = obj.textField("authToken")
        val newUserId = obj.textField("userId")
        if (authToken.isNullOrEmpty() || newUserId.isNullOrEmpty()) {
            throw ApiException("[rocket] switch org login: missing authToken/userId")
        }
        val me = obj?.get("me")?.let { value ->
            runCatching { json.decodeFromJsonElement<LoginMe>(value.jsonObject) }.getOrNull()
        }
        return LoginResult(authToken = authToken, userId = newUserId, me = me)
    }

    // ---- 内部 ----

    /** RN rocketRestRequest restClient.ts:35-99 的预登录子集：URL 组装 + JSON 编解码 + `json ?? {}`。 */
    private suspend fun restCall(
        host: String,
        endpoint: String,
        method: String = "POST",
        body: JsonElement? = null,
        params: Map<String, String>? = null,
        timeoutMs: Long? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
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
                post((body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
            }
        }.build()
        val call = rest.newCall(request)
        // RN restClient.ts:44-50 AbortController：per-call 超时（换票 30s 同款通道）
        timeoutMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { resp ->
            val text = resp.body.string()
            // RN `json ?? {}`：空体/解析失败都当空对象（sendCode 缺省 success → true；getAreaCode → 空 → 回落）
            if (text.isEmpty()) {
                JsonObject(emptyMap())
            } else {
                runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonObject(emptyMap()))
            }
        }
    }

    /** RN `raw?.message != null ? String(raw.message)`：JSON null 视为无。 */
    private fun JsonObject?.textField(key: String): String? = when (val value = this?.get(key)) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> value.toString()
    }

    private fun encodeURIComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- 测试缝（同模块 internal）----

    internal fun clearAreaCodeCacheForTest() = areaCodeCache.clear()

    internal val sdkForTest: RocketSdk get() = sdk
}
