package cn.appia.im.feature.roominfo.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RoomInfoScreen Compose 冒烟（Robolectric）：非直聊全卡渲染（成员网格/信息卡/设置卡/退出钮）、
 * 直聊分支（头像卡+会议行、无退出钮）、加减槽按 canRemove 出现、导航回调参数化触发。
 * 乐观回滚/wire 序列在 RoomInfoActionsTest 已覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomInfoScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun chatRow(
        t: String = "c",
        f: Boolean = false,
        announcement: String? = null,
        appiaUsage: String? = null,
        uids: String? = null,
    ): ChatEntity = ChatEntity(
        _id = "r1", f = f, t = t, ts = 0.0, ls = 0.0, name = "dev-room", fname = "Dev Room",
        rid = "r1", open = true, alert = false, unread = 0.0, user_mentions = 0.0,
        group_mentions = 0.0, room_updated_at = 0.0, ro = false, archived = false,
        auto_translate_language = "en", team_id = "", announcement = announcement,
        appiaUsage = appiaUsage, uids = uids,
    )

    private fun setContent(
        chat: ChatEntity?,
        onAddMembers: (List<String>) -> Unit = {},
        onRemoveMembers: () -> Unit = {},
        onEditChannelName: () -> Unit = {},
    ) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomInfoScreen(
                    rid = "r1",
                    roomType = chat?.t ?: "c",
                    chat = chat,
                    currentUserId = "me",
                    globalRoles = emptyList(),
                    serverUrl = "https://s1",
                    token = "tok",
                    actions = null, // 权限/成员拉取走 actions==null 静默分支（冒烟只查 UI 结构）
                    onBack = {},
                    onAddMembers = onAddMembers,
                    onRemoveMembers = onRemoveMembers,
                    onEditChannelName = onEditChannelName,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `channel renders cards sections and leave button`() {
        setContent(chatRow(announcement = "First announcement"))

        // RN 结构：节标题与行标题同为 roomInfo_channelName（index.tsx:366-371）→ 两处命中
        assertTrue(rule.onAllNodesWithText(context.t("roominfo_channelname")).fetchSemanticsNodes().size >= 2)
        rule.onNodeWithText("Dev Room").assertExists()
        rule.onNodeWithText(context.t("roominfo_announcement")).assertExists()
        rule.onNodeWithText("First announcement").assertExists()
        rule.onNodeWithText(context.t("roominfo_meeting")).assertExists()
        rule.onNodeWithText(context.t("roominfo_category")).assertExists() // c 房有分类行
        rule.onNodeWithText(context.t("roominfo_mute")).assertExists()
        rule.onNodeWithText(context.t("roominfo_pin")).assertExists()
        rule.onNodeWithText(context.t("roominfo_leavechannel")).assertExists()
        rule.onNodeWithText(context.t("roominfo_moremembers")).assertExists()
        // 无 canRemove（actions null）→ 只加槽不加减槽
        rule.onNodeWithTag("qa-roominfo-add-member").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("qa-roominfo-remove-member").fetchSemanticsNodes().size)
    }

    @Test
    fun `announcement empty shows placeholder`() {
        setContent(chatRow(announcement = null))
        rule.onNodeWithText(context.t("roominfo_announcementempty")).assertExists()
    }

    @Test
    fun `direct chat renders peer card and no members grid or leave`() {
        // 直聊：uids 两人（一对一）
        setContent(chatRow(t = "d", uids = """["me","peer"]"""))

        rule.onNodeWithTag("qa-roominfo-direct-peer").assertExists()
        rule.onNodeWithTag("qa-roominfo-direct-add").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("qa-roominfo-add-member").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("qa-roominfo-more-members").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("qa-roominfo-leave").fetchSemanticsNodes().size)
        // 直聊无分类行（canEditRoomUsage('d')=false）
        assertEquals(0, rule.onAllNodesWithTag("qa-roominfo-category").fetchSemanticsNodes().size)
    }

    @Test
    fun `add member slot emits existing usernames callback`() {
        var received: List<String>? = null
        setContent(chatRow(), onAddMembers = { received = it })
        rule.onNodeWithTag("qa-roominfo-add-member").performClick()
        assertEquals(emptyList<String>(), received) // 成员拉取静默（actions null）→ 空表
    }

    @Test
    fun `usage category value formats labels`() {
        setContent(chatRow(appiaUsage = """["Room_Sort_COP","Room_Sort_Meeting"]"""))
        // 顿号连接（RN formatRoomUsageDisplay join('、')）；文案取当前 locale 的 t()
        rule.onNodeWithText(
            listOf("Room_Sort_COP", "Room_Sort_Meeting").joinToString("\u3001") { context.t(it) },
        ).assertExists()
    }
}
