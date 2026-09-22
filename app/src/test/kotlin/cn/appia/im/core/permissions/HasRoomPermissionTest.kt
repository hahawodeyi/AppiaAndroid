package cn.appia.im.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hasRoomPermission 纯函数测试（RN hasRoomPermission.test.ts 移植 + 映射合并/兜底/角色并集/移除房主排除扩面）。
 */
class HasRoomPermissionTest {

    // ---- RN hasRoomPermission.test.ts 移植（remove-user 两例）----

    @Test
    fun `global admin allowed without room owner or moderator role`() {
        // RN: allows global admin without room owner/moderator role
        assertTrue(
            hasRoomPermission("remove-user", emptySet(), setOf("admin"), getDefaultPermissionMapping()),
        )
    }

    @Test
    fun `regular user denied without privileged roles`() {
        // RN: denies regular user without privileged roles
        assertFalse(
            hasRoomPermission("remove-user", emptySet(), setOf("user"), getDefaultPermissionMapping()),
        )
    }

    // ---- 默认映射逐条（RN getDefaultPermissionMapping :46-55 对照）----

    @Test
    fun `default mapping matches RN entries exactly`() {
        val m = getDefaultPermissionMapping()
        assertEquals(listOf("owner"), m["set-owner"])
        assertEquals(listOf("owner", "moderator"), m["set-moderator"])
        assertEquals(listOf("owner"), m["set-ghost-owner"])
        assertEquals(listOf("owner", "moderator"), m["set-leader"])
        assertEquals(listOf("owner"), m["set-pdt"])
        assertEquals(listOf("owner", "moderator", "admin"), m["remove-user"])
        assertEquals(listOf("owner"), m["edit-team-member"])
        assertEquals(listOf("owner", "moderator"), m["mute-user"])
    }

    // ---- 兜底：mapping 缺失走 getDefaultPermissionMapping ----

    @Test
    fun `null mapping falls back to default mapping`() {
        assertTrue(hasRoomPermission("set-owner", setOf("owner"), emptySet(), null))
        assertFalse(hasRoomPermission("set-owner", setOf("moderator"), emptySet(), null))
    }

    @Test
    fun `empty mapping entry denies even with matching role`() {
        // RN :124-125：allowedRoles 空/缺失 → false（不落兜底，映射已显式给出）
        val m = mapOf("remove-user" to emptyList<String>())
        assertFalse(hasRoomPermission("remove-user", setOf("owner"), setOf("admin"), m))
    }

    @Test
    fun `unknown permission key denies`() {
        assertFalse(hasRoomPermission("no-such-permission", setOf("owner"), setOf("admin"), null))
    }

    // ---- 角色并集（房间角色 + 全局角色合并）----

    @Test
    fun `room role alone grants when global empty`() {
        assertTrue(hasRoomPermission("set-moderator", setOf("moderator"), emptySet(), null))
    }

    @Test
    fun `global role alone grants when room roles empty`() {
        // RN remove-user 允许全局 admin（对齐旧版 hasPermission 合并语义）
        assertTrue(hasRoomPermission("remove-user", emptySet(), setOf("admin"), null))
    }

    @Test
    fun `server mapping overrides default`() {
        val m = mapOf("remove-user" to listOf("owner"))
        // 服务端收紧后全局 admin 不再放行
        assertFalse(hasRoomPermission("remove-user", emptySet(), setOf("admin"), m))
        assertTrue(hasRoomPermission("remove-user", setOf("owner"), emptySet(), m))
    }

    // ---- 移除房主排除（RN roomMemberActions.ts:69-71 消费侧语义：owner 行不可移除）----

    @Test
    fun `remove-user permission does not depend on target member roles`() {
        // hasRoomPermission 只判当前用户；「目标是否 owner」的排除在消费侧 canRemoveMemberRow（T5），
        // 纯函数层验证：当前用户有权限即 true，与目标无关
        val m = getDefaultPermissionMapping()
        assertTrue(hasRoomPermission("remove-user", setOf("moderator"), setOf("user"), m))
    }

    @Test
    fun `merged roles dedupe does not affect intersection`() {
        // 房间与全局同角色（owner 重复）——Set 合并去重后交集判定不变
        assertTrue(hasRoomPermission("set-owner", setOf("owner"), setOf("owner"), null))
    }

    // ---- List 重载等价 ----

    @Test
    fun `list overload matches set overload`() {
        assertTrue(hasRoomPermission("edit-room", listOf("moderator"), listOf("user")))
        assertFalse(hasRoomPermission("edit-room", listOf("member"), listOf("user")))
    }
}
