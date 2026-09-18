package cn.appia.im.feature.chat.editor

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import cn.appia.im.BuildConfig

/**
 * 10tap web 构建宿主。加载 assets/editor/index.html，等价 RN RichText 的四件事：
 * 配置注入（并进 HTML，先于 module 脚本）、bridge extendCSS 样式注入（getInjectedJS 等价，
 * 经典脚本建 `<style data-tag>`）、addJavascriptInterface("ReactNativeWebView")、
 * loadDataWithBaseURL 模拟正常 origin。WebView 可聚焦（focus 动作 100ms 后的原生
 * requestFocus 落点，见 [TenTapEditorBridge]）。
 * Robolectric（JVM 测试，无 Chromium）降级为占位 View：真 WebView 初始化会挂起测试——
 * UI 测试经 controller 测试缝驱动内容态，不依赖本 View。
 */
@Composable
fun EditorWebView(
    modifier: Modifier,
    onMessage: (String) -> Unit,
    /** WebView 建成回调：装配 [TenTapEditorBridge] 与生命周期管理（destroy 归调用方）。 */
    onWebViewReady: (WebView, TenTapEditorBridge) -> Unit = { _, _ -> },
    /** 配置构造器（editable/initialContent 等差异经此传入；缺省可编辑空文档）。 */
    config: String = TenTapBridge.configScript(),
) {
    val currentOnMessage by rememberUpdatedState(onMessage)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            if (isRobolectricDevice()) {
                View(context)
            } else {
                realEditorWebView(context, currentOnMessage, onWebViewReady, config)
            }
        },
        onRelease = { view -> if (view is WebView) view.destroy() },
    )
}

private fun isRobolectricDevice(): Boolean =
    "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)

private fun realEditorWebView(
    context: Context,
    onMessage: (String) -> Unit,
    onWebViewReady: (WebView, TenTapEditorBridge) -> Unit,
    config: String,
): WebView {
    return WebView(context).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isFocusable = true
        isFocusableInTouchMode = true
        // chrome://inspect 调试通道（仅 debug 构建）
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val jsInterface = TenTapJsInterface()
        jsInterface.onMessage = { raw -> onMessage(raw) }
        addJavascriptInterface(jsInterface, "ReactNativeWebView")
        val html = context.assets.open("editor/index.html").bufferedReader().use {
            it.readText()
        }
        // extendCSS 样式随配置一并进 <head>（经典脚本同步建 style，规则对后续 DOM 生效）
        val configWithCss = config + TenTapBridge.injectedStyleSheets(EditorCss.BRIDGE_EXTEND_CSS)
        val injected = TenTapBridge.injectConfig(html, configWithCss)
        loadDataWithBaseURL("https://appia.local/", injected, "text/html", "utf-8", null)
        onWebViewReady(this, TenTapEditorBridge(this))
    }
}

/** native→web 下行：dispatch MessageEvent（UI 线程调用） */
fun WebView.dispatchTenTap(messageJson: String) {
    evaluateJavascript(TenTapBridge.dispatchJs(messageJson), null)
}
