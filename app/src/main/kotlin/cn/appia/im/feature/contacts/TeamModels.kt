package cn.appia.im.feature.contacts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通讯录团队模型（RN src/lib/team/teamModels.ts 逐行移植）+
 * employeeUtils.getEmployeeDesc + presence 判定链（pickContactPresenceRaw/
 * mapContactStatusToTUserStatus/pickPresenceUserId/isRocketChatUserId/isBotUserId）。
 * 纯函数无 IO；UI 侧由 [ui.TeamScreen] 消费。
 */

/** RN TeamRootType。 */
enum class TeamRootType { PMT, L1D }

/** RN TEAM_ROOT_IDS：{pmt:'EMT-0', l1d:'EMT-1328'}。 */
val TEAM_ROOT_IDS: Map<TeamRootType, String> = mapOf(
    TeamRootType.PMT to "EMT-0",
    TeamRootType.L1D to "EMT-1328",
)

/** RN TeamEmploymentType 五类。 */
enum class TeamEmploymentType { FULL_TIME, OUTSOURCING, INTERNSHIP, PART_TIME, OTHER }

/** RN TeamEmploymentCounts（五键全量，键序固定保证 footer 拼接稳定）。 */
data class TeamEmploymentCounts(
    val fullTime: Int = 0,
    val outsourcing: Int = 0,
    val internship: Int = 0,
    val partTime: Int = 0,
    val other: Int = 0,
)

/** RN TeamUser（通讯录 userMap 行；字段全可空，解析宽容）。 */
data class TeamUser(
    val _id: String? = null,
    val username: String? = null,
    val name: String? = null,
    val fname: String? = null,
    val jobName: String? = null,
    val primaryOrgName: String? = null,
    val departments: List<String> = emptyList(),
    val status: String? = null,
    val onlineStatus: String? = null,
    val statusConnection: String? = null,
    val employmentType: String? = null,
    val employeeType: String? = null,
    val employeeStatus: String? = null,
)

/** RN TeamDepartment（departmentMap 行）。 */
data class TeamDepartment(
    val _id: String,
    val name: String,
    val type: String? = null,
    val parent: String? = null,
    val children: List<String> = emptyList(),
    val users: List<String> = emptyList(),
    val usersCountIncludeChildren: Int? = null,
    val countIncludeChildren: Map<String, Int> = emptyMap(),
)

/** RN TUserStatus（presence 展示态）。 */
enum class TUserStatus { ONLINE, AWAY, BUSY, OFFLINE }

/** RN TeamMember。 */
data class TeamMember(
    val id: String,
    val username: String,
    val displayName: String,
    val position: String,
    val departmentName: String,
    val onlineStatus: Boolean, // RN 'online'|'offline'（online/away → true）
    val employmentType: TeamEmploymentType,
    val isSelf: Boolean,
    val employeeDesc: String,
    val presenceUserId: String? = null,
    val presenceFallbackStatus: TUserStatus? = null,
)

/** RN TeamDeptSummary。 */
data class TeamDeptSummary(
    val id: String,
    val name: String,
    val tagLabel: String,
    val totalCount: Int,
    val onlineCount: Int,
    val isMine: Boolean,
    val members: List<TeamMember>,
)

/** RN TeamHomeModel。 */
data class TeamHomeModel(
    val rootType: TeamRootType,
    val rootId: String,
    val companyName: String,
    val rootTotalCount: Int,
    val rootEmploymentCounts: Map<String, Int>,
    val me: TeamMember?,
    val departments: List<TeamDeptSummary>,
    val members: List<TeamMember>,
    val totalCount: Int,
    val onlineCount: Int,
    val employmentCounts: TeamEmploymentCounts,
)

/** RN TeamDeptSection。 */
data class TeamDeptSection(val id: String, val title: String, val members: List<TeamMember>)

/** RN TeamDeptModel。 */
data class TeamDeptModel(
    val id: String,
    val name: String,
    val totalCount: Int,
    val onlineCount: Int,
    val employmentCounts: TeamEmploymentCounts,
    val sections: List<TeamDeptSection>,
    val members: List<TeamMember>,
)

/** RN TeamFlatListItem（sealed：dept | member）。 */
sealed interface TeamFlatListItem {
    data class Dept(val dept: TeamDeptSummary) : TeamFlatListItem
    data class Member(val member: TeamMember) : TeamFlatListItem
}

