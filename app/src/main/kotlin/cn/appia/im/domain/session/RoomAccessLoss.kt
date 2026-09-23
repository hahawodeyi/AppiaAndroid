package cn.appia.im.domain.session

import cn.appia.im.feature.roominfo.clearPendingSelfLeaveForRid
import cn.appia.im.feature.roominfo.isPendingSelfLeave
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 房间访问丢失（RN lib/chat/roomAccessLoss.ts 全文件对照，M4-T10）：
 * - **三态检测**（RN inferRoomAccessLossReason :44-55）：markPendingSelfLeave 标记优先（self）→
 *   30s TTL hint 次之（stream-room-messages 的 `ul`→left / `ru`(msg==我)→kicked）→ unknown。
 *   self-leave 标记复用 M4-T3 [cn.appia.im.feature.roominfo.PendingSelfLeave]（RoomInfo 退出按钮
 *   mark → leave 失败 clear，本处只做消费侧读+清）。
 * - **hint 记录**（RN recordRoomAccessHintFromRawMessage :148-168）：RoomStreamManager 的
 *   stream-room-messages 回调逐帧喂 [recordRoomAccessHintFromRawMessage]（仅 ul/ru）。
 * - **DM 过滤**（RN notifyRoomAccessLost :137-145）：payload `t` == 'd' 不通知（DM 被删是常态——
 *   隐藏会话清理，无访问丢失语义）。
 * - **UI 消费**：[RoomAccessLostBus]（SessionExpiredBus 同款进程级 SharedFlow）——
 *   NotifyUserPersistence removed 半程发射，MainActivity 收集后做栈清理导航 + 提示
 *   （RN ref.navigate('MineDrawer') + Alert/Toast 的 Android 等价，见 AppiaNavHost 装配）。
 *
 * 线程契约：标记/记录在 DDP IO 线程同步调用（纯内存 Set/Map，ConcurrentHashMap 承载）；
 * Bus tryEmit 免阻塞。
 */
object RoomAccessLoss {

    /** RN HINT_TTL_MS :10。 */
    private const val HINT_TTL_MS = 30_000L

    enum class Reason { SELF, LEFT, KICKED, UNKNOWN }

    /** RN hints :12（rid → {ul|ru, at}）。@Volatile 时钟缝：TTL 测试注入固定时间。 */
    private val hints = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>() // value: (isUl, at)

    /** 测试缝：TTL 判定用时钟（默认墙钟）。 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** RN recordRoomAccessHint :40-42。 */
    private fun recordHint(rid: String, isUl: Boolean) {
        hints[rid] = isUl to clock()
    }

    /** RN clearRoomAccessHints :36-38（登出清缓存挂点，AuthRepository.logout 调用）。 */
    fun clearHints() = hints.clear()

    /**
     * RN inferRoomAccessLossReason :44-55：self 标记消费即删；hint 命中且未过期才用（同样消费即删）。
     */
    internal fun inferReason(rid: String): Reason {
        if (isPendingSelfLeave(rid)) {
            clearPendingSelfLeaveForRid(rid)
            return Reason.SELF
        }
        val hint = hints.remove(rid)
        if (hint != null && clock() - hint.second <= HINT_TTL_MS) {
            return if (hint.first) Reason.LEFT else Reason.KICKED
        }
        return Reason.UNKNOWN
    }

    /**
     * RN recordRoomAccessHintFromRawMessage :148-168：stream-room-messages 原始消息 → hint。
     * 仅 `t=ul`（有人退房）与 `t=ru && msg==当前用户名`（我被移出）两类；无用户名整体跳过。
     */
    fun recordRoomAccessHintFromRawMessage(raw: JsonElement, currentUsername: String?) {
        if (currentUsername.isNullOrEmpty()) return
        val msg = raw as? JsonObject ?: return
        val rid = (msg["rid"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return
        val type = (msg["t"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return
        when (type) {
            "ul" -> recordHint(rid, isUl = true)
            "ru" ->
                if ((msg["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == currentUsername) {
                    recordHint(rid, isUl = false)
                }
        }
    }

    /**
     * RN notifyRoomAccessLost :137-145（consumed by NotifyUserPersistence removed 半程）：
     * DM 不通知；非 DM 推断三态发 Bus 事件（导航与提示归 MainActivity 收集侧）。
     */
    fun notifyRoomAccessLost(rid: String, subscriptionT: String?) {
        if (subscriptionT == "d") return // RN isDirectSubscriptionType（DIRECT='d' 双写同值）
        val reason = inferReason(rid)
        RoomAccessLostBus.emit(RoomAccessLostEvent(rid, reason))
    }
}

/** 导航消费事件（rid + 三态）。 */
data class RoomAccessLostEvent(val rid: String, val reason: RoomAccessLoss.Reason)

/** SessionExpiredBus 同款：进程级一次性事件流（无 replay，tryEmit 免阻塞）。 */
object RoomAccessLostBus {
    private val _events = MutableSharedFlow<RoomAccessLostEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RoomAccessLostEvent> = _events

    fun emit(event: RoomAccessLostEvent) {
        _events.tryEmit(event)
    }
}
