package cn.appia.im.core.mail

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/** mailClient.ts MailApiError 同构：message 已按 parseErrorMessage 提取。 */
class MailApiException(val status: Int, message: String) : Exception(message)

/** mailClient.ts MailClientAuth：逐请求经 authProvider 解析（换组织/重登录后取新值）。 */
data class MailClientAuth(val authToken: String, val userId: String)

/**
 * exchange 会话 CookieJar（坑 14）：mail 域独立于 RocketSdk 的内存 cookie 库——
 * `/auth/appiaim-exchange` 建立的会话 cookie 由本 jar 存取（RN 手动组 Cookie 头/回写 Set-Cookie，
 * OkHttp CookieJar 等价承载）。进程内存活期 = 会话存活期。
 */
class MailCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val hostStore = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { hostStore[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.values?.filter { it.matches(url) }.orEmpty()

    /** 重新 exchange（换组织/重登录）前清空旧会话 cookie。 */
    fun clear() = store.clear()
}

/**
 * 独立 mail HTTP client（逐行为移植 appiaMobile/src/services/mail/mailClient.ts）：
 * - 基址 `{server}/mail/api` 逐请求解析（serverUrl 可随设置变化）
 * - 每请求 X-Auth-Token + X-User-Id（mailClient.ts:76-82）
 * - Cookie 经 [MailCookieJar] 发送/回写（:103-146）
 * - 401 → POST /auth/refresh → 原请求重试一次（:158-167，refresh 失败按原 401 抛错不重试）
 */
class MailClient(
    private val serverUrl: () -> String,
    /** 公开只读：bootstrapMailSession 需要 authProvider 判空（M9-T3 mailChannelApi 装配用）。 */
    val authProvider: () -> MailClientAuth?,
    baseHttpClient: OkHttpClient = OkHttpClient(),
) {
    val cookieJar = MailCookieJar()
    private val http = baseHttpClient.newBuilder().cookieJar(cookieJar).build()
    private val json = mailJson

    /** mailApiBase.ts resolveMailApiBase：`server + '/mail/api'`（逐请求解析）。 */
    private fun apiBase(): String = serverUrl().trimEnd('/') + "/mail/api"

    /** mailClient.ts buildUrl + buildPathWithSearch：params 中 null/空串跳过（TS :49-62）。 */
    fun url(path: String, params: Map<String, Any?> = emptyMap()): String {
        val suffix = if (path.startsWith('/')) path else "/$path"
        val builder = (apiBase() + suffix).toHttpUrl().newBuilder()
        params.forEach { (key, value) ->
            if (value != null && value != "") builder.addQueryParameter(key, value.toString())
        }
        return builder.build().toString()
    }

    /**
     * mailClient.ts request：成功 → 解析 T（204/空体 → null）；非 2xx 抛 [MailApiException]
     * （message 经 [parseErrorMessage]）。401 且 !skipRefresh 时先 POST /auth/refresh，成功则重试一次。
     */
    fun <T> request(
        path: String,
        deserializer: DeserializationStrategy<T>,
        method: String = "GET",
        body: JsonElement? = null,
        params: Map<String, Any?> = emptyMap(),
        skipRefresh: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val res = execute(url(path, params), method, body, headers)
        if (res.code == 401 && !skipRefresh) {
            // 先耗尽原 401 体并关闭（refresh 失败时仍按原响应语义抛错——TS :160-165 落回 !res.ok 分支）
            val originalMessage = res.use { it.body?.string().orEmpty() }
            // RN refresh 是裸 POST（mailClient.ts:160-163 仅 method）——不透传原请求 headers；
            // X-Auth 头由 execute 内部 authProvider 机制统一附加
            val refreshed = execute(url("/auth/refresh"), "POST", null, emptyMap())
            val refreshOk = refreshed.isSuccessful
            refreshed.close()
            if (refreshOk) {
                val retried = execute(url(path, params), method, body, headers)
                return readBody(retried, deserializer)
            }
            throw MailApiException(401, parseErrorMessage(originalMessage, 401))
        }
        return readBody(res, deserializer)
    }

    /** 便捷入口：@Serializable 模型直传（反序列化器由 reified T 推导）。 */
    inline fun <reified T> request(
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
        params: Map<String, Any?> = emptyMap(),
        skipRefresh: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): T = request(path, serializer<T>(), method, body, params, skipRefresh, headers)

    private fun execute(url: String, method: String, body: JsonElement?, headers: Map<String, String>): Response {
        val builder = Request.Builder().url(url)
        val auth = authProvider()
        if (!auth?.authToken.isNullOrBlank()) builder.header("X-Auth-Token", auth!!.authToken)
        if (!auth?.userId.isNullOrBlank()) builder.header("X-User-Id", auth!!.userId)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (body != null) {
            val payload = json.encodeToString(JsonElement.serializer(), body)
            val contentType = headers["Content-Type"]?.toMediaType() ?: "application/json".toMediaType()
            builder.method(method, payload.toRequestBody(contentType))
        } else if (method == "POST" || method == "PUT" || method == "PATCH") {
            // RN fetch 无 body POST 直接发；OkHttp 要求 method 带 body——空体等价（/auth/refresh 即此形态）
            builder.method(method, ByteArray(0).toRequestBody(null))
        } else {
            builder.method(method, null)
        }
        return http.newCall(builder.build()).execute()
    }

    /** mailClient.ts :168-186：204/空体成功 → null；!ok → 提取 message 抛 MailApiException。 */
    private fun <T> readBody(res: Response, deserializer: DeserializationStrategy<T>): T {
        res.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 204 || (text.isEmpty() && it.isSuccessful)) {
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
            if (!it.isSuccessful) throw MailApiException(it.code, parseErrorMessage(text, it.code))
            return json.decodeFromString(deserializer, text)
        }
    }

    companion object {
        /**
         * mailClient.ts parseErrorMessage：空体 → 默认文案；JSON {message: string|string[]|error} → 提取；
         * 非 JSON → 原文。
         */
        fun parseErrorMessage(text: String, status: Int): String {
            if (text.isEmpty()) return "Mail request failed: $status"
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
            (obj["message"] as? JsonPrimitive)?.let { return it.content }
            (obj["message"] as? JsonArray)?.let { array ->
                return array.joinToString(", ") { (it as JsonPrimitive).content }
            }
            (obj["error"] as? JsonPrimitive)?.let { return it.content }
            return text
        }

        /**
         * mailApi.ts:35-39 isMailThrottleError：HTTP 429 或 message 含
         * "too many login attempts"/"too frequent" → 绑定/测试连接专用节流提示（坑 5）。
         */
        fun isMailThrottleError(error: Throwable): Boolean =
            error is MailApiException &&
                (error.status == 429 ||
                    error.message?.contains("too many login attempts") == true ||
                    error.message?.contains("too frequent") == true)
    }
}