/** RN TeamFlatListModel。 */
data class TeamFlatListModel(
    val name: String,
    val deptId: String,
    val items: List<TeamFlatListItem>,
    val totalCount: Int,
    val directMemberCount: Int,
    val childDeptCount: Int,
    val employmentCounts: Map<String, Int>,
)

/** RN TeamHomeSearchResult。 */
data class TeamHomeSearchResult(val departments: List<TeamDeptSummary>, val members: List<TeamMember>)

// ── presence 判定链（RN lib/presence/*）──

private val METEOR_ID_RE = Regex("^[0-9a-zA-Z]{17}$")
private const val DEPT_KEY_PREFIX = "EMT-"

/** RN isBotUser.isBotUserId：含 '.bot' 即 bot。 */
internal fun isBotUserId(userId: String?): Boolean = userId?.contains(".bot") == true

/**
 * RN isRocketChatUserId：EMT- 部门 key / bot / 与 username 相同的 _id 排除；
 * 17 位 Meteor id 通过；含 '.' 的 username 形态排除；其余 ≥6 字符通过。
 */
internal fun isRocketChatUserId(id: String?, username: String? = null): Boolean {
    val trimmed = id?.trim().orEmpty()
    if (trimmed.isEmpty() || isBotUserId(trimmed)) return false
    if (trimmed.startsWith(DEPT_KEY_PREFIX)) return false
    val normalizedUsername = username?.trim().orEmpty()
    if (normalizedUsername.isNotEmpty() && trimmed == normalizedUsername) return false
    if (METEOR_ID_RE.matches(trimmed)) return true
    if (trimmed.contains('.')) return false
    return trimmed.length >= 6
}

/** RN pickPresenceUserId：user._id 优先（须真 RC id），否则 userMap key；仅 username → null（T9 resolve 链）。 */
internal fun pickPresenceUserId(user: TeamUser, userKey: String): String? {
    val fromUser = user._id?.trim().orEmpty()
    if (fromUser.isNotEmpty() && isRocketChatUserId(fromUser, user.username)) return fromUser
    val key = userKey.trim()
    if (key.isNotEmpty() && isRocketChatUserId(key, user.username)) return key
    return null
}

/** RN pickContactPresenceRaw：statusConnection ?? onlineStatus ?? status。 */
internal fun pickContactPresenceRaw(user: TeamUser): String? =
    user.statusConnection ?: user.onlineStatus ?: user.status

/** RN mapContactStatusToTUserStatus：在线四态；未知/空 → null。 */
internal fun mapContactStatusToTUserStatus(raw: String?): TUserStatus? = when (raw?.trim()?.lowercase()) {
    "online" -> TUserStatus.ONLINE
    "away" -> TUserStatus.AWAY
    "busy" -> TUserStatus.BUSY
    "offline" -> TUserStatus.OFFLINE
    else -> null
}

// ── employeeUtils.getEmployeeDesc（\u 转义过中文检查钩子）──

// 离职 / 试用 / 全职（\u 转义过中文检查钩子，RN employeeUtils.ts 同款）
private const val STATUS_RESIGNED = "\u79bb\u804c"
private const val STATUS_PROBATION = "\u8bd5\u7528"
private const val TYPE_FULLTIME = "\u5168\u804c"

/** RN getEmployeeDesc：离职→离职；试用+全职→试用；其它→employeeType 原样。 */
fun getEmployeeDesc(employeeStatus: String?, employeeType: String?): String {
    val status = employeeStatus.orEmpty()
    val type = employeeType.orEmpty()
    if (status == STATUS_RESIGNED) return status
    if (status == STATUS_PROBATION && type == TYPE_FULLTIME) return status
    return type
}

// ── 构建参数 ──

data class BuildTeamHomeModelParams(
    val rootType: TeamRootType,
    val userMap: Map<String, TeamUser>,
    val departmentMap: Map<String, TeamDepartment>,
    val currentUserId: String? = null,
    val username: String? = null,
    val unknownMemberLabel: String = "team_unknownmember",
)

data class BuildTeamDeptModelParams(
    val deptId: String,
    val userMap: Map<String, TeamUser>,
    val departmentMap: Map<String, TeamDepartment>,
    val currentUserId: String? = null,
    val unknownMemberLabel: String = "team_unknownmember",
    val directMembersTitle: String = "team_directmembers",
)

