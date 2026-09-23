package cn.appia.im.feature.contacts

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ContactsStore/Repository：hrm/v2.users.list wire（路径/鉴权头/data 包裹与顶层双兼容）、
 * phase 状态机（UNLOAD→LOADING→LOADED/LOAD_ERROR）、refresh 序号防竞态、refreshIfUnloaded 单飞。
 */
class ContactsRepositoryTest {

    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
        ContactsStore.reset()
    }

    private fun repo(): ContactsRepository {
        server.start()
        val sdk = RocketSdk(client = OkHttpClient())
        sdk.hydrateRestSession(server.url("/").toString(), "tok-1", "u-1")
        return ContactsRepository(sdk)
    }

    @Test
    fun `refresh hits hrm endpoint with auth headers and parses payload`() = runBlocking {
        val repo = repo()
        server.enqueue(
            MockResponse().setBody(
                """{"userMap":{"u1":{"_id":"u1","username":"zhang.san","fname":"Zhang San"}},
                    "departmentMap":{"d1":{"_id":"d1","name":"Dept","children":["d2"],"users":["u1"],
                      "countIncludeChildren":{"all":5,"fullTime":3}}},
                    "rootTree":["EMT-0","EMT-1328"]}""",
            ),
        )

        ContactsStore.reset()
        repo.refresh()

        assertEquals(ContactsPhase.LOADED, ContactsStore.phase.value)
        val payload = ContactsStore.payload.value
        assertEquals("zhang.san", payload.userMap.getValue("u1").username)
        assertEquals(listOf("d2"), payload.departmentMap.getValue("d1").children)
        assertEquals(5, payload.departmentMap.getValue("d1").countIncludeChildren["all"])
        assertEquals(listOf("EMT-0", "EMT-1328"), payload.rootTree)

        val request = server.takeRequest()
        assertEquals("/api/v1/hrm/v2.users.list", request.path)
        assertEquals("tok-1", request.getHeader("X-Auth-Token"))
        assertEquals("u-1", request.getHeader("X-User-Id"))
    }

    @Test
    fun `refresh tolerates nested data envelope`() = runBlocking {
        val repo = repo()
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"userMap":{"u2":{"username":"li.si"}},"departmentMap":{},"rootTree":[]}}""",
            ),
        )

        ContactsStore.reset()
        repo.refresh()

        assertEquals(ContactsPhase.LOADED, ContactsStore.phase.value)
        assertEquals("li.si", ContactsStore.payload.value.userMap.getValue("u2").username)
    }

    @Test
    fun `refresh failure sets LOAD_ERROR phase`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        ContactsStore.reset()
        repo.refresh()

        assertEquals(ContactsPhase.LOAD_ERROR, ContactsStore.phase.value)
        assertTrue(ContactsStore.payload.value.userMap.isEmpty())
    }

    @Test
    fun `refreshIfUnloaded skips when already loaded`() = runBlocking {
        val repo = repo()
        server.enqueue(
            MockResponse().setBody("""{"userMap":{},"departmentMap":{},"rootTree":[]}"""),
        )
        ContactsStore.reset()
        repo.refresh() // LOADED
        assertEquals(1, server.requestCount)

        repo.refreshIfUnloaded() // 已 LOADED → 不再发请求

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refreshIfUnloaded fetches on UNLOAD and swallows failure`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        ContactsStore.reset()

        repo.refreshIfUnloaded() // 失败吞掉（RN useContacts .catch(() => {}) 同义）

        assertEquals(1, server.requestCount)
        assertEquals(ContactsPhase.LOAD_ERROR, ContactsStore.phase.value)
    }

    @Test
    fun `reset returns phase to UNLOAD and clears payload`() = runBlocking {
        val repo = repo()
        server.enqueue(MockResponse().setBody("""{"userMap":{"u9":{"username":"x"}}}"""))
        ContactsStore.reset()
        repo.refresh()
        assertEquals(ContactsPhase.LOADED, ContactsStore.phase.value)

        ContactsStore.reset()

        assertEquals(ContactsPhase.UNLOAD, ContactsStore.phase.value)
        assertTrue(ContactsStore.payload.value.userMap.isEmpty())
    }
}
