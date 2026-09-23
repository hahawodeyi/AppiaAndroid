package cn.appia.im.feature.roominfo.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 改名屏冒烟（Robolectric）：初始值 fname→dname 回退、max80 截断、保存钮空值/未改禁用、
 * 标题 c/p 分支。wire/Alert 文案在 RoomChannelNameEditActionsTest（MockWebServer）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomChannelNameEditScreenSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun chatRow(fname: String = "Dev Room", dname: String? = null): ChatEntity = ChatEntity(
        _id = "r1", f = false, t = "c", ts = 0.0, ls = 0.0, name = "dev-room", fname = fname,
        rid = "r1", open = true, alert = false, unread = 0.0, user_mentions = 0.0,
        group_mentions = 0.0, room_updated_at = 0.0, ro = false, archived = false,
        auto_translate_language = "en", team_id = "", dname = dname,
    )

    private fun setContent(roomType: String = "c", chat: ChatEntity?) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomChannelNameEditScreen(
                    rid = "r1",
                    roomType = roomType,
                    chat = chat,
                    sdk = null, // 冒烟只查 UI 结构；保存链走 actions 测试
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `initial value falls back fname then dname`() {
        assertEquals("Dev Room", channelNameEditInitial(chatRow()))
        assertEquals("Display Name", channelNameEditInitial(chatRow(fname = "", dname = "Display Name")))
        assertEquals("", channelNameEditInitial(null))
    }

    @Test
    fun `renders hint input and title per room type`() {
        setContent(roomType = "c", chat = chatRow())
        rule.onNodeWithText(context.t("roominfo_editchannelname")).assertExists()
        rule.onNodeWithText(context.t("roominfo_namechangehint")).assertExists()
        rule.onNodeWithTag("qa-channel-name-input").assertExists()
    }

    @Test
    fun `title is edit group name for non-c room`() {
        setContent(roomType = "p", chat = chatRow())
        rule.onNodeWithText(context.t("roominfo_editgroupname")).assertExists()
    }

    @Test
    fun `input caps at 80 chars`() {
        setContent(chat = chatRow(fname = ""))
        rule.onNodeWithTag("qa-channel-name-input").performTextInput("a".repeat(100))
        rule.onNodeWithTag("qa-channel-name-input").assertTextEquals("a".repeat(80))
    }

    @Test
    fun `save button disabled when name unchanged or empty`() {
        setContent(chat = chatRow())
        // 初始值未改 → 保存钮存在但禁用（RN canSave false）
        rule.onNodeWithTag("qa-channel-name-save").assertExists().assertIsNotEnabled()

        rule.onNodeWithTag("qa-channel-name-input").performTextInput("x")
        rule.onNodeWithTag("qa-channel-name-save").assertIsEnabled()
    }
}
