package cn.appia.im.feature.chat.editor

import android.webkit.JavascriptInterface
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
    const val GET_JSON = "get-json"
    const val INSERT_MENTION = "insert-mention"
    const val INSERT_EMOJI = "insert-emoji"
    const val DELETE_RANGE = "delete-range"
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
        data class MentionTrigger(val query: String, val cursorPos: Long) : TenTapMessage
        data class MentionClick(val userId: String) : TenTapMessage
        data class Unknown(val type: String, val payload: JsonElement?) : TenTapMessage
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
                val p = payload as? JsonObject
                TenTapMessage.JsonBack(
                    content = p?.get("content") as? JsonObject ?: JsonObject(emptyMap()),
                    messageId = p?.get("messageId")?.let { it as? JsonPrimitive }?.content,
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

    fun setContentAction(contentJson: String, id: String? = null): String =
        actionJson(SET_CONTENT, buildJsonObject { put("content", Json.parseToJsonElement(contentJson)) }, id)

    fun getJsonAction(messageId: String): String =
        actionJson(GET_JSON, buildJsonObject { put("messageId", messageId) })

    fun insertMentionAction(id: String, label: String, avatarUrl: String = ""): String =
        actionJson(INSERT_MENTION, buildJsonObject {
            put("id", id); put("label", label); put("avatarUrl", avatarUrl)
        })

    fun insertEmojiAction(alt: String, title: String, src: String = "", type: String = "emoji"): String =
        actionJson(INSERT_EMOJI, buildJsonObject {
            put("alt", alt); put("title", title); put("src", src); put("type", type)
        })

    fun deleteRangeAction(from: Long, to: Long): String =
        actionJson(DELETE_RANGE, buildJsonObject { put("from", from); put("to", to) })

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
