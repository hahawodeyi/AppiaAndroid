package cn.appia.im.feature.contacts.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.AppiaTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CreateChannelMembersScreen Compose 冒烟（Robolectric）：三 intent 结构、me 预选、
 * tab 切换、addToRoom 既有成员预检不出底栏、确认禁用态、forward 失败 Alert 先弹后跳
 * （评审 I-1：Alert 随屏亡 → 挂起跳转 dismiss 放行）。判定/wire 其余在
 * CreateChannelSelectionTest/ChannelsApiTest/SpotlightApiTest 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CreateChannelMembersScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

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

    /**
     * 评审 I-1：forward 失败 Alert 必须先弹、dismiss 才放行跳转。
     * 链路：allAutoJoin 使能确认 → channels.create 成功 → chat.sendMessage 500 →
     * Alert（forwardfailed）可见且 onCreated 未触发 → OK dismiss → onCreated 收到新 rid。
     * dispatcher 按 path 分发（ContactsStore hrm 刷新与否随静态态不定，FIFO 不可靠）。
     */
    @Test
    fun `forward failure shows alert first then navigates on dismiss`() {
        var createdRid: String? = null
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                when {
                    request.path?.contains("channels.create") == true ->
                        MockResponse().setBody("""{"channel":{"_id":"NEW-RID","fname":"New Channel"}}""")
                    request.path?.contains("chat.sendMessage") == true ->
                        MockResponse().setResponseCode(500).setBody("boom")
                    else -> MockResponse().setBody("{}") // hrm 等旁路请求
                }
        }
        rule.setContent {
            AppiaTheme(isDark = false) {
                CreateChannelMembersScreen(
                    intent = "forward",
                    rid = null,
                    existingMemberUsernames = emptyList(),
                    preselectedUsernames = emptyList(),
                    forwardMessageIds = listOf("m1"),
                    forwardIsMerged = false,
                    sdk = newSdk(),
                    dbManager = null,
                    serverUrl = "https://s1",
                    currentUserId = "me",
                    currentUsername = "me",
                    onBack = {},
                    onCreated = { rid, _ -> createdRid = rid },
                )
            }
        }
        rule.waitForIdle()
        // forward 标题（评审 Minor：roomList_addMenu_createChannel）
        rule.onNodeWithText(context.t("roomlist_addmenu_createchannel")).assertExists()
        // all=true 使能确认 → 点击
        rule.onNodeWithTag("qa-ccm-toggle-all").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qa-ccm-confirm").performClick()
        // 真网络（MockWebServer IO 线程）——waitUntil 轮询 Alert 上屏
        rule.waitUntil(5_000) { createdRid != null || alertShown() }
        // Alert 可见、跳转未发生
        rule.onNodeWithText(context.t("forwardfailed")).assertExists()
        assertEquals(null, createdRid)
        // dismiss 放行跳转
        rule.onNodeWithText("OK").performClick()
        rule.waitForIdle()
        assertEquals("NEW-RID", createdRid)
    }

    private fun alertShown(): Boolean =
        rule.onAllNodesWithText(context.t("forwardfailed")).fetchSemanticsNodes().isNotEmpty()
}
