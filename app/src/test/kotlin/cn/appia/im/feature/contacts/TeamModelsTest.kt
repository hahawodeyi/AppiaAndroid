package cn.appia.im.feature.contacts

import cn.appia.im.domain.presence.TUserStatus
import cn.appia.im.domain.presence.isRocketChatUserId

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 团队模型纯函数测试（RN src/lib/team/teamModels.test.ts 12 用例逐条移植）+
 * parseContactsMap 解析 + presence 判定链 + 转发组织树辅助（buildOrgTreeRows/
 * buildDeptDescendantUsernames）扩面。
 */
class TeamModelsTest {

    private val departmentMap: Map<String, TeamDepartment> = mapOf(
        "EMT-0" to TeamDepartment(_id = "EMT-0", name = "SSC", children = listOf("pmt-prod", "pmt-ops")),
        "EMT-1328" to TeamDepartment(_id = "EMT-1328", name = "SSC", children = listOf("l1d-office")),
        "pmt-prod" to TeamDepartment(
            _id = "pmt-prod", name = "Galata", parent = "EMT-0",
            children = listOf("pmt-prod-ux"), users = listOf("u-self", "u-prod-2"),
            usersCountIncludeChildren = 3,
        ),
        "pmt-prod-ux" to TeamDepartment(
            _id = "pmt-prod-ux", name = "UX \u7814\u7a76\u7ec4", parent = "pmt-prod",
            users = listOf("u-ux-1"), usersCountIncludeChildren = 1,
        ),
        "pmt-ops" to TeamDepartment(
            _id = "pmt-ops", name = "\u8fd0\u8425\u670d\u52a1", parent = "EMT-0",
            users = listOf("u-ops-1"), usersCountIncludeChildren = 1,
        ),
        "l1d-office" to TeamDepartment(
            _id = "l1d-office", name = "\u8fd0\u8425\u5904", parent = "EMT-1328",
            users = listOf("u-l1d-1"), usersCountIncludeChildren = 1,
        ),
    )

    private val userMap: Map<String, TeamUser> = mapOf(
        "u-self" to TeamUser(
            _id = "u-self", username = "lin.xiaochen", fname = "\u6797\u6653\u6668",
            jobName = "\u4ea7\u54c1\u603b\u76d1", primaryOrgName = "\u4ea7\u54c1\u4e0e\u521b\u65b0\u90e8",
            departments = listOf("pmt-prod"), status = "offline", employmentType = "fulltime",
        ),
        "u-prod-2" to TeamUser(
            _id = "u-prod-2", username = "huang.min", name = "\u9ec4\u654f",
            jobName = "\u4ea7\u54c1\u7ecf\u7406", primaryOrgName = "\u4ea7\u54c1\u4e0e\u521b\u65b0\u90e8",
            departments = listOf("pmt-prod"), status = "online", employmentType = "intern",
        ),
        "u-ux-1" to TeamUser(
            _id = "u-ux-1", username = "qiu.yu", fname = "\u90b1\u96e8",
            jobName = "UI \u8bbe\u8ba1\u5e08", primaryOrgName = "UX \u7814\u7a76\u7ec4",
            departments = listOf("pmt-prod-ux"), onlineStatus = "online", employmentType = "contractor",
        ),
        "u-ops-1" to TeamUser(
            _id = "u-ops-1", username = "chen.hao", fname = "\u9648\u6d69",
            jobName = "\u6280\u672f\u603b\u76d1", primaryOrgName = "\u8fd0\u8425\u670d\u52a1",
            departments = listOf("pmt-ops"), status = "away", employmentType = "parttime",
        ),
        "u-l1d-1" to TeamUser(
            _id = "u-l1d-1", username = "yu.hui", fname = "\u4f59\u6656",
            jobName = "\u8fd0\u8425\u603b\u76d1", primaryOrgName = "\u8fd0\u8425\u5904",
            departments = listOf("l1d-office"), status = "online",
        ),
    )

