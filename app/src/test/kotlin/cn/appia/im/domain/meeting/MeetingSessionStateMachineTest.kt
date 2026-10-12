package cn.appia.im.domain.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会议状态机相位迁移 ≥12（坑 6 互斥不对称 / 坑 4 返回键相位）——RN meetingSessionStore 语义。
 */
class MeetingSessionStateMachineTest {

    private val machine = MeetingSessionStateMachine()

    // ---- 迁移表 ----

    @Test
    fun `initial phase is idle`() {
        assertEquals(MeetingPhase.Idle, machine.phase)
    }

    @Test
    fun `openPreheat moves idle to preheating with info`() {
        machine.openPreheat("weekly", "123456")
        assertEquals(MeetingPhase.Preheating, machine.phase)
        assertEquals("weekly", machine.state.value.title)
        assertEquals("123456", machine.state.value.meetingNumber)
    }

    @Test
    fun `openPreheat is no-op when already preheating`() {
        machine.openPreheat("a", "1")
        machine.openPreheat("b", "2")
        assertEquals(MeetingPhase.Preheating, machine.phase)
        assertEquals("a", machine.state.value.title)
    }

    @Test
    fun `markInMeeting advances from preheating keeping preheat info`() {
        machine.openPreheat("a", "1")
        machine.markInMeeting()
        assertEquals(MeetingPhase.InMeeting, machine.phase)
        assertEquals("a", machine.state.value.title)
        assertEquals("1", machine.state.value.meetingNumber)
        assertEquals(0, machine.state.value.durationSec)
    }

    @Test
    fun `markInMeeting from idle is a cold join`() {
        machine.markInMeeting("cold", "9")
        assertEquals(MeetingPhase.InMeeting, machine.phase)
        assertEquals("cold", machine.state.value.title)
    }

    @Test
    fun `markInMeeting is no-op when already inMeeting`() {
        machine.markInMeeting("first", "1")
        machine.markInMeeting("second", "2")
        assertEquals(MeetingPhase.InMeeting, machine.phase)
        assertEquals("first", machine.state.value.title)
    }

    @Test
    fun `minimize moves inMeeting to minimized`() {
        machine.markInMeeting()
        machine.minimize()
        assertEquals(MeetingPhase.Minimized, machine.phase)
    }

    @Test
    fun `restore moves minimized back to inMeeting`() {
        machine.markInMeeting()
        machine.minimize()
        machine.restore()
        assertEquals(MeetingPhase.InMeeting, machine.phase)
    }

    @Test
    fun `minimize from preheating is no-op`() {
        machine.openPreheat()
        machine.minimize()
        assertEquals(MeetingPhase.Preheating, machine.phase)
    }

    @Test
    fun `end returns any active phase to idle`() {
        machine.openPreheat()
        machine.end()
        assertEquals(MeetingPhase.Idle, machine.phase)

        machine.markInMeeting("t", "1")
        machine.minimize()
        machine.end()
        assertEquals(MeetingPhase.Idle, machine.phase)
        assertNull(machine.state.value.title)
    }

    @Test
    fun `end clears session fields`() {
        machine.markInMeeting("t", "1")
        machine.applyStats(120, 5)
        machine.setMicMuted(true)
        machine.end()
        val s = machine.state.value
        assertNull(s.title)
        assertNull(s.meetingNumber)
        assertEquals(0, s.durationSec)
        assertEquals(0, s.participantCount)
        assertFalse(s.isMuted)
    }

    @Test
    fun `kicked returns inMeeting to idle`() {
        machine.markInMeeting()
        machine.kicked()
        assertEquals(MeetingPhase.Idle, machine.phase)
    }

    // ---- 坑 6：互斥不对称 ----

    @Test
    fun `blocksVoiceStart - preheating does NOT block voice`() {
        machine.openPreheat()
        assertFalse(machine.blocksVoiceStart())
    }

    @Test
    fun `blocksVoiceStart - inMeeting and minimized block voice, idle does not`() {
        machine.markInMeeting()
        assertTrue(machine.blocksVoiceStart())
        machine.minimize()
        assertTrue(machine.blocksVoiceStart())
        machine.end()
        assertFalse(machine.blocksVoiceStart())
    }

    @Test
    fun `meetingBlockedByVoice - any non-idle voice phase blocks meeting`() {
        assertFalse(meetingBlockedByVoice(null))
        assertFalse(meetingBlockedByVoice(""))
        assertFalse(meetingBlockedByVoice("idle"))
        assertTrue(meetingBlockedByVoice("connecting"))
        assertTrue(meetingBlockedByVoice("incall"))
    }

    // ---- 坑 4：返回键相位 ----

    @Test
    fun `consumesBack only in inMeeting`() {
        assertFalse(machine.consumesBack())
        machine.openPreheat()
        assertFalse(machine.consumesBack())
        machine.markInMeeting()
        assertTrue(machine.consumesBack())
        machine.minimize()
        assertFalse(machine.consumesBack())
        machine.restore()
        assertTrue(machine.consumesBack())
        machine.end()
        assertFalse(machine.consumesBack())
    }

    // ---- stats / mute 回写 ----

    @Test
    fun `applyStats updates duration and participantCount`() {
        machine.markInMeeting()
        machine.applyStats(125, 7)
        val s = machine.state.value
        assertEquals(125, s.durationSec)
        assertEquals(7, s.participantCount)
    }

    @Test
    fun `applyStats ignored in idle`() {
        machine.applyStats(99, 9)
        assertEquals(0, machine.state.value.durationSec)
    }

    @Test
    fun `setMicMuted persists across minimize and restore`() {
        machine.markInMeeting()
        machine.setMicMuted(true)
        machine.minimize()
        machine.restore()
        assertTrue(machine.state.value.isMuted)
    }
}
