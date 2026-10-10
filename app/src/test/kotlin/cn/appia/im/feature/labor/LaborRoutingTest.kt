package cn.appia.im.feature.labor

import cn.appia.im.core.network.api.WorktableItem
import cn.appia.im.core.network.api.WorktableItemExtra
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 三支路由对照（RN openLaborItem.ts:27-95 逐分支）。
 */
class LaborRoutingTest {
    private fun item(
        name: String = "\u5e94\u7528",
        type: Int = 0,
        url: String = "https://a.cn/p",
        needAuth: Boolean = false,
        extra: WorktableItemExtra? = null,
    ) = WorktableItem(name = name, type = type, url = url, needAuth = needAuth, extra = extra)

    // ---- 分支 1：type 3 私信员工服务 ----

    @Test
    fun `type 3 resolves to direct message branch`() {
        assertEquals(
            LaborAction.DirectMessage,
            resolveLaborAction(item(type = 3, extra = WorktableItemExtra(name = "hr.bot")), "https://s.cn"),
        )
    }

    @Test
    fun `dm with username resolves rid and reports room`() = runBlocking {
        val r = openLaborDirectMessage(item(extra = WorktableItemExtra(name = " hr.bot "))) { u ->
            assertEquals("hr.bot", u) // extra.name trim 后进链（RN :40）
            "rid-1"
        }
        assertEquals(LaborDmResult.Room("rid-1"), r)
    }

    @Test
    fun `dm missing or blank extra name reports missing`() = runBlocking {
        assertEquals(LaborDmResult.MissingUsername, openLaborDirectMessage(item()) { error("should not resolve") })
        assertEquals(
            LaborDmResult.MissingUsername,
            openLaborDirectMessage(item(extra = WorktableItemExtra(name = "   "))) { error("should not resolve") },
        )
    }

    @Test
    fun `dm chain failure reports failed`() = runBlocking {
        assertEquals(LaborDmResult.Failed, openLaborDirectMessage(item(extra = WorktableItemExtra(name = "u"))) { null })
    }

    // ---- 分支 2：消息待办 ----

    @Test
    fun `name message-todo routes to todo list`() {
        assertEquals(
            LaborAction.TodoList,
            resolveLaborAction(item(name = LABOR_ITEM_NAME_MESSAGE_TODO, url = "https://a.cn"), "https://s.cn"),
        )
    }

    @Test
    fun `type 10 routes to todo list`() {
        assertEquals(LaborAction.TodoList, resolveLaborAction(item(type = 10), "https://s.cn"))
    }

    // ---- 分支 3：InAppWeb ----

    @Test
    fun `default branch maps url title needAuth source needVPN`() {
        val action = resolveLaborAction(
            item(
                name = "\u85aa\u916c",
                url = "https://hr.cn/pay",
                needAuth = true,
                extra = WorktableItemExtra(source = "HR", needVPN = true),
            ),
            "https://s.cn",
        ) as LaborAction.Web
        assertEquals("https://hr.cn/pay", action.url)
        assertEquals("\u85aa\u916c", action.title)
        assertTrue(action.needAuth)
        assertEquals("HR", action.source)
        assertTrue(action.needVPN)
        assertFalse(action.needsLocation)
    }

    @Test
    fun `blank url falls back to host nonsupport path`() {
        val action = resolveLaborAction(item(url = "   "), "https://s.cn/") as LaborAction.Web
        assertEquals("https://s.cn/appia_fe/nonsupport", action.url)
    }

    // ---- 定位权限判定（考勤打卡/企业滴滴） ----

    @Test
    fun `attendance checkin and didi need location permission first`() {
        assertTrue(laborItemNeedsLocationPermission(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN))
        assertTrue((resolveLaborAction(item(name = LABOR_ITEM_NAME_ATTENDANCE_CHECKIN), "https://s.cn") as LaborAction.Web).needsLocation)
        assertTrue((resolveLaborAction(item(name = LABOR_ITEM_NAME_ENTERPRISE_DIDI), "https://s.cn") as LaborAction.Web).needsLocation)
        assertFalse((resolveLaborAction(item(name = "\u85aa\u916c"), "https://s.cn") as LaborAction.Web).needsLocation)
    }
}
