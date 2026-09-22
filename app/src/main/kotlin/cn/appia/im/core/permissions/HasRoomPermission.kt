package cn.appia.im.core.permissions

/**
 * 房间级权限纯函数（RN lib/permissions/hasRoomPermission.ts 逐行移植）。
 *
 * 旧版流程（RN 头注释）：服务端下发 permission→roles 映射 + 合并房间角色/全局角色 + 交集判定。
 * 服务端数据经 PermissionsStore（syncPermissions / permissions-changed 流）就位；
 * 映射缺失走 [getDefaultPermissionMapping] 兜底。
 */

/** Rocket.Chat 默认权限-角色映射（RN getDefaultPermissionMapping hasRoomPermission.ts:46-55 逐条）。 */
fun getDefaultPermissionMapping(): Map<String, List<String>> = mapOf(
    "set-owner" to listOf("owner"),
    "set-moderator" to listOf("owner", "moderator"),
    "set-ghost-owner" to listOf("owner"),
    "set-leader" to listOf("owner", "moderator"),
    "set-pdt" to listOf("owner"),
    "remove-user" to listOf("owner", "moderator", "admin"),
    "edit-team-member" to listOf("owner"),
    "mute-user" to listOf("owner", "moderator"),
    // 以下不在 RN getDefaultPermissionMapping（RN 侧仅同步清单无本地兜底），按 RC 服务端默认补全：
    // delete-c/delete-p = [owner, admin]（channels/rooms 删除权限，owner 与全局 admin 可执行）；
    // edit-room / add-user-to-joined-room = [owner, moderator, admin]（RN canEditRoomSettings.ts:13
    // EDIT_ROOM_FALLBACK_ROLES / hasRoomPermission.ts:63 REMOVE_USER_FALLBACK_ROLES 同清单）。
    "edit-room" to listOf("owner", "moderator", "admin"),
    "add-user-to-joined-room" to listOf("owner", "moderator", "admin"),
    "delete-c" to listOf("owner", "admin"),
    "delete-p" to listOf("owner", "admin"),
)

/**
 * 检查当前用户在指定房间中是否拥有某项权限（RN hasRoomPermission :118-130 逐行）。
 *
 * @param permissionKey   权限标识（如 "remove-user"）
 * @param userRoomRoles   当前用户在该房间的角色（chats.roles JSON 解析，parseRoomRoles 等价）
 * @param userGlobalRoles 当前用户的全局角色（authStore user.roles）
 * @param mapping         服务端权限映射（null → getDefaultPermissionMapping 兜底）
 * @return 合并「房间角色 + 全局角色」后与允许角色有交集 → true；映射缺失/空角色列表 → false（RN :124-125）
 */
fun hasRoomPermission(
    permissionKey: String,
    userRoomRoles: Set<String>,
    userGlobalRoles: Set<String>,
    mapping: Map<String, List<String>>? = null,
): Boolean {
    val allowedRoles = (mapping ?: getDefaultPermissionMapping())[permissionKey]
    if (allowedRoles.isNullOrEmpty()) return false

    // RN :128 合并房间角色 + 全局角色（Set 去重等价）
    val mergedRoles = userRoomRoles + userGlobalRoles
    return allowedRoles.any { it in mergedRoles }
}

/** [hasRoomPermission] 的 List 便捷重载（RN 签名收 string[]）。 */
fun hasRoomPermission(
    permissionKey: String,
    userRoomRoles: List<String>,
    userGlobalRoles: List<String>,
    mapping: Map<String, List<String>>? = null,
): Boolean = hasRoomPermission(
    permissionKey,
    userRoomRoles.toSet(),
    userGlobalRoles.toSet(),
    mapping,
)
