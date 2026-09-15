package cn.appia.im.feature.chatlist

import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * 会话列表 ViewModel（RN useRoomListChats + buildRoomListSections 的 Kotlin 落法）：
 * Room Flow（chatDao.observeList）→ activeDbMatchesAuth 守卫 → 分类+排序 → StateFlow。
 *
 * - 构造时绑定目标 server（换服后由会话层重建实例，同 RoomsSyncRepository 绑定裁定）；
 *   守卫不过的旧库回调一律清空不写新 UI（RN useRoomListChats.ts:32-35 换服先置 []）。
 * - 不做 reconcile：Room invalidation 为表级重发射 + data class equals，行级 diff 交给
 *   UI 层 LazyColumn key + contentType（派单裁定 4）。
 * - currentUserId 用 `user.id`（RN RoomListScreenInner.tsx:56），uid 判定不用 username。
 */
class ChatListViewModel(
    private val dbManager: DatabaseManager,
    private val serverUrl: String,
    private val auth: AuthSessionStore,
    scope: CoroutineScope,
) {

    /** 构造期捕获目标库：observeList 与守卫对照都指它，避免 active 切换后 observe 目标漂移。 */
    private val db = dbManager.databaseFor(dbManager.normalizeServer(serverUrl))

    private val searchText = MutableStateFlow("")

    /** RN RoomListSearchBar 单向数据流：UI 设词，重新过滤/分段由 combine 自动触发。 */
    fun onSearchChanged(text: String) {
        searchText.value = text
    }

    /** RN activeDbMatchesAuth 等价守卫：active 库必须是本实例绑定的库；空白 server 恒不匹配。 */
    internal fun activeDbMatchesAuth(): Boolean {
        if (serverUrl.isBlank()) return false
        return dbManager.active === db
    }

    internal fun currentUserId(): String? = auth.load()?.user?.id?.takeIf { it.isNotEmpty() }

    val sections: StateFlow<List<ChatListSection>> =
        combine(db.chatDao().observeList(), searchText) { rows, query ->
            if (!activeDbMatchesAuth()) {
                emptyList()
            } else {
                buildRoomListSections(rows, currentUserId(), query)
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
