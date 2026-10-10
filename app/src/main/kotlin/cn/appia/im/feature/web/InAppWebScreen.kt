package cn.appia.im.feature.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * needAuth 白名单（RN src/lib/inAppWeb/needAuthWhitelist.ts:15-19 NEED_AUTH_HOSTS 三域逐字）：
 * host 命中其本身或子域名 → 自动 needAuth，source 透传给 users.externalToken。
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

/**
 * RN resolveInAppWebViewParams（needAuthWhitelist.ts:48-60）：白名单命中强制 needAuth，
 * needVPN = 白名单规则标记 || 入参标记（M7-T5 起 Triple 第三位供 VPN 探活消费）。
 */
fun resolveInAppWebViewParams(
    url: String,
    needAuth: Boolean = false,
    source: String? = null,
    needVPN: Boolean = false,
): Triple<Boolean, String?, Boolean> {
    val rule = matchNeedAuthHost(url)
    return Triple(
        rule != null || needAuth,
        rule?.source ?: source,
        rule?.needVPN == true || needVPN,
    )
}

/**
 * needAuth 拼 code（RN resolveWebViewUrl.ts buildNeedAuthUrl :26-103）：
 * needAuth=false → 裸 URL 原样返回（RN :40 门，评审 I-1 补回——chat-gpt source 不得绕过）；
 * accessUrl 命中 → 直接改跳 accessUrl（LEXIANG source 叠参保留）；仅 token → 追加
 * from/code/userId/username/enterpriseId query（chat-gpt source 用 IM sessionToken 作 code）；
 * 两者皆空（失败降级）→ 裸 URL 原样返回。urlType=1 hash-query 分支未入（AA 路由无 urlType，
 * RN 仅 hash-query 调用方使用）。
 */
