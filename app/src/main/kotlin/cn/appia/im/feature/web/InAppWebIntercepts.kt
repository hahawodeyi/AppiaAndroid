package cn.appia.im.feature.web

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.defaultPort
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * InAppWeb 拦截族纯逻辑（M7-T5，RN src/lib/inAppWeb/ 各文件逐义）：
 * 石墨拼参、WPS 入口改写、返回决策链、腾讯会议外链匹配、VPN 探活、泛微 token 进程内去重。
 * 全部无 Android 依赖可单测；副作用（注入/拉外链）在 InAppWebScreen.kt。
 */

// ── ② 石墨 appendShimoQueryIfNeeded（RN resolveWebViewUrl.ts:4-20 逐义）──

/**
 * 石墨 Web：URL 含 `shimo-web` 且不含 token/userId（子串级，RN 同口径）→ 追加
 * org（enterpriseId 小写）/source=appia/userId/token；其余原样。
 */
fun appendShimoQueryIfNeeded(
    urlStr: String,
    enterpriseId: String,
    userId: String,
    token: String,
): String {
    if (!urlStr.contains("shimo-web")) return urlStr
    if (urlStr.contains("token") || urlStr.contains("userId")) return urlStr
    val base = urlStr.toHttpUrlOrNull() ?: return urlStr
    return base.newBuilder()
        .setQueryParameter("org", enterpriseId.lowercase())
        .setQueryParameter("source", "appia")
        .setQueryParameter("userId", userId)
        .setQueryParameter("token", token)
        .build()
        .toString()
}

// ── ③ WPS 入口改写（RN resolveWebViewUrl.ts:108-152 + wpsOrgHosts.ts）──

/** RN WPS_ORG_DOC_HOSTS（wpsOrgHosts.ts:5-12 逐字）。 */
data class WpsOrgDocHost(val org: String, val baseUrl: String)

val WPS_ORG_DOC_HOSTS = listOf(
    WpsOrgDocHost("bitmain", "https://docs.bitmain.vip/"),
    WpsOrgDocHost("ssc", "https://ssc-docs.appia.vip/"),
    WpsOrgDocHost("sophgo", "https://docs.sophgo.com/"),
    WpsOrgDocHost("antalpha", "https://antalpha-docs.appia.vip/"),
    WpsOrgDocHost("antpool", "https://antpool-docs.appia.vip/"),
    WpsOrgDocHost("all", "https://docs.appia.vip/"),
)

/** JS encodeURIComponent 等价（URLEncoder 的 + 与 !'()* 差异校正）。 */
internal fun jsEncodeURIComponent(s: String): String =
    URLEncoder.encode(s, Charsets.UTF_8.name())
        .replace("+", "%20")
        .replace("%21", "!").replace("%27", "'")
        .replace("%28", "(").replace("%29", ")").replace("%2A", "*")

private fun HttpUrl.origin(): String = buildString {
    append(scheme); append("://"); append(host)
    if (port != defaultPort(scheme)) { append(':'); append(port) }
}

/**
 * 金山文档：URL 命中 WPS_ORG_DOC_HOSTS 任一 host（子串级）→ 改为
 * `{host}/c/bitedzservice/getAccessToken?org=<epId 小写>&redirect_url=<encoded>`。
 * docs.appia.vip（'all' 域）按 epId 匹配组织域，未命中回退 'all'（origin 替换同 RN）。
 * 未命中 → null（不改写）。
 */
fun rewriteWpsAccessEntryUrl(urlStr: String, enterpriseIdRaw: String): String? {
    val matched = WPS_ORG_DOC_HOSTS.firstOrNull { item ->
        item.baseUrl.toHttpUrlOrNull()?.host?.let { urlStr.contains(it) } == true
    } ?: return null
    val matchedHost = matched.baseUrl.toHttpUrlOrNull()?.host ?: return null

    val epId = enterpriseIdRaw.lowercase()
    var itemBase: WpsOrgDocHost = WPS_ORG_DOC_HOSTS.first { it.baseUrl.toHttpUrlOrNull()?.host == matchedHost }
    val urlObj = urlStr.toHttpUrlOrNull() ?: return null
    if (urlObj.host == "docs.appia.vip") {
        itemBase = WPS_ORG_DOC_HOSTS.firstOrNull { it.org == epId }
            ?: WPS_ORG_DOC_HOSTS.first { it.org == "all" }
    }
    val host = itemBase.baseUrl.trimEnd('/')
    var nextUrl = urlStr
    if (urlObj.host == "docs.appia.vip") {
        nextUrl = urlStr.replaceFirst(urlObj.origin(), host)
    }
    return "$host/c/bitedzservice/getAccessToken?org=${jsEncodeURIComponent(epId)}" +
        "&redirect_url=${jsEncodeURIComponent(nextUrl)}"
}

// ── ④ 会议外链（RN meetingUrlPatterns.ts 逐义）──

/** /^https:\/\/meeting\.tencent\.com/i */
fun isTencentMeetingHttpUrl(url: String): Boolean = url.startsWith("https://meeting.tencent.com", ignoreCase = true)

/** /^wemeet:\/\//i */
fun isWemeetSchemeUrl(url: String): Boolean = url.startsWith("wemeet://", ignoreCase = true)

