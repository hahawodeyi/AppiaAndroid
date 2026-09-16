package cn.appia.im.feature.chat

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.RoomHistoryRepository
import cn.appia.im.domain.chat.ChatMerger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 一次 loadEarlier 的待核对数据（RN pendingEarlierLoadRef :40-42）：落库后按窗口是否增长判定。 */
data class PendingEarlier(val lengthBefore: Int, val returnedCount: Int)

/**
 * 房间消息分页状态（RN usePaginatedRoomMessages 返回面）。
 * windowSize / noGrowthStreak / pendingEarlier / loadGen 是分页机件（UI 不消费），
 * 与 UI 状态同格存于一个 StateFlow，所有变更走原子 update——JS 单线程的天然串行在此显式化。
 */
data class RoomMessagesUiState(
    val rid: String = "",
    val roomType: String = "",
    val messages: List<MessageEntity> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasMoreEarlier: Boolean = true,
    val windowSize: Int = RoomMessagesViewModel.COUNT,
    val noGrowthStreak: Int = 0,
    val pendingEarlier: PendingEarlier? = null,
    val loadGen: Int = 0,
)

/**
 * 房间消息分页 ViewModel（逐行为移植 appiaMobile/src/hooks/usePaginatedRoomMessages.ts）：
 * - 本地窗口：`WHERE rid=? ORDER BY ts DESC LIMIT windowSize`（50 起，loadEarlier +50），
 *   Room Flow 落库即重发射（WatermelonDB experimentalSubscribeWithColumns 等价）；
 * - 进房：本地查询与远程 50 并发（Promise.all 等价），远程返回 >0 重查一次，5s 兜底落加载位；
 *   RN 的 lastSettledGenRef「同代只 settle 一次」在此为结构性保证——settle 每次 openRoom 恰发一次；
 * - loadEarlier：`latest = 本地最旧一条 ts 的 ISO 串`；返回 0 或 <count → 无更多；
 *   noGrowthStreak≥2（服务端有返回但本地窗口未增长连续 2 次）→ 无更多；
 * - refresh：重拉最近 50，靠 MessageUpsert upsert 去重；
 * - 换房间（openRoom）：state 全量重置，旧代协程取消，迟到回调经 loadGen 守卫丢弃
 *   （RN loadGenRef 逐代比对等价）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMessagesViewModel(
    private val repo: RoomHistoryRepository,
    private val db: AppiaDatabase,
    private val scope: CoroutineScope,
    /** 进房兜底超时（RN INITIAL_LOAD_TIMEOUT_MS :15）；测试注入短值。 */
    private val initialLoadTimeoutMs: Long = INITIAL_LOAD_TIMEOUT_MS,
) {

    private val _state = MutableStateFlow(RoomMessagesUiState())
    val state: StateFlow<RoomMessagesUiState> = _state

    private val genCounter = AtomicInteger(0)
    private var roomJob: Job? = null

    /** 进房/换房（RN useLayoutEffect [rid] :46-59）：全量重置分页状态后起本代收集与一次性 settle。 */
    fun openRoom(rid: String, roomType: String) {
        val gen = genCounter.incrementAndGet()
        roomJob?.cancel()
        roomJob = null
        _state.value = RoomMessagesUiState(
            loadGen = gen,
            rid = rid,
            roomType = roomType,
            isInitialLoading = rid.isNotEmpty(), // RN :34 Boolean(rid)
        )
        if (rid.isEmpty()) return // RN :52-55 空 rid 清空即止，不起订阅

        roomJob = scope.launch {
            coroutineScope {
                // 本地窗口流：windowSize 增长时换查询重订（RN effect [windowSize] :148 重建订阅等价）
                launch {
                    _state.map { it.windowSize }
                        .distinctUntilChanged()
                        .flatMapLatest { limit -> db.messageDao().observeByRid(rid, limit) }
                        .collect { list -> applyWindow(gen, list) }
                }
                // 进房 settle：本地查询与远程 50 并发；同代只 settle 一次（本 launch 每代恰一次）
                launch { settleInitialLoad(gen, rid, roomType) }
                // 5s 兜底：加载位仍挂着就落下（RN safetyTimeout :125-130；gen 守卫使迟到无副作用）
                launch {
                    delay(initialLoadTimeoutMs)
                    finishInitialLoading(gen)
                }
            }
        }
    }

    /**
     * 加载更早（RN loadEarlier :150-182）：游标 = 本地最旧一条 ts 的 ISO 串；
     * 失败（null）不置 hasMoreEarlier=false 也不计 noGrowth；返回 0 / <count → 无更多；
     * 满页 → 窗口 +50 交由窗口流重查，再按是否增长核对 noGrowth。
     */
    fun loadEarlier() {
        val s = _state.value
        if (s.isLoadingEarlier || !s.hasMoreEarlier || s.messages.isEmpty()) return
        val gen = s.loadGen
        val lengthBefore = s.messages.size
        val oldestTs = s.messages.last().ts // DESC 窗口末位 = 本地最旧
        _state.update { it.copy(isLoadingEarlier = true) }
        scope.launch {
            val returned = repo.loadRoomHistory(
                s.rid,
                s.roomType,
                latest = ChatMerger.formatIsoMillis(oldestTs.toLong()), // RN new Date(oldestTs).toISOString()
            )
            updateForGen(gen) { it.copy(isLoadingEarlier = false) }
            if (returned == null) return@launch // 失败：保持 hasMore，可再次 loadEarlier/refresh
            if (returned == 0) { // RN :164-169 服务端确凿无更多
                updateForGen(gen) {
                    it.copy(hasMoreEarlier = false, noGrowthStreak = 0, pendingEarlier = null)
                }
                return@launch
            }
            updateForGen(gen) { // RN :171-176
                it.copy(
                    pendingEarlier = PendingEarlier(lengthBefore, returned),
                    windowSize = it.windowSize + COUNT,
                    hasMoreEarlier = if (returned < COUNT) false else it.hasMoreEarlier,
                )
            }
        }
    }

    /** 下拉刷新（RN refresh :184-193）：重拉最近 50，落库 upsert 去重，窗口流自动重发射。 */
    fun refresh() {
        val s = _state.value
        if (s.rid.isEmpty()) return
        val gen = s.loadGen
        _state.update { it.copy(isRefreshing = true) }
        scope.launch {
            repo.loadRoomHistory(s.rid, s.roomType)
            updateForGen(gen) { it.copy(isRefreshing = false) }
        }
    }

    /** RN settleInitialLoad :97-123：两端并发（Promise.all），远程有货时本地旧快照作废重查一次。 */
    private suspend fun settleInitialLoad(gen: Int, rid: String, roomType: String) {
        coroutineScope {
            val local = async { db.messageDao().getByRid(rid, COUNT) }
            val remote = async { repo.loadRoomHistory(rid, roomType) } // 失败 null（RN .catch(() => 0)）
            val localFirst = local.await()
            val historyCount = remote.await() ?: 0
            var list = localFirst
            if (list.isEmpty() || historyCount > 0) { // RN :106-110 重查一次
                list = db.messageDao().getByRid(rid, COUNT)
            }
            applyWindow(gen, list)
            if (list.isEmpty() && historyCount == 0) finishInitialLoading(gen)
            // historyCount>0 但本地仍空：等窗口流推送后再落下加载位（applyWindow 顺带落，RN :119）
        }
    }

    /** RN apply :77-95：上屏 + pendingEarlier 核对（noGrowth 判定）+ 有数据即落初始加载位。 */
    private fun applyWindow(gen: Int, list: List<MessageEntity>) {
        _state.update { s ->
            if (s.loadGen != gen) return@update s
            var next = s.copy(messages = list)
            val pending = s.pendingEarlier
            if (pending != null) {
                next = if (list.size <= pending.lengthBefore && pending.returnedCount > 0) {
                    // 服务端有返回但窗口未增长：连续 2 次 → 无更多（RN noGrowthStreak :81-91）
                    val streak = s.noGrowthStreak + 1
                    next.copy(
                        noGrowthStreak = streak,
                        hasMoreEarlier = if (streak >= NO_GROWTH_LIMIT) false else s.hasMoreEarlier,
                    )
                } else {
                    next.copy(noGrowthStreak = 0) // 增长即清零
                }
                next = next.copy(pendingEarlier = null)
            }
            if (list.isNotEmpty()) next = next.copy(isInitialLoading = false)
            next
        }
    }

    /** RN finishInitialLoad :72-75：本代且仍加载中才落（迟到的 settle/超时不回写新代状态）。 */
    private fun finishInitialLoading(gen: Int) {
        _state.update {
            if (it.loadGen == gen && it.isInitialLoading) it.copy(isInitialLoading = false) else it
        }
    }

    private fun updateForGen(gen: Int, transform: (RoomMessagesUiState) -> RoomMessagesUiState) {
        _state.update { if (it.loadGen == gen) transform(it) else it }
    }

    companion object {
        const val COUNT = 50

        /** 服务端有返回但窗口未增长的容忍次数，达到即判无更多（RN :84 `>= 2`）。 */
        const val NO_GROWTH_LIMIT = 2

        /** RN INITIAL_LOAD_TIMEOUT_MS :15。 */
        const val INITIAL_LOAD_TIMEOUT_MS = 5_000L
    }
}
