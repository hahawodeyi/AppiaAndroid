package cn.appia.im.domain.voice

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.isRoomVoiceInProgress

/**
 * RN lib/voice/voiceStateMachine.ts verbatim（通话会话状态机）。
 */

/** voiceStateMachine.ts VoiceEvent（RN 判别联合 {type} → sealed interface）。 */
sealed interface VoiceEvent {
    data object StartOutgoing : VoiceEvent
    data object PresentIncoming : VoiceEvent
    data object JoinOngoing : VoiceEvent
    data object Accept : VoiceEvent
    data object Reject : VoiceEvent
    data object RemoteBusy : VoiceEvent
    data object Timeout : VoiceEvent
    data object AgoraJoined : VoiceEvent
    data object Hangup : VoiceEvent
    data object Reset : VoiceEvent
}

/** voiceStateMachine.ts:4-6 canStartOutgoing 参数形态。 */
data class StartOutgoingParams(
    val callMsg: VoiceCallMsg? = null,
)

/** voiceStateMachine.ts:4-6。 */
fun canStartOutgoing(params: StartOutgoingParams): Boolean = !isRoomVoiceInProgress(params.callMsg)

/** voiceStateMachine.ts:8-55：未映射转移保持原相位。 */
fun voiceTransition(phase: VoicePhase, event: VoiceEvent): VoicePhase = when (phase) {
    VoicePhase.Idle -> when (event) {
        VoiceEvent.StartOutgoing -> VoicePhase.OutgoingRinging
        VoiceEvent.PresentIncoming -> VoicePhase.IncomingRinging
        VoiceEvent.JoinOngoing -> VoicePhase.Connecting
        else -> phase
    }
    VoicePhase.OutgoingRinging -> when (event) {
        VoiceEvent.Accept -> VoicePhase.Connecting
        VoiceEvent.Reject -> VoicePhase.Rejected
        VoiceEvent.Timeout -> VoicePhase.Timeout
        VoiceEvent.RemoteBusy -> VoicePhase.Busy
        VoiceEvent.Reset -> VoicePhase.Idle
        else -> phase
    }
    VoicePhase.IncomingRinging -> when (event) {
        VoiceEvent.Accept -> VoicePhase.Connecting
        VoiceEvent.Reject -> VoicePhase.Rejected
        VoiceEvent.Timeout -> VoicePhase.Timeout
        VoiceEvent.RemoteBusy -> VoicePhase.Busy
        VoiceEvent.Reset -> VoicePhase.Idle
        else -> phase
    }
    VoicePhase.Connecting -> when (event) {
        VoiceEvent.AgoraJoined -> VoicePhase.InCall
        VoiceEvent.Reject -> VoicePhase.Rejected
        VoiceEvent.Timeout -> VoicePhase.Timeout
        VoiceEvent.Reset -> VoicePhase.Idle
        else -> phase
    }
    VoicePhase.InCall -> when (event) {
        VoiceEvent.Hangup -> VoicePhase.Ending
        VoiceEvent.Reset -> VoicePhase.Idle
        else -> phase
    }
    VoicePhase.Ending -> when (event) {
        VoiceEvent.Reset -> VoicePhase.Idle
        else -> phase
    }
    VoicePhase.Rejected, VoicePhase.Busy, VoicePhase.Timeout, VoicePhase.Cancelled -> when (event) {
        VoiceEvent.Reset -> VoicePhase.Idle
        else -> phase
    }
}
