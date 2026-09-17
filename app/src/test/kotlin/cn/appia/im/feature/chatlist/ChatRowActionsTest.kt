package cn.appia.im.feature.chatlist

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.database.entity.SubscriptionEntity
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.core.i18n.t
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 列表操作端到端（对照 subscriptionSwipeActions.ts + brief 绑定裁定 1/2/3/5）：
 * - appearsRead 纯函数（subscriptionAppearsRead.ts 全分支）
 * - markRoomRead：先 REST 后双表写；服务端失败不写本地
 * - setRoomFavorite：服务端成功才写 chats.f
 * - markRoomUnread：REST `{roomId}`，本地零改动等 stream 回推
 * - SwipeableChatRow：左钮（未读/置顶）右钮（已读）按已读态显示 + 延迟挂载 + 回调
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 ChatRowSmokeTest）
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRowActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private lateinit var manager: DatabaseManager
    private lateinit var sdk: RocketSdk
    private lateinit var actions: ChatRowActions

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        server.start()
        manager = DatabaseManager(context)
        manager.switchDatabase("https://chat-a.example.com")
        sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(server.url("/").toString(), "tok", "uid")
        actions = ChatRowActions(sdk, manager, "https://chat-a.example.com")
    }

    @After
    fun tearDown() {
        manager.resetAll()
        runCatching { server.shutdown() }
    }

    private fun dao() = manager.active.chatDao()

    /** 未读态 chats 行（open/alert/unread 有值，便于断言被清零）。 */
    private fun unreadChat(id: String, unread: Double = 5.0) = ChatEntity(
        _id = id,
        f = false,
        t = "c",
        ts = 0.0,
        ls = 0.0,
        name = "n",
        fname = "",
        rid = id,
        open = true,
        alert = true,
        unread = unread,
        user_mentions = 2.0,
        group_mentions = 1.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        team_id = "",
    )

    private fun subRow(id: String) = SubscriptionEntity(
        _id = id,
        f = false,
        t = "c",
        ts = 0.0,
        ls = 0.0,
        name = "n",
        fname = "",
        rid = id,
        open = true,
        alert = true,
        unread = 7.0,
        user_mentions = 3.0,
        group_mentions = 2.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        team_id = "",
    )

    private fun enqueueOk() {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
    }

    // ---- appearsRead 纯函数（subscriptionAppearsRead.ts:6-12 全分支）----

    @Test
    fun `appearsRead true when open with no unread and no alert`() {
        assertTrue(subscriptionAppearsRead(unreadChat("r1").copy(unread = 0.0, alert = false)))
    }

    @Test
    fun `appearsRead false when unread positive`() {
        assertFalse(subscriptionAppearsRead(unreadChat("r1").copy(unread = 1.0, alert = false)))
    }

    @Test
    fun `appearsRead false when alert true even with zero unread`() {
        assertFalse(subscriptionAppearsRead(unreadChat("r1").copy(unread = 0.0, alert = true)))
    }

    @Test
    fun `appearsRead true when room closed`() {
        assertTrue(subscriptionAppearsRead(unreadChat("r1").copy(open = false)))
    }

    @Test
    fun `appearsRead true when archived regardless of unread`() {
        assertTrue(subscriptionAppearsRead(unreadChat("r1").copy(archived = true)))
    }

    // ---- markRoomRead：先 REST `{rid}` 后双表写 ----

    @Test
    fun `markRoomRead posts then writes read state to both tables`() = runBlocking {
        dao().insert(unreadChat("r1"))
        manager.active.subscriptionDao().insert(subRow("r1"))
        enqueueOk()

        actions.markRoomRead("r1", now = 1_000L)

        val req = server.takeRequest()
        assertEquals("/api/v1/subscriptions.read", req.path)
        assertEquals("""{"rid":"r1"}""", req.body.readUtf8())
        val chat = dao().getById("r1")!!
        assertEquals(true, chat.open)
        assertEquals(false, chat.alert)
        assertEquals(0.0, chat.unread, 0.0)
        assertEquals(0.0, chat.user_mentions, 0.0)
        assertEquals(0.0, chat.group_mentions, 0.0)
        assertEquals(1_000.0, chat.ls, 0.0)
        val sub = manager.active.subscriptionDao().getById("r1")!!
        assertEquals(0.0, sub.unread, 0.0)
        assertEquals(1_000.0, sub.ls, 0.0)
        assertEquals(false, sub.alert)
    }

    @Test
    fun `markRoomRead updateLastOpen writes last_open to both tables`() = runBlocking {
        // T10 进房即读路径：同名透传 ReadStateWriter（RN readMessages options.updateLastOpen）
        dao().insert(unreadChat("r1").copy(last_open = 555.0))
        manager.active.subscriptionDao().insert(subRow("r1").copy(last_open = 666.0))
        enqueueOk()

        actions.markRoomRead("r1", now = 1_000L, updateLastOpen = true)

        assertEquals(1_000.0, dao().getById("r1")!!.last_open!!, 0.0)
        assertEquals(1_000.0, manager.active.subscriptionDao().getById("r1")!!.last_open!!, 0.0)
    }

    @Test
    fun `markRoomRead does not write locally when server fails`() = runBlocking {
        dao().insert(unreadChat("r1"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"nope"}"""))

        assertThrows(Exception::class.java) { runBlocking { actions.markRoomRead("r1", now = 1_000L) } }

        assertEquals(5.0, dao().getById("r1")!!.unread, 0.0)
        assertEquals(2.0, dao().getById("r1")!!.user_mentions, 0.0)
    }

    // ---- setRoomFavorite：服务端成功才写 chats.f ----

    @Test
    fun `setRoomFavorite posts then flips chats f`() = runBlocking {
        dao().insert(unreadChat("r1"))
        enqueueOk()

        actions.setRoomFavorite("r1", true)

        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.favorite", req.path)
        assertEquals("""{"roomId":"r1","favorite":true}""", req.body.readUtf8())
        assertTrue(dao().getById("r1")!!.f)
    }

    @Test
    fun `setRoomFavorite keeps f when server fails`() = runBlocking {
        dao().insert(unreadChat("r1"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertThrows(Exception::class.java) { runBlocking { actions.setRoomFavorite("r1", true) } }

        assertFalse(dao().getById("r1")!!.f)
    }

    // ---- markRoomUnread：REST `{roomId}`，本地零改动（等 stream 回推）----

    @Test
    fun `markRoomUnread posts roomId and changes nothing locally`() = runBlocking {
        dao().insert(unreadChat("r1"))
        manager.active.subscriptionDao().insert(subRow("r1"))
        val before = dao().getById("r1")!!
        enqueueOk()

        actions.markRoomUnread("r1")

        val req = server.takeRequest()
        assertEquals("/api/v1/subscriptions.unread", req.path)
        assertEquals("""{"roomId":"r1"}""", req.body.readUtf8())
        assertEquals(before, dao().getById("r1"))
        assertEquals(
            manager.active.subscriptionDao().getById("r1"),
            subRow("r1"),
        )
    }

    @Test
    fun `local write is skipped when active db does not match`() = runBlocking {
        val dbA = manager.active
        dbA.chatDao().insert(unreadChat("r1"))
        manager.switchDatabase("https://chat-b.example.com") // active 指向他库
        enqueueOk()

        actions.markRoomRead("r1", now = 1_000L) // REST 成功，但守卫不过

        assertEquals(5.0, dbA.chatDao().getById("r1")!!.unread, 0.0)
    }

    // ---- SwipeableChatRow 手势冒烟 ----

    private fun setSwipeContent(chat: ChatEntity, marks: MutableMap<String, Int>) {
        composeRule.setContent {
            AppiaTheme(isDark = false) {
                SwipeableChatRow(
                    chat = chat,
                    currentUserId = "uid-self",
                    currentUsername = "me",
                    avatarUrl = null,
                    onMarkRead = { marks["read"] = (marks["read"] ?: 0) + 1 },
                    onMarkUnread = { marks["unread"] = (marks["unread"] ?: 0) + 1 },
                    onToggleFavorite = { marks["fav"] = (marks["fav"] ?: 0) + 1 },
                )
            }
        }
    }

    @Test
    fun `read row reveals unread and favorite buttons on right swipe`() {
        val marks = mutableMapOf<String, Int>()
        setSwipeContent(unreadChat("r1").copy(unread = 0.0, alert = false), marks)

        // 延迟挂载：首次拖拽前按钮内容不组装（RN swipeActionsReady :55-56）
        composeRule.onNodeWithText(context.t("roomitem_swipefavorite")).assertDoesNotExist()

        composeRule.onNodeWithTag("chat-row-swipe").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.t("roomitem_swipemarkunread")).assertExists()
        composeRule.onNodeWithText(context.t("roomitem_swipefavorite")).assertExists()
        // 总纲 §4.3-6：滑壳钮 testTag（Maestro 同口径 resource-id）可达
        composeRule.onNodeWithTag("qa-swipe-mark-unread").assertExists()
        composeRule.onNodeWithTag("qa-swipe-favorite").assertExists()
    }

    @Test
    fun `tapping unread button fires onMarkUnread`() {
        val marks = mutableMapOf<String, Int>()
        setSwipeContent(unreadChat("r1").copy(unread = 0.0, alert = false), marks)
        composeRule.onNodeWithTag("chat-row-swipe").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.t("roomitem_swipemarkunread")).performClick()
        composeRule.waitForIdle()
        assertEquals(1, marks["unread"] ?: 0)
    }

    @Test
    fun `tapping favorite button fires onToggleFavorite`() {
        val marks = mutableMapOf<String, Int>()
        setSwipeContent(unreadChat("r1").copy(unread = 0.0, alert = false), marks)
        composeRule.onNodeWithTag("chat-row-swipe").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.t("roomitem_swipefavorite")).performClick()
        composeRule.waitForIdle()
        assertEquals(1, marks["fav"] ?: 0)
    }

    @Test
    fun `unread row reveals mark read on left swipe and favorite only on right swipe`() {
        val marks = mutableMapOf<String, Int>()
        setSwipeContent(unreadChat("r1"), marks)

        composeRule.onNodeWithTag("chat-row-swipe").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.t("roomitem_swipemarkread")).performClick()
        composeRule.waitForIdle()
        assertEquals(1, marks["read"])

        // 右滑只露置顶，无未读钮（未读态的左钮组只有置顶）
        composeRule.onNodeWithTag("chat-row-swipe").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.t("roomitem_swipefavorite")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.t("roomitem_swipemarkunread")).assertDoesNotExist()
        assertEquals(1, marks["fav"])
    }
}
