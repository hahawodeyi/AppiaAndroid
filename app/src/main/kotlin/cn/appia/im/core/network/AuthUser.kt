package cn.appia.im.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 登录 `me` 载荷（RN LoginMePayload，auth/userFromLoginMe.ts:4-11）。 */
@Serializable
data class LoginMe(
    val username: String? = null,
    val name: String? = null,
    val statusText: String? = null,
    /** RN `unknown`：数组与 JSON 字符串两形态并存（parseUserRoles.ts），保持原样交给 parseUserRoles。 */
    val roles: JsonElement? = null,
    val emails: List<LoginMeEmail>? = null,
    val settings: LoginMeSettings? = null,
)

@Serializable
data class LoginMeEmail(val address: String? = null, val verified: Boolean? = null)

@Serializable
data class LoginMeSettings(val preferences: JsonObject? = null)

/** RN stores/authStore 的 AuthUser（userFromLoginMe.ts:26-42 组装的字段全集）。 */
data class AuthUser(
    val id: String,
    val username: String,
    val name: String? = null,
    val statusText: String? = null,
    val emails: List<AuthUserEmail>? = null,
    val roles: List<String>? = null,
    val preferences: JsonObject? = null,
)

data class AuthUserEmail(val address: String, val verified: Boolean?)

/**
 * RN lib/permissions/parseUserRoles.ts：`me.roles` 兼容数组与 JSON 字符串两形态。
 * 数组只留字符串元素；字符串先尝试 JSON.parse 再同样过滤；其余/解析失败 → 空。
 */
fun parseUserRoles(roles: JsonElement?): List<String> {
    if (roles == null || roles is JsonNull) return emptyList()
    return when (roles) {
        is JsonArray -> roles.mapNotNull { it.stringElementOrNull() }
        is JsonPrimitive -> if (roles.isString) parseRolesString(roles.content) ?: emptyList() else emptyList()
        else -> emptyList()
    }
}

private fun parseRolesString(raw: String): List<String>? = runCatching {
    (Json.parseToJsonElement(raw) as? JsonArray)?.mapNotNull { it.stringElementOrNull() }
}.getOrNull()

private fun JsonElement.stringElementOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull

/**
 * RN auth/userFromLoginMe.ts:26-42：登录 userId + me → AuthUser。
 * emails 规整（trim 去空、全空省略）；空 roles/preferences 省略（对应 RN 条件展开）。
 */
fun buildAuthUserFromLogin(userId: String, me: LoginMe?): AuthUser {
    val preferences = me?.settings?.preferences
    val roles = parseUserRoles(me?.roles)
    val emails = me?.emails
        ?.map { AuthUserEmail(address = it.address?.trim().orEmpty(), verified = it.verified) }
        ?.filter { it.address.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
    return AuthUser(
        id = userId,
        username = me?.username ?: "",
        name = me?.name,
        statusText = me?.statusText,
        emails = emails,
        roles = roles.ifEmpty { null },
        preferences = preferences?.takeIf { !it.isEmpty() },
    )
}
