package cn.appia.im.feature.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.ExternalTokenResult
import cn.appia.im.core.network.api.fetchExternalToken
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.settings.SettingsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * needAuth 白名单（RN src/lib/inAppWeb/needAuthWhitelist.ts:15-19 NEED_AUTH_HOSTS 三域逐字）：
 * host 命中其本身或子域名 → 自动 needAuth，source 透传给 users.externalToken。
 * needVPN（lexiang）在 Android 壳内不探测内网可达（vpnReachability 归 M7 域，失败由页面自然加载）。
 */
data class NeedAuthHostRule(val host: String, val source: String, val needVPN: Boolean = false)

val NEED_AUTH_HOSTS = listOf(
    NeedAuthHostRule(host = "survey.appia.cn", source = ""),
    NeedAuthHostRule(host = "lexiang.appia.cn", source = "", needVPN = true),
    NeedAuthHostRule(host = "ssc-docs.appia.vip", source = ""),
)

/** RN matchNeedAuthHost（needAuthWhitelist.ts:22-33）：host 命中本身或子域名。 */
fun matchNeedAuthHost(url: String, hosts: List<NeedAuthHostRule> = NEED_AUTH_HOSTS): NeedAuthHostRule? {
    val host = runCatching { url.trim().toHttpUrlOrNull()?.host }.getOrNull() ?: return null
    return hosts.find { host == it.host || host.endsWith(".${it.host}") }
}

/** RN resolveInAppWebViewParams（needAuthWhitelist.ts:48-60）：白名单命中强制 needAuth。 */
fun resolveInAppWebViewParams(
    url: String,
    needAuth: Boolean = false,
    source: String? = null,
): Pair<Boolean, String?> {
    val rule = matchNeedAuthHost(url)
    return (rule != null || needAuth) to (rule?.source ?: source)
}

/**
 * needAuth 拼 code（RN resolveWebViewUrl.ts buildNeedAuthUrl :26-103 最小面）：
 * accessUrl 命中 → 直接改跳 accessUrl（LEXIANG source 叠参保留）；仅 token → 追加
 * from/code/userId/username/enterpriseId query；两者皆空（失败降级）→ 裸 URL 原样返回。
 * urlType=1 hash query 与 chat-gpt sessionToken 分支未入（Android 无调用方传，M7 域再补）。
 */
fun buildNeedAuthUrl(
    rawUrl: String,
    auth: ExternalTokenResult?,
    source: String?,
    userId: String,
    username: String,
    enterpriseId: String,
): String {
    val accessUrl = auth?.accessUrl
    if (!accessUrl.isNullOrEmpty()) {
        if (source != "LEXIANG") return accessUrl
        val base = accessUrl.toHttpUrlOrNull() ?: return accessUrl
        val b = base.newBuilder()
        listOf(
            "from" to "appia",
            "code" to (auth?.token ?: ""),
            "userId" to userId,
            "username" to username,
            "enterpriseId" to enterpriseId,
        ).forEach { (k, v) ->
            if (v.isNotEmpty() && b.build().queryParameter(k) == null) b.addQueryParameter(k, v)
        }
        return b.build().toString()
    }
    val code = auth?.token.orEmpty()
    if (code.isEmpty()) return rawUrl
    val base = rawUrl.toHttpUrlOrNull() ?: return rawUrl
    val b = base.newBuilder()
    listOf(
        "from" to "appia",
        "code" to code,
        "userId" to userId,
        "username" to username,
        "enterpriseId" to enterpriseId,
    ).forEach { (k, v) -> b.setQueryParameter(k, v) }
    return b.build().toString()
}

/**
 * 返回栈裁定（RN resolveInAppWebBackAction.ts 最小面）：
 * canGoBack 且当前页与根页同域（非域外）→ webview 返回；否则（无历史/已回根/域外）→ 出栈。
 * RN 的「comparable」取 origin+path（hash 取 # 前段）；域外判定为 Android 壳简明化
 * （BACK_CLOSE_URL_SEGMENTS/ant-agent 强制出栈归 M7 拦截域）。
 */
