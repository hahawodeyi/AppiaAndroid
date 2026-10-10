package cn.appia.im.feature.todo

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.TodoItem
import cn.appia.im.core.network.api.TodoListResult
import cn.appia.im.core.network.api.fetchTodos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** RN useTodoListQuery 返回面（items/total/isLoading/isError/refetch/isMutating）。 */
data class TodoListState(
    val items: List<TodoItem> = emptyList(),
    val total: Double = 0.0,
    /** 首载且无缓存数据（RN query.isLoading；refetch/下拉刷新不清空旧列表）。 */
    val isLoading: Boolean = false,
    val isError: Boolean = false,
)

/**
 * 待办列表仓库 = react-query 等价缓存（RN hooks/useTodoListQuery.ts 语义移植）：
 * - 缓存按 rid 分槽（queryKey `todoQueryKeys.list(serverUrl, token, rid)` 等价——会话内
 *   server/token 变更即会话层重建 sdk，此处不重复设键）；槽由 [observe]/[fetch] 惰性创建。
 * - **失效 = 全部已存在槽重新拉取**（RN invalidateQueries(todoQueryKeys.all) 对 active query
 *   的 refetch 同义）：complete/updateReminder 成功后 [invalidateAll]。
 * - 下拉刷新 = 对同一 rid 再次 [fetch]（RN refetch：保留旧数据不清屏）。
 * - **无 DDP 订阅**（研究坑 1）：REST 拉取只在 进屏/失效/手动刷新 三时机——抽屉计数与会话
 *   红点双计数源短暂不同步是 RN 既定行为，勿用单一源「修」齐。
 */
class TodoListRepository(
    private val sdk: RocketSdk,
    private val actions: TodoActions,
) {
    private val mutex = Mutex()
    private val slots = mutableMapOf<String?, MutableStateFlow<TodoListState>>()

    private val _isMutating = MutableStateFlow(false)

    /** 任一 mutation 在途（RN updateMutation.isPending || completeMutation.isPending）——全屏遮罩。 */
    val isMutating: StateFlow<Boolean> = _isMutating.asStateFlow()

    /** 观察某 rid 槽（无副作用；数据经 [fetch] 装载）。 */
    fun observe(rid: String?): StateFlow<TodoListState> = slotFlow(rid).asStateFlow()

    /** 进屏装载 / 手动下拉刷新（RN queryFn + refetch）：已有数据时静默刷新不清屏。 */
    suspend fun fetch(rid: String?) {
        mutex.withLock {
            val flow = slotFlow(rid)
            val prev = flow.value
            if (!prev.items.any() && prev.total == 0.0 && !prev.isError) {
                flow.value = prev.copy(isLoading = true)
            }
            flow.value = try {
                val result: TodoListResult = fetchTodos(sdk, rid)
                TodoListState(items = result.items, total = result.total)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                prev.copy(isLoading = false, isError = true)
            }
        }
    }

    /** 完成（坑 12 双写在 [TodoActions.complete] 内）：成功 → invalidate。false/抛错 → 不失效（RN 同）。 */
    suspend fun complete(item: TodoItem): Boolean =
        mutate { actions.complete(item.id, item.mid) }

    /** 改提醒时间：拒提（过去时间）与 POST 在 [TodoActions.setReminder]；成功 → invalidate。 */
    suspend fun setReminder(item: TodoItem, reminderIso: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        mutate { actions.setReminder(item, reminderIso, nowMs) }

    private suspend fun mutate(block: suspend () -> Boolean): Boolean {
        _isMutating.value = true
        try {
            val ok = block()
            if (ok) invalidateAll()
            return ok
        } finally {
            _isMutating.value = false
        }
    }

    /** RN invalidateQueries(todoQueryKeys.all)：全部已存在槽重新拉取（active query refetch 同义）。 */
    private suspend fun invalidateAll() {
        mutex.withLock {
            for (rid in slots.keys.toList()) {
                val flow = slots.getValue(rid)
                flow.value = try {
                    val result = fetchTodos(sdk, rid)
                    TodoListState(items = result.items, total = result.total)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    flow.value.copy(isError = true)
                }
            }
        }
    }

    private fun slotFlow(rid: String?): MutableStateFlow<TodoListState> =
        slots.getOrPut(rid) { MutableStateFlow(TodoListState()) }
}
