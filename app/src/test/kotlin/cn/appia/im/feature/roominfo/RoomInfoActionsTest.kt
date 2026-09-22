package cn.appia.im.feature.roominfo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.permissions.PermissionsStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * M4-T4：mute 乐观回滚 / pin 服务端后本地 / usage wire+本地写 / 槽位计算 / usage 门 /
 * canEditRoomSettings 组合（T2 binding ①/②）/ leaveRoom wire / pendingSelfLeave 标记。
 * Robolectric + 内存 Room + MockWebServer（RecallActionsTest 同构）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomInfoActionsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppiaDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        PermissionsStore.reset()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        db.close()
        PermissionsStore.reset()
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private suspend fun insertChat(
        hideUnread: Boolean? = null,
        disableNotifications: Boolean? = null,
        f: Boolean = false,
        roles: String? = null,
        prid: String? = null,
        t: String = "c",
        appiaUsage: String? = null,
    ): ChatEntity {
        val row = ChatEntity(
            _id = "r1", f = f, t = t, ts = 0.0, ls = 0.0, name = "n", fname = "fn", rid = "r1",
            open = true, alert = false, unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
            room_updated_at = 0.0, ro = false, archived = false, auto_translate_language = "en",
            team_id = "", roles = roles, prid = prid, appiaUsage = appiaUsage,
            hide_unread_status = hideUnread, disable_notifications = disableNotifications,
        )
        db.chatDao().insert(row)
        return row
    }

    // ---- setRoomMuted（RN setRoomMutedOnServerAndLocal :25-51 乐观 + 回滚）----

    @Test
    fun `mute writes local first then posts saveNotification`() = runBlocking {
        insertChat()
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomMuted("r1", true)

        val row = db.chatDao().getById("r1")!!
        assertTrue(row.hide_unread_status == true)
        assertTrue(row.disable_notifications == true)
        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.saveNotification", req.path)
        assertEquals(
            """{"roomId":"r1","notifications":{"disableNotifications":"1","hideUnreadStatus":"1"}}""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun `unmute posts zeros and clears local columns`() = runBlocking {
        insertChat(hideUnread = true, disableNotifications = true)
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomMuted("r1", false)

        val row = db.chatDao().getById("r1")!!
        assertEquals(false, row.hide_unread_status)
        assertEquals(false, row.disable_notifications)
        assertEquals(
            """{"roomId":"r1","notifications":{"disableNotifications":"0","hideUnreadStatus":"0"}}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `mute failure rolls back previous local values and rethrows`() = runBlocking {
        insertChat(hideUnread = true, disableNotifications = false)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false}"""))
        var thrown: Exception? = null
        try {
            RoomInfoActions(newSdk(), db).setRoomMuted("r1", true)
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown != null) // 异常上抛（RN throw error）
        val row = db.chatDao().getById("r1")!!
        // 回滚旧值（RN :45-48 previousHide/previousDisable）
        assertEquals(true, row.hide_unread_status)
        assertEquals(false, row.disable_notifications)
    }

    @Test
    fun `mute on missing chat row posts without local write`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomMuted("r1", true)
        assertNull(db.chatDao().getById("r1")) // 行不存在静默跳过本地写
        assertEquals("/api/v1/rooms.saveNotification", server.takeRequest().path)
    }

    // ---- setRoomPinned（RN :53-62 先服务端后本地）----

    @Test
    fun `pin posts favorite then writes chats f`() = runBlocking {
        insertChat(f = false)
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).setRoomPinned("r1", true)

        assertTrue(db.chatDao().getById("r1")!!.f)
        val req = server.takeRequest()
        assertEquals("/api/v1/rooms.favorite", req.path)
        assertEquals("""{"roomId":"r1","favorite":true}""", req.body.readUtf8())
    }

    @Test
    fun `pin failure keeps f and rethrows`() = runBlocking {
        insertChat(f = false)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false}"""))
        var thrown: Exception? = null
        try {
            RoomInfoActions(newSdk(), db).setRoomPinned("r1", true)
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertFalse(db.chatDao().getById("r1")!!.f) // 本地零改动
    }

    // ---- setRoomUsage（RN setRoomAppiaUsage :23-29）----

    @Test
    fun `setRoomUsage posts saveRoomSettings then writes appiaUsage json`() = runBlocking {
        insertChat()
        // method.call 信封（T3 envelope 形态）
        val inner = """{"jsonrpc":"2.0","message":"mid","result":{"result":true,"rid":"r1"}}"""
        server.enqueue(
            MockResponse().setBody(
                """{"message":${kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.json.JsonPrimitive.serializer(),
                    kotlinx.serialization.json.JsonPrimitive(inner),
                )}}""",
            ),
        )
        RoomInfoActions(newSdk(), db).setRoomUsage("r1", listOf("Room_Sort_COP"))

        val req = server.takeRequest()
        assertEquals("/api/v1/method.call/saveRoomSettings", req.path)
        val frame = kotlinx.serialization.json.Json.parseToJsonElement(
            kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8())
                .let { it as kotlinx.serialization.json.JsonObject }["message"]!!
                .let { m -> (m as kotlinx.serialization.json.JsonPrimitive).content },
        ).let { it as kotlinx.serialization.json.JsonObject }
        val params = frame["params"]!!.let { it as kotlinx.serialization.json.JsonArray }
        assertEquals("r1", params[0].let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(
            """["Room_Sort_COP"]""",
            (params[1] as kotlinx.serialization.json.JsonObject)["appiaUsage"].toString(),
        )
        // 本地写 JSON 数组串
        assertEquals("""["Room_Sort_COP"]""", db.chatDao().getById("r1")!!.appiaUsage)
    }

    // ---- leaveRoom（T3 wire 复验）----

    @Test
    fun `leaveRoom maps roomType prefix`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        RoomInfoActions(newSdk(), db).leaveRoom("r1", "p")
        val req = server.takeRequest()
        assertEquals("/api/v1/groups.leave", req.path)
        assertEquals("""{"roomId":"r1"}""", req.body.readUtf8())
    }

    // ---- 槽位计算（RN slotsForMembers :182-187）----

    @Test
    fun `memberDisplaySlots subtracts add and remove slots from 20`() {
        assertEquals(20, memberDisplaySlots(showAdd = false, showRemove = false))
        assertEquals(19, memberDisplaySlots(showAdd = true, showRemove = false))
        assertEquals(18, memberDisplaySlots(showAdd = true, showRemove = true))
    }

    // ---- usage 门与解析（RN roomUsageOptions / parseAppiaUsage）----

    @Test
    fun `canEditRoomUsage only allows c and p`() {
        assertTrue(canEditRoomUsage("c"))
        assertTrue(canEditRoomUsage("p"))
        assertFalse(canEditRoomUsage("d"))
        assertFalse(canEditRoomUsage("l"))
    }

    @Test
    fun `parseAppiaUsageList handles json array single key and bad json`() {
        assertEquals(listOf("Room_Sort_COP", "Room_Sort_Organize"), parseAppiaUsageList("""["Room_Sort_COP","Room_Sort_Organize"]"""))
        assertEquals(listOf("Room_Sort_Others"), parseAppiaUsageList("Room_Sort_Others"))
        assertEquals(listOf("""["broken"""), parseAppiaUsageList("""["broken""")) // 坏 JSON 数组串回退原文
        assertEquals(emptyList<String>(), parseAppiaUsageList(null))
        assertEquals(emptyList<String>(), parseAppiaUsageList("  "))
    }

    @Test
    fun `resolveAppiaUsageKeys falls back to default when empty`() {
        assertEquals(listOf("Room_Sort_COP"), resolveAppiaUsageKeys("""["Room_Sort_COP"]"""))
        assertEquals(listOf(DEFAULT_ROOM_USAGE_KEY), resolveAppiaUsageKeys(null))
        assertEquals(listOf(DEFAULT_ROOM_USAGE_KEY), resolveAppiaUsageKeys("[]"))
    }

    // ---- canEditRoomSettings（T2 binding ②：chat null false / prid true / edit-room 权限）----

    @Test
    fun `canEditRoomSettings false when chat missing`() {
        assertFalse(canEditRoomSettings(null, emptyList(), null))
    }

    @Test
    fun `canEditRoomSettings true for discussion room regardless of roles`() = runBlocking {
        val chat = insertChat(prid = "parent")
        assertTrue(canEditRoomSettings(chat, emptyList(), null))
    }

    @Test
    fun `canEditRoomSettings uses room roles plus global roles against edit-room mapping`() = runBlocking {
        val owner = insertChat(roles = """["owner"]""")
        val member = insertChat(roles = """["member"]""")

        // 兜底映射（mapping null → getDefaultPermissionMapping：edit-room=[owner,moderator,admin]）
        assertTrue(canEditRoomSettings(owner, emptyList(), null))
        assertFalse(canEditRoomSettings(member, emptyList(), null))
        // 全局角色并集（RN :128 mergedRoles）
        assertTrue(canEditRoomSettings(member, listOf("admin"), null))
        // 服务端映射命中：自定义 roles 清单
        assertTrue(canEditRoomSettings(member, emptyList(), mapOf("edit-room" to listOf("member"))))
        // store 键未命中 → 传 null 走兜底（binding ①：空 roles 条目不提前 false）
        val emptyRolesStore = mapOf("edit-room" to emptyList<String>())
        assertTrue(canEditRoomSettings(owner, emptyList(), emptyRolesStore))
    }

    // ---- pendingSelfLeave 标记（RN markPendingSelfLeave/clearPendingSelfLeaveForRid）----

    @Test
    fun `pendingSelfLeave marks and clears per rid`() {
        markPendingSelfLeave("r1")
        assertTrue(isPendingSelfLeave("r1"))
        assertFalse(isPendingSelfLeave("r2"))
        clearPendingSelfLeaveForRid("r1")
        assertFalse(isPendingSelfLeave("r1"))
    }
}
