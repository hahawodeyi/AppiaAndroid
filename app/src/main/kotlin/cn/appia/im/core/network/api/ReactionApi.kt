package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 表情回应（绿地功能，RN 版未实现——Android 领先，RN 端不可见为预期）：
 * `POST chat.react` body `{emoji, messageId}`，字段名逐字对照 legacy appiaim-ios
 * `lib/services/restApi.ts:383-385` setReaction。**toggle 语义在服务端**：同一调用按当前状态
 * 加/删自己的回应，本地零裁定；结果经 `stream-room-messages` 回推落库
 * （MessageUpsert 被动持久化 reactions 列，T6 语义）。
 * `emoji` 传 shortname（如 `:tada:`；legacy onReactionPress 透传 reaction.emoji/emoji.name 同口径）。
 */
object ReactionApi {

    suspend fun react(sdk: RocketSdk, emoji: String, messageId: String) {
        sdk.post("chat.react", buildJsonObject {
            put("emoji", emoji)
            put("messageId", messageId)
        })
    }
}
