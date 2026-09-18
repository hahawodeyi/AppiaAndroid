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

    @Test
    fun `focus request before ready is held and never dispatched`() {
        controller.requestFocus("end")
        scheduler.advanceTimeBy(200)
        assertTrue(bridge.calls.none { it.startsWith("focus") }) // 未就绪挂起
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
        controller.applyMentionSelection(
            listOf(cn.appia.im.feature.chat.ALL_MEMBER),
        )
        assertEquals(listOf("mention:all/all"), bridge.calls) // 无 range 不 delete
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

    companion object {
        val DOC = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"hi"}]}]}""",
        ) as JsonObject
        val OTHER_DOC = Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"yo"}]}]}""",
        ) as JsonObject
    }
}
