package cn.appia.im.feature.main.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.AppiaNavHost
import cn.appia.im.RouteDeps
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.LoginMe
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.rest.OrgSwitchState
import cn.appia.im.core.network.rest.SessionExpiredBus
import cn.appia.im.core.push.PushTokenRegistrar
import cn.appia.im.core.realtime.NetworkMonitor
import cn.appia.im.core.realtime.RoomStreamManager
import cn.appia.im.domain.session.AuthRepository
import cn.appia.im.domain.session.OrgSwitchCoordinator
import cn.appia.im.domain.session.RealtimeSessionManager
import cn.appia.im.domain.session.SessionBootstrapOrchestrator
import cn.appia.im.feature.chatlist.chatRow
import cn.appia.im.feature.login.CompanyServer
import cn.appia.im.feature.login.SendCodeResult
import cn.appia.im.feature.login.VerifyEnterpriseResponse
import cn.appia.im.feature.login.ui.LoginDeps
import cn.appia.im.feature.login.ui.LoginState
import cn.appia.im.feature.org.OrgListRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 导航流程串联（T11）：登录成功→Main（持久化+bootstrap）→登出→Auth；
 * 恢复会话首帧即 Main；SessionExpired→登出→回 Auth。会话层为真 orchestrator +
 * bootstrap 缝记录器（不触 DDP/MMKV），登录经 LoginState 注入缝免触网。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 I18nTest）
