package cn.appia.im.core.privacy

import cn.appia.im.core.datastore.InMemoryKvStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PrivacyConsentStore 语义（M11-T5 Slot3 / RN PrivacyConsentStore.kt 对齐）：
 * 默认未同意 / setAgreed 持久化翻转（同意前后状态翻转，简报最低要求）。
 * MMKV native .so 在 JVM 下不可加载，故经 KvStore 接口以 InMemoryKvStore 验证（KvStoreTest 同惯例）。
 */
class PrivacyConsentStoreTest {
    private val kv = InMemoryKvStore()

    @Test
    fun `defaults to not agreed with rn key naming`() {
        assertFalse(PrivacyConsentStore.hasAgreed(kv))
        assertEquals("privacy_consent_agreed", PrivacyConsentStore.KEY_AGREED)
    }

    @Test
    fun `setAgreed flips hasAgreed`() {
        assertFalse(PrivacyConsentStore.hasAgreed(kv))
        PrivacyConsentStore.setAgreed(kv)
        assertTrue(PrivacyConsentStore.hasAgreed(kv))
        // 写入即 KV 持久化（进程重启经同一 MMKV default instance 读回，MmkvKvStore 薄委托）
        assertEquals(true, kv.getBoolean(PrivacyConsentStore.KEY_AGREED, false))
    }
}
