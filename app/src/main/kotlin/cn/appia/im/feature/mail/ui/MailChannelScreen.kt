package cn.appia.im.feature.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.Colors
import cn.appia.im.feature.mail.MAIL_LIST_ESTIMATED_ITEM_SIZE
import cn.appia.im.feature.mail.MailChannelUiState
import cn.appia.im.feature.mail.MailChannelViewModel
import cn.appia.im.feature.mail.createDraftsVirtualFolder
import cn.appia.im.feature.mail.isDraftsFolderPath
import cn.appia.im.feature.mail.resolveMailFolderLabel
import cn.appia.im.feature.mail.shouldShowFolder
import cn.appia.im.feature.chat.ui.RoomHeader
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 收件箱（RN MailChannelScreen/index.tsx 移植）：
 * - 列表**升序、锚定底部**（坑 9）：LazyColumn 数据旧→新；首载/切夹/刷新后 scrollToItem(末位)
 *   （RN initialScrollIndex + scrollToEnd 锚定链）；onStartReached 语义 = 顶部接近 → 翻旧页
 * - 下拉刷新：PullToRefreshBox（ChatListScreen 同款）；syncing 状态条「email · 同步中…」
 * - 搜索：头部 ⌕ 切换（草稿夹隐藏）；未读开关（搜索条 + 文件夹弹层双入口）
 * - 状态分派：初始 loading / loadError / 空态（草稿/普通）→ 居中文案
 * - 无账户 → onSetupRequired（RN navigation.replace('MailAccountSetup')）
 * - 卡片动作：onOpenDetail/onReply/onReplyAll/onViewThread/onCompose = T4/T5 路由接线缝
 *
 * markChannelVisited（角标清零）是 T6 MailBadgeStore 职责——此处留 LaunchedEffect(Unit)
 * 挂点由 MainActivity 注入回调，默认无操作。
 */
