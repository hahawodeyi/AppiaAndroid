package cn.appia.im.core.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `POST /api/v1/{endpoint}` 的登录请求形态（RN LoginRestCall，loginCredentialsRest.ts:9）。 */
data class LoginRestCall(val endpoint: String, val body: JsonObject)

/**
 * 凭证 → 端点/请求体，逐字段对照 RN loginCredentialsRest.ts:20-75：
 * - Cas → `login` `{cas:{credentialToken}}`（RN :23-28）
 * - Sms → `login` `{smsCode:true, phone, code, areaCode}`，不带 ic（RN :30-42；ic 由变体刻意去除，见 Sms KDoc）
 * - SwitchOrg → `login` `{userId, userToken, url}`（RN :44-50）
 * - Password+ldap → `verify-ic` `{username, ldapPass, ldap:true, ldapOptions:{}, ic?}`（RN :54-65）
 * - Password → `login` `{username, password, ic?}`（RN :67-74）
 *
 * 字段顺序与 RN 的对象字面量插入序一致（wire 序列化顺序 = buildJsonObject 插入序）。
 */
object LoginRequestFactory {
    fun create(credentials: LoginCredentials): LoginRestCall = when (credentials) {
        is LoginCredentials.Cas -> LoginRestCall(
            "login",
            buildJsonObject {
                put("cas", buildJsonObject { put("credentialToken", credentials.credentialToken) })
            },
        )

        is LoginCredentials.Sms -> LoginRestCall(
            "login",
            buildJsonObject {
                put("smsCode", true)
                put("phone", credentials.phone)
                put("code", credentials.code)
                put("areaCode", credentials.areaCode)
            },
        )

        is LoginCredentials.SwitchOrg -> LoginRestCall(
            "login",
            buildJsonObject {
                put("userId", credentials.userId)
                put("userToken", credentials.userToken)
                put("url", credentials.url)
            },
        )

        is LoginCredentials.Password ->
            if (credentials.ldap) {
                LoginRestCall(
                    "verify-ic",
                    buildJsonObject {
                        put("username", credentials.username)
                        put("ldapPass", credentials.password)
                        put("ldap", true)
                        put("ldapOptions", JsonObject(emptyMap()))
                        credentials.ic?.let { put("ic", it) }
                    },
                )
            } else {
                LoginRestCall(
                    "login",
                    buildJsonObject {
                        put("username", credentials.username)
                        put("password", credentials.password)
                        credentials.ic?.let { put("ic", it) }
                    },
                )
            }
    }
}
