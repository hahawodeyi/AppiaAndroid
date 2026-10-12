package cn.appia.im.feature.chatlist.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.LatestMailPreview
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.mail.buildMailChannelChatRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MailChannelRow 冒烟（Robolectric + Compose）：蓝底信封行渲染标题/预览/未读 pill、
 * 点击回调触发。纯函数规则在 MailChannelRowTest 覆盖，这里只查 UI 接线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MailChannelRowSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `renders title preview and unread pill and handles press`() {
        val preview = LatestMailPreview(
            subject = "Q4 report",
            fromName = "Bob",
            from = "zhangsan@x.com",
            receivedAt = "2026-10-12T08:00:00.000Z",
            unread = true,
        )
        val row = buildMailChannelChatRow(pinned = true, unreadCount = 120, preview = preview)
        var presses = 0

        rule.setContent {
            AppiaTheme(isDark = false) {
                MailChannelRow(chat = row, onPress = { presses++ })
            }
        }

        // 标题 mail_channel_title；预览 `{fromName}：{subject}`；>99 pill「99+」
        val title = context.t("mail_channel_title")
        rule.onNodeWithText(title).assertExists()
        rule.onNodeWithText("Bob：Q4 report").assertExists()
        rule.onNodeWithText("99+").assertExists()

        rule.onNodeWithText(title).performClick()
        rule.waitForIdle()
        assertEquals(1, presses)
    }
}
