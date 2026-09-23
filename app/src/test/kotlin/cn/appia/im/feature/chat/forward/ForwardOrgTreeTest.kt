package cn.appia.im.feature.chat.forward

import cn.appia.im.feature.contacts.TeamDepartment
import cn.appia.im.feature.contacts.TeamUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组织树纯逻辑（ForwardOrgTree.kt）自检：行构建（根剪枝/展开门控/顺序）、
 * 后代 username 收集（BFS 防环）、部门 checkbox 三态、部门批量勾选上限。
 */
class ForwardOrgTreeTest {

    private fun user(id: String, username: String, fname: String? = null, org: String? = null, job: String? = null) =
        TeamUser(_id = id, username = username, name = username, fname = fname, primaryOrgName = org, jobName = job)

    private val userMap = mapOf(
        "u1" to user("u1", "alice", fname = "Alice A", org = "Org", job = "Dev"),
        "u2" to user("u2", "bob"),
        "u3" to user("u3", "carol"),
    )

    private val deptMap = mapOf(
        "root" to TeamDepartment("root", "Root", children = listOf("a", "b")),
        "a" to TeamDepartment("a", "Dept A", users = listOf("u1"), children = listOf("a1")),
        "a1" to TeamDepartment("a1", "Dept A1", users = listOf("u2")),
        "b" to TeamDepartment("b", "Dept B", users = listOf("u3")),
    )

    @Test
    fun `root dept row is not pushed and unexpanded children are pruned`() {
        // 仅根级展开 root → 子部门 a/b 出行；a 未展开 → a 的 users 与孙 a1 不出
        val rows = buildOrgTreeRows("root", setOf("root"), deptMap, userMap)
        assertEquals(
            listOf("a", "b"),
            rows.mapNotNull { (it as? ForwardOrgRow.Dept)?.id },
        )
    }

    @Test
    fun `expanded dept emits users then grandchildren in order`() {
        val rows = buildOrgTreeRows("root", setOf("root", "a"), deptMap, userMap)
        val kinds = rows.map { it.id }
        // a 行 → u1 user 行 → a1 行（users 先于 children，RN walk 顺序）→ b 行
        assertEquals(listOf("a", "user:alice", "a1", "b"), kinds)
    }

    @Test
    fun `user row depth is one deeper than dept`() {
        val rows = buildOrgTreeRows("root", setOf("root", "a"), deptMap, userMap)
        val userRow = rows.first { it.id == "user:alice" } as ForwardOrgRow.User
        val deptRow = rows.first { it.id == "a" } as ForwardOrgRow.Dept
        assertEquals(deptRow.depth + 1, userRow.depth)
        assertEquals("Alice A", userRow.displayName)
        assertEquals("Org · Dev", userRow.sub)
    }

    @Test
    fun `username missing user rows are skipped`() {
        val map = mapOf("u9" to TeamUser(_id = "u9"))
        val rows = buildOrgTreeRows("root", setOf("root", "a"), deptMap, map)
        assertTrue(rows.none { it.id == "user:" })
    }

    @Test
    fun `empty sub falls back to at-username`() {
        val rows = buildOrgTreeRows("root", setOf("root", "b"), deptMap, userMap)
        val row = rows.first { it.id == "user:carol" } as ForwardOrgRow.User
        assertEquals("@carol", row.sub)
    }

    @Test
    fun `empty departmentMap yields no rows`() {
        assertTrue(buildOrgTreeRows("root", setOf("root"), emptyMap(), userMap).isEmpty())
    }

    @Test
    fun `descendantUsernames collects subtree with cycle safety`() {
        val cyclic = deptMap + mapOf(
            "a1" to TeamDepartment("a1", "A1", users = listOf("u2"), children = listOf("a")),
        )
        // a → a1 → a 环：seen 防死循环，结果仍收敛
        assertEquals(setOf("alice", "bob"), buildDeptDescendantUsernames("a", cyclic, userMap))
        assertEquals(setOf("carol"), buildDeptDescendantUsernames("b", deptMap, userMap))
    }

    @Test
    fun `dept checkbox state is unchecked empty unchecked partial checked full`() {
        assertEquals(ForwardDeptCheckState.UNCHECKED, deptCheckboxState("b", emptySet(), deptMap, userMap))
        val some = setOf("carol")
        assertEquals(ForwardDeptCheckState.CHECKED, deptCheckboxState("b", some, deptMap, userMap))
        // a 子树（alice+bob）：只选 alice → indeterminate
        assertEquals(ForwardDeptCheckState.INDETERMINATE, deptCheckboxState("a", setOf("alice"), deptMap, userMap))
        // a 展开后子树无任何选中 → unchecked
        assertEquals(ForwardDeptCheckState.UNCHECKED, deptCheckboxState("a", emptySet(), deptMap, userMap))
    }

    @Test
    fun `toggleDeptUsers selects all then clears all`() {
        val selected = toggleDeptUsers("a", emptySet(), deptMap, userMap)
        assertEquals(setOf("alice", "bob"), selected)
        // 再点 → 全清（RN allSelected 分支）
        assertTrue(toggleDeptUsers("a", selected, deptMap, userMap).isEmpty())
    }

    @Test
    fun `toggleDeptUsers caps at FORWARD_MAX_SELECT`() {
        // 大部门：12 成员 → 勾选封顶 10
        val manyUsers = (1..12).associate { "m$it" to user("m$it", "m$it") }
        val bigDept = mapOf(
            "root" to TeamDepartment("root", "Root", children = listOf("big")),
            "big" to TeamDepartment("big", "Big", users = manyUsers.keys.toList()),
        )
        val out = toggleDeptUsers("big", emptySet<String>(), bigDept, manyUsers)
        assertEquals(FORWARD_MAX_SELECT, out.size)
    }

    @Test
    fun `toggleDeptUsers with no members returns selection unchanged`() {
        val emptyDept = mapOf("root" to TeamDepartment("root", "Root", children = listOf("leaf")))
        assertEquals(setOf("x"), toggleDeptUsers("leaf", setOf("x"), emptyDept, userMap))
    }
}
