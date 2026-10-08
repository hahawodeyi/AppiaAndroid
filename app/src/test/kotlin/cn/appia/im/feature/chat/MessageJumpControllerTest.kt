package cn.appia.im.feature.chat

import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.network.api.SurroundingRaw
import cn.appia.im.domain.chat.JumpMessageTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 消息跳转高亮控制器测试（M5-T6 / 逐行为对照 appiaMobile/src/hooks/useRoomMessageJump.ts）：
 * plan 三路 / 15s 超时 / 取消 / chunk 占位 / 防死循环（控制器无路由订阅——jumpTo 只能外部
 * 显式调起，状态写不自我重触发）。虚拟时钟（StandardTestDispatcher + scheduler 泵）驱动
 * 300ms 防抖与超时；DraftRepositoryTest 同款。
 */
class MessageJumpControllerTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(dispatcher)

    private val toasts = mutableListOf<String>()
    private val crossRoomJumps = mutableListOf<Triple<String, String, String>>()
    private val scrollTargets = mutableListOf<String>()
    private val fetchCalls = mutableListOf<Pair<String, String>>()

    private var currentMessages: List<MessageEntity> = emptyList()
    private var resolveImpl: suspend (String) -> JumpMessageTarget? = { null }
    private var fetchImpl: suspend (String, String) -> SurroundingRaw = { _, _ ->
        SurroundingRaw(emptyList(), false, false)
    }
    private var scrollSucceeds = true

    /** 泵到静止（body 挂起协程全跑完）。 */
    private fun pump() = scheduler.advanceUntilIdle()

    /** 前进虚拟时钟并执行到期任务。 */
    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun controller(): MessageJumpController =
        MessageJumpController(
            scope = scope,
            rid = "r1",
            roomType = "c",
            resolve = { resolveImpl(it) },
            fetchSurrounding = { id, rid ->
                fetchCalls += id to rid
                fetchImpl(id, rid)
            },
            currentMessages = { currentMessages },
        ).also { c ->
            c.onToast = { toasts += it }
            c.onCrossRoomJump = { rid, t, mid -> crossRoomJumps += Triple(rid, t, mid) }
            c.scrollToMessage = { id ->
                scrollTargets += id
                scrollSucceeds
            }
        }

    // ── 消息构造（窗口倒序惯例：index 0 = 最新；ts ISO 串走 applyApiFields 真实解析）──

    private fun msg(id: String, ts: Long, t: String? = null) = MessageEntity(
        _id = id, rid = "r1", ts = ts.toDouble(), u = """{"_id":"u1","username":"u1"}""",
        alias = "", parse_urls = "[]", _updated_at = ts.toDouble(), msg = "m-$id", t = t,
    )

    /** loadSurroundingMessages 响应 messages 数组的 API 消息形状。 */
    private fun apiMessage(id: String, tsIso: String) = buildJsonObject {
        put("_id", id)
        put("rid", "r1")
        put("ts", tsIso)
        put("msg", "m-$id")
        put("u", buildJsonObject { put("_id", "u1"); put("username", "u1") })
    }

    // ── plan 三路（纯函数）──

    @Test
    fun `plan not-found when resolve returns null`() {
        val plan = planJumpToMessage("r1", "m1", resolved = null, windowIds = setOf("m1"))
        assertTrue(plan is JumpPlan.NotFound)
    }

    @Test
    fun `plan navigate-room when resolved rid differs from current room`() {
        val plan = planJumpToMessage("r1", "m1", JumpMessageTarget("m1", "r2"), windowIds = emptySet())
        assertEquals(JumpPlan.NavigateRoom("m1", "r2"), plan)
    }

    @Test
    fun `plan scroll when message already in window`() {
        val plan = planJumpToMessage(
            "r1", "m1", JumpMessageTarget("m1", "r1"), windowIds = setOf("m0", "m1"),
        )
        assertEquals(JumpPlan.Scroll("m1"), plan)
    }

    @Test
    fun `plan fetch-surrounding when same room but not in window`() {
        val plan = planJumpToMessage("r1", "m9", JumpMessageTarget("m9", "r1"), windowIds = setOf("m1"))
        assertEquals(JumpPlan.FetchSurrounding("m9", "r1"), plan)
    }

    // ── jumpTo 三路执行 + 超时/取消/防抖 ──

    @Test
    fun `not-found path toasts and stays realtime`() {
        resolveImpl = { null }
        val c = controller()
        c.jumpTo("r1", "m1")
        pump()
        assertEquals(listOf(MessageJumpController.TOAST_NOT_FOUND), toasts)
        assertFalse(c.state.value.isJumpMode)
        assertNull(c.state.value.jumpMessages)
    }

    @Test
    fun `cross-room target re-navigates without local work`() {
        val c = controller()
        c.jumpTo("r2", "m1", "p")
        pump()
        // RN runJump :166-173：目标房 ≠ 当前房 → onCrossRoomJump，不起 resolve/防抖
        assertEquals(listOf(Triple("r2", "p", "m1")), crossRoomJumps)
        assertTrue(toasts.isEmpty())
        assertFalse(c.state.value.isJumpLoading)
        assertTrue(fetchCalls.isEmpty())
    }

    @Test
    fun `resolved rid mismatch navigates room with roomType fallback`() {
        resolveImpl = { JumpMessageTarget("m1", "r2") } // resolve 后才见目标房不同
        val c = controller()
        c.jumpTo("r1", "m1")
        pump()
        assertEquals(listOf(Triple("r2", "c", "m1")), crossRoomJumps) // roomT 兜底当前房型
        assertFalse(c.state.value.isJumpMode)
    }

    @Test
    fun `fetch-surrounding replaces window scrolls and highlights`() {
        resolveImpl = { JumpMessageTarget("m9", "r1") }
        fetchImpl = { _, _ ->
            SurroundingRaw(
                apiMessages = listOf(
                    apiMessage("m8", "2026-01-02T00:00:00Z"),
                    apiMessage("m9", "2026-01-01T00:00:00Z"),
                ),
                moreBefore = true,
                moreAfter = false,
            )
        }
        val c = controller()
        c.jumpTo("r1", "m9")
        pump()
        val s = c.state.value
        assertTrue(s.isJumpMode)
        assertEquals("m9", s.highlightedMessageId)
        assertEquals(listOf("m9"), scrollTargets)
        assertTrue(s.moreBefore)
        // 倒序窗口：m8（较新）在前；moreBefore → PREVIOUS_CHUNK 占位在尾部（loadSurrounding 锚 m9）
        assertEquals(listOf("m8", "m9"), s.jumpMessages!!.filter { m -> !isLoadChunkType(m.t) }.map { it._id })
        assertEquals(LOAD_MORE_BEFORE, s.jumpMessages!!.last().t)
    }

    @Test
    fun `scroll path hits existing window without fetching`() {
        resolveImpl = { JumpMessageTarget("m1", "r1") }
        currentMessages = listOf(msg("m1", 10))
        val c = controller()
        c.jumpTo("r1", "m1")
        pump()
        assertEquals("m1", c.state.value.highlightedMessageId)
        assertTrue(fetchCalls.isEmpty()) // 已在窗口 → 不拉 surrounding
        assertFalse(c.state.value.isJumpMode)
    }

    @Test
    fun `loading overlay debounced 300ms and cleared after success`() {
        var releaseResolve: (() -> Unit)? = null
        resolveImpl = {
            suspendCancellableCoroutine { cont ->
                releaseResolve = { cont.resumeWith(Result.success(JumpMessageTarget("m1", "r1"))) }
            }
        }
        currentMessages = listOf(msg("m1", 10))
        val c = controller()
        c.jumpTo("r1", "m1")
        advance(299)
        assertFalse(c.state.value.isJumpLoading) // 防抖窗内不亮
        advance(1)
        assertTrue(c.state.value.isJumpLoading) // 300ms 到点亮
        releaseResolve?.invoke()
        pump()
        assertFalse(c.state.value.isJumpLoading) // 完成即熄
    }

    @Test
    fun `resolve timeout 15s toasts timeout and exits`() {
        resolveImpl = { suspendCancellableCoroutine { } } // 永不返回
        val c = controller()
        c.jumpTo("r1", "m1")
        advance(15_000)
        assertEquals(listOf(MessageJumpController.TOAST_TIMEOUT), toasts)
        assertFalse(c.state.value.isJumpMode)
        assertFalse(c.state.value.isJumpLoading)
    }

    @Test
    fun `cancel aborts run clears loading and exits to realtime source`() {
        resolveImpl = { suspendCancellableCoroutine { } }
        val c = controller()
        c.jumpTo("r1", "m1")
        advance(300)
        assertTrue(c.state.value.isJumpLoading)
        c.cancelJump()
        pump()
        assertFalse(c.state.value.isJumpLoading)
        assertFalse(c.state.value.isJumpMode) // 退回实时源
        assertTrue(toasts.isEmpty()) // 取消不算失败（RN cancelJump 无 toast）
    }

    @Test
    fun `stale run writes are dropped after a newer jump`() {
        var releaseFirst: (() -> Unit)? = null
        resolveImpl = { id ->
            if (id == "m1") {
                suspendCancellableCoroutine { cont ->
                    releaseFirst = { cont.resumeWith(Result.success(JumpMessageTarget("m1", "r1"))) }
                }
            } else {
                JumpMessageTarget(id, "r1")
            }
        }
        fetchImpl = { id, _ ->
            SurroundingRaw(listOf(apiMessage(id, "2026-01-01T00:00:00Z")), false, false)
        }
        val c = controller()
        c.jumpTo("r1", "m1")
        advance(100)
        c.jumpTo("r1", "m2") // 第二跳取代第一跳（runId 前进 + 旧 job 取消）
        releaseFirst?.invoke()
        pump()
        // 迟到代不写状态：高亮来自第二跳（m2），或第二跳滚动成功后的目标
        assertEquals("m2", c.state.value.highlightedMessageId)
    }

    @Test
    fun `empty surrounding result toasts not-found and exits`() {
        resolveImpl = { JumpMessageTarget("m9", "r1") }
        fetchImpl = { _, _ -> SurroundingRaw(emptyList(), false, false) }
        val c = controller()
        c.jumpTo("r1", "m9")
        pump()
        assertEquals(listOf(MessageJumpController.TOAST_NOT_FOUND), toasts)
        assertFalse(c.state.value.isJumpMode)
    }

    @Test
    fun `scroll retries five times then toasts failed without exiting jump mode`() {
        resolveImpl = { JumpMessageTarget("m9", "r1") }
        fetchImpl = { _, _ ->
            SurroundingRaw(listOf(apiMessage("m9", "2026-01-01T00:00:00Z")), false, false)
        }
        scrollSucceeds = false
        val c = controller()
        c.jumpTo("r1", "m9")
        pump()
        // RN scrollAndHighlight :148-156：5 次尝试（50ms 间隔）
        assertEquals(5, scrollTargets.size)
        assertEquals(listOf(MessageJumpController.TOAST_FAILED), toasts)
        assertTrue(c.state.value.isJumpMode) // 滚动失败不退 jump 模式（RN :233-235）
        assertNull(c.state.value.highlightedMessageId)
    }

    // ── loadEarlierInJumpMode（跳转态整窗替换）──

    @Test
    fun `loadEarlierInJumpMode anchors oldest real message and updates moreBefore`() {
        resolveImpl = { JumpMessageTarget("m9", "r1") }
        var first = true
        fetchImpl = { _, _ ->
            if (first) {
                first = false
                SurroundingRaw(
                    listOf(
                        apiMessage("m8", "2026-01-02T00:00:00Z"),
                        apiMessage("m9", "2026-01-01T00:00:00Z"),
                    ),
                    moreBefore = true, moreAfter = false,
                )
            } else {
                SurroundingRaw(
                    listOf(
                        apiMessage("m9", "2026-01-01T00:00:00Z"),
                        apiMessage("m7", "2025-12-31T00:00:00Z"),
                    ),
                    moreBefore = false, moreAfter = false,
                )
            }
        }
        val c = controller()
        c.jumpTo("r1", "m9")
        pump()
        assertTrue(c.state.value.isJumpMode)
        // 窗口 [m8, m9, PREVIOUS_CHUNK(anchor=m9)]：最旧真实消息 = m9（chunk 被跳过）
        c.loadEarlierInJumpMode()
        pump()
        assertEquals("m9" to "r1", fetchCalls.last()) // 第二次拉取锚定最旧真实消息
        val s = c.state.value
        assertFalse(s.moreBefore) // 第二页无更多
        // RN :282 setJumpMessages(surrounding.messages) = 整窗替换（非合并！）：新页 [m9, m7]
        assertEquals(listOf("m9", "m7"), s.jumpMessages!!.filter { m -> !isLoadChunkType(m.t) }.map { it._id })
        assertFalse(s.isLoadingEarlierInJump)
    }

    @Test
    fun `loadEarlierInJumpMode is a no-op in realtime mode or without moreBefore`() {
        val c = controller()
        c.loadEarlierInJumpMode() // 非跳转态：静默
        pump()
        assertFalse(c.state.value.isLoadingEarlierInJump)
        assertTrue(fetchCalls.isEmpty())
    }

    @Test
    fun `loadEarlierInJumpMode failure toasts failed and keeps window`() {
        resolveImpl = { JumpMessageTarget("m9", "r1") }
        var first = true
        fetchImpl = { _, _ ->
            if (first) {
                first = false
                SurroundingRaw(listOf(apiMessage("m9", "2026-01-01T00:00:00Z")), true, false)
            } else {
                throw IllegalStateException("network down") // RN :284-287 catch → failed toast
            }
        }
        val c = controller()
        c.jumpTo("r1", "m9")
        pump()
        c.loadEarlierInJumpMode()
        pump()
        assertEquals(listOf(MessageJumpController.TOAST_FAILED), toasts)
        assertTrue(c.state.value.isJumpMode) // 窗口保留
        assertFalse(c.state.value.isLoadingEarlierInJump)
    }

    @Test
    fun `exitJumpMode returns to realtime source but keeps highlight`() {
        resolveImpl = { JumpMessageTarget("m9", "r1") }
        fetchImpl = { _, _ ->
            SurroundingRaw(listOf(apiMessage("m9", "2026-01-01T00:00:00Z")), false, false)
        }
        val c = controller()
        c.jumpTo("r1", "m9")
        pump()
        assertTrue(c.state.value.isJumpMode)
        c.exitJumpMode()
        assertFalse(c.state.value.isJumpMode) // 回实时源
        assertEquals("m9", c.state.value.highlightedMessageId) // RN exitJumpMode 不清高亮
        assertFalse(c.state.value.moreBefore)
    }

    // ── chunk 占位（纯函数）──

    @Test
    fun `chunk placeholders flank window by moreBefore and moreAfter`() {
        val raw = SurroundingRaw(
            apiMessages = listOf(
                apiMessage("a", "2026-01-02T00:00:00Z"),
                apiMessage("b", "2026-01-01T00:00:00Z"),
            ),
            moreBefore = true,
            moreAfter = true,
        )
        val rows = buildJumpMessages(raw, "r1")
        assertEquals(4, rows.size)
        assertEquals(LOAD_MORE_AFTER, rows.first().t) // NEXT_CHUNK 头部（最新侧，ts+1）
        assertEquals(LOAD_MORE_BEFORE, rows.last().t) // PREVIOUS_CHUNK 尾部（最旧侧，ts-1）
        // 占位 id 规则：load-more-{before|after}-{anchorId}（RN generateLoadMoreChunkId）
        assertEquals("load-more-after-a", rows.first()._id)
        assertEquals("load-more-before-b", rows.last()._id)
        // 占位 ts 保持窗口有序
        assertTrue(rows[0].ts > rows[1].ts)
        assertTrue(rows[3].ts < rows[2].ts)
    }

    @Test
    fun `buildJumpMessages passes rid fallback and keeps empty window empty`() {
        assertEquals(emptyList<MessageEntity>(), buildJumpMessages(SurroundingRaw(emptyList(), true, true), "r1"))
        val noRid = buildJsonObject {
            put("_id", "x1")
            put("ts", "2026-01-01T00:00:00Z")
            put("msg", "m")
        }
        val rows = buildJumpMessages(SurroundingRaw(listOf(noRid), false, false), "r1")
        assertEquals("r1", rows.single().rid) // data 无 rid → fallback rid（RN mapRoomSearchApiMessageToIMessage）
    }

    @Test
    fun `oldestRealMessage skips chunk placeholders`() {
        val rows = listOf(
            msg("newest", 30),
            msg("chunk-after", 29, t = LOAD_MORE_AFTER),
            msg("mid", 20),
            msg("old", 10),
            msg("chunk-before", 9, t = LOAD_MORE_BEFORE),
        )
        assertEquals("old", oldestRealMessage(rows)?._id)
    }

    @Test
    fun `resolveVisibleJumpMessageId maps hidden rollback member to group head`() {
        val head = msg("h1", 30, t = "rollback-message")
        val hidden = msg("h2", 20, t = "rollback-message")
        // 同 rollbacker（u1）连续两条成组：h1 组首、h2 hidden
        assertEquals("h1", resolveVisibleJumpMessageId("h2", listOf(head, hidden)))
        assertEquals("h1", resolveVisibleJumpMessageId("h1", listOf(head, hidden)))
        assertNull(resolveVisibleJumpMessageId("missing", listOf(head, hidden)))
    }

    // ── 防死循环：控制器无路由订阅，jumpTo 只能外部显式调起 ──

    @Test
    fun `controller never self-triggers resolve from state writes`() {
        resolveImpl = { JumpMessageTarget("m1", "r1") }
        currentMessages = listOf(msg("m1", 10))
        var resolveCalls = 0
        val c = MessageJumpController(
            scope = scope,
            rid = "r1",
            roomType = "c",
            resolve = { resolveCalls++; resolveImpl(it) },
            fetchSurrounding = { id, rid -> fetchImpl(id, rid) },
            currentMessages = { currentMessages },
        ).also { ctrl ->
            ctrl.onToast = { toasts += it }
            ctrl.scrollToMessage = { true }
        }
        c.jumpTo("r1", "m1")
        pump()
        // 窗口/高亮写入后无再次 resolve（状态写若自触发 jumpTo 会看到 2+ 次调用）
        assertEquals(1, resolveCalls)
    }
}
