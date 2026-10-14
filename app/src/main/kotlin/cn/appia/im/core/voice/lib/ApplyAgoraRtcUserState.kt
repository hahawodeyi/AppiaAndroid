package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceUser

/**
 * RN lib/voice/applyAgoraRtcUserState.ts verbatim。
 */

/** applyAgoraRtcUserState.ts:3-6 AgoraVolumeSpeaker。 */
data class AgoraVolumeSpeaker(
    val uid: Long,
    val volume: Int? = null,
)

/** applyAgoraRtcUserState.ts:8-28：uid=0 是 Agora 本端回调约定 → 归本端 uid。 */
fun applyAgoraVolumeToUsers(
    users: List<VoiceUser>,
    speakers: List<AgoraVolumeSpeaker>,
    localAgoraUid: Long,
): List<VoiceUser> {
    val speakingUids = mutableSetOf<String>()
    for (item in speakers) {
        val volume = item.volume
        if (volume != null && volume != 0) {
            val key = if (item.uid == 0L) localAgoraUid.toString() else item.uid.toString()
            speakingUids.add(key)
        }
    }

    return users.map { user ->
        val key = if (user.uid != null) user.uid.toString() else ""
        if (key.isEmpty()) {
            user.copy(isSpeaking = false)
        } else {
            user.copy(isSpeaking = speakingUids.contains(key))
        }
    }
}

/** applyAgoraRtcUserState.ts:30-39。 */
fun applyRemoteMuteToUsers(users: List<VoiceUser>, remoteUid: Long, muted: Boolean): List<VoiceUser> =
    users.map { user ->
        if (user.uid != null && user.uid.toString() == remoteUid.toString()) {
            user.copy(isMuted = muted)
        } else {
            user
        }
    }

/** applyAgoraRtcUserState.ts:41-73：`callMsg` 轮询刷新参会人时保留 Agora 音量/静音态（对齐旧版 `usersStateMap` 合并）。 */
fun mergeVoiceUsersPreservingRtcState(next: List<VoiceUser>, prev: List<VoiceUser>): List<VoiceUser> {
    val prevByUsername = mutableMapOf<String, VoiceUser>()
    val prevByUid = mutableMapOf<String, VoiceUser>()
    for (u in prev) {
        val username = u.username?.trim()
        if (!username.isNullOrEmpty()) prevByUsername[username] = u
        if (!u.uid.isNullOrEmpty()) prevByUid[u.uid.toString()] = u
    }

    return next.map { user ->
        val username = user.username?.trim()
        val old = (username?.let { prevByUsername[it] })
            ?: (user.uid?.let { prevByUid[it.toString()] })
        if (old == null) {
            user
        } else {
            user.copy(
                uid = user.uid ?: old.uid,
                isSpeaking = old.isSpeaking,
                isMuted = old.isMuted,
            )
        }
    }
}
