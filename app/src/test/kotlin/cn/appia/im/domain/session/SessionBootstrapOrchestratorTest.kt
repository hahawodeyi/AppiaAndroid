package cn.appia.im.domain.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.LoginSwitchCandidatesCache
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.push.PushTokenRegistrar
import cn.appia.im.feature.org.OrgListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 启动恢复→bootstrap 串联（RN authStore.ts:71-89 三段 + MainNavigator:44-51）：
 * 恢复判定三分支（全/无/半残）、Main 进入 bootstrap 携带 user.id、登出经 manager::teardown 缝、
 * 候选列表缓存兜底/REST 刷新。bootstrap 经注入缝记录，全测不触 DDP。
 */
// Robolectric 仅支持 JUnit4 runner；SDK 36 沙箱要 Java 21，钉在 SDK 34（同 I18nTest）
@RunWith(RobolectricTestRunner::class)
// plain Application：AppiaApplication.onCreate 会初始化 MMKV，JVM 下不可加载
@Config(sdk = [34], application = Application::class)
class SessionBootstrapOrchestratorTest {
    private val context: Context = getApplicationContext()
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()
    private val store = AuthSessionStore(kv)
    private val dbManager = DatabaseManager(context)
    private val bootstraps = CopyOnWriteArrayList<Triple<String, String, String>>()

