package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 用户偏好/状态端点（M5-T9，RN services/api/userPreferences.ts + userStatus.ts +
 * logoutOtherDevices.ts 逐 wire 对照）：
 * - `POST users.setPreferences` body `{userId, data}`（RN setUserPreferences）
 * - `POST users.setStatus` body `{status, message}`（RN setUserStatus——缺省字段不编码）
 * - `POST users.removeOtherTokens` body `{userId}`（RN logoutOtherDevices——注销其它设备登录）
 * 只发请求不做本地写；成功后的会话合并归 [cn.appia.im.core.datastore.AuthSessionStore]。
 */
object UserPreferencesApi {

    /** RN SetUserPreferencesParams：data 值为标量（boolean/字符串——调用方 JsonPrimitive 构造）。 */
    suspend fun setUserPreferences(
        sdk: RocketSdk,
        userId: String,
        data: Map<String, JsonElement>,
    ): JsonElement = sdk.post(
        "users.setPreferences",
        buildJsonObject {
            put("userId", userId)
            put("data", JsonObject(data))
        },
    )

    /** RN setUserStatus({status, message})：null 字段不编码（JS undefined 同义）。 */
    suspend fun setUserStatus(
        sdk: RocketSdk,
        status: String? = null,
        message: String? = null,
    ): JsonElement = sdk.post(
        "users.setStatus",
        buildJsonObject {
            status?.let { put("status", it) }
            message?.let { put("message", it) }
        },
    )

    /** RN logoutOtherDevices：`POST users.removeOtherTokens {userId}`。 */
    suspend fun removeOtherTokens(sdk: RocketSdk, userId: String): JsonElement =
        sdk.post("users.removeOtherTokens", buildJsonObject { put("userId", userId) })
}

/**
 * RN lib/userSessionPrefs.ts parseBoolUserPref：boolean → 本身；字符串 → 非 'false'/'0' 即真；
 * 其余（null/缺字段/数字等非字符串）回退 [fallback]（调用方传 true 与 RN 消息摘要缺省一致）。
 */
fun parseBoolUserPref(value: JsonElement?, fallback: Boolean = true): Boolean {
    val p = value as? kotlinx.serialization.json.JsonPrimitive ?: return fallback
    if (p is kotlinx.serialization.json.JsonNull) return fallback
    if (!p.isString) {
        // 布尔原语 content 恒为 "true"/"false"（RN boolean 分支）；数字等非字符串原语回退（RN 同）
        return if (p.content == "true" || p.content == "false") p.content == "true" else fallback
    }
    return p.content != "false" && p.content != "0"
}
