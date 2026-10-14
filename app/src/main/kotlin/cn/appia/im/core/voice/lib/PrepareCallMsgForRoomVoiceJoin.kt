package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUserStatus

/**
 * RN lib/voice/prepareCallMsgForRoomVoiceJoin.ts verbatim。
 * 对齐旧版 RoomView.callView 点击加入前将本人 operation 置为 accept。
 */
fun prepareCallMsgForRoomVoiceJoin(callMsg: VoiceCallMsg, username: String): VoiceCallMsg {
    val name = username.trim().lowercase()
    if (name.isEmpty() || callMsg.userStatus.isNullOrEmpty()) {
        return callMsg
    }

    return callMsg.copy(
        userStatus = callMsg.userStatus.map { row ->
            if (row.username?.trim()?.lowercase() != name) {
                row
            } else {
                row.copy(operation = "accept", status = "in")
            }
        },
    )
}
