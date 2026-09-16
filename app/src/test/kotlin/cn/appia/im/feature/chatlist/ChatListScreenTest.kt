package cn.appia.im.feature.chatlist

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.RouteDeps
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.push.PushTokenRegistrar
import cn.appia.im.core.realtime.NetworkMonitor
import cn.appia.im.core.realtime.RealtimeTransportPhase
import cn.appia.im.core.realtime.RoomStreamManager
import cn.appia.im.domain.session.AuthRepository
import cn.appia.im.domain.session.OrgSwitchCoordinator
import cn.appia.im.domain.session.RealtimeSessionManager
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.chatlist.ui.ChatListScreen
import cn.appia.im.feature.org.OrgListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ChatListScreen 组装冒烟（T11）：分段/行上屏、行点击串联参数、横幅可见性二态、
 * 顶栏菜单（我的企业/登出迁入）。gateway 走真 orchestrator + bootstrap 缝（不触 DDP）。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 MainNavigationFlowTest）
@RunWith(RobolectricTestRunner::class)
// plain Application：AppiaApplication.onCreate 会初始化 MMKV，JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class ChatListScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: Fixture

    @Before
    fun setUp() {
        fixture = Fixture(context)
    }

    @After
    fun tearDown() {
        // 先取消注入 scope（sections 流 WhileSubscribed(5s) 宽限期会在关库后重查），
        // 再等在途查询落定（cancel 不等待 Room 执行器），最后清库
        fixture.scopes.forEach { it.cancel() }
        runCatching { rule.waitForIdle() }
        Thread.sleep(100)
        fixture.dbManager.resetAll()
    }

    private fun tagExists(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun setContent(phase: RealtimeTransportPhase, online: Boolean?) {
        rule.setContent {
            ChatListScreen(
                gateway = fixture.orchestrator,
                deps = fixture.deps,
                phase = phase,
                networkOnline = online,
                onOpenRoom = { rid, title, roomType -> fixture.opened.add(Triple(rid, title, roomType)) },
                onLogout = { fixture.logoutCalls.incrementAndGet() },
            )
        }
    }

    @Test
    fun `sections and rows render and row tap carries rid title roomType`() {
        fixture.seedChats()
        setContent(RealtimeTransportPhase.CONNECTED, online = true)

        // 三分段中两段上屏：待办段头 + 频道段头；ChatRow 行标题
        rule.waitUntil(5_000) { tagExists("qa-room-list-section-todo") }
        rule.onNodeWithText(context.t("roomList_sectionTodo")).assertExists()
        rule.onNodeWithText(context.t("roomList_sectionChannels")).assertExists()
        rule.onNodeWithText("General").assertExists()

        // 行点击 → RoomRoute 参数（rid + roomTitleFromChat 兜底标题 + t）
        rule.onNodeWithText("General").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple("rid-general", "General", "c")), fixture.opened.toList())
    }

    @Test
    fun `offline banner renders and hides once connected and online`() {
        fixture.seedChats()
        val online = androidx.compose.runtime.mutableStateOf<Boolean?>(false)
        rule.setContent {
            ChatListScreen(
                gateway = fixture.orchestrator,
                deps = fixture.deps,
                phase = RealtimeTransportPhase.CONNECTED,
                networkOnline = online.value,
                onOpenRoom = { _, _, _ -> },
                onLogout = {},
            )
        }
        rule.waitUntil(5_000) { tagExists("qa-room-list-offline-banner") } // 网络不可用 → 横幅立现

        rule.runOnIdle { online.value = true }
        rule.waitUntil(5_000) { !tagExists("qa-room-list-offline-banner") } // 已连 + 在网 → 隐藏不渲染
    }

    @Test
    fun `menu hosts my enterprise and logout entries and logout fires callback`() {
        fixture.seedChats()
        setContent(RealtimeTransportPhase.CONNECTED, online = true)
        rule.waitUntil(5_000) { tagExists("qa-room-list-header") }

        rule.onNodeWithTag("qa-room-list-menu").performClick()
        rule.onNodeWithText(context.t("drawer_myenterprise")).assertExists()
        rule.onNodeWithText(context.t("profile_logout")).performClick()
        rule.waitForIdle()

        assertEquals(1, fixture.logoutCalls.get())
        assertEquals(0, fixture.opened.size)
    }

    /**
     * 滑壳装配（终审 Critical-1）：行内左右滑 → 钮可见 → 点击走真 ChatRowActions
     * （REST 命中 MockWebServer + 本地 DB 落库）。sdk 指向 MockWebServer（同 ChatRowActionsTest 口径）。
     */
    @Test
    fun `swipe actions wired - gestures reveal buttons and taps reach server and local db`() {
        val server = MockWebServer()
        server.start()
        // 三次点击各一条 200（不预置应答时 MockWebServer 会把请求挂起，协程永不恢复）
        repeat(3) { server.enqueue(MockResponse().setBody("""{"success":true}""")) }
        val sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(server.url("/").toString(), "tok-1", "u-1")
        fixture = Fixture(context, sdk)
        fixture.seedChats()
        runBlocking {
            fixture.dbManager.active.chatDao().insertAll(
                listOf(chatRow(_id = "rid-unread", name = "unread-room", fname = "UnreadRoom", unread = 5.0, lm = 95.0)),
            )
        }
        setContent(RealtimeTransportPhase.CONNECTED, online = true)
        rule.waitUntil(5_000) { tagExists("qa-room-list-section-channels") }

        // 壳组合序 = 段序：[0]=todo（已读态）、[1]=rid-unread（频道段未读分支排前）、[2]=general
        // 已读行右滑 → 「未读」「置顶」钮；钮文案首次拖拽才组装，此前不重复（RN swipeActionsReady 同款）
        rule.onAllNodesWithTag("chat-row-swipe")[0].performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithText(context.t("roomitem_swipefavorite")).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { server.requestCount > 0 }
        assertEquals("/api/v1/rooms.favorite", server.takeRequest().path)
        rule.waitUntil(5_000) {
            runBlocking { fixture.dbManager.active.chatDao().getById("rid-todo")?.f == true }
        }

        // 同行再点「未读」→ subscriptions.unread 命中（服务端动作，本地零改动等 stream 回推）。
        // 点击前重滑打开：点按落点派发真实触摸，行合拢后按钮被行覆盖，须再滑出才能点中
        rule.onAllNodesWithTag("chat-row-swipe")[0].performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithText(context.t("roomitem_swipemarkunread")).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { server.requestCount > 1 }
        assertEquals("/api/v1/subscriptions.unread", server.takeRequest().path)

        // 未读行左滑 → 「已读」钮；点击 → subscriptions.read 命中 + 本地双表清未读
        rule.onAllNodesWithTag("chat-row-swipe")[1].performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText(context.t("roomitem_swipemarkread")).performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { server.requestCount > 2 }
        assertEquals("/api/v1/subscriptions.read", server.takeRequest().path)
        rule.waitUntil(5_000) {
            runBlocking { fixture.dbManager.active.chatDao().getById("rid-unread")?.unread == 0.0 }
        }
        runCatching { server.shutdown() }
    }

    /** 预览接线（终审 Important-3）：「自己消息」按 username 判（非 user.id），自己消息无「名字：」前缀。 */
    @Test
    fun `own last message preview omits sender prefix by username not id`() {
        fixture.seedChats()
        runBlocking {
            fixture.dbManager.active.chatDao().insertAll(
                listOf(
                    chatRow(
                        _id = "rid-own",
                        name = "own-room",
                        fname = "OwnRoom",
                        lm = 110.0,
                        last_message = """{"msg":"hello","u":{"_id":"someone-else","name":"bob","username":"bob"}}""",
                    ),
                ),
            )
        }
        setContent(RealtimeTransportPhase.CONNECTED, online = true)
        rule.waitUntil(5_000) { tagExists("qa-room-list-section-channels") }
        // 会话用户 username=bob：预览命中「自己」→ 纯 "hello"；误用 user.id（u-1）恒不命中 → 会渲染 "bob：hello"
        rule.onNodeWithText("hello").assertExists()
        rule.onNodeWithText("bob：hello").assertDoesNotExist()
    }
}

