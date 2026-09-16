package cn.appia.im.feature.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.ReadStateWriter
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.SubscriptionsApi
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.i18n.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 已读态判定（逐行移植 RN subscriptionAppearsRead.ts:6-12）：
 * `!(archived !== true && open === true && (unread > 0 || alert === true))`。
 * M0 ChatEntity 列为非空 Boolean（RN 列非空对照），无可空分支。
 * 「已读态」决定左钮组是否含「标未读」、右钮是否含「标已读」（RN resolveRoomListReadSwipeButtons）。
 */
internal fun subscriptionAppearsRead(chat: ChatEntity): Boolean {
    val isUnread = !chat.archived && chat.open && (chat.unread > 0 || chat.alert)
    return !isUnread
}

/**
 * 列表快捷动作（RN lib/chat/subscriptionSwipeActions.ts 的 Kotlin 落法）。
 * 全部「先服务端后本地」：REST 失败上抛且不触 DB（AuthInterceptor 非 2xx 抛 ApiException，
 * 与 RN restClient.ts:76-91 同口径）；本地写前校验 active 库 == 本实例绑定 server（仓库守卫约定，
 * RN updateChatRow 写 getActiveDatabase() 不设守卫，此处取更严方向）。
 *
 * @param serverUrl 构造期绑定的目标 server（与 ChatListViewModel 同约定，换服后由会话层重建实例）。
 */
class ChatRowActions(
    private val sdk: RocketSdk,
    private val dbManager: DatabaseManager,
    private val serverUrl: String,
) {
    private val db = dbManager.databaseFor(dbManager.normalizeServer(serverUrl))

    private fun activeDbMatchesAuth(): Boolean =
        serverUrl.isNotBlank() && dbManager.active === db

    /**
     * 标已读（RN markRoomReadOnServerAndLocal → readMessages :85-94 顺序）：
     * 先 `POST subscriptions.read {rid}`，成功后双表写（ReadStateWriter 单点，T10 房间内标读复用）。
     * `updateLastOpen=true` 另写 `lastOpen=now`（T10 进房即读，RN readMessages options 同名透传）。
     */
    suspend fun markRoomRead(
        rid: String,
        now: Long = System.currentTimeMillis(),
        updateLastOpen: Boolean = false,
    ) {
        SubscriptionsApi.postSubscriptionsRead(sdk, rid)
        if (!activeDbMatchesAuth()) return
        ReadStateWriter(db).applyReadState(rid, now, updateLastOpen)
    }

    /**
     * 标未读（RN markRoomUnreadOnServer）：只发 `POST subscriptions.unread {roomId}`。
     * 本地零改动（brief 绑定裁定 1）：未读态由 stream 回推统一落库，本地抢先写会与回推竞争。
     */
    suspend fun markRoomUnread(rid: String) {
        SubscriptionsApi.postSubscriptionsUnread(sdk, rid)
    }

    /** 置顶切换（RN setRoomFavoriteOnServerAndLocal :32-40）：`rooms.favorite` 成功后本地写 `chats.f`。 */
    suspend fun setRoomFavorite(rid: String, favorite: Boolean) {
        SubscriptionsApi.postRoomsFavorite(sdk, rid, favorite)
        if (!activeDbMatchesAuth()) return
        db.chatDao().getById(rid)?.let { db.chatDao().update(it.copy(f = favorite)) }
    }
}

/** RN roomRowSharedStyles.ts ACTION_WIDTH = 80（dp）。 */
private val ACTION_WIDTH = 80.dp

/**
 * 可左右滑的列表行（RN RoomChatItem/index.tsx:190-308 Swipeable 壳的 Compose 落法）。
 *
 * - 右滑（行右移）露左钮组：标未读（仅已读态显示）+ 置顶切换；左滑露右钮：标已读（仅未读态显示）。
 *   与 RN renderLeftActions/renderRightActions 的按钮侧一致（RN 以按钮侧命名，未读+置顶在左侧组）。
 * - 侧滑壳常驻：动作层永远在树里（底色钮壳）；图标/文案首次拖拽才组装（RN swipeActionsReady :55-56
 *   防 fling 批量挂 SVG 的同款性能细节），行身份（_id）变更时复位。
 * - 未读态左滑方向无可露按钮：拖拽被钳制在 0，等效 RN 的空占位 + onSwipeableOpen 自动回弹（:281-286/:303-305）。
 * - Foundation `draggable` 而非 `anchoredDraggable`：单值偏移 + 两向独立宽度钳制，
 *   免 ExperimentalFoundationApi 且无需锚点表（回弹/展开用一次性 animate，RN friction 手感简化为阈值判定）。
 *
 * T11 装配锚点：LazyColumn `items(...)` **必须 keyed（key = chat._id）**——本组件的滑出偏移
 * 与 actionsReady 按行身份 remember(chat._id) 重建，只兜行复用换数据；不 key 的装配在行回收
 * 时仍可能让新 chat 继承上一行的滑出偏移（露着别人的按钮）。
 */
