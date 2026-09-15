package cn.appia.im.feature.chatlist

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ChatRow 冒烟（Robolectric + Compose）：草稿前缀、提及前缀、特殊消息模板渲染
 * （t() 查 key + `{{x}}` 替换）、静音行、未读徽标 `99+`。纯函数规则在
 * LastMessagePreviewTest/RoomListTimeTest 已覆盖，这里只查 UI 接线。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 LoginScreenTest）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRowSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setContent(chat: ChatEntity) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                ChatRow(chat = chat, currentUserId = "me", serverUrl = "https://s1")
            }
        }
    }

    @Test
    fun `renders title date and preview text`() {
        setContent(
            chatRow(
                _id = "r1",
                name = "dev-group",
                unread = 3.0,
                lm = 1_000_000.0,
                last_message = """{"msg":"hello","u":{"name":"Bob","username":"bob"}}""",
            ),
        )
        rule.onNodeWithText("dev-group").assertExists()
        rule.onNodeWithText("Bob：hello").assertExists()
    }

    @Test
    fun `draft prefix wins over mention prefix`() {
        setContent(
            chatRow(
                _id = "r1",
                draft_message_plain = "drafting",
                user_mentions = 2.0,
                last_message = """{"msg":"hi","u":{"username":"bob"}}""",
            ),
        )
        rule.onNodeWithText("[${context.t("roomItem_draft")}]").assertExists()
        rule.onNodeWithText("drafting").assertExists()
    }

    @Test
    fun `mention prefix renders when mentioned`() {
        setContent(
            chatRow(
                _id = "r1",
                user_mentions = 1.0,
                last_message = """{"msg":"hi","u":{"username":"bob"}}""",
            ),
        )
        rule.onNodeWithText("[${context.t("roomItem_someoneCalled")}] ").assertExists()
    }

    @Test
    fun `special message template resolves kind placeholder`() {
        setContent(
            chatRow(
                _id = "r1",
                last_message = """{"msg":"","u":{"name":"Bob","username":"bob"},"attachments":[{"image_url":"x"}]}""",
            ),
        )
        val expected = "Bob：" +
            context.t("roomItem_sentAttachment").replace("{{kind}}", context.t("roomItem_attachmentImage"))
        rule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `over 99 unread shows 99+ badge`() {
        setContent(chatRow(_id = "r1", unread = 120.0, last_message = """{"msg":"hi","u":{"username":"me"}}"""))
        rule.onNodeWithText("99+").assertExists()
    }

    @Test
    fun `muted chat hides badge and keeps noMessage`() {
        setContent(
            chatRow(
                _id = "r1",
                unread = 5.0,
                hide_unread_status = true,
                last_message = """{"pinned":true,"u":{"username":"bob"}}""",
            ),
        )
        rule.onNodeWithText("99+").assertDoesNotExist()
        rule.onNodeWithText(context.t("roomItem_nomessage")).assertExists()
    }
}
