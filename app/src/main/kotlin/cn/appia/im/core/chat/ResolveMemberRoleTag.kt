package cn.appia.im.core.chat

/**
 * 成员角色徽标解析（RN lib/chat/resolveMemberRoleTag.ts 逐行）：
 * owner → OWNER；moderator|ghost-owner|admin → ADMIN；空/其他 → null。
 */

/** RN MemberRoleTag = 'owner' | 'admin'。 */
enum class MemberRoleTag { OWNER, ADMIN }

/** RN resolveMemberRoleTag：合并后的 roles 数组解析列表展示 tag；无权限返回 null。 */
fun resolveMemberRoleTag(roles: List<String>): MemberRoleTag? {
    if (roles.isEmpty()) return null
    if ("owner" in roles) return MemberRoleTag.OWNER
    if ("moderator" in roles || "ghost-owner" in roles || "admin" in roles) return MemberRoleTag.ADMIN
    return null
}

/** RN mergeMemberRoles：合并成员 API（v2 map roles）与 room roles API 的角色数组（去重、跳过空串）。 */
fun mergeMemberRoles(memberRoles: List<String>?, roomRoles: List<String>?): List<String> =
    (memberRoles.orEmpty().filter { it.isNotEmpty() } +
        roomRoles.orEmpty().filter { it.isNotEmpty() }).distinct()
