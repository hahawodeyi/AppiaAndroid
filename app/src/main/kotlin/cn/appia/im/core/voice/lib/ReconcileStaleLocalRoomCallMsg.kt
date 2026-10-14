package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/reconcileStaleLocalRoomCallMsg.ts verbatim。
 * 本端 `clearLocalRoomCallAfterEnd` 可能把 `status` 写成 `end`，但 `userStatus` 仍有人 `in`。
 * 对齐旧版 `RoomView.callView`：有人在线即视为通话仍在进行。
 */
fun reconcileStaleLocalRoomCallMsg(callMsg: VoiceCallMsg?): VoiceCallMsg? {
    if (callMsg == null) return null
    val status = callMsg.status
    if ((status == "end" || status == "cancel" || status == "reject") &&
        countUsersInVoiceChannel(callMsg) > 0
    ) {
        return callMsg.copy(status = "talking")
    }
    return callMsg
}
