package cn.appia.im.domain.session

import cn.appia.im.feature.roominfo.clearPendingSelfLeaveForRid
import cn.appia.im.feature.roominfo.markPendingSelfLeave
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 房间访问丢失三态检测（RN roomAccessLoss.test.ts 全语义移植，M4-T10）：
 * - inferReason：self 标记优先（消费即删）/ ul→LEFT / ru→KICKED / 无 hint→UNKNOWN
 * - recordRoomAccessHintFromRawMessage：ul 恒记 / ru 仅 msg==当前用户名 / 无用户名跳过 / 形态防御
 * - notifyRoomAccessLost：DM('d') 不发事件；非 DM 发事件带三态
 * - hint TTL 30s 过期 → UNKNOWN
 * - self 标记复用 M4-T3 PendingSelfLeave（RoomInfoActionsTest 已钉 mark/clear 语义，此处只测消费侧）
 */
class RoomAccessLossTest {

    @Before
    fun setUp() {
        RoomAccessLoss.clock = { System.currentTimeMillis() } // 复位测试缝
        RoomAccessLoss.clearHints()
        clearPendingSelfLeaveForRid(TEST_RID)
    }

    @After
    fun tearDown() {
        RoomAccessLoss.clock = { System.currentTimeMillis() }
        RoomAccessLoss.clearHints()
        clearPendingSelfLeaveForRid(TEST_RID)
    }

    private fun raw(json: String): JsonElement = Json.parseToJsonElement(json)

    // ---- inferReason 三态（RN test.ts describe('inferRoomAccessLossReason')）----

    @Test
    fun `returns SELF when rid is pending self-leave`() {
        markPendingSelfLeave(TEST_RID)
        assertEquals(RoomAccessLoss.Reason.SELF, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `self-leave marker is consumed on first inference`() {
        markPendingSelfLeave(TEST_RID)
        RoomAccessLoss.inferReason(TEST_RID)
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `returns LEFT after ul hint`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID","t":"ul"}"""), "bob")
        assertEquals(RoomAccessLoss.Reason.LEFT, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `returns KICKED after ru hint with my username`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(
            raw("""{"rid":"$TEST_RID","t":"ru","msg":"bob"}"""), "bob",
        )
        assertEquals(RoomAccessLoss.Reason.KICKED, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `returns UNKNOWN with no hints`() {
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `self marker takes precedence over hint`() {
        markPendingSelfLeave(TEST_RID)
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(
            raw("""{"rid":"$TEST_RID","t":"ru","msg":"bob"}"""), "bob",
        )
        assertEquals(RoomAccessLoss.Reason.SELF, RoomAccessLoss.inferReason(TEST_RID))
    }

    // ---- recordRoomAccessHintFromRawMessage（RN :148-168 逐分支）----

    @Test
    fun `ru hint for another user is not recorded`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(
            raw("""{"rid":"$TEST_RID","t":"ru","msg":"alice"}"""), "bob",
        )
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `no current username skips recording entirely`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID","t":"ul"}"""), null)
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `non ul-ru message types are not recorded`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(
            raw("""{"rid":"$TEST_RID","t":"message","msg":"bob"}"""), "bob",
        )
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID","t":"uj"}"""), "bob")
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `malformed raw frames are ignored`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("\"plain string\""), "bob")
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("{}"), "bob")
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"t":"ul"}"""), "bob") // 无 rid
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID"}"""), "bob") // 无 t
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `hints for other rids do not interfere`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"rid-other","t":"ul"}"""), "bob")
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    // ---- hint TTL（RN HINT_TTL_MS :10 = 30s）----

    @Test
    fun `stale hint past TTL falls back to UNKNOWN`() {
        var now = 1_000_000L
        RoomAccessLoss.clock = { now }
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID","t":"ul"}"""), "bob")
        now += 30_001 // TTL 边界外
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    @Test
    fun `hint at TTL boundary is still honored`() {
        var now = 1_000_000L
        RoomAccessLoss.clock = { now }
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID","t":"ul"}"""), "bob")
        now += 30_000 // `Date.now() - hint.at <= HINT_TTL_MS` 含等号
        assertEquals(RoomAccessLoss.Reason.LEFT, RoomAccessLoss.inferReason(TEST_RID))
    }

    // ---- notifyRoomAccessLost（RN :137-145 + test.ts describe('notifyRoomAccessLost')）----

    @Test
    fun `direct subscription t=d does not emit`() = runBlocking {
        val received = mutableListOf<RoomAccessLostEvent>()
        val collector = CoroutineScope(Dispatchers.IO).launch {
            RoomAccessLostBus.events.collect { received.add(it) }
        }
        kotlinx.coroutines.delay(50) // 订阅就绪（SharedFlow 无 buffer replay）
        RoomAccessLoss.notifyRoomAccessLost(TEST_RID, subscriptionT = "d")
        kotlinx.coroutines.delay(100) // 若有事件，200ms 缓冲窗内必达
        collector.cancel()
        assertEquals(emptyList<RoomAccessLostEvent>(), received)
    }

    @Test
    fun `non-direct subscription emits event with reason`() = runBlocking {
        val event = emitAndCollect { RoomAccessLoss.notifyRoomAccessLost(TEST_RID, subscriptionT = "c") }
        assertEquals(TEST_RID, event.rid)
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, event.reason) // 无 hint 无标记
    }

    @Test
    fun `null subscriptionT emits event`() = runBlocking {
        val event = emitAndCollect { RoomAccessLoss.notifyRoomAccessLost(TEST_RID, subscriptionT = null) }
        assertEquals(TEST_RID, event.rid)
    }

    @Test
    fun `kicked hint flows through notify as KICKED`() = runBlocking {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(
            raw("""{"rid":"$TEST_RID","t":"ru","msg":"bob"}"""), "bob",
        )
        val event = emitAndCollect { RoomAccessLoss.notifyRoomAccessLost(TEST_RID, subscriptionT = "c") }
        assertEquals(RoomAccessLoss.Reason.KICKED, event.reason)
    }

    @Test
    fun `clearHints drops all recorded hints`() {
        RoomAccessLoss.recordRoomAccessHintFromRawMessage(raw("""{"rid":"$TEST_RID","t":"ul"}"""), "bob")
        RoomAccessLoss.clearHints()
        assertEquals(RoomAccessLoss.Reason.UNKNOWN, RoomAccessLoss.inferReason(TEST_RID))
    }

    /** 先订阅再 emit，取首事件（SharedFlow 无 replay，订阅就绪窗 50ms + 到达窗 200ms）。 */
    private suspend fun emitAndCollect(emit: () -> Unit): RoomAccessLostEvent {
        var event: RoomAccessLostEvent? = null
        val scope = CoroutineScope(Dispatchers.IO)
        val collector = scope.launch {
            RoomAccessLostBus.events.collect { if (event == null) event = it }
        }
        kotlinx.coroutines.delay(50)
        emit()
        val deadline = System.currentTimeMillis() + 5_000
        while (event == null && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)
        collector.cancel()
        return event ?: throw AssertionError("expected RoomAccessLostBus event, none arrived")
    }

    private companion object {
        const val TEST_RID = "rid-test"
    }
}
