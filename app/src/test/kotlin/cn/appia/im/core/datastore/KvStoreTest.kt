package cn.appia.im.core.datastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

// MMKV native .so 在 JVM/Robolectric 下无法加载（UnsatisfiedLinkError），
// 故单测以内存 fake 验证 KvStore 契约行为；MmkvKvStore 为 MMKV 薄委托，由真机/Maestro 冒烟覆盖。
// ConcurrentHashMap：被测代码跨线程读写（如 RoleRefresher 后台 fetch 合并写、主线程断言读）。
class InMemoryKvStore : KvStore {
    private val map = ConcurrentHashMap<String, Any>()

    override fun putString(key: String, value: String) { map[key] = value }
    override fun getString(key: String, defValue: String): String = map[key] as? String ?: defValue
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun remove(key: String) { map.remove(key) }
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun clear() = map.clear()
}

class KvStoreTest {
    private fun store(): KvStore = InMemoryKvStore()

    @Test
    fun `string round-trip and overwrite`() {
        val kv = store()
        kv.putString("server_url", "https://a.com")
        assertEquals("https://a.com", kv.getString("server_url", ""))
        kv.putString("server_url", "https://b.com")
        assertEquals("https://b.com", kv.getString("server_url", ""))
    }

    @Test
    fun `boolean int long round-trip`() {
        val kv = store()
        kv.putBoolean("logged_in", true)
        kv.putInt("org_count", 3)
        kv.putLong("user_id", 9007199254740993L)
        assertTrue(kv.getBoolean("logged_in", false))
        assertEquals(3, kv.getInt("org_count", 0))
        assertEquals(9007199254740993L, kv.getLong("user_id", 0L))
    }

    @Test
    fun `missing key returns default`() {
        val kv = store()
        assertEquals("fallback", kv.getString("absent", "fallback"))
        assertFalse(kv.getBoolean("absent", false))
        assertEquals(-1, kv.getInt("absent", -1))
        assertEquals(0L, kv.getLong("absent", 0L))
    }

    @Test
    fun `remove then contains is false`() {
        val kv = store()
        kv.putString("token", "abc")
        assertTrue(kv.contains("token"))
        kv.remove("token")
        assertFalse(kv.contains("token"))
        assertEquals("", kv.getString("token", ""))
    }

    @Test
    fun `clear wipes all keys`() {
        val kv = store()
        kv.putString("a", "1")
        kv.putInt("b", 2)
        kv.clear()
        assertFalse(kv.contains("a"))
        assertFalse(kv.contains("b"))
    }
}
