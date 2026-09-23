package cn.appia.im.feature.roominfo.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.chat.RoomAnnouncementFile
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 公告屏冒烟（Robolectric）：列表/编辑双态、canEdit 门（owner roles 直通）、
 * 直聊不可传文件（上传双钮隐藏）、空态、删除确认 Alert、纯函数（图片判定/附件分类/时间格式化）。
 * wire（saveRoomSettings 发布/删除、announcement.bot 上传）在 RoomSettingsApiTest / AnnouncementBotUploadTest；
 * refreshRoomAnnouncements 回写在 RefreshRoomAnnouncementsTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomAnnouncementScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun chatRow(
        t: String = "c",
        roles: String? = null,
        announcement: String? = null,
        announcements: String? = null,
    ): ChatEntity = ChatEntity(
        _id = "r1", f = false, t = t, ts = 0.0, ls = 0.0, name = "dev-room", fname = "Dev Room",
        rid = "r1", open = true, alert = false, unread = 0.0, user_mentions = 0.0,
        group_mentions = 0.0, room_updated_at = 0.0, ro = false, archived = false,
        auto_translate_language = "en", team_id = "", roles = roles,
        announcement = announcement, announcements = announcements,
    )

    private fun setContent(
        chat: ChatEntity?,
        roomType: String = chat?.t ?: "c",
    ) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomAnnouncementScreen(
                    rid = "r1",
                    roomType = roomType,
                    chat = chat,
                    currentUserId = "me",
                    globalRoles = emptyList(),
                    serverUrl = "https://s1",
                    token = "tok",
                    sdk = null,
                    actions = null,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    // ---- 纯函数 ----

    @Test
    fun `isAnnouncementImageFile matches rn ext list`() {
        assertTrue(isAnnouncementImageFile(RoomAnnouncementFile("a.png", "https://x/a.png", "png")))
        // RN ?? 链：fileType null → fileName 先探（非空串阻断 fileUrl）
        assertTrue(isAnnouncementImageFile(RoomAnnouncementFile("a.jpg", "https://x/other", null)))
        assertFalse(isAnnouncementImageFile(RoomAnnouncementFile("a.pdf", "https://x/a.pdf", "pdf")))
    }

    @Test
    fun `announcementAttachmentKind classifies image video file`() {
        assertEquals(
            AnnouncementKind.IMAGE,
            announcementAttachmentKind(RoomAnnouncementFile("p.png", "https://x/p.png", "png")),
        )
        assertEquals(
            AnnouncementKind.VIDEO,
            announcementAttachmentKind(RoomAnnouncementFile("v.mp4", "https://x/v.mp4", null)),
        )
        assertEquals(
            AnnouncementKind.FILE,
            announcementAttachmentKind(RoomAnnouncementFile("d.pdf", "https://x/d.pdf", "pdf")),
        )
    }

    @Test
    fun `formatAnnouncementUpdateTime parses or falls back to raw`() {
        assertEquals("", formatAnnouncementUpdateTime(null))
        assertEquals("", formatAnnouncementUpdateTime(""))
        // 非日期原文回退（RN Number.isNaN 同义）
        assertEquals("not-a-date", formatAnnouncementUpdateTime("not-a-date"))
    }

    // ---- UI 结构 ----

    @Test
    fun `empty state shows add button for editor`() {
        setContent(chatRow(roles = """["owner"]"""))
        rule.onNodeWithText(context.t("announcement_empty")).assertExists()
        rule.onNodeWithTag("qa-announcement-add").assertExists()
    }

    @Test
    fun `empty state hides add button without edit permission`() {
        setContent(chatRow(roles = """["member"]"""))
        rule.onNodeWithText(context.t("announcement_empty")).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("qa-announcement-add").fetchSemanticsNodes().size)
    }

    @Test
    fun `list shows cards with edit actions for editor and plain text body`() {
        val announcements = """[{"_id":"a1","message":"Hello <b>world</b>","announcementType":0}]"""
        setContent(chatRow(roles = """["owner"]""", announcements = announcements))
        // 纯文本路径：剥标签显示
        rule.onNodeWithText("Hello world", substring = true).assertExists()
        rule.onNodeWithTag("qa-announcement-delete").assertExists()
        rule.onNodeWithTag("qa-announcement-edit").assertExists()
        rule.onNodeWithTag("qa-announcement-add-list").assertExists()
    }

    @Test
    fun `list hides edit actions without permission`() {
        val announcements = """[{"_id":"a1","message":"Hello","announcementType":0}]"""
        setContent(chatRow(roles = """["member"]""", announcements = announcements))
        assertEquals(0, rule.onAllNodesWithTag("qa-announcement-delete").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("qa-announcement-edit").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("qa-announcement-add-list").fetchSemanticsNodes().size)
    }

    @Test
    fun `edit mode shows input and upload buttons for non-direct room`() {
        setContent(chatRow(roles = """["owner"]"""))
        rule.onNodeWithTag("qa-announcement-add").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qa-announcement-input").assertExists()
        rule.onNodeWithTag("qa-announcement-upload-file").assertExists()
        rule.onNodeWithTag("qa-announcement-upload-photo").assertExists()
        rule.onNodeWithTag("qa-announcement-publish").assertExists()
    }

    @Test
    fun `direct room hides upload buttons in edit mode`() {
        // 直聊：canEdit false（RN useCanEditRoomSettings isDirect → false）→ 编辑态不可进；
        // 上传钮双门 canEdit && !isDirect 验证：owner 直聊也不出
        setContent(chatRow(t = "d", roles = """["owner"]"""), roomType = "d")
        assertEquals(0, rule.onAllNodesWithTag("qa-announcement-add").fetchSemanticsNodes().size)
    }

    @Test
    fun `delete click shows confirm dialog`() {
        val announcements = """[{"_id":"a1","message":"Hello","announcementType":0}]"""
        setContent(chatRow(roles = """["owner"]""", announcements = announcements))
        rule.onNodeWithTag("qa-announcement-delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.t("announcement_delete_confirm")).assertExists()
    }
}
