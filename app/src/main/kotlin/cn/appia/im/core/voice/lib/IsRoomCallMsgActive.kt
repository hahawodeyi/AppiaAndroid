package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext

/**
 * RN lib/voice/isRoomCallMsgActive.ts verbatim。
 * 房间 `callMsg` 是否表示仍有进行中的通话（对齐旧版 `recordData.exist`）。
 * 群聊在 `exist` 短暂为 false 但仍有未接听成员时仍视为进行中。
 */
fun isRoomCallMsgActive(callMsg: VoiceCallMsg?, ctx: VoiceCallMsgContext?): Boolean {
    val status = callMsg?.status
    if (callMsg == null || status == null || status !in setOf("calling", "talking")) {
        return false
    }
    if (callMsg.recordData != null) {
        if (isCallRecordActive(callMsg.recordDataObj?.exist)) {
            return true
        }
        return isGroupVoiceCallOngoing(callMsg, ctx)
    }
    return true
}

/**
 * RN lib/voice/resolveRoomVoiceJoinBanner.ts:13-30 verbatim。
 * 当前在 Agora 频道内的成员数（`userStatus.status === 'in'`，按 username 去重小写）。
 * Task 3 横幅文件依赖，此处先行提供（RN 原文同位依赖——reconcile 引用本函数）。
 */
fun countUsersInVoiceChannel(callMsg: VoiceCallMsg?): Int {
    val seen = mutableSetOf<String>()
    var count = 0
    for (row in callMsg?.userStatus ?: emptyList()) {
        if (row.status != "in") continue
        val name = row.username?.trim()?.lowercase()
        if (name.isNullOrEmpty() || !seen.add(name)) continue
        count += 1
    }
    return count
}

