package cn.appia.im.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** bot → SSE 端点（RN lib/ai/botConfig.ts BotEndpoints）。 */
data class BotEndpoints(val stream: String, val stop: String? = null)

/** botConfig.ts:4-10 内置端点表（staffService 客服域的 stop 端点归 M10 使用，映射表保持完整）。 */
private val BOT_CONFIG: Map<String, BotEndpoints> = mapOf(
    "staffService.bot" to BotEndpoints(
        stream = "/api/v1/bot.saveToStaffServiceAgent",
        stop = "/api/v1/bot.stopToStaffServiceAgent",
    ),
    "agent.bot" to BotEndpoints(stream = "/api/v1/bot.sendToAI"),
    "personal.bot" to BotEndpoints(stream = "/api/v1/bot.sendToAI"),
)

/**
 * RN botConfig.ts:13-23 resolveBotEndpoints：内置表优先；其余 `Agent_Bot_List` 成员走
 * saveToClawAgent（RN 的 `!== 'staffService.bot'` 守卫在查表命中后不可达）；否则 null（不触发）。
 */
fun resolveBotEndpoints(botUsername: String, agentBotList: Collection<String>): BotEndpoints? {
    BOT_CONFIG[botUsername]?.let { return it }
    if (botUsername in agentBotList) return BotEndpoints(stream = "/api/v1/bot.saveToClawAgent")
    return null
}

/**
 * 解析 `Agent_Bot_List` 设置为 username 列表（RN useAiTrigger.ts parseAgentBotList）：
 * JSON 字符串数组或逗号/换行分隔纯列表；trim 去重保序；非法 JSON 回退分隔符切分。
 * 对象形态（带 name）归 feature/chat 的 parseAgentBotMentionList（@ 候选展示用）。
 */
fun parseAgentBotList(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()

    if (trimmed.startsWith("[")) {
        val array = runCatching { Json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
        if (array != null) {
            val seen = LinkedHashSet<String>()
            for (el in array) {
                val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                if (s.isNotEmpty()) seen.add(s)
            }
            return seen.toList()
        }
        // 非法 JSON / 非数组 → 回退分隔符切分
    }

    val seen = LinkedHashSet<String>()
    for (part in trimmed.split(",", "\n")) {
        val s = part.trim()
        if (s.isNotEmpty()) seen.add(s)
    }
    return seen.toList()
}
