package cn.appia.im.feature.search

import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.feature.chat.RoomMemberRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间内搜索（M5-T7）纯逻辑 + VM 行为测试（对照 RN useRoomSearch.ts / getVisibleRoomSearchTabs.ts /
 * roomSearchLinksHeuristic.ts / filterRoomMembersByQuery.ts / parseRoomFilesResponse.ts /
 * groupRoomSearchMediaByDate.ts / resolveRoomSearchFileUrl.ts）：
 * tab 结构 6/4、links 启发式、mentions 双层、加密房本地 LIKE、分页去重止步、竞态守卫。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSearchViewModelTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun msg(id: String, msg: String = "m-$id", ts: Double = 10.0) = MessageEntity(
        _id = id, rid = "r1", ts = ts, u = """{"_id":"u1","username":"u1"}""",
        alias = "", parse_urls = "[]", _updated_at = ts, msg = msg,
    )

    private fun chatSearchJson(vararg ids: String): JsonObject = obj(
        """{"success":true,"messages":[${ids.joinToString(",") {
            "{\"_id\":\"$it\",\"msg\":\"hit $it\",\"ts\":1}"
        }}]}""",
    )

    /** VM 工厂：全 fetch 注入 fake（测试不触网；scope 传 runTest 的 backgroundScope 走虚拟时钟）。 */
    private fun vm(
        scope: kotlinx.coroutines.CoroutineScope,
        roomType: String = "c",
        encrypted: Boolean = false,
        currentUserId: String? = "me",
        chatSearch: suspend (String, Int) -> JsonObject? = { _, _ -> chatSearchJson("m1") },
        files: suspend (String, Int, String) -> JsonObject? = { _, _, _ -> obj("""{"success":true,"files":[]}""") },
        mentions: suspend (Int) -> JsonObject? = { _ -> chatSearchJson("m1") },
        members: suspend () -> JsonObject? = { obj("""{"data":[]}""") },
        local: suspend (String, String) -> List<MessageEntity> = { _, _ -> emptyList() },
    ) = RoomSearchViewModel(
        rid = "r1",
        roomType = roomType,
        encrypted = encrypted,
        currentUserId = currentUserId,
        fetchChatSearch = chatSearch,
        fetchFilesPage = files,
        fetchMentionsPage = mentions,
        fetchMembers = members,
        localMessageSearch = local,
        scope = scope,
    )

    // ── tab 结构（RN getVisibleRoomSearchTabs / resolveInitialRoomSearchTab）──

    @Test
    fun `group room shows six tabs in RN order`() {
        assertEquals(
            listOf(
                RoomSearchTab.MESSAGES, RoomSearchTab.MEMBERS, RoomSearchTab.FILES,
                RoomSearchTab.MEDIA, RoomSearchTab.MENTIONS, RoomSearchTab.LINKS,
            ),
            getVisibleRoomSearchTabs("c"),
        )
    }

    @Test
    fun `direct room shows four tabs`() {
        assertEquals(
            listOf(RoomSearchTab.MESSAGES, RoomSearchTab.FILES, RoomSearchTab.MEDIA, RoomSearchTab.LINKS),
            getVisibleRoomSearchTabs("d"),
        )
        // 初始 tab 不在可见集 → 回退 messages（RN resolveInitialRoomSearchTab）
        assertEquals(RoomSearchTab.MESSAGES, resolveInitialRoomSearchTab("d", RoomSearchTab.MEMBERS))
        assertEquals(RoomSearchTab.MEMBERS, resolveInitialRoomSearchTab("c", RoomSearchTab.MEMBERS))
    }

    @Test
    fun `setActiveTab rejects invisible tab for direct room`() = runTest {
        val v = vm(backgroundScope, roomType = "d", chatSearch = { _, _ -> chatSearchJson("m1") })
        // MEMBERS 不可见 → 状态不切换、不触发 member 拉取
        v.setActiveTab(RoomSearchTab.MEMBERS)
        assertEquals(RoomSearchTab.MESSAGES, v.activeTab.value)
    }

    // ── links 启发式（RN roomSearchLinksHeuristic）──

    @Test
    fun `links heuristic keeps urls json or http in msg`() {
        val withUrls = msg("a").copy(urls = """[{"url":"https://a.com"}]""")
        val withHttp = msg("b", msg = "see https://b.com ok")
        val plain = msg("c", msg = "no link here")
        val badJson = msg("d").copy(urls = "{not json")
        val kept = filterMessagesWithLinks(listOf(withUrls, withHttp, plain, badJson))
        assertEquals(listOf("a", "b"), kept.map { it._id })
    }

    // ── members 过滤（RN filterRoomMembersByQuery）──

    @Test
    fun `member filter matches name username jobName case-insensitively`() {
        val members = listOf(
            RoomMemberRow("_1", "zhangsan", "\u5f20\u4f1f", jobName = "\u5de5\u7a0b\u5e08"),
            RoomMemberRow("_2", "lisi", "\u674e\u56db", jobName = "Designer"),
        )
        assertEquals(listOf("_1"), filterRoomMembersByQuery(members, "zhang").map { it._id })
        assertEquals(listOf("_1"), filterRoomMembersByQuery(members, "\u5de5").map { it._id })
        assertEquals(listOf("_2"), filterRoomMembersByQuery(members, "DESIGN").map { it._id })
        // 空词空集（RN 同：空词不显示 members 结果）
        assertTrue(filterRoomMembersByQuery(members, "  ").isEmpty())
    }

    @Test
    fun `members tab loads cache once then filters locally without refetch`() = runTest {
        var fetchCount = 0
        val v = RoomSearchViewModel(
            rid = "r1",
            roomType = "p",
            encrypted = false,
            currentUserId = "me",
            fetchChatSearch = { _, _ -> chatSearchJson("m1") },
            fetchFilesPage = { _, _, _ -> obj("""{"success":true,"files":[]}""") },
            fetchMentionsPage = { _ -> chatSearchJson("m1") },
            fetchMembers = {
                fetchCount++
                obj("""{"data":[{"map":{"zw":{"_id":"u1","username":"zw","name":"\u5f20\u4f1f","jobName":"\u5de5\u7a0b\u5e08"}},"members":["zw"]}]}""")
            },
            localMessageSearch = { _, _ -> emptyList() },
            scope = backgroundScope,
        )
        v.setActiveTab(RoomSearchTab.MEMBERS)
        v.onQueryChanged("\u5f20")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, v.state.value.memberRows.size)
        assertEquals("u1", v.state.value.memberRows[0]._id)
        // 换词重新过滤：不发第二次 members/v2
        v.onQueryChanged("\u5de5\u7a0b\u5e08")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, v.state.value.memberRows.size)
        assertEquals(1, fetchCount)
    }

    // ── files 解析 + hasMore（RN parseRoomFilesResponse / resolveRoomFilesHasMore）──

    @Test
    fun `parseRoomFilesPage maps fields and falls back sender name`() {
        val raw = obj(
            """{"success":true,"total":9,"files":[
               {"_id":"f1","name":"a.pdf","size":1024,"uploadedAt":"2026-01-02T03:04:05Z",
                "url":"/u/f1","typeGroup":"file","user":{"name":"\u5f20\u4e09"}},
               {"_id":"f2","name":"b.png","user":{"username":"lisi"}},
               {"name":"no-id"}]}""",
        )
        val (files, meta) = parseRoomFilesPage(raw)
        assertEquals(2, files.size) // _id 缺失剔除
        assertEquals("\u5f20\u4e09", files[0].senderName)
        assertEquals("lisi", files[1].senderName) // name 缺 → username
        assertTrue(meta.totalFromApi)
        assertEquals(9, meta.total)
        // success=false → 空页不可信
        val (empty, meta2) = parseRoomFilesPage(obj("""{"success":false,"files":[]}"""))
        assertTrue(empty.isEmpty())
        assertFalse(meta2.totalFromApi)
    }

    @Test
    fun `files hasMore trusts total when present else page-full heuristic`() {
        val page = listOf(RoomSearchFileRow("f", "n", "s"))
        assertFalse(resolveRoomFilesHasMore(1, emptyList(), FilesPageMeta(9, true), 50)) // 空页止步
        assertFalse(resolveRoomFilesHasMore(9, page, FilesPageMeta(9, true), 50)) // loaded>=total
        assertTrue(resolveRoomFilesHasMore(1, page, FilesPageMeta(9, true), 50)) // total 可信续
        val full = (1..50).map { RoomSearchFileRow("f$it", "n", "s") }
        assertTrue(resolveRoomFilesHasMore(50, full, FilesPageMeta(0, false), 50)) // 页满可续
        assertFalse(resolveRoomFilesHasMore(3, page, FilesPageMeta(0, false), 50)) // 不满止步
    }

    @Test
    fun `files tab paginates with dedupe and stops on zero added`() = runTest {
        var call = 0
        val v = vm(backgroundScope,
            files = { _, offset, fileType ->
                call++
                when (call) {
                    1 -> obj(
                        """{"success":true,"files":[${(1..50).joinToString(",") {
                            """{"_id":"f$it","name":"f$it.pdf","typeGroup":"$fileType"}"""
                        }}]}""",
                    ) // 首页 50 条（无 total → 页满可续）
                    2 -> obj("""{"success":true,"files":[{"_id":"f1","name":"dup"}]}""") // 全重复
                    else -> obj("""{"success":true,"files":[]}""")
                }
            },
        )
        v.setActiveTab(RoomSearchTab.FILES)
        v.onQueryChanged("f")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(50, v.state.value.fileRows.size)
        assertTrue(v.state.value.hasMoreFiles)
        v.loadMoreFiles()
        runCurrent() // backgroundScope 新起协程需 runCurrent 驱动（runTest 语义）
        // 全重复 → addedCount==0 止步，不追加
        assertEquals(50, v.state.value.fileRows.size)
        assertFalse(v.state.value.hasMoreFiles)
        assertEquals(2, call)
    }

    // ── media URL 解析（RN resolveRoomSearchFileUrl）──

    @Test
    fun `media url synthesis uses url then file-proxy and swaps ufs path`() {
        val withUrl = RoomSearchFileRow("f1", "a.png", "s", url = "/ufs/FileSystem:Uploads/a.png")
        val resolved = resolveRoomSearchFileUrl(withUrl, "u", "t", "https://s.test")
        assertTrue(resolved.contains("/file-proxy/a.png"))
        assertTrue(resolved.contains("rc_uid=u"))
        // url 缺失 → /file-proxy/{id}/{name} 合成 + 鉴权参数
        val synthesized = resolveRoomSearchFileUrl(RoomSearchFileRow("f9", "report.docx", "s"), "u1", "tk", "https://s.test")
        assertTrue(synthesized.contains("/file-proxy/f9/report.docx"))
        assertTrue(synthesized.contains("rc_uid=u1"))
        // 双缺 → 空串
        assertEquals("", resolveRoomSearchFileUrl(RoomSearchFileRow("", "", "s"), "u", "t", "https://s"))
    }

    @Test
    fun `media grid groups by calendar day into 4-column rows`() {
        // RN getMonth/getDate = 本地日历日；用本地 12:00 免时区跨日歧义
        fun localNoonIso(daysAgo: Int): String {
            val z = java.time.ZonedDateTime.now().withHour(12).withMinute(0).withSecond(0).withNano(0)
                .minusDays(daysAgo.toLong())
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(z)
        }
        val items = listOf(
            RoomSearchFileRow("a", "1", "s", uploadedAt = localNoonIso(0)),
            RoomSearchFileRow("b", "2", "s", uploadedAt = localNoonIso(0)),
            RoomSearchFileRow("c", "3", "s", uploadedAt = localNoonIso(0)),
            RoomSearchFileRow("d", "4", "s", uploadedAt = localNoonIso(0)),
            RoomSearchFileRow("e", "5", "s", uploadedAt = localNoonIso(0)),
            RoomSearchFileRow("f", "6", "s", uploadedAt = localNoonIso(1)),
            RoomSearchFileRow("g", "7", "s"), // 无日期 → unknown
        )
        val sections = groupRoomSearchMediaByDate(items)
        assertEquals(3, sections.size) // 今天 / 昨天 / unknown
        assertEquals(listOf(4, 1), sections[0].rows.map { it.size }) // 4 列网格
        assertEquals(ROOM_SEARCH_MEDIA_GRID_COLUMNS, 4)
        assertTrue(isMediaVideoCell(RoomSearchFileRow("v", "v", "s", typeGroup = "video")))
        assertFalse(isMediaVideoCell(RoomSearchFileRow("i", "i", "s", typeGroup = "image")))
    }

    // ── mentions 双层（服务端 mentions._id query + 客户端 msg 过滤）──

    @Test
    fun `mentions tab applies client-side msg filter over server query`() = runTest {
        var offsets = mutableListOf<Int>()
        val v = vm(backgroundScope,
            roomType = "p",
            mentions = { offset ->
                offsets.add(offset)
                obj(
                    """{"success":true,"messages":[
                       {"_id":"m1","msg":"hit \u5f20","ts":1},
                       {"_id":"m2","msg":"no match","ts":2}]}""",
                )
            },
        )
        v.setActiveTab(RoomSearchTab.MENTIONS)
        v.onQueryChanged("\u5f20")
        advanceTimeBy(301)
        runCurrent()
        // 双层：m2 被客户端过滤掉
        assertEquals(listOf("m1"), v.state.value.mentionRows.map { it._id })
        assertEquals(listOf(0), offsets)
        // 单聊（d）mentions tab 不可见也不发请求
        val dm = vm(backgroundScope, roomType = "d", mentions = { offset -> offsets.add(offset); chatSearchJson("x") })
        assertTrue(RoomSearchTab.MENTIONS !in dm.visibleTabs)
    }

    // ── 加密房本地 LIKE 分支 ──

    @Test
    fun `encrypted room searches local LIKE and disables paging`() = runTest {
        var localQueries = mutableListOf<String>()
        var remoteCalls = 0
        val v = vm(backgroundScope,
            encrypted = true,
            chatSearch = { _, _ -> remoteCalls++; chatSearchJson("never") },
            local = { _, q -> localQueries.add(q); listOf(msg("L1", msg = "local hit")) },
        )
        v.onQueryChanged("secret")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(0, remoteCalls) // 不走 chat.search
        assertEquals(listOf("secret"), localQueries)
        assertEquals(listOf("L1"), v.state.value.messageRows.map { it._id })
        assertFalse(v.state.value.hasMoreMessages)
        // loadMore 短路（RN loadMoreMessages encrypted 早退）
        v.loadMoreMessages()
        advanceUntilIdle()
        assertEquals(1, localQueries.size)
    }

    @Test
    fun `like pattern escapes percent underscore backslash`() {
        assertEquals("\\%50\\_\\%", sanitizeLike("%50_%"))
        assertEquals("a\\\\b", sanitizeLike("a\\b"))
        assertEquals("%\u5f20\u4e09%", buildLocalLikePattern(" \u5f20\u4e09 "))
    }

    // ── messages 分页 + 竞态（RN requestIdRef）──

    @Test
    fun `messages pagination advances offset and dedupes appends`() = runTest {
        val offsets = mutableListOf<Int>()
        val v = vm(backgroundScope,
            chatSearch = { _, offset ->
                offsets.add(offset)
                when (offset) {
                    0 -> chatSearchJson(*Array(50) { "m$it" }) // 首页 50
                    else -> chatSearchJson("m49", "m50", "extra") // 部分重复
                }
            },
        )
        v.onQueryChanged("hit")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(50, v.state.value.messageRows.size)
        assertTrue(v.state.value.hasMoreMessages)
        v.loadMoreMessages()
        runCurrent() // backgroundScope 新起协程需 runCurrent 驱动（runTest 语义）
        assertEquals(listOf(0, 50), offsets)
        // 2 新增（m49/m50 重复去重）→ append 非 0 → hasMore 按页满判（3<50 → false）
        assertEquals(52, v.state.value.messageRows.size)
        assertFalse(v.state.value.hasMoreMessages)
    }

    @Test
    fun `stale response does not overwrite newer results`() = runTest {
        // 旧查询慢返回：epoch 守卫丢弃
        val v = vm(backgroundScope,
            chatSearch = { q, _ ->
                if (q == "a") delay(100) // 旧查询慢
                chatSearchJson("res-$q")
            },
        )
        v.onQueryChanged("a")
        advanceTimeBy(301)
        runCurrent() // "a" 挂起在 delay(100)
        v.onQueryChanged("ab")
        advanceTimeBy(301)
        runCurrent() // "ab" 完成
        advanceUntilIdle() // "a" 迟到返回
        assertEquals(listOf("res-ab"), v.state.value.messageRows.map { it._id })
    }

    @Test
    fun `empty query clears immediately and failure resets rows`() = runTest {
        val v = vm(backgroundScope, chatSearch = { _, _ -> throw IllegalStateException("offline") })
        v.onQueryChanged("x")
        advanceTimeBy(301)
        runCurrent()
        assertTrue(v.state.value.messageRows.isEmpty())
        assertFalse(v.state.value.loading)
        // 先有结果再清空
        val v2 = vm(backgroundScope, chatSearch = { _, _ -> chatSearchJson("m1") })
        v2.onQueryChanged("x")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, v2.state.value.messageRows.size)
        v2.onQueryChanged("")
        runCurrent()
        assertTrue(v2.state.value.messageRows.isEmpty())
        assertEquals("", v2.state.value.searchText)
    }

    // ── chat.search wire 形状（RN getChatSearch：notIncludeFile=true 默认）──

    @Test
    fun `files query serializes name regex and typeGroup as JSON string`() {
        // 构造纯函数段：query JSON 串可被服务端 JSON.parse（转义正确性）
        val name = "a\"b\\c"
        val query = """{"name":{"${'$'}regex":"${escapeRegexInput(name)}","${'$'}options":"i"},"typeGroup":"file"}"""
        val parsed = Json.parseToJsonElement(query) as JsonObject
        val nameQuery = (parsed["name"] as JsonObject)
        assertEquals(name, (nameQuery["${'$'}regex"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("i", (nameQuery["${'$'}options"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("file", (parsed["typeGroup"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `chat search response maps to entities without persist`() {
        val rows = parseRoomSearchChatResponse(
            obj("""{"success":true,"messages":[{"_id":"m1","msg":"hello","ts":"2026-01-01T00:00:00Z","u":{"_id":"u1","username":"z"}}]}"""),
            "fallback-rid",
        )
        assertEquals(1, rows.size)
        assertEquals("m1", rows[0]._id)
        assertEquals("hello", rows[0].msg)
        // rid 由 applyApiFields 折算（无 data.rid 时 fallback）
        assertEquals("fallback-rid", rows[0].rid)
        // success=false → 空集
        assertTrue(parseRoomSearchChatResponse(obj("""{"success":false}"""), "r").isEmpty())
    }

    // ── M6 ②：出屏 dispose 取消在飞防抖/网络（RN unmount-cancel 同义）──

    @Test
    fun `dispose cancels in-flight debounce and fetch on unmount`() = runTest {
        var calls = 0
        val vm = vm(backgroundScope, chatSearch = { _, _ -> calls++; chatSearchJson("m1") })
        vm.onQueryChanged("kw")
        vm.dispose() // popBackStack → onCleared 等价（宿主 ViewModelStore 释放）
        advanceUntilIdle()
        assertEquals(0, calls) // 防抖窗口内出屏——请求不发
        assertEquals("", vm.state.value.searchText)
    }
}
