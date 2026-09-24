package cn.appia.im.domain.presence

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UsernameIdResolver 对照 RN resolvePresenceUserIdByUsername（400ms 防抖合并 users.info +
 * 回写缓存 + 触发 presence 拉取；rcId==username 丢弃；cacheRcUserId 守卫幂等）。
 */
class UsernameIdResolverTest {

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var sdk: RocketSdk
    private val infoRequests = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                infoRequests.add(request.requestUrl?.queryParameter("userId"))
                return when (request.requestUrl?.queryParameter("userId")) {
                    "benfu.wei" -> MockResponse().setBody(
                        """{"user":{"_id":"rB8xK9mN2pQ4sT6vW","username":"benfu.wei"}}""",
                    )
                    // HRM key 形态：_id 与 username 相同 → 丢弃
                    "hao.chen" -> MockResponse().setBody(
                        """{"user":{"_id":"hao.chen","username":"hao.chen"}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        sdk = RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "me") }
        UsernameIdResolver.attach(sdk, scope)
        PresenceBatcher.attach(null, scope) // 阻断 batcher 真发请求（resolve 触发的拉取不进本测试断言）
    }

    @AfterEach
    fun tearDown() {
        UsernameIdResolver.reset()
        PresenceBatcher.reset()
        scope.cancel()
        runCatching { server.shutdown() }
    }

    @Test
    fun `resolves username to rc user id and caches`() = runBlocking {
        UsernameIdResolver.scheduleResolve("benfu.wei")
        UsernameIdResolver.scheduleResolve("someone.else") // 同窗合并
        assertEquals(setOf("benfu.wei", "someone.else"), UsernameIdResolver.pendingForTest())

        UsernameIdResolver.resolveNow()
        assertEquals("rB8xK9mN2pQ4sT6vW", UsernameIdResolver.resolvedRcUserId("benfu.wei"))
        assertEquals(null, UsernameIdResolver.resolvedRcUserId("someone.else")) // 404 静默
        assertTrue(infoRequests.containsAll(listOf("benfu.wei", "someone.else")))
    }

    @Test
    fun `drops resolve when id equals username`() = runBlocking {
        UsernameIdResolver.scheduleResolve("hao.chen")
        UsernameIdResolver.resolveNow()
        assertEquals(null, UsernameIdResolver.resolvedRcUserId("hao.chen"))
    }

    @Test
    fun `cached username skips scheduling`() {
        UsernameIdResolver.cacheRcUserId("benfu.wei", "rB8xK9mN2pQ4sT6vW")
        UsernameIdResolver.scheduleResolve("benfu.wei") // 已缓存 → 不入 pending
        assertEquals(emptySet<String>(), UsernameIdResolver.pendingForTest())
    }

    @Test
    fun `cache rejects bot dept and username lookalike ids`() {
        UsernameIdResolver.cacheRcUserId("u", "agent.bot")
        UsernameIdResolver.cacheRcUserId("u", "EMT-0")
        UsernameIdResolver.cacheRcUserId("benfu.wei", "benfu.wei")
        assertTrue(UsernameIdResolver.cachedForTest().isEmpty())
    }

    @Test
    fun `cache same value twice is idempotent`() {
        UsernameIdResolver.cacheRcUserId("benfu.wei", "rB8xK9mN2pQ4sT6vW")
        val before = UsernameIdResolver.resolvedVersion.value
        UsernameIdResolver.cacheRcUserId("benfu.wei", "rB8xK9mN2pQ4sT6vW")
        assertEquals(before, UsernameIdResolver.resolvedVersion.value) // 无 bump
        assertEquals(1, UsernameIdResolver.cachedForTest().size)
    }
}
