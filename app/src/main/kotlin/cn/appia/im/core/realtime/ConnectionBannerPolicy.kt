package cn.appia.im.core.realtime

/**
 * 连接状态横幅策略，逐行移植 appiaMobile/src/lib/network/connectionBanner.ts:9-31 与
 * coldStartReconnectGrace.ts。phase 为 DDP 传输层三态（RN realtimeConnectionStore 的
 * RealtimeTransportPhase），由 RealtimeSessionManager 的传输回调驱动（M2 T5）。
 */

/** RN realtimeConnectionStore.ts:4（'connected' | 'connecting' | 'disconnected'）。 */
enum class RealtimeTransportPhase { CONNECTED, CONNECTING, DISCONNECTED }

/** RN connectionBanner.ts:3 四种横幅形态。 */
enum class ConnectionBannerMode { HIDDEN, NETWORK_OFFLINE, CONNECTING, SERVER_OFFLINE }

/** RN ConnectionBannerPresentation（当前仅 mode 一个字段）。 */
data class ConnectionBannerPresentation(val mode: ConnectionBannerMode)

/**
 * RN RoomListConnectionBanner/index.tsx:30：connecting 防抖 2s——回前台短暂重连不闪现横幅。
 * 离线两态（networkOffline/serverOffline）是明确故障，仍立即展示。
 */
const val CONNECTING_BANNER_DEBOUNCE_MS = 2_000L

/**
 * RN connectionBanner.ts:9-31 精确移植。两处防抖/宽限收进时间参数（RN 组件内 setTimeout 与
 * Date.now() 的可测化等价）：
 * - [connectingForMs]：当前 connecting 段已持续的毫秒数（< 防抖阈值不展示；默认 Long.MAX_VALUE =
 *   已超过防抖窗口，即 RN 纯函数不含防抖时的原语义）。
 * - [suppressConnecting]：冷启动宽限命中（[ColdStartReconnectGrace.shouldSuppressColdStartConnectingBanner]）。
 * 优先级与 RN 一致：网络断开（立即）> connected 隐藏 > connecting（宽限抑制/防抖）> serverOffline。
 */
fun resolveConnectionBannerPresentation(
    networkOnline: Boolean?,
    phase: RealtimeTransportPhase,
    suppressConnecting: Boolean = false,
    connectingForMs: Long = Long.MAX_VALUE,
): ConnectionBannerPresentation {
    if (networkOnline == false) {
        return ConnectionBannerPresentation(ConnectionBannerMode.NETWORK_OFFLINE)
    }
    if (phase == RealtimeTransportPhase.CONNECTED) {
        return ConnectionBannerPresentation(ConnectionBannerMode.HIDDEN)
    }
    if (phase == RealtimeTransportPhase.CONNECTING) {
        if (suppressConnecting) {
            return ConnectionBannerPresentation(ConnectionBannerMode.HIDDEN)
        }
        if (connectingForMs < CONNECTING_BANNER_DEBOUNCE_MS) {
            return ConnectionBannerPresentation(ConnectionBannerMode.HIDDEN)
        }
        return ConnectionBannerPresentation(ConnectionBannerMode.CONNECTING)
    }
    return ConnectionBannerPresentation(ConnectionBannerMode.SERVER_OFFLINE)
}

/**
 * 冷启动重连横幅宽限，逐行移植 coldStartReconnectGrace.ts：冷启动恢复会话后的 4s 内，
 * 本地有会话（房间可离线浏览）时 connecting 不展示。RN 为模块级可变状态，此处 object +
 * @Volatile 等价（进程内一次，markBegin 幂等）。
 */
object ColdStartReconnectGrace {
    const val COLD_START_BANNER_GRACE_MS = 4_000L

    @Volatile
    private var beginAt: Long? = null

    @Volatile
    private var consumed = false

    /** 时钟缝：RN 直读 Date.now()；测试注入静止时钟。 */
    @Volatile
    internal var now: () -> Long = System::currentTimeMillis

    /** RN coldStartReconnectGrace.ts:6-9：冷启动恢复会话时标记起点（进程内一次，重复 mark 不重置）。 */
    fun markColdStartReconnectBegin() {
        if (beginAt != null) return
        beginAt = now()
    }

    /** RN :11-18：无标记/无本地房间/已消费 → false；超 4s（严格大于）消费并返回 false；窗口内 true。 */
    fun shouldSuppressColdStartConnectingBanner(localHasRooms: Boolean): Boolean {
        val begin = beginAt
        if (consumed || begin == null || !localHasRooms) return false
        if (now() - begin > COLD_START_BANNER_GRACE_MS) {
            consumed = true
            return false
        }
        return true
    }

    /** 仅测试：复位模块状态（RN resetColdStartGraceForTests）。 */
    internal fun resetForTests() {
        beginAt = null
        consumed = false
    }
}
