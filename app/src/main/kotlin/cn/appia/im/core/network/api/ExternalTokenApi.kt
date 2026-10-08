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

/** RN UsersExternalTokenResult（webAuth.ts:5-9）。 */
data class ExternalTokenResult(
    val success: Boolean = false,
    val accessUrl: String? = null,
    val token: String? = null,
)

/**
 * 换码：成功/失败上抛由调用方降级（RN try/catch → auth=null 裸 URL 继续）。
 * 响应经 sdk.get 平铺（`data ?? resp`，RocketSdk 既有口径）。
 */
suspend fun fetchExternalToken(sdk: RocketSdk, url: String, source: String): ExternalTokenResult =
    parseExternalToken(
        sdk.get("users.externalToken", mapOf("url" to url, "source" to source, "platform" to "APP")),
    )

internal fun parseExternalToken(raw: JsonElement?): ExternalTokenResult {
    val o = raw as? JsonObject ?: return ExternalTokenResult()
    fun str(key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
    return ExternalTokenResult(
        success = (o["success"] as? JsonPrimitive)?.contentOrNull == "true",
        accessUrl = str("accessUrl"),
        token = str("token"),
    )
}
