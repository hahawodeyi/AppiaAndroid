package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 转发消息（RN src/services/api/messages.ts:74-89 forwardMessage 逐字段）：
 * `POST chat.sendMessage`，body `{message:{forwardUsers?, forwardRooms?, forwardMessageIds,
 * isForwardMessage, isForwardMerged}}`——**不含 rid/msg，内容由服务端组装**。
 *
 * 发送路径裁定（RN ForwardSelectScreen handleConfirm :356-390 源读）：转发**直接 REST
 * sendMessage，绕过 SendOrchestrator**——不落本地 QUEUED 行、无本地回显，消息由服务端
 * 创建并经目标房间的 `stream-room-messages` 推送出现。
 */
object ForwardApi {

    /**
     * RN handleConfirm 恒传四参（forwardUsers/forwardRooms 为数组、可空集）；
     * [isForwardMerged] = 合并转发（RN route.params.isMerged）。
     */
    suspend fun forwardMessage(
        sdk: RocketSdk,
        forwardMessageIds: List<String>,
        forwardUsers: List<String> = emptyList(),
        forwardRooms: List<String> = emptyList(),
        isForwardMerged: Boolean = false,
    ): JsonElement = sdk.post("chat.sendMessage", buildJsonObject {
        put("message", buildJsonObject {
            put("forwardUsers", JsonArray(forwardUsers.map(::JsonPrimitive)))
            put("forwardRooms", JsonArray(forwardRooms.map(::JsonPrimitive)))
            put("forwardMessageIds", JsonArray(forwardMessageIds.map(::JsonPrimitive)))
            put("isForwardMessage", true)
            put("isForwardMerged", isForwardMerged)
        })
    })
}