    /** orgList/coordinator 持有的 sdk 引用（REST 就绪门前置用，见 orgCandidates 用例）。 */
    private var orgSdk: RocketSdk? = null

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        dbManager.resetAll()
        runCatching { server.shutdown() }
    }

    /** 全 fakes 装配：bootstrap 缝改记录器（manager/coordinator 真实例但引导不触网）。 */
    private fun orchestrator(): SessionBootstrapOrchestrator {
        val auth = AuthRepository(
            store = store,
            push = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { null }), // 无 deviceId：不出网
            kv = kv,
            orgCache = OrgSessionCache(InMemoryKvStore()),
            dbManager = dbManager,
            backgroundScope = CoroutineScope(Dispatchers.Unconfined),
        )
        val manager = RealtimeSessionManager(RocketSdk(), dbManager, syncInitial = {})
        val sdk = RocketSdk().also { orgSdk = it }
        val coordinator = OrgSwitchCoordinator(sdk, manager, auth, store, OrgSessionCache(InMemoryKvStore()), dbManager)
        val orgList = OrgListRepository(sdk, kv)
        return SessionBootstrapOrchestrator(
            store = store,
            auth = auth,
            manager = manager,
            coordinator = coordinator,
            orgList = orgList,
            scope = CoroutineScope(Dispatchers.Unconfined), // launch 同步跑完，断言确定化
        ).apply {
            bootstrapRealtime = { s, t, u -> bootstraps.add(Triple(s, t, u)) }
        }
    }

    private fun saveFullSession(serverUrl: String = "https://a.cn/") {
        store.save(AuthSession("tok-1", AuthUser(id = "u-1", username = "bob", name = "Bob"), serverUrl))
    }

    // ---- 恢复判定三分支 ----

    @Test
    fun `full session restores and bootstraps with stored server token and user id`() {
        val orchestrator = orchestrator()
        saveFullSession()

        assertTrue(orchestrator.hasRestorableSession())
        assertEquals("tok-1", orchestrator.restorableSession()?.token)

        orchestrator.bootstrapOnMainEntered()

        // RN bootstrap(server, token) + T8 指针：第三参 = AuthSession.user.id；serverUrl 原样存原样传
        assertEquals(listOf(Triple("https://a.cn/", "tok-1", "u-1")), bootstraps.toList())
    }

    @Test
    fun `no session does not bootstrap`() {
        val orchestrator = orchestrator()

        assertFalse(orchestrator.hasRestorableSession())
        orchestrator.bootstrapOnMainEntered()

        assertTrue(bootstraps.isEmpty())
    }

    @Test
    fun `half session with empty token is treated as not logged in`() {
        val orchestrator = orchestrator()
        // 半残：JSON 合法但 token 空（isAuthenticated 三字段语义，RN authStore.ts:187）
        store.save(AuthSession("", AuthUser(id = "u-1", username = "bob"), "https://a.cn"))

        assertFalse(orchestrator.hasRestorableSession())
        assertNull(orchestrator.restorableSession())
        orchestrator.bootstrapOnMainEntered()

        assertTrue(bootstraps.isEmpty())
    }

    // ---- 登出与切组织候选 ----

    @Test
    fun `logout clears session and restores prelogin database`() {
        val orchestrator = orchestrator()
        saveFullSession()
        dbManager.switchDatabase("https://a.cn/")

        orchestrator.logout() // teardown 注入缝 = manager::teardown（真实例幂等，无连接可拆）

        assertNull(store.load())
        assertSame(dbManager.databaseFor(DatabaseManager.PRELOGIN_NORMALIZED), dbManager.active) // 回落占位库
        assertFalse(orchestrator.hasRestorableSession())
    }

    @Test
    fun `orgCandidates emits cache first then refreshes from rest and writes cache`() = runBlocking {
        val orchestrator = orchestrator()
        saveFullSession(serverUrl = server.url("/").toString()) // 候选请求落 MockWebServer
        // REST 就绪前置：sdk 会话已 hydrate（等价 MainScreen 开弹层时 bootstrap 步骤 1 已跑的时序），
        // waitSdkRestLogin 判定信号 sdk.currentAuthToken == token 即刻命中
        val session = orchestrator.restorableSession()!!
        orgSdk!!.hydrateRestSession(session.serverUrl, session.token, session.user.id)
        // 段1 数据源：缓存有旧值（RN useState 初始即读缓存）
        val stale = LoginSwitchCandidate(appiaUrl = server.url("/").toString(), companyName = "Stale")
        LoginSwitchCandidatesCache(kv).write("bob", listOf(stale))
        server.enqueue(MockResponse().setBody("""{"data":[{"appiaUrl":"https://b.cn/","companyName":"B"}]}"""))

        orchestrator.refreshOrgCandidates()

        // 段1：缓存值同步发射（两段式，不等待 REST——M1 的 ≤15s 阻塞语义已删）
        assertEquals(listOf(stale), orchestrator.orgCandidates.value)
        // 段2：REST 到后发射新值并回写缓存（RN useLoginSwitchCandidates.ts:46-49）
        withTimeout(5_000) { orchestrator.orgCandidates.first { it != listOf(stale) } }
        assertEquals(1, orchestrator.orgCandidates.value!!.size)
        assertEquals("https://b.cn/", orchestrator.orgCandidates.value!![0].appiaUrl)
        assertEquals("/api/v1/login.getSwitchCandidate", server.takeRequest().path)
        assertEquals("B", LoginSwitchCandidatesCache(kv).read("bob")?.firstOrNull()?.companyName)
    }

    @Test
    fun `orgCandidates emits cache immediately and keeps it when rest fetch fails`() = runBlocking {
        val orchestrator = orchestrator()
        saveFullSession(serverUrl = server.url("/").toString())
        // 缓存读校验含当前主体（RN readLoginSwitchCandidatesCache serverUrl 门）：行 appiaUrl = 当前 server
        val row = LoginSwitchCandidate(appiaUrl = server.url("/").toString(), companyName = "B")
        LoginSwitchCandidatesCache(kv).write("bob", listOf(row))
        val session = orchestrator.restorableSession()!!
        orgSdk!!.hydrateRestSession(session.serverUrl, session.token, session.user.id)
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        orchestrator.refreshOrgCandidates()

        // 段1：缓存值同步发射（此前 M1 版本要等 waitSdkRestLogin/失败才回缓存）
        assertEquals(listOf(row), orchestrator.orgCandidates.value)
        server.takeRequest() // 刷新请求照发（RN effect 同），500 失败
        delay(500) // 让失败响应回流、后台协程走完 catch
        assertEquals(listOf(row), orchestrator.orgCandidates.value) // 失败静默保持缓存（RN catch{} 同）
    }

    @Test
    fun `orgCandidates refresh without cache emits null first then rest value`() = runBlocking {
        val orchestrator = orchestrator()
        saveFullSession(serverUrl = server.url("/").toString())
        val session = orchestrator.restorableSession()!!
        orgSdk!!.hydrateRestSession(session.serverUrl, session.token, session.user.id)
        server.enqueue(MockResponse().setBody("""{"data":[{"appiaUrl":"https://b.cn/","companyName":"B"}]}"""))

        orchestrator.refreshOrgCandidates()

        assertNull(orchestrator.orgCandidates.value) // 段1：无缓存 → null（UI 即时空列表，不再挂起）
        withTimeout(5_000) { orchestrator.orgCandidates.first { it != null } }
        assertEquals("B", orchestrator.orgCandidates.value!![0].companyName)
    }
}