data class BuildTeamFlatListParams(
    val deptId: String,
    val userMap: Map<String, TeamUser>,
    val departmentMap: Map<String, TeamDepartment>,
    val currentUserId: String? = null,
    val username: String? = null,
    val unknownMemberLabel: String = "team_unknownmember",
)

private data class MemberSource(val userKey: String, val deptId: String)

// ── 内部工具 ──

private fun toOnlineStatus(user: TeamUser): Boolean {
    val raw = pickContactPresenceRaw(user)?.lowercase().orEmpty()
    return raw == "online" || raw == "away"
}

/** RN toEmploymentType：全等四值直通；fulltime/intern/parttime/contractor 归一；其余 other。 */
internal fun toEmploymentType(value: String?): TeamEmploymentType = when (value) {
    "fullTime", "outsourcing", "internship", "partTime" -> when (value) {
        "fullTime" -> TeamEmploymentType.FULL_TIME
        "outsourcing" -> TeamEmploymentType.OUTSOURCING
        "internship" -> TeamEmploymentType.INTERNSHIP
        else -> TeamEmploymentType.PART_TIME
    }
    else -> when (value.orEmpty().trim().lowercase().replace("_", "")) {
        "fulltime" -> TeamEmploymentType.FULL_TIME
        "intern" -> TeamEmploymentType.INTERNSHIP
        "parttime" -> TeamEmploymentType.PART_TIME
        "contractor" -> TeamEmploymentType.OUTSOURCING
        else -> TeamEmploymentType.OTHER
    }
}

private fun toDisplayName(user: TeamUser, unknownMemberLabel: String): String =
    user.fname ?: user.name ?: user.username ?: unknownMemberLabel

/** RN toMember。 */
private fun toMember(
    userKey: String,
    user: TeamUser,
    dept: TeamDepartment?,
    currentUserId: String?,
    username: String?,
    unknownMemberLabel: String,
): TeamMember {
    val id = user._id ?: userKey
    val isSelf =
        (!currentUserId.isNullOrEmpty() && (currentUserId == user._id || currentUserId == userKey)) ||
            (!username.isNullOrEmpty() && user.username == username)
    return TeamMember(
        id = id,
        username = user.username.orEmpty(),
        displayName = toDisplayName(user, unknownMemberLabel),
        position = user.jobName.orEmpty(),
        departmentName = user.primaryOrgName ?: dept?.name ?: "",
        onlineStatus = toOnlineStatus(user),
        employmentType = toEmploymentType(user.employmentType),
        isSelf = isSelf,
        employeeDesc = getEmployeeDesc(user.employeeStatus, user.employeeType),
        presenceUserId = pickPresenceUserId(user, userKey),
        presenceFallbackStatus = mapContactStatusToTUserStatus(pickContactPresenceRaw(user)),
    )
}

private fun dedupeMembers(members: List<TeamMember>): List<TeamMember> {
    val seen = mutableSetOf<String>()
    return members.filter { seen.add(it.id) }
}

private fun countOnline(members: List<TeamMember>): Int = members.count { it.onlineStatus }

private fun countEmployment(members: List<TeamMember>): TeamEmploymentCounts {
    val counts = mutableMapOf<TeamEmploymentType, Int>()
    members.forEach { counts[it.employmentType] = (counts[it.employmentType] ?: 0) + 1 }
    return TeamEmploymentCounts(
        fullTime = counts[TeamEmploymentType.FULL_TIME] ?: 0,
        outsourcing = counts[TeamEmploymentType.OUTSOURCING] ?: 0,
        internship = counts[TeamEmploymentType.INTERNSHIP] ?: 0,
        partTime = counts[TeamEmploymentType.PART_TIME] ?: 0,
        other = counts[TeamEmploymentType.OTHER] ?: 0,
    )
}

/** RN memberMatchesQuery：displayName/username/position/departmentName 任一 contains。 */
fun memberMatchesQuery(member: TeamMember, normalizedQuery: String): Boolean =
    listOf(member.displayName, member.username, member.position, member.departmentName)
        .any { it.lowercase().contains(normalizedQuery) }

