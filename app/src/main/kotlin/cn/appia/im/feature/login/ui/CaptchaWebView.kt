package cn.appia.im.feature.login.ui

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Auth 域 WebView 共享配置（RN LoginScreen:603-606 / SmsCaptchaBottomSheet:104-106 / AuthWebScreen:93-97 同套）：
 * `javaScriptEnabled + domStorageEnabled + mixedContentMode=always + originWhitelist['*']（全局接受三方 cookie）`。
 */
internal fun WebView.applyAuthWebSettings() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(this@applyAuthWebSettings, true)
    }
}

/**
 * 滑块验证码 WebView（RN LoginScreen:598-609 内嵌滑块 + SmsCaptchaBottomSheet:97-123 弹层滑块）。
 * H5 经 `ReactNativeWebView.postMessage(JSON)` 回传 ic（与 RN webview 桥同名，LoginScreen:293-301
 * 非 JSON 心跳由调用方解析时忽略）；主线程错误经 [onWebViewError] 上抛。
 *
 * uri 变化经 `key` 重建实例（RN `key={uri}`；T6 教训：AndroidView update 不得无条件 loadUrl）。
 */
@Composable
fun CaptchaWebView(
    uri: String,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onWebViewError: (String) -> Unit = {},
) {
    key(uri) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    applyAuthWebSettings()
                    val main = Handler(Looper.getMainLooper())
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postMessage(value: String?) {
                                // JS 桥跑在 WebView 线程，回主线程再写 Compose 状态
                                main.post { onMessage(value.orEmpty()) }
                            }
                        },
                        "ReactNativeWebView",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // RN onWebViewError：仅主框架错误算加载失败（子资源失败不弹）
                            if (request.isForMainFrame) {
                                onWebViewError(error.description?.toString() ?: "WebView error")
                            }
                        }
                    }
                    loadUrl(uri)
                }
            },
            update = { /* noop：uri 变化由 key 重建，绝不在这里 loadUrl */ },
            onRelease = { it.destroy() },
        )
    }
}
