package cn.appia.im.core.messaging

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 发送文本消息（RN `src/services/api/messages.ts:26-35` sendTextMessage）：
 * `POST /api/v1/chat.sendMessage`，body `{message:{_id,rid,msg}}`。
 * `_id` = 本地 tempId 作幂等键（服务端据此创建消息）；**`md` 完全省略**——RN 条件展开
 * `...(md ? {md} : {})`，纯文本路径不传，wire 兼容。
 */
object MessagesApi {

    suspend fun sendTextMessage(sdk: RocketSdk, rid: String, msg: String, id: String): JsonElement =
        sdk.post("chat.sendMessage", buildJsonObject {
            put("message", buildJsonObject {
                put("_id", id)
                put("rid", rid)
                put("msg", msg)
            })
        })
}
