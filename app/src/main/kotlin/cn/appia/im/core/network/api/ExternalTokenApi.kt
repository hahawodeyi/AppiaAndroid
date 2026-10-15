package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * users.externalToken 换码（RN src/services/api/webAuth.ts:29-35 getUsersExternalToken，
 * 对齐 legacy restApi.ts:1183 getAuthCode）：`GET users.externalToken {url, source, platform:'APP'}`。
 *
 * **安全**：响应含 accessUrl/token（换得的凭证），本文件与调用方均不得打印/埋点响应体——
 * 埋点只允许布尔形态（hasCode/hasAccessUrl，RN InAppWebScreen :260-268 同口径）。
 */

/** RN UsersExternalTokenResult（webAuth.ts:5-9）。success 三态：null = missing（voice 流接受）。 */
data class ExternalTokenResult(
    val success: Boolean? = null,
    val accessUrl: String? = null,
    val token: String? = null,
)

/**
 * 换码：成功/失败上抛由调用方降级（RN try/catch → auth=null 裸 URL 继续）。
 * 响应经 sdk.get 平铺（`data ?? resp`，RocketSdk 既有口径）。
 *
 * params：web 流传 {url, source, platform}（RN webAuth.ts:29-35）；voice 流传 null——
 * RN voice 路径（webAuth.ts:12-27）与 legacy iOS（server.js:318）均为裸端点 + 仅鉴权头。
 */
suspend fun fetchExternalToken(sdk: RocketSdk, url: String, params: Map<String, String>?): ExternalTokenResult =
    parseExternalToken(sdk.get("users.externalToken", params))

/** web 流变体：`GET users.externalToken {url, source, platform:'APP'}`。 */
suspend fun fetchExternalToken(sdk: RocketSdk, url: String, source: String): ExternalTokenResult =
    fetchExternalToken(sdk, url, mapOf("url" to url, "source" to source, "platform" to "APP"))

internal fun parseExternalToken(raw: JsonElement?): ExternalTokenResult {
    val o = raw as? JsonObject ?: return ExternalTokenResult()
    fun str(key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
    return ExternalTokenResult(
        success = (o["success"] as? JsonPrimitive)?.let { it.content == "true" },
        accessUrl = str("accessUrl"),
        token = str("token"),
    )
}