@RunWith(RobolectricTestRunner::class)
// plain Application：AppiaApplication.onCreate 会初始化 MMKV，JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class MainNavigationFlowTest {
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
        // 先取消注入的 scope（sections 流 WhileSubscribed(5s) 宽限期会在 resetAll 关库后重查），
        // 再等主线程与 Room 执行器上的在途查询落定（cancel 不等待第三方执行器），
        // 最后关 MockWebServer 与库——顺序颠倒会出现「already-closed」竞态（T11 实测）
        fixture.scopes.forEach { it.cancel() }
        runCatching { rule.waitForIdle() }
        Thread.sleep(100)
        runCatching { fixture.server.shutdown() } // M2 前置收尾（总纲 §4.2-5）：MockWebServer 不跨用例泄漏
        fixture.dbManager.resetAll()
    }

    private fun waitUntilExists(matcher: () -> Boolean) {
        rule.waitUntil(5_000, matcher)
    }

    private fun textExists(text: String) =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun tagExists(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `login success persists lands on Main then logout returns to enterprise code`() {
        rule.setContent {
            AppiaNavHost(
                verify = { _, _ ->
                    VerifyEnterpriseResponse(success = true, servers = listOf(CompanyServer(url = "https://s1", name = "S1")))
                },
                session = fixture.orchestrator,
                deps = fixture.deps,
                loginState = { servers, onLoginSuccess ->
                    LoginState(
                        servers,
                        LoginDeps(
                            strings = { context.t(it) },
                            sendCode = { _, _, _, _ -> SendCodeResult(true) },
                            login = { _, _ -> LoginResult("tok-1", "u-1", LoginMe(username = "bob", name = "Bob")) },
                            fetchCasUrl = { null },
                            generateSsoToken = { "abc123xyz09876zy" },
                            onLoginSuccess = onLoginSuccess,
                        ),
                    ).apply {
                        phone = "13800138000"
                        smsCode = "1234"
                        captchaIc = fixture.ic
                    }
                },
            )
        }

        // 企业码 → 登录页
        rule.onNodeWithTag("enterprise_code_input").performTextInput("demo")
        rule.onNodeWithText(context.t("enterprise_next")).performClick()
        waitUntilExists { tagExists("login_phone_input") }

        // ic+验证码已预置 → 登录键可用；fake login 即回 → 持久化 → Main（真实会话列表）
        rule.onNodeWithTag("login_submit").performScrollTo().performClick()
        waitUntilExists { textExists("Bob") } // 主屏顶栏显示主体名

        assertEquals("tok-1", fixture.store.load()?.token)
        assertEquals(listOf(Triple("https://s1", "tok-1", "u-1")), fixture.bootstraps.toList()) // 进 Main 即 bootstrap

        // 登出（顶栏 ⋮ 菜单迁入）→ 回企业码页
        openMenuAndLogout()
        waitUntilExists { tagExists("enterprise_code_input") }
        assertNull(fixture.store.load())
    }

    /** T11 主屏菜单两跳：⋮ → 退出登录（登出/我的企业自占位 MainScreen 迁入 ChatListScreen 顶栏）。 */
    private fun openMenuAndLogout() {
        rule.onNodeWithTag("qa-room-list-menu").performClick()
        rule.onNodeWithText(context.t("profile_logout")).performClick()
    }

    @Test
    fun `restored session lands on Main on first frame and bootstraps`() {
        fixture.saveSession()

        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }

        // 无需任何导航动作：首帧即 Main（RN RootNavigator:66-95）
        waitUntilExists { textExists("Bob") }
        assertEquals(listOf(Triple("https://s1", "tok-1", "u-1")), fixture.bootstraps.toList()) // 进 Main 即 bootstrap
    }

    @Test
    fun `session expired event logs out and returns to enterprise code`() {
        fixture.saveSession()

        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("Bob") }

        rule.runOnIdle { SessionExpiredBus.emit() }

        waitUntilExists { tagExists("enterprise_code_input") }
        assertNull(fixture.store.load())
    }

    // ---- 评审 Important-2 回归：组织切换中总线事件被豁免，不登出不导航 ----

    @Test
    fun `session expired during org switch does not log out or navigate`() {
        fixture.saveSession()

        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("Bob") }

        rule.runOnIdle { OrgSwitchState.begin() }
        try {
            rule.runOnIdle { SessionExpiredBus.emit() }
            rule.waitForIdle() // 若修复前：logout + goAuth 已把用户甩到企业码页

            assertTrue(textExists("Bob")) // 仍在主屏
            assertNotNull(fixture.store.load()) // 会话未被清（logout 豁免跳过）
        } finally {
            // 全局单例标志：断言失败也必须复位，不污染同类/同 JVM 的后续测试
            rule.runOnIdle { OrgSwitchState.end() }
        }

        // 豁免期结束后的失效事件照常登出（豁免只挡切换窗口，不吞语义）
        rule.runOnIdle { SessionExpiredBus.emit() }
        waitUntilExists { tagExists("enterprise_code_input") }
        assertNull(fixture.store.load())
    }

    // ---- M2 前置收尾（总纲 §4.2-2）：组织切换中手动登出豁免，不登出不导航 ----

    @Test
    fun `manual logout during org switch is exempted and stays on main`() {
        fixture.saveSession()

        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("Bob") }

        rule.runOnIdle { OrgSwitchState.begin() }
        try {
            openMenuAndLogout()
            rule.waitForIdle()

            assertTrue(textExists("Bob")) // 仍在主屏（未导航回企业码页）
            assertNotNull(fixture.store.load()) // 会话未被清（logout 豁免跳过）
        } finally {
            rule.runOnIdle { OrgSwitchState.end() }
        }

        // 豁免窗口外的手动登出照常执行
        openMenuAndLogout()
        waitUntilExists { tagExists("enterprise_code_input") }
        assertNull(fixture.store.load())
    }

    @Test
    fun `org switch click survives sheet dismissal and refreshes main screen`() {
        // 候选请求落 MockWebServer：REST 就绪门即刻命中（bootstrap 已 hydrate 的等价时序）
        fixture.saveSession(serverUrl = fixture.server.url("/").toString())
        fixture.makeRestReady()
        fixture.server.enqueue(
            MockResponse().setBody("{\"data\":[{\"appiaUrl\":\"https://b.cn/\",\"companyName\":\"B\"}]}"),
        )
        // switchOrg 缝：模拟 coordinator.applySession（更新 store）+ 记录，使完成路径可观测；
        // delay 等价真实换票 REST 挂起点——弹层关闭若取消承载 scope（修复前缺陷）则在此死掉
        fixture.orchestrator.switchOrgImpl = { target ->
            delay(50)
            fixture.switchCalls.add(target)
            fixture.store.save(AuthSession("tok-2", AuthUser(id = "u-1", username = "bob", name = "Bob"), target))
        }

        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("Bob") }

        // 顶栏 ⋮ → 我的企业 → 候选两段式
        rule.onNodeWithTag("qa-room-list-menu").performClick()
        rule.onNodeWithText(context.t("drawer_myenterprise")).performClick()
        waitUntilExists { textExists("B") } // 候选行（REST 刷新）

        rule.onNodeWithText("B").performClick()
        // 修复前：onSwitch 先关弹层（host 离组合）→ host 自有 scope 被取消 → 切换静默无操作；
        // 修复后：切组织在 ChatListScreen 级 scope 跑完 → session 重读 → VM 按新 server 重建
        waitUntilExists { fixture.switchCalls.isNotEmpty() }
        assertEquals(listOf("https://b.cn/"), fixture.switchCalls.toList())
    }

    // ---- T11 导航链：列表 → 房间 → 返回 ----

    @Test
    fun `list row navigates to room and back returns to list`() {
        fixture.seedChats()

        rule.setContent {
            AppiaNavHost(session = fixture.orchestrator, startAuthenticated = true, deps = fixture.deps)
        }
        waitUntilExists { textExists("General") } // ChatRow 行上屏

        rule.onNodeWithText("General").performClick() // → RoomRoute(rid, title 兜底, t)
        waitUntilExists { tagExists("qa-room-input") }

        rule.onNodeWithTag("qa-room-header-back").performClick() // popBackStack → 列表
        waitUntilExists { textExists("General") }
    }
}

