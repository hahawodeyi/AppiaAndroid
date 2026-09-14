package cn.appia.im.feature.org

import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
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
 * OrgListRepository：login.getSwitchCandidate 解析（sdk.get 已平铺 `data ?? resp`，直接读数组形态）、
 * MMKV 候选缓存（key 不含 token、serverUrl 校验）、waitSdkRestLogin 判定信号与超时。
 */
class OrgListRepositoryTest {
    private val server = MockWebServer()
    private val kv = InMemoryKvStore()
    private lateinit var sdk: RocketSdk

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun repo(): OrgListRepository {
        server.start()
        sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(server.url("/").toString(), "tok-1", "u-1")
        return OrgListRepository(sdk, kv)
    }

    @Test
    fun `fetchCandidates parses flattened data array with all six fields`() = runBlocking {
        val repo = repo()
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"userId":"u1","appiaUrl":"https://a.cn/","phone":"13800000000",""" +
                    """"companyName":"A Ltd","companyNameCn":"A Ltd CN","companyLogo":"https://a.cn/logo.png"}]}""",
            ),
        )

        val list = repo.fetchCandidates()

        assertEquals(1, list.size)
        assertEquals(
            LoginSwitchCandidate(
                userId = "u1",
                appiaUrl = "https://a.cn/",
                phone = "13800000000",
                companyName = "A Ltd",
                companyNameCn = "A Ltd CN",
                companyLogo = "https://a.cn/logo.png",
            ),
            list[0],
        )
        val request = server.takeRequest()
        assertEquals("/api/v1/login.getSwitchCandidate", request.path)
        assertEquals("tok-1", request.getHeader("X-Auth-Token"))
    }

    @Test
    fun `fetchCandidates tolerates bare array and unknown fields`() = runBlocking {
        val repo = repo()
        server.enqueue(
            MockResponse().setBody("""[{"userId":"u2","appiaUrl":"https://b.cn","extra":1}]"""),
        )

        val list = repo.fetchCandidates()

        assertEquals(1, list.size)
        assertEquals("u2", list[0].userId)
        assertEquals("https://b.cn", list[0].appiaUrl)
        assertEquals("", list[0].companyName)
    }

    @Test
    fun `fetchCandidates returns empty list for non-array payload`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setBody("""{"data":{"not":"an array"}}"""))

        assertEquals(0, repo.fetchCandidates().size)
    }

    // ---- 候选缓存（RN loginSwitchCandidatesCache.ts）----

    @Test
    fun `cache round-trip and serverUrl match with trailing slash tolerance`() {
        val repo = OrgListRepository(RocketSdk(), kv)
        val list = listOf(LoginSwitchCandidate(userId = "u1", appiaUrl = "https://a.cn/"))

        repo.writeCache("alice", list)

        assertEquals(list, repo.readCache("alice"))
        assertEquals(list, repo.readCache("alice", serverUrl = "https://a.cn"))
        assertNull(repo.readCache("alice", serverUrl = "https://other.cn"))
    }

    @Test
    fun `cache key holds no token and clear removes only that username`() {
        val repo = OrgListRepository(RocketSdk(), kv)
        repo.writeCache("alice", listOf(LoginSwitchCandidate(userId = "u1", appiaUrl = "https://a.cn")))
        repo.writeCache("bob", listOf(LoginSwitchCandidate(userId = "u2", appiaUrl = "https://b.cn")))

        assertFalse(kv.getString("login-switch-candidates-alice", "").contains("tok"))

        repo.clearCache("alice")

        assertNull(repo.readCache("alice"))
        assertEquals("u2", repo.readCache("bob")!![0].userId)
    }

    // ---- waitSdkRestLogin（RN 50ms 轮询 15s 上限）----

    @Test
    fun `waitSdkRestLogin returns true immediately when session token matches`() = runBlocking {
        val repo = repo() // hydrateRestSession("tok-1")

        assertTrue(repo.waitSdkRestLogin("tok-1", pollMs = 1, maxWaitMs = 100))
    }

    @Test
    fun `waitSdkRestLogin times out returning false when token never matches`() = runBlocking {
        val repo = repo()

        val started = System.currentTimeMillis()
        val ready = repo.waitSdkRestLogin("tok-other", pollMs = 10, maxWaitMs = 80)

        assertFalse(ready)
        assertTrue(System.currentTimeMillis() - started >= 70, "must wait out the deadline")
    }
}
