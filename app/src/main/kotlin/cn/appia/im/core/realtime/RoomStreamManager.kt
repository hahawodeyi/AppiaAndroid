package cn.appia.im.core.realtime

import android.util.Log
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.ddp.DdpException
import cn.appia.im.core.network.ddp.DdpSubscription
import cn.appia.im.core.network.ddp.Disposable
import cn.appia.im.core.network.ROOM_NOTIFY_ROOM
import cn.appia.im.core.network.ROOM_STREAM_MESSAGES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 房间流订阅与消息分发（逐行为移植 appiaMobile/src/services/realtime/roomStreams.ts）：
 * - [subscribeRoom]：三订阅 + 两条 onStreamData 监听；同一 rid 重复调用先退订（幂等，RN :59）
 * - `stream-room-messages` 回调：`args[0].rid == rid` 过滤 → [persistMessage] 落库 → [incomingMessages]
 *   发射 rid（RN :85-94 persist 后回调 onMessagePersisted；已读 debounce 消费归房间页任务）
 * - [unsubscribeRoom] / [unsubscribeAllRoomStreams]（RN :119-138）
 * - [resubscribeAllActiveRoomStreams]（RN :143-148）：挂在 RealtimeSessionManager 重连收尾
 *   （addReconnectTail，等价 RN resumeStreamsAfterSocketUp :162）
 *
 * 线程契约：回调在 DDP IO 线程被调（M1 KDoc）；持久化切换到注入的 [scope]，发射经
 * MutableSharedFlow——ViewModel collect 自然线程安全（T6↔M1 预检裁定）。
 */
