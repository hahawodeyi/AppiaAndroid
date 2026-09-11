package cn.appia.im.core.network.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.Response

/** 当前 REST 会话；M1 起由 authStore 提供。 */
data class AuthSession(val token: String, val userId: String)

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * 逐行为移植 appiaMobile/src/services/sdk/restClient.ts（单个函数拆成 interceptor 两段）：
 * - 请求前：已登录时注入 X-Auth-Token / X-User-Id；login 端点除外
 *   （TS headers 由调用方按次拼，login 调用不带——换组织/重登录不得携带旧组织 token）
 * - 401 且非组织切换中 → 发 SessionExpiredBus，总是抛 AuthSessionExpiredException（TS:78-83）
 * - 其余非 2xx → 按 message ?? error ?? 原始 text ?? HTTP <status> 提取后抛 ApiException（TS:85-90）
 */
class AuthInterceptor(private val authProvider: () -> AuthSession?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = if (request.url.encodedPathSegments.lastOrNull() == "login") null else authProvider()
        val authorized = if (session == null) {
            request
        } else {
            request.newBuilder()
                .header("X-Auth-Token", session.token)
                .header("X-User-Id", session.userId)
                .build()
        }

        val response = chain.proceed(authorized)
        val method = request.method
        val endpoint = request.url.encodedPath.removePrefix("/api/v1/")

        if (response.code == 401) {
            if (!OrgSwitchState.isInProgress()) {
                SessionExpiredBus.emit() // TS:79-81 authActions.logout()
            }
            response.close()
            throw AuthSessionExpiredException()
        }

        if (!response.isSuccessful) {
            // TS:65-74 先读 text 再尝试 JSON 解析
            val text = response.body.string()
            val json = runCatching { errorJson.parseToJsonElement(text).jsonObject }.getOrNull()
            val message = field(json, "message")
                ?: field(json, "error")
                ?: text.ifEmpty { null }
                ?: "HTTP ${response.code}"
            response.close()
            throw ApiException("[rocket] REST $method $endpoint failed: $message")
        }

        return response
    }

    /** TS `json[name]`：缺失/JSON null 视为无，非原始类型（对象/数组）回退下一级，不抛。 */
    private fun field(json: JsonObject?, name: String): String? =
        when (val value = json?.get(name)) {
            null, is JsonNull -> null
            is JsonPrimitive -> value.content
            else -> null
        }
}
