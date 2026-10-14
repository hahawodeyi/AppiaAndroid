package cn.appia.im.domain.voice

import cn.appia.im.core.voice.VoicePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoiceSessionStateMachine（RN voiceStateMachine.test.ts 全用例 + 简报 24 行转移矩阵）。
 */
class VoiceStateMachineTest {

    // ---- canStartOutgoing（RN voiceStateMachine.test.ts 'canStartOutgoing' 全用例）----

    @Test
    fun `is false when room callMsg is calling with active exist`() {
        // RN 'is false when room callMsg is calling with active exist'
        val callMsg = cn.appia.im.core.voice.VoiceCallMsg(
            status = "calling",
            recordData = kotlinx.serialization.json.buildJsonObject {
                put("exist", kotlinx.serialization.json.JsonPrimitive(true)); put("recordId", kotlinx.serialization.json.JsonPrimitive("rec-1"))
            },
        )
        assertFalse(canStartOutgoing(StartOutgoingParams(callMsg = callMsg)))
    }

    @Test
    fun `is false when status calling even if exist ended`() {
        // RN 'is false when status calling even if exist ended (align legacy MessageBox)'
        val callMsg = cn.appia.im.core.voice.VoiceCallMsg(
            status = "calling",
            recordData = kotlinx.serialization.json.buildJsonObject {
                put("exist", kotlinx.serialization.json.JsonPrimitive(false)); put("recordId", kotlinx.serialization.json.JsonPrimitive("rec-1"))
            },
        )
        assertFalse(canStartOutgoing(StartOutgoingParams(callMsg = callMsg)))
    }

    @Test
    fun `is false when room callMsg is talking with active exist`() {
        // RN 'is false when room callMsg is talking with active exist'
        val callMsg = cn.appia.im.core.voice.VoiceCallMsg(
            status = "talking",
            recordData = kotlinx.serialization.json.buildJsonObject {
                put("exist", kotlinx.serialization.json.JsonPrimitive("true")); put("recordId", kotlinx.serialization.json.JsonPrimitive("rec-1"))
            },
        )
        assertFalse(canStartOutgoing(StartOutgoingParams(callMsg = callMsg)))
    }

    @Test
    fun `is true when callMsg is end or absent`() {
        // RN 'is true when callMsg is end or absent'
        assertTrue(
            canStartOutgoing(
                StartOutgoingParams(callMsg = cn.appia.im.core.voice.VoiceCallMsg(status = "end")),
            ),
        )
        assertTrue(canStartOutgoing(StartOutgoingParams()))
    }

    // ---- transition（简报 24 行矩阵 verbatim + RN voiceStateMachine.ts:12-55 全行对照）----

    @Test
    fun `full transition matrix`() {
        // RN voiceStateMachine.ts:12-55 全行对照
        val cases = listOf(
            VoicePhase.Idle to VoiceEvent.StartOutgoing to VoicePhase.OutgoingRinging,
            VoicePhase.Idle to VoiceEvent.PresentIncoming to VoicePhase.IncomingRinging,
            VoicePhase.Idle to VoiceEvent.JoinOngoing to VoicePhase.Connecting,
            VoicePhase.OutgoingRinging to VoiceEvent.Accept to VoicePhase.Connecting,
            VoicePhase.OutgoingRinging to VoiceEvent.Reject to VoicePhase.Rejected,
            VoicePhase.OutgoingRinging to VoiceEvent.Timeout to VoicePhase.Timeout,
            VoicePhase.OutgoingRinging to VoiceEvent.RemoteBusy to VoicePhase.Busy,
            VoicePhase.OutgoingRinging to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.IncomingRinging to VoiceEvent.Accept to VoicePhase.Connecting,
            VoicePhase.IncomingRinging to VoiceEvent.Reject to VoicePhase.Rejected,
            VoicePhase.IncomingRinging to VoiceEvent.Timeout to VoicePhase.Timeout,
            VoicePhase.IncomingRinging to VoiceEvent.RemoteBusy to VoicePhase.Busy,
            VoicePhase.IncomingRinging to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.Connecting to VoiceEvent.AgoraJoined to VoicePhase.InCall,
            VoicePhase.Connecting to VoiceEvent.Reject to VoicePhase.Rejected,
            VoicePhase.Connecting to VoiceEvent.Timeout to VoicePhase.Timeout,
            VoicePhase.Connecting to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.InCall to VoiceEvent.Hangup to VoicePhase.Ending,
            VoicePhase.InCall to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.Ending to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.Rejected to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.Busy to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.Timeout to VoiceEvent.Reset to VoicePhase.Idle,
            VoicePhase.Cancelled to VoiceEvent.Reset to VoicePhase.Idle,
        )
        cases.forEach { (input, expected) ->
            val (phase, event) = input
            assertEquals("transition $phase + $event", expected, voiceTransition(phase, event))
        }
    }

