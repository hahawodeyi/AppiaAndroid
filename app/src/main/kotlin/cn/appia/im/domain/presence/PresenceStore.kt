package cn.appia.im.domain.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * presence 状态仓（RN src/stores/presenceStore.ts zustand 模块单例等价）。
 * 写入方：PresenceBatcher（users.presence 批量 merge）/ stream-user-presence 增量 setUserStatus；
 * 读取方：UI 经 [status] 订阅后叠 fallback 双层（[mergePresenceStatus]）。
 * 登出清空（RN authStore logout :153 usePresenceStore.clear 同位——AuthRepository.logout 挂点）。
 */
object PresenceStore {

    /** RN ActiveUserEntry { status, statusText? }。 */
    data class ActiveUserEntry(val status: TUserStatus, val statusText: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeUsers = MutableStateFlow<Map<String, ActiveUserEntry>>(emptyMap())

    /** 全量快照（观测/批量场景；单用户订阅用 [status]）。 */
    val activeUsers: StateFlow<Map<String, ActiveUserEntry>> = _activeUsers

    // ponytail: per-user flow 缓存无淘汰——上限=见过的用户数（小）；量大再改 LRU 或直接 map 派生
    private val statusFlows = HashMap<String, StateFlow<TUserStatus?>>()
    private val flowsLock = Any()

    /** 单用户状态流（store 层原始值；UI 侧再叠 fallback）。同 id 复用同一实例。 */
    fun status(userId: String): StateFlow<TUserStatus?> = synchronized(flowsLock) {
        statusFlows.getOrPut(userId) {
            _activeUsers
                .map { it[userId]?.status }
                .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, _activeUsers.value[userId]?.status)
        }
    }

    /** RN mergeActiveUsers：批量浅合并（users.presence 响应整批落，含缺失条目 OFFLINE）。 */
    fun mergeActiveUsers(batch: Map<String, ActiveUserEntry>) {
        _activeUsers.update { it + batch }
    }

    /** RN setUserStatus：stream-user-presence 单条增量。 */
    fun setUserStatus(userId: String, entry: ActiveUserEntry) {
        _activeUsers.update { it + (userId to entry) }
    }

    /** RN clear。 */
    fun clear() {
        _activeUsers.value = emptyMap()
    }

    /** 测试观测：当前快照单用户直读。 */
    fun snapshot(userId: String): ActiveUserEntry? = _activeUsers.value[userId]
}