fun buildNeedAuthUrl(
    rawUrl: String,
    auth: ExternalTokenResult?,
    needAuth: Boolean,
    source: String?,
    userId: String,
    username: String,
    enterpriseId: String,
    sessionToken: String = "",
): String {
    if (!needAuth) return rawUrl // RN :40
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
    // RN :68：chat-gpt source 换 IM sessionToken 作 code
    val code = if (source == "chat-gpt") sessionToken else auth?.token.orEmpty()
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
 * RN NAVIGATION_STATE_SCRIPT（buildInjectedScripts.ts:6-22 逐字）：包装 pushState/replaceState
 * 并监听 popstate，postMessage `navigationStateChange`。JS 侧 `window.ReactNativeWebView` 由
 * [AppWebView] addJavascriptInterface("ReactNativeWebView") 同名提供（RN WebView 桥同形）。
 */
fun buildNavigationStateScript(): String = """
function wrap(fn) {
  return function wrapper() {
    var res = fn.apply(this, arguments);
    try {
      window.ReactNativeWebView.postMessage(JSON.stringify({eventType: 'navigationStateChange',url:window.location.href}));
    } catch (e) {}
    return res;
  };
}
history.pushState = wrap(history.pushState);
history.replaceState = wrap(history.replaceState);
window.addEventListener('popstate', function() {
  try {
    window.ReactNativeWebView.postMessage(JSON.stringify({eventType: 'navigationStateChange',url:window.location.href}));
  } catch (e) {}
});
""".trimIndent()

/**
 * Android 泛微页：劫持 XHR/fetch 附带 WEAVERTOKEN（RN buildInjectedScripts.ts:98-122
 * buildAndroidFanweiNetworkPatch 逐字，token 经 JSON 转义防引号注入）。
 */
fun buildAndroidFanweiNetworkPatch(weaverToken: String): String {
    val tokLit = JsonPrimitive(weaverToken).toString()
    return """(function(){
  try {
    var tok = $tokLit;
    var send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      try { this.setRequestHeader('WEAVERTOKEN', tok); } catch (e) {}
      return send.apply(this, Array.prototype.slice.call(arguments));
    };
    var originFetch = window.fetch;
    window.fetch = function (resource, options) {
      var params = options || {};
      var headers = params.headers || {};
      if (headers instanceof Headers) {
        headers.set('WEAVERTOKEN', tok);
      } else {
        headers = Object.assign({}, headers, { WEAVERTOKEN: tok });
      }
      params.headers = headers;
      return originFetch(resource, params);
    };
  } catch (e) {}
})();"""
}

/** 招聘系统隐藏顶栏（RN buildInjectedScripts.ts:82-95 buildRecruitmentHideChromeScript 逐字）。 */
fun buildRecruitmentHideChromeScript(): String {
    val css = JsonPrimitive(
        ".mobile-recruit-paas-interviewer-replay__page-head," +
            ".recruit-paas-mobile-head-tabs__container-left," +
            ".drawer-animation-wrap-header-return," +
            ".fixed-header{display: none;}",
    ).toString()
    return """(function(){
  setTimeout(function(){
    try {
      var head = document.getElementsByTagName('head')[0];
      var styleEle = document.createElement('style');
      styleEle.innerHTML = $css;
      head.appendChild(styleEle);
    } catch (e) {}
  }, 500);
})();"""
}

/**
 * 泛微 H5：写 Weavertoken Cookie 并 sessionStorage 去重 reload（RN buildInjectedScripts.ts:125-147
 * buildFanweiCookieReloadScript 的 evaluate-ready 内核——RN 外层 `window.addEventListener('load')`
 * 由 Android 侧 onPageFinished/doUpdateVisitedHistory 触发点承担，sessionStorage
 * `hasReloaded_<url>` 防循环 key 原样保留）。
 */
fun buildFanweiCookieReloadScript(fanweiMobileUrl: String, weaverToken: String): String {
    val baseLit = JsonPrimitive(fanweiMobileUrl).toString()
    val tokLit = JsonPrimitive(weaverToken).toString()
    return """(function(){
  try {
    var fanweiUrl = $baseLit;
    var tok = $tokLit;
    if (window.location.href.indexOf(fanweiUrl) !== -1) {
      document.cookie = 'Weavertoken=' + tok + '; path=/';
      if (document.cookie.indexOf('Weavertoken') !== -1) {
        var currentUrl = window.location.href;
        var key = 'hasReloaded_' + currentUrl;
        if (!sessionStorage.getItem(key)) {
          sessionStorage.setItem(key, 'true');
          window.location.reload();
        }
      }
    }
  } catch (e) {}
})();"""
}

private enum class InAppWebErrorKind { NONE, INVALID, PREP, VPN }

/** 屏内 WebView 导航轨迹（返回链 + 泛微跨页 transition 判定输入，RN refs 同位）。 */
private class InAppWebNavState {
    var currentUrl by mutableStateOf("")
    var prevUrl by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
}

/**
 * 应用内 WebView 全量版（M7-T5 七项补齐，RN screens/InAppWebScreen/index.tsx 对照）：
 * 启动链 needVPN 探活（HEAD 10s）→ 泛微 token 无条件预取（进程内去重、失败静默）→
 * needAuth 换码（needVPN 命中时失败/空即错误页，否则降级裸 URL）→ WPS 入口改写 →
 * needAuth 拼参 → 石墨拼参。页内：泛微三件套（Weavertoken 请求头/cookie+sessionStorage
 * 去重 reload/XHR-fetch 劫持）、跨页 transition 注入、postMessage 桥（SetTitle 标题 /
 * onResetPasswordSuccess 登出）、腾讯会议外链拦截、全分支返回决策链、RECRUITMENT 隐藏顶栏。
 * RN 三类错误埋点（onError/onHttpError/onRenderProcessGone）AA 无 analytics 总线，未移植
 * （renderProcessGone 不 override = 不重载，RN `return false` 同默认）；AntMeeting 预热
 * 随 M8 原生会议整体作废（WebView 预热不再实现），AntMeeting URL 移交逻辑归 M8 T5。
 */
@Composable
fun InAppWebScreen(
    url: String,
    title: String,
    needAuth: Boolean = false,
    source: String? = null,
    needVPN: Boolean = false,
    sdk: RocketSdk?,
    serverUrl: String,
    token: String?,
    userId: String?,
    username: String?,
    enterpriseId: String,
    fanweiMobileUrl: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current

    // 白名单命中强制 needAuth/needVPN（RN resolveInAppWebViewParams）
    val effective = remember(url, needAuth, source, needVPN) {
        resolveInAppWebViewParams(url, needAuth, source, needVPN)
    }
    val (effectiveNeedAuth, effectiveSource, effectiveNeedVPN) = effective

    var headerTitle by remember { mutableStateOf(title) }
    var errorKind by remember { mutableStateOf(InAppWebErrorKind.NONE) }
    var booting by remember { mutableStateOf(true) }
    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    var weaverToken by remember { mutableStateOf("") }
    // 屏内 WebView 引用（返回栈 goBack 用；url 变化 key 重建时回填）
    var web by remember { mutableStateOf<WebView?>(null) }
    val nav = remember { InAppWebNavState() }

    LaunchedEffect(url, effectiveNeedAuth, effectiveSource, effectiveNeedVPN) {
        val trimmed = url.trim()
        val http = trimmed.toHttpUrlOrNull()
        if (http == null) {
            errorKind = InAppWebErrorKind.INVALID
            booting = false
            return@LaunchedEffect
        }
        errorKind = InAppWebErrorKind.NONE
        booting = true
        resolvedUrl = null

        // needVPN 探活（RN :182-194，HEAD 10s 不通 → 「需 VPN」错误页）
        if (effectiveNeedVPN && !isVpnReachable(trimmed)) {
            errorKind = InAppWebErrorKind.VPN
            booting = false
            return@LaunchedEffect
        }

        // 泛微 token 无条件预取（RN :196-203：进程内去重，失败静默空串仍可开非泛微 H5）
        weaverToken = if (sdk != null) fanweiWeaverTokenGate.get { fetchFanweiWeaverToken(sdk) } else ""

        var auth: ExternalTokenResult? = null
        if (effectiveNeedAuth && sdk != null) {
            // 失败/空结果：needVPN 时终止错误页，否则降级裸 URL（RN :205-225 stopWhenAuthIsRequired）
            auth = try {
                fetchExternalToken(sdk, trimmed, effectiveSource.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (auth?.accessUrl.isNullOrEmpty() && auth?.token.isNullOrEmpty()) {
                if (effectiveNeedVPN) {
                    errorKind = InAppWebErrorKind.PREP
                    booting = false
                    return@LaunchedEffect
                }
                auth = null
            }
        }

        // URL 改写链（RN :227-247）：WPS 入口 → needAuth 拼参 → 石墨拼参
        var next = trimmed
        rewriteWpsAccessEntryUrl(next, enterpriseId)?.let { next = it }
        next = buildNeedAuthUrl(
            next, auth, effectiveNeedAuth, effectiveSource,
            userId.orEmpty(), username.orEmpty(), enterpriseId,
            sessionToken = token.orEmpty(),
        )
        next = appendShimoQueryIfNeeded(next, enterpriseId, userId.orEmpty(), token.orEmpty())

        resolvedUrl = next
        // RN :270-275：currentUrl/prevNavUrl=root、canGoBack=false
        nav.currentUrl = next
        nav.prevUrl = next
        nav.canGoBack = false
        booting = false
    }

    // 全分支返回决策链（RN handleBack :108-121 → resolveInAppWebBackAction 完整移植）
    BackHandler(enabled = resolvedUrl != null) {
        val action = resolveInAppWebBackAction(
            source = effectiveSource,
            currentUrl = nav.currentUrl,
            canGoBack = nav.canGoBack,
            fanweiMobileUrl = fanweiMobileUrl,
            rootUrl = resolvedUrl,
        )
        if (action == InAppWebBackAction.WEBVIEW_BACK) {
            web?.goBack()
        } else {
            onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-in-app-web-screen")) {
        RoomHeader(
            title = headerTitle.ifBlank { title }.ifBlank {
                resolvedUrl?.toHttpUrlOrNull()?.host.orEmpty()
            },
            onBack = {
                if (resolvedUrl != null) {
                    val action = resolveInAppWebBackAction(
                        effectiveSource, nav.currentUrl, nav.canGoBack, fanweiMobileUrl, resolvedUrl,
                    )
                    if (action == InAppWebBackAction.WEBVIEW_BACK) web?.goBack() else onBack()
                } else {
                    onBack()
                }
            },
        )
        when (errorKind) {
            InAppWebErrorKind.INVALID -> ErrorBox(context.t("labor_web_invalidurl"))
            InAppWebErrorKind.PREP -> ErrorBox(context.t("labor_web_loadfailed"))
            InAppWebErrorKind.VPN -> ErrorBox(context.t("labor_web_vpnrequired"))
            InAppWebErrorKind.NONE ->
                if (booting || resolvedUrl == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.tintColor)
                    }
                } else {
                    AppWebView(
                        url = resolvedUrl!!,
                        serverUrl = serverUrl,
                        token = token,
                        userId = userId,
                        enterpriseId = enterpriseId,
                        source = effectiveSource,
                        weaverToken = weaverToken,
                        fanweiMobileUrl = fanweiMobileUrl,
                        nav = nav,
                        onSetTitle = { headerTitle = it },
                        onResetPassword = onLogout,
                        onWebviewReady = { web = it },
                    )
                }
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
 * WebView 本体（RN WebView props/injection 逐项）：
 * - onPageStarted 注入 NAVIGATION_STATE_SCRIPT +（泛微域 XHR/fetch 劫持 | 同源 Meteor localStorage）
 *   + RECRUITMENT 隐藏顶栏（RN injectedJavaScriptBeforeContentLoaded 拼装序同，Android 无文档级
 *   前置钩子，onPageStarted 是最早可用点——M6 已验证 meteor 时序位同 RN-on-Android）；
 * - doUpdateVisitedHistory/onPageFinished 注入泛微 cookie+sessionStorage 去重 reload（坑 9），
 *   前者兼作跨页非泛微→泛微 transition 判定（RN onNavigationStateChange 同缝）；
 * - Weavertoken 请求头随首载（RN source.headers 同）；腾讯会议 http(s) 转外链、wemeet:// 等
 *   非 http(s) 拒载（RN onShouldStartLoadWithRequest + originWhitelist 同语义）；
 * - JS 桥 addJavascriptInterface("ReactNativeWebView")：SetTitle 改标题、
 *   onResetPasswordSuccess 登出（navigationStateChange 由原生 doUpdateVisitedHistory 承担，
 *   RN onMessage 同样不消费它）；
 * - settings：mixedContent always / domStorage / third-party cookie / 多窗口 false /
 *   geolocation 授权放行（RN geolocationEnabled=true）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AppWebView(
    url: String,
    serverUrl: String,
    token: String?,
    userId: String?,
    enterpriseId: String,
    source: String?,
    weaverToken: String,
    fanweiMobileUrl: String,
    nav: InAppWebNavState,
    onSetTitle: (String) -> Unit,
    onResetPassword: () -> Unit,
    onWebviewReady: (WebView) -> Unit,
) {
    androidx.compose.runtime.key(url) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("qa-in-app-webview"),
            factory = { ctx ->
                val mainHandler = Handler(Looper.getMainLooper())
                val androidFanwei = url.startsWith(fanweiMobileUrl)
                // RN :299-328 拼装序：nav-state → （泛微 XHR 劫持 | 同源 Meteor）→ RECRUITMENT
                val startScript = buildList {
                    add(buildNavigationStateScript())
                    if (androidFanwei && weaverToken.isNotEmpty()) {
                        add(buildAndroidFanweiNetworkPatch(weaverToken))
                    } else if (token != null && userId != null && urlsSameOrigin(url, serverUrl)) {
                        add(buildMeteorLocalStorageScript(token, userId, enterpriseId))
                    }
                    if (source == "RECRUITMENT") add(buildRecruitmentHideChromeScript())
                }.joinToString("\n")
                val fanweiReloadScript = if (weaverToken.isNotEmpty()) {
                    buildFanweiCookieReloadScript(fanweiMobileUrl, weaverToken)
                } else null

                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.setSupportMultipleWindows(false)
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    // 同源守卫：token/userId 缺失或域外 → 不注入任何会话材料
                    if (token != null && userId != null && !androidFanwei && urlsSameOrigin(url, serverUrl)) {
                        // RC 会话 Cookie 7 天（RN cookieExpiresUtc 同期）——仅同源 origin
                        cm.setCookie(url, "rc_token=$token; Path=/; Max-Age=604800")
                        cm.setCookie(url, "rc_uid=$userId; Path=/; Max-Age=604800")
                        cm.flush()
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            view.evaluateJavascript(startScript, null)
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            val u = url.orEmpty()
                            nav.prevUrl = nav.currentUrl
                            nav.currentUrl = u
                            nav.canGoBack = view.canGoBack()
                            // RN injectFanweiTransitionScript :330-352：非泛微域跳入泛微域 → 注入+reload
                            //（sessionStorage hasReloaded_<url> 防循环）
                            if (fanweiReloadScript != null &&
                                u.startsWith(fanweiMobileUrl) &&
                                !nav.prevUrl.startsWith(fanweiMobileUrl)
                            ) {
                                view.evaluateJavascript(fanweiReloadScript, null)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            // 泛微 cookie+reload 常规触发点（坑 9；脚本内 URL 判定 + 去重 key 自防）
                            if (fanweiReloadScript != null) view.evaluateJavascript(fanweiReloadScript, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            // 腾讯会议 http(s) → 外部浏览器拒载（RN :376-381 Linking.openURL 同）
                            if (isTencentMeetingHttpUrl(u.toString())) {
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, u))
                                } catch (_: ActivityNotFoundException) {
                                }
                                return true
                            }
                            // wemeet:// 拒载不拉起（RN :382-385）；其余非 http(s)（RN originWhitelist
                            // ['http://*','https://*']）同拒
                            return u.scheme != "http" && u.scheme != "https"
                        }
                    }
                    // RN getAndroidWebViewGeolocationProps：geolocationEnabled=true → H5 定位授权放行
                    webChromeClient = object : WebChromeClient() {
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?,
                        ) {
                            callback?.invoke(origin, true, false)
                        }
                    }
                    // RN onMessage 桥（:354-374）同口径：仅 SetTitle/onResetPasswordSuccess
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postMessage(raw: String?) {
                                val s = raw ?: return
                                mainHandler.post {
                                    try {
                                        val o = Json.parseToJsonElement(s) as? JsonObject ?: return@post
                                        when ((o["eventType"] as? JsonPrimitive)?.contentOrNull) {
                                            "SetTitle" -> {
                                                val t = (o["title"] as? JsonPrimitive)
                                                    ?.takeIf { it !is JsonNull }?.contentOrNull
                                                if (t != null) onSetTitle(t)
                                            }
                                            "onResetPasswordSuccess" -> onResetPassword()
                                        }
                                    } catch (_: Exception) {
                                        /* 非 JSON：忽略（RN 同） */
                                    }
                                }
                            }
                        },
                        "ReactNativeWebView",
                    )
                    // Weavertoken 请求头随首载（RN source.headers :518-528，仅 http(s) 主帧生效）
                    if (weaverToken.isEmpty()) loadUrl(url) else loadUrl(url, mapOf("Weavertoken" to weaverToken))
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
