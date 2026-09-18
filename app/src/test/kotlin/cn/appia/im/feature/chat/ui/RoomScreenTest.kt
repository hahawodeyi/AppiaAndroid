package cn.appia.im.feature.chat.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.feature.chat.DraftController
import cn.appia.im.feature.chat.DraftRepository
import cn.appia.im.feature.chat.editor.ChatInputBarController
import cn.appia.im.feature.chat.RoomMessagesUiState
import cn.appia.im.feature.chatlist.chatRow
import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RoomScreen UI 测试（Robolectric + Compose + 内存 Room）：
 * 发送链（输入→点发→QUEUED 行出现→fake orchestrator 置 SENT 徽标变化）、草稿 debounce
 * 虚拟时钟 + IME 组合态守卫、系统消息映射渲染、日期分隔、空态、头部标题解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomScreenTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    @get:org.junit.Rule
    val rule = createComposeRule()

    private var messages by mutableStateOf(listOf<MessageEntity>())
    private val sentTexts = mutableListOf<String>()
    private val draftScheduler = TestCoroutineScheduler()
    private val draftScope = CoroutineScope(StandardTestDispatcher(draftScheduler))
    private val draftController = DraftController(DraftRepository(db), draftScope)
    private val editorScope = CoroutineScope(StandardTestDispatcher(draftScheduler))
    private val editor = ChatInputBarController(editorScope)

    @Before
    fun setUp() {
        runBlocking { db.chatDao().insert(chatRow(_id = "r1", name = "dev")) }
    }

    @After
    fun tearDown() {
        draftScope.cancel()
        db.close()
    }

    private fun messageRow(
        _id: String,
        msg: String? = "hi",
        t: String? = null,
        ts: Double = 1_757_900_000_000.0,
        u: String = """{"_id":"u2","username":"bob","name":"Bob"}""",
        status: Double? = null,
        mentions: String? = null,
        md: String? = null,
        alias: String = "",
    ) = MessageEntity(
        _id = _id, rid = "r1", ts = ts, u = u, alias = alias, parse_urls = "[]",
        _updated_at = ts, msg = msg, t = t, status = status, mentions = mentions, md = md,
    )

    /** fake orchestrator：enqueue 落 QUEUED 行（同 SendOrchestrator.enqueueTextMessage 面向 UI 的效果）。 */
    private fun setContent(initial: List<MessageEntity> = emptyList(), initialLoading: Boolean = false) {
        messages = initial
        rule.setContent {
            AppiaTheme(isDark = false) {
                RoomScreen(
                    rid = "r1",
                    title = "dev",
                    state = RoomMessagesUiState(rid = "r1", messages = messages, isInitialLoading = initialLoading),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "https://s1",
                    token = "tok",
                    draftController = draftController,
                    editorController = editor,
                    onSend = { msg, _ ->
                        println("DEBUG onSend called: $msg")
                        sentTexts += msg
                        val id = "local-${sentTexts.size}"
                        runBlocking { db.messageDao().insert(messageRow(id, msg = msg, status = 1.0, u = """{"_id":"me","username":"me"}""")) }
                        messages = messages + messageRow(id, msg = msg, status = 1.0, u = """{"_id":"me","username":"me"}""")
                    },
                    onResend = {},
                    onBack = {},
                    onLoadEarlier = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun chat() = runBlocking { db.chatDao().getById("r1") }

    companion object {
        private val TEST_DOC_JSON = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"hello"}]}]}""",
        ) as kotlinx.serialization.json.JsonObject
    }

    /** 虚拟时钟推进 + 泵调度器直到草稿列满足条件（Room suspend 恢复异步再入队列）。 */
    private fun awaitDraft(desc: String, cond: (cn.appia.im.core.database.entity.ChatEntity) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < deadline) {
            draftScheduler.runCurrent()
            val row = chat()
            if (row != null && cond(row)) return
            Thread.sleep(10)
        }
        throw AssertionError("timeout: $desc")
    }

    @Test
    fun `send chain - editor content becomes QUEUED row then SENT badge clears`() {
        setContent()
        // T12：编辑器内容经 controller 管线（WebView JS 在 Robolectric 不执行，测试缝直注）
        editor.simulateContent(TEST_DOC_JSON, "hello")
        rule.waitForIdle()
        rule.onNodeWithTag("qa-room-send").performClick()
        rule.waitUntil(5_000) { sentTexts.isNotEmpty() } // 发送在 recomposer 协程域，异步落
        rule.waitForIdle()

        assertEquals(listOf("hello"), sentTexts)
        rule.onNodeWithText("hello").assertExists() // QUEUED 行已上屏
        rule.onNodeWithTag("qa-message-status-loading").assertExists()

        // fake orchestrator 完成：status → SENT，徽标消失
        messages = messages.map { it.copy(status = 0.0) }
        rule.waitForIdle()
        rule.onAllNodesWithTag("qa-message-status-loading").assertCountEquals(0)

        // 发送成功清草稿（四列）——发送路径 clearAfterSend 后 debounce 也已取消
        awaitDraft("clear four columns after send") { row ->
            row.draft_message == "" && row.draft_message_plain == "" &&
                row.draft_reply_msg_id == "" && row.draft_attachments == ""
        }
    }

    @Test
    fun `draft debounce - editor content settles saves after 1s virtual`() {
        setContent()

        // 编辑器内容落定（content-update → getJSON 管线等价）→ 草稿 debounce 1s
        editor.simulateContent(TEST_DOC_JSON, "pin")
        draftScheduler.advanceTimeBy(500)
        draftScheduler.runCurrent()
        assertNull(chat()?.draft_message_plain)

        draftScheduler.advanceTimeBy(500)
        awaitDraft("debounced write") { it.draft_message_plain == "pin" }
        // T12：draft_message 存 TipTap JSON（plain 列存纯文本）
        assertEquals(TEST_DOC_JSON.toString(), chat()?.draft_message)
    }

    @Test
    fun `system message renders mapped template`() {
        setContent(
            listOf(messageRow("m1", t = "r", msg = "new-name")),
        )
        // en strings：{{userBy}} changed room name to: {{name}}
        val expected = "Bob changed room name to: new-name"
        rule.onNodeWithText(expected).assertExists()
        rule.onAllNodesWithTag("qa-system-message").assertCountEquals(1)
    }

    /** 纯结构断言（RN renderItem 派生）：同天不插、跨天插、末条（无更旧）必插。 */
    @Test
    fun `date separator structure derived from neighbor days`() {
        val day = 86_400_000.0
        val hour = 3_600_000.0
        val rows = listOf(
            messageRow("a", ts = 10 * day + hour),
            messageRow("b", ts = 10 * day + hour - 1_000), // 与 a 同天：不插
            messageRow("c", ts = 8 * day + hour), // 与 b 跨天：插；末条无更旧：必插
        )
        assertEquals(
            listOf("Message", "Message", "DateSeparator", "Message", "DateSeparator"),
            buildRoomListItems(rows).map { it::class.simpleName },
        )
    }

    /** 总纲 §4.3-3：load_chunk 行渲染 1px，不参与分隔推导（RN chunk 早退同款）。 */
    @Test
    fun `load chunk rows render bare and derive no separator`() {
        val day = 86_400_000.0
        val hour = 3_600_000.0
        val rows = listOf(
            messageRow("a", ts = 10 * day + hour),
            messageRow("chunk", t = "load-more-before", ts = 10 * day + hour - 1_000), // chunk：无分隔
            messageRow("c", ts = 8 * day + hour), // 末条：必插
        )
        assertEquals(
            listOf("Message", "Message", "Message", "DateSeparator"),
            buildRoomListItems(rows).map { it::class.simpleName },
        )
    }

    @Test
    fun `chunk as oldest still derives neighbor separator from raw position`() {
        // 分隔的「更旧一条」取原始相邻位（RN derivedMessages[index+1] 不过滤 chunk）
        val day = 86_400_000.0
        val rows = listOf(
            messageRow("a", ts = 10 * day),
            messageRow("chunk", t = "load-more-after", ts = 8 * day), // chunk 自身无分隔（虽为末条）
        )
        assertEquals(
            listOf("Message", "DateSeparator", "Message"),
            buildRoomListItems(rows).map { it::class.simpleName },
        )
    }

    @Test
    fun `same-day pair renders only the oldest-message separator`() {
        val day = 86_400_000.0
        val hour = 3_600_000.0
        setContent(listOf(messageRow("a", ts = 10 * day + hour), messageRow("b", ts = 10 * day + hour - 1_000)))
        rule.onAllNodesWithTag("qa-message-date-separator").assertCountEquals(1)
    }

    @Test
    fun `cross-day pair renders neighbor and oldest separators`() {
        val day = 86_400_000.0
        val hour = 3_600_000.0
        setContent(listOf(messageRow("a", ts = 10 * day + hour), messageRow("c", ts = 8 * day + hour)))
        rule.onAllNodesWithTag("qa-message-date-separator").assertCountEquals(2)
    }

    @Test
    fun `empty state shows no-messages label after loading settles`() {
        setContent(initialLoading = false)
        rule.onNodeWithTag("qa-room-message-list-empty").assertExists()
        rule.onNodeWithText(context.t("room_no_messages")).assertExists()
    }

    @Test
    fun `header title prefers chat row then route fallback`() {
        val chat = chatRow(_id = "r1", name = "fallback-name", fname = "", dname = "")
        assertEquals("fallback-name", resolveRoomHeaderTitle("route-title", chat))
        assertEquals("route-title", resolveRoomHeaderTitle("route-title", null))
        val chatF = chatRow(_id = "r1", name = "n", fname = "Dev Room")
        assertEquals("Dev Room", resolveRoomHeaderTitle("route-title", chatF))
    }
}