class RoomStreamManager(
    private val sdk: RocketSdk,
    /** 消息落库缝（DI 接 MessageUpsert.persistFromUnknown；RN persistRocketChatMessageFromUnknown :85）。 */
    private val persistMessage: suspend (raw: JsonElement, rid: String) -> Unit,
    private val scope: CoroutineScope,
    /** RN :82-84 recordRoomAccessHintFromRawMessage 的缝（M4-T10；DI 接 RoomAccessLoss，
     *  core 不反向依赖 domain——persistMessage 同款注入裁定）。 */
    private val recordAccessHint: (raw: JsonElement, currentUsername: String?) -> Unit = { _, _ -> },
    /** RN :82 useAuthStore.getState().user?.username 现读——hint 记录的当前用户名（DI 传 store 现读）。 */
    private val currentUsernameProvider: () -> String? = { null },
) {

    private class ActiveEntry(
        val ddpSubs: List<DdpSubscription>,
        val streamStops: List<Disposable>,
    )

    /** RN activeByRid :19：活跃房间流表（rid → 订阅与监听句柄）。 */
    private val activeByRid = ConcurrentHashMap<String, ActiveEntry>()

    /**
     * RN roomStreams 模块单线程事件循环的等价：sub/unsub 全模块串行。极快退/进同房时，
     * 迟到的 unsubscribe（onDispose 异步）与新 subscribe 在 IO 线程并发交错会把新订阅连根
     * 摘除（房间流静默死亡，M2-T11 评审 Minor-1）——互斥保证单操作原子，不与另一 op 交错。
     */
    private val opMutex = Mutex()

    /** 消息落库完成后发射 rid（已读 debounce 的消费口；RN onMessagePersistedCallbacks 等价）。 */
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<String> = _incomingMessages

    /**
     * 进入房间：对指定 rid 建立三条 DDP 订阅并注册仅处理本房间的监听（RN subscribeRoomStreams :54-117）。
     * 幂等：重复 sub 先退订。DDP 未初始化即抛（RN ensureDdpResumeForStreams 失败同途——
     * 会话级 resume 编排归 RealtimeSessionManager，进房前由调用方 bootstrap）。
     */
    suspend fun subscribeRoom(rid: String) = opMutex.withLock { subscribeRoomLocked(rid) }

    private suspend fun subscribeRoomLocked(rid: String) {
        require(rid.isNotEmpty()) { "[roomStreams] rid is required" } // RN :55-57
        unsubscribeRoomLocked(rid) // RN :59 幂等先退订（锁内走 Locked，避免重入死锁）

        val ddp = sdk.ddp ?: throw DdpException("[roomStreams] ddp not initialized")
        val ddpSubs = sdk.subscribeRoom(rid) // RN :67 sdk.subscribeRoom 三条

        val streamStops = ArrayList<Disposable>(2)
        // RN :70-75 stream-notify-room：本任务只注册（typing 分发 UI 归房间页任务；deleteMessage RN 未处理）
        streamStops.add(
            ddp.onStreamData(ROOM_NOTIFY_ROOM) { msg ->
                if (parseNotifyRoomRid(msg) == rid) {
                    // TODO(T7+/RN 对齐)：RN 对 user-activity/deleteMessage 只订阅不处理（roomStreams.ts:73）；
                    // 语音通话 callMsg 等分支待后续任务按 event 接入
                }
            },
        )
        // RN :77-96 stream-room-messages：rid 过滤 → 落库 → 通知已读 debounce
        streamStops.add(
            ddp.onStreamData(ROOM_STREAM_MESSAGES) { msg -> handleRoomMessage(msg, rid) },
        )

        activeByRid[rid] = ActiveEntry(ddpSubs, streamStops) // RN :98-99
    }

    /** 退订本房间三条订阅并摘除监听（RN unsubscribeRoomStreams :119-132）；未订阅时 no-op。 */
    suspend fun unsubscribeRoom(rid: String) = opMutex.withLock { unsubscribeRoomLocked(rid) }

    private suspend fun unsubscribeRoomLocked(rid: String) {
        val entry = activeByRid.remove(rid) ?: return
        entry.streamStops.forEach { runCatching { it.stop() } }
        entry.ddpSubs.forEach { sub -> runCatching { sub.unsubscribe() } } // RN :130 失败吞
    }

    /** 登出/断连前退订全部房间流（RN unsubscribeAllRoomStreams :135-138；async 们经 opMutex 串行落地）。 */
    suspend fun unsubscribeAllRoomStreams() {
        val rids = activeByRid.keys.toList()
        coroutineScope { rids.map { rid -> async { unsubscribeRoom(rid) } }.awaitAll() }
    }

    /**
     * 传输层重连后对仍活跃的房间重订（RN resubscribeAllActiveRoomStreams :143-148，串行、
     * 失败上抛——调用方 finalize 捕获后置 disconnected 等下一轮 connected 重试）。
     * subscribeRoom 内部先退订再订阅（RN 同）。
     */
    suspend fun resubscribeAllActiveRoomStreams() {
        val rids = activeByRid.keys.toList()
        for (rid in rids) {
            subscribeRoom(rid)
        }
    }

    /**
     * 会话 teardown 挂点（RealtimeSessionManager.addTeardownHook）：摘除本管理器的
     * onStreamData 监听并清活跃表（RN :683 unsubscribeAllRoomStreams 的监听摘除半程）。
     * 网络退订不做——紧随其后的 disconnect 即服务端全量退订（RN :684-687 的
     * unsubscribeAllRoomStreams 在 async run 里与 disconnect 赛跑，终态一致）；活跃表清空后
     * 重连收尾不再重订已拆会话的房间流，旧监听不摘除会在同服复连后误收消息帧。
     *
     * 摘表必须与 sub/unsub 互斥（总纲 §4.3-4 陈旧条目复活竞态）：否则 teardown 恰好落在
     * subscribeRoom 的「已订网、未落表」窗口内时清的是空表，subscribe 随后落表复活旧会话条目，
     * 重连收尾即重订已拆会话的房间流。teardown hook 非 suspend：经 runBlocking 进 [opMutex]。
     *
     * **主线程 ANR 上界（M3 终审 T1）**：logout 由主线程触发（MainActivity/ChatListScreen），
     * 锁持有者只做 DDP sub/unsub 网络 IO——若恰有在途操作卡在 DDP 25s 订阅超时窗内，
     * 无界 runBlocking 即主线程阻塞至 ANR。故 1s 超时跳过：等不到锁则清场放弃，紧随的
     * disconnect 兜底（服务端全量退订）；该竞态窗口（订阅中途登出）本就终态一致。
     */
    fun onSessionTornDown() {
        runBlocking {
            withTimeoutOrNull(TEARDOWN_LOCK_TIMEOUT_MS) {
                opMutex.withLock {
                    for (entry in activeByRid.values) {
                        entry.streamStops.forEach { runCatching { it.stop() } }
                    }
                    activeByRid.clear()
                }
            }
        }
    }

    /** RN :77-96：rid 过滤 → hint 记录（:83-84）→ 异步落库 → 发射 rid；失败仅 warn（RN __DEV__ console.warn 同义）。 */
    private fun handleRoomMessage(msg: JsonElement, rid: String) {
        val raw = parseStreamRoomMessageRaw(msg) ?: return
        if (roomRidOf(raw) != rid) return // RN :79 args[0].rid == rid 过滤
        // RN :82-84 recordRoomAccessHintFromRawMessage（ul/ru → 访问丢失三态 hint；M4-T10）
        runCatching { recordAccessHint(raw, currentUsernameProvider()) }
        scope.launch {
            try {
                persistMessage(raw, rid)
                _incomingMessages.tryEmit(rid) // RN :86-89 persist 成功后回调
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "persist message failed (rid=$rid)", e)
            }
        }
    }

    companion object {
        private const val TAG = "roomStreams"

        /** 主线程 teardown 等锁上界（远小于 5s ANR 阈值；超时跳过清场由 disconnect 兜底）。 */
        internal const val TEARDOWN_LOCK_TIMEOUT_MS = 1_000L

        /** RN parseNotifyRoomRid :32-38：`{rid}/{event}` 取首段（此滤波路径 RN 有非数组回退，:43-45）。 */
        internal fun parseNotifyRoomRid(ddpMessage: JsonElement): String? {
            val eventName = (ddpMessage as? JsonObject)?.let { o ->
                (o["fields"] as? JsonObject)?.get("eventName")
            } as? JsonPrimitive ?: return null
            if (!eventName.isString) return null
            return eventName.content.split('/').firstOrNull()?.takeIf { it.isNotEmpty() }
        }

        /** RN :80-82 持久化提取：仅取数组 args 首元素；非数组**无回退即丢弃**（`Array.isArray ? args[0] : undefined`）。 */
        internal fun parseStreamRoomMessageRaw(ddpMessage: JsonElement): JsonElement? {
            val fields = (ddpMessage as? JsonObject)?.get("fields") as? JsonObject ?: return null
            val args = fields["args"] as? JsonArray ?: return null
            return args.firstOrNull()
        }

        /** RN :44-45：`typeof msg?.rid === 'string'`。 */
        internal fun roomRidOf(raw: JsonElement): String? =
            ((raw as? JsonObject)?.get("rid") as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull
    }
}
