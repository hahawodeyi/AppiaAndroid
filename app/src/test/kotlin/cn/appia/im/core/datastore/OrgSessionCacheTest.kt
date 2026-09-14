package cn.appia.im.core.datastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * OrgSessionCache（fake KvStore）：单 key `sessions-v1` 的 Record 覆盖语义 / host 尾斜杠归一 /
 * 坏行坏 JSON 回退。对照 RN services/api/orgSessionByHost.ts:1-57。
 */
class OrgSessionCacheTest {
    private val kv = InMemoryKvStore()
    private val cache get() = OrgSessionCache(kv)

    private val row = OrgSessionCacheRow(token = "t-1", userId = "u-1", username = "bob", name = "Bob")

    @Test
    fun `set get round trip across instances`() {
        cache.set("https://a.cn", row)

        assertEquals(row, OrgSessionCache(kv).get("https://a.cn"))
    }

    @Test
    fun `set overwrites same host`() {
        cache.set("https://a.cn", row)

        cache.set("https://a.cn", row.copy(token = "t-2"))

        assertEquals("t-2", cache.get("https://a.cn")?.token)
    }

    @Test
    fun `host trailing slash normalized on read and write`() {
        cache.set("https://a.cn/", row)

        assertEquals(row, cache.get("https://a.cn"))
        assertEquals(row, cache.get("https://a.cn/"))

        cache.clear("https://a.cn/")
        assertNull(cache.get("https://a.cn"))
    }

    @Test
    fun `multiple hosts live in the single key`() {
        cache.set("https://a.cn", row)
        cache.set("https://b.cn", row.copy(userId = "u-2"))

        assertEquals("u-1", cache.get("https://a.cn")?.userId)
        assertEquals("u-2", cache.get("https://b.cn")?.userId)
        assertTrue(kv.contains("sessions-v1"))
    }

    @Test
    fun `clear removes only target host`() {
        cache.set("https://a.cn", row)
        cache.set("https://b.cn", row)

        cache.clear("https://a.cn")

        assertNull(cache.get("https://a.cn"))
        assertEquals(row, cache.get("https://b.cn"))
    }

    @Test
    fun `clearAll removes the whole key`() {
        cache.set("https://a.cn", row)
        cache.set("https://b.cn", row)

        cache.clearAll()

        assertNull(cache.get("https://a.cn"))
        assertNull(cache.get("https://b.cn"))
        assertFalse(kv.contains("sessions-v1")) // RN :54-56 remove key 而非清实例
    }

    @Test
    fun `get rejects rows missing token or userId`() {
        // token 空（RN :36 !row?.token）
        kv.putString("sessions-v1", """{"https://a.cn":{"token":"","userId":"u-1","username":"bob"}}""")
        assertNull(cache.get("https://a.cn"))

        // userId 缺省（RN !row.userId；JS undefined → falsy）
        kv.putString("sessions-v1", """{"https://a.cn":{"token":"t-1","username":"bob"}}""")
        assertNull(cache.get("https://a.cn"))
    }

    @Test
    fun `corrupt storage falls back to empty map and recovers on set`() {
        kv.putString("sessions-v1", "{oops")

        assertNull(cache.get("https://a.cn"))

        cache.set("https://a.cn", row)
        assertEquals(row, cache.get("https://a.cn"))
    }

    @Test
    fun `normalizeOrgSessionHost strips one trailing slash only`() {
        assertEquals("https://a.cn", normalizeOrgSessionHost("https://a.cn/"))
        // RN replace(/\/$/, '') 只去一个尾斜杠
        assertEquals("https://a.cn/", normalizeOrgSessionHost("https://a.cn//"))
        assertEquals("https://a.cn", normalizeOrgSessionHost("https://a.cn"))
    }
}
