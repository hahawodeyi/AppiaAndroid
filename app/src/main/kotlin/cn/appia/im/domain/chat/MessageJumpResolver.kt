package cn.appia.im.domain.chat

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.MessageJumpApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** RN src/lib/message/jumpToMessage/types.ts JumpMessageTarget。 */
data class JumpMessageTarget(val id: String, val rid: String)

/**
 * 跳转目标解析（M5-T6 / RN src/lib/message/jumpToMessage/resolveMessageForJump.ts :9-26 逐行）：
 * 本地 messages 表按 `_id` find →（行 `_id`/`rid` 皆非空）即取；本地 miss → REST
 * `chat.getMessage {msgId}` 回退（`message._id` 与 `message.rid` 为字符串才算命中）；两处皆空 → null。
 */
suspend fun resolveMessageForJump(
    db: AppiaDatabase,
    sdk: RocketSdk,
    messageId: String,
): JumpMessageTarget? {
    val local = db.messageDao().getById(messageId)
    if (local != null && local._id.isNotEmpty() && local.rid.isNotEmpty()) {
        return JumpMessageTarget(local._id, local.rid)
    }
    val remote = MessageJumpApi.getChatMessage(sdk, messageId) ?: return null
    val id = remote.str("_id") ?: return null
    val rid = remote.str("rid") ?: return null
    return JumpMessageTarget(id, rid)
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
