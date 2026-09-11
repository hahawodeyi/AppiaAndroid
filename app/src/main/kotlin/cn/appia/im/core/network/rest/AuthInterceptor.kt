package cn.appia.im.core.network.rest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/** 当前 REST 会话；M1 起由 authStore 提供。 */
data class AuthSession(val token: String, val userId: String)

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * 逐行为移植 appiaMobile/src/services/sdk/restClient.ts（单个函数拆成 interceptor 两段）：
 * - 请求前：已登录时注入 X-Auth-Token / X-User-Id（TS headers 由调用方拼，此处统一）
 * - 401 且非组织切换中 → 发 SessionExpiredBus，总是抛 AuthSessionExpiredException（TS:78-83）
 * - 其余非 2xx → 按 message ?? error ?? 原始 text ?? HTTP <status> 提取后抛 ApiException（TS:85-90）
 */
class AuthInterceptor(private val authProvider: () -> AuthSession?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val session = authProvider()
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
            val message = (json?.get("message") ?: json?.get("error"))?.jsonPrimitive?.content
                ?: text.ifEmpty { null }
                ?: "HTTP ${response.code}"
            response.close()
            throw ApiException("[rocket] REST $method $endpoint failed: $message")
        }

        return response
    }
}
