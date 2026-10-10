package cn.appia.im.feature.labor

import cn.appia.im.core.network.api.WorktableGroup
import cn.appia.im.core.network.api.WorktableItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * normalizeWorktableGroups / filterWorktableBySearch 移植对照
 * （RN normalizeWorktableGroups.ts + filterWorktableBySearch.ts 逐行）。
 */
class LaborRepositoryTest {
    private fun item(
        name: String = "\u5e94\u7528",
        status: Int = 1,
        url: String = "https://a.cn/x",
        icon: String = "https://a.cn/i.png",
        type: Int = 0,
        needAuth: Boolean = false,
        extraName: String? = null,
        source: String? = null,
        needVPN: Boolean? = null,
    ) = WorktableItem(
        name = name, status = status, url = url, icon = icon, type = type,
        needAuth = needAuth,
        extra = if (extraName == null && source == null && needVPN == null) null
        else cn.appia.im.core.network.api.WorktableItemExtra(source, extraName, needVPN),
    )

    private fun group(name: String = "\u5e38\u7528", vararg items: WorktableItem) =
        WorktableGroup(name = name, row = 1, items = items.toList())

    // ---- normalize：status 过滤 ----

    @Test
    fun `status zero and negative filtered`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item("a", status = 0), item("b", status = -1), item("c", status = 1))),
            "https://s.cn",
        )
        assertEquals(listOf("c"), out[0].items.map { it.name })
    }

    // ---- normalize：相对 url/icon 补前缀 ----

    @Test
    fun `relative url and icon get server prefix`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item(url = "/appia_fe/att", icon = "/icon/a.png"))),
            "https://s.cn",
        )
        assertEquals("https://s.cn/appia_fe/att", out[0].items[0].url)
        assertEquals("https://s.cn/icon/a.png", out[0].items[0].icon)
    }

    @Test
    fun `relative without leading slash gets slash inserted`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item(url = "appia_fe/att", icon = "icon/a.png"))),
            "https://s.cn",
        )
        assertEquals("https://s.cn/appia_fe/att", out[0].items[0].url)
        assertEquals("https://s.cn/icon/a.png", out[0].items[0].icon)
    }

    @Test
    fun `absolute http url untouched (case-insensitive scheme)`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item(url = "HTTPS://Ext.CN/p", icon = "http://ext.cn/i.png"))),
            "https://s.cn",
        )
        assertEquals("HTTPS://Ext.CN/p", out[0].items[0].url)
        assertEquals("http://ext.cn/i.png", out[0].items[0].icon)
    }

    @Test
    fun `trailing slash server base stripped before prefixing`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item(url = "/p"))),
            "https://s.cn///",
        )
        assertEquals("https://s.cn/p", out[0].items[0].url)
    }

    // ---- normalize：空分组丢弃 ----

    @Test
    fun `empty group dropped`() {
        val out = normalizeWorktableGroups(listOf(group("empty"), group("kept", item("a"))), "https://s.cn")
        assertEquals(listOf("kept"), out.map { it.name })
    }

    @Test
    fun `group whose items all status-filtered dropped`() {
        val out = normalizeWorktableGroups(listOf(group("dead", item("a", status = 0))), "https://s.cn")
        assertTrue(out.isEmpty())
    }

    // ---- normalize：guest 过滤 E-Learning ----

    @Test
    fun `guest filters E-Learning and E-learning items`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item("E-Learning \u5e73\u53f0"), item("E-learning \u8bfe\u7a0b"), item(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN))),
            "https://s.cn",
            isGuest = true,
        )
        assertEquals(listOf(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN), out[0].items.map { it.name })
    }

    @Test
    fun `non guest keeps E-Learning items`() {
        val out = normalizeWorktableGroups(
            listOf(group("g", item("E-Learning \u5e73\u53f0"))),
            "https://s.cn",
            isGuest = false,
        )
        assertEquals(listOf("E-Learning \u5e73\u53f0"), out[0].items.map { it.name })
    }

    @Test
    fun `guest with only blocked items drops whole group`() {
        val out = normalizeWorktableGroups(
            listOf(group("learn", item("E-Learning"))),
            "https://s.cn",
            isGuest = true,
        )
        assertTrue(out.isEmpty())
    }

    // ---- search ----

    @Test
    fun `blank query returns groups unchanged`() {
        val groups = listOf(group("g", item(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN), item("E-Learning")))
        assertEquals(groups, filterWorktableBySearch(groups, ""))
        assertEquals(groups, filterWorktableBySearch(groups, "   "))
    }

    @Test
    fun `query filters by case-insensitive name substring`() {
        val groups = listOf(group("g", item(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN), item("Weaver OA")))
        val out = filterWorktableBySearch(groups, "\u6253\u5361")
        assertEquals(listOf(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN), out[0].items.map { it.name })
        val out2 = filterWorktableBySearch(groups, "weaver")
        assertEquals(listOf("Weaver OA"), out2[0].items.map { it.name })
    }

    @Test
    fun `query trims and groups without matches dropped`() {
        val groups = listOf(group("g1", item(LABOR_ITEM_NAME_ATTENDANCE_CHECKIN)), group("g2", item(LABOR_ITEM_NAME_ENTERPRISE_DIDI)))
        val out = filterWorktableBySearch(groups, " \u6ef4\u6ef4 ")
        assertEquals(listOf("g2"), out.map { it.name })
    }

    // ---- repository（react-query 语义：刷新保留旧数据/错误置位） ----

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `fetch normalizes config and reports data`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"name":"g","row":1,"items":[
                   {"name":"a","status":1,"url":"/x","icon":""},
                   {"name":"b","status":0,"url":"","icon":""}]}]}""",
            ),
        )
        val repo = LaborRepository(OkHttpClient())
        repo.fetch(server.url("/").toString(), isGuest = false)
        val state = repo.state.value
        assertFalse(state.isError)
        assertEquals(listOf("a"), state.groups[0].items.map { it.name })
        // 相对 url 已补前缀
        assertTrue(state.groups[0].items[0].url.startsWith("http"))
    }

    @Test
    fun `fetch error keeps previous data and sets isError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val repo = LaborRepository(OkHttpClient())
        repo.fetch(server.url("/").toString(), isGuest = false)
        assertTrue(repo.state.value.isError)
    }
}
