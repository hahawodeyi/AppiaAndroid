package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** RN IClawAgentCatalogItem（types/clawAgent.ts）选人器所需子集（管理字段 M5+ 域）。 */
data class ClawAgentItem(
    val id: String,
    val username: String,
    val name: String,
    val active: Boolean,
)

/**
 * Claw agents 目录（RN services/api/clawAgents.ts fetchClawAgents +
 * lib/agents/clawAgents.ts orderClawAgents/filterClawAgents 对照）。
 * M4 简化：**仅选择**——编辑/禁用/恢复（appia.updateClawAgent/deleteClawAgent/restoreClawAgent
 * 与 AgentEditor 导航）归 M5+，本层不落地。
 */
object ClawAgentsApi {

    /** RN fetchClawAgents（clawAgents.ts:83-90）：`GET appia.getClawAgents?offset=0&count=50`。 */
    suspend fun fetchClawAgents(sdk: RocketSdk): List<ClawAgentItem> =
        parseClawAgents(sdk.get("appia.getClawAgents", mapOf("offset" to "0", "count" to "50")))

    /** RN orderClawAgents（lib/agents/clawAgents.ts:88-89）：active 在前（稳定）。 */
    fun orderAgents(items: List<ClawAgentItem>): List<ClawAgentItem> =
        items.sortedBy { if (it.active) 0 else 1 }

    /** RN filterClawAgents（lib/agents/clawAgents.ts:101-113）：name/username 局部匹配。 */
    fun filterAgents(items: List<ClawAgentItem>, keyword: String): List<ClawAgentItem> {
        val q = keyword.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { it.name.lowercase().contains(q) || it.username.lowercase().contains(q) }
    }
}

/** RN extractRows + mapClawAgentToCatalogItem（clawAgents.ts:26-81）：三层 envelope 兼容。 */
internal fun parseClawAgents(raw: JsonElement): List<ClawAgentItem> {
    val rows = when (raw) {
        is JsonArray -> raw
        is JsonObject -> {
            (raw["agents"] as? JsonArray)
                ?: (raw["data"] as? JsonArray)
                ?: ((raw["data"] as? JsonObject)?.get("agents") as? JsonArray)
                ?: emptyList()
        }
        else -> emptyList()
    }
    return rows.mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        fun str(key: String): String? =
            (row[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.contentOrNull?.trim()?.ifEmpty { null }
        val username = str("username") ?: return@mapNotNull null
        val id = str("_id") ?: str("id") ?: username
        val name = str("appiaOpenClawName") ?: str("name") ?: username
        val status = str("status")?.lowercase()
        val active = (row["active"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: (status != "disabled" && status != "inactive")
        ClawAgentItem(id, username, name, active)
    }
}

// ── 合作伙伴（RN services/api/partners.ts fetchPartners）──

/**
 * 合作伙伴目录（RN usePartners → `GET channels.getPartnerChannel`）。
 * 响应形态：`{partners: {<companyKey>: {companyName|name, usersArray: [{_id,username,name}]}}}`。
 */
object PartnersApi {

    /** RN fetchPartners（partners.ts:8-11）：raw.partners 兜底原对象。 */
    suspend fun fetchPartners(sdk: RocketSdk): JsonElement? {
        val raw = sdk.get("channels.getPartnerChannel")
        return (raw as? JsonObject)?.get("partners") ?: raw
    }
}
