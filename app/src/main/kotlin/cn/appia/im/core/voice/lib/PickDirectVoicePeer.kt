package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoiceUser

/**
 * RN lib/voice/pickDirectVoicePeer.ts + isDirectVoicePeerInCall.ts verbatim。
 */

/** pickDirectVoicePeer.ts:8-11 VoiceDisplayPeer。 */
data class VoiceDisplayPeer(
    val username: String,
    val name: String,
)

/** pickDirectVoicePeer.ts:13-16。 */
private fun toPeer(username: String, name: String?): VoiceDisplayPeer =
    VoiceDisplayPeer(username, name?.trim()?.ifEmpty { null } ?: username)

/** pickDirectVoicePeer.ts:75-76 isDirectVoiceRoom（RN SubscriptionType.DIRECT === 'd'）。 */
fun isDirectVoiceRoom(roomType: String?): Boolean = roomType == "d"

/**
 * pickDirectVoicePeer.ts:19-73：单聊通话页固定展示的唯一对方
 * （避免 userStatus 抖动时在 1/2 人布局间闪烁）。
 */
fun pickDirectVoicePeer(
    users: List<VoiceUser>,
    localUsername: String? = null,
    waitingCallee: VoiceDisplayPeer? = null,
    callMsg: VoiceCallMsg? = null,
    hoster: VoiceChatHoster? = null,
): VoiceDisplayPeer? {
    if (!waitingCallee?.username?.trim().isNullOrEmpty()) {
        return waitingCallee
    }

    val local = localUsername?.trim()?.ifEmpty { null }
    val remoteUsers = if (local != null) {
        users.filter { !it.username?.trim().isNullOrEmpty() && it.username != local }
    } else {
        emptyList()
    }

    if (remoteUsers.isNotEmpty()) {
        val inCall = remoteUsers.find { it.status == "in" }
        val pick = inCall ?: remoteUsers[0]
        return toPeer(pick.username!!, pick.name)
    }

    if (callMsg != null) {
        val initiator = callMsg.invitorUsername?.trim()?.ifEmpty { null }
            ?: callMsg.recordDataObj?.initiator?.trim()?.ifEmpty { null }
        if (initiator != null && initiator != local) {
            return toPeer(
                initiator,
                callMsg.invitorName
                    ?: callMsg.recordDataObj?.initiatorName
                    ?: callMsg.uObj?.name,
            )
        }

        val receivers = callMsg.recordDataObj?.receivers ?: callMsg.receivers
        val remoteReceiver = receivers.find { it.receiver.trim().isNotEmpty() && it.receiver != local }
        if (remoteReceiver != null) {
            return toPeer(remoteReceiver.receiver, remoteReceiver.name)
        }
    }

    val hostReceiver = hoster?.receivers?.find { it.receiver.trim().isNotEmpty() && it.receiver != local }
    if (hostReceiver != null) {
        return toPeer(hostReceiver.receiver, hostReceiver.name)
    }

    return null
}

/** isDirectVoicePeerInCall.ts:5-21：单聊对方是否已在通话中（对齐旧版 isConnect / status === in）。 */
fun isDirectVoicePeerInCall(
    callMsg: VoiceCallMsg?,
    localUsername: String?,
    hoster: VoiceChatHoster? = null,
): Boolean {
    val peer = pickDirectVoicePeer(
        users = emptyList(),
        localUsername = localUsername,
        callMsg = callMsg,
        hoster = hoster,
    )
    if (peer == null) {
        return false
    }
    val row = callMsg?.userStatus?.find { it.username == peer.username }
    return row?.status == "in"
}