/** RN collectSubtreeMemberSources：环安全（visitedDeptIds）。 */
private fun collectSubtreeMemberSources(
    deptId: String,
    departmentMap: Map<String, TeamDepartment>,
    visitedDeptIds: MutableSet<String> = mutableSetOf(),
): List<MemberSource> {
    if (!visitedDeptIds.add(deptId)) return emptyList()
    val dept = departmentMap[deptId] ?: return emptyList()
    val directSources = dept.users.map { MemberSource(it, deptId) }
    val childSources = dept.children.flatMap { collectSubtreeMemberSources(it, departmentMap, visitedDeptIds) }
    return directSources + childSources
}

private fun buildMembersFromSources(
    sources: List<MemberSource>,
    userMap: Map<String, TeamUser>,
    departmentMap: Map<String, TeamDepartment>,
    currentUserId: String?,
    username: String?,
    unknownMemberLabel: String,
): List<TeamMember> = dedupeMembers(
    sources.flatMap { (userKey, deptId) ->
        val user = userMap[userKey] ?: return@flatMap emptyList()
        listOf(toMember(userKey, user, departmentMap[deptId], currentUserId, username, unknownMemberLabel))
    },
)

private fun collectSubtreeMembers(
    deptId: String,
    userMap: Map<String, TeamUser>,
    departmentMap: Map<String, TeamDepartment>,
    currentUserId: String?,
    username: String?,
    unknownMemberLabel: String,
): List<TeamMember> = buildMembersFromSources(
    collectSubtreeMemberSources(deptId, departmentMap),
    userMap, departmentMap, currentUserId, username, unknownMemberLabel,
)

/** RN totalCountForDepartment：countIncludeChildren.all ?? usersCountIncludeChildren ?? members.size。 */
private fun totalCountForDepartment(dept: TeamDepartment?, members: List<TeamMember>): Int {
    val all = dept?.countIncludeChildren?.get("all")
    if (all != null) return all
    dept?.usersCountIncludeChildren?.let { return it }
    return members.size
}

private fun hasSelfMember(members: List<TeamMember>, username: String?): Boolean =
    members.any { it.isSelf || (!username.isNullOrEmpty() && it.username == username) }

/** RN removeSeenMembers。 */
private fun removeSeenMembers(members: List<TeamMember>, seenMemberIds: MutableSet<String>): List<TeamMember> =
    members.filter { seenMemberIds.add(it.id) }

/** RN findCurrentUser：_id/userKey 命中 currentUserId 或 username 命中。 */
private fun findCurrentUser(
    userMap: Map<String, TeamUser>,
    departmentMap: Map<String, TeamDepartment>,
    currentUserId: String?,
    username: String?,
    unknownMemberLabel: String,
): TeamMember? {
    val entry = userMap.entries.find { (userKey, user) ->
        (!currentUserId.isNullOrEmpty() && (currentUserId == user._id || currentUserId == userKey)) ||
            (!username.isNullOrEmpty() && user.username == username)
    } ?: return null
    val deptId = entry.value.departments.firstOrNull().orEmpty()
    return toMember(entry.key, entry.value, departmentMap[deptId], currentUserId, username, unknownMemberLabel)
}

private fun buildDepartmentSummary(dept: TeamDepartment, params: BuildTeamHomeModelParams): TeamDeptSummary {
    val members = collectSubtreeMembers(
        dept._id, params.userMap, params.departmentMap,
        params.currentUserId, params.username, params.unknownMemberLabel,
    )
    return TeamDeptSummary(
        id = dept._id,
        name = dept.name,
        tagLabel = dept.type ?: if (dept.children.isNotEmpty()) "PMT" else "L3D",
        totalCount = totalCountForDepartment(dept, members),
        onlineCount = countOnline(members),
        isMine = hasSelfMember(members, params.username),
        members = members,
    )
}

/** RN extractEmploymentCountsFromDept：仅取 >0 的值。 */
internal fun extractEmploymentCountsFromDept(dept: TeamDepartment?): Map<String, Int> {
    if (dept == null) return emptyMap()
    val raw = dept.countIncludeChildren
    if (raw.isEmpty()) return emptyMap()
    val keys = listOf("fullTime", "outsourcing", "internship", "partTime", "other")
    val result = mutableMapOf<String, Int>()
    for (key in keys) {
        val value = raw[key]
        if (value != null && value > 0) result[key] = value
    }
    return result
}

// ── 公开构建函数 ──

