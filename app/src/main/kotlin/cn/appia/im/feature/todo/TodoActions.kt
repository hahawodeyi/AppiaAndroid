package cn.appia.im.feature.todo

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.completeTodo
import cn.appia.im.core.network.api.toggleTodoMessage
import cn.appia.im.core.network.api.updateTodo

/**
 * 待办动作（RN hooks/useTodoListQuery mutations + RoomScreen handlers :834-842 语义）：
 * - [complete]（坑 12 双写）：POST update-message-todo-status 成功后**再**清本地消息
 *   `appia_todo`（RN clearMessageAppiaTodo——消息未落库 no-op 吞错）。漏掉本地清库则房间内
 *   消息待办样式不消失（列表页由 invalidate 兜住，房间内不会）。
 * - [setReminder]：过去时间拒提（RN TodoListScreen :79-82 `date <= new Date()`）→ 不发请求。
 * - [setTodo]（设待办，坑 3）：只 POST set-message-todo status:1，**本地不写 appia_todo**——
 *   等 DDP stream 回流（status 0=进行中，坑 2 语义不对称照抄勿「修正」）。
 */
class TodoActions(private val sdk: RocketSdk, private val db: AppiaDatabase) {

    /**
     * 完成待办：返回 false = 服务端 success:false（RN res?.success === false → toast，不清库
     * 不失效）；网络错误上抛由 UI catch → toast。返回 true 已完成本地清库。
     */
    suspend fun complete(id: String, mid: String): Boolean {
        val res = completeTodo(sdk, id)
        if (isFailed(res)) return false
        clearMessageAppiaTodo(mid)
        return true
    }

    /**
     * 改提醒时间：[reminderIso] 须晚于 [nowMs]（RN :79-82），否则拒提返回 false。
     * 成功后由仓库层 invalidate（本方法只管 POST + 拒提判定，RN onConfirmReminder 同）。
     */
    suspend fun setReminder(
        item: cn.appia.im.core.network.api.TodoItem,
        reminderIso: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        // RN `date <= new Date()`：等于也算过去（millis 比较同口径）
        val target = cn.appia.im.domain.chat.ChatMerger.parseIsoMillis(reminderIso)
            ?: return false
        if (target <= nowMs.toDouble()) return false
        val res = updateTodo(
            sdk,
            item.id,
            title = item.title,
            tips = item.tips,
            type = item.type,
            reminderTime = reminderIso,
        )
        return !isFailed(res)
    }

    /** 设待办（Room 长按，RN onSetTodo :834-838）：唯一调用形态 status:1；失败上抛（RN console.warn 同级）。 */
    suspend fun setTodo(messageId: String) {
        toggleTodoMessage(sdk, messageId)
    }

    /** RN clearMessageAppiaTodo（lib/todo/clearMessageAppiaTodo.ts:9-25）：置 `''`；吞一切错（旧版 catch 一致）。 */
    private suspend fun clearMessageAppiaTodo(messageId: String) {
        runCatching { db.messageDao().clearAppiaTodo(messageId) }
    }

    /** RN `res?.success === false` 判定（其余响应形态一律视为成功）。 */
    private fun isFailed(res: kotlinx.serialization.json.JsonElement): Boolean =
        (res as? kotlinx.serialization.json.JsonObject)?.get("success") ==
            kotlinx.serialization.json.JsonPrimitive(false)
}

/** RN parseAppiaTodo（messageActions.tsx:105-113）：`{status:number, tid?:string}`，坏 JSON → null。 */
data class AppiaTodo(val status: Double, val tid: String? = null)

fun parseAppiaTodo(raw: String?): AppiaTodo? {
    if (raw.isNullOrEmpty()) return null
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            as? kotlinx.serialization.json.JsonObject ?: return null
        val status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            ?: return null
        AppiaTodo(
            status = status,
            tid = (obj["tid"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }.getOrNull()
}

/** RN isTodoInProgress（messageActions.tsx:164-165）：有 appia_todo 且 status===0 = 进行中。 */
fun isTodoInProgress(raw: String?): Boolean = parseAppiaTodo(raw)?.status == 0.0
