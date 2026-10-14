package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceUser

/**
 * RN lib/voice/filterVoiceDisplayUsers.ts verbatim。
 * 通话页展示用参会人（单聊不展示本端；已挂断/拒绝不出现在列表）。
 */
fun filterVoiceDisplayUsers(
    users: List<VoiceUser>,
    localUsername: String?,
    roomType: String?,
): List<VoiceUser> {
    val visible = users.filter { !it.username?.trim().isNullOrEmpty() && !isVoiceUserLeftCall(it.toStatusLike()) }

    if (localUsername?.trim().isNullOrEmpty()) {
        return visible
    }
    if (roomType == "d") {
        return visible.filter { it.username != localUsername }
    }
    return visible
}

/** VoiceUser → isVoiceUserLeftCall 所需形态（groupVoiceCall.ts:55-59 仅看 operation）。 */
private fun VoiceUser.toStatusLike() = cn.appia.im.core.voice.VoiceUserStatus(
    username = username,
    name = name,
    operation = operation,
    status = status,
)
