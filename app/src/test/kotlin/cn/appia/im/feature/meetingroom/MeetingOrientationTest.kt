package cn.appia.im.feature.meetingroom

import cn.appia.im.domain.meeting.MeetingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏三触发源 + 会中常亮判定（M8-T9；≥6 触发 + ≥2 常亮）。RN 规格源：
 * useOrientationToggle.ts:12-40 / MeetingWebView:109-117 / keepAwake/index.ts。
 */
class MeetingOrientationTest {

    // ── 触发① 共享驱动（web meeting:orientation:landscape/portrait 原生对位）──

    @Test
    fun `share on locks landscape and records memory`() {
        val o = MeetingOrientationState()
        assertEquals(MeetingOrientation.Landscape, o.onShareToggled(true))
        assertTrue(o.prefersLandscape)
    }

    @Test
    fun `share off locks portrait and clears memory`() {
        val o = MeetingOrientationState()
        o.onShareToggled(true)
        assertEquals(MeetingOrientation.Portrait, o.onShareToggled(false))
        assertFalse(o.prefersLandscape)
    }

    // ── 触发② 顶栏转屏按钮（按实际方向取反，直调不依赖 state——useOrientationToggle:25-40）──

    @Test
    fun `toggle from actual portrait goes landscape`() {
        val o = MeetingOrientationState()
        assertEquals(MeetingOrientation.Landscape, o.toggle(actualIsLandscape = false))
        assertTrue(o.prefersLandscape)
    }

    @Test
    fun `toggle from actual landscape goes portrait`() {
        val o = MeetingOrientationState()
        assertEquals(MeetingOrientation.Portrait, o.toggle(actualIsLandscape = true))
        assertFalse(o.prefersLandscape)
    }

    // ── 触发③ 最小化强制竖屏 / 恢复回记忆方向（forcePortrait 语义）──

    @Test
    fun `minimize forces portrait and keeps landscape memory`() {
        val o = MeetingOrientationState()
        o.onShareToggled(true)
        assertEquals(MeetingOrientation.Portrait, o.onMinimized())
        assertTrue(o.prefersLandscape) // RN forcePortrait 不抹 landscape state
    }

    @Test
    fun `restore returns to remembered landscape`() {
        val o = MeetingOrientationState()
        o.onShareToggled(true)
        o.onMinimized()
        assertEquals(MeetingOrientation.Landscape, o.onRestored())
    }

    @Test
    fun `restore without memory stays portrait`() {
        val o = MeetingOrientationState()
        assertEquals(MeetingOrientation.Portrait, o.onRestored())
    }

    // ── 会中常亮（任务定版：仅 InMeeting，最小化即清——与 RN 分歧见 meetingKeepScreenOn KDoc）──

    @Test
    fun `keep screen on only while inMeeting`() {
        assertTrue(meetingKeepScreenOn(MeetingPhase.InMeeting))
        assertFalse(meetingKeepScreenOn(MeetingPhase.Minimized))
        assertFalse(meetingKeepScreenOn(MeetingPhase.Preheating))
        assertFalse(meetingKeepScreenOn(MeetingPhase.Idle))
    }

    @Test
    fun `keep screen on clears on exit from inMeeting`() {
        val machine = cn.appia.im.domain.meeting.MeetingSessionStateMachine()
        machine.openPreheat()
        assertFalse(meetingKeepScreenOn(machine.state.value.phase))
        machine.markInMeeting()
        assertTrue(meetingKeepScreenOn(machine.state.value.phase))
        machine.minimize()
        assertFalse(meetingKeepScreenOn(machine.state.value.phase))
        machine.restore()
        assertTrue(meetingKeepScreenOn(machine.state.value.phase))
        machine.end()
        assertFalse(meetingKeepScreenOn(machine.state.value.phase))
    }
}
