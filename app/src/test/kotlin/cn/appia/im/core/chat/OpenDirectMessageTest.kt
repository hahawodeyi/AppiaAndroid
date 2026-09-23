package cn.appia.im.core.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DM 三段链（RN openDirectMessage.test.ts 8 用例移植 + Android DAO 实库版）：
 * knownRid 校验 → 本地 chats 扫描 → im.create；knownRid=username 回退忽略、
 * 多人直聊排除、im.create 无 rid / 异常 → null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenDirectMessageTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient())
            .also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    /** RN localChat fixture：t='d'、usernames JSON 含 alice+bob、_id=rid。 */
    private fun dmRow(
        id: String,
        usernames: String? = """["alice","bob"]""",
        uids: String? = null,
        t: String = "d",
    ) = ChatEntity(
        _id = id,
        f = false,
        t = t,
        ts = 0.0,
        ls = 0.0,
        name = "Bob",
        fname = "",
        rid = id,
        open = true,
        alert = false,
        unread = 0.0,
        user_mentions = 0.0,
        group_mentions = 0.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        team_id = "",
        usernames = usernames,
        uids = uids,
    )

    // ── 段 1：knownRid 校验 ──

    @Test
    fun `knownRid adopted when local DM exists and skips im create`() = runBlocking {
        db.chatDao().insert(dmRow("room-known"))
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "bob", knownRid = "room-known")
        assertEquals("room-known", rid)
        assertEquals(0, server.requestCount) // 未触网
    }

    @Test
    fun `knownRid ignored when it is the username fallback`() = runBlocking {
        // 本地无 DM；knownRid == username（spotlight 回退形态）→ 直落 im.create
        server.enqueue(
            MockResponse().setBody("""{"success":true,"room":{"_id":"room-new","rid":"room-new","t":"d"}}"""),
        )
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "charlie", knownRid = "charlie")
        assertEquals("room-new", rid)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/im.create", req.path)
        assertTrue(req.body.readUtf8().contains("\"username\":\"charlie\""))
    }

    @Test
    fun `knownRid rejected when local row is group direct chat`() = runBlocking {
        // 多人直聊（uids 3 人）——knownRid 命中该行但 isGroupChat 排除 → 回退 im.create
        db.chatDao().insert(dmRow("room-group", uids = """["a","b","c"]"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"room":{"_id":"room-new"}}"""))
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "bob", knownRid = "room-group")
        assertEquals("room-new", rid)
    }

    @Test
    fun `knownRid rejected when row usernames lack target user`() = runBlocking {
        db.chatDao().insert(dmRow("room-x", usernames = """["carol","dave"]"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"room":{"_id":"room-new"}}"""))
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "bob", knownRid = "room-x")
        assertEquals("room-new", rid)
    }

    // ── 段 2：本地 chats 扫描 ──

    @Test
    fun `resolves local DM rid by full scan when no knownRid`() = runBlocking {
        db.chatDao().insert(dmRow("room-123"))
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "bob")
        assertEquals("room-123", rid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `scan skips non-direct and group rows`() = runBlocking {
        db.chatDao().insert(dmRow("room-c", t = "c")) // 非直聊（getDirects 不返回）
        db.chatDao().insert(dmRow("room-group", uids = """["a","b","c"]""")) // 多人直聊
        server.enqueue(MockResponse().setBody("""{"success":true,"room":{"_id":"room-new"}}"""))
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "bob")
        assertEquals("room-new", rid)
    }

    // ── 段 3：im.create ──

    @Test
    fun `calls im create when no local chat`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"success":true,"room":{"_id":"room-new","rid":"room-alt"}}"""),
        )
        val rid = resolveDirectChatRid(db.chatDao(), newSdk(), "charlie")
        assertEquals("room-new", rid) // room._id 优先（RN `?? room.rid`）
    }

    @Test
    fun `returns null when im create has no rid`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"room":{}}"""))
        assertNull(resolveDirectChatRid(db.chatDao(), newSdk(), "dave"))
    }

    @Test
    fun `returns null when im create throws`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertNull(resolveDirectChatRid(db.chatDao(), newSdk(), "eve"))
    }

    @Test
    fun `returns null when username is blank`() = runBlocking {
        assertNull(resolveDirectChatRid(db.chatDao(), newSdk(), "   "))
        assertEquals(0, server.requestCount)
    }

    // ── 纯函数单元 ──

    @Test
    fun `directChatIncludesUsername parses json array and falls back to substring`() {
        assertTrue(directChatIncludesUsername("""["alice","bob"]""", "bob"))
        assertTrue(directChatIncludesUsername("not-json bob", "bob")) // 解析失败回退子串
        assertFalse(directChatIncludesUsername("""["alice","carol"]""", "bob"))
        assertFalse(directChatIncludesUsername(null, "bob"))
    }

    @Test
    fun `isGroupDirectChat checks uids and usernames length over 2`() {
        assertTrue(isGroupDirectChat(dmRow("g1", uids = """["a","b","c"]""")))
        assertTrue(isGroupDirectChat(dmRow("g2", usernames = """["a","b","c"]""")))
        assertFalse(isGroupDirectChat(dmRow("g3")))
    }

    @Test
    fun `parseDirectRoomRid prefers _id then rid`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"room":{"_id":"r1","rid":"r2"}}""",
        )
        assertEquals("r1", parseDirectRoomRid(json))
        val json2 = kotlinx.serialization.json.Json.parseToJsonElement("""{"room":{"rid":"r2"}}""")
        assertEquals("r2", parseDirectRoomRid(json2))
        assertNull(parseDirectRoomRid(kotlinx.serialization.json.Json.parseToJsonElement("""{"room":{}}""")))
        assertNull(parseDirectRoomRid(null))
    }
}