    @Test
    fun `builds PMT home model from the configured root`() {
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = userMap, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals("SSC", model.companyName)
        assertEquals("\u6797\u6653\u6668", model.me?.displayName)
        assertEquals(false, model.me?.onlineStatus)
        assertEquals("u-self", model.me?.presenceUserId)
        assertEquals(2, model.departments.size)
        model.departments[0].let {
            assertEquals("pmt-prod", it.id)
            assertEquals("Galata", it.name)
            assertEquals("PMT", it.tagLabel)
            assertEquals(3, it.totalCount)
            assertEquals(2, it.onlineCount)
            assertTrue(it.isMine)
        }
        model.departments[1].let {
            assertEquals("pmt-ops", it.id)
            assertEquals("L3D", it.tagLabel)
            assertEquals(1, it.totalCount)
            assertEquals(1, it.onlineCount)
            assertFalse(it.isMine)
        }
        assertEquals(4, model.totalCount)
        assertEquals(
            TeamEmploymentCounts(fullTime = 1, outsourcing = 1, internship = 1, partTime = 1, other = 0),
            model.employmentCounts,
        )
    }

    @Test
    fun `maps statusConnection to presence fallback on members`() {
        val users = userMap + (
            "u-ops-1" to userMap.getValue("u-ops-1").copy(status = "offline", statusConnection = "online")
            )
        val model = buildTeamDeptModel(
            BuildTeamDeptModelParams(deptId = "pmt-ops", userMap = users, departmentMap = departmentMap),
        )
        assertEquals(TUserStatus.ONLINE, model.members.first { it.username == "chen.hao" }.presenceFallbackStatus)
    }

    @Test
    fun `maps away contact status to presence fallback on members`() {
        val model = buildTeamDeptModel(
            BuildTeamDeptModelParams(deptId = "pmt-ops", userMap = userMap, departmentMap = departmentMap),
        )
        val member = model.members.first { it.username == "chen.hao" }
        assertEquals("u-ops-1", member.presenceUserId)
        assertEquals(TUserStatus.AWAY, member.presenceFallbackStatus)
    }

    @Test
    fun `builds L1D home model from the configured root`() {
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.L1D,
                userMap = userMap, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals(1, model.departments.size)
        model.departments[0].let {
            assertEquals("l1d-office", it.id)
            assertEquals("\u8fd0\u8425\u5904", it.name)
            assertEquals("L3D", it.tagLabel)
            assertEquals(1, it.totalCount)
            assertEquals(1, it.onlineCount)
            assertFalse(it.isMine)
        }
        assertEquals(1, model.employmentCounts.other)
    }

