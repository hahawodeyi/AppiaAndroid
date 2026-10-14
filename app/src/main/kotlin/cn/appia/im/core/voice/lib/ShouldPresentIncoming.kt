package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import kotlinx.serialization.json.JsonNull

/**
 * RN lib/voice/shouldPresentIncoming.ts verbatim（来电呈现判定）。
 */

/**
 * shouldPresentIncoming.ts:25-41：群聊被叫仍应响铃（含通话中邀请新成员）。
 * 邀请 payload 常缺 `roomType` / receivers，但 `userStatus` 里已有人 `in` 频道。
 */
private fun isGroupCalleeStillRinging(callMsg: VoiceCallMsg, username: String): Boolean {
    if (callMsg.status != "calling" && callMsg.status != "talking") return false
    val selfStatus = callMsg.userStatus?.find { it.username == username }
    if (selfStatus == null || !isVoiceUserStillPending(selfStatus)) return false
    if (countUsersInVoiceChannel(callMsg) > 0) return true
    val roomType = resolveVoiceCallRoomType(callMsg, null)
    return isGroupVoiceRoom(roomType) || isGroupVoiceCallMsg(callMsg, null)
}

/**
 * shouldPresentIncoming.ts:47-109。
 * 对齐旧版 `NotificationView`：根据 `callMsg.userStatus` 判断当前用户是否应看到来电 UI。
 * `onCallStatus === false` 时与旧版 `receiverOpenEvent` 一致，不展示来电。
 */
fun shouldPresentIncomingFromCallMsg(callMsg: VoiceCallMsg, username: String, onCallStatus: Boolean?): Boolean {
    val status = callMsg.status
    if (status == "end" || status == "cancel" || status == "reject") return false

    val groupStillRinging = isGroupCalleeStillRinging(callMsg, username)

    // shouldPresentIncoming.ts:59-65
    if (isCallRecordExplicitlyEnded(callMsg) &&
        countUsersInVoiceChannel(callMsg) == 0 &&
        !groupCallHasActiveOrPendingParticipants(callMsg, username)
    ) {
        return false
    }

    // shouldPresentIncoming.ts:67-69
    if (onCallStatus == false && !groupStillRinging) return false
    // shouldPresentIncoming.ts:70-75：recordData 缺失（RN falsy，含显式 null→JsonNull），或 exist 非 active 且非群组响铃 → 不展示
    if (callMsg.recordData == null || callMsg.recordData is JsonNull ||
        (!isCallRecordActive(callMsg.recordDataObj?.exist) && !groupStillRinging)
    ) {
        return false
    }
    // shouldPresentIncoming.ts:76-82：自己是 initiator 不显示（群组 reinvite 例外）
    val initiatorName = callMsg.recordDataObj?.initiator?.trim()?.lowercase()
    val localName = username.trim().lowercase()
    if (!initiatorName.isNullOrEmpty() && initiatorName == localName) {
        if (!isGroupInitiatorReinviteEligible(callMsg, username)) {
            return false
        }
    }

    // shouldPresentIncoming.ts:84-96
    val selfStatus = callMsg.userStatus?.find { it.username == username }
    if (selfStatus != null) {
        if (selfStatus.status == "in") return false
        if (selfStatus.operation == "reject" || selfStatus.operation == "hangup") return false
        return true
    }

    // shouldPresentIncoming.ts:98-100
    if (callMsg.status != "calling" && callMsg.status != "talking") return false

    // shouldPresentIncoming.ts:102-108
    return callMsg.recordDataObj?.receivers?.any { r ->
        r.receiver == username && (r.status == "out" || r.status == null || r.status == "")
    } ?: false
}

/**
 * shouldPresentIncoming.ts:111-129：在 FromCallMsg 基础上叠加本地「忽略」判定。
 */
fun shouldPresentIncomingForUser(
    callMsg: VoiceCallMsg,
    username: String,
    onCallStatus: Boolean?,
    dismissed: VoiceCallDismissedStore,
): Boolean {
    if (!shouldPresentIncomingFromCallMsg(callMsg, username, onCallStatus)) return false

    val recordId = callMsg.recordId ?: callMsg.recordDataObj?.recordId
    if (recordId.isNullOrEmpty() || !dismissed.isVoiceCallDismissed(recordId)) return true

    val inviteSnapshot = dismissed.buildVoiceInviteSnapshot(callMsg, username)
    return !dismissed.shouldBlockDismissedIncoming(recordId, inviteSnapshot)
}
