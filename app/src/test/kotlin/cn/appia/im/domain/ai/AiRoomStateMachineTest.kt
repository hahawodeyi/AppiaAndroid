package cn.appia.im.domain.ai

import cn.appia.im.core.ai.AiTurnInput
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.network.sse.AiStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AI 槽位机制 + 串行驱动测试（M7-T7 / RN stores/aiStore.ts + lib/ai/runAiTurn.ts +
 * AgentLoadingMessage 消费核心 runAgentLoadingTurn + RoomMessageList 槽位派生）。
 * 坑 5（错误路径必 finalize）/坑 6（先 setProcessing 再 await；同 id 不注入）均为钉死用例。
 */
class AiRoomStateMachineTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(dispatcher)

    private fun machine(
        waitTimeoutMs: Long = AiRoomStateMachine.DEFAULT_WAIT_TIMEOUT_MS,
        finalizeFallbackMs: Long = AiRoomStateMachine.FINALIZE_FALLBACK_MS,
        genMessageId: () -> String = { "mid-${(0..9999).random()}" },
    ) = AiRoomStateMachine(scope, waitTimeoutMs = waitTimeoutMs, finalizeFallbackMs = finalizeFallbackMs, genMessageId = genMessageId)

    private fun pump() = scheduler.runCurrent()
    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    // ── aiStore 状态面 ──

    @Test
    fun `setProcessing stores all per-rid fields`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "mid-1", prompt = "hi", relatedUserMessageId = "u1", botReplyIndex = 0, inAgentRoom = true, botDisplayName = "Assistant")
        val room = m.room("r1")!!
        assertTrue(room.isProcessing)
        assertEquals("agent.bot", room.currentBotUsername)
        assertEquals("mid-1", room.currentMessageId)
        assertEquals("hi", room.prompt)
        assertEquals("u1", room.relatedUserMessageId)
        assertEquals(0, room.botReplyIndex)
        assertTrue(room.inAgentRoom)
        assertEquals("Assistant", room.botDisplayName)
        assertFalse(room.isStopping)
    }

    @Test
    fun `clear removes room entry`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "mid-1")
        m.clear("r1")
        assertNull(m.room("r1"))
    }

    @Test
    fun `setStopping on absent room creates EMPTY plus isStopping`() {
        // RN aiStore.setStopping：`state.rooms[rid] ?? EMPTY` 原样转录
        val m = machine()
        m.setStopping("r1")
        val room = m.room("r1")!!
        assertTrue(room.isStopping)
        assertFalse(room.isProcessing)
        assertNull(room.currentMessageId)
    }

    @Test
    fun `setError keeps processing and stores error`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "mid-1")
        m.setError("r1", "network")
        val room = m.room("r1")!!
        assertEquals("network", room.error)
        assertTrue(room.isProcessing)
    }

    // ── 串行驱动（坑 6）──

    @Test
    fun `runAiTurn is serial - bot1 waits for bot0 clear`() {
        val m = machine(genMessageId = { "mid-serial" })
        scope.launch { m.runAiTurn("r1", listOf("b0.bot", "b1.bot"), "hi", "u1") }
        pump()
        // 第 0 bot 已 setProcessing 且 awaitClear 挂起（房未清）
        assertEquals("b0.bot", m.room("r1")?.currentBotUsername)
        assertEquals(0, m.room("r1")?.botReplyIndex)
        // clear 后串行推进到 bot1
        m.clear("r1")
        pump()
        assertEquals("b1.bot", m.room("r1")?.currentBotUsername)
        assertEquals(1, m.room("r1")?.botReplyIndex)
    }

    @Test
    fun `awaitClear suspends while processing - setProcessing-before-await invariant`() {
        // 坑 6 负向钉死：房处于 processing 时 await 不 resolve；若驱动顺序反了
        // （先 await 后 setProcessing），这里第 0 轮即会立即放行
        val m = machine()
        m.setProcessing("r1", "b0.bot", "mid-0")
        var released = false
        scope.launch { m.awaitClear("r1"); released = true }
        scheduler.runCurrent()
        assertFalse(released)
        m.clear("r1")
        scheduler.runCurrent()
        assertTrue(released)
    }

    @Test
    fun `awaitClear resolves immediately when room already cleared`() {
        val m = machine()
        var released = false
        scope.launch { m.awaitClear("r1"); released = true }
        scheduler.runCurrent()
        assertTrue(released)
    }

    @Test
    fun `wait timeout forces advance to next bot`() {
        val m = machine(waitTimeoutMs = 5_000)
        scope.launch { m.runAiTurn("r1", listOf("b0.bot", "b1.bot"), "hi", "u1") }
        pump()
        assertEquals("b0.bot", m.room("r1")?.currentBotUsername)
        advance(4_999)
        assertEquals("b0.bot", m.room("r1")?.currentBotUsername) // 仍在等
        advance(1)
        assertEquals("b1.bot", m.room("r1")?.currentBotUsername) // 超时兜底推进
    }

    @Test
    fun `default wait timeout is 60s`() {
        val m = machine(waitTimeoutMs = AiRoomStateMachine.DEFAULT_WAIT_TIMEOUT_MS)
        scope.launch { m.runAiTurn("r1", listOf("b0.bot", "b1.bot"), "hi", "u1") }
        pump()
        advance(59_999)
        assertEquals("b0.bot", m.room("r1")?.currentBotUsername)
        advance(1)
        assertEquals("b1.bot", m.room("r1")?.currentBotUsername)
    }

    @Test
    fun `setStopping does not release serial await`() {
        // 停止只置 isStopping；槽位要等 finalize 的 persist/clear 才放行（RN 同）
        val m = machine()
        m.setProcessing("r1", "b0.bot", "mid-0")
        scope.launch { m.runAiTurn("r1", listOf("b0.bot", "b1.bot"), "hi", "u1") }
        pump()
        m.setStopping("r1")
        pump()
        assertEquals("b0.bot", m.room("r1")?.currentBotUsername)
        assertTrue(m.room("r1")!!.isStopping)
        m.clear("r1")
        pump()
        assertEquals("b1.bot", m.room("r1")?.currentBotUsername)
    }

    @Test
    fun `error path clear releases serial driver`() {
        // 坑 5 驱动侧：错误 finalize = clear → 下一 bot 正常推进
        val m = machine()
        scope.launch { m.runAiTurn("r1", listOf("b0.bot", "b1.bot"), "hi", "u1") }
        pump()
        m.setError("r1", "AI error")
        m.clear("r1")
        pump()
        assertEquals("b1.bot", m.room("r1")?.currentBotUsername)
    }

    @Test
    fun `each bot gets a fresh slot message id`() {
        val ids = mutableListOf<String>()
        var n = 0
        val m = machine(genMessageId = { "mid-${n++}" })
        scope.launch { m.runAiTurn("r1", listOf("b0.bot", "b1.bot"), "hi", "u1") }
        pump()
        ids += m.room("r1")!!.currentMessageId!!
        m.clear("r1")
        pump()
        ids += m.room("r1")!!.currentMessageId!!
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `runAiTurn with empty bots is a no-op`() {
        val m = machine()
        scope.launch { m.runAiTurn("r1", emptyList(), "hi", "u1") }
        pump()
        assertNull(m.room("r1"))
    }

    // ── 15s 兜底强清（RN persistSelf setTimeout）──

    @Test
    fun `fallback clear fires for same slot after finalizeFallbackMs`() {
        val m = machine(finalizeFallbackMs = 10_000)
        m.setProcessing("r1", "b0.bot", "mid-0")
        m.scheduleFallbackClear("r1", "mid-0")
        pump()
        advance(9_999)
        assertTrue(m.room("r1")!!.isProcessing)
        advance(1)
        assertNull(m.room("r1"))
    }

    @Test
    fun `fallback clear ignores a different slot`() {
        // 串行已推进到下一 bot（新 mid）：不得误清新槽位
        val m = machine(finalizeFallbackMs = 10_000)
        m.setProcessing("r1", "b0.bot", "mid-0")
        m.scheduleFallbackClear("r1", "mid-0")
        m.setProcessing("r1", "b1.bot", "mid-1")
        advance(10_000)
        assertEquals("mid-1", m.room("r1")?.currentMessageId)
    }

    @Test
    fun `fallback clear is a no-op after manual clear`() {
        val m = machine(finalizeFallbackMs = 10_000)
        m.setProcessing("r1", "b0.bot", "mid-0")
        m.scheduleFallbackClear("r1", "mid-0")
        m.clear("r1") // 真实消息到达（RoomMessageList effect）
        advance(10_000)
        assertNull(m.room("r1"))
    }

    // ── 发送后触发装配（RN maybeTriggerAiAfterSend）──

    private fun triggerInput(msg: String) = AiTurnInput(
        rid = "r1", fromAgent = false, isStaffService = false, staffAssignType = null, msg = msg,
    )

    @Test
    fun `maybeTriggerAiAfterSend hits mention and drives machine`() {
        val m = machine()
        scope.launch {
            maybeTriggerAiAfterSend(m, triggerInput("@agent.bot hello there"), listOf("agent.bot"), "", "u1")
        }
        pump()
        val room = m.room("r1")!!
        assertEquals("agent.bot", room.currentBotUsername)
        // 坑 7：prompt 剔除 @bot 自身
        assertEquals("hello there", room.prompt)
        assertEquals("u1", room.relatedUserMessageId)
        assertFalse(room.inAgentRoom)
    }

    @Test
    fun `maybeTriggerAiAfterSend misses without mention and is a no-op`() {
        val m = machine()
        scope.launch { maybeTriggerAiAfterSend(m, triggerInput("plain message"), listOf("agent.bot"), "", "u1") }
        pump()
        assertNull(m.room("r1"))
    }
}

