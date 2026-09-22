package cn.appia.im.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M4-T5：resolveMemberRoleTag / mergeMemberRoles（RN resolveMemberRoleTag.ts 逐行移植）。
 */
class ResolveMemberRoleTagTest {

    // ---- resolveMemberRoleTag ----

    @Test
    fun `empty roles returns null`() {
        assertNull(resolveMemberRoleTag(emptyList()))
    }

    @Test
    fun `owner wins over everything`() {
        assertEquals(MemberRoleTag.OWNER, resolveMemberRoleTag(listOf("owner")))
        // owner + moderator → owner（RN :6 owner 优先判断）
        assertEquals(MemberRoleTag.OWNER, resolveMemberRoleTag(listOf("moderator", "owner")))
        assertEquals(MemberRoleTag.OWNER, resolveMemberRoleTag(listOf("owner", "admin", "ghost-owner")))
    }

    @Test
    fun `moderator ghost-owner admin map to admin tag`() {
        assertEquals(MemberRoleTag.ADMIN, resolveMemberRoleTag(listOf("moderator")))
        assertEquals(MemberRoleTag.ADMIN, resolveMemberRoleTag(listOf("ghost-owner")))
        assertEquals(MemberRoleTag.ADMIN, resolveMemberRoleTag(listOf("admin")))
        assertEquals(MemberRoleTag.ADMIN, resolveMemberRoleTag(listOf("user", "moderator")))
    }

    @Test
    fun `other roles return null`() {
        assertNull(resolveMemberRoleTag(listOf("user")))
        assertNull(resolveMemberRoleTag(listOf("bot", "leader")))
    }

    // ---- mergeMemberRoles ----

    @Test
    fun `merge dedupes and preserves order`() {
        assertEquals(
            listOf("owner", "moderator"),
            mergeMemberRoles(listOf("owner"), listOf("moderator", "owner")),
        )
    }

    @Test
    fun `merge handles null and empty string entries`() {
        assertEquals(listOf("a", "b"), mergeMemberRoles(null, listOf("a", "b")))
        assertEquals(listOf("a"), mergeMemberRoles(listOf("a"), null))
        assertEquals(emptyList<String>(), mergeMemberRoles(null, null))
        // RN 空串过滤（role falsy 跳过）
        assertEquals(listOf("x"), mergeMemberRoles(listOf("", "x"), listOf("")))
    }
}
