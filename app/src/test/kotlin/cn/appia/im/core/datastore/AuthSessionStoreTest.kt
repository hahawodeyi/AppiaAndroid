package cn.appia.im.core.datastore

import cn.appia.im.core.network.AuthUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AuthSessionStore（fake KvStore）：持久化往返 / isAuthenticated 三字段判定 / 损坏数据回退。
 * 对照 RN stores/authStore.ts:172-189（persist name `auth-storage`、partialize 三字段、
 * merge 同步 isAuthenticated）。
 */
class AuthSessionStoreTest {
    private val kv = InMemoryKvStore()
    private val store get() = AuthSessionStore(kv)

    private val session = AuthSession(
        token = "tok-1",
        user = AuthUser(id = "u-1", username = "bob", name = "Bob"),
        serverUrl = "https://a.cn",
    )

    @Test
    fun `save load round trip across store instances`() {
        store.save(session)

        // 新实例 = 模拟进程重启后从同一 MMKV 读回
        assertEquals(session, AuthSessionStore(kv).load())
        assertTrue(AuthSessionStore(kv).isAuthenticated)
    }

    @Test
    fun `isAuthenticated requires all three fields`() {
        store.save(session)
        assertTrue(store.isAuthenticated)

        // serverUrl 空串（RN 空串 falsy，authStore.ts:187）
        kv.putString("auth-storage", """{"token":"tok-1","user":{"id":"u-1"},"serverUrl":""}""")
        assertFalse(store.isAuthenticated)

        // token 空串
        kv.putString("auth-storage", """{"token":"","user":{"id":"u-1"},"serverUrl":"https://a.cn"}""")
        assertFalse(store.isAuthenticated)

        // user 缺失（RN 持久化 user:null 形态）→ 解不出会话
        kv.putString("auth-storage", """{"token":"tok-1","serverUrl":"https://a.cn"}""")
        assertNull(store.load())
        assertFalse(store.isAuthenticated)
    }

    @Test
    fun `empty or corrupt storage loads null`() {
        assertNull(store.load())
        assertFalse(store.isAuthenticated)

        kv.putString("auth-storage", "not-json{")
        assertNull(store.load())

        kv.putString("auth-storage", "{}")
        assertNull(store.load())
        assertFalse(store.isAuthenticated)
    }

    @Test
    fun `unknown persisted user fields are ignored`() {
        // RN AuthUser 含 appiaQuickReplies 等扩展字段，跨版本持久化不炸
        kv.putString(
            "auth-storage",
            """{"token":"tok-1","user":{"id":"u-1","appiaQuickReplies":"x"},"serverUrl":"https://a.cn"}""",
        )
        assertEquals("u-1", store.load()?.user?.id)
    }

    @Test
    fun `clear wipes session`() {
        store.save(session)

        store.clear()

        assertNull(store.load())
        assertFalse(store.isAuthenticated)
        assertFalse(kv.contains("auth-storage"))
    }

    /** M4-T10 fix Minor-2：currentUsername 缓存生命周期（save 回填 / clear 失效 / 冷启动惰性回填）。 */
    @Test
    fun `currentUsername cache is filled on save invalidated on clear and lazily backfilled`() {
        assertNull(store.currentUsername) // 未登录

        store.save(session)
        assertEquals("bob", store.currentUsername)

        // 冷启动（新实例 = 进程重启恢复）：缓存空 → 惰性回填（只 load 不 save 的路径）
        assertEquals("bob", AuthSessionStore(kv).currentUsername)

        store.clear()
        assertNull(store.currentUsername)
        assertNull(AuthSessionStore(kv).currentUsername) // 清后新实例也不回填
    }

    /** M5-T2：mergeUserRoles 对照 RN authStore.ts:123-127——整体替换 roles（非并集）。 */
    @Test
    fun `mergeUserRoles replaces roles array and updates flow`() {
        store.save(session)
        assertTrue(store.roles.value.isEmpty())

        store.mergeUserRoles("u-1", listOf("admin", "user"))
        assertEquals(listOf("admin", "user"), store.roles.value)
        assertEquals(listOf("admin", "user"), store.load()?.user?.roles)

        // 再合并 = 替换（RN `[...roles]` 整组覆盖，不是 accumulate）
        store.mergeUserRoles("u-1", listOf("leader"))
        assertEquals(listOf("leader"), store.roles.value)
    }

    /** M5-T2 加固：userId 不匹配（组织切换/登出后在途响应）不串写；未登录忽略。 */
    @Test
    fun `mergeUserRoles ignores mismatched user and missing session`() {
        store.save(session)
        store.mergeUserRoles("u-other", listOf("admin"))
        assertTrue(store.roles.value.isEmpty())
        assertNull(store.load()?.user?.roles)

        store.clear()
        store.mergeUserRoles("u-1", listOf("admin"))
        assertNull(store.load())
    }

    /** M5-T2：roles 流生命周期（save 覆盖 / clear 清空 / 冷启动从持久化恢复）。 */
    @Test
    fun `roles flow follows save clear and cold start`() {
        assertTrue(store.roles.value.isEmpty()) // 未登录空

        store.save(session.copy(user = session.user.copy(roles = listOf("admin"))))
        assertEquals(listOf("admin"), store.roles.value)

        store.clear()
        assertTrue(store.roles.value.isEmpty())

        // 冷启动：构造时从 KV 恢复
        store.save(session.copy(user = session.user.copy(roles = listOf("leader"))))
        assertEquals(listOf("leader"), AuthSessionStore(kv).roles.value)
    }
}