// ── ⑥ 返回决策链（RN resolveInAppWebBackAction.ts 完整移植）──

val BACK_CLOSE_URL_SEGMENTS = listOf("/approve/list", "/error", "/404", "/500", "/403")

val ANT_AGENT_FORCE_POP_URLS = listOf(
    "https://ssc.antagent.space/#/search",
    "https://ssc.antagent.space/#/login",
)

enum class InAppWebBackAction { WEBVIEW_BACK, STACK_POP }

/** RN getComparableUrl：hash 页取 origin+hash 去 query；普通页取 origin+pathname。 */
private fun comparableUrl(url: String): String? {
    val u = url.toHttpUrlOrNull() ?: return null
    val origin = u.origin()
    val fragment = u.fragment
    return if (!fragment.isNullOrEmpty()) origin + "#" + fragment.substringBefore('?')
    else origin + u.encodedPath
}

private fun matchesBackCloseUrl(currentUrl: String): Boolean =
    BACK_CLOSE_URL_SEGMENTS.any { currentUrl.indexOf(it) > 0 }

/**
 * 返回裁定（RN resolveInAppWebBackAction.ts:39-78 逐分支）：
 * chat-gpt source → 出栈；BACK_CLOSE_URL_SEGMENTS 段命中 → 出栈；canGoBack 时
 * 已回根页（origin+hash 去 query 比较）或 antagent 强制出栈 URL → 出栈，否则 webview 返回；
 * 无历史 → 一律出栈（泛微/普通页同）。
 */
fun resolveInAppWebBackAction(
    source: String?,
    currentUrl: String,
    canGoBack: Boolean,
    fanweiMobileUrl: String,
    rootUrl: String?,
): InAppWebBackAction {
    if (source == "chat-gpt") return InAppWebBackAction.STACK_POP
    if (matchesBackCloseUrl(currentUrl)) return InAppWebBackAction.STACK_POP

    if (canGoBack) {
        if (rootUrl != null) {
            val current = comparableUrl(currentUrl)
            val root = comparableUrl(rootUrl)
            if (current != null && root != null && current == root) return InAppWebBackAction.STACK_POP
            if (currentUrl in ANT_AGENT_FORCE_POP_URLS) return InAppWebBackAction.STACK_POP
            return InAppWebBackAction.WEBVIEW_BACK
        }
        if (currentUrl in ANT_AGENT_FORCE_POP_URLS) return InAppWebBackAction.STACK_POP
        return InAppWebBackAction.WEBVIEW_BACK
    }

    // RN 末两分支同值（fanwei 与普通页均出栈），合并保留注释对齐
    return InAppWebBackAction.STACK_POP
}

// ── ⑦ needVPN 探活（RN vpnReachability.ts：HEAD 10s 超时）──

/**
 * HEAD 探活：任何 HTTP 响应即可达（RN fetch 4xx 不 throw 同口径）；异常/超时 → false。
 * [probe] 注入便于单测（RN fetchImpl 同缝）。
 */
suspend fun isVpnReachable(
    url: String,
    timeoutMs: Long = 10_000L,
    probe: suspend (String, Long) -> Boolean = ::vpnHeadProbe,
): Boolean = withTimeoutOrNull(timeoutMs) { probe(url, timeoutMs) } ?: false

/** OkHttp HEAD：响应到达（含 4xx/5xx）→ true；IO 异常/超时 → false。 */
internal suspend fun vpnHeadProbe(url: String, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
    val client = okhttp3.OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    try {
        client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { true }
    } catch (_: Exception) {
        false
    }
}

// ── ① 泛微 token 进程内去重（RN webAuth.ts:30-52）──

/** RN webAuth.ts:46-50：读 `proxy/hrm/resource/token` → `result.data`；空/缺 → 空串。 */
internal fun parseWeaverToken(raw: JsonElement?): String {
    val o = raw as? JsonObject ?: return ""
    val d = (o["result"] as? JsonObject)?.get("data") as? JsonPrimitive ?: return ""
    return if (d is JsonNull) "" else d.content
}

/** 预取（RN getFanweiWeaverToken 同缝）；异常上抛由 [FanweiWeaverTokenGate] 静默化。 */
internal suspend fun fetchFanweiWeaverToken(sdk: RocketSdk): String = parseWeaverToken(
    sdk.get("proxy/hrm/resource/token"),
)

/**
 * 进程内 promise 去重（RN fanweiWeaverTokenRequest 同语义）：并发调用共享一次请求；
 * settle 后清缓存（后续调用重取）；失败静默空串。fetch 挂在门级 scope——调用方取消
 * 不殃及其他等待者（RN 共享 promise 同性质）。
 */
class FanweiWeaverTokenGate(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()

    @Volatile
    private var inFlight: Deferred<String>? = null

    suspend fun get(fetch: suspend () -> String): String {
        inFlight?.takeIf { it.isActive }?.let { return it.await() }
        return mutex.withLock {
            inFlight?.takeIf { it.isActive }?.let { return it.await() }
            val d = scope.async { runCatching { fetch() }.getOrDefault("") }
            inFlight = d
            try {
                d.await()
            } finally {
                inFlight = null
            }
        }
    }
}

/** 进程级单例（RN 模块级 fanweiWeaverTokenRequest）。 */
val fanweiWeaverTokenGate = FanweiWeaverTokenGate()
