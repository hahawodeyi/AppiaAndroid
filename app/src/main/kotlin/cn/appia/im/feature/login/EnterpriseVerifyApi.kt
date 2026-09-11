package cn.appia.im.feature.login

import cn.appia.im.core.network.RocketHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 企业校验返回的可用登录节点（RN types/companyServer.ts 逐字段）。 */
@Serializable
data class CompanyServer(
    val url: String,
    val name: String? = null,
    val ename: String? = null,
    val logo: String? = null,
    val selected: Boolean? = null,
)

/** POST {envHost}/provider/api/v1/verify 响应（RN verifyEnterprise.ts:3-7）。 */
@Serializable
data class VerifyEnterpriseResponse(
    val success: Boolean = false,
    val msg: String? = null,
    val servers: List<CompanyServer>? = null,
) {
    /** RN EnterpriseCodeScreen:74 `json?.success && json.servers?.length` 双条件才放行。 */
    val pass: Boolean get() = success && !servers.isNullOrEmpty()
}

/** RN EnterpriseCodeScreen:26。 */
const val DEFAULT_VERIFY_ENV_HOST = "https://appia.cn"

@Serializable
private data class VerifyRequest(val identity: String)

private val verifyJson = Json { ignoreUnknownKeys = true }

/**
 * 校验企业识别码，返回可用登录服务器列表（对齐 RN verifyEnterprise.ts:12-24）：
 * POST {envHost 去尾斜杠}/provider/api/v1/verify，JSON body {identity: trim}；
 * 不检查 HTTP 状态码，响应体即结果（RN 直接 response.json()）。
 * 一次性端点，OkHttp 直调共享 RocketHttp 连接池即可，不走 Retrofit。
 */
suspend fun verifyEnterprise(envHost: String, identity: String): VerifyEnterpriseResponse {
    val base = envHost.trim().trimEnd('/') // RN base = envHost.replace(/\/+$/, '')，入参 trim 归屏幕层
    val request = Request.Builder()
        .url("$base/provider/api/v1/verify")
        .post(verifyJson.encodeToString(VerifyRequest(identity.trim())).toRequestBody("application/json".toMediaType()))
        .build()
    return withContext(Dispatchers.IO) {
        RocketHttp.client.newCall(request).execute().use { resp ->
            verifyJson.decodeFromString<VerifyEnterpriseResponse>(resp.body.string())
        }
    }
}
