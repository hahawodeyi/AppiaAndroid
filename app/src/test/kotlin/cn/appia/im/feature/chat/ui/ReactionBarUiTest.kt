package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TADA_ONLY = """{":tada:":{"_id":"m1:tada:","emoji":":tada:","usernames":["bob"]}}"""

/**
 * ReactionBar/ReactionPicker UI 测试：行内反应条（emoji+计数+自己已回应态渲染）、
 * 点击 toggle 透传 shortname、＋入口开 picker、picker 选 emoji toggle；
 * MessageRow 行内挂接（普通行 reactions 非空才渲染）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReactionBarUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `empty reactions still renders bar with add entry`() {
        // T13 组装（T8-T11 窗口缺口）：空条也渲染 ＋，首反应入口不依赖长按菜单
        rule.setContent {
            AppiaTheme(isDark = false) { ReactionBar(reactionsJson = null, currentUsername = "me", onToggle = {}) }
        }
        rule.onAllNodesWithTag("qa-reaction-bar").assertCountEquals(1)
        rule.onAllNodesWithTag("qa-reaction-add").assertCountEquals(1)
    }

    @Test
    fun `bar renders chips and click toggles shortname`() {
        val toggled = mutableListOf<String>()
        rule.setContent {
            AppiaTheme(isDark = false) {
                ReactionBar(
                    reactionsJson = """{":tada:":{"usernames":["bob"]},":fire:":{"usernames":["bob","alice"]}}""",
                    currentUsername = "bob", // :fire: 自己已回应（高亮态分支）
                    onToggle = { toggled += it },
                )
            }
        }
        rule.onNodeWithTag("qa-reaction-chip-:tada:").assertExists()
        rule.onNodeWithTag("qa-reaction-chip-:fire:").assertExists()
        rule.onNodeWithTag("qa-reaction-chip-:tada:").performClick()
        rule.waitForIdle()
        assertEquals(listOf(":tada:"), toggled)
    }

    @Test
    fun `add button opens picker and selecting emoji toggles`() {
        val toggled = mutableListOf<String>()
        rule.setContent {
            AppiaTheme(isDark = false) {
                ReactionBar(reactionsJson = TADA_ONLY, currentUsername = "me", onToggle = { toggled += it })
            }
        }
        rule.onNodeWithTag("qa-reaction-picker").assertDoesNotExist()
        rule.onNodeWithTag("qa-reaction-add").performClick()
        rule.onNodeWithTag("qa-reaction-picker").assertExists()
        rule.onNodeWithTag("qa-reaction-pick-:fire:").performClick()
        rule.waitForIdle()
        assertEquals(listOf(":fire:"), toggled)
        rule.onNodeWithTag("qa-reaction-picker").assertDoesNotExist() // 选中即关
    }

    @Test
    fun `message row with reactions renders bar`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                MessageRow(
                    message = MessageEntity(
                        _id = "m1", rid = "r1", ts = 0.0,
                        u = """{"_id":"u2","username":"bob","name":"Bob"}""",
                        alias = "", parse_urls = "[]", _updated_at = 0.0, msg = "hi",
                        reactions = TADA_ONLY,
                    ),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    onResend = {},
                )
            }
        }
        rule.onNodeWithTag("qa-reaction-bar").assertExists()
    }
}
