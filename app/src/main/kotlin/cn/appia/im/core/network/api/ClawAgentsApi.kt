package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** RN IClawAgentCatalogItem（types/clawAgent.ts）选人/管理所需子集。 */
data class ClawAgentItem(
    val id: String,
    val username: String,
    val name: String,
    val active: Boolean,
    /** RN appiaOpenClawAgentId（编辑表单回填）。 */
    val agentId: String? = null,
    /** RN appiaOpenClawBaseurl（编辑表单回填）。 */
    val serviceUrl: String? = null,
    /** RN appiaClawAgentCreatorUserId（canManageClawAgent 判定）。 */
    val creatorUserId: String? = null,
)

/** RN IClawAgentPayload（types/clawAgent.ts）：创建/编辑提交体（调用方已 trim）。 */
data class ClawAgentPayload(
    val name: String,
    val agentId: String,
    val url: String,
    val apiKey: String,
)

/**
 * RN buildClawBotUsername（lib/agents/clawAgents.ts:72-86）：`claw.<body>.bot`，
 * body = agentId||name||'claw' 小写、非法字符折叠为 `.`、首尾点剔除；body 过短补
 * base36 时间戳，超长截 48。与四 POST 同层（core 不依赖 feature）。
 */
fun buildClawBotUsername(payload: ClawAgentPayload, timestamp: Long = System.currentTimeMillis()): String {
    val normalizedBody = (payload.agentId.ifEmpty { payload.name }.ifEmpty { "claw" })
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), ".")
        .replace(Regex("\\.+"), ".")
        .trim('.')
    val timestampPart = timestamp.toString(36)
    var body = normalizedBody
    if (body.length < 2) {
        // RN :79-81：body 非空补 `.时间戳`，空则整个用时间戳
        body = if (body.isEmpty()) timestampPart else "$body.$timestampPart"
    }
    return "claw.${if (body.length > 55) body.take(48) else body}.bot"
}

/**
 * Claw agents 目录 + 管理动作（RN services/api/clawAgents.ts 全量对照：
 * fetchClawAgents + create/update/delete/restore 四 POST + ensureMutationSuccess）。
 */
object ClawAgentsApi {

    /** RN fetchClawAgents（clawAgents.ts:83-90）：`GET appia.getClawAgents?offset=0&count=50`。 */
    suspend fun fetchClawAgents(sdk: RocketSdk): List<ClawAgentItem> =
        parseClawAgents(sdk.get("appia.getClawAgents", mapOf("offset" to "0", "count" to "50")))

    /** RN orderClawAgents（lib/agents/clawAgents.ts:88-89）：active 在前（稳定）。 */
    fun orderAgents(items: List<ClawAgentItem>): List<ClawAgentItem> =
        items.sortedBy { if (it.active) 0 else 1 }

    /** RN filterClawAgents（lib/agents/clawAgents.ts:101-113）：name/username/agentId/serviceUrl 四字段局部匹配。 */
    fun filterAgents(items: List<ClawAgentItem>, keyword: String): List<ClawAgentItem> {
        val q = keyword.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter {
            it.name.lowercase().contains(q) || it.username.lowercase().contains(q) ||
                it.agentId?.lowercase()?.contains(q) == true || it.serviceUrl?.lowercase()?.contains(q) == true
        }
    }

    /**
     * RN createClawAgentWithGeneratedUsername（clawAgents.ts:121-140）：username 客户端生成
     * （buildClawBotUsername），成功返回 {username}；success:false 抛错（含服务端重复 id 文案，
     * 供 isDuplicateClawAgentIdError 识别）。
     */
    suspend fun createClawAgent(
        sdk: RocketSdk,
        payload: ClawAgentPayload,
        timestamp: Long = System.currentTimeMillis(),
    ): String {
        val username = buildClawBotUsername(payload, timestamp)
        val response = sdk.post(
            "appia.createClawAgents",
            buildJsonObject {
                put("username", username)
                put("appiaOpenClawName", payload.name.trim())
                put("appiaOpenClawBaseurl", payload.url.trim())
                put("appiaOpenClawApiSecret", payload.apiKey.trim())
                put("appiaOpenClawAgentId", payload.agentId.trim())
            },
        )
        ensureMutationSuccess(response)
        return username
    }

    /** RN updateClawAgent（clawAgents.ts:142-160）：apiKey 留空则不携带密钥字段（沿用旧密钥）。 */
    suspend fun updateClawAgent(sdk: RocketSdk, id: String, payload: ClawAgentPayload) {
        val response = sdk.post(
            "appia.updateClawAgent",
            buildJsonObject {
                put("_id", id)
                put("appiaOpenClawName", payload.name.trim())
                put("appiaOpenClawBaseurl", payload.url.trim())
                put("appiaOpenClawAgentId", payload.agentId.trim())
                if (payload.apiKey.trim().isNotEmpty()) put("appiaOpenClawApiSecret", payload.apiKey.trim())
            },
        )
        ensureMutationSuccess(response)
    }

    /** RN disableClawAgent（clawAgents.ts:162-166）：`POST appia.deleteClawAgent {_id}`（软删=禁用）。 */
    suspend fun disableClawAgent(sdk: RocketSdk, id: String) {
        ensureMutationSuccess(sdk.post("appia.deleteClawAgent", buildJsonObject { put("_id", id) }))
    }

    /** RN restoreClawAgent（clawAgents.ts:168-172）：`POST appia.restoreClawAgent {_id}`。 */
    suspend fun restoreClawAgent(sdk: RocketSdk, id: String) {
        ensureMutationSuccess(sdk.post("appia.restoreClawAgent", buildJsonObject { put("_id", id) }))
    }
}

/** RN ensureMutationSuccess（clawAgents.ts:20-28）：success===false 抛 message||error||兜底。 */
private fun ensureMutationSuccess(response: JsonElement) {
    val obj = response as? JsonObject ?: return
    val success = (obj["success"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    if (success != false) return
    fun str(key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.contentOrNull?.trim()
    throw IllegalStateException(str("message") ?: str("error") ?: "Claw Agent request failed")
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
