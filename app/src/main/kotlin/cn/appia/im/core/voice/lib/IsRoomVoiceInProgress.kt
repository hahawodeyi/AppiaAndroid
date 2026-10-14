package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/isRoomVoiceInProgress.ts verbatim。
 * 对齐旧版 MessageBox：`status` 为 calling/talking 即视为房间通话进行中。
 */
fun isRoomVoiceInProgress(callMsg: VoiceCallMsg?): Boolean {
    val effective = reconcileStaleLocalRoomCallMsg(callMsg) ?: callMsg
    val status = effective?.status
    return status == "calling" || status == "talking"
}