fun shouldWebViewGoBack(currentUrl: String, canGoBack: Boolean, rootUrl: String): Boolean {
    if (!canGoBack) return false
    val cur = currentUrl.toHttpUrlOrNull() ?: return false
    val root = rootUrl.toHttpUrlOrNull() ?: return false
    if (cur.host != root.host) return false // 域外 → 出栈
    // 已回根页（origin+path 一致）→ 出栈，防 redirect 链滞留
    return !(cur.host == root.host && cur.encodedPath == root.encodedPath)
}

/** RN urlsSameOrigin（urlsSameOrigin.ts:1-7）。 */
fun urlsSameOrigin(loadUrl: String, serverUrl: String): Boolean {
    val a = loadUrl.toHttpUrlOrNull() ?: return false
    val b = serverUrl.toHttpUrlOrNull() ?: return false
    return a.scheme == b.scheme && a.host == b.host && a.port == b.port
}

/**
 * RN buildMeteorAndRcCookieScript 的 localStorage 段（buildInjectedScripts.ts:62-75）：
 * 同源页注入 Meteor.loginToken / Meteor.userId / source='appia' / org 四项，值均经
 * [JsonPrimitive.toString()] JSON 转义（RN JSON.stringify 同防引号注入）；条件写（值不同才 set）。
 * Cookie 段（rc_token/rc_uid）由 CookieManager 承担（见 [AppWebView]），此处仅 localStorage。
 */
fun buildMeteorLocalStorageScript(token: String, userId: String, enterpriseId: String): String {
    val tokenLit = JsonPrimitive(token).toString()
    val uidLit = JsonPrimitive(userId).toString()
    val orgLit = JsonPrimitive(enterpriseId).toString()
    return """(function(){
  try {
    if (localStorage.getItem('Meteor.loginToken') !== $tokenLit) {
      localStorage.setItem('Meteor.loginToken', $tokenLit);
    }
    if (localStorage.getItem('Meteor.userId') !== $uidLit) {
      localStorage.setItem('Meteor.userId', $uidLit);
    }
    if (localStorage.getItem('source') !== 'appia') {
      localStorage.setItem('source', 'appia');
    }
    if (localStorage.getItem('org') !== $orgLit) {
      localStorage.setItem('org', $orgLit);
    }
  } catch (e) {}
})();"""
}

/**
 * 应用内 WebView 壳（M5-T10 最小版，RN screens/InAppWebScreen/index.tsx 对照）：
 * needAuth 白名单自动换 code 拼 URL；同源（IM server）注入 rc_token/rc_uid Cookie；
 * 返回栈 canGoBack 域外判定；换码失败降级裸 URL 继续加载（RN :217-224 auth=null 同语义）。
 * 泛微 Weavertoken/石墨参数/WPS 入口改写/会议外链拦截/postMessage 桥归 M7。
 */
