package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/isVoluntaryGroupRejoinEligible.ts + isVoiceMemberLockedForInvite.ts verbatim。
 */

private fun normalizeUsername(value: String?): String = value?.trim()?.lowercase() ?: ""

/**
 * isVoluntaryGroupRejoinEligible.ts:14-47：群聊成员主动挂断后自行点击房间「加入语音」条
 * （非 waiting 重邀）时的资格判定。不要求 inChannelCount > 0，以容忍 callMsg 同步抖动。
 */
fun isVoluntaryGroupRejoinEligible(callMsg: VoiceCallMsg, username: String, roomType: String? = null): Boolean {
    val name = normalizeUsername(username)
    if (name.isEmpty()) {
        return false
    }

    val rt = roomType ?: resolveVoiceCallRoomType(callMsg, null)
    if (!isGroupVoiceRoom(rt)) {
        return false
    }

    val status = callMsg.status
    if (status != "calling" && status != "talking") {
        return false
    }

    val selfRow = callMsg.userStatus?.find { normalizeUsername(it.username) == name }
    if (selfRow == null || selfRow.status == "in") {
        return false
    }
    if (selfRow.operation == "waiting" || selfRow.operation == "reject") {
        return false
    }
    if (selfRow.operation == "hangup") {
        return true
    }
    return selfRow.status == "out"
}

/** isVoiceMemberLockedForInvite.ts:7-15：已在通话中或仍在等待接听：邀请页展示但不可选（对齐旧版 selectedUsers）。 */
fun isVoiceMemberLockedForInvite(callMsg: VoiceCallMsg?, username: String): Boolean {
    val row = callMsg?.userStatus?.find { it.username == username } ?: return false
    return !isVoiceUserLeftCall(row)
}