@Composable
fun MailChannelScreen(
    viewModel: MailChannelViewModel,
    onBack: () -> Unit,
    onSetupRequired: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onReply: (String) -> Unit,
    onReplyAll: (String) -> Unit,
    onViewThread: (String) -> Unit,
    onCompose: () -> Unit,
    onEditDraft: (String) -> Unit,
    onChannelVisited: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val isDraftsFolder = isDraftsFolderPath(state.activeFolder)

    // RN useFocusEffect markChannelVisited（:391-394）：T6 注入真实角标清零
    LaunchedEffect(Unit) { onChannelVisited() }

    // 无账户 → 绑定页（一次性跳转；setupRequired 粘滞防重复触发）
    LaunchedEffect(state.setupRequired) {
        if (state.setupRequired) onSetupRequired()
    }

    // ── 升序锚定底部（坑 9：RN initialScrollIndex + markInitialAnchorDone 链）──
    val listState = rememberLazyListState()
    val items = if (isDraftsFolder) state.drafts else state.messages
    // 溢出判定：itemCount × 估算行高 > 视口高（RN resolveMailListInitialScrollIndex：
    // 不溢出 → undefined 即不滚动；溢出 → itemCount-1）
    var viewportHeightPx by remember { mutableStateOf(0) }
    val overflowsViewport = items.size * MAIL_LIST_ESTIMATED_ITEM_SIZE > viewportHeightPx
    var lastAnchoredRequest by remember { mutableStateOf(0) }
    // 草稿删除确认（RN Alert.alert mail_delete_draft_confirm）——声明前置供卡片回调
    var confirmDeleteDraft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.anchorRequest, items.size, state.loading) {
        if (state.loading || state.anchorRequest == 0) return@LaunchedEffect
        if (state.anchorRequest == lastAnchoredRequest) return@LaunchedEffect
        lastAnchoredRequest = state.anchorRequest
        if (items.isEmpty()) {
            viewModel.onInitialAnchored()
        } else if (!overflowsViewport) {
            viewModel.onInitialAnchored()
        } else {
            listState.scrollToItem(items.size - 1)
            // 二帧确认（RN requestAnimationFrame 双跳同义）后放行 loadMore
            listState.scrollToItem(items.size - 1)
            viewModel.onInitialAnchored()
        }
    }

    // onStartReached 语义（RN :674-678）：顶部接近（首项索引 0 且锚定已完成）→ 翻旧页
    LaunchedEffect(listState, isDraftsFolder) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { first ->
                if (first <= 0 && !isDraftsFolder) viewModel.onLoadMore()
            }
    }

    var folderSheetOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Colors.bgTertiary)) {
        // ── 头部：标题 + 文件夹 chip ▾（点开弹层）；右侧 ✎ 撰写 + ⌕ 搜索（草稿夹隐藏）──
        RoomHeader(
            title = context.t("mail_list_title"),
            onBack = onBack,
            headerAction = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "✎",
                        color = Colors.textPrimary,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(32.dp)
                            .wrapContentSize(Alignment.Center)
                            .clickable(onClick = onCompose)
                            .testTag("mail-compose-toggle"),
                    )
                    if (!isDraftsFolder) {
                        Text(
                            "⌕",
                            color = if (state.searchVisible) Colors.primary else Colors.textPrimary,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .size(32.dp)
                                .wrapContentSize(Alignment.Center)
                                .clickable(onClick = viewModel::toggleSearch)
                                .testTag("mail-search-toggle"),
                        )
                    }
                }
            },
            onTitleClick = { folderSheetOpen = true },
        )
        // 标题下文件夹 chip（RN headerTitle 下 folderChip：`{label} ▾`，testID mail-folder-chip）
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                activeFolderLabel(state, context::t) + " ▾",
                fontSize = 12.sp,
                color = Colors.primary,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clickable { folderSheetOpen = true }
                    .testTag("mail-folder-chip"),
            )
        }

        // ── 状态条：account.email（+ 同步中）──
        state.account?.let { account ->
            Column(Modifier.fillMaxWidth().background(Colors.bgBase)) {
                Text(
                    if (state.syncing) "${account.email} · ${context.t("mail_syncing")}" else account.email,
                    fontSize = 12.sp,
                    color = Colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(thickness = 1.dp, color = Colors.borderLight)
            }
        }

        // ── 搜索条（可见且非草稿夹）：输入框 + 未读开关 ──
        if (state.searchVisible && !isDraftsFolder) {
            Column(
                Modifier.fillMaxWidth().background(Colors.bgBase).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Colors.textPrimary),
                    cursorBrush = SolidColor(Colors.primary),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().height(36.dp)
                                .background(Colors.bgBase, RoundedCornerShape(8.dp))
                                .borderLine()
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.searchQuery.isEmpty()) {
                                Text(context.t("mail_search_placeholder"), fontSize = 14.sp, color = Colors.textTertiary)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("mail-list-search-input"),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(context.t("mail_unread_only"), fontSize = 13.sp, color = Colors.textPrimary)
                    Switch(
                        checked = state.unreadOnly,
                        onCheckedChange = viewModel::onUnreadOnlyChanged,
                        modifier = Modifier.testTag("mail-list-unread-switch"),
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = Colors.borderLight)
        }

        // ── 主体四态：初始 loading / 错误 / 空 / 列表 ──
        val showInitialLoader = state.loading && items.isEmpty()
        val listEmpty = !state.loading && !state.loadError && items.isEmpty()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::onRefresh,
                modifier = Modifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height },
            ) {
                when {
                    showInitialLoader -> CenterState { Text(context.t("mail_loading"), fontSize = 14.sp, color = Colors.textTertiary) }
                    state.loadError -> CenterState {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(context.t("mail_load_failed"), fontSize = 14.sp, color = Colors.textTertiary)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                context.t("mail_retry"),
                                fontSize = 14.sp,
                                color = Colors.primary,
                                modifier = Modifier.clickable(onClick = viewModel::onRefresh),
                            )
                        }
                    }
                    listEmpty -> CenterState {
                        Text(
                            context.t(if (isDraftsFolder) "mail_empty_drafts" else "mail_empty"),
                            fontSize = 14.sp,
                            color = Colors.textTertiary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag(if (isDraftsFolder) "mail-draft-list" else "mail-message-list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                        // 翻旧页顶部 spinner（RN ListHeaderComponent loadingMore）
                        if (state.loadingMore) {
                            item("loading-more") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Colors.primary)
                                }
                            }
                        }
                        if (isDraftsFolder) {
                            items(state.drafts.size, key = { state.drafts[it].id }) { index ->
                                val draft = state.drafts[index]
                                MailDraftCard(
                                    draft = draft,
                                    onEdit = onEditDraft,
                                    onDelete = { confirmDeleteDraft = draft.id },
                                )
                            }
                        } else {
                            items(state.messages.size, key = { state.messages[it].id }) { index ->
                                val message = state.messages[index]
                                MailMessageCard(
                                    message = message,
                                    onOpenDetail = onOpenDetail,
                                    onReply = onReply,
                                    onReplyAll = onReplyAll,
                                    onViewThread = onViewThread,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 草稿删除确认（RN Alert.alert mail_delete_draft_confirm）──
    if (confirmDeleteDraft != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteDraft = null },
            title = { Text(context.t("mail_delete_draft_confirm")) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.deleteDraft(confirmDeleteDraft!!)
                    confirmDeleteDraft = null
                }) { Text(context.t("mail_delete"), color = Colors.danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteDraft = null }) {
                    Text(context.t("cancel"))
                }
            },
        )
    }

    // ── 文件夹弹层（displayFolders：可见夹 + 草稿非空前置虚拟 __drafts__）──
    if (folderSheetOpen && state.account != null) {
        val displayFolders = remember(state.folders, state.drafts.size) {
            val visible = state.folders.filter(::shouldShowFolder)
            if (state.drafts.isEmpty()) visible else listOf(createDraftsVirtualFolder()) + visible
        }
        MailFolderSheet(
            accountEmail = state.account!!.email,
            folders = displayFolders,
            activeFolder = state.activeFolder,
            unreadOnly = state.unreadOnly,
            onUnreadOnlyChange = viewModel::onUnreadOnlyChanged,
            onSelectFolder = viewModel::onSelectFolder,
            onDismiss = { folderSheetOpen = false },
            resolveFolderLabel = { resolveMailFolderLabel(it, context::t) },
        )
    }
}

/** 当前夹标签（RN activeFolderLabel：displayFolders 命中或 path 直解；i18n 经 Context）。 */
@Composable
private fun activeFolderLabel(state: MailChannelUiState, t: (String) -> String): String {
    val folder = state.folders.firstOrNull { it.path == state.activeFolder }
    return resolveMailFolderLabel(
        folder ?: cn.appia.im.core.mail.MailFolder(path = state.activeFolder, name = state.activeFolder),
        t,
    )
}

/** 居中态容器（RN centerState）。 */
@Composable
private fun CenterState(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/** 搜索框 1dp 边框简写。 */
private fun Modifier.borderLine(): Modifier =
    this.border(androidx.compose.foundation.BorderStroke(1.dp, Colors.border), RoundedCornerShape(8.dp))
