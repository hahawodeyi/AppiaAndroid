package cn.appia.im.feature.main.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.AppiaNavHost
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
import cn.appia.im.core.realtime.RoomStreamManager
import cn.appia.im.domain.session.AuthRepository
import cn.appia.im.domain.session.OrgSwitchCoordinator
import cn.appia.im.domain.session.RealtimeSessionManager
import cn.appia.im.domain.session.RoomAccessLostBus
import cn.appia.im.domain.session.RoomAccessLoss
import cn.appia.im.domain.session.RoomAccessLostEvent
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.chatlist.chatRow
import cn.appia.im.feature.org.OrgListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 房间访问丢失导航收口（M4-T10 / RN handleRoomAccessLostNavigation）：
 * - 栈涉及 rid（Room 在栈）→ Bus 事件 pop 回列表 + 提示（kicked→AlertDialog / self→Toast）
 * - 栈不涉及 rid（纯列表态）→ 静默不导航
 * - RoomInfo 深栈（列表→Room→RoomInfo）同样被弹回列表（stackInvolvesRid 清单覆盖）
 * 会话层真 orchestrator（同 MainNavigationFlowTest fixture 口径，不触 DDP）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomAccessLossNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: AccessLossFixture

    @Before
    fun setUp() {
        fixture = AccessLossFixture(context)
    }

    @After
    fun tearDown() {
        fixture.scopes.forEach { it.cancel() }
        runCatching { rule.waitForIdle() }
        Thread.sleep(100)
        runCatching { fixture.server.shutdown() }
        fixture.dbManager.resetAll()
    }

    private fun waitUntilExists(matcher: () -> Boolean) = rule.waitUntil(5_000, matcher)

    private fun textExists(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun tagExists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `kicked event pops room stack back to list and shows removed alert`() {
        fixture.seedChats()
        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("General") }
        rule.onNodeWithText("General").performClick()
        waitUntilExists { tagExists("qa-room-editor") }

        // 被移出：Bus 事件（栈涉及 rid → pop 回列表 + AlertDialog）
        RoomAccessLostBus.emit(RoomAccessLostEvent("rid-general", RoomAccessLoss.Reason.KICKED))

        waitUntilExists { textExists("General") } // 列表行重现（栈已弹回 Main）
        waitUntilExists { textExists(context.t("roomAccess_removed")) } // Alert 正文
        // 编辑器（Room 屏）已出栈
        assertEquals(false, tagExists("qa-room-editor"))
    }

    @Test
    fun `self leave event pops room stack and shows leave success toast`() {
        fixture.seedChats()
        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("General") }
        rule.onNodeWithText("General").performClick()
        waitUntilExists { tagExists("qa-room-editor") }

        RoomAccessLostBus.emit(RoomAccessLostEvent("rid-general", RoomAccessLoss.Reason.SELF))

        waitUntilExists { textExists("General") }
        // RN ToastAndroid 同义：Robolectric shadow 断言最近一条 Toast 文案
        waitUntilExists { ShadowToast.getTextOfLatestToast() == context.t("roomInfo_leaveSuccess") }
        assertEquals(false, tagExists("qa-room-editor"))
    }

    @Test
    fun `event for rid not in stack is silently ignored`() {
        fixture.seedChats()
        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("General") }
        // 纯列表态（无 Room 在栈）：另一 rid 的 removed 不导航不弹窗
        RoomAccessLostBus.emit(RoomAccessLostEvent("rid-elsewhere", RoomAccessLoss.Reason.KICKED))
        rule.waitForIdle()
        Thread.sleep(200)
        // 仍在列表（无 Alert 文案上屏）
        assertTrue(textExists("General"))
        assertEquals(false, textExists(context.t("roomAccess_removed")))
    }

    @Test
    fun `room info deep stack is popped back to list`() {
        fixture.seedChats()
        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("General") }
        rule.onNodeWithText("General").performClick()
        waitUntilExists { tagExists("qa-room-editor") }
        // 标题点击 → RoomInfo（M4-T4 入口）
        rule.onNodeWithTag("qa-room-header-title").performClick()
        waitUntilExists { tagExists("qa-roominfo-leave") }

        RoomAccessLostBus.emit(RoomAccessLostEvent("rid-general", RoomAccessLoss.Reason.LEFT))

        waitUntilExists { textExists("General") } // Room+RoomInfo 全弹回列表
        waitUntilExists { textExists(context.t("roomAccess_leftElsewhere")) }
        assertEquals(false, tagExists("qa-roominfo-leave"))
        assertEquals(false, tagExists("qa-room-editor"))
    }
}

/** MainNavigationFlowTest.Fixture 同口径（会话层真件 + 全 fakes，不触 DDP/MMKV）。 */
private class AccessLossFixture(context: Context) {
    val kv = InMemoryKvStore()
    val store = AuthSessionStore(kv)
    val dbManager = DatabaseManager(context)
    val server = okhttp3.mockwebserver.MockWebServer()
    val scopes = CopyOnWriteArrayList<CoroutineScope>()

    private val silence = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        System.err.println("[RoomAccessLossNavigationTest] suppressed fixture coroutine failure: $e")
    }

    private fun newScope() =
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + silence).also { scopes.add(it) }

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
        val coordinator = OrgSwitchCoordinator(
            RocketSdk(), manager, auth, store, OrgSessionCache(InMemoryKvStore()), dbManager,
        )
        val orgList = OrgListRepository(RocketSdk(), kv)
        SessionBootstrapOrchestrator(
            store = store,
            auth = auth,
            manager = manager,
            coordinator = coordinator,
            orgList = orgList,
            scope = newScope(),
        )
    }

    val deps: RouteDeps by lazy {
        RouteDeps(
            dbManager = dbManager,
            sdk = RocketSdk(),
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
    }

    fun seedChats(serverUrl: String = "https://s1") {
        store.save(AuthSession("tok-1", AuthUser(id = "u-1", username = "bob", name = "Bob"), serverUrl))
        dbManager.switchDatabase(serverUrl)
        kotlinx.coroutines.runBlocking {
            dbManager.databaseFor(dbManager.normalizeServer(serverUrl)).chatDao().insertAll(
                listOf(
                    chatRow(_id = "rid-general", name = "general", fname = "General", lm = 100.0),
                ),
            )
        }
    }

    init {
        server.start()
    }
}
