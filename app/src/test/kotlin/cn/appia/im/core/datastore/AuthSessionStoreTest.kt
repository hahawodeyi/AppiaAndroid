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
}
