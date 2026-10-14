package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoicePhase

/**
 * RN lib/voice/getOtherIncomingAlertType.ts verbatim。
 */

/** getOtherIncomingAlertType.ts:5。 */
private val NEEDS_CONFIRM_PHASES = setOf(VoicePhase.InCall, VoicePhase.Connecting, VoicePhase.OutgoingRinging)

/** getOtherIncomingAlertType.ts:7-31：通话中接听/加入另一路前弹窗类型（对齐旧版 `otherCallAlertType`，1/2/3）。 */
fun getOtherIncomingAlertType(
    phase: VoicePhase,
    currentRecordId: String?,
    currentOrg: String,
    otherCallMsg: VoiceCallMsg,
    intent: String,
): Int? {
    if (currentRecordId.isNullOrEmpty() || !NEEDS_CONFIRM_PHASES.contains(phase)) return null // RN `!currentRecordId` falsy

    val otherOrg = otherCallMsg.org?.trim()?.lowercase()
    val localOrg = currentOrg.trim().lowercase()
    if (!otherOrg.isNullOrEmpty() && otherOrg != localOrg) return 3

    if (intent == "join") return 2

    return 1
}
