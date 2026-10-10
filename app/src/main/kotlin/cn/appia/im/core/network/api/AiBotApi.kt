package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** RN services/api/ai.ts saveAiMessage 参数（SaveAiMessageParams）。 */
data class SaveAiMessageParams(
    val rid: String,
    /** 当前用户 username（RN toUsername = authStore.user.username） */
    val toUsername: String,
    /** 槽位 message id（服务端以同 id 落真实 ai_response 消息，DDP 回流替换槽位） */
    val mid: String,
    val botName: String,
    val content: String,
    val inAgentRoom: Boolean = false,
)

/**
 * AI bot 端点（RN services/api/ai.ts）：
 * - [saveAiMessage]：`POST bot.saveAIMessage`——流式 finalize 的持久化回写；服务端落一条
 *   msgType='ai_response' 真实消息经既有 DDP 消息流回流，同 id 替换槽位（坑 5 双兜底的一侧）；
 * - [stopToStaffServiceAgent]：`POST bot.stopToStaffServiceAgent {rid}`——staffService 人工
 *   停止端点（stopAiProcessing :15-18；该域本身归 M10，端点随 stopAiProcessing 完整保留）。
 * bot.docs（fastModelMsg 引文）随 staffService 域划出，不落。
 */
object AiBotApi {

    suspend fun saveAiMessage(sdk: RocketSdk, params: SaveAiMessageParams): JsonElement =
        sdk.post(
            "bot.saveAIMessage",
            buildJsonObject {
                put("rid", params.rid)
                put("toUsername", params.toUsername)
                put("mid", params.mid)
                put("botName", params.botName)
                put("msgType", "ai_response")
                put("msg", buildJsonObject { put("content", params.content) })
                put("inAgentRoom", params.inAgentRoom)
            },
        )

    suspend fun stopToStaffServiceAgent(sdk: RocketSdk, rid: String): JsonElement =
        sdk.post("bot.stopToStaffServiceAgent", buildJsonObject { put("rid", rid) })
}
