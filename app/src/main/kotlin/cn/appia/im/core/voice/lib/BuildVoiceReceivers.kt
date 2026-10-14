package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceChatReceiver

/**
 * RN lib/voice/buildVoiceReceivers.ts verbatim。
 * 简报裁定：RN IFederatedMembersGroup 依赖映射为 AA 侧选人数据，
 * 收窄为最小接口 RoomMemberLike(username, id, name)，签名扁平化
 * （RN groups 嵌套 forEach ≡ flatMap，调用方自行拉平组序）。
 */

/** 选人数据最小接口（M4 选人器行子集：username/_id/name）。 */
data class RoomMemberLike(
    val username: String?,
    val id: String?,
    val name: String?,
)

/** buildVoiceReceivers.ts:4-21：排除本人、映射 receiver 字段。 */
fun buildVoiceReceivers(members: List<RoomMemberLike>, currentUsername: String): List<VoiceChatReceiver> {
    return members
        .mapNotNull { member ->
            val username = member.username ?: return@mapNotNull null
            if (username.isEmpty() || username == currentUsername) return@mapNotNull null
            VoiceChatReceiver(
                receiver = username,
                receiverAppiaId = member.id,
                name = member.name ?: username,
            )
        }
}
