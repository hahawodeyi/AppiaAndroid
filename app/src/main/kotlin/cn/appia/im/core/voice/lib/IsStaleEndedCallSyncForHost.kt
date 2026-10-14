package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoicePhase

/**
 * RN lib/voice/isStaleEndedCallSyncForHost.ts verbatim。
 */

/** isStaleEndedCallSyncForHost.ts:4-7。 */
private val HOST_OUTGOING_PHASES = setOf(VoicePhase.OutgoingRinging, VoicePhase.Connecting)

/** isStaleEndedCallSyncForHost.ts:9-15。 */
private fun isSyncCallMsgEnded(callMsg: VoiceCallMsg): Boolean {
    if (isCallRecordExplicitlyEnded(callMsg)) return true
    val status = callMsg.status
    return status == "end" || status == "cancel" || status == "reject"
}

/** isStaleEndedCallSyncForHost.ts:17-39：主叫重拨时，房间同步可能仍带上一次挂断的 end callMsg。 */
fun isStaleEndedCallSyncForHost(
    phase: VoicePhase,
    hasHoster: Boolean,
    currentRecordId: String?,
    parsed: VoiceCallMsg,
): Boolean {
    if (!hasHoster || !HOST_OUTGOING_PHASES.contains(phase)) return false
    if (!isSyncCallMsgEnded(parsed)) return false

    val syncRecordId = parsed.recordId ?: parsed.recordDataObj?.recordId
    if (currentRecordId.isNullOrEmpty()) return true // RN `!currentRecordId` falsy：空串同无 recordId
    if (!syncRecordId.isNullOrEmpty() && syncRecordId != currentRecordId) return true
    return false
}