/** RN buildTeamHomeModel。 */
fun buildTeamHomeModel(params: BuildTeamHomeModelParams): TeamHomeModel {
    val rootId = TEAM_ROOT_IDS.getValue(params.rootType)
    val rootDept = params.departmentMap[rootId]
    val departments = (rootDept?.children ?: emptyList()).mapNotNull { deptId ->
        params.departmentMap[deptId]?.let { buildDepartmentSummary(it, params) }
    }
    val members = dedupeMembers(departments.flatMap { it.members })
    return TeamHomeModel(
        rootType = params.rootType,
        rootId = rootId,
        companyName = rootDept?.name.orEmpty(),
        rootTotalCount = totalCountForDepartment(rootDept, emptyList()),
        rootEmploymentCounts = extractEmploymentCountsFromDept(rootDept),
        me = findCurrentUser(
            params.userMap, params.departmentMap,
            params.currentUserId, params.username, params.unknownMemberLabel,
        ),
        departments = departments,
        members = members,
        totalCount = members.size,
        onlineCount = countOnline(members),
        employmentCounts = countEmployment(members),
    )
}

/** RN buildTeamDeptModel。 */
fun buildTeamDeptModel(params: BuildTeamDeptModelParams): TeamDeptModel {
    val dept = params.departmentMap[params.deptId]
    val members = collectSubtreeMembers(
        params.deptId, params.userMap, params.departmentMap,
        params.currentUserId, null, params.unknownMemberLabel,
    )
    if (dept == null) {
        return TeamDeptModel(params.deptId, "", 0, 0, TeamEmploymentCounts(), emptyList(), emptyList())
    }
    val directMembers = buildMembersFromSources(
        dept.users.map { MemberSource(it, dept._id) },
        params.userMap, params.departmentMap,
        params.currentUserId, null, params.unknownMemberLabel,
    )
    val sections = mutableListOf<TeamDeptSection>()
    val seenSectionMemberIds = mutableSetOf<String>()
    val uniqueDirectMembers = removeSeenMembers(directMembers, seenSectionMemberIds)
    if (uniqueDirectMembers.isNotEmpty()) {
        sections.add(TeamDeptSection("direct:${dept._id}", params.directMembersTitle, uniqueDirectMembers))
    }
    for (childId in dept.children) {
        val childDept = params.departmentMap[childId]
        val childMembers = collectSubtreeMembers(
            childId, params.userMap, params.departmentMap,
            params.currentUserId, null, params.unknownMemberLabel,
        )
        val uniqueChildMembers = removeSeenMembers(childMembers, seenSectionMemberIds)
        if (childDept == null || uniqueChildMembers.isEmpty()) continue
        sections.add(TeamDeptSection("dept:$childId", childDept.name, uniqueChildMembers))
    }
    if (sections.isEmpty() && members.isNotEmpty()) {
        sections.add(TeamDeptSection("flat:${dept._id}", dept.name, members))
    }
    return TeamDeptModel(
        id = dept._id,
        name = dept.name,
        totalCount = members.size,
        onlineCount = countOnline(members),
        employmentCounts = countEmployment(members),
        sections = sections,
        members = members,
    )
}

/** RN buildTeamFlatList：子部门 summary + 直属成员；items = 部门前、成员后。 */
fun buildTeamFlatList(params: BuildTeamFlatListParams): TeamFlatListModel {
    val dept = params.departmentMap[params.deptId]
    if (dept == null) {
        return TeamFlatListModel(params.deptId, params.deptId, emptyList(), 0, 0, 0, emptyMap())
    }
    val childDepts = dept.children.mapNotNull { params.departmentMap[it] }
    val deptSummaries = childDepts.map { child ->
        buildDepartmentSummary(
            child,
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT, // RN :520 字面量（仅 summary 构建用，rootType 不参与）
                userMap = params.userMap,
                departmentMap = params.departmentMap,
                currentUserId = params.currentUserId,
                username = params.username,
                unknownMemberLabel = params.unknownMemberLabel,
            ),
        )
    }
    val directMembers = buildMembersFromSources(
        dept.users.map { MemberSource(it, dept._id) },
        params.userMap, params.departmentMap,
        params.currentUserId, null, params.unknownMemberLabel,
    )
    val items: List<TeamFlatListItem> =
        deptSummaries.map { TeamFlatListItem.Dept(it) } + directMembers.map { TeamFlatListItem.Member(it) }
    val allMembers = dedupeMembers(directMembers + deptSummaries.flatMap { it.members })
    return TeamFlatListModel(
        name = dept.name,
        deptId = dept._id,
        items = items,
        totalCount = allMembers.size,
        directMemberCount = directMembers.size,
        childDeptCount = childDepts.size,
        employmentCounts = extractEmploymentCountsFromDept(dept),
    )
}

