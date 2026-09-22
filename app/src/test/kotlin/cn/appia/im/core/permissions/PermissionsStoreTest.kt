package cn.appia.im.core.permissions

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.PermissionsApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PermissionsStore + PermissionsApi 测试：
 * store CRUD / permissions-changed 帧消费（RN session.ts:107-126 逐行为）/
 * listAll 同步（RN syncPermissions.ts 逐行为：tracked 过滤/remove 任意 id/双空不动/失败保留）。
 */
class PermissionsStoreTest {

    private val server = MockWebServer()
    private var listAllBody: String? = null

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/v1/permissions.listAll") ->
                        listAllBody?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        PermissionsStore.reset()
    }

    @After
    fun tearDown() {
        PermissionsStore.reset()
        runCatching { server.shutdown() }
    }

    private fun sdk(): RocketSdk = RocketSdk(client = OkHttpClient()).also {
        it.hydrateRestSession(server.url("/").toString(), "tok", "uid")
    }

    private fun frame(eventName: String, argsJson: String): kotlinx.serialization.json.JsonElement =
        Json.parseToJsonElement(
            "{\"msg\":\"changed\",\"collection\":\"stream-notify-logged\",\"id\":\"evt\"," +
                "\"fields\":{\"eventName\":\"$eventName\",\"args\":$argsJson}}",
        ).jsonObject

    // ---- store 基础（RN permissionsStore.ts 四动作）----

    @Test
    fun `setPermissionsMap overwrites and updatePermission upserts`() {
        PermissionsStore.setPermissionsMap(mapOf("edit-room" to listOf("owner")))
        assertEquals(listOf("owner"), PermissionsStore.getPermissionRoles("edit-room"))

        PermissionsStore.updatePermission("edit-room", listOf("admin"))
        PermissionsStore.updatePermission("remove-user", listOf("owner", "moderator"))
        assertEquals(listOf("admin"), PermissionsStore.getPermissionRoles("edit-room"))
        assertEquals(listOf("owner", "moderator"), PermissionsStore.getPermissionRoles("remove-user"))
    }

    @Test
    fun `reset clears all entries`() {
        PermissionsStore.updatePermission("edit-room", listOf("owner"))
        PermissionsStore.reset()
        assertNull(PermissionsStore.getPermissionRoles("edit-room"))
        assertTrue(PermissionsStore.permissions.value.isEmpty())
    }

    // ---- permissions-changed 帧消费（RN session.ts:107-126）----

    @Test
    fun `permissions-changed frame upserts id and roles`() {
        PermissionsStore.applyPermissionsChangedFrame(
            frame("permissions-changed", """["updated",{"_id":"edit-room","roles":["owner","admin"]}]"""),
        )
        assertEquals(listOf("owner", "admin"), PermissionsStore.getPermissionRoles("edit-room"))
    }

    @Test
    fun `frame roles filter non-string entries`() {
        PermissionsStore.applyPermissionsChangedFrame(
            frame("permissions-changed", """["updated",{"_id":"remove-user","roles":["owner",123,null,"admin"]}]"""),
        )
        assertEquals(listOf("owner", "admin"), PermissionsStore.getPermissionRoles("remove-user"))
    }

    @Test
    fun `frame without tracked id still lands (RN no tracked filter)`() {
        // RN handleStreamNotifyLogged 不筛 PERMISSION_IDS_TO_TRACK——任意权限 id 都 upsert
        PermissionsStore.applyPermissionsChangedFrame(
            frame("permissions-changed", """["updated",{"_id":"view-statistics","roles":["admin"]}]"""),
        )
        assertEquals(listOf("admin"), PermissionsStore.getPermissionRoles("view-statistics"))
    }

    @Test
    fun `malformed frames are ignored silently`() {
        val cases = listOf(
            frame("other-event", """["updated",{"_id":"x","roles":[]}]"""), // eventName 不含 permissions-changed
            frame("permissions-changed", """["updated"]"""), // args < 2
            frame("permissions-changed", """["updated","not-an-object"]"""), // args[1] 非对象
            frame("permissions-changed", """["updated",{"roles":["owner"]}]"""), // 无 _id
            frame("permissions-changed", """["updated",{"_id":"x"}]"""), // roles 非数组
        )
        for (c in cases) PermissionsStore.applyPermissionsChangedFrame(c)
        assertTrue(PermissionsStore.permissions.value.isEmpty())
    }

    // ---- listAll 同步（RN syncPermissions.ts）----

    @Test
    fun `sync writes only tracked ids from update list`() = runBlocking {
        listAllBody = """
            {"success":true,"update":[
              {"_id":"edit-room","roles":["owner","moderator"]},
              {"_id":"add-user-to-joined-room","roles":["owner"]},
              {"_id":"untracked-permission","roles":["admin"]},
              {"_id":"set-owner"}
            ]}
        """.trimIndent()

        val written = PermissionsApi.syncPermissions(sdk())

        assertEquals(2, written)
        assertEquals(listOf("owner", "moderator"), PermissionsStore.getPermissionRoles("edit-room"))
        assertEquals(listOf("owner"), PermissionsStore.getPermissionRoles("add-user-to-joined-room"))
        assertNull(PermissionsStore.getPermissionRoles("untracked-permission")) // 不在清单
        assertNull(PermissionsStore.getPermissionRoles("set-owner")) // roles 非数组不写（RN :33-35）
    }

    @Test
    fun `sync remove deletes any id without tracked filter`() = runBlocking {
        PermissionsStore.setPermissionsMap(
            mapOf(
                "edit-room" to listOf("owner"),
                "view-statistics" to listOf("admin"), // 不在 tracked 清单也可被 remove 删（RN :38-41）
            ),
        )
        listAllBody = """
            {"success":true,"update":[],"remove":[{"_id":"edit-room"},{"_id":"view-statistics"}]}
        """.trimIndent()

        PermissionsApi.syncPermissions(sdk())

        assertNull(PermissionsStore.getPermissionRoles("edit-room"))
        assertNull(PermissionsStore.getPermissionRoles("view-statistics"))
    }

    @Test
    fun `sync with empty update and remove keeps store untouched`() = runBlocking {
        PermissionsStore.setPermissionsMap(mapOf("edit-room" to listOf("owner")))
        listAllBody = """{"success":true,"update":[],"remove":[]}"""

        PermissionsApi.syncPermissions(sdk())

        assertEquals(listOf("owner"), PermissionsStore.getPermissionRoles("edit-room"))
    }

    @Test
    fun `sync failure keeps existing cache (fallback)`() = runBlocking {
        PermissionsStore.setPermissionsMap(mapOf("edit-room" to listOf("owner")))
        listAllBody = null // 404 → 异常 → 保留缓存

        PermissionsApi.syncPermissions(sdk())

        assertEquals(listOf("owner"), PermissionsStore.getPermissionRoles("edit-room"))
    }

    @Test
    fun `sync with success false is a no-op`() = runBlocking {
        listAllBody = """{"success":false,"update":[{"_id":"edit-room","roles":["owner"]}]}"""

        PermissionsApi.syncPermissions(sdk())

        assertTrue(PermissionsStore.permissions.value.isEmpty())
    }
}
