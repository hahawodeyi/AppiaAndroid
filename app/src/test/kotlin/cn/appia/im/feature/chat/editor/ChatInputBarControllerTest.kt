package cn.appia.im.feature.chat.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T12 控制器单测：草稿 setContent isReady 门控 + editorReadyCount 重注、焦点单一调度
 * （debounce/generation/同向去重/blur 即时）、mention-trigger 链、IME 预热判定。
 * 桥以 fake [EditorBridge] 记录调用（RN EditorBridge 同为可替身接口）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatInputBarControllerTest {

    private class FakeBridge : EditorBridge {
        val calls = mutableListOf<String>()
        var nextJson: JsonObject = EMPTY
        override fun setContent(content: JsonObject) { calls += "set-content" }
        override fun setContentHtml(html: String) { calls += "set-html:$html" }
        override suspend fun getJson(): JsonObject = nextJson
        override fun focus(pos: String) { calls += "focus:$pos" }
        override fun blur() { calls += "blur" }
        override fun insertMention(id: String, label: String) { calls += "mention:$id/$label" }
        override fun insertEmoji(alt: String, title: String, src: String, type: String) { calls += "emoji:$alt" }
        override fun deleteRange(from: Long, to: Long) { calls += "delete:$from-$to" }
        override fun setEditable(editable: Boolean) { calls += "editable:$editable" }
        override fun sendAction(actionType: String, payload: kotlinx.serialization.json.JsonElement?) {
            calls += "action:$actionType${payload?.let { ":$it" } ?: ""}"
        }
        companion object {
            val EMPTY = Json.parseToJsonElement("""{"type":"doc","content":[]}""") as JsonObject
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(dispatcher)
    private lateinit var controller: ChatInputBarController
    private lateinit var bridge: FakeBridge

    @Before
    fun setUp() {
        controller = ChatInputBarController(scope, focusDebounceMs = 50)
        bridge = FakeBridge()
        controller.bridge = bridge
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun editorReady() = controller.onRawMessage("""{"type":"editor-ready"}""")

    @Test
    fun `draft setContent gates on editor ready`() {
        controller.setContentWhenReady(DOC)
        scheduler.runCurrent()
        assertTrue(bridge.calls.none { it.startsWith("set-") }) // 未就绪：挂起不下发
        assertFalse(controller.isReady)

        editorReady()
        scheduler.runCurrent()
        assertTrue(controller.isReady)
        assertEquals(1, controller.editorReadyCount)
        assertEquals(listOf("set-content"), bridge.calls.filter { it.startsWith("set-") }) // 就绪即补注
    }

    @Test
    fun `content applies immediately once ready`() {
        editorReady()
        scheduler.runCurrent()
        bridge.calls.clear()
        controller.setContentWhenReady(DOC)
        scheduler.runCurrent()
        assertEquals(listOf("set-content"), bridge.calls)
    }

    /** RN 草稿覆盖 bug 的两道门之二：同版本内容不因消息流重放（effect 重跑等价）而重注。 */
    @Test
    fun `same content version is not re applied by message flow replays`() {
        editorReady()
        controller.setContentWhenReady(DOC)
        scheduler.runCurrent()
        assertEquals(1, bridge.calls.count { it.startsWith("set-") })
        // stateUpdate / content-update 重放不触发注入（无新版本）
        controller.onRawMessage("""{"type":"stateUpdate","payload":{}}""")
        controller.onRawMessage("""{"type":"content-update"}""")
        scheduler.runCurrent()
        assertEquals(1, bridge.calls.count { it.startsWith("set-") })
        // 新版本（编辑回填/草稿重载）才重注
        controller.setContentWhenReady(OTHER_DOC)
        scheduler.runCurrent()
        assertEquals(2, bridge.calls.count { it.startsWith("set-") })
    }

    /** WebView 重建（RN iOS remount 同义）：新就绪周期重注当前挂起内容。 */
    @Test
    fun `new ready cycle re applies pending content`() {
        controller.setContentWhenReady(DOC)
        editorReady()
        scheduler.runCurrent()
        assertEquals(1, bridge.calls.count { it == "set-content" })
        editorReady() // 第二周期（WebView 重建）
        scheduler.runCurrent()
        assertEquals(2, controller.editorReadyCount)
        assertEquals(2, bridge.calls.count { it == "set-content" })
    }

    @Test
    fun `html fallback form routes to set content html`() {
        editorReady()
        bridge.calls.clear()
        controller.setContentHtmlWhenReady("<p>hi</p>")
        scheduler.runCurrent()
        assertEquals(listOf("set-html:<p>hi</p>"), bridge.calls)
        assertEquals("hi", controller.plainText)
    }

    @Test
    fun `content updates via message pipeline settle plain text`() {
        editorReady()
        val json = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"abc"}]}]}""",
        ) as JsonObject
        bridge.nextJson = json
        var settled: Pair<JsonObject?, String>? = null
        controller.onContentSettled = { j, p -> settled = j to p }
        controller.onRawMessage("""{"type":"stateUpdate","payload":{"isFocused":true,"contentHeight":101.5}}""")
        controller.onRawMessage("""{"type":"content-update"}""")
        scheduler.advanceUntilIdle()
        assertEquals(json.toString(), controller.jsonContent.toString())
        assertEquals("abc", controller.plainText)
        assertEquals("abc", settled?.second)
        assertEquals(101.5, controller.contentHeightDp, 0.0)
        assertTrue(controller.isFocused)
    }

    // ── 焦点单一调度 ──

    @Test
    fun `focus dispatches after debounce and dedupes same direction`() {
        editorReady()
        controller.requestFocus("end")
        scheduler.runCurrent()
        assertTrue(bridge.calls.none { it.startsWith("focus") }) // 50ms debounce 未到
        scheduler.advanceTimeBy(50)
        scheduler.runCurrent()
        assertEquals(listOf("focus:end"), bridge.calls)

        // 同向连发（keyboardDidShow resync 等价）→ 去重不下发
        controller.requestFocus("end")
        scheduler.advanceTimeBy(50)
        scheduler.runCurrent()
        assertEquals(1, bridge.calls.count { it.startsWith("focus") })
    }

    @Test
    fun `stale generation focus is discarded by newer blur`() {
        editorReady()
        controller.requestFocus("end")
        controller.requestBlur() // blur 立即 + generation 丢弃在途 focus
        scheduler.runCurrent()
        assertEquals(listOf("blur"), bridge.calls)
        scheduler.advanceTimeBy(200)
        assertEquals(listOf("blur"), bridge.calls)
    }

    @Test
    fun `blur dispatches immediately without debounce`() {
        editorReady()
        controller.requestBlur()
        scheduler.runCurrent()
        assertEquals(listOf("blur"), bridge.calls)
    }

    /**
     * 评审 Critical-2：未就绪 focus 不静默丢——记意图挂起，editor-ready 时重派一次
     * （RN useEditorFocusController 行为；原实现 `if (!isReady) return@launch` 无人重派）。
     */
    @Test
    fun `focus request before ready is held and dispatched once ready`() {
        controller.requestFocus("end")
        scheduler.advanceTimeBy(200)
        assertTrue(bridge.calls.none { it.startsWith("focus") }) // 未就绪：挂起不下发

        editorReady()
        scheduler.advanceTimeBy(50) // 重派走 debounce
        scheduler.runCurrent()
        assertEquals(listOf("focus:end"), bridge.calls)
    }

    /** blur 清挂起意图（后到意图胜出）：挂起 focus 被 blur 覆盖后 ready 也不重派。 */
    @Test
    fun `blur before ready clears held focus`() {
        controller.requestFocus("end")
        controller.requestBlur()
        scheduler.runCurrent()
        assertEquals(listOf("blur"), bridge.calls)
        editorReady()
        scheduler.advanceTimeBy(200)
        scheduler.runCurrent()
        assertEquals(listOf("blur"), bridge.calls) // 无挂起 focus 被重派
    }

    @Test
    fun `blur can follow focus once direction flips`() {
        editorReady()
        controller.requestFocus("end")
        scheduler.advanceTimeBy(50)
        scheduler.runCurrent() // focus 落地
        controller.requestBlur()
        scheduler.runCurrent()
        assertEquals(listOf("focus:end", "blur"), bridge.calls)
    }

    // ── mention 链 ──

    @Test
    fun `mention trigger records range and selection applies delete plus inserts`() {
        editorReady()
        val accepted = mutableListOf<Pair<String, Long>>()
        controller.onMentionNavigate = { query, pos -> accepted += query to pos }
        controller.onRawMessage(
            """{"type":"action","payload":{"type":"mention-trigger","payload":{"query":"ab","cursorPos":6}}}""",
        )
        assertEquals(listOf("ab" to 6L), accepted)
        assertEquals(6L to 9L, controller.mentionRange) // @ + query.length

        controller.applyMentionSelection(
            listOf(cn.appia.im.feature.chat.MentionCandidate("u1", "u1", "@Alice")),
        )
        assertEquals(listOf("delete:6-9", "mention:u1/@Alice"), bridge.calls)
        assertNull(controller.mentionRange)
    }

    @Test
    fun `toolbar mention without range inserts at cursor only`() {
        editorReady()
        controller.applyMentionSelection(
            listOf(cn.appia.im.feature.chat.ALL_MEMBER),
        )
        assertEquals(listOf("mention:all/all"), bridge.calls) // 无 range 不 delete
    }

    // ── fix round 2：提及选中就绪门控 + WebView 销毁重置 ──

    /** 未就绪到达的选中挂起，ready 后（内容注入之后）补插——生产 = 选人页返回冷 WebView 竞态。 */
    @Test
    fun `mention selection before ready is held and applied after content on ready`() {
        controller.setContentWhenReady(DOC) // 草稿挂起（选人前的编辑器内容）
        controller.applyMentionSelection(
            listOf(cn.appia.im.feature.chat.MentionCandidate("u1", "u1", "@Alice")),
        )
        scheduler.runCurrent()
        assertTrue(bridge.calls.isEmpty()) // 未就绪：不 delete 不 insert

        editorReady()
        scheduler.runCurrent()
        // 顺序：内容先注入，提及后补发（deleteRange/insertMention 依赖文档就位）
        assertEquals(listOf("set-content", "mention:u1/@Alice"), bridge.calls)
    }

    /** 无挂起内容时 ready 即补插提及（空草稿路径）。 */
    @Test
    fun `held mention applies on ready without pending content`() {
        controller.applyMentionSelection(
            listOf(cn.appia.im.feature.chat.MentionCandidate("u1", "u1", "@Alice")),
        )
        editorReady()
        scheduler.runCurrent()
        assertEquals(listOf("mention:u1/@Alice"), bridge.calls)
    }

    /** WebView 离组合：就绪归零 + 桥置空；挂起提及/focus 留存，下个 ready 周期补发。 */
    @Test
    fun `web view destroy resets readiness and next ready cycle re flushes held mention`() {
        editorReady()
        controller.onWebViewDestroyed()
        assertFalse(controller.isReady)
        controller.bridge = bridge // 返回重建：onWebViewReady 重接桥（生产等价时序）
        controller.applyMentionSelection(
            listOf(cn.appia.im.feature.chat.MentionCandidate("u1", "u1", "@Alice")),
        )
        assertTrue(bridge.calls.none { it.startsWith("mention:") }) // 冷态挂起

        editorReady() // 返回重建：新就绪周期
        scheduler.runCurrent()
        assertEquals(1, bridge.calls.count { it == "mention:u1/@Alice" })
    }

    // ── IME 预热判定（RN shouldPrimeAndroidIme 逐条）──

    @Test
    fun `ime prime fires once when ready and focused on android`() {
        assertTrue(shouldPrimeAndroidIme("android", isReady = true, isFocused = true, primed = false))
        assertFalse(shouldPrimeAndroidIme("android", isReady = true, isFocused = true, primed = true))
        assertFalse(shouldPrimeAndroidIme("android", isReady = false, isFocused = true, primed = false))
        assertFalse(shouldPrimeAndroidIme("android", isReady = true, isFocused = false, primed = false))
        assertFalse(shouldPrimeAndroidIme("ios", isReady = true, isFocused = true, primed = false))
    }

    @Test
    fun `clear editor resets local content snapshot`() {
        editorReady()
        controller.setContentWhenReady(DOC)
        controller.clearEditor()
        assertTrue(bridge.calls.contains("set-content")) // 空 doc 注入（RN clearChatEditor）
        assertTrue(bridge.calls.contains("focus:start")) // Android 光标回起点
        assertNull(controller.jsonContent)
        assertEquals("", controller.plainText)
    }

    // ── T13 工具栏：stateUpdate 活动态解析 + 命令动作序列 ──

    /** stateUpdate → 工具栏活动态（10tap extendEditorState 合并产物直读）。 */
    @Test
    fun `state update populates toolbar activity flags`() {
        controller.onRawMessage(
            """{"type":"stateUpdate","payload":{"isBoldActive":true,"isItalicActive":false,"isStrikeActive":true,
               "isOrderedListActive":true,"isBulletListActive":false,"headingLevel":2,
               "activeColor":"#EF4444","activeFontSize":"16px"}}""",
        )
        assertTrue(controller.isBoldActive)
        assertFalse(controller.isItalicActive)
        assertTrue(controller.isStrikeActive)
        assertTrue(controller.isOrderedListActive)
        assertFalse(controller.isBulletListActive)
        assertEquals(2, controller.headingLevel)
        assertEquals("#EF4444", controller.activeColor)
        assertEquals("16px", controller.activeFontSize)
        assertFalse(controller.isHighlightActive) // 高亮 = bold+#FF0000+16px 三者同活

        // activeColor null（JS null）→ 清空
        controller.onRawMessage("""{"type":"stateUpdate","payload":{"activeColor":null}}""")
        assertNull(controller.activeColor)
    }

    /** RN useBridgeState 整体替换：缺席键重置默认（清除标题/字色后不假亮滞留）。 */
    @Test
    fun `absent state keys reset to defaults instead of retaining`() {
        controller.onRawMessage(
            """{"type":"stateUpdate","payload":{"isBoldActive":true,"headingLevel":2,
               "activeColor":"#EF4444","activeFontSize":"16px"}}""",
        )
        controller.onRawMessage("""{"type":"stateUpdate","payload":{"isFocused":true}}""")
        assertFalse(controller.isBoldActive)
        assertEquals(0, controller.headingLevel)
        assertNull(controller.activeColor)
        assertNull(controller.activeFontSize)
    }

    /** 高亮组合 on：bold + set-color(#FF0000) + set-font-size(16px)（RN :1054-1058）。 */
    @Test
    fun `toggle highlight on sends bold color fontsize combo`() {
        controller.toggleHighlight()
        val actions = bridge.calls.filter { it.startsWith("action:") }
        assertEquals(
            listOf(
                "action:toggle-bold",
                "action:set-color:\"#FF0000\"",
                "action:set-font-size:\"16px\"",
            ),
            actions,
        )
    }

    /** 高亮 off（三态同活时）：bold + unset-color + unset-font-size（RN :1051-1053）。 */
    @Test
    fun `toggle highlight off sends bold unset color fontsize`() {
        controller.onRawMessage(
            """{"type":"stateUpdate","payload":{"isBoldActive":true,"activeColor":"#FF0000","activeFontSize":"16px"}}""",
        )
        assertTrue(controller.isHighlightActive)
        controller.toggleHighlight()
        val actions = bridge.calls.filter { it.startsWith("action:") }
        assertEquals(
            listOf("action:toggle-bold", "action:unset-color", "action:unset-font-size"),
            actions,
        )
    }

    /** 颜色（RN handleSetColor）：非空 setColor / 空 unsetColor。 */
    @Test
    fun `set color routes to set or unset by nullability`() {
        controller.setColor("#3B82F6")
        controller.setColor(null)
        val actions = bridge.calls.filter { it.startsWith("action:") }
        assertEquals(listOf("action:set-color:\"#3B82F6\"", "action:unset-color"), actions)
    }

    /** 清除格式（RN handleClearFormat fallback：web 构建无 clear-nodes 动作 → 逐活动态翻转）。 */
    @Test
    fun `clear format toggles only active marks and unsets color`() {
        controller.onRawMessage(
            """{"type":"stateUpdate","payload":{"isBoldActive":true,"isItalicActive":true,
               "isOrderedListActive":true,"headingLevel":3}}""",
        )
        controller.clearFormat()
        val actions = bridge.calls.filter { it.startsWith("action:") }
        assertEquals(
            listOf(
                "action:toggle-heading:3",
                "action:toggle-orderedList",
                "action:toggle-bold",
                "action:toggle-italic",
                "action:unset-color",
                "action:unset-font-size",
                "action:unset-highlight",
            ),
            actions,
        )
    }

    /** 简单 toggle 命令直发动作名（bold/italic/strike/orderedList/bulletList）。 */
    @Test
    fun `simple toggles dispatch matching actions`() {
        controller.toggleBold()
        controller.toggleItalic()
        controller.toggleStrike()
        controller.toggleOrderedList()
        controller.toggleBulletList()
        assertEquals(
            listOf(
                "action:toggle-bold", "action:toggle-italic", "action:toggle-strike",
                "action:toggle-orderedList", "action:toggle-bulletList",
            ),
            bridge.calls.filter { it.startsWith("action:") },
        )
    }

    companion object {
        val DOC = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"hi"}]}]}""",
        ) as JsonObject
        val OTHER_DOC = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"yo"}]}]}""",
        ) as JsonObject
    }
}
