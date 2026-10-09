package cn.appia.im.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PendingPushNavigation 测试（RN roomPushNavigationQueue.ts 移植）：
 * 90s TTL / rid:messageId 去重 / drain 清空 / 时钟注入 / 入队与冲刷双向过期丢弃。
 */
class PendingPushNavigationTest {

    /** 可控时钟：测试中手动推进 */
    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun newQueue(clock: FakeClock) = PendingPushNavigation(clock = { clock.now })

    private fun enq(q: PendingPushNavigation, rid: String, msgId: String? = null, t: String = "c", title: String? = null) =
        q.enqueue(rid = rid, t = t, title = title, messageId = msgId)

    // ---- 基本存取 ----

    @Test
    fun `enqueue then drain returns intent with all fields`() {
        val clock = FakeClock()
        val q = newQueue(clock)
        q.enqueue(rid = "r1", t = "d", title = "Alice", messageId = "m1")
        val out = q.drain()
        assertEquals(1, out.size)
        assertEquals("r1", out[0].rid)
        assertEquals("d", out[0].t)
        assertEquals("Alice", out[0].title)
        assertEquals("m1", out[0].messageId)
        assertEquals(clock.now, out[0].receivedAt)
    }

    @Test
    fun `drain clears queue - second drain empty`() {
        val q = newQueue(FakeClock())
        enq(q, "r1")
        assertEquals(1, q.drain().size)
        assertTrue(q.drain().isEmpty())
    }

    @Test
    fun `drain preserves insertion order`() {
        val q = newQueue(FakeClock())
        enq(q, "r1")
        enq(q, "r2")
        enq(q, "r3")
        val out = q.drain()
        assertEquals(listOf("r1", "r2", "r3"), out.map { it.rid })
    }

    // ---- 去重 rid:messageId ----

    @Test
    fun `dedupe same rid and messageId - re-enqueue replaces`() {
        val clock = FakeClock()
        val q = newQueue(clock)
        enq(q, "r1", "m1", title = "first")
        clock.advance(1000)
        enq(q, "r1", "m1", title = "second")
        val out = q.drain()
        assertEquals(1, out.size)
        assertEquals("second", out[0].title)
    }

    @Test
    fun `same rid different messageId both kept`() {
        val q = newQueue(FakeClock())
        enq(q, "r1", "m1")
        enq(q, "r1", "m2")
        assertEquals(2, q.drain().size)
    }

    @Test
    fun `null messageId dedupes as empty key`() {
        val q = newQueue(FakeClock())
        enq(q, "r1", null)
        enq(q, "r1", null)
        assertEquals(1, q.drain().size)
    }

    // ---- TTL 90s ----

    @Test
    fun `expired items dropped at drain`() {
        val clock = FakeClock()
        val q = newQueue(clock)
        enq(q, "r-old")
        clock.advance(PendingPushNavigation.TTL_MS + 1)
        enq(q, "r-new")
        val out = q.drain()
        assertEquals(listOf("r-new"), out.map { it.rid })
    }

    @Test
    fun `exactly at TTL boundary item still alive`() {
        val clock = FakeClock()
        val q = newQueue(clock)
        enq(q, "r1")
        clock.advance(PendingPushNavigation.TTL_MS)
        assertEquals(listOf("r1"), q.drain().map { it.rid })
    }

    @Test
    fun `expired items dropped at enqueue`() {
        val clock = FakeClock()
        val q = newQueue(clock)
        enq(q, "r-old")
        clock.advance(PendingPushNavigation.TTL_MS + 1)
        enq(q, "r-fresh") // 触发入队清扫
        assertEquals(listOf("r-fresh"), q.drain().map { it.rid })
    }

    // ---- 辅助 API ----

    @Test
    fun `pendingCount reflects queue size`() {
        val q = newQueue(FakeClock())
        assertEquals(0, q.pendingCount)
        enq(q, "r1")
        enq(q, "r2")
        assertEquals(2, q.pendingCount)
        q.drain()
        assertEquals(0, q.pendingCount)
    }

    @Test
    fun `clear empties queue`() {
        val q = newQueue(FakeClock())
        enq(q, "r1")
        q.clear()
        assertEquals(0, q.pendingCount)
        assertTrue(q.drain().isEmpty())
    }
}
