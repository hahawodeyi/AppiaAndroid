package cn.appia.im.domain.session

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.datastore.LoginSwitchCandidatesCache
import cn.appia.im.core.datastore.OrgSessionCache
import cn.appia.im.core.datastore.OrgSessionCacheRow
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.network.AuthUser
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.LoginMe
import cn.appia.im.core.network.rest.OrgSwitchState
import cn.appia.im.core.push.PushTokenRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AuthRepository：login 落库 + 推送 fire-and-forget / restore / logout 完整清单
 * （RN authStore.ts:138-169 顺序对照）。backgroundScope 注入 Dispatchers.Unconfined：
 * launch 在调用线程同步跑完，wire 断言与调用序（teardown 时 store 是否已清）确定化。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthRepositoryTest {
    private val context: Context = getApplicationContext()
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()
    private val store = AuthSessionStore(kv)
    private val orgCache = OrgSessionCache(InMemoryKvStore())
    private val dbManager = DatabaseManager(context)
    private val teardownOrder = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        dbManager.resetAll()
        runCatching { server.shutdown() }
    }

    private fun repo(): AuthRepository = AuthRepository(
        store = store,
        push = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { "device-1" }),
        kv = kv,
        orgCache = orgCache,
        dbManager = dbManager,
        backgroundScope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** 登录主体 = MockWebServer：fire-and-forget 的推送请求才可被断言。 */
    private fun host(): String = server.url("/").toString()

    private fun loginResult() = LoginResult(authToken = "tok-1", userId = "u-1", me = LoginMe(username = "bob"))

    @Test
    fun `login persists session and fires push register fire-and-forget`() {
        val repo = repo()
        server.enqueue(MockResponse().setBody("{}"))

        repo.login(loginResult(), host())

        val session = store.load()!!
        assertEquals("tok-1", session.token)
        assertEquals("u-1", session.user.id)
        assertEquals("bob", session.user.username)
        assertEquals(host(), session.serverUrl) // RN 原样存，normalize 在网络边界
        assertTrue(store.isAuthenticated)

        // fire-and-forget 已出网：serverUrl 为登录主体（RN authStore.ts:105 不 await）
        val request = server.takeRequest()
        assertEquals("/api/v1/push.token", request.path)
        assertEquals("""{"value":"device-1","type":"gcm","appName":"cn.appia.im"}""", request.body.readUtf8())
        assertEquals("tok-1", request.getHeader("X-Auth-Token"))
    }

    @Test
    fun `login survives push registration failure`() {
        val repo = repo()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))

        repo.login(loginResult(), host()) // 推送失败不上抛、不阻塞

        assertTrue(store.isAuthenticated)
    }

    @Test
    fun `restore returns null before login and session after`() {
        val repo = repo()

        assertNull(repo.restore())

        store.save(AuthSession("tok-1", AuthUser(id = "u-1", username = "bob"), "https://a.cn"))
        assertEquals("tok-1", repo.restore()?.token)
    }

    // ---- logout 完整清单（RN authStore.ts:138-169 顺序）----

    @Test
    fun `logout clears store and unregisters from prior server`() {
        val repo = repo()
        server.enqueue(MockResponse().setBody("{}")) // register
        repo.login(loginResult(), host())
        server.takeRequest()

        server.enqueue(MockResponse().setBody("{}")) // unregister
        repo.logout()

        assertNull(store.load())
        assertFalse(store.isAuthenticated)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/push.token", request.path)
        assertEquals("""{"token":"device-1"}""", request.body.readUtf8())
        assertNull(request.getHeader("X-Auth-Token"))
    }

    @Test
    fun `logout with empty store hits no network`() {
        val repo = repo()

        repo.logout()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `logout during org switch is a no-op`() {
        val repo = repo()
        server.enqueue(MockResponse().setBody("{}")) // register
        repo.login(loginResult(), host())

        OrgSwitchState.begin()
        try {
            repo.logout()
            assertEquals("tok-1", store.load()!!.token) // RN :139-141 直接 return
        } finally {
            OrgSwitchState.end()
        }
    }

    @Test
    fun `logout runs full RN checklist in order`() {
        val repo = repo()
        server.enqueue(MockResponse().setBody("{}")) // register
        repo.login(loginResult(), host())
        server.takeRequest()
        orgCache.set(host(), OrgSessionCacheRow(token = "tok-x", userId = "u-x", username = "x"))
        LoginSwitchCandidatesCache(kv).write("bob", listOf(LoginSwitchCandidate(userId = "u-1", appiaUrl = host())))
        val cursorKey = "roomsUpdatedAt:${host().trimEnd('/')}" // RoomsSyncCursor key 去尾斜杠
        kv.putString(cursorKey, "2026-01-01T00:00:00.000Z")
        dbManager.switchDatabase(host())
        val orgDb = dbManager.active
        server.enqueue(MockResponse().setBody("{}")) // unregister

        // teardown 缝记录调用点：RN :152 teardown 先于 :157 store 清空
        repo.logout { teardownOrder.add("storeClearedAtTeardown=${store.load() == null}") }

        assertEquals(listOf("storeClearedAtTeardown=false"), teardownOrder)

        // RN :143 组织会话缓存全清
        assertNull(orgCache.get(host()))
        // RN :144-146 候选缓存清
        assertNull(LoginSwitchCandidatesCache(kv).read("bob"))
        // RN :148-150 roomsUpdatedAt 清
        assertEquals("", kv.getString(cursorKey, ""))
        // RN :157 store 清（上面已断言 teardown 时点未清）
        assertNull(store.load())
        // RN :161-168 删旧库并回落 prelogin（resetDatabase 关闭删除 + finally setActivePreloginDatabase）
        assertNotSame(orgDb, dbManager.active)
        assertTrue(dbManager.active === dbManager.databaseFor(DatabaseManager.PRELOGIN_NORMALIZED))

        val request = server.takeRequest() // RN :159-160 注销推送（失败吞，fire-and-forget）
        assertEquals("/api/v1/push.token", request.path)
        assertEquals("DELETE", request.method)
    }

    @Test
    fun `logout without server still falls back to prelogin db`() {
        val repo = repo()
        store.save(AuthSession("tok-1", AuthUser(id = "u-1", username = ""), ""))
        dbManager.switchDatabase(DatabaseManager.PRELOGIN_NORMALIZED)
        val prelogin = dbManager.active

        repo.logout()

        assertNull(store.load())
        assertEquals(0, server.requestCount) // 无 server 不发注销（Registrar 无处寻址）
        assertTrue(dbManager.active === prelogin)
    }
}
