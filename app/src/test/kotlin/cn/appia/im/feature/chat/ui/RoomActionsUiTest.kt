package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chat.RoomMessagesUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11 长按菜单/回复引用/多选条/批量撤回/重新编辑 UI 测试（Robolectric + Compose，装配缝全部 fake）：
 * 长按出菜单（判定顺序与文案）、只读房拦截、引用态前缀 + 发送拼接、多选条（计数/批量撤回/取消）、
 * rollback 重新编辑回填输入框（作新消息发送）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomActionsUiTest {

    @get:org.junit.Rule
    val rule = createComposeRule()

    private val sentTexts = mutableListOf<String>()
    private val recalled = mutableListOf<String>()
    private val batchRecalled = mutableListOf<List<String>>()
    private var readOnly = false
    private val editor = cn.appia.im.feature.chat.editor.ChatInputBarController(
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    )

    private fun ownMsg(
        _id: String,
        msg: String? = "hi",
        t: String? = null,
        ts: Double = 1_757_900_000_000.0,
        originalContent: String? = null,
        u: String = """{"_id":"me","username":"me","name":"Me"}""",
    ) = MessageEntity(
        _id = _id, rid = "r1", ts = ts, u = u, alias = "", parse_urls = "[]",
        _updated_at = ts, msg = msg, t = t, original_content = originalContent,
    )

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Application>()
    }

    private fun setContent(messages: List<MessageEntity>) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomScreen(
                    rid = "r1",
                    title = "dev",
                    state = RoomMessagesUiState(rid = "r1", messages = messages),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    draftController = null,
                    editorController = editor,
                    onSend = { msg, _ -> sentTexts += msg },
                    onResend = {},
                    onBack = {},
                    onLoadEarlier = {},
                    isRoomReadOnly = readOnly,
                    onRecall = { m -> recalled += m._id },
                    onBatchRecall = { ids -> batchRecalled += ids },
                )
            }
        }
        rule.waitForIdle()
    }

    /** 第 i 行正文节点（列表 ts 倒序：index 0 = 最新）。 */
    private fun body(index: Int) = rule.onAllNodesWithTag("qa-room-message-body")[index]

    @Test
    fun `long press own message shows menu then reply prefix is sent`() {
        setContent(listOf(ownMsg("m1", msg = "hi")))
        rule.onNodeWithTag("qa-room-message-body").performTouchInput { longClick() }
        rule.waitForIdle()

        // 菜单全集与顺序（RN getOptions :129-245）：回复/编辑/复制/转发/多选/撤回；无重发（SENT 态）
        val tags = listOf("reply", "edit", "copy", "forward", "multi-select", "recall")
        val titles = listOf("Reply", "Edit", "Copy", "Forward", "Multi Select", "Recall")
        tags.forEachIndexed { i, tag ->
            rule.onNodeWithTag("qa-action-$tag").assertExists()
            rule.onNodeWithText(titles[i]).assertExists()
        }
        rule.onAllNodesWithTag("qa-action-resend").assertCountEquals(0)

        // 点引用 → 回复预览 + 发送拼接 permalink（roomType 缺省 group 路径）+ 清回复态
        rule.onNodeWithTag("qa-action-reply").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qa-reply-preview").assertExists()
        editor.simulateContent(null, "reply!")
        rule.waitForIdle()
        println("DEBUG plain=|" + editor.plainText + "|")
        rule.onNodeWithTag("qa-room-send").performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { sentTexts.isNotEmpty() } // 发送在 recomposer 协程域，异步落
        rule.waitForIdle()
        assertEquals(listOf("[ ](https://s1/group/r1?msg=m1) reply!"), sentTexts)
        rule.onNodeWithTag("qa-reply-preview").assertDoesNotExist()
    }

    @Test
    fun `long press readonly room shows no menu`() {
        readOnly = true
        setContent(listOf(ownMsg("m1")))
        rule.onNodeWithTag("qa-room-message-body").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag("qa-action-reply").assertDoesNotExist()
    }

    @Test
    fun `multi select flow - enter from menu toggle rows and batch recall via confirm`() {
        setContent(
            listOf(
                ownMsg("m1", ts = 1_757_900_000_000.0),
                ownMsg("m2", ts = 1_757_900_001_000.0), // 更新 → 列表 index 0
            ),
        )
        rule.onAllNodesWithTag("qa-room-message-body")[0].performTouchInput { longClick() }
        rule.onNodeWithTag("qa-action-multi-select").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("qa-multiselect-count").assertTextEquals("1 selected")
        rule.onNodeWithTag("qa-room-editor").assertDoesNotExist() // 多选态替换输入框（RN RoomFooter）
        rule.onNodeWithTag("qa-message-selected").assertExists() // 已选行勾选列

        rule.onAllNodesWithTag("qa-room-message-body")[1].performClick() // 点未选行加入
        rule.waitForIdle()
        rule.onNodeWithTag("qa-multiselect-count").assertTextEquals("2 selected")
        rule.onAllNodesWithTag("qa-message-selected").assertCountEquals(2)

        // 批量撤回：确认弹窗（buildBatchRecallTip）→ 确定 → POST ids + 退出多选
        rule.onNodeWithTag("qa-multiselect-batch-recall").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("qa-batch-recall-confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, batchRecalled.size)
        assertEquals(setOf("m1", "m2"), batchRecalled[0].toSet())
        rule.onNodeWithTag("qa-room-editor").assertExists() // 已退出多选
    }

    @Test
    fun `own rollback with snapshot shows reedit and refills input as new message`() {
        setContent(
            listOf(
                ownMsg("rm1", t = "rollback-message", originalContent = """{"msg":"restore me"}"""),
            ),
        )
        rule.onNodeWithTag("qa-system-message-reedit").performClick()
        rule.waitForIdle()
        // 回填编辑器（buildEditContent 无 md → HTML；plainText 本地快照立即生效）
        rule.onNodeWithTag("qa-room-editor").assertExists()
        org.junit.Assert.assertEquals("restore me", editor.plainText)
        rule.onNodeWithTag("qa-room-send").performClick()
        rule.waitUntil(5_000) { sentTexts.isNotEmpty() } // 发送在 recomposer 协程域，异步落
        rule.waitForIdle()
        assertEquals(listOf("restore me"), sentTexts) // 作为新消息发送（不进编辑模式）
    }
}
