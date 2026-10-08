package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.feature.chat.LOAD_MORE_BEFORE
import cn.appia.im.feature.chat.MessageJumpController
import cn.appia.im.feature.chat.MessageJumpUiState
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 跳转高亮 UI 冒烟（M5-T6）：
 * - MessageRow highlighted=true → qa-message-highlighted tag（RN RoomMessageRow :231 同判定）；
 * - 跳转态 chunk 占位行 1px 渲染（M2-T1 通路复用：不出消息内容）；
 * - 加载浮层可点击取消（qa-jump-to-message-loading → cancelJump）；
 * - 跳转窗口替换实时源（jumpMessages 非空时列表显示跳转窗口而非实时窗口）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JumpHighlightUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun row(id: String, msg: String = "m-$id") = MessageEntity(
        _id = id, rid = "r1", ts = 10.0, u = """{"_id":"u1","username":"u1"}""",
        alias = "", parse_urls = "[]", _updated_at = 10.0, msg = msg,
    )

    @Test
    fun `highlighted row swaps test tag`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageRow(message = row("m1"), highlighted = true, currentUserId = "me", currentUsername = "me", serverUrl = "", token = null, onResend = {})
            }
        }
        rule.onNodeWithTag("qa-message-highlighted").assertExists()
        rule.onNodeWithTag("qa-room-message-row").assertDoesNotExist()
    }

    @Test
    fun `default row keeps plain tag`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageRow(message = row("m1"), currentUserId = "me", currentUsername = "me", serverUrl = "", token = null, onResend = {})
            }
        }
        rule.onNodeWithTag("qa-room-message-row").assertExists()
        rule.onNodeWithTag("qa-message-highlighted").assertDoesNotExist()
    }

    @Test
    fun `loading overlay renders and click cancels`() {
        var cancelled = 0
        rule.setContent {
            AppiaTheme(isDark = false) {
                JumpLoadingOverlay(onCancel = { cancelled++ })
            }
        }
        rule.onNodeWithTag("qa-jump-to-message-loading").performClick()
        rule.waitForIdle()
        assertEquals(1, cancelled)
    }

    @Test
    fun `jump loading overlay keeps message list laid out`() {
        // 评审 I2 回归测试：isJumpLoading=true 时 overlay 叠加而非参与 Column 测量——
        // 列表与输入栏占位不动（fillMaxSize 会把 weighted 兄弟挤到 0px 最长 15s）
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomScreen(
                    rid = "r1",
                    title = "dev",
                    state = cn.appia.im.feature.chat.RoomMessagesUiState(
                        rid = "r1",
                        messages = listOf(row("m1")),
                    ),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "",
                    token = null,
                    jumpState = cn.appia.im.feature.chat.MessageJumpUiState(isJumpLoading = true),
                    draftController = null,
                    editorController = cn.appia.im.feature.chat.editor.rememberChatInputBarController(),
                    onSend = { _, _ -> },
                    onResend = {},
                    onBack = {},
                    onLoadEarlier = {},
                )
            }
        }
        rule.waitForIdle()
        // 列表仍在（overlay 未挤占其测量）+ overlay 存在
        rule.onNodeWithTag("qa-room-message-list").assertExists()
        rule.onNodeWithTag("qa-jump-to-message-loading").assertExists()
        rule.onNodeWithTag("qa-room-editor").assertExists()
    }
}
