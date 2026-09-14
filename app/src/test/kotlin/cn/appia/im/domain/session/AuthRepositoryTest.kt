package cn.appia.im.domain.session

import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.network.LoginResult
import cn.appia.im.core.network.LoginMe
import cn.appia.im.core.push.PushTokenRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AuthRepository：login 落库 + 推送 fire-and-forget / restore / logout 最小实现。
 * backgroundScope 注入 Dispatchers.Unconfined：launch 在调用线程同步跑完（无挂起点），
 * fire-and-forget 的 wire 断言因此确定化。
 */
class AuthRepositoryTest {
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()
    private val store = AuthSessionStore(kv)

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun repo(): AuthRepository {
        server.start()
        return AuthRepository(
            store = store,
            push = PushTokenRegistrar(kv, OkHttpClient(), deviceIdProvider = { "device-1" }),
            backgroundScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

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

        store.save(AuthSession("tok-1", cn.appia.im.core.network.AuthUser(id = "u-1", username = "bob"), "https://a.cn"))
        assertEquals("tok-1", repo.restore()?.token)
    }

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
}
