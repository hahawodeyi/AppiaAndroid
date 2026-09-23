package cn.appia.im.core.chat

import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** RN CreateChannelMode（createChannelSelection.ts:1）：members | org。 */
enum class CreateChannelMode { MEMBERS, ORG }

/**
 * RN canConfirmCreateChannel（createChannelSelection.ts:3-20）逐行移植：
 * - members：all（全员自动加入）恒可确认；否则 includeMe + 至少 1 个非自己（userCount >= 2）
 * - org：允许混合 users + depIds——人数 >= 2 或任一部门即真
 */
fun canConfirmCreateChannel(
    mode: CreateChannelMode,
    all: Boolean,
    selectedUsernames: Set<String>,
    selectedDepIds: Set<String>,
): Boolean {
    val userCount = selectedUsernames.size
    val depCount = selectedDepIds.size
    return if (mode == CreateChannelMode.MEMBERS) {
        if (all) return true
        userCount >= 2
    } else {
        userCount >= 2 || depCount > 0
    }
}

/**
 * RN canConfirmAddToRoom（createChannelSelection.ts:22-32）逐行移植：
 * 任一部门被选即真；否则存在任一「非既有成员」的用户即真。
 */
fun canConfirmAddToRoom(
    existingUsernames: Set<String>,
    selectedUsernames: Set<String>,
    selectedDepIds: Set<String>,
): Boolean {
    if (selectedDepIds.isNotEmpty()) return true
    return selectedUsernames.any { it !in existingUsernames }
}

// ── 最近联系成员源（RN useRecentContacts map 语义的纯函数半；t='d' 过滤归 ChatDao 查询）──

/** RN RecentContactRow：username（建频道 users[] 用）+ displayName。 */
data class RecentContactRow(val username: String, val displayName: String)

/** RN parseJsonStringArray（useRecentContacts.ts:23-34）：JSON 字符串数组，非法容忍空。 */
internal fun parseJsonStringArray(raw: String?): List<String> {
    if (raw == null) return emptyList()
    return runCatching {
        (Json.parseToJsonElement(raw) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()
    }.getOrDefault(emptyList())
}

/**
 * RN useRecentContacts map（:51-70）：从 usernames（JSON）选出「对方」username
 * （排除自己，回退 name 列）；去重（保留首个）；displayName = fname || other。
 * chats 入参须已按 t='d'/非 bot/未归档/open/room_updated_at 倒序过滤（DAO 层）。
 */
fun mapRecentContactRows(chats: List<ChatEntity>, me: String): List<RecentContactRow> {
    val out = mutableListOf<RecentContactRow>()
    val seen = mutableSetOf<String>()
    for (chat in chats) {
        val usernames = parseJsonStringArray(chat.usernames)
        val other = usernames.firstOrNull { it.isNotEmpty() && it != me }
            ?: chat.name.trim()
        if (other.isEmpty() || !seen.add(other)) continue
        out += RecentContactRow(other, chat.fname.trim().ifEmpty { other })
    }
    return out
}

// ── 合作伙伴（RN partnerGroups/togglePartnerGroupAll/getPartnerGroupCheckboxState 语义）──

/** RN PartnerUser（name 为空时展示回退 username）。 */
data class PartnerContact(val username: String, val name: String?)

/** RN PartnerCompanyGroup。 */
data class PartnerGroup(val key: String, val title: String, val users: List<PartnerContact>)

/** RN CheckboxState（components/ui/Checkbox）：三态。 */
enum class PartnerCheckState { UNCHECKED, CHECKED, INDETERMINATE }

/** RN getPartnerGroupCheckboxState（:678-691）：无成员→unchecked；全选→checked；部分→indeterminate。 */
fun partnerGroupCheckState(group: PartnerGroup, selectedUsernames: Set<String>): PartnerCheckState {
    val usernames = group.users.map { it.username }
    if (usernames.isEmpty()) return PartnerCheckState.UNCHECKED
    val hit = usernames.count { it in selectedUsernames }
    return when {
        hit == 0 -> PartnerCheckState.UNCHECKED
        hit == usernames.size -> PartnerCheckState.CHECKED
        else -> PartnerCheckState.INDETERMINATE
    }
}

/**
 * RN togglePartnerGroupAll（:693-709）：checked → 全清（自己保留）；否则（unchecked/
 * indeterminate）→ 全补。空组 no-op。
 */
fun togglePartnerGroupUsers(
    group: PartnerGroup,
    selectedUsernames: Set<String>,
    me: String,
): Set<String> {
    val usernames = group.users.map { it.username }.filter { it.isNotEmpty() }
    if (usernames.isEmpty()) return selectedUsernames
    val next = selectedUsernames.toMutableSet()
    if (usernames.all { it in selectedUsernames }) {
        for (u in usernames) {
            if (u != me) next.remove(u)
        }
    } else {
        next.addAll(usernames)
    }
    return next
}

/** RN partnerGroups 查询过滤（:670）：`${name}${username}`.toLowerCase().includes(q)。 */
fun filterPartnerGroups(groups: List<PartnerGroup>, query: String): List<PartnerGroup> =
    if (query.isBlank()) {
        groups
    } else {
        groups.mapNotNull { g ->
            val users = g.users.filter {
                "${it.name.orEmpty()}${it.username}".lowercase().contains(query)
            }
            if (users.isEmpty()) null else g.copy(users = users)
        }
    }

/**
 * RN partnerGroups useMemo（CreateChannelMembersScreen :664-674）：PartnersApi 原始
 * `{<key>: {companyName|name, usersArray:[{_id,username,name}]}}` → 组列表。
 * title = companyName ?? name ?? key，按 title 排序（JS localeCompare('en') ≈ 大小写不敏感码点序）。
 */
fun mapPartnerGroups(raw: JsonElement?): List<PartnerGroup> {
    val obj = raw as? JsonObject ?: return emptyList()
    val groups = mutableListOf<PartnerGroup>()
    for ((k, v) in obj) {
        val row = v as? JsonObject ?: continue
        val users = (row["usersArray"] as? JsonArray)?.mapNotNull { el ->
            val u = el as? JsonObject ?: return@mapNotNull null
            val username = (u["username"] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                ?.contentOrNull?.trim().orEmpty()
            if (username.isEmpty()) return@mapNotNull null
            val name = (u["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                ?.contentOrNull?.trim()?.ifEmpty { null }
            PartnerContact(username, name)
        }.orEmpty()
        if (users.isEmpty()) continue
        val title = (row["companyName"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }
            ?: (row["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }
            ?: k
        groups += PartnerGroup(k, title, users)
    }
    return groups.sortedBy { it.title.lowercase() }
}
