package cn.appia.im.feature.chat.editor

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 10tap web 构建桥协议（spike 最小版，T12 转正再完整化）。
 * 协议对照（只读参考 external/10tap-editor/src）：
 * - web→native：`window.ReactNativeWebView.postMessage(JSON.stringify(msg))` → addJavascriptInterface 收字符串；
 *   普通消息 {type,payload}，mention 事件为双层嵌套 {type:"action",payload:{type,payload}}
 *   （RichText/useTenTap.tsx 的 handleMentionClick/handleMentionTrigger）。
 * - native→web：evaluateJavascript dispatch MessageEvent('message')，web 侧 window+document 双监听
 *   （useTenTap.tsx handleWebviewMessage）；动作壳 {type:"action",payload:{type,payload},id?}
 *   （useEditorBridge.tsx sendAction + Android 新架构防重 id）。
 * - 配置注入：utils.ts getInjectedJSBeforeContentLoad 等价物。web 构建轮询 window.contentInjected
 *   才渲染编辑器（simpleWebEditor/index.tsx），配置脚本必须在 module 脚本前就位 → 注入进 HTML <head> 顶部
 *   （经典脚本同步执行，module 脚本 deferred）。
 */
object TenTapBridge {

    // ── 消息类型常量（bridges/core.ts CoreEditorActionType / mention.ts / useTenTap.tsx）──
    const val ACTION = "action"
    const val EDITOR_READY = "editor-ready"
    const val STATE_UPDATE = "stateUpdate"
    const val CONTENT_UPDATE = "content-update"
    const val DOCUMENT_HEIGHT = "document-height"
    const val SEND_JSON_BACK = "send-json-back"
    const val MENTION_TRIGGER = "mention-trigger"
    const val MENTION_CLICK = "mention-click"
    const val SET_CONTENT = "set-content"
    const val GET_HTML = "get-html"
    const val GET_JSON = "get-json"
    const val GET_TEXT = "get-text"
    const val SEND_HTML_BACK = "send-html-back"
    const val SEND_TEXT_BACK = "send-text-back"
    const val INSERT_MENTION = "insert-mention"
    const val INSERT_EMOJI = "insert-emoji"
    const val DELETE_RANGE = "delete-range"
    const val UPDATE_SCROLL_MARGIN = "update-scroll-threshold-and-margin"
    const val SET_EDITABLE = "set-editable"
    const val FOCUS = "focus"
    const val BLUR = "blur"

    /**
     * TenTapStartKit 各 bridge 名（StarterKit.ts 顺序）。base.ts 构造器：forceName 仅在
     * 无 tiptapExtension 时生效，其余名 = tiptapExtension.name。注意两个坑（2026-09 spike
     * 在构建产物中实测校准）：
     * - CoreBridge 带 tiptapExtension(Document)，forceName:"coreBridge" 被忽略 → 实名 "doc"；
     * - 本 tiptap v3 构建中 UndoRedo/Dropcursor 扩展名为 "undoRedo"/"dropCursor"（非 history/dropcursor）。
     * web 侧按名查 bridgeExtensionConfigMap（useTenTap.tsx extensionConfigs[e.name]），
     * 缺名则该扩展被整体丢弃（CoreBridge 丢失即 "Schema is missing its top node type ('doc')" 崩溃）。
     */
    val BRIDGE_NAMES = listOf(
        "aicHighlightInputRule", "bold", "undoRedo", "code", "italic", "strike",
        "underline", "orderedList", "heading", "image", "bulletList", "blockquote",
        "taskList", "link", "color", "fontSize", "highlight", "doc",
        "placeholder", "listItem", "dropCursor", "hardBreak", "customEmoji",
        "mention", "contentHeightBridge",
    )

