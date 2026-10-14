package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext

/**
 * RN lib/voice/shouldEndCallFromCallMsg.ts verbatim（结束本端通话会话判定）。
 */

/**
 * shouldEndCallFromCallMsg.ts:17-44。
 * 群聊：单人 leave 后若仍有人在频道或仍在拨号，本端不应结束（对齐旧版群聊 leaveChannel）。
 * 须先判断待接听成员，再处理 `status: end`（服务端可能在群呼未结束时误推 end）。
 */
fun shouldEndGroupCallFromCallMsg(
    callMsg: VoiceCallMsg,
    localUsername: String?,
    ctx: VoiceCallMsgContext?,
): Boolean {
    val status = callMsg.status

    // 振铃阶段 timeout/cancel：即使 receivers 仍列待接听，本端也应收尾。
    if (status == "timeout" || status == "cancel") {
        if (localUsername.isNullOrEmpty()) return true // RN `!localUsername` falsy
        val self = callMsg.userStatus?.find { it.username == localUsername }
        if (self?.status == "in" && groupHasCalleeInVoiceChannel(callMsg, localUsername)) {
            return false
        }
        return true
    }

    if (isGroupVoiceCallEndedForLocalUser(callMsg, localUsername)) return true

    return false
}

/**
 * shouldEndCallFromCallMsg.ts:50-82。
 * 是否应结束本端通话会话（房间 callMsg / oncall 轮询结果）。
 * 除 `exist` 明确结束外，单聊在对方 `userStatus` 已 hangup/reject 时也应尽快收尾。
 */
fun shouldEndCallFromCallMsg(
    callMsg: VoiceCallMsg,
    localUsername: String?,
    ctx: VoiceCallMsgContext?,
): Boolean {
    if (isGroupVoiceCallMsg(callMsg, ctx)) {
        return shouldEndGroupCallFromCallMsg(callMsg, localUsername, ctx)
    }

    if (callMsg.status == "timeout" || callMsg.status == "cancel") return true

    if (isCallRecordExplicitlyEnded(callMsg)) return true
    if (localUsername == null || callMsg.roomType != "d") return false
    // exist 仍为 true 时对齐旧版：不以 userStatus hangup/reject 单独结束
    if (isCallRecordActive(callMsg.recordDataObj?.exist)) return false
    val others = callMsg.userStatus?.filter { !it.username.isNullOrEmpty() && it.username != localUsername }
    if (others.isNullOrEmpty()) return false
    return others.all { it.operation == "hangup" || it.operation == "reject" }
}
