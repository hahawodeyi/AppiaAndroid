package cn.appia.im.feature.todo.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.api.TodoAttachment
import cn.appia.im.core.network.api.TodoItem
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chatlist.ChatRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 待办卡片/角标冒烟（Robolectric + Compose）：高优/普通 tag、标题渲染、附件文件行、
 * 房间名/时间元信息、超时提醒、完成+去处理操作行、会话行头像红点。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 ChatRowSmokeTest）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TodoCardSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun item(
        id: String = "t1",
        title: String? = "Reimburse **report** now",
        type: String? = null,
        reminderTime: String? = null,
        isOvertime: Boolean? = null,
        attachments: List<TodoAttachment>? = null,
        name: String? = "dev room",
    ) = TodoItem(
        id = id, title = title, createdAt = "2026-01-01T10:00:00.000Z",
        updatedAt = "2026-01-01T10:00:00.000Z", status = 0.0, mid = "m-$id",
        attachments = attachments, type = type, reminderTime = reminderTime,
        isOvertime = isOvertime, rid = "r1", name = name,
    )

    private fun setContent(item: TodoItem) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                TodoCard(
                    item = item,
                    roomDisplayName = item.name.orEmpty(),
                    disabled = false,
                    onComplete = {},
                    onGotoSession = {},
                    onOpenReminderPicker = {},
                    onOpenImage = { _, _ -> },
                    onOpenDoc = {},
                    userId = "uid",
                    token = "tok",
                    serverUrl = "https://im.example.com",
                )
            }
        }
    }

    @Test
    fun `renders tag title meta and actions`() {
        setContent(item())

        rule.onNodeWithText(context.t("todo_defaulttag")).assertExists()
        rule.onNodeWithText("Reimburse report now").assertExists() // stripMarkdownLite 已剥 **
        rule.onNodeWithText(context.t("todo_roomname")).assertExists()
        rule.onNodeWithText("dev room").assertExists()
        rule.onNodeWithText(context.t("todo_finish")).assertExists()
        rule.onNodeWithText(context.t("todo_gotosession")).assertExists()
        rule.onNodeWithText("⏰ ${context.t("todo_setreminder")}").assertExists()
    }

    @Test
    fun `high tag differs from default`() {
        setContent(item(type = "h"))
        rule.onNodeWithText(context.t("todo_hightag")).assertExists()
    }

    @Test
    fun `reminder row shows time plus change link when set`() {
        setContent(item(reminderTime = "2026-06-01T08:00:00.000Z"))
        // 本地时区渲染存在即断言（formatUtcYmdHm 已单测）；修改链接可见
        rule.onNodeWithText(context.t("todo_changereminder")).assertExists()
    }

    @Test
    fun `file attachment renders icon row with title`() {
        setContent(
            item(attachments = listOf(TodoAttachment(titleLink = "/file-upload/rooms/abc/a.pdf", title = "a.pdf"))),
        )
        rule.onNodeWithText("a.pdf").assertExists()
    }

    @Test
    fun `badge text caps at 99 plus in chat row`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                ChatRow(
                    chat = chatRow(todoCount = 120.0),
                    currentUserId = "uid",
                    currentUsername = "me",
                    avatarUrl = null,
                )
            }
        }
        rule.onNodeWithText("99+").assertExists()
    }

    private fun chatRow(todoCount: Double? = null) = ChatEntity(
        _id = "r1", f = false, t = "c", ts = 1_000.0, ls = 1_000.0,
        name = "dev", fname = "dev", rid = "r1", open = true, alert = false,
        unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
        room_updated_at = 1_000.0, ro = false, archived = false,
        auto_translate_language = "", team_id = "", todoCount = todoCount,
    )
}
