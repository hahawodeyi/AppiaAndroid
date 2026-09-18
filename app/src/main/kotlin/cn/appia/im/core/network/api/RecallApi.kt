package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 撤回/编辑端点（RN src/services/api/messages.ts 逐字段）：
 * - [recallMessage]：`POST message.recall {id}`（RN :41-42）→ 服务端标记撤回 → DDP 回推
 *   t='rollback-message'（本地不硬删，等回推落库）；
 * - [messageBatchRecall]：`POST message.batch.recall {ids}`（RN :49-50，对齐 legacy iOS
 *   restApi.ts:1414）；
 * - [editMessage]：DDP callMethod `updateMessage {rid,_id,msg[,md]}`（RN :95-106；
 *   编辑 UI 完整形态是 T12，本任务只落端点+菜单判定+入口回调）。
 */
object RecallApi {

    suspend fun recallMessage(sdk: RocketSdk, id: String): JsonElement =
        sdk.post("message.recall", buildJsonObject { put("id", id) })

    suspend fun messageBatchRecall(sdk: RocketSdk, ids: List<String>): JsonElement =
        sdk.post("message.batch.recall", buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) })

    suspend fun editMessage(
        sdk: RocketSdk,
        rid: String,
        messageId: String,
        msg: String,
        md: JsonElement? = null,
    ): JsonElement? = sdk.methodCall("updateMessage", listOf(buildJsonObject {
        put("rid", rid)
        put("_id", messageId)
        put("msg", msg)
        if (md != null) put("md", md)
    }))
}
