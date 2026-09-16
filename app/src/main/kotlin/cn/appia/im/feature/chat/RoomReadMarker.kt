package cn.appia.im.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 房间内已读标记（逐行为移植 appiaMobile/src/hooks/useRoomReadMessages.ts:21-48）。
 * 挂点 API：RoomScreen 装配（onEnter/onLeave 挂 DisposableEffect(rid)、onMessagePersisted 接
 * RoomStreamManager.incomingMessages 收集）与 subscribeRoom 一起归 T11——无消息流时本类只剩
 * 进房即读半程，两处一起接线才是完整特性。
 *
 * - [onEnter]：进房即 `readMessages(rid, now, updateLastOpen=true)`（RN :23-27，另写 lastOpen=now）。
 * - [onMessagePersisted]：新消息落库信号 → 记**到达时刻**并重挂 1000ms 防抖（RN :30-39
 *   WINDOW_TIME）→ 静默期满标读一次（updateLastOpen=false，RN :36）。时刻语义经 RN 源核实：
 *   roomStreams.ts:88 `cb(new Date())` 是 **persist 完成时刻**，不是消息自身 ts 字段——故此处
 *   收到信号即取 nowMs()，与 RN「cb 收到 persist 时的 new Date()」一致。
 * - [onLeave]：清防抖 timer（RN :41-45 clearTimeout）；incomingMessages 收集协程由 T11 装配的
 *   onDispose 一并取消（本类只持 timer，不持流订阅）。
 *
 * markRead 复用 ChatRowActions.markRoomRead（REST + ReadStateWriter 双表写单点，勿另起第二套写）。
 * 失败吞掉：RN `void readMessages` 未处理 rejection 同义，不打断停留期后续标读。
 * 线程契约：三挂点均从 UI 线程（Compose 生命周期）调用。
 */
class RoomReadMarker(
    private val markRead: suspend (rid: String, now: Long, updateLastOpen: Boolean) -> Unit,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** 当前驻留房间（RN 回调按 rid 注册于 Map 的等价守卫：异房落库事件不触发防抖）。 */
    private var activeRid: String? = null

    private var debounceJob: Job? = null

    /** 最后一条新消息的到达时刻（RN latestLastOpenRef，防抖期满时作为标读 ts）。 */
    private var latestArrivalMs = 0L

    fun onEnter(rid: String) {
        cancelDebounce()
        activeRid = rid
        val now = nowMs()
        latestArrivalMs = now // RN :24 latestLastOpenRef.current = now
        scope.launch { runCatching { markRead(rid, now, true) } }
    }

    fun onMessagePersisted(rid: String) {
        if (rid != activeRid) return
        latestArrivalMs = nowMs() // RN roomStreams.ts:88 cb(new Date())
        debounceJob?.cancel() // RN :32-34 每条新消息重置 timer
        debounceJob = scope.launch {
            delay(READ_DEBOUNCE_MS) // RN :38 WINDOW_TIME
            runCatching { markRead(rid, latestArrivalMs, false) }
        }
    }

    fun onLeave() {
        cancelDebounce()
        activeRid = null
    }

    private fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    companion object {
        /** RN useRoomReadMessages.ts:38 WINDOW_TIME。 */
        const val READ_DEBOUNCE_MS = 1000L
    }
}
