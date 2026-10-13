package cn.appia.im.feature.mail

import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.mail.LatestMailPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * MailBadgeStore（mailChannelStore + mailPreviewByOrg 移植验证）：
 * - org 分桶（多组织互不覆盖）
 * - markChannelVisited 清零 + lastVisitAt
 * - ensureLastVisitAtInitialized 首装基线
 * - incrementUnread 增量钳 0
 * - v1 形态 migrate（RN version 2 persist migrate）
 */
class MailBadgeStoreTest {

    private fun preview(subject: String = "s", from: String = "a@x.com") =
        LatestMailPreview(subject = subject, fromName = null, from = from, receivedAt = "2026-10-01T00:00:00Z", unread = true)

    private fun store(clock: AtomicLong? = null): MailBadgeStore =
        if (clock == null) MailBadgeStore(InMemoryKvStore()) else MailBadgeStore(InMemoryKvStore()) { clock.get() }

    @Test
    fun `org buckets isolate unread and preview across orgs`() {
        val s = store()
        s.incrementUnread(3, "https://a.com")
        s.incrementUnread(5, "https://b.com")
        assertEquals(3, s.unreadCountForOrg("https://a.com"))
        assertEquals(5, s.unreadCountForOrg("https://b.com"))
        assertEquals(0, s.unreadCountForOrg("https://c.com")) // 缺桶 → 0

        s.setPreview(preview("A"), "https://a.com")
        assertEquals("A", s.previewForOrg("https://a.com")?.subject)
        assertNull(s.previewForOrg("https://b.com"))
    }

    @Test
    fun `mark channel visited clears unread and stamps last visit`() {
        val clock = AtomicLong(1_000L)
        val s = store(clock)
        s.incrementUnread(4, "https://a.com")
        clock.set(2_000L)
        s.markChannelVisited("https://a.com")
        assertEquals(0, s.unreadCountForOrg("https://a.com"))
        assertEquals(2_000L, s.lastVisitAtForOrg("https://a.com"))
        // 其他 org 不受影响
        s.incrementUnread(1, "https://b.com")
        s.markChannelVisited("https://a.com")
        assertEquals(1, s.unreadCountForOrg("https://b.com"))
    }

    @Test
    fun `ensure last visit initialized writes once only`() {
        val clock = AtomicLong(100L)
        val s = store(clock)
        s.ensureLastVisitAtInitialized("https://a.com")
        assertEquals(100L, s.lastVisitAtForOrg("https://a.com"))
        clock.set(200L)
        s.ensureLastVisitAtInitialized("https://a.com") // 已有桶 → 不覆写
        assertEquals(100L, s.lastVisitAtForOrg("https://a.com"))
    }

    @Test
    fun `increment unread clamps negative and empty org key is no-op`() {
        val s = store()
        s.incrementUnread(2, "https://a.com")
        s.incrementUnread(-5, "https://a.com") // 负值钳 0 后累加
        assertEquals(2, s.unreadCountForOrg("https://a.com"))
        s.incrementUnread(9, "") // 空 key 守卫
        s.setPreview(preview(), "")
        s.markChannelVisited("")
        assertEquals(0, s.unreadCountForOrg(""))
        assertNull(s.previewForOrg(""))
    }

    @Test
    fun `set preview null removes bucket`() {
        val s = store()
        s.setPreview(preview(), "https://a.com")
        s.setPreview(null, "https://a.com")
        assertNull(s.previewForOrg("https://a.com"))
    }

    @Test
    fun `state persists to kv and survives new store instance`() {
        val kv = InMemoryKvStore()
        val s1 = MailBadgeStore(kv)
        s1.incrementUnread(7, "https://a.com")
        s1.setPreview(preview("persisted"), "https://a.com")
        s1.setPinned(true)

        val s2 = MailBadgeStore(kv)
        assertEquals(7, s2.unreadCountForOrg("https://a.com"))
        assertEquals("persisted", s2.previewForOrg("https://a.com")?.subject)
        assertTrue(s2.pinned())
    }

    @Test
    fun `corrupted persisted json falls back to defaults`() {
        val kv = InMemoryKvStore()
        kv.putString("mail-channel-storage", "{not-json")
        val s = MailBadgeStore(kv)
        assertFalse(s.pinned())
        assertEquals(0, s.unreadCountForOrg("https://a.com"))
        assertNull(s.previewForOrg("https://a.com"))
    }

    @Test
    fun `reset clears all buckets`() {
        val s = store()
        s.incrementUnread(2, "https://a.com")
        s.setPreview(preview(), "https://a.com")
        s.setPinned(true)
        s.reset()
        assertEquals(0, s.unreadCountForOrg("https://a.com"))
        assertNull(s.previewForOrg("https://a.com"))
        assertFalse(s.pinned())
        assertEquals(0L, s.lastVisitAtForOrg("https://a.com"))
    }

    @Test
    fun `normalize org key trims and strips trailing slashes`() {
        assertEquals("https://a.com", normalizeMailOrgKey("https://a.com/"))
        assertEquals("https://a.com", normalizeMailOrgKey("  https://a.com  "))
        assertEquals("", normalizeMailOrgKey(null))
        assertEquals("", normalizeMailOrgKey(""))
    }

    @Test
    fun `snapshot flow emits mutations`() {
        val s = store()
        val emissions = mutableListOf<Int>()
        kotlinx.coroutines.runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
                s.snapshot.collect { emissions.add(it.unreadCountByOrg["https://a.com"] ?: 0) }
            }
            s.incrementUnread(1, "https://a.com")
            s.incrementUnread(2, "https://a.com")
            job.cancel()
        }
        assertEquals(listOf(0, 1, 3), emissions)
    }
}
