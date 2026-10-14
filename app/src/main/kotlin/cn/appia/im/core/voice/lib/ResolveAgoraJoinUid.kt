package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/** resolveAgoraJoinUid.ts:59 被叫路径找不到 uid 时抛出（RN 侧 Error，Controller 捕获后 toast+reset）。 */
class VoiceUidResolveException(message: String = "voice_agora_uid_missing") : Exception(message)

/**
 * RN lib/voice/resolveAgoraJoinUid.ts verbatim。
 * 解析本端加入 Agora 频道应使用的 UID（须与 informInit / token 签发一致）。
 * 被叫不得回退到主叫 UID，否则双方同 UID 会导致双向无声。
 */

/** resolveAgoraJoinUid.ts:4-10：`Number(value)` → `toLongOrNull()`（数字/字符串双形态）。 */
private fun parseUid(value: Any?): Long? {
    if (value == null || value == "") return null
    return value.toString().toLongOrNull()
}

/** resolveAgoraJoinUid.ts:16-60。 */
fun resolveAgoraJoinUid(
    callMsg: VoiceCallMsg?,
    username: String,
    asHostInitiator: Boolean = false,
): Long {
    if (asHostInitiator) {
        val initiatorUid = parseUid(callMsg?.recordDataObj?.initiatorUid)
        return initiatorUid ?: VOICE_HOSTER_AGORA_UID
    }

    val name = username.trim().lowercase()
    val fromRecordReceiver = callMsg?.recordDataObj?.receivers?.find {
        it.receiver?.trim()?.lowercase() == name
    }
    val receiverUid = parseUid(fromRecordReceiver?.receiverUid)
    if (receiverUid != null) return receiverUid

    val fromTopReceivers = callMsg?.receivers?.find { it.receiver?.trim()?.lowercase() == name }
    val topUid = parseUid(fromTopReceivers?.receiverUid)
    if (topUid != null) return topUid

    val statusRow = callMsg?.userStatus?.find { it.username?.trim()?.lowercase() == name }
    val statusUid = parseUid(statusRow?.uid)
    if (statusUid != null) return statusUid

    val initiator = callMsg?.recordDataObj?.initiator?.trim()?.lowercase()
    if (!initiator.isNullOrEmpty() && initiator == name) {
        val initiatorUid = parseUid(callMsg.recordDataObj?.initiatorUid)
        if (initiatorUid != null) return initiatorUid
    }

    throw VoiceUidResolveException()
}
