package cn.appia.im.feature.todo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.chat.ui.DocPreviewParams
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.chat.ui.ViewerImage
import cn.appia.im.feature.todo.TodoListRepository
import cn.appia.im.feature.todo.coerceRoomType

/**
 * 房间待办屏（RN RoomTodoScreen/index.tsx）：同卡片按 rid 过滤；差异点——
 * - onGotoSession **同房保留路由参**（RN :109-118：roomType/routeTitle/fromAgent 原样 +
 *   jumpToMessageId，不回退 item.t/name）；跨房 = 待办条目自身的房间参数。
 * - footer/空态「全部待办」钮 → TodoList（RN :148-159,204）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomTodoScreen(
    rid: String,
    roomType: String,
    routeTitle: String,
    repo: TodoListRepository,
    username: String,
    serverUrl: String,
    userId: String,
    token: String,
    kv: KvStore,
    onBack: () -> Unit,
    onGotoSession: (rid: String, roomType: String, title: String, messageId: String) -> Unit,
    onOpenAllTodos: () -> Unit,
    onOpenImage: (List<ViewerImage>, Int) -> Unit,
    onOpenDoc: (DocPreviewParams) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        RoomHeader(title = context.t("todo_title"), onBack = onBack)
        TodoScreenBody(
            rid = rid,
            repo = repo,
            username = username,
            serverUrl = serverUrl,
            userId = userId,
            token = token,
            kv = kv,
            modifier = Modifier.weight(1f),
            onGotoSession = { item ->
                if (item.isMessageDeleted == true) {
                    todoToast(context, "todo_messagedeleted")
                } else if (!item.rid.isNullOrEmpty()) {
                    val isSameRoom = item.rid == rid
                    onGotoSession(
                        if (isSameRoom) rid else item.rid!!,
                        if (isSameRoom) roomType else coerceRoomType(item.t),
                        if (isSameRoom) routeTitle else item.name.orEmpty(),
                        item.mid,
                    )
                }
            },
            onOpenAllTodos = onOpenAllTodos,
            showAllTodosEntry = true,
            onOpenImage = onOpenImage,
            onOpenDoc = onOpenDoc,
        )
    }
}
