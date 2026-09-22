package cn.appia.im.core.chat

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomSettingsApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 成员列表动作项构建（RN lib/chat/roomMemberActions.ts buildMemberActionSheetItems 逐行）：
 * 发消息恒有；set-owner（joinType 含 user 门）/set-moderator（非 owner + canGoDirect 门）/
 * remove-user（allowRemoveFromRoom + 非 owner + joinType 门）——三者全部 [hasRoomPermission] 权限门。
 *
 * canRemoveMemberRow（RN RoomMembersScreen index.tsx:41-44）：本地双层判定的第二层——
 * owner 行恒不可移除，joinType 含 'user' 才可移除。与服务端权限判定（remove-user）**独立**。
 */

/** RN IRoomMemberRow 面向本域的形态（v2 map 行子集 + joinType/roles）。 */
data class MemberRow(
    val _id: String,
    val username: String? = null,
    val name: String? = null,
    val joinType: List<String>? = null,
    val roles: List<String>? = null,
)

/** 动作项（RN MemberActionSheetItem {label, onPress}——label 由 UI 层 t(key) 后传入）。 */
data class MemberActionItem(
    val labelKey: String,
    val onPress: () -> Unit,
)

/** RN canGoDirect：username 缺失或不含 ':'（外部用户标识）。 */
private fun canGoDirect(username: String?): Boolean =
    username == null || !username.contains(":")

/**
 * RN buildMemberActionSheetItems。`onPress` 回调逐项携带；labelKey 对照 RN i18n key
 * （roomMembers_sendMessage / setOwner|removeOwner / setModerator|removeModerator / removeFromRoom）。
 */
fun buildMemberActionSheetItems(
    member: MemberRow,
    memberRoles: List<String>,
    currentUserRoles: List<String>,
    globalRoles: List<String>,
    permissions: Map<String, List<String>>?,
    allowRemoveFromRoom: Boolean,
    onSendMessage: () -> Unit,
    onToggleOwner: () -> Unit,
    onToggleModerator: () -> Unit,
    onRemoveFromRoom: () -> Unit,
): List<MemberActionItem> {
    val isOwner = "owner" in memberRoles
    val isModerator = "moderator" in memberRoles
    val isUserJoinType = member.joinType?.any { it == "user" } == true

    val items = mutableListOf(MemberActionItem("roomMembers_sendMessage", onSendMessage))

    // binding ①（T2 评审）：映射键未命中/空 roles → null 触发 getDefaultPermissionMapping 兜底
    fun mappingFor(key: String): Map<String, List<String>>? =
        permissions?.get(key)?.takeIf { it.isNotEmpty() }?.let { mapOf(key to it) }

    if (cn.appia.im.core.permissions.hasRoomPermission(
            "set-owner", currentUserRoles, globalRoles, mappingFor("set-owner"),
        ) && isUserJoinType
    ) {
        items.add(
            MemberActionItem(
                if (isOwner) "roomMembers_removeOwner" else "roomMembers_setOwner",
                onToggleOwner,
            ),
        )
    }

    if (cn.appia.im.core.permissions.hasRoomPermission(
            "set-moderator", currentUserRoles, globalRoles, mappingFor("set-moderator"),
        ) && !isOwner && canGoDirect(member.username)
    ) {
        items.add(
            MemberActionItem(
                if (isModerator) "roomMembers_removeModerator" else "roomMembers_setModerator",
                onToggleModerator,
            ),
        )
    }

    if (allowRemoveFromRoom &&
        cn.appia.im.core.permissions.hasRoomPermission(
            "remove-user", currentUserRoles, globalRoles, mappingFor("remove-user"),
        ) &&
        !isOwner &&
        isUserJoinType
    ) {
        items.add(MemberActionItem("roomMembers_removeFromRoom", onRemoveFromRoom))
    }

    return items
}

/**
 * RN canRemoveMemberRow（index.tsx:41-44）：本地移除行判定——owner 不可移除，
 * joinType 含 'user' 才可移除（组织加入行无 user joinType → 不可单独移除）。
 */
fun canRemoveMemberRow(member: MemberRow, memberRoles: List<String>): Boolean {
    if ("owner" in memberRoles) return false
    return member.joinType?.any { it == "user" } == true
}

/**
 * RN getMemberRoles（index.tsx:186-192 getRoomRoles 唯一消费面）：
 * `roles.find { r.u._id == uid }?.roles ?? []`。T3 定案：getRoomRoles 返回原始 JsonElement
 * （`{roles:[{u:{_id}, roles:string[]}]}`），本函数按需解析。
 */
