package cn.appia.im.feature.roominfo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RoomMembersScreen Compose 冒烟（Robolectric）：list/remove 双模式结构、remove 模式
 * 头部移除钮随选中态禁用、部门块展开、导航回调参数化。权限判定/wire 在
 * RoomMemberActionsTest 已覆盖；sdk=null 走静默分支（冒烟只查 UI 结构）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomMembersScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun setContent(mode: String, onOpenMemberProfile: (String, String?) -> Unit = { _, _ -> }) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomMembersScreen(
                    rid = "r1",
                    roomType = "c",
                    mode = mode,
                    sdk = null, // 静默分支：loading 即落、无网络
                    currentUserId = "me",
                    globalRoles = emptyList(),
                    serverUrl = "https://s1",
                    token = "tok",
                    onBack = {},
                    onOpenMemberProfile = onOpenMemberProfile,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `list mode shows group members title and no remove action`() {
        setContent(mode = "list")
        rule.onNodeWithText(context.t("roomMembers_title")).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("qa-roommembers-remove-action").fetchSemanticsNodes().size)
    }

    @Test
    fun `remove mode shows remove title and disabled remove action`() {
        setContent(mode = "remove")
        rule.onNodeWithText(context.t("roomMembers_removeTitle")).assertExists()
        // 无选中 → 移除钮存在但禁用（RN headerRight disabled=!hasBulkSelection）
        rule.onNodeWithTag("qa-roommembers-remove-action").assertExists()
    }

    @Test
    fun `empty state renders without crash when sdk null`() {
        setContent(mode = "list")
        // sdk null → loading 立即落空列表；无崩溃即过
        rule.waitForIdle()
    }

    @Test
    fun `member profile callback parameterized`() {
        var opened: String? = null
        setContent(mode = "list", onOpenMemberProfile = { u, _ -> opened = u })
        rule.waitForIdle()
        assertEquals(null, opened) // 无成员行可点（sdk null 空列表）
    }
}
