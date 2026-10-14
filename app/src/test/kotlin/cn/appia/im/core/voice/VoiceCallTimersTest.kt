package cn.appia.im.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RN src/lib/voice/voiceCallTimers.test.ts 全量移植 + brief Step 1 用例。
 * RN 测试名以注释保留；setTimeout 用 TestScope 虚拟时间（注入 scope + delay）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallTimersTest {

    private val testScope = TestScope()

    private lateinit var timers: VoiceCallTimers

    @Before
    fun setUp() {
        timers = VoiceCallTimers(testScope)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    /** RN: fires host 65s callback when recordId matches + brief host timers fire 30s and 65s。 */
    @Test
    fun `host timers fire 30s and 65s when anchored`() {
        var fired30 = false
        var fired65 = false
        timers.startHostOutgoingTimers("rec-1", on30s = { fired30 = true }, on65s = { fired65 = true })

        testScope.advanceTimeBy(30_000)
        testScope.runCurrent()
        assertTrue(fired30)
        assertFalse(fired65)

        testScope.advanceTimeBy(35_000)
        testScope.runCurrent()
        assertTrue(fired65)
    }

    /** RN: does not fire host 65s after clearVoiceCallTimers + brief clear cancels all。 */
    @Test
    fun `clear cancels all`() {
        var host30 = false
        var host65 = false
        var callee65 = false
        timers.startHostOutgoingTimers("rec-1", on30s = { host30 = true }, on65s = { host65 = true })
        timers.startCalleeIncomingTimer("rec-2", on65s = { callee65 = true })
        timers.clear()

        testScope.advanceTimeBy(65_000)
        testScope.runCurrent()

        assertFalse(host30)
        assertFalse(host65)
        assertFalse(callee65)
    }

    /** RN: fires callee 65s callback。 */
    @Test
    fun `callee timer fires 65s when anchored`() {
        var fired65 = false
        timers.startCalleeIncomingTimer("rec-2", on65s = { fired65 = true })

        testScope.advanceTimeBy(VoiceCallTimers.CALLEE_END_MS)
        testScope.runCurrent()

        assertTrue(fired65)
    }

    /** RN: replaces host timer when restarted with new recordId + brief anchor mismatch skips callback。 */
    @Test
    fun `anchor mismatch skips callback`() {
        var first = 0
        var first30 = 0
        var second = 0
        timers.startHostOutgoingTimers("rec-old", on30s = { first30++ }, on65s = { first++ })
        timers.startHostOutgoingTimers("rec-new", on30s = { }, on65s = { second++ })

        testScope.advanceTimeBy(VoiceCallTimers.HOST_END_MS)
        testScope.runCurrent()

        assertEquals(0, first)
        assertEquals(0, first30)
        assertEquals(1, second)
    }

    /** RN :60-65 resetHostOutgoingTimers = start（重锚后再计时）。 */
    @Test
    fun `reset re-anchors and restarts host timers`() {
        var fired30 = 0
        var fired65 = 0
        timers.startHostOutgoingTimers("rec-1", on30s = { }, on65s = { })
        testScope.advanceTimeBy(20_000)
        timers.resetHostOutgoingTimers("rec-1", on30s = { fired30++ }, on65s = { fired65++ })

        // 重锚后从头计时：累计 50s 时 30s 未到（重锚窗口才 30s）
        testScope.advanceTimeBy(30_000)
        testScope.runCurrent()
        assertEquals(1, fired30)
        assertEquals(0, fired65)

        testScope.advanceTimeBy(35_000)
        testScope.runCurrent()
        assertEquals(1, fired65)
    }
}