@Composable
fun InAppWebScreen(
    url: String,
    title: String,
    needAuth: Boolean = false,
    source: String? = null,
    sdk: RocketSdk?,
    serverUrl: String,
    token: String?,
    userId: String?,
    username: String?,
    enterpriseId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current

    // 白名单命中强制 needAuth（RN resolveInAppWebViewParams）
    val effective = remember(url, needAuth, source) { resolveInAppWebViewParams(url, needAuth, source) }
    val (effectiveNeedAuth, effectiveSource) = effective

    var error by remember { mutableStateOf(false) }
    var booting by remember { mutableStateOf(true) }
    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    // 屏内 WebView 引用（返回栈 goBack/canGoBack 判定用；url 变化 key 重建时回填）
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(url, effectiveNeedAuth, effectiveSource) {
        val trimmed = url.trim()
        val http = trimmed.toHttpUrlOrNull()
        if (http == null) {
            error = true
            booting = false
            return@LaunchedEffect
        }
        error = false
        booting = true
        resolvedUrl = null
        var auth: ExternalTokenResult? = null
        if (effectiveNeedAuth && sdk != null) {
            // 失败/空结果降级裸 URL（RN catch → auth=null；token/accessUrl 不得入日志）
            auth = try {
                fetchExternalToken(sdk, trimmed, effectiveSource.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        resolvedUrl = if (effectiveNeedAuth) {
            buildNeedAuthUrl(
                trimmed, auth, effectiveSource,
                userId.orEmpty(), username.orEmpty(), enterpriseId,
            )
        } else {
            trimmed
        }
        booting = false
    }

    BackHandler(enabled = resolvedUrl != null) {
        // canGoBack 域外判定在 WebView 实例上现读（RN onNavigationStateChange 裁定的惰性等价）
        val w = web
        if (resolvedUrl != null && w != null &&
            shouldWebViewGoBack(w.url.orEmpty(), w.canGoBack(), resolvedUrl!!)
        ) {
            w.goBack()
        } else {
            onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-in-app-web-screen")) {
        RoomHeader(title = title.ifBlank { resolvedUrl?.toHttpUrlOrNull()?.host.orEmpty() }, onBack = onBack)
        when {
            error -> ErrorBox(context.t("labor_web_invalidurl"))
            booting || resolvedUrl == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.tintColor)
                }

            else -> AppWebView(
                url = resolvedUrl!!,
                serverUrl = serverUrl,
                token = token,
                userId = userId,
                enterpriseId = enterpriseId,
                onWebviewReady = { web = it },
            )
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = LocalAppiaColors.current.auxiliaryText,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * WebView 本体：同源（IM server）注入 rc_token/rc_uid Cookie（RN buildMeteorAndRcCookieScript
 * 的 CookieManager 等价——仅对当前加载 origin 生效，不外溢域外页）+ Meteor localStorage
 * 四项（onPageStarted 注入；RC 会话键 7 天，RN cookieExpiresUtc 同期）。
 */
@Composable
private fun AppWebView(
    url: String,
    serverUrl: String,
    token: String?,
    userId: String?,
    enterpriseId: String,
    onWebviewReady: (WebView) -> Unit,
) {
    androidx.compose.runtime.key(url) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("qa-in-app-webview"),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    // 同源守卫：token/userId 缺失或域外 → 不注入任何会话材料
                    val meteorScript = if (token != null && userId != null && urlsSameOrigin(url, serverUrl)) {
                        // RC 会话 Cookie 7 天（RN cookieExpiresUtc 同期）——仅同源 origin
                        cm.setCookie(url, "rc_token=$token; Path=/; Max-Age=604800")
                        cm.setCookie(url, "rc_uid=$userId; Path=/; Max-Age=604800")
                        cm.flush()
                        buildMeteorLocalStorageScript(token, userId, enterpriseId)
                    } else null
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            // RN injectedJavaScriptBeforeContentLoaded 在页面内容前执行；Android
                            // evaluateJavascript 无文档级前置钩子，onPageStarted 是最早可用点
                            //（DOM/storage 可写）——时机略晚于 RN，主站脚本读取时序近似对齐。
                            meteorScript?.let { view.evaluateJavascript(it, null) }
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // 会议外链拦截归 M7：此处放行全部 http(s)
                            return false
                        }
                    }
                    loadUrl(url)
                    onWebviewReady(this)
                }
            },
            update = { /* url 变化由 key 重建，不在此 loadUrl */ },
            onRelease = { it.destroy() },
        )
    }
}

/**
 * openLink 等价工具（RN src/lib/openLink/openLink.ts:47-81 逐义）：
 * 浏览器 pref（T9 持久化 DEFAULT_BROWSER_KEY）inApp → 应用内 WebView；needVPN 白名单域
 * （lexiang）无视 pref 强制应用内；systemDefault → ACTION_VIEW，失败返回 false（RN 双跳降级
 * Android 无外部浏览器 scheme 仅系统默认一段）。
 */
fun openLink(
    context: Context,
    kv: KvStore,
    url: String,
    openInAppWeb: (url: String) -> Unit,
): Boolean {
    val trimmed = url.trim().removeSuffix(",").removeSuffix("，") // RN normalizeMessageLink
    if (trimmed.isEmpty()) return false
    val browser = kv.getString(SettingsConstants.DEFAULT_BROWSER_KEY, SettingsConstants.DEFAULT_BROWSER)
    val requiresInAppWeb = matchNeedAuthHost(trimmed)?.needVPN == true
    if (browser == "inApp" || requiresInAppWeb) {
        openInAppWeb(trimmed)
        return true
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
