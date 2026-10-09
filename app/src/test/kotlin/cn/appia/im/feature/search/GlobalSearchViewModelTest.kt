package cn.appia.im.feature.search

import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.api.FilesSearchApi
import cn.appia.im.feature.search.ui.appendChatSearchPage
import cn.appia.im.feature.search.ui.chatSearchHasMore
import cn.appia.im.feature.search.ui.parseChatSearchMessages
import cn.appia.im.feature.search.ui.messageSearchSnippet
import cn.appia.im.feature.search.ui.formatSearchMessageTime
import cn.appia.im.feature.search.ui.parseIsoTs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * \u5168\u5c40\u641c\u7d22\u7eaf\u903b\u8f91 + VM \u884c\u4e3a\u6d4b\u8bd5\uff08\u5bf9\u7167 RN useGlobalSearch.test / spotlightV2GlobalSearch.test /
 * globalSearchFilter.test / splitTextBySearchKeyword.test\uff09\uff1a
 * \u4e09\u6bb5\u67e5\u8be2\u53c2\u6570\u3001\u6620\u5c04\u5206\u7ec4\u3001\u53bb\u91cd\u6b62\u6b65\u3001\u9ad8\u4eae\u5206\u6bb5\u3001\u9632\u6296 300\u3001\u7ade\u6001\u5b88\u536b\u3001\u79bb\u7ebf\u56de\u9000\u672c\u5730\u5206\u533a\u3002
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private fun tResolver(): (String, Map<String, String>) -> String = { key, args ->
        var out = when (key) {
            "globalsearch_messageroomline" -> "{{count}} matches for {{q}}"
            "globalsearch_matchedmembersline" -> "Includes members: {{names}}"
            "globalsearch_nameseparator" -> ", "
            "roomitem_nomessage" -> "No message"
            "agent" -> "Agent"
            else -> key
        }
        args.entries.fold(out) { acc, (k, v) -> acc.replace("{{$k}}", v) }
    }

    private fun chat(
        _id: String,
        t: String,
        fname: String = "",
        name: String = "",
        uids: String? = null,
        usernames: String? = null,
        lastMessage: String? = null,
    ): ChatEntity = ChatEntity(
        _id = _id,
        f = false,
        t = t,
        ts = 0.0,
        ls = 0.0,
        name = name,
        fname = fname,
        rid = _id,
        open = true,
        alert = false,
        unread = 0.0,
        user_mentions = 0.0,
        group_mentions = 0.0,
        room_updated_at = 0.0,
        ro = false,
        archived = false,
        auto_translate_language = "en",
        team_id = "",
        uids = uids,
        usernames = usernames,
        last_message = lastMessage,
    )

    private fun obj(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    // \u2500\u2500 \u6620\u5c04\u5206\u7ec4\uff08RN mapSpotlightV2ToRows\uff09\u2500\u2500

    @Test
    fun `maps spotlight users rooms and usersInRooms with dedupe`() {
        val raw = obj(
            """
            {"users":[{"_id":"u1","username":"zhang","name":"\u5f20\u4f1f"}],
             "rooms":[{"_id":"r1","name":"dev","t":"c","lastMessage":{"msg":"hello **team**"}}],
             "usersInRooms":[{"room":{"_id":"r1","t":"p","dname":"dup-room"},"users":[{"name":"\u7532"}]},
                              {"room":{"_id":"r2","t":"p","dname":"\u641c\u7d22\u7fa4"},"users":[{"name":"\u7532"},{"name":"\u4e59"}]}]}
            """.trimIndent(),
        )
        val rows = mapSpotlightV2ToRows(raw, emptyList(), tResolver())
        assertEquals(3, rows.size)
        val contact = rows[0]
        assertTrue(contact.isContact)
        assertEquals("\u5f20\u4f1f", contact.title)
        assertEquals("zhang", contact.username)
        // \u65e0\u672c\u5730 DM \u2192 rid \u56de\u9000 username\uff08openDirectMessage \u94fe knownRid \u786e\u8bc1\u8bed\u4e49\uff09
        assertEquals("zhang", contact.rid)
        val channel = rows[1]
        assertEquals("spotlight-room-r1", channel.key)
        assertEquals("dev", channel.title)
        assertEquals("c", channel.roomType)
        // lastMessage msg stripMarkdownLite \u622a 120
        assertEquals("hello team", channel.subtitle)
        // usersInRooms \u4e2d r1 \u4e0e rooms \u91cd\u590d \u2192 \u53bb\u91cd\uff1br2 \u4fdd\u7559 + \u5339\u914d\u6210\u5458 subtitle
        val uir = rows[2]
        assertEquals("spotlight-uir-r2", uir.key)
        assertEquals("p", uir.roomType)
        assertEquals("Includes members: \u7532, \u4e59", uir.subtitle)
    }

    @Test
    fun `mapSpotlightV2ToRows usersInRooms empty names falls back to ellipsis`() {
        val raw = obj("""{"usersInRooms":[{"room":{"_id":"r9","t":"p","name":"n"},"users":[]}]}""")
        val rows = mapSpotlightV2ToRows(raw, emptyList(), tResolver())
        assertEquals("Includes members: \u2026", rows[0].subtitle)
    }

    @Test
    fun `resolveDmRid uses local direct chat when usernames include target`() {
        val chats = listOf(
            chat("dm1", "d", fname = "\u5f20\u4e09", usernames = """["me","zhangsan"]"""),
            chat("dm2", "d", fname = "\u674e\u56db", usernames = """["me","lisi"]"""),
        )
        assertEquals("dm1", resolveDmRid("zhangsan", chats))
        // \u65e0\u672c\u5730\u547d\u4e2d \u2192 username \u56de\u9000
        assertEquals("wangwu", resolveDmRid("wangwu", chats))
    }

    // \u2500\u2500 messages.rooms \u6620\u5c04\uff08RN mapSpotlightMessagesToRows\uff09\u2500\u2500

    @Test
    fun `maps message rooms with count subtitle and direct avatar name`() {
        val raw = obj(
            """
            {"messages":{"rooms":[
              {"_id":"dm1","t":"d","fname":"\u5f20\u4e09","name":"display-name","username":"zhangsan","messageLength":2},
              {"_id":"r1","t":"c","name":"general","messageLength":10}
            ]}}
            """.trimIndent(),
        )
        val rows = mapSpotlightMessagesToRows(raw, " doc ", tResolver())
        assertEquals(2, rows.size)
        assertEquals("d", rows[0].roomType)
        assertEquals("zhangsan", rows[0].avatarName) // RN: d \u578b\u623f username \u4f18\u5148
        assertEquals("\u5f20\u4e09", rows[0].title)
        assertEquals("2 matches for doc", rows[0].subtitle)
        assertEquals("c", rows[1].roomType)
        assertEquals("general", rows[1].avatarName)
        assertEquals("10 matches for doc", rows[1].subtitle)
    }

    @Test
    fun `enrichMessageRows prefers local chat type and avatar`() {
        val rows = listOf(
            MessageSearchRow("k", "t", "s", "dm1", "d", avatarName = "wrong"),
            MessageSearchRow("k2", "t2", "s", "unknown", "c"),
        )
        val chats = listOf(chat("dm1", "d", name = "zhangsan"))
        val enriched = enrichMessageRows(rows, chats)
        assertEquals("d", enriched[0].roomType)
        assertEquals("zhangsan", enriched[0].avatarName)
        // \u65e0\u672c\u5730\u884c\u7684\u4fdd\u7559\u539f\u6837
        assertEquals("c", enriched[1].roomType)
        assertEquals(null, enriched[1].avatarName)
    }

    @Test
    fun `messageLength missing defaults to zero and missing rid skipped`() {
        val raw = obj(
            """{"messages":{"rooms":[{"_id":"","name":"x"},{"_id":"r1","name":"y"}]}}""",
        )
        val rows = mapSpotlightMessagesToRows(raw, "q", tResolver())
        assertEquals(1, rows.size)
        assertEquals("0 matches for q", rows[0].subtitle)
    }

    // \u2500\u2500 files meta / fileRowKey\uff08RN spotlightFilesMeta / fileRowKey\uff09\u2500\u2500

    @Test
    fun `spotlightFilesMeta reads cursor hasMore and totalHint`() {
        val raw = obj(
            """{"files":[{"_id":"f1"},{"_id":"f2"}],"filesNextCursor":"c1","filesHasMore":true,"filesLength":9}""",
        )
        val meta = spotlightFilesMeta(raw)
        assertEquals(2, meta.files.size)
        assertEquals("c1", meta.nextCursor)
        assertTrue(meta.hasMore)
        assertEquals(9, meta.totalHint)
    }

    @Test
    fun `fileRowKey combines messageId and nested fileId with fallbacks`() {
        val both = obj("""{"_id":"m1","file":{"_id":"f1"}}""")
        assertEquals("m1:f1", fileRowKey(both, 0))
        val nestedOnly = obj("""{"file":{"_id":"f2"}}""")
        assertEquals("f2", fileRowKey(nestedOnly, 0))
        val messageOnly = obj("""{"_id":"m2"}""")
        assertEquals("m2:3", fileRowKey(messageOnly, 3))
        val neither = obj("""{}""")
        assertEquals("file-1", fileRowKey(neither, 1))
    }

    @Test
    fun `fileItemDisplayName prefers nested file name`() {
        assertEquals("nested.pdf", fileItemDisplayName(obj("""{"name":"top.txt","file":{"_id":"f","name":"nested.pdf"}}""")))
        assertEquals("top.txt", fileItemDisplayName(obj("""{"name":"top.txt"}""")))
        assertEquals("f", fileItemDisplayName(obj("""{"file":{"_id":"f"}}""")))
        assertEquals("", fileItemDisplayName(obj("""{}""")))
    }

    @Test
    fun `pickGlobalSearchFileLink walks shapes and synthesizes file-proxy`() {
        assertEquals("/x/a.pdf", pickGlobalSearchFileLink(obj("""{"url":"/x/a.pdf"}""")))
        assertEquals("s3path", pickGlobalSearchFileLink(obj("""{"AmazonS3":{"path":"s3path"}}""")))
        assertEquals(
            "title_link.pdf",
            pickGlobalSearchFileLink(obj("""{"attachments":[{"title_link":"title_link.pdf"}]}""")),
        )
        // \u5bf9\u9f50\u65e7\u7248 loadFile\uff1afileId+fileName \u5408\u6210 /file-proxy/{fileId}/{fileName}
        assertEquals(
            "/file-proxy/f9/report.docx",
            pickGlobalSearchFileLink(obj("""{"file":{"_id":"f9","name":"report.docx"}}""")),
        )
    }

    // \u2500\u2500 \u79bb\u7ebf\u56de\u9000\u5206\u533a\uff08RN partitionChatsForGlobalSearch\uff09\u2500\u2500

    @Test
    fun `partition puts direct matches in contacts and channel-like in channels`() {
        val chats = listOf(
            chat("d1", "d", fname = "\u5f20\u4f1f", uids = """["u1","u2"]""", usernames = """["u1","u2"]"""),
            chat("c1", "c", fname = "\u5f20\u4f1f\u9879\u76ee\u7fa4"),
            chat("a1", "d", fname = "\u52a9\u624b", uids = """["me"]""", usernames = """["me"]"""),
            chat("x1", "d", fname = "\u4e0d\u5339\u914d"),
        )
        val (contacts, channels) = partitionChatsForGlobalSearch(chats, "\u5f20", "me")
        assertEquals(listOf("d1"), contacts.map { it._id })
        assertEquals(listOf("c1"), channels.map { it._id })
    }

    @Test
    fun `local fallback rows carry subtitles for contacts and channels`() {
        val chats = listOf(
            chat("d1", "d", fname = "\u5f20\u4f1f", name = "zw", usernames = """["me","zw"]""",
                lastMessage = null),
            chat("c1", "c", fname = "\u9879\u76ee\u7fa4", name = "proj",
                lastMessage = """{"msg":"\u89c1\u9644\u4ef6","u":{"name":"\u7532"}}"""),
        )
        val rows = localFallbackRows(chats, "\u9879", "me", tResolver())
        // \u5173\u952e\u8bcd\u300c\u9879\u300d\u53ea\u547d\u4e2d c1
        assertEquals(1, rows.size)
        assertEquals("local-ch-c1", rows[0].key)
        assertEquals("\u9879\u76ee\u7fa4", rows[0].title)
        assertEquals("\u7532\uff1a\u89c1\u9644\u4ef6", rows[0].subtitle)
        // contacts \u547d\u4e2d\uff1adescription \u00b7 topic
        val rows2 = localFallbackRows(chats, "\u5f20", "me", tResolver())
        assertEquals(1, rows2.size)
        assertEquals("local-dm-d1", rows2[0].key)
        assertEquals("d", rows2[0].roomType)
        assertEquals("zw", rows2[0].avatarName)
    }

    // \u2500\u2500 \u9632\u6296 300 / \u7ade\u6001\u5b88\u536b / \u5931\u8d25\u56de\u9000\uff08VM \u884c\u4e3a\uff09\u2500\u2500

    @Test
    fun `debounces 300ms and empty query clears immediately`() = runTest {
        var calls = 0
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = { calls++; emptySpotlightObject },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("a")
        runCurrent()
        assertEquals(0, calls) // \u672a\u5230 300ms \u4e0d\u53d1
        advanceTimeBy(299)
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)

        // \u7a7a\u8bcd\u7acb\u5373\u6e05\u7a7a\uff08\u4e0d\u7b49\u5f85\u9632\u6296\uff09
        vm.onQueryChanged("")
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun `retyping within debounce window cancels pending search`() = runTest {
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = { emptySpotlightObject },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("a")
        advanceTimeBy(200)
        vm.onQueryChanged("ab") // \u91cd\u7f6e\u9632\u6296
        advanceTimeBy(200)
        runCurrent()
        // \u7b2c\u4e00\u4e2a 300ms \u7a97\u53e3\u88ab\u53d6\u6d88\u2014\u2014\u672a\u53d1
        assertEquals("", vm.state.value.query)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("ab", vm.state.value.query)
    }

    @Test
    fun `dispose cancels in-flight debounce and search on unmount`() = runTest {
        // M6 ② / 终审 M-2：出屏（popBackStack → onCleared）取消在飞防抖——RN unmount-cancel 同义
        var calls = 0
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = { calls++; emptySpotlightObject },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("a")
        vm.dispose()
        advanceUntilIdle()
        assertEquals(0, calls) // 防抖窗口内出屏——搜索不发
        assertEquals("", vm.state.value.query)
    }

    @Test
    fun `out-of-order spotlight responses keep the latest query results`() = runTest {
        // RN \u8bed\u4e49\uff1a\u65e7\u67e5\u8be2\u54cd\u5e94\u665a\u5230\u4e0d\u5f97\u8986\u76d6\u65b0\u67e5\u8be2\u7ed3\u679c\u3002Kotlin \u4fa7\u7ade\u6001\u5b88\u536b = debounceJob \u53d6\u6d88
        // + searchEpoch \u53cc\u4fdd\u9669\u2014\u2014\u65e7\u67e5\u8be2\u7684 runSearch \u5728\u6302\u8d77\u70b9\u88ab\u53d6\u6d88\uff0c\u6c38\u4e0d\u6e05 state\u3002
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = { q ->
                if (q == "a") kotlinx.coroutines.delay(100) // \u65e7\u67e5\u8be2\u6162\u8fd4\u56de
                obj("""{"users":[{"_id":"x","username":"$q","name":"Result $q"}]}""")
            },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("a")
        advanceTimeBy(301)
        runCurrent() // "a" \u641c\u7d22\u5df2\u53d1\u51fa\uff0c\u6302\u8d77\u5728 delay(100)
        vm.onQueryChanged("ab")
        advanceTimeBy(301)
        runCurrent() // \u53d6\u6d88 "a"\uff1b"ab" \u641c\u7d22\u5b8c\u6210
        assertEquals("Result ab", vm.state.value.memberRows.first().title)
        advanceUntilIdle()
        assertEquals("Result ab", vm.state.value.memberRows.first().title)
    }

    @Test
    fun `search failure falls back to local partition rows with error banner`() = runTest {
        val chats = listOf(chat("c1", "c", fname = "\u5f20\u4f1f\u9879\u76ee\u7fa4"))
        val chatsFlow = MutableStateFlow(chats)
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = { throw IllegalStateException("offline") },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = chatsFlow,
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("\u5f20")
        advanceTimeBy(301)
        runCurrent()
        val s = vm.state.value
        assertTrue(s.error)
        assertTrue(s.isResultsReady)
        assertEquals(1, s.memberRows.size)
        assertEquals("local-ch-c1", s.memberRows[0].key)
        assertTrue(s.messagePreviewRows.isEmpty())
        assertTrue(s.files.isEmpty())
    }

    @Test
    fun `messages full prefetch populates messageFullRows after main response`() = runTest {
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = {
                obj("""{"messages":{"rooms":[{"_id":"r1","name":"room","messageLength":2}]}}""")
            },
            fetchMessagesFull = {
                obj(
                    """{"messages":{"rooms":[
                        {"_id":"r1","name":"room","messageLength":2},
                        {"_id":"r2","name":"room2","messageLength":1},
                        {"_id":"r3","name":"room3","messageLength":4},
                        {"_id":"r4","name":"room4","messageLength":1}]}}""",
                )
            },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("doc")
        advanceTimeBy(301)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.messagePreviewRows.size)
        assertEquals(4, s.messageFullRows?.size)
        assertFalse(s.messagesFullLoading)
    }

    @Test
    fun `loadMoreFiles appends pages and stops when hasMore false`() = runTest {
        var pageCalls = 0
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = {
                obj("""{"files":[{"_id":"f0"}],"filesNextCursor":"c0","filesHasMore":true}""")
            },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, cursor ->
                pageCalls++
                if (cursor == "c0") {
                    FilesSearchApi.Page(
                        listOf(obj("""{"_id":"f1"}"""), obj("""{"_id":"f0"}""")), // f0 \u91cd\u590d\uff08\u540c key \u4fdd\u7559 append\u2014\u2014RN \u540c\u4e49\u4e0d\u53bb\u91cd files\uff09
                        nextCursor = "c1",
                        hasMore = true,
                    )
                } else {
                    FilesSearchApi.Page(emptyList(), null, false)
                }
            },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("f")
        advanceTimeBy(301)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.files.size)

        vm.loadMoreFiles()
        runCurrent()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(3, s.files.size) // f0 \u9996\u9875 + f1/f0 \u8ffd\u52a0\uff08RN files append \u4e0d\u53bb\u91cd\uff09
        assertEquals("c1", s.filesCursor)

        vm.loadMoreFiles()
        runCurrent()
        advanceUntilIdle()
        val s2 = vm.state.value
        assertFalse(s2.filesHasMore)
        assertEquals(3, s2.files.size) // \u7a7a\u9875\u505c\u6b65
        assertEquals(2, pageCalls)
    }

    @Test
    fun `loadMoreFiles guards against missing hasMore and concurrent loads`() = runTest {
        val vm = GlobalSearchViewModel(
            fetchGlobalSearch = { obj("""{"files":[{"_id":"f0"}],"filesHasMore":false}""") },
            fetchMessagesFull = { emptySpotlightObject },
            fetchFilesPage = { _, _ -> FilesSearchApi.Page(emptyList(), null, false) },
            chatsFlow = MutableStateFlow(emptyList()),
            currentUserId = "me",
            scope = backgroundScope,
            t = tResolver(),
        )
        vm.onQueryChanged("f")
        advanceTimeBy(301)
        advanceUntilIdle()
        vm.loadMoreFiles() // hasMore=false \u2192 \u4e0d\u53d1
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.files.size)
    }

    @Test
    fun `messagesPreviewHasMore reflects spotlight messages hasMore flag`() = runTest {
        assertTrue(messagesHasMore(obj("""{"messages":{"hasMore":true,"rooms":[]}}""")))
        assertFalse(messagesHasMore(obj("""{"messages":{"rooms":[]}}""")))
    }

    // \u2500\u2500 \u6d88\u606f\u8be6\u60c5\u5206\u9875\uff08RN GlobalSearchMessageDetailScreen loadMessages\uff1a_id \u53bb\u91cd append / addedCount==0 \u6b62\u6b65\uff09\u2500\u2500

    @Test
    fun `appendChatSearchPage dedups by _id and counts added`() {
        val prev = listOf(
            searchMessage("m1"),
            searchMessage("m2"),
        )
        val page = listOf(
            searchMessage("m2"), // \u5df2\u89c1
            searchMessage("m3"), // \u65b0\u589e
            searchMessage("m1"), // \u5df2\u89c1
        )
        val (merged, added) = appendChatSearchPage(prev, page)
        assertEquals(3, merged.size)
        assertEquals(1, added)
        assertEquals(listOf("m1", "m2", "m3"), merged.map { it._id })
    }

    @Test
    fun `chatSearchHasMore stops on empty page or zero adds and continues on full page`() {
        // \u7a7a\u9875 \u2192 \u6b62\u6b65
        assertFalse(chatSearchHasMore(emptyList(), 0, 50))
        // append \u5168\u91cd\u590d\uff08addedCount==0\uff09\u2192 \u6b62\u6b65
        assertFalse(chatSearchHasMore(List(50) { searchMessage("m$it") }, 0, 50))
        // \u9996\u62c9\u4e0d\u8db3 50 \u2192 \u6b62\u6b65
        assertFalse(chatSearchHasMore(List(49) { searchMessage("m$it") }, 49, 50))
        // \u6ee1 50 \u4e14\u6709\u65b0\u589e \u2192 \u53ef\u7eed
        assertTrue(chatSearchHasMore(List(50) { searchMessage("m$it") }, 50, 50))
    }

    @Test
    fun `parseChatSearchMessages reads sender and body fields`() {
        val raw = obj(
            """
            {"messages":[
              {"_id":"m1","msg":"hello","ts":"2024-05-01T08:00:00.000Z",
               "u":{"_id":"u1","username":"zhang","name":"\u5f20\u4e09"},
               "attachments":[{"title_link":"/file-upload/f1/a.pdf"}]},
              {"msg":"blank id kept (RN same)"}
            ]}
            """.trimIndent(),
        )
        val messages = parseChatSearchMessages(raw)
        // RN loadMessages \u4e0d\u5254\u9664\u7a7a _id\uff08append \u53bb\u91cd\u65f6 !item._id \u6052\u65b0\u589e\uff1bkeyExtractor \u8d70 index \u56de\u9000\uff09
        assertEquals(2, messages.size)
        val m = messages[0]
        assertEquals("m1", m._id)
        assertEquals("hello", m.msg)
        assertEquals("u1", m.senderId)
        assertEquals("zhang", m.senderUsername)
        assertEquals("\u5f20\u4e09", m.senderName)
        assertTrue(m.attachments.toString().contains("title_link"))
        assertEquals("", messages[1]._id)
    }

    @Test
    fun `messageSearchSnippet windows keyword with ellipses`() {
        // \u65e0\u547d\u4e2d \u2192 \u9996\u5019\u9009\u5934 80 + ...
        val long = "x".repeat(100) + "tail"
        assertEquals(
            long.take(80) + "...",
            messageSearchSnippet(searchMessage("m", msg = long), "zz"),
        )
        // \u547d\u4e2d\u5728\u4e2d\u95f4 \u2192 \u524d\u540e\u7701\u7565\u7a97\u53e3
        val text = "a".repeat(100) + "KEYWORD" + "b".repeat(100)
        val snippet = messageSearchSnippet(searchMessage("m", msg = text), "keyword")
        assertTrue(snippet.startsWith("..."))
        assertTrue(snippet.endsWith("..."))
        assertTrue(snippet.contains("KEYWORD", ignoreCase = true))
        // \u77ed\u6587\u672c\u539f\u6837\u8fd4\u56de
        assertEquals("short msg", messageSearchSnippet(searchMessage("m", msg = "short msg"), "short"))
    }

    @Test
    fun `formatSearchMessageTime formats with local utc offset`() {
        val zone = java.util.TimeZone.getTimeZone("GMT+08:00")
        // 2024-05-01 00:30 UTC+8 \u2192 2024/05/01 00:30 (UTC+8)
        assertEquals(
            "2024/05/01 00:30 (UTC+8)",
            formatSearchMessageTime(java.time.ZonedDateTime
                .of(2024, 5, 1, 0, 30, 0, 0, zone.toZoneId())
                .toInstant().toEpochMilli(), zone),
        )
        assertEquals("", formatSearchMessageTime(0, zone))
    }

    @Test
    fun `parseIsoTs handles utc and offset forms`() {
        // Z \u540e\u7f00\uff1aUTC \u6beb\u79d2\uff082024-05-01T08:00:00Z\uff09
        assertEquals(1714550400000L, parseIsoTs("2024-05-01T08:00:00.000Z"))
        // +08:00 \u65f6\u533a\u504f\u79fb\uff1a\u672c\u5730 08:00 = UTC 00:00\uff082024-05-01T00:00:00Z\uff09
        assertEquals(1714521600000L, parseIsoTs("2024-05-01T08:00:00+08:00"))
        assertEquals(1714521600000L, parseIsoTs("2024-05-01T08:00:00+0800"))
        // \u79d2\u7701\u7565\u6beb\u79d2 / \u65e0\u533a\uff08\u6309 UTC\uff09
        assertEquals(1714550400000L, parseIsoTs("2024-05-01T08:00:00"))
        assertEquals(0L, parseIsoTs("not-a-date"))
        assertEquals(0L, parseIsoTs(null))
    }

    private fun searchMessage(id: String, msg: String? = null) =
        cn.appia.im.feature.search.ui.ChatSearchMessage(_id = id, msg = msg)
}