fun findRoomMemberRoles(raw: JsonElement?, userId: String): List<String> {
    val roles = (raw as? JsonObject)?.get("roles") as? JsonArray ?: return emptyList()
    for (entry in roles) {
        val obj = entry as? JsonObject ?: continue
        val uid = (obj["u"] as? JsonObject)
            ?.let { u -> (u["_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        if (uid == userId) {
            return (obj["roles"] as? JsonArray)
                ?.mapNotNull { r -> (r as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()
        }
    }
    return emptyList()
}

/**
 * 批量移除链（RN handleBulkRemove :346-377）：并行 per-user `postRemoveUserFromRoom`
 * + 单次 `postRemoveDepartmentFromRoom`（多 dep 合一调用，RN `Array.from(selectedDepIds)` 同）
 * → 全部成功 goBack；失败弹 actionFailed。coroutineScope 语义 = Promise.all 首异常即抛。
 */
suspend fun bulkRemoveRoomMembers(
    sdk: RocketSdk,
    rid: String,
    roomType: String,
    userIds: List<String>,
    depIds: List<String>,
) {
    // async 并行（RoomStreamManager.unsubscribeAll 同款；coroutineScope 首异常即抛 = Promise.all）
    coroutineScope {
        userIds.map { uid ->
            async { RoomSettingsApi.postRemoveUserFromRoom(sdk, rid, roomType, uid) }
        }.awaitAll()
    }
    if (depIds.isNotEmpty()) {
        RoomSettingsApi.postRemoveDepartmentFromRoom(sdk, rid, depIds)
    }
}

// ── 成员列表行解析（RN parseAppiaRoomMembersV2.ts buildListRows* 的 listRows 半程；
//    M3 MentionSource.parseAppiaRoomMembersV2 只做 members 扁平集——本屏的 org/部门结构在此独立实现）──

/** 成员列表行（RN RoomMembersListRow 三态密封）。 */
sealed interface RoomMembersListRows {
    data class OrgHeader(val org: String) : RoomMembersListRows
    data class User(val user: MemberRow) : RoomMembersListRows
    data class Department(val department: DepartmentRow) : RoomMembersListRows
}

data class DepartmentRow(
    val _id: String,
    val name: String,
    val type: String? = null,
    val members: List<MemberRow> = emptyList(),
)

/**
 * RN buildListRowsFromBlocks（parseAppiaRoomMembersV2.ts:94-117）：
 * isLocal 块排前 → 每块先 user 行（members 逐个，剔除已在部门内的）后 department 行；
 * 多块（>1）时每块前插 orgHeader（org 空 → "—"）。
 */
fun parseRoomMembersListRows(raw: JsonElement?): List<RoomMembersListRows> {
    val blocks = (raw as? JsonObject)?.get("data") as? JsonArray
        ?: (raw as? JsonArray)
        ?: return emptyList()

    val userMap = LinkedHashMap<String, MemberRow>()
    val orgBlocks = mutableListOf<Pair<Boolean, JsonObject>>() // (isLocal, block)

    for (blockEl in blocks) {
        val block = blockEl as? JsonObject ?: continue
        val isLocal = (block["isLocal"] as? JsonPrimitive)?.booleanOrNull == true
        orgBlocks += isLocal to block
        (block["map"] as? JsonObject)?.forEach { (key, userEl) ->
            val user = userEl as? JsonObject ?: return@forEach
            val uname = ((user["username"] as? JsonPrimitive)?.contentOrNull ?: key).trim()
            if (uname.isEmpty()) return@forEach
            // isLocal 块覆盖非本地块（RN :133 !userMap[uname] || block.isLocal）
            if (!userMap.containsKey(uname) || isLocal) {
                userMap[uname] = MemberRow(
                    _id = (user["_id"] as? JsonPrimitive)?.contentOrNull ?: uname,
                    username = uname,
                    name = (user["name"] as? JsonPrimitive)?.contentOrNull,
                    joinType = (user["joinType"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    roles = (user["roles"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            }
        }
    }

    // RN sortOrgBlocks：本地块在前
    orgBlocks.sortByDescending { it.first }

    fun resolveUser(username: String): MemberRow? {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) return null
        return userMap[trimmed] ?: MemberRow(_id = trimmed, username = trimmed, name = trimmed)
    }

    val showOrgHeaders = orgBlocks.size > 1
    val rows = mutableListOf<RoomMembersListRows>()

    for ((isLocal, block) in orgBlocks) {
        val blockRows = mutableListOf<RoomMembersListRows>()
        val departmentRows = mutableListOf<RoomMembersListRows>()
        val inDepartment = mutableSetOf<String>()

        for (depEl in block["departments"] as? JsonArray ?: emptyList()) {
            val dep = depEl as? JsonObject ?: continue
            val depMembers = (dep["members"] as? JsonArray ?: emptyList())
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::resolveUser) }
            depMembers.forEach { m -> m.username?.let { inDepartment.add(it) } }
            departmentRows += RoomMembersListRows.Department(
                DepartmentRow(
                    _id = (dep["_id"] as? JsonPrimitive)?.contentOrNull ?: "",
                    name = (dep["name"] as? JsonPrimitive)?.contentOrNull ?: "",
                    type = (dep["type"] as? JsonPrimitive)?.contentOrNull,
                    members = depMembers,
                ),
            )
        }

        for (unameEl in block["members"] as? JsonArray ?: emptyList()) {
            val uname = (unameEl as? JsonPrimitive)?.contentOrNull ?: continue
            val trimmed = uname.trim()
            if (trimmed.isEmpty() || inDepartment.contains(trimmed)) continue
            resolveUser(trimmed)?.let { blockRows += RoomMembersListRows.User(it) }
        }
        blockRows += departmentRows

        if (blockRows.isEmpty()) continue
        if (showOrgHeaders) {
            val org = ((block["org"] as? JsonPrimitive)?.contentOrNull ?: "").trim().ifEmpty { "—" }
            rows += RoomMembersListRows.OrgHeader(org)
        }
        rows += blockRows
    }
    return rows
}