/** RN searchTeamHome。 */
fun searchTeamHome(model: TeamHomeModel, query: String): TeamHomeSearchResult {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return TeamHomeSearchResult(emptyList(), emptyList())
    return TeamHomeSearchResult(
        departments = model.departments.filter { it.name.lowercase().contains(q) },
        members = model.members.filter { memberMatchesQuery(it, q) },
    )
}

/** RN searchTeamDept。 */
fun searchTeamDept(model: TeamDeptModel, query: String): TeamDeptModel {
    val q = query.trim().lowercase()
    if (q.isEmpty()) {
        return model.copy(totalCount = 0, onlineCount = 0, employmentCounts = TeamEmploymentCounts(), sections = emptyList(), members = emptyList())
    }
    val sections = model.sections
        .map { it.copy(members = it.members.filter { m -> memberMatchesQuery(m, q) }) }
        .filter { it.members.isNotEmpty() }
    val members = dedupeMembers(sections.flatMap { it.members })
    return model.copy(
        totalCount = members.size,
        onlineCount = countOnline(members),
        employmentCounts = countEmployment(members),
        sections = sections,
        members = members,
    )
}

// ── 解析（hrm/v2.users.list → userMap/departmentMap/rootTree）──

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.strArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: emptyList()

private fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toDoubleOrNull()?.toInt()

/** RN parseContactsMap（services/api/contacts.ts）：envelope.data ?? envelope 后取 userMap/departmentMap/rootTree。
 *  Android sdk.get 已做 `data ?? resp` 平铺——这里再兼容一层包裹（RN parseContactsMapResponse 双重保险同义）。 */
fun parseContactsMap(raw: JsonElement): ContactsMapPayload {
    val envelope = raw as? JsonObject ?: return ContactsMapPayload()
    val data = envelope["data"] as? JsonObject ?: envelope
    val userMap = (data["userMap"] as? JsonObject)?.mapValues { (_, v) -> parseTeamUser(v as? JsonObject) }
        ?: emptyMap()
    val departmentMap = (data["departmentMap"] as? JsonObject)?.mapValues { (_, v) -> parseTeamDepartment(v as? JsonObject) }
        ?: emptyMap()
    val rootTree = data.strArray("rootTree")
    return ContactsMapPayload(userMap, departmentMap, rootTree)
}

/** 解析产物。 */
data class ContactsMapPayload(
    val userMap: Map<String, TeamUser> = emptyMap(),
    val departmentMap: Map<String, TeamDepartment> = emptyMap(),
    val rootTree: List<String> = emptyList(),
)

/** userMap 行解析（字段缺失全容忍）。 */
internal fun parseTeamUser(o: JsonObject?): TeamUser {
    if (o == null) return TeamUser()
    return TeamUser(
        _id = o.str("_id"),
        username = o.str("username"),
        name = o.str("name"),
        fname = o.str("fname"),
        jobName = o.str("jobName"),
        primaryOrgName = o.str("primaryOrgName"),
        departments = o.strArray("departments"),
        status = o.str("status"),
        onlineStatus = o.str("onlineStatus"),
        statusConnection = o.str("statusConnection"),
        employmentType = o.str("employmentType"),
        employeeType = o.str("employeeType"),
        employeeStatus = o.str("employeeStatus"),
    )
}

/** departmentMap 行解析。 */
internal fun parseTeamDepartment(o: JsonObject?): TeamDepartment {
    if (o == null) return TeamDepartment(_id = "", name = "")
    val counts = (o["countIncludeChildren"] as? JsonObject)
        ?.mapNotNull { (k, v) ->
            val n = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toDoubleOrNull()?.toInt()
            if (n != null) k to n else null
        }?.toMap()
        ?: emptyMap()
    return TeamDepartment(
        _id = o.str("_id").orEmpty(),
        name = o.str("name").orEmpty(),
        type = o.str("type"),
        parent = o.str("parent"),
        children = o.strArray("children"),
        users = o.strArray("users"),
        usersCountIncludeChildren = o.intOrNull("usersCountIncludeChildren"),
        countIncludeChildren = counts,
    )
}
