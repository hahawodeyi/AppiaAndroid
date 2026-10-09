package cn.appia.im.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PushNavigationDrainer 测试（RN pushNavigation.ts drainPendingRoomPushNavigations +
 * MainNavigator.tsx:38-40/:62-72 三 drain 点时序移植）：
 * 门控（isAuthenticated && navReady）/ 三触发 / 回前台即时 drain+清理 + 400ms 延迟二次 drain
 * / 二次回前台与回后台取消在途延迟任务。
 */
class PushNavigationDrainerTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) { now += ms }
    }

    /** 可控延迟调度器：记录任务，手动执行；取消置 cancelled 后 run 变 no-op。 */
    private class FakeScheduler {
        class Task(val delayMs: Long, val block: () -> Unit) {
            var cancelled = false
            fun run() { if (!cancelled) block() }
        }

        val tasks = mutableListOf<Task>()

        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            val task = Task(delayMs, block)
            tasks += task
            return { task.cancelled = true }
        }

        /** 已调度且未取消的延迟任务（按序最后一个 = 最近一次回前台排的）。 */
        val pending: List<Task> get() = tasks.filter { !it.cancelled }
    }

    private class Harness(authenticated: Boolean = true) {
        val clock = FakeClock()
        val queue = PendingPushNavigation(clock = { clock.now })
        val scheduler = FakeScheduler()
        val navigated = mutableListOf<PendingPushNavigation.Intent>()
        var clearedNotifications = 0
        var auth = authenticated

        val drainer = PushNavigationDrainer(
            queue = queue,
            isAuthenticated = { auth },
            scheduleDelayed = scheduler::schedule,
        ).apply {
            navigate = { navigated += it }
            clearNotifications = { clearedNotifications++ }
        }

        fun enqueue(rid: String = "r1", messageId: String? = "m1") {
            queue.enqueue(rid = rid, t = "d", title = "Alice", messageId = messageId)
        }
    }

    // ---- 门控 ----

    @Test
    fun `navReady with authenticated drains queue to navigate`() {
        val h = Harness()
        h.enqueue(rid = "r1")
        h.enqueue(rid = "r2", messageId = null)
        h.drainer.onNavReady()
        assertEquals(listOf("r1", "r2"), h.navigated.map { it.rid })
        assertEquals(0, h.queue.pendingCount)
    }

    @Test
    fun `not authenticated suppresses all triggers until auth`() {
        val h = Harness(authenticated = false)
        h.enqueue()
        h.drainer.onNavReady()
        h.drainer.onMainMounted()
        h.drainer.onAppForeground()
        assertTrue(h.navigated.isEmpty())
        assertEquals(0, h.clearedNotifications)
        // 90s 内登录完成（RN 同语义：drain 门控即全部，无强制续跳）→ 下一次 Main 挂载 drain 补跳
        h.auth = true
        h.drainer.onMainMounted()
        assertEquals(1, h.navigated.size)
    }

    @Test
    fun `mainMounted before navReady is suppressed - navReady drains later`() {
        val h = Harness()
        h.enqueue()
        h.drainer.onMainMounted()
        assertTrue(h.navigated.isEmpty())
        h.drainer.onNavReady()
        assertEquals(1, h.navigated.size)
    }

    @Test
    fun `expired item is not navigated`() {
        val h = Harness()
        h.enqueue()
        h.clock.advance(PendingPushNavigation.TTL_MS + 1)
        h.drainer.onNavReady()
        assertTrue(h.navigated.isEmpty())
    }

    // ---- 回前台（RN MainNavigator.tsx:62-72）----

    @Test
    fun `foreground drains immediately and clears notifications`() {
        val h = Harness()
        h.drainer.onNavReady()
        h.enqueue()
        h.drainer.onAppForeground()
        assertEquals(1, h.navigated.size)
        assertEquals(1, h.clearedNotifications)
    }

    @Test
    fun `foreground schedules 400ms delayed second drain`() {
        val h = Harness()
        h.drainer.onNavReady()
        h.enqueue()
        h.drainer.onAppForeground() // 即时 drain 先吃掉积压
        assertEquals(1, h.navigated.size)
        val pending = h.scheduler.pending
        assertEquals(1, pending.size)
        assertEquals(PushNavigationDrainer.FOREGROUND_SECOND_DRAIN_DELAY_MS, pending[0].delayMs)
        // 队列已清空 → 延迟任务执行为 no-op（RN 二次 drain 同语义，幂等）
        pending[0].run()
        assertEquals(1, h.navigated.size)
        // 竞态窗口内新入队（点击通知落在即时与延迟之间）→ 延迟任务补跳
        h.enqueue(rid = "late")
        pending[0].run()
        assertEquals(2, h.navigated.size)
        assertEquals("late", h.navigated[1].rid)
    }

    @Test
    fun `second foreground cancels previous delayed drain`() {
        val h = Harness()
        h.drainer.onNavReady()
        h.enqueue(rid = "a")
        h.drainer.onAppForeground()
        h.enqueue(rid = "b")
        h.drainer.onAppForeground() // 即时 drain 吃掉 b + 取消第一轮延迟任务
        val pending = h.scheduler.pending
        assertEquals(1, pending.size)
        // 第一轮延迟任务已取消：执行不再触发导航
        h.scheduler.tasks[0].run()
        assertEquals(listOf("a", "b"), h.navigated.map { it.rid })
    }

    @Test
    fun `background cancels pending delayed drain`() {
        val h = Harness()
        h.drainer.onNavReady()
        h.drainer.onAppForeground()
        h.drainer.onAppBackground()
        assertTrue(h.scheduler.pending.isEmpty())
        h.enqueue()
        h.scheduler.tasks[0].run() // 已取消：no-op
        assertTrue(h.navigated.isEmpty())
    }

    @Test
    fun `foreground without navReady suppresses drain but still cleanup notification`() {
        // RN 的清理与 drain 同在 active 分支（:64 在 drain 旁）；导航未就绪（纯 Auth 栈）时
        // 不进房，但坑 16 的通知清理不应被吞。
        val h = Harness()
        h.enqueue()
        h.drainer.onAppForeground()
        assertTrue(h.navigated.isEmpty())
        assertEquals(1, h.clearedNotifications)
    }

    @Test
    fun `navigate handler unset - drain keeps intents in queue`() {
        // 组合尚未接线（onResume 先于首帧组合的窗口期）：不得提前清空队列丢意图。
        val clock = FakeClock()
        val q = PendingPushNavigation(clock = { clock.now })
        val drainer = PushNavigationDrainer(q, { true }, FakeScheduler()::schedule)
        q.enqueue(rid = "r1", t = "c", title = null, messageId = null)
        drainer.onNavReady()
        drainer.onAppBackground()
        assertEquals(1, q.pendingCount)
        assertFalse(q.drain().isEmpty())
    }
}