/** 真 orchestrator + 全 fakes：bootstrap/switchOrg 缝改记录器（不触 DDP）；推送无 deviceId（不出网）。 */
private class Fixture(context: Context) {
    val kv = InMemoryKvStore()
    val store = AuthSessionStore(kv)
    val dbManager = DatabaseManager(context)
    val bootstraps = CopyOnWriteArrayList<Triple<String, String, String>>()
    val switchCalls = CopyOnWriteArrayList<String>()
    val server = MockWebServer()
    val ic = JsonObject(mapOf("ic" to JsonPrimitive("ticket")))
    val scopes = CopyOnWriteArrayList<CoroutineScope>()

    /** CEH 兜底：tearDown 关库与在途 observeList 的竞态异常不该记到下个用例头上（功能断言另有 UI 覆盖）。 */
    private val silence = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        System.err.println("[MainNavigationFlowTest] suppressed fixture coroutine failure: $e")
    }

    private fun newScope(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) =
        CoroutineScope(dispatcher + SupervisorJob() + silence).also { scopes.add(it) }

    /** orgList/coordinator 持有的 sdk（REST 就绪门前置用，见 makeRestReady）。 */
    lateinit var orgSdk: RocketSdk
        private set

    val orchestrator: SessionBootstrapOrchestrator = run {
        val auth = AuthRepository(
            store = store,
            push = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { null }),
            kv = kv,
            orgCache = OrgSessionCache(InMemoryKvStore()),
            dbManager = dbManager,
            // 生产同款 IO：logout 的删库/回落异步于 UI——Unconfined 会在重组中同步关池，observeList 竞态
            backgroundScope = newScope(Dispatchers.IO),
        )
        val manager = RealtimeSessionManager(RocketSdk(), dbManager, syncInitial = {})
        val sdk = RocketSdk().also { orgSdk = it }
        val coordinator = OrgSwitchCoordinator(sdk, manager, auth, store, OrgSessionCache(InMemoryKvStore()), dbManager)
        val orgList = OrgListRepository(sdk, kv)
        SessionBootstrapOrchestrator(
            store = store,
            auth = auth,
            manager = manager,
            coordinator = coordinator,
            orgList = orgList,
            scope = newScope(),
        )
    }.apply {
        bootstrapRealtime = { s, t, u -> bootstraps.add(Triple(s, t, u)) }
    }

    fun saveSession(serverUrl: String = "https://s1") {
        store.save(AuthSession("tok-1", AuthUser(id = "u-1", username = "bob", name = "Bob"), serverUrl))
    }

    /** T11 路由装配束（真实会话列表/房间页所需单例；房间流 stub 不触 DDP）。 */
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

    /** 预置目标库会话行 + 激活库（ChatListViewModel 守卫要求 active == 绑定库）。 */
    fun seedChats(serverUrl: String = "https://s1") {
        saveSession(serverUrl)
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

    /** REST 就绪前置：sdk 会话 hydrate（等价 MainScreen 开弹层时 bootstrap 步骤 1 已跑的时序）。 */
    fun makeRestReady() {
        val session = store.load()!!
        orgSdk.hydrateRestSession(session.serverUrl, session.token, session.user.id)
    }

    init {
        server.start()
    }
}
