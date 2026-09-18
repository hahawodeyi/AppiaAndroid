package cn.appia.im.feature.chat.editor

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * RN EditorBridge 的 Android 面：命令下行（dispatch 动作）+ get-* 异步 RPC + 焦点竞态加固。
 * 单一实现包 [WebView]；测试以 [EditorBridge] fake 替身（Robolectric 不执行 WebView JS）。
 */
interface EditorBridge {
    fun setContent(content: JsonObject)
    fun setContentHtml(html: String)
    suspend fun getJson(): JsonObject?
    fun focus(pos: String = "end")
    fun blur()
    fun insertMention(id: String, label: String)
    fun insertEmoji(alt: String, title: String, src: String, type: String)
    fun deleteRange(from: Long, to: Long)
    fun setEditable(editable: Boolean)
}

/**
 * 10tap web 构建的命令/RPC 封装。所有下行经 [WebView.dispatchTenTap]（UI 线程），
 * get-* 走 [EditorAsyncMessages] messageId 配对（RN AsyncMessages 同构）。
 *
 * **focus('end') 光标竞态加固（T2 绑定条件 (b) 落痕）**：对照 10tap core.ts:330-343——
 * RN 侧 focus 先 `setTimeout(100ms) → webview.requestFocus()` 再发 focus 动作（react-native-webview
 * #1172：requestFocus 离 load 太近即丢焦点，表现之一为光标落 doc 头）。本类逐条移植：100ms 延迟
 * 的原生 requestFocus + 动作下发；上层 [ChatInputBarController] 再叠单一调度（去重+generation）
 * 消除连发两次 requestFocus 的第二个竞态源。
 *
 * **Gboard Inline composing OFF 过期光标归因（T2 绑定条件 (a)(c) 结论）**：坏例链路为
 * IME commitText（绕过 setComposingText 直提）→ Chromium InputConnection → 渲染进程 composition
 * region → ProseMirror sync——直提落点是渲染进程当时的选区快照，与最近一次 tiptap 事务存在
 * 帧边界滞后，即「Gboard 提交缓冲 × WebView composing region 处理」的配合问题；本桥/native 层
 * 不经手 InputConnection，无可裁修点（RN 同栈同现，T2 评审已裁方案 b 亦不可消除）→ 接受；
 * 真机 + 主流中文输入法复测保留为用户验收项（M3-T14 自测协议）。
 */
class TenTapEditorBridge(
    private val webView: WebView,
    private val async: EditorAsyncMessages = EditorAsyncMessages(),
) : EditorBridge {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpcCounter = AtomicInteger(0)

    /** web→native 入口（UI 线程）：RPC 配对先行，消息原样返回给上层消费。 */
    fun onRawMessage(raw: String): TenTapBridge.TenTapMessage? {
        val msg = TenTapBridge.parseMessage(raw) ?: return null
        when (msg) {
            is TenTapBridge.TenTapMessage.JsonBack -> async.onMessage(msg.messageId, msg.content)
            is TenTapBridge.TenTapMessage.HtmlBack -> async.onMessage(msg.messageId, JsonPrimitive(msg.content))
            is TenTapBridge.TenTapMessage.TextBack -> async.onMessage(msg.messageId, JsonPrimitive(msg.content))
            else -> Unit
        }
        return msg
    }

    private fun dispatch(messageJson: String) {
        webView.dispatchTenTap(messageJson)
    }

    override fun setContent(content: JsonObject) = dispatch(TenTapBridge.setContentAction(content.toString()))

    override fun setContentHtml(html: String) = dispatch(TenTapBridge.setContentHtmlAction(html))

    override fun focus(pos: String) {
        // core.ts:337-341：Android requestFocus 延后 100ms（#1172 race，太靠近 load 焦点即丢）
        mainHandler.postDelayed({ webView.requestFocus() }, FOCUS_NATIVE_DELAY_MS)
        dispatch(TenTapBridge.focusAction(pos))
    }

    override fun blur() = dispatch(TenTapBridge.blurAction())

    override fun insertMention(id: String, label: String) =
        dispatch(TenTapBridge.insertMentionAction(id, label))

    override fun insertEmoji(alt: String, title: String, src: String, type: String) =
        dispatch(TenTapBridge.insertEmojiAction(alt, title, src, type))

    override fun deleteRange(from: Long, to: Long) = dispatch(TenTapBridge.deleteRangeAction(from, to))

    override fun setEditable(editable: Boolean) = dispatch(TenTapBridge.setEditableAction(editable))

    override suspend fun getJson(): JsonObject? = rpc { id -> TenTapBridge.getJsonAction(id) }
        ?.let { runCatching { it.jsonObject }.getOrNull() }

    suspend fun getHtml(): String? = rpc { id -> TenTapBridge.getHtmlAction(id) }
        ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    suspend fun getText(): String? = rpc { id -> TenTapBridge.getTextAction(id) }
        ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    /** 登记配对 → 下发 → 挂起等 send-*-back（超时 null，不悬挂草稿保存链）。 */
    private suspend fun rpc(build: (String) -> String): JsonElement? =
        withContext(Dispatchers.Main.immediate) {
            val id = "rpc-${rpcCounter.incrementAndGet()}"
            val deferred = async.prepare(id)
            dispatch(build(id))
            deferred
        }.let { withTimeoutOrNull(RPC_TIMEOUT_MS) { it.await() } }

    /** RichText.tsx:83-90：ProseMirror 底部 padding（键盘弹出时留出滚入空间）。 */
    fun setDocBottomPadding(px: Int) {
        dispatchJs(
            "doc=document.querySelector('.ProseMirror');" +
                "if(doc)doc.style.paddingBottom='${px}px';true;",
        )
    }

    /** RichText.tsx:99 + core.ts:223-229：滚动阈值/margin 与键盘联动（Android 路径）。 */
    fun updateScrollThresholdAndMargin(bottomPx: Int) =
        dispatch(TenTapBridge.updateScrollMarginAction(bottomPx))

    /** RN editor.injectCSS（useEditorBridge.tsx:125-129）。 */
    fun injectCss(css: String, tag: String = "custom-css") = dispatchJs(TenTapBridge.styleSheetJs(css, tag))

    /**
     * RN clearEditorContent.ts buildPostClearScript（Android 分支）：setContent(空 doc) 后
     * 剥除空链接 + 删尾随空段（Android 光标回起点的配套清理）。
     */
    fun runPostClear() {
        dispatchJs(
            "(function(){var root=document.querySelector('.ProseMirror');if(!root)return;" +
                "root.querySelectorAll('a[href]').forEach(function(node){" +
                "if(!node.textContent||!node.textContent.trim()){node.remove();}});" +
                "while(root.children.length>1){var last=root.lastChild;" +
                "if(last&&(!last.textContent||!last.textContent.trim())){root.removeChild(last);}else{break;}}})();true;",
        )
    }

    private fun dispatchJs(js: String) = webView.evaluateJavascript(js, null)

    companion object {
        const val FOCUS_NATIVE_DELAY_MS = 100L
        const val RPC_TIMEOUT_MS = 4_000L
    }
}
