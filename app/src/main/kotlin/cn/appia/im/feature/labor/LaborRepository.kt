package cn.appia.im.feature.labor

import cn.appia.im.core.network.RocketHttp
import cn.appia.im.core.network.api.WorktableGroup
import cn.appia.im.core.network.api.WorktableItem
import cn.appia.im.core.network.api.fetchWorktableConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * 工作台域常量（RN lib/worktable/laborConstants.ts verbatim）：与名称/type 绑定的分支逻辑
 * 集中一处，避免魔法串散落。中文名是服务端 config 下发的协议值（非 UI 文案，勿进 i18n），
 * 按 RN laborConstants.ts:6 先例以 \u 转义落码过中文检查钩子。
 */
/** 考勤打卡 —— 旧版 worktable 条目名（打开前需定位权限）。 */
const val LABOR_ITEM_NAME_ATTENDANCE_CHECKIN = "\u8003\u52e4\u6253\u5361"

/** 企业滴滴 —— 同为定位权限类条目（laborItemNeedsLocationPermission 另一支）。 */
const val LABOR_ITEM_NAME_ENTERPRISE_DIDI = "\u4f01\u4e1a\u6ef4\u6ef4"

/** 消息待办条目名（点击 → TodoList）。 */
const val LABOR_ITEM_NAME_MESSAGE_TODO = "\u6d88\u606f\u5f85\u529e"

/** 私信员工服务 —— 旧版 `item.type === 3`。 */
const val LABOR_ITEM_TYPE_DIRECT_SERVICE = 3

/** 消息待办 —— 旧版 `item.type === 10`。 */
const val LABOR_ITEM_TYPE_MESSAGE_TODO = 10

/** 访客账号需隐藏的条目关键词（RN :17——大小写两形态各自整串匹配）。 */
val LABOR_GUEST_BLOCKED_SUBSTRINGS = listOf("E-Learning", "E-learning")

/**
 * RN normalizeWorktableGroups.ts 逐行移植（对齐旧版 app/sagas/workspace.js）：
 * - 过滤 `status <= 0` 条目；
 * - 相对 `url`/`icon` 补 server 前缀（`^https?://` 大小写不敏感视为绝对；无前导 `/` 自动补）；
 * - guest（username 含 `appia.guest`）过滤名称含 E-Learning/E-learning 的条目；
 * - 空分组（含全被过滤后）丢弃。
 * Kotlin 数据类不可变，RN 的逐条浅拷贝无对应物（无原地修改）。
 */
fun normalizeWorktableGroups(
    raw: List<WorktableGroup>,
    serverBase: String,
    isGuest: Boolean = false,
): List<WorktableGroup> {
    if (raw.isEmpty()) return emptyList()
    val base = serverBase.trimEnd('/')
    val out = mutableListOf<WorktableGroup>()
    for (g in raw) {
        var items: List<WorktableItem> = g.items.filter { it.status > 0 }
        items = items.map { a ->
            a.copy(
                url = a.url.prefixServerIfRelative(base),
                icon = a.icon.prefixServerIfRelative(base),
            )
        }
        if (isGuest) items = items.filter { a -> LABOR_GUEST_BLOCKED_SUBSTRINGS.none { (a.name).contains(it) } }
        if (items.isEmpty()) continue
        out.add(g.copy(items = items))
    }
    return out
}

/** RN startsWithHttp + 前缀拼接：绝对地址原样；相对地址 `base` + 补 `/`。 */
private fun String.prefixServerIfRelative(base: String): String {
    if (isEmpty()) return this
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(this)) return this
    return "$base${if (startsWith("/")) "" else "/"}$this"
}

/**
 * RN filterWorktableBySearch.ts：按应用名称子串过滤（trim+lowercase）；query 空白返回原分组。
 */
fun filterWorktableBySearch(groups: List<WorktableGroup>, query: String): List<WorktableGroup> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return groups
    return groups
        .map { g -> g.copy(items = g.items.filter { it.name.trim().lowercase().contains(q) }) }
        .filter { it.items.isNotEmpty() }
}

/** RN useQuery 数据面（data/isPending/isError）；刷新保留旧数据（react-query refetch 语义）。 */
data class LaborState(
    val groups: List<WorktableGroup> = emptyList(),
    /** 首载且无缓存数据（RN query.isPending；刷新不清屏）。 */
    val isLoading: Boolean = false,
    val isError: Boolean = false,
)

/**
 * 工作台仓库 = react-query 等价单槽缓存（RN LaborScreen/index.tsx:32-39 useQuery 语义）：
 * - fetch = fetchWorktableConfig + normalizeWorktableGroups（RN queryFn 同序）；
 * - 刷新（下拉）对同参再次拉取，失败/刷新中保留旧数据；
 * - 无 DDP/无推送失效：拉取只在 进屏/手动刷新 两时机（RN 同）。
 * 每屏新建实例（MainActivity remember(serverUrl) 口径，换服随屏重建）。
 */
class LaborRepository(private val client: OkHttpClient = RocketHttp.client) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LaborState())

    val state: StateFlow<LaborState> = _state.asStateFlow()

    suspend fun fetch(serverUrl: String, isGuest: Boolean) {
        mutex.withLock {
            val prev = _state.value
            if (prev.groups.isEmpty() && !prev.isError) {
                _state.value = prev.copy(isLoading = true)
            }
            _state.value = try {
                val raw = fetchWorktableConfig(serverUrl, client)
                LaborState(groups = normalizeWorktableGroups(raw, serverUrl, isGuest))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                LaborState(groups = prev.groups, isError = true)
            }
        }
    }
}
