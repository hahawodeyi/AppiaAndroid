package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/isGroupInitiatorReinviteEligible.ts verbatim。
 * 群聊主叫已退出、通话仍在进行且本人被重新邀请时，允许走被叫响铃链路。
 */

/** isGroupInitiatorReinviteEligible.ts:8-9。 */
private fun normalizeUsername(value: String?): String = value?.trim()?.lowercase() ?: ""

/** isGroupInitiatorReinviteEligible.ts:14-51。 */
fun isGroupInitiatorReinviteEligible(callMsg: VoiceCallMsg, username: String): Boolean {
    val name = normalizeUsername(username)
    val initiator = normalizeUsername(callMsg.recordDataObj?.initiator)
    if (name.isEmpty() || initiator != name) return false
    if (!isGroupVoiceCallMsg(callMsg, null)) return false

    val status = callMsg.status
    if (status != "calling" && status != "talking") return false
    if (countUsersInVoiceChannel(callMsg) < 1) return false

    val selfStatus = callMsg.userStatus?.find { normalizeUsername(it.username) == name }
    if (selfStatus == null || selfStatus.status == "in") return false
    if (selfStatus.operation == "hangup" || selfStatus.operation == "reject") return false
    if (selfStatus.operation == "waiting") return true
    return isVoiceUserStillPending(selfStatus)
}
