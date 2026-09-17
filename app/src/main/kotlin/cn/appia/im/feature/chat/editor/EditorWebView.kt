package cn.appia.im.feature.chat.editor

import android.graphics.Color
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import cn.appia.im.BuildConfig

/**
 * 10tap web 构建宿主（spike 最小版）。加载 assets/editor/index.html，
 * 等价 RN RichText 的三件事：配置注入（并进 HTML，先于 module 脚本）、
 * addJavascriptInterface("ReactNativeWebView")、loadDataWithBaseURL 模拟正常 origin。
 */
@Composable
fun EditorWebView(
    modifier: Modifier,
    onMessage: (String) -> Unit,
    onWebViewReady: (WebView) -> Unit = {},
) {
    val currentOnMessage by rememberUpdatedState(onMessage)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // chrome://inspect 调试通道（仅 debug 构建）
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                val jsInterface = TenTapJsInterface()
                jsInterface.onMessage = { raw -> currentOnMessage(raw) }
                addJavascriptInterface(jsInterface, "ReactNativeWebView")
                val html = context.assets.open("editor/index.html").bufferedReader().use {
                    it.readText()
                }
                val injected = TenTapBridge.injectConfig(html, TenTapBridge.configScript())
                loadDataWithBaseURL("https://appia.local/", injected, "text/html", "utf-8", null)
                onWebViewReady(this)
            }
        },
    )
}

/** native→web 下行：dispatch MessageEvent（UI 线程调用） */
fun WebView.dispatchTenTap(messageJson: String) {
    evaluateJavascript(TenTapBridge.dispatchJs(messageJson), null)
}