    @Test
    fun `unmapped transitions keep phase`() {
        assertEquals(VoicePhase.Idle, voiceTransition(VoicePhase.Idle, VoiceEvent.Hangup))
        assertEquals(VoicePhase.InCall, voiceTransition(VoicePhase.InCall, VoiceEvent.Accept))
        assertEquals(VoicePhase.Rejected, voiceTransition(VoicePhase.Rejected, VoiceEvent.Accept))
        assertEquals(VoicePhase.OutgoingRinging, voiceTransition(VoicePhase.OutgoingRinging, VoiceEvent.AgoraJoined))
    }

    // ---- RN voiceStateMachine.test.ts 'transition' 各路径用例 ----

    @Test
    fun `outgoing lifecycle idle to ending to idle`() {
        // RN 'idle → outgoingRinging → connecting → inCall → ending → idle'
        assertEquals(VoicePhase.OutgoingRinging, voiceTransition(VoicePhase.Idle, VoiceEvent.StartOutgoing))
        assertEquals(VoicePhase.Connecting, voiceTransition(VoicePhase.OutgoingRinging, VoiceEvent.Accept))
        assertEquals(VoicePhase.InCall, voiceTransition(VoicePhase.Connecting, VoiceEvent.AgoraJoined))
        assertEquals(VoicePhase.Ending, voiceTransition(VoicePhase.InCall, VoiceEvent.Hangup))
        assertEquals(VoicePhase.Idle, voiceTransition(VoicePhase.Ending, VoiceEvent.Reset))
    }

    @Test
    fun `incoming lifecycle idle to incomingRinging to connecting to inCall`() {
        // RN 'idle → incomingRinging → connecting → inCall'
        assertEquals(VoicePhase.IncomingRinging, voiceTransition(VoicePhase.Idle, VoiceEvent.PresentIncoming))
        assertEquals(VoicePhase.Connecting, voiceTransition(VoicePhase.IncomingRinging, VoiceEvent.Accept))
        assertEquals(VoicePhase.InCall, voiceTransition(VoicePhase.Connecting, VoiceEvent.AgoraJoined))
    }

    @Test
    fun `idle to connecting on join ongoing`() {
        // RN 'idle → connecting on JOIN_ONGOING'
        assertEquals(VoicePhase.Connecting, voiceTransition(VoicePhase.Idle, VoiceEvent.JoinOngoing))
    }

    @Test
    fun `incomingRinging to rejected on reject`() {
        // RN 'incomingRinging → rejected on REJECT'
        assertEquals(VoicePhase.Rejected, voiceTransition(VoicePhase.IncomingRinging, VoiceEvent.Reject))
        assertEquals(VoicePhase.Idle, voiceTransition(VoicePhase.Rejected, VoiceEvent.Reset))
    }

    @Test
    fun `incomingRinging to busy on remote busy`() {
        // RN 'incomingRinging → busy on REMOTE_BUSY'
        assertEquals(VoicePhase.Busy, voiceTransition(VoicePhase.IncomingRinging, VoiceEvent.RemoteBusy))
    }

    @Test
    fun `outgoingRinging to timeout on timeout`() {
        // RN 'outgoingRinging → timeout on TIMEOUT'
        assertEquals(VoicePhase.Timeout, voiceTransition(VoicePhase.OutgoingRinging, VoiceEvent.Timeout))
    }
}
