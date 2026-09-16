package cn.appia.im.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 房间内已读标记（对照 useRoomReadMessages.ts:21-48 + roomStreams.ts:85-89）：
 * 进房即读（updateLastOpen=true）、停留新消息 debounce 1000ms 标一次（取最新**到达时刻**，
 * RN cb(new Date()) —— persist 完成时刻而非消息 ts 字段）、离开清 timer、异房事件忽略、
 * markRead 失败不打断后续（RN void promise 吞 rejection 同义）。
 */
class RoomReadMarkerTest {

    private data class Call(val rid: String, val now: Long, val updateLastOpen: Boolean)

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val calls = mutableListOf<Call>()

    /** 手动时钟：只喂给 nowMs（到达时刻）；防抖等待走虚拟 scheduler，两者互不干扰。 */
    private var clock = 1_000L
    private var failOnEnter = false

    private val marker = RoomReadMarker(
        markRead = { rid, now, updateLastOpen ->
            if (failOnEnter && updateLastOpen) throw IllegalStateException("rest down")
            calls += Call(rid, now, updateLastOpen)
        },
        scope = scope,
        nowMs = { clock },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun advance(ms: Long) {
        dispatcher.scheduler.advanceTimeBy(ms)
        dispatcher.scheduler.runCurrent()
    }

    private fun runNow() = dispatcher.scheduler.runCurrent()

    @Test
    fun `onEnter marks read immediately with updateLastOpen true`() {
        marker.onEnter("r1")
        runNow()

        assertEquals(listOf(Call("r1", 1_000L, true)), calls)
    }

    @Test
    fun `onMessagePersisted debounces full 1000ms before marking read`() {
        marker.onEnter("r1")
        runNow()

        clock = 1_500L
        marker.onMessagePersisted("r1")
        advance(999)
        assertEquals(1, calls.size) // 静默期内未标读

        advance(1)
        assertEquals(listOf(Call("r1", 1_000L, true), Call("r1", 1_500L, false)), calls)
    }

    @Test
    fun `multiple persists inside window coalesce to one mark with latest arrival ts`() {
        marker.onEnter("r1")
        runNow()

        clock = 2_000L
        marker.onMessagePersisted("r1")
        clock = 2_500L
        marker.onMessagePersisted("r1")
        clock = 2_900L
        marker.onMessagePersisted("r1")
        advance(1_000)

        assertEquals(
            listOf(Call("r1", 1_000L, true), Call("r1", 2_900L, false)),
            calls, // 3 条消息只标一次，ts = 最后一条到达时刻
        )
    }

    @Test
    fun `onLeave cancels pending debounce`() {
        marker.onEnter("r1")
        runNow()

        clock = 1_500L
        marker.onMessagePersisted("r1")
        advance(999)
        marker.onLeave()
        advance(1_000)

        assertEquals(1, calls.size) // 只有进房那一次
    }

    @Test
    fun `re-enter after leave marks read again with updateLastOpen true`() {
        marker.onEnter("r1")
        runNow()
        marker.onLeave()

        clock = 5_000L
        marker.onEnter("r1")
        runNow()

        assertEquals(listOf(Call("r1", 1_000L, true), Call("r1", 5_000L, true)), calls)
    }

    @Test
    fun `persist for another rid is ignored`() {
        marker.onEnter("r1")
        runNow()

        marker.onMessagePersisted("r2")
        advance(2_000)

        assertEquals(1, calls.size)
    }

    @Test
    fun `markRead failure on enter does not break later debounced marks`() {
        failOnEnter = true
        marker.onEnter("r1")
        runNow()
        assertEquals(0, calls.size) // 进房标读失败被吞（RN void promise 同义）

        clock = 2_000L
        marker.onMessagePersisted("r1")
        advance(1_000)

        assertEquals(listOf(Call("r1", 2_000L, false)), calls)
    }
}
