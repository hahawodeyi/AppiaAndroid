package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceUser

/**
 * RN lib/voice/resolveVoiceParticipantState.ts verbatim。
 * 群聊通话页：根据 userStatus 区分已接入、响铃中、拨号中、已拒绝等。
 */

/** resolveVoiceParticipantState.ts:3-8 VoiceParticipantUiKind。 */
enum class VoiceParticipantUiKind { Connected, Ringing, Dialing, Rejected, Ended }

/** resolveVoiceParticipantState.ts:10-15 VoiceParticipantUiState（labelKey 为 i18n key）。 */
data class VoiceParticipantUiState(
    val kind: VoiceParticipantUiKind,
    val labelKey: String,
    val showWaitingOverlay: Boolean,
)

/** resolveVoiceParticipantState.ts:18-57。 */
fun resolveVoiceParticipantUiState(user: VoiceUser): VoiceParticipantUiState {
    val status = user.status?.trim()
    val operation = user.operation?.trim()

    if (operation == "reject") {
        return VoiceParticipantUiState(VoiceParticipantUiKind.Rejected, "voice_participant_rejected", false)
    }
    if (operation == "hangup" && status != "in") {
        return VoiceParticipantUiState(VoiceParticipantUiKind.Ended, "voice_participant_hangup", false)
    }
    if (status == "in") {
        return VoiceParticipantUiState(VoiceParticipantUiKind.Connected, "voice_participant_connected", false)
    }
    if (operation == "waiting") {
        return VoiceParticipantUiState(VoiceParticipantUiKind.Ringing, "voice_participant_ringing", true)
    }
    return VoiceParticipantUiState(VoiceParticipantUiKind.Dialing, "voice_participant_dialing", true)
}

/** resolveVoiceParticipantState.ts:59-65 KIND_SORT_ORDER。 */
private val KIND_SORT_ORDER = mapOf(
    VoiceParticipantUiKind.Connected to 0,
    VoiceParticipantUiKind.Ringing to 1,
    VoiceParticipantUiKind.Dialing to 2,
    VoiceParticipantUiKind.Rejected to 3,
    VoiceParticipantUiKind.Ended to 4,
)

/** resolveVoiceParticipantState.ts:67-72：按状态排序（connected 最前）。 */
fun compareVoiceParticipantsByState(a: VoiceUser, b: VoiceUser): Int =
    (KIND_SORT_ORDER[resolveVoiceParticipantUiState(a).kind] ?: 0) -
        (KIND_SORT_ORDER[resolveVoiceParticipantUiState(b).kind] ?: 0)
