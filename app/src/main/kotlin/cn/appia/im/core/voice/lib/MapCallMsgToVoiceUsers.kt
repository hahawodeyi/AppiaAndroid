package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceUser

/**
 * RN lib/voice/mapCallMsgToVoiceUsers.ts verbatim。
 */

/** mapCallMsgToVoiceUsers.ts:4-26：username → receiverUid 映射（主叫 initiatorUid 优先入表）。 */
private fun buildReceiverUidByUsername(callMsg: VoiceCallMsg): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val initiator = callMsg.recordDataObj?.initiator?.trim()
    val initiatorUid = callMsg.recordDataObj?.initiatorUid
    if (!initiator.isNullOrEmpty() && !initiatorUid.isNullOrEmpty()) {
        map[initiator] = initiatorUid
    }
    for (r in (callMsg.recordDataObj?.receivers ?: emptyList()) + callMsg.receivers) {
        val name = r.receiver?.trim()
        if (name.isNullOrEmpty() || r.receiverUid == null) continue
        if (!map.containsKey(name)) map[name] = r.receiverUid.toString()
    }
    return map
}

/** mapCallMsgToVoiceUsers.ts:28-41：uid 缺失时按 username 补挂（Task1 review：数字 uid 必须 toString）。 */
private fun attachAgoraUid(user: VoiceUser, uidByUsername: Map<String, String>): VoiceUser {
    if (!user.uid.isNullOrEmpty()) return user
    val username = user.username?.trim()
    if (username.isNullOrEmpty()) return user
    val uid = uidByUsername[username] ?: return user
    return user.copy(uid = uid)
}

/** mapCallMsgToVoiceUsers.ts:43-103：从房间 `callMsg` 生成通话页参会人列表（对齐旧版 `updateUserData` 展示）。 */
fun mapCallMsgToVoiceUsers(callMsg: VoiceCallMsg?): List<VoiceUser> {
    if (callMsg == null) return emptyList()

    val uidByUsername = buildReceiverUidByUsername(callMsg)

    val fromStatus = callMsg.userStatus?.map { u ->
        attachAgoraUid(
            VoiceUser(
                username = u.username,
                name = u.name ?: u.username,
                status = u.status,
                operation = u.operation,
                uid = u.uid?.toString(),
            ),
            uidByUsername,
        )
    } ?: emptyList()

    if (fromStatus.isNotEmpty()) return fromStatus

    val users = mutableListOf<VoiceUser>()
    val initiator = callMsg.recordDataObj?.initiator
    if (!initiator.isNullOrEmpty()) {
        users.add(
            attachAgoraUid(
                VoiceUser(
                    username = initiator,
                    name = callMsg.recordDataObj?.initiatorName ?: initiator,
                    status = "in",
                ),
                uidByUsername,
            ),
        )
    }

    // RN `callMsg.recordData?.receivers ?? callMsg.receivers`：recordData.receivers 为 null 才回退顶层
    val fallbackReceivers = callMsg.recordDataObj?.receivers ?: callMsg.receivers
    for (r in fallbackReceivers) {
        if (r.receiver.isNotEmpty()) {
            users.add(
                attachAgoraUid(
                    VoiceUser(
                        username = r.receiver,
                        name = r.name, // RN `r.name ?? r.receiver`：name 非空字段（Task1 已收窄），空串保持 RN 原语义
                        status = r.status,
                        uid = r.receiverUid?.toString(),
                    ),
                    uidByUsername,
                ),
            )
        }
    }

    return users
}
