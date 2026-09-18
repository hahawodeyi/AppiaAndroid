package cn.appia.im.feature.chat.forward

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.api.ForwardSearchRow
import cn.appia.im.core.theme.LocalAppiaColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "forwardSelect"

/**
 * 转发选择页（RN screens/ForwardSelectScreen 同构）：搜索框 + 3 tab（最近会话 + 双组织树）+
 * 两 Set 选中（rooms/usernames，合并计 MAX 10）+ 底栏计数与确认。
 * 组织树 tab 降级：联系人域属 M4，PMT/L1D 暂以最近会话列表占位（见 progress 报告）。
 * 搜索态盖过 tab（RN :646-670 同构）：300ms debounce（[ForwardSearcher]）。
 */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ForwardSearcher(
    private val search: suspend (String) -> List<ForwardSearchRow>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    private val query = MutableStateFlow("")

    /** RN useForwardSearch 语义：空词 → 立即清空不加载；非空 → loading 即刻 + 300ms debounce 取结果（失败清空）。 */
    val state: StateFlow<ForwardSearchState> = query.flatMapLatest { q ->
        flow {
            if (q.isBlank()) {
                emit(ForwardSearchState(loading = false, rows = emptyList()))
            } else {
                emit(ForwardSearchState(loading = true, rows = emptyList()))
                delay(300)
                val rows = runCatching { search(q.trim()) }.getOrElse { emptyList() }
                emit(ForwardSearchState(loading = false, rows = rows))
            }
        }
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        ForwardSearchState(loading = false, rows = emptyList()),
    )

    fun onQueryChanged(text: String) {
        query.value = text
    }
}

data class ForwardSearchState(val loading: Boolean, val rows: List<ForwardSearchRow>)

private val TAB_LABEL_KEYS =
    listOf("createchannelmembers_sourcerecent", "forward_tab_pmt", "forward_tab_l1d")

/** 显示名（RN ChatItem :180：fname || dname || name || rid）。 */
internal fun chatDisplayName(chat: ChatEntity): String =
    chat.fname.takeIf { it.isNotEmpty() }
        ?: chat.dname?.takeIf { it.isNotEmpty() }
        ?: chat.name.takeIf { it.isNotEmpty() }
        ?: chat.rid

