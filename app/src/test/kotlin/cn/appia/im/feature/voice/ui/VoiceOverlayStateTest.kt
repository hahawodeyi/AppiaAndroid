package cn.appia.im.feature.voice.ui

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.domain.voice.OtherIncoming
import cn.appia.im.domain.voice.VoiceSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RN VoiceCallHost/index.tsx:135-189 相位分流（ACTIVE_CALL_PHASES = {outgoingRinging, connecting,
 * inCall} 逐字）→ [voiceUiSurface] 纯映射表测试：每相位 × expanded × minimized × otherIncoming/alert 组合。
 * ending 不渲染（RN 逐字；~800ms linger 由 Controller 相位时序承载，UI 不感知）。
 */
class VoiceOverlayStateTest {

    private val callMsg = VoiceCallMsg(status = "calling", roomId = "room-1")

    private fun state(
        phase: VoicePhase,
        expanded: Boolean = false,
        minimized: Boolean = false,
        otherIncoming: OtherIncoming? = null,
        alertType: Int? = null,
    ) = VoiceSessionState(
        phase = phase,
        incomingUiExpanded = expanded,
        uiMinimized = minimized,
        otherIncoming = otherIncoming,
        otherIncomingAlertType = alertType,
        callMsg = callMsg,
    )

    private val other = OtherIncoming(callMsg = callMsg, rid = "room-2")

    @Test
    fun `idle renders nothing`() {
        assertEquals(VoiceUiSurface.None, voiceUiSurface(state(VoicePhase.Idle)))
    }

    @Test
    fun `ending rejected busy timeout cancelled render nothing`() {
        // RN :172-174 !ACTIVE_CALL_PHASES.has(phase) → null（ending/rejected/busy/timeout/cancelled 全部）
        VoicePhase.Ending.let { assertEquals(VoiceUiSurface.None, voiceUiSurface(state(it))) }
        VoicePhase.Rejected.let { assertEquals(VoiceUiSurface.None, voiceUiSurface(state(it))) }
        VoicePhase.Busy.let { assertEquals(VoiceUiSurface.None, voiceUiSurface(state(it))) }
        VoicePhase.Timeout.let { assertEquals(VoiceUiSurface.None, voiceUiSurface(state(it))) }
        VoicePhase.Cancelled.let { assertEquals(VoiceUiSurface.None, voiceUiSurface(state(it))) }
    }

    @Test
    fun `incoming ringing collapsed renders banner`() {
        assertEquals(VoiceUiSurface.Banner, voiceUiSurface(state(VoicePhase.IncomingRinging, expanded = false)))
    }

    @Test
    fun `incoming ringing expanded renders confirm`() {
        assertEquals(VoiceUiSurface.Confirm, voiceUiSurface(state(VoicePhase.IncomingRinging, expanded = true)))
    }

    @Test
    fun `minimized incoming ringing still renders banner or confirm`() {
        // RN VoiceCallHost 分流不读 uiMinimized（最小化收口在 MinimizeShell 层）；AA 简化：来电链不受 minimize 影响
        assertEquals(VoiceUiSurface.Banner, voiceUiSurface(state(VoicePhase.IncomingRinging, minimized = true)))
        assertEquals(VoiceUiSurface.Confirm, voiceUiSurface(state(VoicePhase.IncomingRinging, expanded = true, minimized = true)))
    }

    @Test
    fun `active phases render overlay`() {
        assertEquals(VoiceUiSurface.Overlay(otherIncoming = false, alertType = null), voiceUiSurface(state(VoicePhase.OutgoingRinging)))
        assertEquals(VoiceUiSurface.Overlay(otherIncoming = false, alertType = null), voiceUiSurface(state(VoicePhase.Connecting)))
        assertEquals(VoiceUiSurface.Overlay(otherIncoming = false, alertType = null), voiceUiSurface(state(VoicePhase.InCall)))
    }

    @Test
    fun `minimized active phases render nothing`() {
        // RN :177 callLayer 内 MinimizeShell 读 uiMinimized 切 bubble；AA Host 直接不渲染（bubble 归 Task 12）
        assertEquals(VoiceUiSurface.None, voiceUiSurface(state(VoicePhase.InCall, minimized = true)))
        assertEquals(VoiceUiSurface.None, voiceUiSurface(state(VoicePhase.OutgoingRinging, minimized = true)))
    }

    @Test
    fun `overlay carries otherIncoming and alertType`() {
        // RN :176-188 callLayer 内 OtherCallAlert + otherIncoming Modal + MinimizeShell 叠加
        val s = state(VoicePhase.InCall, otherIncoming = other, alertType = 2)
        assertEquals(VoiceUiSurface.Overlay(otherIncoming = true, alertType = 2), voiceUiSurface(s))
    }

    @Test
    fun `alert only without otherIncoming still renders overlay`() {
        val s = state(VoicePhase.InCall, alertType = 1)
        assertEquals(VoiceUiSurface.Overlay(otherIncoming = false, alertType = 1), voiceUiSurface(s))
    }

    @Test
    fun `table every phase x flags`() {
        // 全相位 × {expanded, minimized} × {otherIncoming, alert} 组合表（4 标志 = 16 组合 × 10 相位）
        val phases = VoicePhase.entries
        for (phase in phases) {
            for (expanded in listOf(false, true)) {
                for (minimized in listOf(false, true)) {
                    for (otherIn in listOf(false, true)) {
                        for (alert in listOf(null, 1)) {
                            val st = state(
                                phase,
                                expanded = expanded,
                                minimized = minimized,
                                otherIncoming = if (otherIn) other else null,
                                alertType = alert,
                            )
                            val surface = voiceUiSurface(st)
                            val expected = when {
                                phase == VoicePhase.Idle -> VoiceUiSurface.None
                                phase == VoicePhase.IncomingRinging ->
                                    if (expanded) VoiceUiSurface.Confirm else VoiceUiSurface.Banner
                                // ending 及其余非活跃相位：RN ACTIVE_CALL_PHASES 之外一律 null
                                phase != VoicePhase.OutgoingRinging &&
                                    phase != VoicePhase.Connecting &&
                                    phase != VoicePhase.InCall -> VoiceUiSurface.None
                                minimized -> VoiceUiSurface.None
                                else -> VoiceUiSurface.Overlay(otherIncoming = otherIn, alertType = alert)
                            }
                            assertEquals("phase=$phase expanded=$expanded minimized=$minimized other=$otherIn alert=$alert", expected, surface)
                        }
                    }
                }
            }
        }
    }
}
