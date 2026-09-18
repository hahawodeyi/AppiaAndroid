package cn.appia.im.core.network.api

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 已读回执三端点 wire 对照（appiaMobile `src/services/api/readReceipts.ts` / `roomFirstUnread.ts`）：
 * - `GET chat.getMessageReadReceipts?messageId` → `{receipts:[{_id,userId,user:{username,name}}]}`
 * - `GET appia/room/members?rid` → `{success,data:[{members:[...]}]}`（RN 同文件 :14-22）
 * - `GET room.firsUnread?rid` → `{success,message?:{_id},unread}`（**端点拼写保留**，RN :10）
 */
class ReadReceiptsApiTest {
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** hydrateRestSession 直接注入 REST 会话（无登录/无 DDP，同 ReactionApiTest）。 */
    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    @Test
    fun `getMessageReadReceipts requests by messageId and parses receipts`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"receipts":[{"_id":"rc1","roomId":"r1","userId":"u1","messageId":"m1",""" +
                    """"ts":"2026-06-03T00:00:00.000Z","user":{"_id":"u1","username":"alice","name":"Alice"}}]}""",
            ),
        )
        val receipts = ReadReceiptsApi.getMessageReadReceipts(newSdk(), "m1")

        val req = server.takeRequest()
        assertEquals("/api/v1/chat.getMessageReadReceipts", req.path?.substringBefore("?"))
        assertEquals("GET", req.method)
        assertTrue(req.path!!.contains("messageId=m1"))
        assertEquals(1, receipts.size)
        assertEquals("rc1", receipts[0]._id)
        assertEquals("u1", receipts[0].userId)
        assertEquals("alice", receipts[0].user?.username)
        assertEquals("Alice", receipts[0].user?.name)
    }

    @Test
    fun `getMessageReadReceipts missing receipts yields empty list`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(ReadReceiptsApi.getMessageReadReceipts(newSdk(), "m2").isEmpty())
    }

    @Test
    fun `getFederatedRoomMembers requests appia room members by rid`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"data":[{"members":[{"_id":"u1","username":"alice","name":"Alice"},""" +
                    """{"_id":"u2","username":"bob"}]}]}""",
            ),
        )
        val res = ReadReceiptsApi.getFederatedRoomMembers(newSdk(), "GENERAL")

        val req = server.takeRequest()
        assertEquals("/api/v1/appia/room/members", req.path?.substringBefore("?"))
        assertTrue(req.path!!.contains("rid=GENERAL"))
        assertTrue(res.success)
        assertEquals(listOf("Alice", null), res.data.flatMap { it.members }.map { it.name })
    }

    @Test
    fun `getFederatedRoomMembers non success keeps empty data`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":false}"""))
        val res = ReadReceiptsApi.getFederatedRoomMembers(newSdk(), "GENERAL")
        assertFalse(res.success)
        assertTrue(res.data.isEmpty())
    }

    @Test
    fun `getFirstUnread keeps firsUnread endpoint spelling and parses count`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"message":{"_id":"msg9"},"unread":12}"""))
        val res = ReadReceiptsApi.getFirstUnread(newSdk(), "GENERAL")

        val req = server.takeRequest()
        assertEquals("/api/v1/room.firsUnread", req.path?.substringBefore("?"))
        assertTrue(req.path!!.contains("rid=GENERAL"))
        assertTrue(res.success)
        assertEquals("msg9", res.messageId)
        assertEquals(12, res.unread)
    }

    @Test
    fun `getFirstUnread defaults success false unread zero`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val res = ReadReceiptsApi.getFirstUnread(newSdk(), "GENERAL")
        assertFalse(res.success)
        assertNull(res.messageId)
        assertEquals(0, res.unread)
    }
}
