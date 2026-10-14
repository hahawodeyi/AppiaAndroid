package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.VoiceChatHoster

/**
 * RN lib/voice/isExpectedDirectVoiceRemoteUid.ts + isExpectedOutgoingRemoteUid.ts verbatim。
 */

/** isExpectedDirectVoiceRemoteUid.ts:7-13（同 ResolveAgoraJoinUid.parseUid 口径）。 */
private fun parseUid(value: Any?): Long? {
    if (value == null || value == "") return null
    return value.toString().toLongOrNull()
}

/**
 * isExpectedDirectVoiceRemoteUid.ts:16-33：单聊远端 Agora UID 是否为对端
 * （主叫看 receiverUid，被叫看 initiatorUid）。
 */
fun isExpectedDirectVoiceRemoteUid(
    remoteUid: Long,
    callMsg: VoiceCallMsg?,
    hoster: VoiceChatHoster?,
): Boolean {
    val roomType = resolveVoiceCallRoomType(callMsg, VoiceCallMsgContext(fallbackRoomType = hoster?.roomType))
    if (!isDirectVoiceRoom(roomType)) {
        return true
    }
    if (hoster != null) {
        return isExpectedOutgoingRemoteUid(remoteUid, callMsg, hoster)
    }
    val initiatorUid = parseUid(callMsg?.recordDataObj?.initiatorUid) ?: VOICE_HOSTER_AGORA_UID
    return remoteUid == initiatorUid
}

/** isExpectedOutgoingRemoteUid.ts:6-29：单聊主叫仅当远端 UID 与对端 receiverUid 一致时才视为接通。 */
fun isExpectedOutgoingRemoteUid(
    remoteUid: Long,
    callMsg: VoiceCallMsg?,
    hoster: VoiceChatHoster?,
): Boolean {
    val roomType = resolveVoiceCallRoomType(callMsg, VoiceCallMsgContext(fallbackRoomType = hoster?.roomType))
    if (!isDirectVoiceRoom(roomType) || hoster == null) {
        return true
    }

    val receivers = callMsg?.recordDataObj?.receivers ?: hoster.receivers
    if (receivers.size != 1) {
        return true
    }

    val peerUid = receivers[0].receiverUid
    if (peerUid == null) {
        return true
    }

    return peerUid == remoteUid
}