@Composable
fun ForwardSelectScreen(
    messageIds: List<String>,
    isMerged: Boolean,
    chats: List<ChatEntity>,
    currentUserId: String?,
    searcher: ForwardSearcher,
    onForward: suspend (users: List<String>, rooms: List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var selectedRids by remember { mutableStateOf(emptySet<String>()) }
    var selectedUserIds by remember { mutableStateOf(emptySet<String>()) }
    var sending by remember { mutableStateOf(false) }

    val searchState by searcher.state.collectAsState()
    val isSearching = searchText.trim().isNotEmpty()
    val totalSelected = selectedRids.size + selectedUserIds.size

    // RN toggleChat/toggleOrgUsername :265-295：两 Set 合并计 MAX 10——移除不限、新增达上限即忽略
    fun toggleRid(rid: String) {
        selectedRids = selectedRids.toMutableSet().apply {
            if (rid in this) remove(rid)
            else if (selectedRids.size + selectedUserIds.size < FORWARD_MAX_SELECT) add(rid)
        }
    }

    fun toggleUser(username: String) {
        selectedUserIds = selectedUserIds.toMutableSet().apply {
            if (username in this) remove(username)
            else if (selectedRids.size + selectedUserIds.size < FORWARD_MAX_SELECT) add(username)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        // 搜索框
        TextField(
            value = searchText,
            onValueChange = {
                searchText = it
                searcher.onQueryChanged(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("qa-forward-search-input"),
            placeholder = { Text(context.t("forward_search_placeholder"), color = colors.auxiliaryText) },
            singleLine = true,
        )

        // tab 栏（搜索态隐藏，RN :646）
        if (!isSearching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TAB_LABEL_KEYS.forEachIndexed { index, key ->
                    val active = activeTab == index
                    Text(
                        context.t(key),
                        color = if (active) colors.tintColor else colors.auxiliaryText,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                activeTab = index
                                searchText = ""
                                searcher.onQueryChanged("")
                            }
                            .padding(vertical = 8.dp)
                            .testTag("qa-forward-tab-$index"),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                isSearching && searchState.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.testTag("forward-search-loading"))
                    }

                isSearching && searchState.rows.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            context.t("forward_search_empty"),
                            color = colors.auxiliaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.testTag("qa-forward-search-empty"),
                        )
                    }

                isSearching -> LazyColumn(Modifier.fillMaxSize()) {
                    items(searchState.rows, key = { it.key }) { row ->
                        val checked = when (row) {
                            is ForwardSearchRow.User -> row.username in selectedUserIds
                            is ForwardSearchRow.Room -> row.rid in selectedRids
                        }
                        ForwardSelectRow(
                            title = when (row) {
                                is ForwardSearchRow.User -> row.displayName
                                is ForwardSearchRow.Room -> row.title
                            },
                            subtitle = (row as? ForwardSearchRow.User)?.subtitle,
                            checked = checked,
                            fallback = when (row) {
                                is ForwardSearchRow.User -> row.username
                                is ForwardSearchRow.Room -> row.title
                            },
                            modifier = Modifier.testTag(
                                when (row) {
                                    is ForwardSearchRow.User -> "forward-search-row-user-${row.username}"
                                    is ForwardSearchRow.Room -> "forward-search-row-room-${row.rid}"
                                },
                            ),
                            onToggle = {
                                when (row) {
                                    is ForwardSearchRow.User -> toggleUser(row.username)
                                    is ForwardSearchRow.Room -> toggleRid(row.rid)
                                }
                            },
                        )
                    }
                }

                else ->
                    // 三个 tab 均出最近会话（PMT/L1D 组织树归 M4 联系人域——降级占位）
                    LazyColumn(Modifier.fillMaxSize().testTag("qa-forward-list")) {
                        items(chats, key = { it.rid }) { chat ->
                            ForwardSelectRow(
                                title = chatDisplayName(chat),
                                subtitle = null,
                                checked = chat.rid in selectedRids,
                                fallback = chatDisplayName(chat),
                                modifier = Modifier.testTag("qa-forward-recent-row-${chat.rid}"),
                                onToggle = { toggleRid(chat.rid) },
                            )
                        }
                    }
            }
        }

        // 底栏：计数 + 确认
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.messageboxBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (totalSelected > 0) {
                    interpolate(
                        context.t("forward_selected_count"),
                        mapOf("count" to totalSelected.toString(), "max" to FORWARD_MAX_SELECT.toString()),
                    )
                } else {
                    context.t("forward_select_hint")
                },
                color = colors.auxiliaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                context.t("todo_confirm"),
                color = if (totalSelected > 0 && !sending) colors.tintColor else colors.auxiliaryText,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable(enabled = totalSelected > 0 && !sending) {
                        scope.launch {
                            sending = true
                            val targets = buildForwardTargets(selectedRids, selectedUserIds, chats, currentUserId)
                            val ok = runCatching { onForward(targets.users, targets.rooms) }
                                .onFailure { Log.w(TAG, "forward message failed", it) }
                                .isSuccess
                            sending = false
                            if (ok) onBack() // RN :383-384：成功才退出（失败留在本页）
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("qa-forward-confirm"),
            )
        }
    }
}

/** 勾选行：checkbox + 头像占位（首字母圆形）+ 标题/副标题。 */
@Composable
private fun ForwardSelectRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    fallback: String,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.chatComponentBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(fallback.take(1), color = colors.auxiliaryText, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = colors.titleText, fontSize = 15.sp, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = colors.auxiliaryText, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
