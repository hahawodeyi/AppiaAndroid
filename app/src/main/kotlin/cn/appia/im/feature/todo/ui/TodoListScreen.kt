package cn.appia.im.feature.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.api.TodoItem
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.DocPreviewParams
import cn.appia.im.feature.chat.ui.ViewerImage
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.todo.TodoListRepository
import cn.appia.im.feature.todo.coerceRoomType
import cn.appia.im.feature.todo.todoRoomDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 全量待办屏（RN TodoListScreen/index.tsx）：列表 + 下拉刷新 + 空态三态（loading/error 重试/
 * empty）+ mutation 全屏遮罩 + TodoReminderPicker。「去处理」→ RoomRoute(jumpToMessageId)
 * （RN useJumpToMessage：跨屏进房）；附件 → 图片预览/文档预览（RN openTodoAttachment 鉴权参同链）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(
    repo: TodoListRepository,
    username: String,
    serverUrl: String,
    userId: String,
    token: String,
    kv: KvStore,
    onBack: () -> Unit,
    onGotoSession: (rid: String, roomType: String, title: String, messageId: String) -> Unit,
    onOpenImage: (List<ViewerImage>, Int) -> Unit,
    onOpenDoc: (DocPreviewParams) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        RoomHeader(title = context.t("todo_title"), onBack = onBack)
        TodoScreenBody(
            rid = null,
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
                    // RN :101-116：跳原房（t 未知名值回退 c；标题 = item.name）
                    onGotoSession(item.rid!!, coerceRoomType(item.t), item.name.orEmpty(), item.mid)
                }
            },
            onOpenImage = onOpenImage,
            onOpenDoc = onOpenDoc,
        )
    }
}

/**
 * 双屏共用体（RN TodoListScreen 与 RoomTodoScreen 的 FlatList/空态/遮罩/选时器逐块同构）：
 * 差异只在 rid 过滤与「全部待办」入口（RoomTodo footer/空态）与 onGotoSession 同房保留参
 * （见 [RoomTodoScreen]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoScreenBody(
    rid: String?,
    repo: TodoListRepository,
    username: String,
    serverUrl: String,
    userId: String,
    token: String,
    kv: KvStore,
    modifier: Modifier = Modifier,
    onGotoSession: (TodoItem) -> Unit,
    onOpenImage: (List<ViewerImage>, Int) -> Unit,
    onOpenDoc: (DocPreviewParams) -> Unit,
    showAllTodosEntry: Boolean = false,
    onOpenAllTodos: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val scope = rememberCoroutineScope()
    val state by repo.observe(rid).collectAsState()
    val isMutating by repo.isMutating.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var pickerItem by remember { mutableStateOf<TodoItem?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // 进屏装载（RN react-query refetchOnMount：每次进屏重拉，mutation 跨屏失效后回屏即新）
    LaunchedEffect(rid) { repo.fetch(rid) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    repo.fetch(rid) // RN onRefresh → refetch（保留旧数据）
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = modifier.fillMaxWidth().testTag("qa-todo-list"),
    ) {
        if (state.items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.testTag("qa-todo-loading"))
                    state.isError -> {
                        Text(context.t("todo_loadfailed"), color = colors.auxiliaryText, fontSize = 14.sp)
                        Text(
                            context.t("todo_retry"),
                            color = colors.primary,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .clickable { scope.launch { repo.fetch(rid) } }
                                .padding(8.dp)
                                .testTag("qa-todo-retry"),
                        )
                    }
                    else -> Text(context.t("todo_empty"), color = colors.auxiliaryText, fontSize = 14.sp)
                }
                if (showAllTodosEntry && !state.isLoading) AllTodosButton(onOpenAllTodos)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.items, key = { it.id }) { item ->
                    TodoCard(
                        item = item,
                        roomDisplayName = todoRoomDisplayName(item.rid, item.name, username, serverUrl, context.t("Agent"), kv),
                        disabled = isMutating,
                        onComplete = {
                            scope.launch {
                                try {
                                    if (!repo.complete(item)) todoToast(context, "todo_operationfailed")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    todoToast(context, "todo_operationfailed")
                                }
                            }
                        },
                        onGotoSession = { onGotoSession(item) },
                        onOpenReminderPicker = {
                            pickerItem = item
                            pickerOpen = true
                        },
                        onOpenImage = onOpenImage,
                        onOpenDoc = onOpenDoc,
                        userId = userId,
                        token = token,
                        serverUrl = serverUrl,
                    )
                }
                if (showAllTodosEntry) {
                    item("all-todos") { AllTodosButton(onOpenAllTodos) }
                }
            }
        }
    }

    // mutation 全屏遮罩（RN :185-189：pointerEvents auto 挡操作）
    if (isMutating) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.backgroundColor.copy(alpha = 0.5f))
                .testTag("qa-todo-mutating"),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    TodoReminderPicker(
        open = pickerOpen,
        initialMillis = todoPickerInitialMillis(pickerItem),
        onConfirm = { millis ->
            pickerOpen = false
            val item = pickerItem
            pickerItem = null
            if (item == null) return@TodoReminderPicker
            // RN :79-82：过去时间拒提（不发请求）
            if (!isReminderInFuture(millis)) {
                todoToast(context, "todo_remindermustbefuture")
                return@TodoReminderPicker
            }
            scope.launch {
                try {
                    // RN date.toISOString()：UTC ISO（todoReminderIso 同形）
                    if (!repo.setReminder(item, cn.appia.im.feature.todo.todoReminderIso(millis))) {
                        todoToast(context, "todo_operationfailed")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    todoToast(context, "todo_operationfailed")
                }
            }
        },
        onCancel = {
            pickerOpen = false
            pickerItem = null
        },
    )
}

@Composable
private fun AllTodosButton(onOpen: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(onClick = onOpen)
            .testTag("qa-todo-all"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📅", fontSize = 16.sp)
        Spacer(Modifier.size(6.dp))
        Text(context.t("todo_alltodos"), color = Color(0xFF2878FF), fontSize = 14.sp)
    }
}

/** toast（RN showAppToast）：主线程弹出。 */
internal fun todoToast(context: android.content.Context, key: String) {
    Toast.makeText(context, context.t(key), Toast.LENGTH_SHORT).show()
}
