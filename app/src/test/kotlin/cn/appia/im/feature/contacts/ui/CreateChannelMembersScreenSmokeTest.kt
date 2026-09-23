package cn.appia.im.feature.contacts.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * CreateChannelMembersScreen Compose 冒烟（Robolectric）：三 intent 结构、me 预选、
 * tab 切换、addToRoom 既有成员预检不出底栏、确认禁用态。判定/wire 在
 * CreateChannelSelectionTest/ChannelsApiTest 已覆盖；sdk=null 走静默分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CreateChannelMembersScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun setContent(
        intent: String,
        existing: List<String> = emptyList(),
        onCreated: (String, String?) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                CreateChannelMembersScreen(
                    intent = intent,
                    rid = if (intent == "addToRoom") "r1" else null,
                    existingMemberUsernames = existing,
                    preselectedUsernames = emptyList(),
                    forwardMessageIds = emptyList(),
                    forwardIsMerged = false,
                    sdk = null, // 静默分支：无网络、组织树 LOADING
                    dbManager = null,
                    serverUrl = "https://s1",
                    currentUserId = "me",
                    currentUsername = "me",
                    onBack = {},
                    onCreated = onCreated,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `create intent preselects me and disables confirm until second user`() {
        setContent(intent = "create")
        // me 预选 → 底栏出已选条（u:me）
        rule.onNodeWithTag("qa-ccm-selected-u:me").assertExists()
        // 仅 1 人 → 确认禁用（canConfirmCreateChannel members 非 all 需 ≥2）
        rule.onNodeWithTag("qa-ccm-confirm").assertIsNotEnabled()
    }

    @Test
    fun `all auto join enables confirm`() {
        setContent(intent = "create")
        rule.onNodeWithTag("qa-ccm-toggle-all").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qa-ccm-confirm").assertIsEnabled()
    }

    @Test
    fun `addToRoom prechecks existing members but keeps them out of selected bar`() {
        setContent(intent = "addToRoom", existing = listOf("alice"))
        // 既有成员预检：不出底栏（无 u:alice 条目）
        assertEquals(0, rule.onAllNodesWithTag("qa-ccm-selected-u:alice").fetchSemanticsNodes().size)
        // 无新选 → 确认禁用（canConfirmAddToRoom 需非既有用户或部门）
        rule.onNodeWithTag("qa-ccm-confirm").assertIsNotEnabled()
        // 分享消息记录开关隐藏（RN :1628：addToRoom 且 canConfirm 才显示）
        assertEquals(0, rule.onAllNodesWithTag("qa-ccm-share-record").fetchSemanticsNodes().size)
    }

    @Test
    fun `org tab switches without crash`() {
        setContent(intent = "create")
        rule.onNodeWithText(context.t("createchannelmembers_taborg")).performClick()
        rule.waitForIdle()
        // org 模式 org 子 tab 常驻（部门树无 sdk → LOADING 提示）
        rule.onNodeWithTag("qa-ccm-org-tab-pmt").assertExists()
    }

    @Test
    fun `search input hides tabs`() {
        setContent(intent = "create")
        rule.onNodeWithTag("qa-ccm-search-input").performTextInput("bob")
        rule.waitForIdle()
        // 搜索态：tab 栏隐藏（RN :1644/:1671）
        assertEquals(0, rule.onAllNodesWithTag("qa-ccm-main-tab-members").fetchSemanticsNodes().size)
    }
}