@Composable
fun SwipeableChatRow(
    chat: ChatEntity,
    currentUserId: String?,
    avatarUrl: String?,
    onMarkRead: () -> Unit = {},
    onMarkUnread: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onPress: (ChatEntity) -> Unit = {},
    onLongPress: (ChatEntity) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val appearsRead = subscriptionAppearsRead(chat)
    val showMarkUnread = appearsRead // RN resolveRoomListReadSwipeButtons：互斥两钮
    val showMarkRead = !appearsRead
    val leftWidthPx = with(density) { (ACTION_WIDTH * (if (showMarkUnread) 2 else 1)).toPx() }
    val rightWidthPx = with(density) { (if (showMarkRead) ACTION_WIDTH else 0.dp).toPx() }

    // 滑出状态全部按行身份 remember（评审 Important：防 LazyColumn 行回收时新 chat 继承旧偏移）
    var offset by remember(chat._id) { mutableFloatStateOf(0f) }
    var settleJob by remember(chat._id) { mutableStateOf<Job?>(null) }
    // RN :55-56/:70-72：壳常驻、内容延迟挂载，行身份变更复位
    var actionsReady by remember(chat._id) { mutableStateOf(false) }

    fun close() {
        settleJob?.cancel()
        settleJob = scope.launchAnimateTo(offset, 0f, onEach = { offset = it })
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("chat-row-swipe"),
    ) {
        // 动作层：matchParentSize 跟随内容行尺寸，不参与测量（壳常驻）
        Row(Modifier.matchParentSize()) {
            if (showMarkUnread) {
                SwipeActionButton(
                    label = context.t("roomItem_swipeMarkUnread"),
                    background = colors.tintColor,
                    contentReady = actionsReady,
                    onClick = { close(); onMarkUnread() },
                )
            }
            SwipeActionButton(
                label = if (chat.f) context.t("roomItem_swipeUnfavorite") else context.t("roomItem_swipeFavorite"),
                background = colors.favoriteBackground,
                contentReady = actionsReady,
                onClick = { close(); onToggleFavorite() },
            )
            Spacer(Modifier.weight(1f))
            if (showMarkRead) {
                SwipeActionButton(
                    label = context.t("roomItem_swipeMarkRead"),
                    background = colors.tintColor,
                    contentReady = actionsReady,
                    onClick = { close(); onMarkRead() },
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        settleJob?.cancel()
                        offset = (offset + delta).coerceIn(-rightWidthPx, leftWidthPx)
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { actionsReady = true },
                    onDragStopped = {
                        val target = when {
                            offset > leftWidthPx / 2f -> leftWidthPx
                            offset < -rightWidthPx / 2f -> -rightWidthPx
                            else -> 0f
                        }
                        settleJob = scope.launchAnimateTo(offset, target, onEach = { offset = it })
                    },
                ),
        ) {
            ChatRow(
                chat = chat,
                currentUserId = currentUserId,
                avatarUrl = avatarUrl,
                onPress = onPress,
                onLongPress = onLongPress,
            )
        }
    }
}

/** 展开判定：过半吸附展开、否则回弹（RN Swipeable rightThreshold 的简化：一次性 animate）。 */
private fun CoroutineScope.launchAnimateTo(
    from: Float,
    to: Float,
    onEach: (Float) -> Unit,
): Job = launch {
    androidx.compose.animation.core.animate(
        initialValue = from,
        targetValue = to,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
    ) { value, _ -> onEach(value) }
}

/**
 * 单个滑出动作钮（RN renderLeftActions 的 RectButton）：底色壳常驻、可点性随 contentReady；
 * label（RN 图标+文案）首次拖拽后才组装（RN :55-56 性能细节，Compose 无 SVG 批挂问题，仅沿用语义）。
 */
@Composable
private fun SwipeActionButton(
    label: String,
    background: Color,
    contentReady: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(ACTION_WIDTH)
            .fillMaxHeight()
            .background(background)
            .clickable(enabled = contentReady, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (contentReady) {
            Text(
                label,
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