/** 真 orchestrator + 全 fakes（同 MainNavigationFlowTest Fixture 约定）；bootstrap 缝改记录器。 */
private class Fixture(context: Context, sdkOverride: RocketSdk = RocketSdk()) {
    val kv = InMemoryKvStore()
    val store = AuthSessionStore(kv)
    val dbManager = DatabaseManager(context)
    val opened = CopyOnWriteArrayList<Triple<String, String, String>>()
    val logoutCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val scopes = CopyOnWriteArrayList<CoroutineScope>()

    /** CEH 兜底：tearDown 关库与在途 observeList 的竞态异常不该记到下个用例头上（功能断言另有 UI 覆盖）。 */
    private val silence = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        System.err.println("[ChatListScreenTest] suppressed fixture coroutine failure: $e")
    }

    private fun newScope() = CoroutineScope(Dispatchers.Unconfined + silence).also { scopes.add(it) }

    val orchestrator: SessionBootstrapOrchestrator = run {
        val auth = AuthRepository(
            store = store,
            push = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { null }),
            kv = kv,
            orgCache = OrgSessionCache(InMemoryKvStore()),
            dbManager = dbManager,
            backgroundScope = newScope(),
        )
        val manager = RealtimeSessionManager(RocketSdk(), dbManager, syncInitial = {})
        val coordinator = OrgSwitchCoordinator(RocketSdk(), manager, auth, store, OrgSessionCache(InMemoryKvStore()), dbManager)
        SessionBootstrapOrchestrator(
            store = store,
            auth = auth,
            manager = manager,
            coordinator = coordinator,
            orgList = OrgListRepository(RocketSdk(), kv),
            scope = newScope(),
        )
    }.apply {
        bootstrapRealtime = { _, _, _ -> } // 不触 DDP
    }

    val deps: RouteDeps = RouteDeps(
        dbManager = dbManager,
        sdk = sdkOverride,
        store = store,
        scope = newScope(),
        kv = kv,
        roomStreams = RoomStreamManager(
            sdk = RocketSdk(),
            persistMessage = { _, _ -> },
            scope = newScope(),
        ),
        networkMonitor = NetworkMonitor(context),
    )

    /** 预置目标库会话行 + 激活库（ChatListViewModel 守卫要求 active == 绑定库）。 */
    fun seedChats(serverUrl: String = "https://s1") {
        store.save(AuthSession("tok-1", AuthUser(id = "u-1", username = "bob", name = "Bob"), serverUrl))
        dbManager.switchDatabase(serverUrl)
        kotlinx.coroutines.runBlocking {
            dbManager.databaseFor(dbManager.normalizeServer(serverUrl)).chatDao().insertAll(
                listOf(
                    chatRow(_id = "rid-general", name = "general", fname = "General", lm = 100.0),
                    chatRow(_id = "rid-todo", name = "todo", fname = "Todo", todoCount = 1.0, lm = 90.0),
                ),
            )
        }
    }
}