    /** web→native 消息解析结果 */
    sealed interface TenTapMessage {
        data object EditorReady : TenTapMessage
        data class StateUpdate(val payload: JsonObject) : TenTapMessage
        data object ContentUpdate : TenTapMessage
        data class DocumentHeight(val height: Double) : TenTapMessage
        data class JsonBack(val content: JsonObject, val messageId: String?) : TenTapMessage
        data class HtmlBack(val content: String, val messageId: String?) : TenTapMessage
        data class TextBack(val content: String, val messageId: String?) : TenTapMessage
        data class MentionTrigger(val query: String, val cursorPos: Long) : TenTapMessage
        data class MentionClick(val userId: String) : TenTapMessage
        data class Unknown(val type: String, val payload: JsonElement?) : TenTapMessage
    }

    /** send-*-back 三型的公共载荷形状：content + messageId（core.ts MessageToNative）。 */
    private fun payloadContentString(payload: JsonElement?): Pair<JsonElement?, String?> {
        val p = payload as? JsonObject
        return p?.get("content") to p?.get("messageId")?.let { it as? JsonPrimitive }?.content
    }

    /** 解析 web→native 消息；mention 事件剥双层 action 壳；坏 JSON/缺 type 返回 null */
    fun parseMessage(raw: String): TenTapMessage? {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val type = root["type"]?.let { it as? JsonPrimitive }?.content ?: return null
        // mention 事件走 EditorMessageType.Action 壳：{type:"action",payload:{type,payload}}
        var innerType = type
        var payload: JsonElement? = root["payload"]
        if (type == ACTION) {
            val inner = payload as? JsonObject ?: return null
            innerType = inner["type"]?.let { it as? JsonPrimitive }?.content ?: return null
            payload = inner["payload"]
        }
        return when (innerType) {
            EDITOR_READY -> TenTapMessage.EditorReady
            STATE_UPDATE -> TenTapMessage.StateUpdate(payload as? JsonObject ?: JsonObject(emptyMap()))
            CONTENT_UPDATE -> TenTapMessage.ContentUpdate
            DOCUMENT_HEIGHT -> TenTapMessage.DocumentHeight(
                runCatching { payload?.jsonPrimitive?.double }.getOrNull() ?: 0.0
            )
            SEND_JSON_BACK -> {
                val (content, messageId) = payloadContentString(payload)
                TenTapMessage.JsonBack(
                    content = content as? JsonObject ?: JsonObject(emptyMap()),
                    messageId = messageId,
                )
            }
            SEND_HTML_BACK -> {
                val (content, messageId) = payloadContentString(payload)
                TenTapMessage.HtmlBack(
                    content = (content as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    messageId = messageId,
                )
            }
            SEND_TEXT_BACK -> {
                val (content, messageId) = payloadContentString(payload)
                TenTapMessage.TextBack(
                    content = (content as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                    messageId = messageId,
                )
            }
            MENTION_TRIGGER -> {
                val p = payload as? JsonObject
                TenTapMessage.MentionTrigger(
                    query = p?.get("query")?.let { it as? JsonPrimitive }?.content ?: "",
                    cursorPos = runCatching { p?.get("cursorPos")?.jsonPrimitive?.long }.getOrNull() ?: 0L,
                )
            }
            MENTION_CLICK -> TenTapMessage.MentionClick(
                (payload as? JsonObject)?.get("userId")?.let { it as? JsonPrimitive }?.content ?: ""
            )
            else -> TenTapMessage.Unknown(innerType, payload)
        }
    }

    /**
     * getInjectedJSBeforeContentLoad 等价：单行配置脚本。
     * bridgeExtensionConfigMap 条目为 {}（RN 侧 optionsConfig/extendConfig 均 undefined，
     * JSON.stringify 丢弃 undefined 后即空对象）。
     */
    fun configScript(
        editable: Boolean = true,
        // RN RichText：Android 未显式传时默认关色块选区（10tap issue #184）
        disableColorHighlight: Boolean = true,
        dynamicHeight: Boolean = false,
        initialContent: String? = null,
    ): String {
        val configMap = BRIDGE_NAMES.joinToString(",") { "\"$it\":{}" }
        val whitelist = BRIDGE_NAMES.joinToString(",") { "'$it'" }
        val initial = initialContent?.let { "window.initialContent = ${jsonString(it)};" } ?: ""
        return (
            "window.bridgeExtensionConfigMap = '{$configMap}';" +
                "window.whiteListBridgeExtensions = [$whitelist];" +
                initial +
                "window.editable = $editable;" +
                "window.disableColorHighlight = $disableColorHighlight;" +
                "window.dynamicHeight = $dynamicHeight;" +
                "window.contentInjected = true;" +
                "window.platform = \"android\";"
            ).replace("\n", "")
    }

    /** 把配置脚本作为经典 <script> 插到 <head> 顶部（先于 module 脚本执行）；已注入则原样返回 */
    fun injectConfig(html: String, config: String): String {
        if (html.contains("window.contentInjected = true")) return html
        val marker = "<head>"
        val at = html.indexOf(marker)
        require(at >= 0) { "assets editor/index.html: <head> marker missing (build output changed?)" }
        val insertAt = at + marker.length
        return html.substring(0, insertAt) + "<script>" + config + "</script>" + html.substring(insertAt)
    }

    /**
     * 构造 native→web 动作消息：{type:"action",payload:{type,payload},id?}（useEditorBridge.tsx sendAction）。
     * id 缺省自动生成：web 侧按外层 id 去重（window+document 双 dispatch / 重复投递防抖，
     * 对应 react-native-webview #3305 同款机制）。
     */
    fun actionJson(actionType: String, actionPayload: JsonElement = JsonObject(emptyMap()), id: String? = null): String =
        buildJsonObject {
            put("type", ACTION)
            put("payload", buildJsonObject {
                put("type", actionType)
                put("payload", actionPayload)
            })
            put("id", id ?: newMessageId())
        }.toString()

    private val idRandom = kotlin.random.Random(System.nanoTime())
    private fun newMessageId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..7).map { chars[idRandom.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * set-content 载荷 content 既可 TipTap JSON（[setContentAction]）也可 HTML 串
     * （[setContentHtmlAction]，tiptap setContent(string) 直吃）——mdToTipTap.buildEditContent
     * 无 md 时回退 `<p>..</p>` 的 RN 同款双形态。
     */
    fun setContentAction(contentJson: String, id: String? = null): String =
        actionJson(SET_CONTENT, buildJsonObject { put("content", Json.parseToJsonElement(contentJson)) }, id)

    fun setContentHtmlAction(html: String, id: String? = null): String =
        actionJson(SET_CONTENT, buildJsonObject { put("content", JsonPrimitive(html)) }, id)

    fun getHtmlAction(messageId: String): String =
        actionJson(GET_HTML, buildJsonObject { put("messageId", messageId) })

    fun getJsonAction(messageId: String): String =
        actionJson(GET_JSON, buildJsonObject { put("messageId", messageId) })

    fun getTextAction(messageId: String): String =
        actionJson(GET_TEXT, buildJsonObject { put("messageId", messageId) })

    /**
     * insert-mention 只传 id/label。**avatarUrl 弃用决策（T2 绑定条件 (b) 落痕）**：
     * fork 的 mention 节点 schema 无 avatarUrl 属性，传即被 tiptap 静默丢弃（RN 端同样丢弃，
     * spike 判据②实测）。扩 schema 需改 web 构建资产，弃用对齐 RN 端到端行为——采弃用；
     * mention 显示名/头像由 native 渲染链（T1 MentionDisplayResolver）按 id 自行解析。
     */
    fun insertMentionAction(id: String, label: String): String =
        actionJson(INSERT_MENTION, buildJsonObject {
            put("id", id); put("label", label)
        })

    fun insertEmojiAction(alt: String, title: String, src: String = "", type: String = "emoji"): String =
        actionJson(INSERT_EMOJI, buildJsonObject {
            put("alt", alt); put("title", title); put("src", src); put("type", type)
        })

    fun deleteRangeAction(from: Long, to: Long): String =
        actionJson(DELETE_RANGE, buildJsonObject { put("from", from); put("to", to) })

    /** RichText.tsx:99-101 键盘联动：ProseMirror 底部 padding + 滚动阈值/margin（core.ts :223-229）。 */
    fun updateScrollMarginAction(bottomPx: Int): String =
        actionJson(UPDATE_SCROLL_MARGIN, JsonPrimitive(bottomPx))

    fun setEditableAction(editable: Boolean): String = actionJson(SET_EDITABLE, JsonPrimitive(editable))

    fun focusAction(pos: String = "end"): String = actionJson(FOCUS, JsonPrimitive(pos))
    fun blurAction(): String = actionJson(BLUR)

    /**
     * evaluateJavascript 注入串：window+document 双 dispatch（useTenTap.tsx 双监听）。
     * data 必须是字符串（web 侧 JSON.parse(event.data)）。
     */
    fun dispatchJs(messageJson: String): String {
        val lit = jsStringLiteral(messageJson)
        return "window.dispatchEvent(new MessageEvent('message',{data:$lit}));" +
            "document.dispatchEvent(new MessageEvent('message',{data:$lit}));true;"
    }

    /**
     * RichText/utils.ts getStyleSheetCSS 等价：按 tag 建/更 `<style data-tag>` 样式元素。
     * [getInjectedJS] 的逐 bridge extendCSS 注入即本式的 map 版。
     */
    fun styleSheetJs(css: String, tag: String): String =
        "cssContent=${jsStringLiteral(css)};" +
            "head=document.head||document.getElementsByTagName('head')[0]," +
            "styleElement=head.querySelector('style[data-tag=${jsStringLiteral(tag)}]');" +
            "if(!styleElement){styleElement=document.createElement('style');" +
            "styleElement.setAttribute('data-tag',${jsStringLiteral(tag)});styleElement.type='text/css';" +
            "head.appendChild(styleElement);}styleElement.innerHTML=cssContent;"

    /** RichText/utils.ts getInjectedJS 等价：逐 bridge extendCSS 一条 styleSheet + `true;` 收尾。 */
    fun injectedStyleSheets(cssByName: Map<String, String>): String =
        cssByName.entries.joinToString(" ") { styleSheetJs(it.value, it.key) } + " true;"

    /** JS 字符串字面量转义（含 </script> 粉碎，防 evaluateJavascript 路径外破壳） */
    fun jsStringLiteral(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '<' -> sb.append("\\u003c") // 防 </script> 断串
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonString(s: String): String = JsonPrimitive(s).toString()
}

/** addJavascriptInterface 落点：web 侧只认 window.ReactNativeWebView.postMessage(string) */
class TenTapJsInterface {
    var onMessage: ((String) -> Unit)? = null

    @JavascriptInterface
    fun postMessage(message: String?) {
        val cb = onMessage ?: return
        if (message != null) cb(message)
    }
}

/**
 * 异步 RPC 登记（RN RichText/AsyncMessages.ts 等价）：get-html/get-json/get-text 带 messageId，
 * web 侧回 send-*-back 同 messageId 完成配对；native 侧 messageId→CompletableDeferred。
 * 超时兜底（RN 无超时、悬挂即泄漏）由调用方 withTimeoutOrNull 完成。
 */
class EditorAsyncMessages {
    private val subscriptions = HashMap<String, CompletableDeferred<JsonElement>>()

    /** 发出前登记：返回等回包的 deferred（RN sendAsyncMessage 的挂起形态）。 */
    fun prepare(messageId: String): CompletableDeferred<JsonElement> =
        CompletableDeferred<JsonElement>().also {
            synchronized(subscriptions) { subscriptions[messageId] = it }
        }

    /** send-*-back 到达：按 messageId 完成配对（RN onMessage）。 */
    fun onMessage(messageId: String?, value: JsonElement) {
        if (messageId == null) return
        synchronized(subscriptions) { subscriptions.remove(messageId) }?.complete(value)
    }

    /** 测试缝：清空在途 RPC。 */
    fun clear() = synchronized(subscriptions) { subscriptions.clear() }
}
