package cn.appia.im.core.realtime

import cn.appia.im.core.realtime.ConnectionBannerMode.CONNECTING
import cn.appia.im.core.realtime.ConnectionBannerMode.HIDDEN
import cn.appia.im.core.realtime.ConnectionBannerMode.NETWORK_OFFLINE
import cn.appia.im.core.realtime.ConnectionBannerMode.SERVER_OFFLINE
import cn.appia.im.core.realtime.RealtimeTransportPhase.CONNECTED
import cn.appia.im.core.realtime.RealtimeTransportPhase.CONNECTING as PHASE_CONNECTING
import cn.appia.im.core.realtime.RealtimeTransportPhase.DISCONNECTED
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 连接横幅策略（RN connectionBanner.ts:9-31 + coldStartReconnectGrace.ts 逐行移植）：
 * networkOnline false/null/true × phase 三态 × 冷启动宽限 × connecting 防抖全分支。
 * 防抖与宽限都走时间参数（RN 组件 setTimeout / Date.now() 的可测化等价）。
 */
class ConnectionBannerPolicyTest {

    private fun resolve(
        networkOnline: Boolean?,
        phase: RealtimeTransportPhase,
        suppressConnecting: Boolean = false,
        connectingForMs: Long = Long.MAX_VALUE,
    ): ConnectionBannerMode =
        resolveConnectionBannerPresentation(networkOnline, phase, suppressConnecting, connectingForMs).mode

    // ---- 策略全分支 ----

    @Test
    fun `network offline wins immediately over every phase like RN 18-20`() {
        assertEquals(NETWORK_OFFLINE, resolve(networkOnline = false, phase = CONNECTED))
        assertEquals(NETWORK_OFFLINE, resolve(networkOnline = false, phase = PHASE_CONNECTING, connectingForMs = 10_000))
        assertEquals(NETWORK_OFFLINE, resolve(networkOnline = false, phase = DISCONNECTED))
    }

    @Test
    fun `unknown network null falls through to phase like RN 21-30`() {
        assertEquals(SERVER_OFFLINE, resolve(networkOnline = null, phase = DISCONNECTED))
        assertEquals(HIDDEN, resolve(networkOnline = null, phase = CONNECTED))
    }

    @Test
    fun `connected phase hides banner`() {
        assertEquals(HIDDEN, resolve(networkOnline = true, phase = CONNECTED))
    }

    @Test
    fun `connecting shows only after 2s debounce elapsed like RN index tsx 30`() {
        assertEquals(HIDDEN, resolve(networkOnline = true, phase = PHASE_CONNECTING, connectingForMs = 0))
        assertEquals(HIDDEN, resolve(networkOnline = true, phase = PHASE_CONNECTING, connectingForMs = CONNECTING_BANNER_DEBOUNCE_MS - 1))
        assertEquals(CONNECTING, resolve(networkOnline = true, phase = PHASE_CONNECTING, connectingForMs = CONNECTING_BANNER_DEBOUNCE_MS))
    }

    @Test
    fun `cold start grace suppresses connecting regardless of elapsed`() {
        assertEquals(
            HIDDEN,
            resolve(networkOnline = true, phase = PHASE_CONNECTING, suppressConnecting = true, connectingForMs = 10_000),
        )
    }

    @Test
    fun `disconnected means server offline with retry like RN 30`() {
        assertEquals(SERVER_OFFLINE, resolve(networkOnline = true, phase = DISCONNECTED))
    }

    // ---- 冷启动宽限（RN coldStartReconnectGrace.ts：4s 窗口严格大于才过期，过期即永久消费） ----

    private fun grace(clock: AtomicLong): ColdStartReconnectGrace {
        ColdStartReconnectGrace.resetForTests()
        ColdStartReconnectGrace.now = { clock.get() }
        return ColdStartReconnectGrace
    }

    @Test
    fun `grace suppresses within 4s only with local rooms`() {
        val clock = AtomicLong(1_000_000)
        val g = grace(clock)
        g.markColdStartReconnectBegin()

        clock.addAndGet(ColdStartReconnectGrace.COLD_START_BANNER_GRACE_MS) // 窗口末沿（=4000 不算过期）
        assertTrue(g.shouldSuppressColdStartConnectingBanner(localHasRooms = true))
        assertFalse(g.shouldSuppressColdStartConnectingBanner(localHasRooms = false)) // RN :12 无本地房间不抑制
    }

    @Test
    fun `grace expires past 4s and stays consumed for the rest of the process`() {
        val clock = AtomicLong(1_000_000)
        val g = grace(clock)
        g.markColdStartReconnectBegin()

        clock.addAndGet(ColdStartReconnectGrace.COLD_START_BANNER_GRACE_MS + 1) // RN :13 严格大于才消费
        assertFalse(g.shouldSuppressColdStartConnectingBanner(localHasRooms = true))
        clock.addAndGet(-10_000) // 回到窗口内也已被消费（RN :12 consumed 短路）
        assertFalse(g.shouldSuppressColdStartConnectingBanner(localHasRooms = true))
    }

    @Test
    fun `mark begin is once per process and without mark nothing suppresses`() {
        val clock = AtomicLong(500_000)
        val g = grace(clock)
        assertFalse(g.shouldSuppressColdStartConnectingBanner(localHasRooms = true)) // 未 mark：宽限不存在

        g.markColdStartReconnectBegin()
        clock.addAndGet(3_000)
        g.markColdStartReconnectBegin() // RN :7 二次 mark 不重置起点
        clock.addAndGet(1_500) // 距首次 mark 4.5s：已过期
        assertFalse(g.shouldSuppressColdStartConnectingBanner(localHasRooms = true))
    }
}
