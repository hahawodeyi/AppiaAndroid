package cn.appia.im.core.chat

import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M4-T5：buildMemberActionSheetItems 权限全集（RN roomMemberActions.ts 逐行）+
 * canRemoveMemberRow 双层独立判定 + findRoomMemberRoles（getRoomRoles 唯一消费面）+
 * bulkRemoveRoomMembers wire 序列。
 */
class RoomMemberActionsTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun newSdk(): RocketSdk =
        RocketSdk(client = OkHttpClient()).also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }

    private fun member(
        joinType: List<String>? = listOf("user"),
        username: String? = "alice",
    ) = MemberRow(_id = "u1", username = username, name = "Alice", joinType = joinType)

    private var sent = 0
    private var owner = 0
    private var mod = 0
    private var removed = 0

    private fun build(
        m: MemberRow = member(),
        memberRoles: List<String> = emptyList(),
        currentUserRoles: List<String> = listOf("owner"),
        globalRoles: List<String> = emptyList(),
        permissions: Map<String, List<String>>? = null,
        allowRemoveFromRoom: Boolean = true,
    ): List<MemberActionItem> = buildMemberActionSheetItems(
        member = m,
        memberRoles = memberRoles,
        currentUserRoles = currentUserRoles,
        globalRoles = globalRoles,
        permissions = permissions,
        allowRemoveFromRoom = allowRemoveFromRoom,
        onSendMessage = { sent++ },
        onToggleOwner = { owner++ },
        onToggleModerator = { mod++ },
        onRemoveFromRoom = { removed++ },
    )

    // ---- 发消息项（恒有）----

    @Test
    fun `send message always first item regardless of permissions`() {
        val items = build(currentUserRoles = emptyList(), globalRoles = emptyList(), allowRemoveFromRoom = false)
        assertEquals(listOf("roomMembers_sendMessage"), items.map { it.labelKey })
        items[0].onPress()
        assertEquals(1, sent)
    }

    // ---- set-owner 门（权限 + joinType 含 user）----

    @Test
    fun `set owner shown when permitted and user joinType`() {
        val items = build()
        assertTrue(items.any { it.labelKey == "roomMembers_setOwner" })
    }

    @Test
    fun `set owner hidden when joinType lacks user`() {
        val items = build(m = member(joinType = listOf("dep")))
        assertFalse(items.any { it.labelKey.contains("Owner") })
    }

    @Test
    fun `set owner hidden without permission`() {
        val items = build(currentUserRoles = listOf("member"), globalRoles = emptyList())
        assertFalse(items.any { it.labelKey.contains("Owner") })
    }

    @Test
    fun `owner member shows removeOwner label and toggles`() {
        val items = build(memberRoles = listOf("owner"))
        val item = items.first { it.labelKey == "roomMembers_removeOwner" }
        item.onPress()
        assertEquals(1, owner)
    }

    // ---- set-moderator 门（权限 + 非 owner + canGoDirect）----

    @Test
    fun `moderator toggle hidden for owner member`() {
        val items = build(memberRoles = listOf("owner"))
        assertFalse(items.any { it.labelKey.contains("Moderator") })
    }

    @Test
    fun `moderator toggle hidden for colon username`() {
        // RN canGoDirect：username 含 ':' 视为外部用户，不可设 moderator
        val items = build(m = member(username = "ext:remote"))
        assertFalse(items.any { it.labelKey.contains("Moderator") })
    }

    @Test
    fun `moderator toggle labels by current state and fires callback`() {
        val setItems = build(memberRoles = emptyList())
        setItems.first { it.labelKey == "roomMembers_setModerator" }.onPress()
        assertEquals(1, mod)
        val rmItems = build(memberRoles = listOf("moderator"))
        assertTrue(rmItems.any { it.labelKey == "roomMembers_removeModerator" })
    }

    @Test
    fun `moderator toggle needs permission but null username passes canGoDirect`() {
        // username null → canGoDirect true；无权限仍隐藏
        val noPerm = build(m = member(username = null), currentUserRoles = emptyList(), globalRoles = emptyList())
        assertFalse(noPerm.any { it.labelKey.contains("Moderator") })
        val ownerPerm = build(m = member(username = null), memberRoles = emptyList())
        assertTrue(ownerPerm.any { it.labelKey == "roomMembers_setModerator" })
    }

    // ---- remove-user 门（allowRemoveFromRoom + 权限 + 非 owner + joinType）----

    @Test
    fun `remove hidden when allowRemoveFromRoom false`() {
        // RN 部门块内成员 allowRemoveFromRoom=false
        val items = build(allowRemoveFromRoom = false)
        assertFalse(items.any { it.labelKey == "roomMembers_removeFromRoom" })
    }

    @Test
    fun `remove hidden without remove-user permission`() {
        // set-owner 有权（owner）但 remove-user 无权（去掉 admin/moderator）
        val items = build(currentUserRoles = listOf("owner"), permissions = mapOf("remove-user" to listOf("admin")))
        assertFalse(items.any { it.labelKey == "roomMembers_removeFromRoom" })
        assertTrue(items.any { it.labelKey == "roomMembers_setOwner" })
    }

    @Test
    fun `remove shown and fires callback when all gates pass`() {
        val items = build()
        items.first { it.labelKey == "roomMembers_removeFromRoom" }.onPress()
        assertEquals(1, removed)
    }

    // ---- mapping null 兜底（T2 binding ①）----

    @Test
    fun `empty store roles entry falls back to default mapping`() {
        // permissions store 键命中但 roles 空 → 传 null → getDefaultPermissionMapping 兜底
        // set-owner 兜底 = [owner]，currentUserRoles 含 owner → 仍显示
        val items = build(permissions = mapOf("set-owner" to emptyList()))
        assertTrue(items.any { it.labelKey == "roomMembers_setOwner" })
    }

    @Test
    fun `store mapping hit overrides default`() {
        // 自定义 set-owner 映射 [custom-role]，owner 不再命中
        val items = build(permissions = mapOf("set-owner" to listOf("custom-role")))
        assertFalse(items.any { it.labelKey.contains("Owner") })
        val custom = build(
            currentUserRoles = listOf("custom-role"),
            permissions = mapOf("set-owner" to listOf("custom-role")),
        )
        assertTrue(custom.any { it.labelKey == "roomMembers_setOwner" })
    }

    // ---- canRemoveMemberRow（本地判定，与服务端权限独立）----

    @Test
    fun `canRemoveMemberRow excludes owner rows`() {
        assertFalse(canRemoveMemberRow(member(), listOf("owner")))
    }

    @Test
    fun `canRemoveMemberRow requires user joinType`() {
        assertTrue(canRemoveMemberRow(member(), emptyList()))                       // joinType=[user]
        assertFalse(canRemoveMemberRow(member(joinType = null), emptyList()))       // null joinType
        assertFalse(canRemoveMemberRow(member(joinType = listOf("dep")), emptyList()))
        assertFalse(canRemoveMemberRow(member(joinType = listOf("dep", "user")), listOf("owner")))
        assertTrue(canRemoveMemberRow(member(joinType = listOf("dep", "user")), emptyList()))
    }

    // ---- findRoomMemberRoles（getRoomRoles 唯一消费面）----

    @Test
    fun `findRoomMemberRoles finds by uid and returns empty when absent`() {
        val raw = Json.parseToJsonElement(
            """{"roles":[{"u":{"_id":"u1"},"roles":["owner"]},{"u":{"_id":"u2"},"roles":["moderator"]}]}""",
        )
        assertEquals(listOf("owner"), findRoomMemberRoles(raw, "u1"))
        assertEquals(listOf("moderator"), findRoomMemberRoles(raw, "u2"))
        assertEquals(emptyList<String>(), findRoomMemberRoles(raw, "u3"))
        assertEquals(emptyList<String>(), findRoomMemberRoles(null, "u1"))
    }

    /**
     * 评审 Bug-1 证明：canRemoveFromRoom 派生链（findRoomMemberRoles → hasRoomPermission）
     * 在角色异步到达前后重算——进页时 raw=null（角色在途）→ false；raw 到达含 owner → true。
     * 屏内为派生值直算（无 effect 快照），本用例钉死该纯函数链的时序行为。
     */
    @Test
    fun `derived canRemove recomputes when roles arrive async`() {
        val currentUserId = "me"
        val globalRoles = emptyList<String>()
        val mapping: Map<String, List<String>>? = null // store 键未命中 → null 兜底

        // 阶段 1：getRoomRoles 在途（raw=null）→ 空角色 → remove-user 兜底映射不命中 → false
        val rolesInFlight = findRoomMemberRoles(null, currentUserId)
        assertFalse(
            cn.appia.im.core.permissions.hasRoomPermission("remove-user", rolesInFlight, globalRoles, mapping),
        )

        // 阶段 2：角色到达（me=owner）→ 同链重算 → true（部门行移除动作出现）
        val arrived = Json.parseToJsonElement(
            """{"roles":[{"u":{"_id":"me"},"roles":["owner"]}]}""",
        )
        val rolesArrived = findRoomMemberRoles(arrived, currentUserId)
        assertTrue(
            cn.appia.im.core.permissions.hasRoomPermission("remove-user", rolesArrived, globalRoles, mapping),
        )
    }

    // ---- bulkRemove wire（并行 per-user kick + 单次 removeDepartment）----

    @Test
    fun `bulkRemove kicks each user then posts one removeDepartment`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"success":true}""")) }
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        bulkRemoveRoomMembers(newSdk(), "r1", "c", listOf("u1", "u2"), listOf("d1", "d2"))

        assertEquals(3, server.requestCount)
        // 并行 kick：两请求路径同 endpoint（到达顺序不保证）；dep 单调用合一 depIds
        val reqs = (1..3).map { server.takeRequest()!! }
        assertEquals(
            listOf("/api/v1/channels.kick", "/api/v1/channels.kick", "/api/v1/local.removeGroupUsersToRoom").sorted(),
            reqs.map { it.path.orEmpty() }.sorted(),
        )
        assertEquals(
            """{"rid":"r1","depIds":["d1","d2"]}""",
            reqs.first { it.path == "/api/v1/local.removeGroupUsersToRoom" }.body.readUtf8(),
        )
    }

    @Test
    fun `bulkRemove with no deps skips department call`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        bulkRemoveRoomMembers(newSdk(), "r1", "c", listOf("u1"), emptyList())
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/channels.kick", server.takeRequest().path)
    }
}
