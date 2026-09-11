package cn.appia.im.feature.login.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.LoginCredentials
import cn.appia.im.feature.login.CasApi
import cn.appia.im.feature.login.CasRedirect
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 未登录栈内嵌 WebView 屏（对照 RN AuthWebScreen）：忘记密码网页直开 + CAS SSO 回调判定。
 * CAS 命中（service host == 企业服务器 host）经 `onSsoLogin(Cas(ssoToken))` 交调用方走与账密/SMS
 * 相同的 `AuthApi.login` 成功路径并关页（RN:56-58 onSsoLogin + goBack）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthWebScreen(
    url: String,
    title: String? = null,
    authType: String? = null,
    ssoToken: String? = null,
    onSsoLogin: (LoginCredentials.Cas) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val trimmed = url.trim()
    // RN isHttpUrl:15-22：非 http(s) 整页回落错误文案（OkHttp HttpUrl 仅认 http/https，语义一致）
    val httpUrl = remember(trimmed) { trimmed.toHttpUrlOrNull() }
    // serverHost 从初始 CAS url 的 service 参数推导（RN 路由参数无 server：service={server}/_cas/{token}）
    val serverHost = remember(httpUrl) {
        httpUrl?.queryParameter("service")?.trim()?.toHttpUrlOrNull()?.host
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                // RN:79 `title?.trim() || t('login_forgot_password')`
                title = {
                    Text(
                        title?.trim()?.ifEmpty { null } ?: context.t("login_forgot_password"),
                        maxLines = 1,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        if (httpUrl == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(context.t("login_web_invalid_url"))
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { ctx ->
                    val web = WebView(ctx)
                    web.apply {
                        // RN:93-97 配置逐一映射
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        // RN sharedCookiesEnabled + originWhitelist ['*']：全局接受 cookie（含三方）
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(web, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // RN:98 authType 为空（忘记密码）不挂拦截
                                if (authType != "cas") return false
                                return when (
                                    CasApi.evaluateCasRedirect(request.url.toString(), serverHost.orEmpty())
                                ) {
                                    CasRedirect.Allow -> false
                                    CasRedirect.Success -> {
                                        onSsoLogin(LoginCredentials.Cas(ssoToken.orEmpty()))
                                        onBack()
                                        true // 已关页，拦下跳转（RN 返回 false 后 goBack，终态一致）
                                    }
                                }
                            }
                        }
                        loadUrl(trimmed)
                    }
                },
                update = { it.loadUrl(trimmed) },
            )
        }
    }
}
