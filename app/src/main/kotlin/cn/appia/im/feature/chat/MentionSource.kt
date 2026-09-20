package cn.appia.im.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 选中回插 mention 的成员（RN MentionSelectedMember：id==username，displayName=name 回退 username）。
 * java.io.Serializable：savedStateHandle 跨屏结果投递（MENTION_SELECTED_KEY）所需。 */
data class MentionCandidate(
    val id: String,
    val username: String,
    val displayName: String,
) : java.io.Serializable

/** RN IRoomMemberRow 子集（mention 候选只用这几列）。 */
data class RoomMemberRow(
    val _id: String,
    val username: String,
    val name: String?,
    val orderNumber: Double? = null,
)

/** RN ALL_MEMBER（MentionSuggestion index.tsx:41-45）：写死首项。 */
val ALL_MEMBER = MentionCandidate(id = "all", username = "all", displayName = "all")

// ── GET appia/room/members/v2 解析（RN parseAppiaRoomMembersV2 的 members 子集）──

/**
 * 解析 `appia/room/members/v2` 响应（`data: [{map, members, departments, isLocal}]` 块）→
 * 排序去重成员集。RN 的 listRows/组织头/部门块是房间成员页的渲染面，mention 候选只用
 * `parsed.members`：块内 `members`/`departments[].members` 的 username 收集 → map 解析
 * （缺 map 条目以 username 兜底成行，RN resolveUser 同款）→ [sortMembers]。
 */
fun parseAppiaRoomMembersV2(raw: JsonElement?): List<RoomMemberRow> {
    val blocks = (raw as? JsonObject)?.get("data") as? JsonArray
        ?: (raw as? JsonArray)
        ?: return emptyList()

    val userMap = LinkedHashMap<String, RoomMemberRow>()
    val usernameSet = LinkedHashSet<String>()

    for (blockEl in blocks) {
        val block = blockEl as? JsonObject ?: continue
        val isLocal = (block["isLocal"] as? JsonPrimitive)?.booleanOrNull == true
        (block["map"] as? JsonObject)?.forEach { (key, userEl) ->
            val user = userEl as? JsonObject ?: return@forEach
            val uname = ((user["username"] as? JsonPrimitive)?.contentOrNull ?: key).trim()
            if (uname.isEmpty()) return@forEach
            // isLocal 块覆盖非本地块（RN :101-104：!userMap[uname] || block.isLocal 才写）
            if (!userMap.containsKey(uname) || isLocal) {
                userMap[uname] = RoomMemberRow(
                    _id = (user["_id"] as? JsonPrimitive)?.contentOrNull ?: uname,
                    username = uname,
                    name = (user["name"] as? JsonPrimitive)?.contentOrNull,
                    orderNumber = (user["orderNumber"] as? JsonPrimitive)?.doubleOrNull,
                )
            }
        }
        collectBlockUsernames(block, usernameSet)
    }

    // map 无条目的 username 兜底成行（RN resolveUser：`userMap[trimmed] ?? {_id,name,username:trimmed}`）
    return usernameSet.map { u -> userMap[u] ?: RoomMemberRow(_id = u, username = u, name = u) }
        .sortedWith(::sortMembers)
}

private fun collectBlockUsernames(block: JsonObject, out: LinkedHashSet<String>) {
    for (m in block["members"] as? JsonArray ?: emptyList()) {
        val s = (m as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
        if (s.isNotEmpty()) out.add(s)
    }
    for (dep in block["departments"] as? JsonArray ?: emptyList()) {
        val d = dep as? JsonObject ?: continue
        for (m in d["members"] as? JsonArray ?: emptyList()) {
            val s = (m as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
            if (s.isNotEmpty()) out.add(s)
        }
    }
}

/** RN sortMembers（parseAppiaRoomMembersV2.ts:23-27）：orderNumber 升序（缺省 9999），同名回退 name/username 字典序。 */
internal fun sortMembers(a: RoomMemberRow, b: RoomMemberRow): Int {
    val ao = a.orderNumber ?: 9999.0
    val bo = b.orderNumber ?: 9999.0
    if (ao != bo) return ao.compareTo(bo)
    val an = a.name ?: a.username
    val bn = b.name ?: b.username
    return (an).compareTo(bn)
}

// ── Agent 房间 @ 候选（RN lib/ai/agentBotList.ts 逐条）──

data class AgentBotMentionItem(val username: String, val name: String)

/**
 * 解析 `Agent_Bot_List` 设置（RN parseAgentBotMentionList）：JSON 数组（字符串或 {username,name}）
 * 或逗号/换行分隔纯列表；去重保序，name 缺省回退 username。
 */
fun parseAgentBotMentionList(raw: String?): List<AgentBotMentionItem> {
    if (raw.isNullOrBlank()) return emptyList()
    val trimmed = raw.trim()

    if (trimmed.startsWith("[")) {
        runCatching {
            val parsed = Json.parseToJsonElement(trimmed) as? JsonArray ?: return@runCatching
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<AgentBotMentionItem>()
            for (it in parsed) {
                var username = ""
                var name = ""
                when (it) {
                    is JsonPrimitive -> if (it.isString) {
                        username = it.content.trim()
                        name = username
                    }
                    is JsonObject -> {
                        username = (it["username"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() ?: ""
                        name = (it["name"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() ?: ""
                    }
                    else -> Unit
                }
                if (username.isEmpty() || !seen.add(username)) continue
                result += AgentBotMentionItem(username, name.ifEmpty { username })
            }
            return result
        }
        // 非合法 JSON 回退分隔符切分
    }

    val seen = LinkedHashSet<String>()
    val result = mutableListOf<AgentBotMentionItem>()
    for (part in trimmed.split(",", "\n")) {
        val u = part.trim()
        if (u.isEmpty() || !seen.add(u)) continue
        result += AgentBotMentionItem(u, u)
    }
    return result
}

/**
 * 解析 `Appia_Claw_Agent_Visibility`（RN parseClawAgentVisibilityMap）：JSON 对象
 * `{username: 'Creator'|'All'|'Hidden'|'Users'}`；坏 JSON/非对象 → 空 map。
 */
fun parseClawAgentVisibilityMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
    return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
}

/** RN filterBotsByClawAgentVisibility：未配置恒显示；配置过的仅 `All` 显示。 */
fun <T> filterBotsByClawAgentVisibility(botList: List<T>, visibilityMap: Map<String, String>, usernameOf: (T) -> String): List<T> {
    if (botList.isEmpty()) return emptyList()
    return botList.filter { bot ->
        val scope = visibilityMap[usernameOf(bot)]
        scope == null || scope == "All"
    }
}

/** Agent 房间候选 → MentionCandidate（RN :68 把 bot 映射为 {_id:username, username, name}）。 */
fun agentBotsToCandidates(bots: List<AgentBotMentionItem>): List<MentionCandidate> =
    bots.map { MentionCandidate(id = it.username, username = it.username, displayName = it.name) }

/**
 * mention 候选管线（RN MentionSuggestion filteredMembers :110-118）：query 去空格小写后
 * name/username `includes` 过滤；[includeAllMember] 时 [ALL_MEMBER] 写死首项（RN :243 header 仅
 * 非 agent 房渲染）。
 */
fun buildMentionCandidates(
    all: List<MentionCandidate>,
    query: String,
    includeAllMember: Boolean,
): List<MentionCandidate> {
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) all else all.filter { m ->
        m.displayName.lowercase().contains(q) || m.username.lowercase().contains(q)
    }
    return if (includeAllMember) listOf(ALL_MEMBER) + filtered else filtered
}
