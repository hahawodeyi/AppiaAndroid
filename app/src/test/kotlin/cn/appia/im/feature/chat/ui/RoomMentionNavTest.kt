package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.MENTION_SELECTED_KEY
import cn.appia.im.MentionSuggestionRoute
import cn.appia.im.RoomRoute
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chat.MentionCandidate
import cn.appia.im.feature.chat.RoomMessagesUiState
import cn.appia.im.feature.chat.editor.ChatInputBarController
import cn.appia.im.feature.chat.editor.EditorBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 评审 Critical-1 回归：@提及选人 → 真 NavHost 往返（popBackStack 回房）→ 回插生效。
 * 原 MentionSelectionBus（MutableSharedFlow replay=0）在选人页打开期间 RoomScreen collector
 * 已取消、tryEmit 即丢——选人后不回插。修法 = previousBackStackEntry.savedStateHandle
 * （Navigation Compose 跨屏结果惯例）；本测试按 MainActivity 同款接线走真导航。
 * 桥以 fake 记录（Robolectric 无真 WebView），候选加载 fake（数据源单测在 MentionSourceTest）。
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

    /** 与 MainActivity RoomRoute/MentionSuggestionRoute 同款接线（savedStateHandle 结果回投）。 */
    @Composable
    private fun TestNavHost(editor: ChatInputBarController) {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = RoomRoute(rid = "r1", title = "dev", roomType = "c")) {
            composable<RoomRoute> { entry ->
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
                    mentionSelections = entry.savedStateHandle.getStateFlow(MENTION_SELECTED_KEY, emptyList()),
                    editorController = editor,
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
    fun `mention selection survives navigation round trip and inserts into editor`() {
        val bridge = RecordingBridge()
        val editor = ChatInputBarController(CoroutineScope(Dispatchers.Unconfined))
        editor.bridge = bridge

        rule.setContent { AppiaTheme(isDark = false) { TestNavHost(editor) } }
        rule.waitForIdle()

        // 工具栏 @ 直跳选人页（非 DM 房渲染 qa-room-mention）
        rule.onNodeWithTag("qa-room-mention").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-mention-row-u1").fetchSemanticsNodes().isNotEmpty() }

        // 选中 alice → 结果写 previousBackStackEntry.savedStateHandle → popBackStack 回房
        rule.onNodeWithTag("qa-mention-row-u1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("qa-room-editor").fetchSemanticsNodes().isNotEmpty() }

        // 回房后 StateFlow 投递 → 回插（无 range 不 delete）+ 重新拉焦
        rule.waitUntil(5_000) { bridge.calls.any { it.startsWith("mention:") } }
        assertEquals(true, bridge.calls.any { it == "mention:alice/Alice" })
        assertEquals(0, bridge.calls.count { it.startsWith("delete:") }) // 工具栏直跳无 range
    }
}
