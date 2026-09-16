package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.chat.DraftController
import cn.appia.im.feature.chat.RoomMessagesUiState
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.formatMessageDateLabel
import cn.appia.im.core.util.isSameCalendarDay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** RN messageTypeLoad：load_chunk 渲染 1px 空行。 */
private val LOAD_CHUNK_TYPES = setOf("load-more-before", "load-more-after")

/** 列表项两型：消息（含系统/1px chunk）与日期分隔（contentType 三型在此派生）。 */
internal sealed class RoomListItem {
    abstract val key: String

    data class Message(val message: MessageEntity) : RoomListItem() {
        override val key: String get() = message._id
    }

    data class DateSeparator(val tsMs: Long, val anchorId: String) : RoomListItem() {
        override val key: String get() = "sep-$anchorId"
    }
}

internal fun RoomListItem.contentType(): String = when (this) {
    is RoomListItem.Message ->
        if (isSystemMessageRow(message)) "system" else "msg"
    is RoomListItem.DateSeparator -> "separator"
}

/** 输入变更（含 IME 组合态守卫）：组合期不写草稿，commit 后才计 debounce；返回新输入态。 */
internal fun onRoomInputChanged(rid: String, next: TextFieldValue, controller: DraftController?): TextFieldValue {
    if (next.composition == null) controller?.onTextChanged(rid, next.text)
    return next
}

/**
 * RN RoomMessageList renderItem 派生：与上一条（inverted index+1，即列表后一位的更旧消息）
 * 非同一天插日期分隔（含列表末条——无更旧消息必插）；分隔标签取当前消息 ts（RN 同）。
 */
internal fun buildRoomListItems(messages: List<MessageEntity>): List<RoomListItem> {
    val out = mutableListOf<RoomListItem>()
    for ((i, m) in messages.withIndex()) {
        out += RoomListItem.Message(m)
        val older = messages.getOrNull(i + 1)
        if (older == null || !isSameCalendarDay(older.ts.toLong(), m.ts.toLong())) {
            out += RoomListItem.DateSeparator(tsMs = m.ts.toLong(), anchorId = m._id)
        }
    }
    return out
}

/**
 * RoomScreen（RN screens/RoomScreen 的 M2 版：消息列表/输入/头部）。
 * 有 t 且非 announcement 的消息走 SystemMessageText；普通行（含 announcement 三型，=RN）走 MessageRow。
 * 空态：初始加载菊花 / `room_no_messages`；滚到底按钮在不在列表顶（最新处）时显示（简化：
 * 不区分"新消息到达"与"回看历史"，见任务报告）。
 * 草稿：IME 组合态不写（onValueChange 过滤 composition != null），commit 后 debounce 1s，
 * blur 即写，离开本屏 flush，发送成功清四列。
 *
 * **T11 锚点（已在 MainActivity RoomRoute 装配处接线）**：进房
 * `RoomStreamManager.subscribeRoom(rid)`（实时消息流；T6 重连重订已挂 manager 内）+
 * `RoomReadMarker`（onEnter/onLeave 挂 DisposableEffect(rid)、onMessagePersisted 接
 * incomingMessages 收集）。本屏只消费 DB 窗口流。
 */
