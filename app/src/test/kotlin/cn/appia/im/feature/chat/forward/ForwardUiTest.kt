package cn.appia.im.feature.chat.forward

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.api.ForwardSearchRow
import cn.appia.im.core.theme.AppiaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 转发 UI（Robolectric + Compose）：合并转发卡片（标题/前 2 条预览/页脚/坏 JSON 错误态）、
 * 选择页（最近会话勾选、MAX 10、DM/群载荷、搜索行勾选）、详情页（行 + 日期分隔 + 错误态）。
 * i18n 期望值经 context.t + interpolate 现算（Robolectric locale 随 JVM，不写死语言文案）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForwardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Application
    private val scheduler = TestCoroutineScheduler()
    private val searchScope = CoroutineScope(StandardTestDispatcher(scheduler))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        searchScope.cancel()
    }

    private fun chat(_id: String, t: String, uids: String? = null, name: String = _id) = ChatEntity(
        _id = _id, f = false, t = t, ts = 0.0, ls = 0.0, name = name, fname = "", rid = _id,
        open = true, alert = false, unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
        room_updated_at = 0.0, ro = false, archived = false, auto_translate_language = "en",
        team_id = "", uids = uids,
    )

    private val msgDataJson = """
        {"originRoom":{"rid":"room1","names":["\u5f20\u4e09","\u674e\u56db"]},
         "messages":[
           {"_id":"m1","msg":"hello","u":{"username":"u1","name":"\u5f20\u4e09"},"ts":"2024-06-10T09:05:00.000Z"},
           {"_id":"m2","msg":"world","u":{"username":"u2","name":"\u674e\u56db"},"ts":"2024-06-10T10:00:00.000Z"},
           {"_id":"m3","msg":"\u7b2c\u4e09\u6761\u4e0d\u8fdb\u9884\u89c8","u":{"username":"u1","name":"\u5f20\u4e09"},"ts":"2024-06-11T09:00:00.000Z"}
         ]}
    """.trimIndent()

    private fun twoNameTitle(): String =
        interpolate(context.t("message_forwardmergetitle"), mapOf("name0" to "\u5f20\u4e09", "name1" to "\u674e\u56db"))

    // ── ForwardMergeCard ──

    @Test
    fun `card renders title two previews and footer`() {
        val opened = mutableListOf<Pair<String, String>>()
        rule.setContent {
            AppiaTheme(isDark = false) { ForwardMergeCard(msgData = msgDataJson, onOpen = { a, b -> opened += a to b }) }
        }
        rule.onNodeWithText(twoNameTitle()).assertExists()
        rule.onNodeWithText("\u5f20\u4e09: hello").assertExists()
        rule.onNodeWithText("\u674e\u56db: world").assertExists()
        rule.onNodeWithText("\u7b2c\u4e09\u6761\u4e0d\u8fdb\u9884\u89c8").assertDoesNotExist() // 仅前 2 条
        rule.onNodeWithText(context.t("message_chathistory")).assertExists()

        rule.onNodeWithTag("qa-forward-msg").performClick()
        assertEquals(listOf(msgDataJson to twoNameTitle()), opened)
    }

    @Test
    fun `card with room name title and broken json error state`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                Column {
                    ForwardMergeCard(
                        msgData = """{"originRoom":{"rid":"r","name":"\u6280\u672f\u8ba8\u8bba"},"messages":[]}""",
                        onOpen = { _, _ -> },
                    )
                    ForwardMergeCard(msgData = "{broken", onOpen = { _, _ -> })
                }
            }
        }
        rule.onNodeWithText(
            interpolate(context.t("message_forwardmergetitleroom"), mapOf("roomName" to "\u6280\u672f\u8ba8\u8bba")),
        ).assertExists()
        rule.onNodeWithTag("qa-forward-msg-error").assertExists()
        rule.onNodeWithText(context.t("message_formaterror")).assertExists()
    }

    // ── ForwardSelectScreen ──

    private fun setContentWith(
        chats: List<ChatEntity>,
        searchRows: List<ForwardSearchRow>,
        onForward: suspend (List<String>, List<String>) -> Unit = { _, _ -> },
    ): ForwardSearcher {
        val searcher = ForwardSearcher({ searchRows }, searchScope)
        rule.setContent {
            AppiaTheme(isDark = false) {
                ForwardSelectScreen(
                    messageIds = listOf("m1"),
                    isMerged = true,
                    chats = chats,
                    currentUserId = "me",
                    searcher = searcher,
                    onForward = onForward,
                    onBack = {},
                )
            }
        }
        return searcher
    }

    @Test
    fun `select dm and group then confirm builds targets`() {
        val forwarded = mutableListOf<Pair<List<String>, List<String>>>()
        rule.setContent {
            AppiaTheme(isDark = false) {
                ForwardSelectScreen(
                    messageIds = listOf("m1"),
                    isMerged = true,
                    chats = listOf(chat("dm1", "d", """["me","peer1"]"""), chat("gp1", "p")),
                    currentUserId = "me",
                    searcher = ForwardSearcher({ emptyList() }, searchScope),
                    onForward = { users, rooms -> forwarded += users to rooms },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("qa-forward-recent-row-dm1").performClick()
        rule.onNodeWithTag("qa-forward-recent-row-gp1").performClick()
        rule.onNodeWithTag("qa-forward-confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf(listOf("peer1") to listOf("gp1")), forwarded)
    }

    @Test
    fun `max ten selections across both sets`() {
        val chats = (1..11).map { chat("gp$it", "p") }
        setContentWith(chats, emptyList())
        (1..11).forEach {
            val tag = "qa-forward-recent-row-gp$it"
            rule.onNodeWithTag("qa-forward-list").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
        }
        rule.onNodeWithText(
            interpolate(context.t("forward_selected_count"), mapOf("count" to "10", "max" to "10")),
        ).assertExists() // 第 11 个勾选被忽略
    }

    @Test
    fun `search rows toggle user and room selections`() {
        val forwarded = mutableListOf<Pair<List<String>, List<String>>>()
        setContentWith(
            chats = emptyList(),
            searchRows = listOf(
                ForwardSearchRow.User("u1", "zhangsan", "\u5f20\u4e09"),
                ForwardSearchRow.Room("rid1", "\u7fa41", "p"),
            ),
            onForward = { users, rooms -> forwarded += users to rooms },
        )
        rule.onNodeWithTag("qa-forward-search-input").performTextInput("\u5f20")
        scheduler.advanceTimeBy(300)
        scheduler.runCurrent()
        rule.waitForIdle()
        rule.onNodeWithTag("forward-search-row-user-zhangsan").performClick()
        rule.onNodeWithTag("forward-search-row-room-rid1").performClick()
        rule.onNodeWithTag("qa-forward-confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf(listOf("zhangsan") to listOf("rid1")), forwarded)
    }

    // ── ForwardDetailScreen ──

    @Test
    fun `detail renders rows and date separator per calendar day`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                ForwardDetailScreen(
                    msgDataJson = msgDataJson,
                    title = twoNameTitle(),
                    currentUserId = "me",
                    currentUsername = "me",
                    serverUrl = "",
                    token = null,
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("qa-forward-message-list").assertExists()
        rule.onNodeWithText("hello").assertExists()
        rule.onNodeWithText("world").assertExists()
        rule.onNodeWithText("\u7b2c\u4e09\u6761\u4e0d\u8fdb\u9884\u89c8").assertExists() // 详情页全量渲染
        // 6/10 两条一次分隔 + 6/11 一条一次分隔
        rule.onAllNodesWithTag("qa-message-date-separator").assertCountEquals(2)
    }

    @Test
    fun `detail with broken msgData shows error`() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                ForwardDetailScreen(
                    msgDataJson = "{broken",
                    title = "",
                    currentUserId = null,
                    currentUsername = null,
                    serverUrl = "",
                    token = null,
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("qa-forward-msg-error").assertExists()
    }
}
