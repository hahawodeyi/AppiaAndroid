package cn.appia.im.domain.chat

import cn.appia.im.core.database.AppiaDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全局搜索进房 bump（RN src/lib/chat/bumpChatFromGlobalSearch.ts + 行为源旧版
 * RoomsListView.onSearchPressItem :1102-1123：`tSearch`/`roomUpdatedAt` 同写 `new Date()`），
 * 经 [useBumpChatFromGlobalSearchOnFocus](RN hook) 的进房/离房双 bump 时序编排：
 *
 * - [enterRoom]：立即 bump 一次 + [RETRY_DELAY_MS] 后重试一次——停留期间 DDP
 *   rooms-changed 整行覆盖（tSearch 未知 → null）会清掉本地写，800ms 内的第二写恢复排序位。
 * - [leaveRoom]：取消未触发的重试 + 补一次 bump（RN useFocusEffect cleanup 同语义：
 *   离开搜索来源房间时兜底，防进房 bump 被进房瞬间的 DDP 覆盖吞掉后无从恢复）。
 *
 * 写值取本机时钟（RN `new Date()`，非 serverNow）；行不存在/空白 rid 静默 false（RN find catch）。
 * ChatMerger 落库路径 tSearch 取 max（:633），本类直写路径仅来自本地用户动作，
 * 单调时钟注入 [now] 供测试。
 */
class ChatBumper(
    private val db: AppiaDatabase,
    private val scope: CoroutineScope,
    /** 测试时钟注入；生产恒 System.currentTimeMillis()。 */
    private val now: () -> Long = System::currentTimeMillis,
    /** 重试间隔（RN setTimeout 800）；测试注入短窗。 */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    private var retryJob: Job? = null

    /**
     * 进房：立即 bump + 800ms 重试（DDP 覆盖防护）。
     * 重复调用（同 rid 再进）按先离后进由 [leaveRoom] 归位；直接重入仅刷新重试计时。
     */
    fun enterRoom(rid: String) {
        if (rid.isBlank()) return
        retryJob?.cancel()
        scope.launch { bump(rid) }
        retryJob = scope.launch {
            delay(retryDelayMs)
            bump(rid)
        }
    }

    /** 离房：取消未触发重试 + 兜底再 bump 一次（RN focus cleanup）。 */
    fun leaveRoom(rid: String) {
        if (rid.isBlank()) return
        retryJob?.cancel()
        retryJob = null
        scope.launch { bump(rid) }
    }

    /** 单次写（RN bumpChatFromGlobalSearch :29-54）：tSearch + room_updated_at 同毫秒。 */
    suspend fun bump(rid: String): Boolean {
        val trimmed = rid.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val row = db.chatDao().getById(trimmed) ?: return false
            val millis = now().toDouble()
            db.chatDao().update(row.copy(tSearch = millis, room_updated_at = millis))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false // RN catch → undefined 同义：写失败不影响进房
        }
    }

    companion object {
        /** RN useBumpChatFromGlobalSearchOnFocus :20 `setTimeout(bump, 800)`。 */
        const val RETRY_DELAY_MS = 800L
    }
}
