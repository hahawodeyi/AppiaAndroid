package cn.appia.im.core.network.api

import cn.appia.im.core.messaging.MessageUpsert
import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** RN src/lib/message/jumpToMessage/loadSurroundingMessages.ts :34-38 SurroundingMessagesResult（wire 原始形态）。 */
data class SurroundingRaw(
    val apiMessages: List<JsonObject>,
    val moreBefore: Boolean,
    val moreAfter: Boolean,
)

/**
 * 消息跳转 wire（M5-T6 / 逐行为对照 appiaMobile）：
 * - [getChatMessage]：REST `GET chat.getMessage {msgId}`（RN services/api/messages.ts :7-20）——
 *   消息定位时解析单条消息（resolveMessageForJump 的远程回退）；
 * - [loadSurrounding]：Meteor `loadSurroundingMessages`，参数 `{_id, rid}, 50`
 *   （RN src/lib/message/jumpToMessage/loadSurroundingMessages.ts :44-48 的
 *   `sdk.callMethod('loadSurroundingMessages', { _id, rid }, 50)`——RN 走 DDP 直调，
 *   Android 按库内统一裁定经 [RocketSdk.methodCall] REST 信封执行）。
 */
object MessageJumpApi {

    /** RN SURROUNDING_COUNT = 50。 */
    const val SURROUNDING_COUNT = 50

    /** REST chat.getMessage：响应 `message` 字段（RN isApiMessage 门：`_id` 为字符串才算数）。 */
    suspend fun getChatMessage(sdk: RocketSdk, msgId: String): JsonObject? {
        val res = sdk.get("chat.getMessage", mapOf("msgId" to msgId))
        return MessageUpsert.isApiMessage((res as? JsonObject)?.get("message"))
    }

    /** Meteor loadSurroundingMessages：`{messages: [...], moreBefore, moreAfter}`。 */
    suspend fun loadSurrounding(
        sdk: RocketSdk,
        messageId: String,
        rid: String,
        count: Int = SURROUNDING_COUNT,
    ): SurroundingRaw {
        val raw = sdk.methodCall(
            "loadSurroundingMessages",
            listOf(
                buildJsonObject {
                    put("_id", messageId)
                    put("rid", rid)
                },
                JsonPrimitive(count),
            ),
        )
        val obj = raw as? JsonObject ?: return SurroundingRaw(emptyList(), false, false)
        val arr = (obj["messages"] as? JsonArray) ?: JsonArray(emptyList())
        return SurroundingRaw(
            apiMessages = arr.mapNotNull { MessageUpsert.isApiMessage(it) },
            moreBefore = obj.boolFlag("moreBefore"),
            moreAfter = obj.boolFlag("moreAfter"),
        )
    }

    /** RN `!!payload.moreBefore`：JSON 布尔或字符串 "true" 都按真值。 */
    private fun JsonObject.boolFlag(key: String): Boolean {
        val v = this[key] as? JsonPrimitive ?: return false
        return v.booleanOrNull ?: (v.contentOrNull == "true")
    }
}
