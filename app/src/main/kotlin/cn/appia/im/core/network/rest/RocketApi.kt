package cn.appia.im.core.network.rest

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** TS loginCredentialsRest.ts 密码登录分支（M1 登录 UI 落地时按需补 ic/sms/cas 变体）。 */
@Serializable
data class LoginRequest(val username: String, val password: String)

/**
 * TS parseSwitchOrgLoginResponse：登录响应从 .data 取 authToken/userId/me；
 * auth.ts:126 `raw?.data ?? raw` —— 兼容 `{data:{...}}` 包裹与裸 `{authToken,...}` 两形态。
 */
@Serializable
data class LoginResponse(
    val status: String? = null,
    val data: LoginData? = null,
    val userId: String? = null,
    val authToken: String? = null,
    val me: Me? = null,
) {
    /** 平铺后的会话字段：包一层取 .data，裸形态整包就是数据（与 RN 同优先级：data 优先）。 */
    val payload: LoginData?
        get() = data ?: LoginData(userId = userId, authToken = authToken, me = me)
}

@Serializable
data class LoginData(
    val userId: String? = null,
    val authToken: String? = null,
    val me: Me? = null,
)

@Serializable
data class Me(val username: String? = null, val name: String? = null)

/** GET /api/v1/info —— 会话恢复前的服务器探测。 */
@Serializable
data class ServerInfoResponse(val success: Boolean? = null, val info: ServerInfo? = null)

@Serializable
data class ServerInfo(val version: String? = null)

interface RocketApi {
    @POST("login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("info")
    suspend fun serverInfo(): ServerInfoResponse
}
