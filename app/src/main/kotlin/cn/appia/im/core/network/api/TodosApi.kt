package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.domain.chat.ChatMerger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * 待办 wire（RN src/services/api/todos.ts + messages.ts toggleTodoMessage 逐字段）：
 * - [fetchTodos]：`GET appia/todos?offset=0&count=1000[&rid]`（RN :12-23，条目经 [sortTodoList] 四段排序）；
 * - [toggleTodoMessage]：`POST appia/set-message-todo {messageId, status:1, tips:'', type:'d'}`
 *   （RN messages.ts:54-62；Room 长按设待办，唯一调用形态硬编码这组值）；
 * - [completeTodo]：`POST appia/update-message-todo-status {id, status:-1}`（RN todos.ts:37-38）；
 * - [updateTodo]：`POST appia/update-message-todo {id, title?, tips?, type?, reminderTime?}`
 *   （RN todos.ts:33-35；null 字段不下发，与 JS undefined 同义）。
 *
 * status 语义照抄勿「修正」（研究坑 2）：设待办发 1，消息回流 status=0 才是进行中，完成发 -1。
 */

/** RN TodoAttachmentItem（types/todo.ts:2-10，wire 原始下划线形态）。 */
@Serializable
data class TodoAttachment(
    val ts: String = "",
    @SerialName("title_link") val titleLink: String = "",
    val title: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("title_link_download") val titleLinkDownload: String = "",
    val format: String = "",
    val type: String = "",
)

/**
 * RN TodoItem（types/todo.ts:12-28）。必填字段不给默认值：服务端契约漂移在解析期爆出
 * （与 RN 缺字段时 Date(undefined)→NaN 同为可观测异常面，不静默吞）。
 */
@Serializable
data class TodoItem(
    val id: String,
    val title: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val status: Double,
    val mid: String,
    val tips: String? = null,
    val attachments: List<TodoAttachment>? = null,
    /** 'h' = 高优。 */
    val type: String? = null,
    val reminderTime: String? = null,
    @SerialName("isOvertime") val isOvertime: Boolean? = null,
    val rid: String? = null,
    val t: String? = null,
    @SerialName("isMessageDeleted") val isMessageDeleted: Boolean? = null,
    val name: String? = null,
)

/** RN TodoModel（types/todo.ts:30-34）。 */
@Serializable
data class TodoModel(
    val count: Double? = null,
    val list: List<TodoItem>? = null,
    val total: Double? = null,
)

/** RN TodoListResult（todos.ts:7-10）。 */
data class TodoListResult(
    val total: Double,
    val items: List<TodoItem>,
)

/** RN fetchTodos 常量（todos.ts:16-17）。 */
private const val OFFSET = "0"
private const val COUNT = "1000"

private val todoJson = Json { ignoreUnknownKeys = true }

/**
 * RN sortTodoList（lib/todo/sortTodoList.ts 逐行）：四段
 * 高优+提醒时间 ↑ → 高优无时间（createdAt ↑）→ 普通+时间 ↑ → 普通无时间（createdAt ↑）。
 * 段内稳定排序（RN Array#sort ES2019 起稳定，Kotlin sortedWith 同）。
 */
fun sortTodoList(list: List<TodoItem>): List<TodoItem> {
    val (high, low) = list.partition { it.type == "h" }
    val (highWithTime, highNoTime) = high.partition { !it.reminderTime.isNullOrEmpty() }
    val (lowWithTime, lowNoTime) = low.partition { !it.reminderTime.isNullOrEmpty() }
    return highWithTime.sortedBy(::reminderMillis) +
        highNoTime.sortedBy(::createdMillis) +
        lowWithTime.sortedBy(::reminderMillis) +
        lowNoTime.sortedBy(::createdMillis)
}

private fun reminderMillis(i: TodoItem): Double =
    i.reminderTime?.let { ChatMerger.parseIsoMillis(it) } ?: 0.0

private fun createdMillis(i: TodoItem): Double =
    ChatMerger.parseIsoMillis(i.createdAt) ?: 0.0

/**
 * RN fetchTodos（todos.ts:12-23）：rid 可选；`data.total ?? items.length`；条目四段排序。
 */
suspend fun fetchTodos(sdk: RocketSdk, rid: String? = null): TodoListResult {
    val params = buildMap {
        put("offset", OFFSET)
        put("count", COUNT)
        if (!rid.isNullOrEmpty()) put("rid", rid)
    }
    val model = todoJson.decodeFromJsonElement<TodoModel>(sdk.get("appia/todos", params))
    val items = sortTodoList(model.list ?: emptyList())
    return TodoListResult(total = model.total ?: items.size.toDouble(), items = items)
}

/** RN toggleTodoMessage（messages.ts:54-62）——AA 调用只取设待办形态，参数收敛为 messageId。 */
suspend fun toggleTodoMessage(sdk: RocketSdk, messageId: String): JsonElement =
    sdk.post(
        "appia/set-message-todo",
        buildJsonObject {
            put("messageId", messageId)
            put("status", 1)
            put("tips", "")
            put("type", "d")
        },
    )

/** RN completeTodo（todos.ts:37-38）；status 默认 -1（完成语义，研究坑 2）。 */
suspend fun completeTodo(sdk: RocketSdk, id: String, status: Int = -1): JsonElement =
    sdk.post(
        "appia/update-message-todo-status",
        buildJsonObject {
            put("id", id)
            put("status", status)
        },
    )

/** RN updateTodo（todos.ts:33-35）：null 字段省略（JS undefined 不下发）。 */
suspend fun updateTodo(
    sdk: RocketSdk,
    id: String,
    title: String? = null,
    tips: String? = null,
    type: String? = null,
    reminderTime: String? = null,
): JsonElement =
    sdk.post(
        "appia/update-message-todo",
        buildJsonObject {
            put("id", id)
            title?.let { put("title", it) }
            tips?.let { put("tips", it) }
            type?.let { put("type", it) }
            reminderTime?.let { put("reminderTime", it) }
        },
    )
