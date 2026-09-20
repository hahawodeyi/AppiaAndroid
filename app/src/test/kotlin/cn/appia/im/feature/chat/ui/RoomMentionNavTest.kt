package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.MENTION_SELECTED_KEY
import cn.appia.im.MentionSuggestionRoute
import cn.appia.im.RoomRoute
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chat.MentionCandidate
import cn.appia.im.feature.chat.RoomMessagesUiState
import cn.appia.im.feature.chat.editor.ChatInputBarController
import cn.appia.im.feature.chat.editor.EditorBridge
import cn.appia.im.feature.chat.editor.RoomEditorViewModel
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 评审 fix round 2 Critical-1/Important-2 回归：@提及选人 → 真 NavHost 往返 → 回插生效且不重放。
 *
 * **与生产装配同构**：控制器宿主 = entry 级 RoomEditorViewModel（viewModel()，组合销毁不丢）；
 * 不提升到组合外、不预接 fake bridge。navigation 2.9.5 非 FloatingWindow 压栈即把下层 entry
 * 移出 visibleEntries——RoomRoute 组合随选人页打开而销毁（WebView 亦销毁），返回重建。
 * Robolectric 下 WebView 是占位 View（无 onWebViewReady 回调）：返回重建后接记录桥 +
 * 注入 editor-ready 模拟生产时序（工厂回调接桥、web 侧 ready 事件）。导航销毁期
 * onRelease 已把桥置空（onWebViewDestroyed）——接桥必须在重建后。就绪前到达的选中由
 * 控制器 pendingMention 门控挂起——生产竞态时序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomMentionNavTest {

    @get:Rule
    val rule = createComposeRule()

    private class RecordingBridge : EditorBridge {
        val calls = mutableListOf<String>()
        override fun setContent(content: JsonObject) { calls += "set-content" }
        override fun setContentHtml(html: String) { calls += "set-html" }
        override suspend fun getJson(): JsonObject? = null
        override fun focus(pos: String) { calls += "focus:$pos" }
        override fun blur() { calls += "blur" }
        override fun insertMention(id: String, label: String) { calls += "mention:$id/$label" }
        override fun insertEmoji(alt: String, title: String, src: String, type: String) {}
        override fun deleteRange(from: Long, to: Long) { calls += "delete:$from-$to" }
        override fun setEditable(editable: Boolean) {}
    }

    private val bridge = RecordingBridge()
    private var controller: ChatInputBarController? = null

    /** 与 MainActivity RoomRoute/MentionSuggestionRoute 同款接线（savedStateHandle + VM 宿主）。 */
    @Composable
    private fun TestNavHost() {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = RoomRoute(rid = "r1", title = "dev", roomType = "c")) {
            composable<RoomRoute> { entry ->
                val vm = viewModel<RoomEditorViewModel>()
                LaunchedEffect(vm) { controller = vm.controller }
                RoomScreen(
                    rid = "r1",
                    title = "dev",
                    state = RoomMessagesUiState(rid = "r1", roomType = "c"),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    draftController = null,
                    onSend = { _, _ -> },
                    onResend = {},
                    onBack = {},
                    onLoadEarlier = {},
                    onOpenMentionSuggestion = { query ->
                        nav.navigate(MentionSuggestionRoute(rid = "r1", roomType = "c", initialQuery = query))
                    },
                    mentionSelections = entry.savedStateHandle.getStateFlow(MENTION_SELECTED_KEY, emptyList<MentionCandidate>()),
                    onMentionSelectionConsumed = {
                        entry.savedStateHandle.set(MENTION_SELECTED_KEY, emptyList<MentionCandidate>())
                    },
                    editorController = vm.controller,
                )
            }
            composable<MentionSuggestionRoute> {
                MentionSuggestionScreen(
                    initialQuery = "",
                    isAgentRoom = false,
                    loadCandidates = { _ ->
                        listOf(MentionCandidate(id = "u1", username = "alice", displayName = "Alice"))
                    },
                    onSelected = { members ->
                        nav.previousBackStackEntry?.savedStateHandle?.set(MENTION_SELECTED_KEY, members)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }

    @Test
    fun `mention selection survives round trip with production wiring and inserts once ready`() {
        rule.setContent { AppiaTheme(isDark = false) { TestNavHost() } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { controller != null }

        // @ 直跳选人页——RoomRoute 组合随导航销毁（navigation 2.9.5 visibleEntries 语义）
        rule.onNodeWithTag("qa-room-mention").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-mention-row-u1").fetchSemanticsNodes().isNotEmpty() }

        // 选中 → 写 previousBackStackEntry.savedStateHandle → popBackStack（组合重建、控制器 VM 存活）
        rule.onNodeWithTag("qa-mention-row-u1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-room-editor").fetchSemanticsNodes().isNotEmpty() }

        // 重建后控制器未就绪（WebView 冷）→ 选中挂起 pendingMention；重接桥 + 注入 ready =
        // 生产 onWebViewReady + editor-ready 时序（导航销毁期 onRelease 已清桥）
        rule.runOnIdle {
            controller!!.bridge = bridge
            controller!!.onRawMessage("""{"type":"editor-ready"}""")
        }
        rule.waitUntil(5_000) { bridge.calls.any { it.startsWith("mention:") } }
        assertEquals(listOf("mention:alice/Alice"), bridge.calls.filter { it.startsWith("mention:") })
        assertEquals(0, bridge.calls.count { it.startsWith("delete:") }) // 工具栏直跳无 range
    }

    @Test
    fun `consumed selection does not replay on next round trip`() {
        rule.setContent { AppiaTheme(isDark = false) { TestNavHost() } }
        rule.waitForIdle()
        rule.waitUntil(5_000) { controller != null }

        // 第一轮：选中 alice → 返回 → ready → 插入一次（消费即写回空表）
        rule.onNodeWithTag("qa-room-mention").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-mention-row-u1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("qa-mention-row-u1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-room-editor").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle {
            controller!!.bridge = bridge
            controller!!.onRawMessage("""{"type":"editor-ready"}""")
        }
        rule.waitUntil(5_000) { bridge.calls.count { it.startsWith("mention:") } == 1 }

        // 第二轮：进选人页直接返回（未选）→ 不重放旧选中（Important-2：粘性已清）。
        // 返回后控制器冷：若粘性重放，选中只会挂起——注入新 ready 周期逼出潜在二次插入再断言。
        rule.onNodeWithTag("qa-room-mention").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-mention-search").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("qa-room-header-back").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-room-editor").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle {
            controller!!.bridge = bridge
            controller!!.onRawMessage("""{"type":"editor-ready"}""") // 第二就绪周期
        }
        assertEquals(1, bridge.calls.count { it.startsWith("mention:") })
    }
}
