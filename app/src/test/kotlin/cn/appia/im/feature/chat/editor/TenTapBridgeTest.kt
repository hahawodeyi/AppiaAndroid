package cn.appia.im.feature.chat.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 T2 spike：桥消息解析 / 配置注入构造器 / 下行动作编码单测。
 * 协议对照 external/10tap-editor/src（只读参考）。
 */
class TenTapBridgeTest {

    // ── web→native 解析 ──

    @Test
    fun `editor ready message parses`() {
        // RN 真实产物 payload 为 undefined 时 JSON.stringify 直接丢 key
        val msg = TenTapBridge.parseMessage("""{"type":"editor-ready"}""")
        assertEquals(TenTapBridge.TenTapMessage.EditorReady, msg)
    }

    @Test
    fun `state update keeps payload object`() {
        val msg = TenTapBridge.parseMessage(
            """{"type":"stateUpdate","payload":{"isFocused":true,"empty":false,"selection":{"from":3,"to":5},"contentHeight":128.5}}"""
        )
        assertTrue(msg is TenTapBridge.TenTapMessage.StateUpdate)
        val payload = (msg as TenTapBridge.TenTapMessage.StateUpdate).payload
        assertEquals("true", payload["isFocused"]!!.jsonPrimitive.content)
        assertEquals(128.5, payload["contentHeight"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `double nested mention trigger action unwraps`() {
        // useTenTap.tsx：mention 事件经 EditorMessageType.Action 壳双层嵌套
        val msg = TenTapBridge.parseMessage(
            """{"type":"action","payload":{"type":"mention-trigger","payload":{"query":"ab","cursorPos":12}}}"""
        )
        assertEquals(TenTapBridge.TenTapMessage.MentionTrigger("ab", 12), msg)
    }

    @Test
    fun `mention click with userId parses`() {
        val msg = TenTapBridge.parseMessage(
            """{"type":"action","payload":{"type":"mention-click","payload":{"userId":"u-1"}}}"""
        )
        assertEquals(TenTapBridge.TenTapMessage.MentionClick("u-1"), msg)
    }

    @Test
    fun `send json back carries content and messageId`() {
        val msg = TenTapBridge.parseMessage(
            """{"type":"send-json-back","payload":{"content":{"type":"doc"},"messageId":"rt1"}}"""
        )
        assertTrue(msg is TenTapBridge.TenTapMessage.JsonBack)
        msg as TenTapBridge.TenTapMessage.JsonBack
        assertEquals("rt1", msg.messageId)
        assertEquals("doc", msg.content["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `document height numeric payload`() {
        assertEquals(
            TenTapBridge.TenTapMessage.DocumentHeight(220.0),
            TenTapBridge.parseMessage("""{"type":"document-height","payload":220}"""),
        )
    }

    @Test
    fun `unknown type falls back not null`() {
        val msg = TenTapBridge.parseMessage("""{"type":"some-future","payload":1}""")
        assertTrue(msg is TenTapBridge.TenTapMessage.Unknown)
    }

    @Test
    fun `malformed json and missing type return null`() {
        assertNull(TenTapBridge.parseMessage("not json at all"))
        assertNull(TenTapBridge.parseMessage("""{"payload":1}"""))
        assertNull(TenTapBridge.parseMessage("""{"type":"action"}"""))
    }

    // ── native→web 动作编码（双层 action 壳 + 防重 id）──

    @Test
    fun `action json wraps double nested with id`() {
        val json = TenTapBridge.actionJson(
            TenTapBridge.SET_CONTENT,
            Json.parseToJsonElement("""{"content":{"type":"doc"}}"""),
            id = "abc",
        )
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("action", root["type"]!!.jsonPrimitive.content)
        assertEquals("abc", root["id"]!!.jsonPrimitive.content)
        val payload = root["payload"]!!.jsonObject
        assertEquals("set-content", payload["type"]!!.jsonPrimitive.content)
        assertEquals("doc", payload["payload"]!!.jsonObject["content"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set content action embeds draft verbatim`() {
        val draft = "{\"type\":\"doc\",\"content\":[{\"type\":\"text\",\"text\":\"\u4e2d\u6587\uD83D\uDE00\"}]}"
        val json = TenTapBridge.setContentAction(draft)
        assertTrue(json.contains("\u4e2d\u6587\uD83D\uDE00"))
        val embedded = Json.parseToJsonElement(json)
            .jsonObject["payload"]!!.jsonObject["payload"]!!.jsonObject["content"]!!
        assertEquals(Json.parseToJsonElement(draft), embedded)
    }

    @Test
    fun `get json insert mention emoji delete range builders`() {
        val getJson = Json.parseToJsonElement(TenTapBridge.getJsonAction("m1")).jsonObject
        assertEquals("get-json", getJson["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("m1", getJson["payload"]!!.jsonObject["payload"]!!.jsonObject["messageId"]!!.jsonPrimitive.content)

        val mention = Json.parseToJsonElement(TenTapBridge.insertMentionAction("u1", "@\u674e\u56db")).jsonObject
        val mPayload = mention["payload"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("insert-mention", mention["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("@\u674e\u56db", mPayload["label"]!!.jsonPrimitive.content)
        assertEquals("u1", mPayload["id"]!!.jsonPrimitive.content)

        val emoji = Json.parseToJsonElement(TenTapBridge.insertEmojiAction("😀", "😀")).jsonObject
        val ePayload = emoji["payload"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("insert-emoji", emoji["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("emoji", ePayload["type"]!!.jsonPrimitive.content)

        val del = Json.parseToJsonElement(TenTapBridge.deleteRangeAction(3, 7)).jsonObject
        val dPayload = del["payload"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("delete-range", del["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(3, dPayload["from"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, dPayload["to"]!!.jsonPrimitive.content.toInt())
    }

    // ── 配置注入构造器（getInjectedJSBeforeContentLoad 等价）──

    @Test
    fun `config script exposes whitelist config map content injected and platform`() {
        val js = TenTapBridge.configScript()
        // 关键 bridge 名必须在 map 与 whitelist 里（缺名 → web 侧丢扩展；CoreBridge 实名是 "doc"）
        for (name in listOf("doc", "mention", "bulletList", "orderedList", "customEmoji", "listItem", "placeholder", "undoRedo", "dropCursor")) {
            assertTrue("missing bridge name $name", js.contains("\"$name\":{}"))
            assertTrue("whitelist missing $name", js.contains("'$name'"))
        }
        assertEquals(TenTapBridge.BRIDGE_NAMES.size, Regex("\"\\w+\":\\{}").findAll(js).count())
        assertTrue(js.contains("window.contentInjected = true;"))
        assertTrue(js.contains("window.platform = \"android\";"))
        assertTrue(js.contains("window.editable = true;"))
        assertTrue(js.contains("window.disableColorHighlight = true;"))
        assertFalse(js.contains("\n"))
    }

    @Test
    fun `inject config lands before module script and is idempotent`() {
        val html = """<!DOCTYPE html><html><head><script type="module">console.log(1)</script></head><body><div id="root"></div></body></html>"""
        val injected = TenTapBridge.injectConfig(html, TenTapBridge.configScript())
        val configAt = injected.indexOf("window.contentInjected")
        val moduleAt = injected.indexOf("type=\"module\"")
        assertTrue("config script must precede module script", configAt in 0 until moduleAt)
        assertTrue(injected.indexOf("<script>") < injected.indexOf("type=\"module\""))
        // 二次注入不重复
        val twice = TenTapBridge.injectConfig(injected, TenTapBridge.configScript())
        assertEquals(injected, twice)
    }

    @Test
    fun `dispatch js escapes string literal and dispatches window and document`() {
        val js = TenTapBridge.dispatchJs("""{"a":"he said"}""")
        assertTrue(js.contains("window.dispatchEvent(new MessageEvent('message'"))
        assertTrue(js.contains("document.dispatchEvent(new MessageEvent('message'"))
        assertTrue(js.endsWith(";true;"))
        // JSON 里的双引号须转义成 JS 字符串安全字面量；< 粉碎为 \u003c
        assertTrue(js.contains("\"{\\\"a\\\":\\\"he said\\\"}\""))
        val withTag = TenTapBridge.dispatchJs("""{"b":"</script>"}""")
        assertFalse(withTag.contains("</script>"))
        assertTrue(withTag.contains("\\u003c/script>"))
    }

    // ── T12 转正：get-html/get-text 回包解析 + set-content HTML 形态 + margin/editable ──

    @Test
    fun `send html back and send text back parse with messageId`() {
        val html = TenTapBridge.parseMessage(
            """{"type":"send-html-back","payload":{"content":"<p>hi</p>","messageId":"h1"}}"""
        )
        assertEquals(TenTapBridge.TenTapMessage.HtmlBack("<p>hi</p>", "h1"), html)
        val text = TenTapBridge.parseMessage(
            """{"type":"send-text-back","payload":{"content":"hello","messageId":"t1"}}"""
        )
        assertEquals(TenTapBridge.TenTapMessage.TextBack("hello", "t1"), text)
        // 缺 messageId 不崩：配对层按 null 忽略
        val bare = TenTapBridge.parseMessage("""{"type":"send-text-back","payload":{"content":"x"}}""")
        assertTrue(bare is TenTapBridge.TenTapMessage.TextBack && bare.messageId == null)
    }

    @Test
    fun `set content html action carries string content verbatim`() {
        val json = TenTapBridge.setContentHtmlAction("<p>a&amp;b</p>")
        val payload = Json.parseToJsonElement(json).jsonObject["payload"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals("<p>a&amp;b</p>", payload["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get html get text margin editable builders wire payload`() {
        val getHtml = Json.parseToJsonElement(TenTapBridge.getHtmlAction("h9")).jsonObject
        assertEquals("get-html", getHtml["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("h9", getHtml["payload"]!!.jsonObject["payload"]!!.jsonObject["messageId"]!!.jsonPrimitive.content)

        val getText = Json.parseToJsonElement(TenTapBridge.getTextAction("t9")).jsonObject
        assertEquals("get-text", getText["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val margin = Json.parseToJsonElement(TenTapBridge.updateScrollMarginAction(44)).jsonObject
        assertEquals("update-scroll-threshold-and-margin", margin["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(44, margin["payload"]!!.jsonObject["payload"]!!.jsonPrimitive.content.toInt())

        val editable = Json.parseToJsonElement(TenTapBridge.setEditableAction(false)).jsonObject
        assertEquals("set-editable", editable["payload"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, editable["payload"]!!.jsonObject["payload"]!!.jsonPrimitive.content.toBooleanStrict())
    }

    /** T2 绑定 (b) 决策落痕：insert-mention 不再携带 avatarUrl（fork schema 无此 attr，静默丢弃）。 */
    @Test
    fun `insert mention action carries no avatar url`() {
        val mention = Json.parseToJsonElement(TenTapBridge.insertMentionAction("u1", "@x")).jsonObject
        val mPayload = mention["payload"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals(setOf("id", "label"), mPayload.keys)
    }

    // ── T12 转正：extendCSS 样式注入（getStyleSheetCSS/getInjectedJS 等价）──

    @Test
    fun `style sheet js creates tagged style element and sets content`() {
        val js = TenTapBridge.styleSheetJs("a{color:red}", "mention")
        assertTrue(js.contains("style[data-tag=" + TenTapBridge.jsStringLiteral("mention") + "]"))
        assertTrue(js.contains("createElement('style')"))
        assertTrue(js.contains("styleElement.innerHTML=cssContent;"))
        assertTrue(js.contains("a{color:red}"))
    }

    @Test
    fun `injected style sheets covers every bridge with extend css`() {
        val js = TenTapBridge.injectedStyleSheets(EditorCss.BRIDGE_EXTEND_CSS)
        for (tag in EditorCss.BRIDGE_EXTEND_CSS.keys) {
            assertTrue("missing style tag $tag", js.contains("data-tag=" + TenTapBridge.jsStringLiteral(tag) + "]"))
        }
        // RN getInjectedJS 以 `true;` 收尾；css 内容经字面量转义不得破串
        assertTrue(js.endsWith(" true;"))
        assertFalse(js.contains("</script>"))
        // fork mention 芯片样式必须在场（ RN bridges/mention.ts extendCSS 逐字）
        assertTrue(js.contains(".mention-node"))
    }

    /** 异步 RPC 配对：同 messageId 完成等待方；未登记/缺 id 忽略。 */
    @Test
    fun `async messages pairs by message id`() = kotlinx.coroutines.test.runTest {
        val async = EditorAsyncMessages()
        val d1 = async.prepare("id1")
        val d2 = async.prepare("id2")
        async.onMessage("id2", Json.parseToJsonElement("""{"a":1}"""))
        assertEquals("""{"a":1}""", d2.await().toString())
        assertFalse(d1.isCompleted)
        async.onMessage(null, Json.parseToJsonElement("{}")) // 缺 id 忽略
        async.onMessage("ghost", Json.parseToJsonElement("{}")) // 未登记忽略
        assertFalse(d1.isCompleted)
        async.onMessage("id1", Json.parseToJsonElement("""{"b":2}"""))
        assertEquals("""{"b":2}""", d1.await().toString())
    }

    /** EditorCss：输入条 CSS 带 placeholder content 与列表计数后缀（RN handleLoad 逐段）。 */
    @Test
    fun `input bar css embeds placeholder and counter styles`() {
        val css = EditorCss.inputBarCss("Say something")
        assertTrue(css.contains("content: \"Say something\";"))
        assertTrue(css.contains("list-style-type: decimal-type;"))
        assertTrue(css.contains("scrollbar-width: none;"))
    }
}