/**
 * runAgentLoadingTurn 测试（RN AgentLoadingMessage 流消费核心：坑 5 错误必 finalize、
 * 停止持久化、finalize 恰一次）。
 */
class RunAgentLoadingTurnTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(dispatcher)

    private val texts = mutableListOf<String>()
    private val persisted = mutableListOf<String>()
    private var cleared = 0
    private val errors = mutableListOf<String>()

    private fun machine() = AiRoomStateMachine(scope)

    private fun flowOf(vararg events: AiStreamEvent): Flow<AiStreamEvent> = flow {
        for (e in events) emit(e)
    }

    private suspend fun run(
        m: AiRoomStateMachine,
        rid: String = "r1",
        stream: Flow<AiStreamEvent>,
    ) = runAgentLoadingTurn(
        stream = stream,
        machine = m,
        rid = rid,
        onText = { texts += it },
        onPersist = { persisted += it },
        onClear = { cleared += 1 },
        onError = { errors += it },
    )

    private fun pump() = scheduler.runCurrent()

    @Test
    fun `text accumulates incrementally`() {
        scope.launch { run(machine(), stream = flowOf(AiStreamEvent.Text("he"), AiStreamEvent.Text("llo"), AiStreamEvent.Finished)) }
        pump()
        assertEquals(listOf("he", "hello"), texts)
        assertEquals("hello", persisted.single())
        assertEquals(0, cleared)
    }

    @Test
    fun `finished with empty text clears`() {
        scope.launch { run(machine(), stream = flowOf(AiStreamEvent.Finished)) }
        pump()
        assertEquals(1, cleared)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `finished trims trailing whitespace before persist`() {
        scope.launch { run(machine(), stream = flowOf(AiStreamEvent.Text("hi  \n"), AiStreamEvent.Finished)) }
        pump()
        assertEquals("hi", persisted.single())
    }

    @Test
    fun `error finalizes with clear - pit 5`() {
        // 坑 5：错误路径必 finalize（空文本 → clear），否则 isProcessing 恒真卡死串行驱动
        val m = machine()
        scope.launch { run(m, stream = flowOf(AiStreamEvent.Text("par"), AiStreamEvent.Error("network"))) }
        pump()
        assertEquals("network", errors.single())
        assertEquals("network", m.room("r1")?.error)
        assertEquals(1, cleared)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `finalize happens exactly once on error then finished`() {
        scope.launch { run(machine(), stream = flowOf(AiStreamEvent.Error("boom"), AiStreamEvent.Finished)) }
        pump()
        assertEquals(1, cleared)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `stop with text persists - stopped-with-text still saved`() {
        val m = machine()
        // 流永不终止：停止分支负责收尾（RN AbortController 中止同型）
        scope.launch { run(m, stream = flow { emit(AiStreamEvent.Text("partial")); awaitCancellation() }) }
        scheduler.runCurrent()
        assertEquals("partial", texts.single())
        m.setStopping("r1")
        pump()
        assertEquals("partial", persisted.single())
        assertEquals(0, cleared)
    }

    @Test
    fun `stop with empty text clears`() {
        val m = machine()
        scope.launch { run(m, stream = flow { awaitCancellation() }) }
        scheduler.runCurrent()
        m.setStopping("r1")
        pump()
        assertEquals(1, cleared)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `stop discards late text after stopping`() {
        // RN :173/:185 stoppingRef 守卫：用户中止后不再吞增量
        val m = machine()
        val ch = kotlinx.coroutines.channels.Channel<AiStreamEvent>(Channel.UNLIMITED)
        scope.launch { run(m, stream = ch.receiveAsFlow()) }
        scheduler.runCurrent()
        ch.trySend(AiStreamEvent.Text("a"))
        scheduler.runCurrent()
        m.setStopping("r1")
        scheduler.runCurrent()
        ch.trySend(AiStreamEvent.Text("b"))
        pump()
        assertEquals("a", persisted.single())
    }

    @Test
    fun `stream error while stopping is guarded - stop path finalizes`() {
        // RN :185 `if (stoppingRef.current) return`：中止引发的流错误不重复 finalize
        val m = machine()
        val ch = kotlinx.coroutines.channels.Channel<AiStreamEvent>(Channel.UNLIMITED)
        scope.launch { run(m, stream = ch.receiveAsFlow()) }
        scheduler.runCurrent()
        m.setStopping("r1")
        scheduler.runCurrent()
        ch.trySend(AiStreamEvent.Error("network"))
        pump()
        assertEquals(1, cleared) // 空 → clear 恰一次
        assertTrue(errors.isEmpty())
    }
}

/**
 * 槽位注入派生测试（RN RoomMessageList/index.tsx:133-153）：注入位次、防闪烁不变量、
 * msgData/u JSON 形状。
 */
class AgentSlotInjectionTest {

    private var now = 1_000.0

    private fun machine() = AiRoomStateMachine(CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler())))

    private fun msg(id: String, ts: Long) = MessageEntity(
        _id = id, rid = "r1", ts = ts.toDouble(), u = """{"_id":"u1","username":"u1"}""",
        alias = "", parse_urls = "[]", _updated_at = ts.toDouble(), msg = "m-$id",
    )

    private fun derive(messages: List<MessageEntity>, room: AiRoomState?) =
        deriveMessagesWithAgentSlot(messages, room, nowMs = { now++ })

    @Test
    fun `no processing - list unchanged`() {
        val messages = listOf(msg("a", 10), msg("b", 5))
        assertEquals(messages, derive(messages, null))
        assertEquals(messages, derive(messages, AiRoomState(isProcessing = false, currentMessageId = "slot")))
    }

    @Test
    fun `processing injects slot at newest end with prompt and msgType`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "slot-1", prompt = "hello there")
        val messages = listOf(msg("a", 10), msg("b", 5))
        val derived = derive(messages, m.room("r1"))
        assertEquals(3, derived.size)
        val slot = derived.first()
        assertEquals("slot-1", slot._id)
        assertEquals("r1", slot.rid)
        assertEquals(AGENT_LOADING_MSG_TYPE, slot.msg_type)
        assertEquals("hello there", slot.msg)
        assertTrue(slot.ts > messages.first().ts)
    }

    @Test
    fun `same id already in list - no injection - anti-flicker invariant`() {
        // 坑 6 防闪烁不变量（RN :135）：同 id 真实消息已进窗口 → 槽位让位，不注入
        val m = machine()
        m.setProcessing("r1", "agent.bot", "real-1", prompt = "hi")
        val messages = listOf(msg("real-1", 10), msg("b", 5))
        assertEquals(messages, derive(messages, m.room("r1")))
    }

    @Test
    fun `null currentMessageId - no injection`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "slot-1")
        val room = m.room("r1")!!.copy(currentMessageId = null)
        val messages = listOf(msg("a", 10))
        assertEquals(messages, derive(messages, room))
    }

    @Test
    fun `msgData carries relatedUserMessageId and botReplyIndex with defaults`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "slot-1", relatedUserMessageId = "u1", botReplyIndex = 2)
        val slot = derive(listOf(msg("a", 10)), m.room("r1")).first()
        val data = Json.parseToJsonElement(slot.msg_data!!).jsonObject
        assertEquals("u1", data["relatedUserMessageId"]!!.toString().trim('"'))
        assertEquals(2, data["botReplyIndex"]!!.toString().toInt())
        // 缺省：relatedUserMessageId → ""、botReplyIndex → 0（RN `?? ''` / `?? 0`）
        m.setProcessing("r1", "agent.bot", "slot-2")
        val slot2 = derive(listOf(msg("a", 10)), m.room("r1")).first()
        val data2 = Json.parseToJsonElement(slot2.msg_data!!).jsonObject
        assertEquals("", data2["relatedUserMessageId"]!!.toString().trim('"'))
        assertEquals(0, data2["botReplyIndex"]!!.toString().toInt())
    }

    @Test
    fun `u json uses bot username with botDisplayName fallback`() {
        val m = machine()
        m.setProcessing("r1", "agent.bot", "slot-1", botDisplayName = "Assistant")
        val u = Json.parseToJsonElement(derive(listOf(msg("a", 10)), m.room("r1")).first().u).jsonObject
        assertEquals("agent.bot", u["_id"]!!.toString().trim('"'))
        assertEquals("agent.bot", u["username"]!!.toString().trim('"'))
        assertEquals("Assistant", u["name"]!!.toString().trim('"'))
        // 缺省回退 bot username（RN `aiRoom.botDisplayName ?? bot`）
        m.setProcessing("r1", "agent.bot", "slot-2")
        val u2 = Json.parseToJsonElement(derive(listOf(msg("a", 10)), m.room("r1")).first().u).jsonObject
        assertEquals("agent.bot", u2["name"]!!.toString().trim('"'))
    }

    @Test
    fun `empty window - no injection (slot needs rid from window) - documented limitation`() {
        // 实际不可达：send 路径先落库行再 setProcessing，窗口必非空；钉死当前行为
        val m = machine()
        m.setProcessing("r1", "agent.bot", "slot-1")
        assertEquals(emptyList<MessageEntity>(), derive(emptyList(), m.room("r1")))
    }
}