@Composable
fun RoomScreen(
    rid: String,
    title: String,
    state: RoomMessagesUiState,
    currentUserId: String?,
    currentUsername: String?,
    serverUrl: String,
    token: String?,
    draftController: DraftController?,
    onSend: suspend (String) -> Unit,
    onResend: (MessageEntity) -> Unit,
    onBack: () -> Unit,
    onLoadEarlier: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items = remember(state.messages) { buildRoomListItems(state.messages) }
    val latestState by rememberUpdatedState(state)
    val latestOnLoadEarlier by rememberUpdatedState(onLoadEarlier)

    // RN onEndReached（inverted 列表末端 = 最旧）：接近列表顶端触发 loadEarlier
    LaunchedEffect(listState, items) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (items.isNotEmpty() && lastVisible >= items.size - 3 &&
                    latestState.hasMoreEarlier && !latestState.isLoadingEarlier
                ) {
                    latestOnLoadEarlier()
                }
            }
    }

    // 输入与草稿（RN ChatInputBar + useDraft 时序；组合态不写，commit 才计）
    var input by remember(rid) { mutableStateOf(TextFieldValue("")) }
    // 失焦即写需真实 focus→blur 跃迁：onFocusChanged 首次合成会以未聚焦态上报，须有曾聚焦守卫
    var inputHadFocus by remember(rid) { mutableStateOf(false) }
    LaunchedEffect(rid, draftController) {
        draftController?.let { c ->
            val saved = c.loadDraft(rid)
            if (saved.isNotEmpty()) input = TextFieldValue(saved)
        }
    }
    DisposableEffect(rid) {
        onDispose { draftController?.flushOnDispose(rid) }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = title, onBack = onBack)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.isInitialLoading) {
                        CircularProgressIndicator(Modifier.testTag("qa-room-message-list-loading"))
                    } else {
                        Text(
                            context.t("room_no_messages"),
                            color = colors.auxiliaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.testTag("qa-room-message-list-empty"),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true, // RN inverted：index 0 = 最新 = 底部
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("qa-room-message-list"),
                ) {
                    items(items, key = { it.key }, contentType = { it.contentType() }) { item ->
                        when (item) {
                            is RoomListItem.Message -> when {
                                item.message.t in LOAD_CHUNK_TYPES ->
                                    Box(Modifier.fillMaxWidth().height(1.dp)) // RN load_chunk 1px
                                isSystemMessageRow(item.message) ->
                                    SystemMessageText(item.message)
                                else ->
                                    MessageRow(
                                        message = item.message,
                                        currentUserId = currentUserId,
                                        currentUsername = currentUsername,
                                        serverUrl = serverUrl,
                                        token = token,
                                        onResend = onResend,
                                    )
                            }
                            is RoomListItem.DateSeparator -> DateSeparator(item.tsMs)
                        }
                    }
                }

                // 滚到底：不在最新处即显示（简化实现）
                if (listState.firstVisibleItemIndex > 0) {
                    Text(
                        "↓",
                        color = colors.tintColor,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.chatComponentBackground)
                            .wrapContentSize(Alignment.Center)
                            .clickable { scope.launch { listState.animateScrollToItem(0) } }
                            .testTag("qa-room-scroll-to-bottom"),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.messageboxBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = input,
                onValueChange = { v ->
                    // IME 组合态（拼音/滑行预提交）不触发草稿保存抖动；commit 后才计
                    input = onRoomInputChanged(rid, v, draftController)
                },
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 320.dp)
                    // RN ChatInputBar blur → saveDraftImmediate：失焦即写草稿（Important-1 接线）
                    .onFocusChanged {
                        if (it.isFocused) inputHadFocus = true
                        else if (inputHadFocus) {
                            inputHadFocus = false
                            draftController?.onBlur(rid, input.text)
                        }
                    }
                    .testTag("qa-room-input"),
                placeholder = { Text(context.t("chatinput_placeholder"), color = colors.auxiliaryText) },
                maxLines = 5,
            )
            Text(
                "↑",
                color = if (input.text.isNotBlank()) colors.tintColor else colors.auxiliaryText,
                fontSize = 22.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(40.dp)
                    .wrapContentSize(Alignment.Center)
                    .clickable(enabled = input.text.isNotBlank()) {
                        val text = input.text
                        scope.launch {
                            onSend(text) // SendOrchestrator.enqueueTextMessage（纯文本）
                            input = TextFieldValue("") // 发送成功清输入
                            draftController?.clearAfterSend(rid) // 四列清（RN clearDraft）
                        }
                    }
                    .testTag("qa-room-send"),
            )
        }
    }
}

/** RN MessageDateSeparator：线 + `yyyy年M月d日` + 线。 */
@Composable
private fun DateSeparator(tsMs: Long) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("qa-message-date-separator"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderColor))
        Text(
            formatMessageDateLabel(tsMs),
            color = colors.auxiliaryText,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.borderColor))
    }
}

/**
 * 路由装配依赖束原 `RoomScreenDeps` 已于 T11 更名 [cn.appia.im.RouteDeps] 并移入 MainActivity.kt
 * （同时服务 Main/Room 两路由）；进房接线（subscribeRoom/已读标记/草稿 flush 的生命周期编排）见
 * MainActivity RoomRoute 装配处与 RoomScreen KDoc 的 T11 锚点说明。
 */