    @Test
    fun `marks the current member as self when resolved by username only`() {
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = userMap, departmentMap = departmentMap,
                username = "lin.xiaochen",
            ),
        )

        assertEquals(true, model.me?.isSelf)
    }

    @Test
    fun `searches the current home model by department and member fields`() {
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = userMap, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals(listOf("pmt-prod"), searchTeamHome(model, "Galata").departments.map { it.id })
        assertEquals(listOf("qiu.yu"), searchTeamHome(model, "\u90b1\u96e8").members.map { it.username })
        val empty = searchTeamHome(model, "not-found")
        assertTrue(empty.departments.isEmpty())
        assertTrue(empty.members.isEmpty())
    }

    @Test
    fun `builds department detail sections with direct members before child departments`() {
        val model = buildTeamDeptModel(
            BuildTeamDeptModelParams(
                deptId = "pmt-prod",
                userMap = userMap, departmentMap = departmentMap,
                currentUserId = "u-self", directMembersTitle = "Direct reports",
            ),
        )

        assertEquals("Galata", model.name)
        assertEquals(3, model.totalCount)
        assertEquals(2, model.onlineCount)
        assertEquals(2, model.sections.size)
        model.sections[0].let {
            assertEquals("direct:pmt-prod", it.id)
            assertEquals("Direct reports", it.title)
            assertEquals(listOf(true, false), it.members.map { m -> m.isSelf })
        }
        model.sections[1].let {
            assertEquals("dept:pmt-prod-ux", it.id)
            assertEquals(listOf("qiu.yu"), it.members.map { m -> m.username })
        }
    }

    @Test
    fun `uses caller-provided unknown member labels in home and department models`() {
        val usersWithUnknownMember = userMap + (
            "u-prod-2" to TeamUser(_id = "u-prod-2", status = "online")
            )
        val homeModel = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = usersWithUnknownMember, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
                unknownMemberLabel = "Unknown person",
            ),
        )
        val deptModel = buildTeamDeptModel(
            BuildTeamDeptModelParams(
                deptId = "pmt-prod",
                userMap = usersWithUnknownMember, departmentMap = departmentMap,
                currentUserId = "u-self", unknownMemberLabel = "Unknown person",
            ),
        )

        assertEquals("Unknown person", homeModel.members.first { it.id == "u-prod-2" }.displayName)
        assertEquals("Unknown person", deptModel.members.first { it.id == "u-prod-2" }.displayName)
    }

    @Test
    fun `deduplicates members across department detail sections`() {
        val duplicatedDepartments = departmentMap + (
            "pmt-prod-ux" to departmentMap.getValue("pmt-prod-ux").copy(users = listOf("u-ux-1", "u-prod-2"))
            )
        val model = buildTeamDeptModel(
            BuildTeamDeptModelParams(
                deptId = "pmt-prod",
                userMap = userMap, departmentMap = duplicatedDepartments,
                currentUserId = "u-self",
            ),
        )

        assertEquals(
            listOf("lin.xiaochen", "huang.min", "qiu.yu"),
            model.sections.flatMap { it.members.map { m -> m.username } },
        )
    }

    @Test
    fun `searches department detail members and removes empty sections`() {
        val model = buildTeamDeptModel(
            BuildTeamDeptModelParams(
                deptId = "pmt-prod",
                userMap = userMap, departmentMap = departmentMap,
                currentUserId = "u-self",
            ),
        )

        val sections = searchTeamDept(model, "UI").sections
        assertEquals(1, sections.size)
        assertEquals("dept:pmt-prod-ux", sections[0].id)
        assertEquals(listOf("qiu.yu"), sections[0].members.map { it.username })
    }

    @Test
    fun `does not loop forever when department children contain a cycle`() {
        val cyclicDepartments = departmentMap + mapOf(
            "pmt-prod" to departmentMap.getValue("pmt-prod").copy(children = listOf("pmt-prod-ux")),
            "pmt-prod-ux" to departmentMap.getValue("pmt-prod-ux").copy(children = listOf("pmt-prod")),
        )
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = userMap, departmentMap = cyclicDepartments,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals(4, model.totalCount)
    }

    @Test
    fun `shows probation as employeeDesc for probationary full-time member`() {
        val users = userMap + (
            "u-self" to userMap.getValue("u-self")
                .copy(employeeStatus = "\u8bd5\u7528", employeeType = "\u5168\u804c")
            )
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = users, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals("\u8bd5\u7528", model.me?.employeeDesc)
    }

    @Test
    fun `shows employeeType as employeeDesc for normal active member`() {
        val users = userMap + (
            "u-self" to userMap.getValue("u-self")
                .copy(employeeStatus = "\u5728\u804c", employeeType = "\u5168\u804c")
            )
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT,
                userMap = users, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals("\u5168\u804c", model.me?.employeeDesc)
    }

    // ---- buildTeamFlatList（子部门视图）----

    @Test
    fun `flat list puts child depts before direct members and counts subtree`() {
        val model = buildTeamFlatList(
            BuildTeamFlatListParams(
                deptId = "pmt-prod",
                userMap = userMap, departmentMap = departmentMap,
                currentUserId = "u-self", username = "lin.xiaochen",
            ),
        )

        assertEquals("Galata", model.name)
        assertEquals(3, model.totalCount)
        assertEquals(2, model.directMemberCount)
        assertEquals(1, model.childDeptCount)
        // items 序：先部门（pmt-prod-ux）后直属成员（u-self, u-prod-2）
        assertEquals(listOf("dept", "member", "member"), model.items.map { it::class.simpleName?.lowercase() })
        val deptItem = model.items[0] as TeamFlatListItem.Dept
        assertEquals("pmt-prod-ux", deptItem.dept.id)
        assertEquals(1, deptItem.dept.totalCount)
        assertEquals("lin.xiaochen", (model.items[1] as TeamFlatListItem.Member).member.username)
        assertEquals("huang.min", (model.items[2] as TeamFlatListItem.Member).member.username)
    }

    @Test
    fun `flat list on missing dept returns empty model`() {
        val model = buildTeamFlatList(
            BuildTeamFlatListParams(deptId = "no-such", userMap = userMap, departmentMap = departmentMap),
        )
        assertTrue(model.items.isEmpty())
        assertEquals("no-such", model.deptId)
        assertEquals(0, model.totalCount)
    }

    // ---- rootTree 计数（footer）----

    @Test
    fun `root employment counts extract only positive values from countIncludeChildren`() {
        val rootWithCounts = departmentMap + (
            "EMT-0" to departmentMap.getValue("EMT-0").copy(
                countIncludeChildren = mapOf(
                    "all" to 10, "fullTime" to 7, "outsourcing" to 0, "internship" to 3, "partTime" to 0,
                ),
            )
            )
        val model = buildTeamHomeModel(
            BuildTeamHomeModelParams(
                rootType = TeamRootType.PMT, userMap = userMap, departmentMap = rootWithCounts,
            ),
        )

        assertEquals(10, model.rootTotalCount)
        assertEquals(mapOf("fullTime" to 7, "internship" to 3), model.rootEmploymentCounts)
    }

    // ---- presence 判定链 ----

    @Test
    fun `isRocketChatUserId filters dept keys bots username lookalikes and short ids`() {
        assertTrue(isRocketChatUserId("aBcDeFgHiJkLmNoPQ")) // 17 \u4f4d Meteor id
        assertTrue(isRocketChatUserId("652a1b3c4d5e6f00112233aa")) // ≥6 \u957f\u4e32
        assertFalse(isRocketChatUserId("EMT-0")) // \u90e8\u95e8 key
        assertFalse(isRocketChatUserId("meeting.bot")) // bot
        assertFalse(isRocketChatUserId("same.name", username = "same.name")) // \u4e0e username \u76f8\u540c
        assertFalse(isRobotChatUserIdLookalike()) // \u542b '.' \u7684 username \u5f62\u6001
        assertFalse(isRocketChatUserId("ab12")) // <6
        assertFalse(isRocketChatUserId(null))
        assertFalse(isRocketChatUserId("  "))
    }

    private fun isRobotChatUserIdLookalike(): Boolean = isRocketChatUserId("zhang.san01")

    @Test
    fun `pickPresenceUserId prefers real rc id then map key`() {
        // _id 与 username 相同 → 排除；key 命中
        assertEquals(
            "u-self",
            pickPresenceUserId(TeamUser(_id = "lin.xiaochen", username = "lin.xiaochen"), "u-self"),
        )
        // _id 真 RC id → 直取
        assertEquals(
            "652a1b3c4d5e6f00112233aa",
            pickPresenceUserId(TeamUser(_id = "652a1b3c4d5e6f00112233aa", username = "lin.xiaochen"), "u-self"),
        )
        // 两者均不合规（EMT- key + username 同 _id）→ null
        assertNull(pickPresenceUserId(TeamUser(_id = "lin.xiaochen", username = "lin.xiaochen"), "EMT-0"))
    }

    @Test
    fun `employment type normalization maps contractor intern parttime`() {
        assertEquals(TeamEmploymentType.FULL_TIME, toEmploymentType("fulltime"))
        assertEquals(TeamEmploymentType.INTERNSHIP, toEmploymentType("intern"))
        assertEquals(TeamEmploymentType.PART_TIME, toEmploymentType("Part_Time"))
        assertEquals(TeamEmploymentType.OUTSOURCING, toEmploymentType("contractor"))
        assertEquals(TeamEmploymentType.OUTSOURCING, toEmploymentType("outsourcing"))
        assertEquals(TeamEmploymentType.OTHER, toEmploymentType("weird"))
        assertEquals(TeamEmploymentType.OTHER, toEmploymentType(null))
    }

    // ---- parseContactsMap ----

    @Test
    fun `parseContactsMap reads top-level fields and tolerates missing maps`() {
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"userMap":{"u1":{"_id":"u1","username":"zhang.san","fname":"Zhang San"}},
                "departmentMap":{"d1":{"_id":"d1","name":"Dept","children":["d2"],"users":["u1"]}},
                "rootTree":["EMT-0","EMT-1328"]}""",
        )
        val payload = parseContactsMap(raw)

        assertEquals("zhang.san", payload.userMap.getValue("u1").username)
        assertEquals("Zhang San", payload.userMap.getValue("u1").fname)
        assertEquals(listOf("d2"), payload.departmentMap.getValue("d1").children)
        assertEquals(listOf("EMT-0", "EMT-1328"), payload.rootTree)
    }

    @Test
    fun `parseContactsMap unwraps nested data envelope and defaults on invalid shapes`() {
        val nested = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"data":{"userMap":{},"departmentMap":{},"rootTree":[]}}""",
        )
        val empty = parseContactsMap(nested)
        assertTrue(empty.userMap.isEmpty())
        assertTrue(empty.departmentMap.isEmpty())
        assertTrue(empty.rootTree.isEmpty())

        // 裸数组/非对象 → 空载荷
        val arr = kotlinx.serialization.json.Json.parseToJsonElement("[1,2]")
        val fromArray = parseContactsMap(arr)
        assertTrue(fromArray.userMap.isEmpty())
    }
}
