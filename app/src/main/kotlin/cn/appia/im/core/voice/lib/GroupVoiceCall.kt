package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoiceUserStatus

/**
 * RN lib/voice/groupVoiceCall.ts verbatim（群组通话判定）。
 */

private val ACTIVE_CALL_STATUSES = setOf("calling", "talking")

/** groupVoiceCall.ts:12-19。 */
fun resolveVoiceCallRoomType(callMsg: VoiceCallMsg?, ctx: VoiceCallMsgContext?): String? =
    callMsg?.roomType?.trim()?.ifEmpty { null }
        ?: callMsg?.recordDataObj?.roomType?.trim()?.ifEmpty { null }
        ?: ctx?.fallbackRoomType?.trim()?.ifEmpty { null }

/** groupVoiceCall.ts:21-22。 */
fun isGroupVoiceRoom(roomType: String?): Boolean = roomType == "p" || roomType == "c"

/** groupVoiceCall.ts:24-38：非主叫 receiver 去重计数。 */
private fun countNonInitiatorReceivers(callMsg: VoiceCallMsg): Int {
    val initiator = callMsg.recordDataObj?.initiator?.trim()
    val seen = mutableSetOf<String>()
    for (r in (callMsg.recordDataObj?.receivers ?: emptyList()) + callMsg.receivers) {
        val username = r.receiver.trim()
        if (username.isEmpty() || username == initiator) continue
        seen.add(username)
    }
    return seen.size
}

/** groupVoiceCall.ts:40-52。 */
fun isGroupVoiceCallMsg(callMsg: VoiceCallMsg?, ctx: VoiceCallMsgContext?): Boolean {
    val roomType = resolveVoiceCallRoomType(callMsg, ctx)
    if (isGroupVoiceRoom(roomType)) return true
    if (callMsg == null) {
        return isGroupVoiceRoom(ctx?.fallbackRoomType)
    }
    return countNonInitiatorReceivers(callMsg) > 1
}

/** groupVoiceCall.ts:55-59：已挂断/拒绝，不再展示在通话成员列表（对齐旧版 `updateUserData`）。 */
fun isVoiceUserLeftCall(user: VoiceUserStatus): Boolean {
    val op = user.operation?.trim()
    return op == "hangup" || op == "reject"
}

/** groupVoiceCall.ts:62-70：仍在等待接听（未 reject/hangup，且未进频道）。 */
fun isVoiceUserStillPending(user: VoiceUserStatus): Boolean {
    if (isVoiceUserLeftCall(user)) return false
    return user.status != "in"
}

/** groupVoiceCall.ts:72-80。 */
private fun isReceiverStillPending(callMsg: VoiceCallMsg, username: String): Boolean {
    val row = callMsg.userStatus?.find { it.username == username }
    if (row != null) {
        return row.status == "in" || isVoiceUserStillPending(row)
    }
    return callMsg.status == "calling" || callMsg.status == "talking"
}

/** groupVoiceCall.ts:84-105：群聊被邀请人数（不含主叫），用于判断 mid-call 是否新增受邀人。 */
fun countGroupVoiceInvitees(callMsg: VoiceCallMsg?): Int {
    val initiator = callMsg?.recordDataObj?.initiator?.trim()
    val names = mutableSetOf<String>()
    for (r in (callMsg?.recordDataObj?.receivers ?: emptyList()) + (callMsg?.receivers ?: emptyList())) {
        val username = r.receiver.trim()
        if (username.isNotEmpty() && username != initiator) names.add(username)
    }
    for (row in callMsg?.userStatus ?: emptyList()) {
        val username = row.username?.trim()
        if (!username.isNullOrEmpty() && username != initiator) names.add(username)
    }
    return names.size
}

/** groupVoiceCall.ts:108-119：群聊除指定用户外是否已有成员进 Agora 频道（对齐旧版 `isConnect`）。 */
fun groupHasCalleeInVoiceChannel(callMsg: VoiceCallMsg?, excludeUsername: String?): Boolean {
    val exclude = excludeUsername?.trim()
    return (callMsg?.userStatus ?: emptyList()).any { u ->
        if (u.username?.trim().isNullOrEmpty() || u.username == exclude) {
            return@any false
        }
        u.status == "in"
    }
}

/** groupVoiceCall.ts:122-154：群聊是否仍有成员在频道内或仍在被呼叫（含 receivers 里尚未写入 userStatus 的受邀人）。 */
fun groupCallHasActiveOrPendingParticipants(callMsg: VoiceCallMsg, excludeUsername: String?): Boolean {
    val users = callMsg.userStatus ?: emptyList()
    if (users.any { u ->
            if (u.username.isNullOrEmpty() || u.username == excludeUsername) {
                return@any false
            }
            u.status == "in" || isVoiceUserStillPending(u)
        }
    ) {
        return true
    }

    val seen = mutableSetOf<String>()
    for (r in (callMsg.recordDataObj?.receivers ?: emptyList()) + callMsg.receivers) {
        val username = r.receiver.trim()
        if (username.isEmpty() || username == excludeUsername || !seen.add(username)) continue
        if (isReceiverStillPending(callMsg, username)) return true
    }
    return false
}

/** groupVoiceCall.ts:156-168。 */
fun isGroupVoiceCallOngoing(callMsg: VoiceCallMsg, ctx: VoiceCallMsgContext?): Boolean {
    val status = callMsg.status
    if (status !in ACTIVE_CALL_STATUSES) return false
    if (!isGroupVoiceCallMsg(callMsg, ctx)) return false
    return groupCallHasActiveOrPendingParticipants(callMsg, null)
}

/** groupVoiceCall.ts:171-200：群聊整通是否应对本端结束（不含 timeout/cancel 本端仍在频道特例）。 */
fun isGroupVoiceCallEndedForLocalUser(callMsg: VoiceCallMsg, localUsername: String?): Boolean {
    if (callMsg.status == "end" || callMsg.status == "reject") {
        return true
    }

    val calleeStillInChannel = groupHasCalleeInVoiceChannel(callMsg, localUsername)

    if (isCallRecordExplicitlyEnded(callMsg) && !calleeStillInChannel) {
        return true
    }

    if (!calleeStillInChannel) {
        val others = callMsg.userStatus?.filter { !it.username.isNullOrEmpty() && it.username != localUsername }
        if (others?.any { it.operation == "hangup" || it.operation == "reject" } == true) {
            return true
        }
    }

    return false
}

/** groupVoiceCall.ts:203-218：本端 leave 后是否应清除房间本地 `callMsg`（群聊仍有人在线/待接听时不清）。 */
fun shouldClearLocalRoomCallAfterEnd(
    callMsg: VoiceCallMsg?,
    localUsername: String?,
    ctx: VoiceCallMsgContext?,
): Boolean {
    if (callMsg == null) return true
    if (!isGroupVoiceCallMsg(callMsg, ctx)) return true
    if (isGroupVoiceCallEndedForLocalUser(callMsg, localUsername)) return true
    return !groupCallHasActiveOrPendingParticipants(callMsg, localUsername)
}
