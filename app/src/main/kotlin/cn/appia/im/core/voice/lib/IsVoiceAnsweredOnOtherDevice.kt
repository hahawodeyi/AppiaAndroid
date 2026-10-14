package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg

/**
 * RN lib/voice/isVoiceAnsweredOnOtherDevice.ts verbatim。
 * 对齐旧版 `RoomView.callView` / `VoiceChatMsg`：本人已在其他设备接听（`deviceId` 不一致）。
 */

/** isVoiceAnsweredOnOtherDevice.ts:3。 */
private fun normalize(value: String?): String = value?.trim() ?: ""

/** isVoiceAnsweredOnOtherDevice.ts:8-35。 */
fun isVoiceAnsweredOnOtherDevice(
    callMsg: VoiceCallMsg?,
    username: String,
    localDeviceId: String,
): Boolean {
    val name = normalize(username)
    val deviceId = normalize(localDeviceId)
    if (callMsg == null || name.isEmpty() || deviceId.isEmpty()) return false

    return callMsg.userStatus?.any { row ->
        if (normalize(row.username) != name) return@any false
        val op = row.operation?.trim()
        if (op == "reject" || op == "hangup" || op == "waiting") return@any false
        val rowDeviceId = normalize(row.deviceId)
        if (rowDeviceId.isEmpty()) return@any false
        rowDeviceId != deviceId
    } ?: false
}
