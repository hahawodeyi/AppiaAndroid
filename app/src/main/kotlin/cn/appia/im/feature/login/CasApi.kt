package cn.appia.im.feature.login

import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.ServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import kotlin.random.Random

/** WebView 回调判定结果（RN AuthWebScreen onShouldStartLoadWithRequest 的两出口）。 */
sealed interface CasRedirect {
    /** 正常浏览/带 ticket 放行加载（服务端需消费 ticket，不拦）。 */
    data object Allow : CasRedirect

    /** service 参数 host 与企业服务器一致 → 触发 `login(Cas(ssoToken))` + 关页。 */
    data object Success : CasRedirect
}

/**
 * CAS SSO（逐行为移植 RN LoginScreen:87-108/:213-233 + AuthWebScreen:15-69）。
 * 登录前 WatermelonDB 无数据，CAS 按钮显隐靠公开 REST `settings.oauth` 探测；
 * 网络异常一律 null → 按钮保持隐藏（RN LoginScreen:103-105 catch 忽略）。
 */
object CasApi {

    /**
     * RN LoginScreen:91-108：GET {server}/api/v1/settings.oauth → `res?.services ?? []`
     * 中 `service === 'cas' && enabled` 的 `login_url`（trim）；未启用/未找到/空值/异常 → null。
     * 逐条目 JsonElement 遍历：单条脏数据不炸整响应（同 AuthApi 口径）。
     */
    suspend fun fetchCasLoginUrl(server: String): String? = try {
        withContext(Dispatchers.IO) {
            val url = "${ServerUrl.normalizeServer(server)}/api/v1/settings.oauth"
            RocketHttp.client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val root = json.parseToJsonElement(resp.body.string()) as? JsonObject ?: return@use null
                val services = root.get("services") as? JsonArray ?: return@use null
                val cas = services.firstOrNull {
                    (it as? JsonObject)?.textField("service") == "cas" &&
                        (it.get("enabled") as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull == true
                } as? JsonObject ?: return@use null
                cas.textField("login_url")?.trim()?.ifEmpty { null }
            }
        }
    } catch (e: CancellationException) {
        throw e // 取消不是“探测失败”，不得被隐藏按钮的 null 吞掉（Minor 统一修复）
    } catch (_: Exception) {
        null // RN:103-105 网络异常一律忽略，CAS 按钮保持隐藏
    }

    /** RN LoginScreen:222-224：17 位随机 base36（`nextInt(36)` 有界随机源，字母表纯净无负号）。 */
    fun generateSsoToken(): String = buildString {
        repeat(17) { append(Random.nextInt(36).toString(36)) }
    }

    /** RN LoginScreen:225 原样拼接（RN 未 encodeURIComponent，service 的 `://` 照旧透传）。 */
    fun buildCasUrl(casLoginUrl: String, server: String, ssoToken: String): String =
        "$casLoginUrl?service=$server/_cas/$ssoToken"

    /**
     * 回调判定纯函数（RN AuthWebScreen:37-69）：先 decodeURIComponent（%2B 保护 `+` 不折成空格，
     * 同 decodeURIComponent 语义）；带 ticket → Allow 放行服务端消费；
     * **当前跳转 URL 自身 host** == serverHost → Success（RN:50-54 `service.hostname === u.hostname`，
     * service 取自初始页 URL 即 `{server}/_cas/{token}`，故 serverHost 即企业服务器 host，屏层从初始
     * url 的 service 参数提取传入）；其余/坏 URL → Allow。
     */
    fun evaluateCasRedirect(rawUrl: String, serverHost: String): CasRedirect {
        val decoded = runCatching { URLDecoder.decode(rawUrl.trim().replace("+", "%2B"), "UTF-8") }
            .getOrNull() ?: return CasRedirect.Allow
        val url = decoded.toHttpUrlOrNull() ?: return CasRedirect.Allow
        // RN `searchParams.get('ticket')` 真值判定：空串不算 ticket
        if (!url.queryParameter("ticket").isNullOrEmpty()) return CasRedirect.Allow
        // HttpUrl.host 已小写；ignoreCase 兜底调用方传入大小写不一的 host
        return if (url.host.equals(serverHost.trim(), ignoreCase = true)) CasRedirect.Success else CasRedirect.Allow
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** RN `raw?.x != null ? String(raw.x)`：JSON null 视为无。 */
    private fun JsonObject.textField(key: String): String? = when (val value = get(key)) {
        null, is JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }
}
