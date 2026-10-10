package cn.appia.im.feature.agents

import cn.appia.im.core.network.api.ClawAgentItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Claw Agent 管理纯逻辑（RN lib/agents/clawAgents.ts 移植）：
 * 快捷添加解析（Base64/JSON 双形态）+ 重复 id 错误识别 + 管理权限判定。
 * bot username 生成与网络 POST 在 [cn.appia.im.core.network.api.ClawAgentsApi]，
 * UI 在 feature/agents/ui。
 */

/** RN ClawAgentQuickAddParseError：'empty' | 'invalid_json' | 'missing_fields'。 */
enum class ClawAgentQuickAddError { EMPTY, INVALID_JSON, MISSING_FIELDS }

/** RN ClawAgentQuickAddParseResult：命中返回三字段配置，否则错误码。 */
sealed class ClawAgentQuickAddResult {
    data class Ok(val agentId: String, val apiKey: String, val url: String) : ClawAgentQuickAddResult()
    data class Failed(val error: ClawAgentQuickAddError) : ClawAgentQuickAddResult()
}

private val quickAddJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun trimmedString(value: kotlinx.serialization.json.JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.contentOrNull?.trim()?.ifEmpty { null }

/**
 * RN parseClawAgentQuickAddInput（clawAgents.ts:22-70）：原始输入先按原文、再按去空白后
 * Base64 解码（长度 %4 != 1 才试，解码以 `{` 开头才采纳）双候选解析 JSON；
 * agentId + 密钥（apiSecret??apiKey）+ 地址（streamUrl??url??serviceUrl??appiaOpenClawBaseurl）
 * 缺一即 missing_fields（先于后续候选短路，RN 同）。
 */
fun parseClawAgentQuickAddInput(
    raw: String,
    decodeBase64: (String) -> String,
): ClawAgentQuickAddResult {
    val trimmedRaw = raw.trim()
    if (trimmedRaw.isEmpty()) return ClawAgentQuickAddResult.Failed(ClawAgentQuickAddError.EMPTY)

    val compactRaw = trimmedRaw.replace(Regex("\\s+"), "")
    val candidates = mutableListOf(trimmedRaw)
    if (compactRaw.length % 4 != 1) {
        val decoded = runCatching { decodeBase64(compactRaw) }.getOrNull()
        if (decoded?.startsWith("{") == true) candidates.add(0, decoded)
    }

    for (candidate in candidates) {
        if (!candidate.startsWith("{")) continue
        val value = runCatching { quickAddJson.parseToJsonElement(candidate) }.getOrNull()
            ?: continue
        if (value !is JsonObject) continue
        val agentId = trimmedString(value["agentId"])
        val apiKey = trimmedString(value["apiSecret"]) ?: trimmedString(value["apiKey"])
        val url = trimmedString(value["streamUrl"])
            ?: trimmedString(value["url"])
            ?: trimmedString(value["serviceUrl"])
            ?: trimmedString(value["appiaOpenClawBaseurl"])
        if (agentId == null || apiKey == null || url == null) {
            return ClawAgentQuickAddResult.Failed(ClawAgentQuickAddError.MISSING_FIELDS)
        }
        return ClawAgentQuickAddResult.Ok(agentId, apiKey, url)
    }
    return ClawAgentQuickAddResult.Failed(ClawAgentQuickAddError.INVALID_JSON)
}

/** RN isDuplicateClawAgentIdError（clawAgents.ts:115）：服务端重复 id 错误文案识别（不区分大小写）。 */
fun isDuplicateClawAgentIdError(message: String): Boolean =
    Regex("appia(?:OpenClawAgentId|ClawAgentUniqueId)[\\s:]*already exists", RegexOption.IGNORE_CASE)
        .containsMatchIn(message)

/** RN canManageClawAgent（clawAgents.ts:91-94）：登录者非空且（目录行未标创建者 或 创建者即本人）。 */
fun canManageClawAgent(authUserId: String, item: ClawAgentItem): Boolean =
    authUserId.isNotBlank() && (item.creatorUserId == null || item.creatorUserId == authUserId)
