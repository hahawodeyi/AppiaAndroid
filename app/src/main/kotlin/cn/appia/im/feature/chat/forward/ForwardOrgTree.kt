package cn.appia.im.feature.chat.forward

import cn.appia.im.feature.contacts.TeamDepartment
import cn.appia.im.feature.contacts.TeamUser

/**
 * 转发页组织树纯逻辑（RN ForwardSelectScreen/index.tsx :87-169 移植）。
 * 与 TeamModels 的树构建解耦：这里行结构直接服务 checkbox 三态 + 展开态渲染。
 */

/** RN OrgTreeRow。 */
sealed interface ForwardOrgRow {
    val id: String
    val depth: Int

    data class Dept(
        override val id: String,
        override val depth: Int,
        val dept: TeamDepartment,
    ) : ForwardOrgRow

    data class User(
        override val id: String,
        override val depth: Int,
        val username: String,
        val displayName: String,
        val sub: String,
    ) : ForwardOrgRow
}

/**
 * RN buildOrgTreeRows：根 dept 不出行；非根未展开即剪枝（children+users 都不出）；
 * children 在 users 之后（RN walk 顺序：dept 行 → users → children）。
 */
internal fun buildOrgTreeRows(
    rootId: String,
    expandedDeptIds: Set<String>,
    departmentMap: Map<String, TeamDepartment>,
    userMap: Map<String, TeamUser>,
): List<ForwardOrgRow> {
    if (departmentMap.isEmpty()) return emptyList()
    val rows = mutableListOf<ForwardOrgRow>()
    // username 去重（保留首个）：同一人挂在两个部门时 LazyColumn key 唯一（RN FlatList 仅 warn）
    val seenUsernames = mutableSetOf<String>()

    fun walk(deptId: String, depth: Int) {
        val dept = departmentMap[deptId] ?: return
        val atRoot = deptId == rootId
        if (!atRoot) {
            rows.add(ForwardOrgRow.Dept(deptId, depth, dept))
        }
        if (!atRoot && deptId !in expandedDeptIds) return

        if (!atRoot) {
            for (uid in dept.users) {
                val u = userMap[uid] ?: continue
                val username = u.username?.trim().orEmpty()
                if (username.isEmpty() || !seenUsernames.add(username)) continue
                val displayName = u.fname ?: u.name ?: username
                val sub = buildString {
                    u.primaryOrgName?.let { append(it) }
                    u.jobName?.let { append(" · ").append(it) }
                }.trim().ifEmpty { "@$username" }
                rows.add(ForwardOrgRow.User("user:$username", depth + 1, username, displayName, sub))
            }
        }

        for (childId in dept.children) {
            walk(childId, if (atRoot) depth else depth + 1)
        }
    }

    walk(rootId, 0)
    return rows
}

/** RN buildDeptDescendantUsernames：BFS 收集子树全部 username（seen 防环）。 */
internal fun buildDeptDescendantUsernames(
    deptId: String,
    departmentMap: Map<String, TeamDepartment>,
    userMap: Map<String, TeamUser>,
): Set<String> {
    val out = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(deptId))
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (!seen.add(cur)) continue
        val dept = departmentMap[cur] ?: continue
        queue.addAll(dept.children)
        for (uid in dept.users) {
            val username = userMap[uid]?.username?.trim().orEmpty()
            if (username.isNotEmpty()) out.add(username)
        }
    }
    return out
}

/** RN CheckboxState。 */
enum class ForwardDeptCheckState { UNCHECKED, CHECKED, INDETERMINATE }

/** RN getDeptCheckboxState：无成员→unchecked；全选→checked；部分→indeterminate。 */
internal fun deptCheckboxState(
    deptId: String,
    selectedUserIds: Set<String>,
    departmentMap: Map<String, TeamDepartment>,
    userMap: Map<String, TeamUser>,
): ForwardDeptCheckState {
    val descendants = buildDeptDescendantUsernames(deptId, departmentMap, userMap)
    if (descendants.isEmpty()) return ForwardDeptCheckState.UNCHECKED
    val hit = descendants.count { it in selectedUserIds }
    return when {
        hit == 0 -> ForwardDeptCheckState.UNCHECKED
        hit == descendants.size -> ForwardDeptCheckState.CHECKED
        else -> ForwardDeptCheckState.INDETERMINATE
    }
}

/** RN toggleDeptUsers：全选→全清；否则补齐（到上限停）。 */
internal fun toggleDeptUsers(
    deptId: String,
    selectedUserIds: Set<String>,
    departmentMap: Map<String, TeamDepartment>,
    userMap: Map<String, TeamUser>,
): Set<String> {
    val descendants = buildDeptDescendantUsernames(deptId, departmentMap, userMap)
    if (descendants.isEmpty()) return selectedUserIds
    val next = selectedUserIds.toMutableSet()
    val allSelected = descendants.all { it in selectedUserIds }
    if (allSelected) {
        next.removeAll(descendants.toSet())
    } else {
        for (u in descendants) {
            // RN :330-333 上限只计 user 侧（rid 不参与此分支）
            if (next.size >= FORWARD_MAX_SELECT) break
            next.add(u)
        }
    }
    return next
}
