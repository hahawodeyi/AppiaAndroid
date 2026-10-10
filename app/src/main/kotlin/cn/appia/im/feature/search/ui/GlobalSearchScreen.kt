package cn.appia.im.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chatlist.chatAvatarUrl
import cn.appia.im.feature.search.GlobalSearchFileRow
import cn.appia.im.feature.search.GlobalSearchListRow
import cn.appia.im.feature.search.GLOBAL_SEARCH_PREVIEW_LIMIT
import cn.appia.im.feature.search.GlobalSearchTab
import cn.appia.im.feature.search.GlobalSearchUiState
import cn.appia.im.feature.search.MessageSearchRow
import cn.appia.im.feature.search.interpolate
import coil3.compose.AsyncImage

private val AVATAR_SIZE = 44.dp

/**
 * 全局搜索 4 tab 屏（RN screens/GlobalSearchScreen/index.tsx）：
 * - all：成员/消息/文件三分区，3 条预览 + 查看更多折叠
 * - members：合并成员+频道全列表；messages：完整消息房列表；files：cursor 分页
 * - 点击：contact → openDirectMessage 链（回调参数化）；频道/房间 → 平跳（tSearch bump 为 T8）
 * - banner error：远端失败本地分区回退提示
 */
@Composable
fun GlobalSearchScreen(
    initialQuery: String,
    state: GlobalSearchUiState,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onQueryChanged: (String) -> Unit,
    onLoadMoreFiles: () -> Unit,
    onOpenContact: (username: String, title: String, knownRid: String) -> Unit,
    onOpenRoom: (rid: String, title: String, roomType: String) -> Unit,
    onOpenMessageDetail: (row: MessageSearchRow) -> Unit,
    onOpenFile: (file: GlobalSearchFileRow) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    // 输入框初值 = VM state.query（深链词由 VM 构造器同步种入 state，t0 即见；旋转后
    // remember 重建也回读 VM 真值——清空/改词不会被复活，backlog #10 / 评审 I-1）
    var query by remember { mutableStateOf(state.query) }
    var activeTab by remember { mutableStateOf(GlobalSearchTab.ALL) }
    var expandedMembers by remember { mutableStateOf(false) }
    var expandedMessages by remember { mutableStateOf(false) }
    var expandedFiles by remember { mutableStateOf(false) }

    val trimmedQuery = query.trim()
    val previewCount = state.messagePreviewRows.size
    val messagesTotalCount = state.messageFullRows?.size ?: previewCount
    val messagesSectionHasMore = state.messagesPreviewHasMore ||
        previewCount > GLOBAL_SEARCH_PREVIEW_LIMIT ||
        messagesTotalCount > GLOBAL_SEARCH_PREVIEW_LIMIT
    val messageRows = state.messageFullRows ?: state.messagePreviewRows

    // RN isSearchPending（:197-203）：loading / 防抖未结束 / 结果未就绪 / messages tab 完整段未回
    val isMessagesReady = activeTab != GlobalSearchTab.MESSAGES || state.messageFullRows != null
    val isSearchPending = trimmedQuery.isNotEmpty() && (
        state.loading ||
            trimmedQuery != state.query ||
            !state.isResultsReady ||
            (activeTab == GlobalSearchTab.MESSAGES && state.isResultsReady && !isMessagesReady)
        )
    val showSearchResults = trimmedQuery.isNotEmpty() && !isSearchPending

    val hasMemberResults = state.memberRows.isNotEmpty()
    val hasMessageResults = previewCount > 0 || messageRows.isNotEmpty() || messagesSectionHasMore
    val hasFileResults = state.files.isNotEmpty()
    val showNoResultsEmpty = showSearchResults && state.query.isNotEmpty() && when (activeTab) {
        GlobalSearchTab.ALL -> !hasMemberResults && !hasMessageResults && !hasFileResults
        GlobalSearchTab.MEMBERS -> !hasMemberResults
        GlobalSearchTab.MESSAGES -> !hasMessageResults && isMessagesReady
        GlobalSearchTab.FILES -> !hasFileResults
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .statusBarsPadding()
            .testTag("qa-global-search-screen"),
    ) {
        // 头部：返回 + 搜索框 + 清空
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = colors.headerTintColor,
                fontSize = 22.sp,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(8.dp)
                    .testTag("qa-global-search-back"),
            )
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChanged(it)
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("qa-global-search-input"),
                placeholder = { Text(context.t("roomlist_searchplaceholder"), color = colors.auxiliaryText) },
                singleLine = true,
            )
            if (query.isNotEmpty()) {
                Text(
                    "×",
                    color = colors.auxiliaryText,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable {
                            query = ""
                            onQueryChanged("")
                        }
                        .padding(6.dp)
                        .testTag("qa-global-search-clear"),
                )
            }
        }

        if (state.error) {
            Text(
                context.t("globalsearch_bannererror"),
                color = colors.bodyText,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bannerBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // tab 栏（空词隐藏，RN :504）
        if (trimmedQuery.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                GlobalSearchTab.entries.forEach { tab ->
                    val active = activeTab == tab
                    val labelKey = when (tab) {
                        GlobalSearchTab.ALL -> "globalsearch_taball"
                        GlobalSearchTab.MEMBERS -> "globalsearch_tabmembers"
                        GlobalSearchTab.MESSAGES -> "globalsearch_tabmessages"
                        GlobalSearchTab.FILES -> "globalsearch_tabfiles"
                    }
                    Text(
                        context.t(labelKey),
                        color = if (active) colors.tintColor else colors.auxiliaryText,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { activeTab = tab }
                            .padding(vertical = 8.dp)
                            .testTag("qa-global-search-tab-${tab.name.lowercase()}"),
                    )
                }
            }
        }

        when {
            isSearchPending -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag("qa-global-search-pending"))
            }

            trimmedQuery.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("globalsearch_emptyhint"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-global-search-empty-hint"),
                )
            }

            showNoResultsEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("globalsearch_noresults"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-global-search-no-results"),
                )
            }

            activeTab == GlobalSearchTab.ALL -> AllTabContent(
                state = state,
                expandedMembers = expandedMembers,
                onToggleMembers = { expandedMembers = !expandedMembers },
                expandedMessages = expandedMessages,
                onToggleMessages = { expandedMessages = !expandedMessages },
                expandedFiles = expandedFiles,
                onToggleFiles = { expandedFiles = !expandedFiles },
                messagesTotalCount = messagesTotalCount,
                messagesSectionHasMore = messagesSectionHasMore,
                serverUrl = serverUrl,
                currentUserId = currentUserId,
                token = token,
                onOpenContact = onOpenContact,
                onOpenRoom = onOpenRoom,
                onOpenMessageDetail = onOpenMessageDetail,
                onOpenFile = onOpenFile,
                onLoadMoreFiles = onLoadMoreFiles,
            )

            activeTab == GlobalSearchTab.MEMBERS -> ListContent(serverUrl, currentUserId, token) {
                items(state.memberRows, key = { it.key }) { row ->
                    MemberRow(row, trimmedQuery, serverUrl, currentUserId, token, onOpenContact, onOpenRoom)
                }
            }

            activeTab == GlobalSearchTab.MESSAGES -> ListContent(serverUrl, currentUserId, token) {
                items(messageRows, key = { it.key }) { row ->
                    MessageRow(row, trimmedQuery, serverUrl, currentUserId, token, onOpenMessageDetail)
                }
            }

            activeTab == GlobalSearchTab.FILES -> FilesTabContent(
                state = state,
                onLoadMoreFiles = onLoadMoreFiles,
                onOpenFile = onOpenFile,
            )
        }
    }
}

@Composable
private fun ListContent(
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    content: LazyListScope.() -> Unit,
) {
    val colors = LocalAppiaColors.current
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-global-search-list"),
        content = content,
    )
}

/** all tab：三分区（成员/消息/文件），3 条预览 + 查看更多（RN allTabContent :342-417）。 */
@Composable
private fun AllTabContent(
    state: GlobalSearchUiState,
    expandedMembers: Boolean,
    onToggleMembers: () -> Unit,
    expandedMessages: Boolean,
    onToggleMessages: () -> Unit,
    expandedFiles: Boolean,
    onToggleFiles: () -> Unit,
    messagesTotalCount: Int,
    messagesSectionHasMore: Boolean,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenContact: (String, String, String) -> Unit,
    onOpenRoom: (String, String, String) -> Unit,
    onOpenMessageDetail: (MessageSearchRow) -> Unit,
    onOpenFile: (GlobalSearchFileRow) -> Unit,
    onLoadMoreFiles: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val keyword = state.query

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .testTag("qa-global-search-all"),
    ) {
        if (state.memberRows.isNotEmpty()) {
            SectionHeader(
                title = context.t("globalsearch_sectionmembers"),
                count = state.memberRows.size,
                expanded = expandedMembers,
                onToggle = onToggleMembers,
            )
            Card {
                val rows = if (expandedMembers) state.memberRows else state.memberRows.take(GLOBAL_SEARCH_PREVIEW_LIMIT)
                rows.forEach { row ->
                    MemberRow(row, keyword, serverUrl, currentUserId, token, onOpenContact, onOpenRoom)
                }
            }
        }

        val hasMessageResults = state.messagePreviewRows.isNotEmpty() ||
            (state.messageFullRows?.isNotEmpty() == true) || messagesSectionHasMore
        if (hasMessageResults) {
            SectionHeader(
                title = context.t("globalsearch_sectionmessages"),
                count = messagesTotalCount,
                expanded = expandedMessages,
                onToggle = onToggleMessages,
                showViewMore = messagesSectionHasMore || messagesTotalCount > GLOBAL_SEARCH_PREVIEW_LIMIT,
            )
            Card {
                if (expandedMessages && state.messagesFullLoading) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val rows = if (expandedMessages) {
                        state.messageFullRows ?: state.messagePreviewRows
                    } else {
                        state.messagePreviewRows.take(GLOBAL_SEARCH_PREVIEW_LIMIT)
                    }
                    rows.forEach { row ->
                        MessageRow(row, keyword, serverUrl, currentUserId, token, onOpenMessageDetail)
                    }
                }
            }
        }

        if (state.files.isNotEmpty()) {
            SectionHeader(
                title = context.t("globalsearch_sectionfiles"),
                count = state.filesTotalCount.ifZero { state.files.size },
                expanded = expandedFiles,
                onToggle = onToggleFiles,
            )
            Card {
                val files = if (expandedFiles) state.files else state.files.take(GLOBAL_SEARCH_PREVIEW_LIMIT)
                files.forEach { file -> FileRowItem(file, keyword, onOpenFile) }
                if (expandedFiles && state.filesHasMore) {
                    LoadMoreButton(state.filesLoadingMore, onLoadMoreFiles)
                }
            }
        }
        Spacer(Modifier.size(20.dp))
    }
}

private fun Int.ifZero(fallback: (Int) -> Int): Int = if (this == 0) fallback(this) else this

/** files tab：cursor 分页列表（RN FlatList onEndReached）。 */
@Composable
private fun FilesTabContent(
    state: GlobalSearchUiState,
    onLoadMoreFiles: () -> Unit,
    onOpenFile: (GlobalSearchFileRow) -> Unit,
) {
    val listState = rememberLazyListState()
    // RN onEndReachedThreshold 0.3 等价：末 4 项可见即触发
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && state.filesHasMore && !state.filesLoadingMore) onLoadMoreFiles()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("qa-global-search-files-list"),
    ) {
        items(state.files, key = { it.key }) { file -> FileRowItem(file, state.query, onOpenFile) }
        if (state.filesLoadingMore) {
            item(key = "files-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("qa-global-search-files-loading-more"))
                }
            }
        }
    }
}

/** 分区头（RN sectionHeader :321-340）：标题 + 查看更多(count)/收起。 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    showViewMore: Boolean = count > GLOBAL_SEARCH_PREVIEW_LIMIT,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.titleText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (showViewMore) {
            Text(
                if (expanded) {
                    context.t("globalsearch_collapsesection")
                } else {
                    interpolate(context.t("globalsearch_viewmore"), mapOf("count" to count.toString()))
                },
                color = colors.tintColor,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("qa-global-search-section-toggle"),
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val colors = LocalAppiaColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chatComponentBackground)
            .testTag("qa-global-search-card"),
    ) { content() }
}

/** 成员/频道行（RN renderMemberRow :240-265）：contact 走 openDirectMessage 链。 */
@Composable
private fun MemberRow(
    row: GlobalSearchListRow,
    keyword: String,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenContact: (String, String, String) -> Unit,
    onOpenRoom: (String, String, String) -> Unit,
) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val username = row.username?.takeIf { it.isNotEmpty() }
                if (row.isContact && username != null) {
                    onOpenContact(username, row.title, row.rid)
                } else {
                    onOpenRoom(row.rid, row.title, row.roomType)
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("qa-global-search-row-${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchRowAvatar(row.title, row.avatarName, row.roomType, serverUrl, currentUserId, token)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            HighlightText(text = row.title, keyword = keyword, maxLines = 1)
            if (!row.subtitle.isNullOrEmpty()) {
                Text(
                    row.subtitle,
                    color = colors.auxiliaryText,
                    fontSize = 12.sp,
                    maxLines = if (row.isContact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("›", color = colors.auxiliaryText, fontSize = 18.sp)
    }
}

/** 消息分区行（RN renderMessageRow :267-287）→ 进 Detail 屏。 */
@Composable
private fun MessageRow(
    row: MessageSearchRow,
    keyword: String,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenMessageDetail: (MessageSearchRow) -> Unit,
) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenMessageDetail(row) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("qa-global-search-row-${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchRowAvatar(row.title, row.avatarName, row.roomType, serverUrl, currentUserId, token)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            HighlightText(text = row.title, keyword = keyword, maxLines = 1)
            Text(
                row.subtitle,
                color = colors.auxiliaryText,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = colors.auxiliaryText, fontSize = 18.sp)
    }
}

/** 文件行（RN renderFileRow :289-312）：彩色扩展名角标 + 名称高亮。 */
@Composable
private fun FileRowItem(
    file: GlobalSearchFileRow,
    keyword: String,
    onOpenFile: (GlobalSearchFileRow) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val info = searchFileInfo(file.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenFile(file) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("qa-global-search-file-${file.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(info.color),
            contentAlignment = Alignment.Center,
        ) {
            Text(info.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            HighlightText(text = file.name, keyword = keyword, maxLines = 2)
        }
    }
}

@Composable
private fun LoadMoreButton(loading: Boolean, onClick: () -> Unit) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp).testTag("qa-global-search-files-more-loading"))
        } else {
            Text(
                context.t("globalsearch_loadmorefiles"),
                color = colors.tintColor,
                fontSize = 13.sp,
                modifier = Modifier.testTag("qa-global-search-files-more"),
            )
        }
    }
}

/** 搜索行头像：initial 垫底 + /avatar/{name} 鉴权 URL（chatAvatarUrl 同款）。 */
@Composable
private fun SearchRowAvatar(
    title: String,
    avatarName: String?,
    roomType: String,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
) {
    val colors = LocalAppiaColors.current
    val density = LocalDensity.current
    val url = avatarName?.let {
        chatAvatarUrl(serverUrl, it, null, currentUserId, token, with(density) { AVATAR_SIZE.roundToPx() })
    }
    Box(
        Modifier
            .size(AVATAR_SIZE)
            .clip(CircleShape)
            .background(colors.chatComponentBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (if (roomType == "d") title else avatarName ?: title).take(1).uppercase().ifEmpty { "#" },
            color = colors.auxiliaryText,
            fontSize = 16.sp,
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(AVATAR_SIZE).clip(CircleShape))
        }
    }
}

/** RN utils/fileType.ts getFileType 紧凑移植：扩展名 → 角标/颜色。 */
internal data class SearchFileInfo(val label: String, val color: Color)

internal fun searchFileInfo(fileName: String): SearchFileInfo {
    val ext = fileName.trim().substringAfterLast('.', "").lowercase()
    val type = when (ext) {
        "xls", "xlsx", "spreadsheet", "csv" -> "excel"
        "pdf" -> "pdf"
        "ppt", "pptx", "presentation" -> "ppt"
        "txt", "document", "text", "md" -> "txt"
        "doc", "docx", "documentpro", "rtf" -> "word"
        "zip", "rar", "7z", "apk" -> "zip"
        "m4a", "mp3", "aac", "wav", "ogg", "flac" -> "audio"
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "heic", "heif" -> "image"
        "mp4", "mov", "mkv", "avi", "wmv", "flv", "webm", "3gp" -> "video"
        "folder" -> "folder"
        else -> "unknown"
    }
    val color = when (type) {
        "pdf", "audio" -> Color(0xFFFF7878)
        "ppt" -> Color(0xFFF98950)
        "word" -> Color(0xFF53B7F4)
        "excel" -> Color(0xFF53D39C)
        "txt" -> Color(0xFF4FD397)
        "zip", "image" -> Color(0xFFFFC757)
        "video" -> Color(0xFF8B72F7)
        "folder" -> Color(0xFFFFBA53)
        else -> Color(0xFFC7DADD)
    }
    val label = ext.uppercase().ifEmpty {
        when (type) {
            "pdf" -> "PDF"; "ppt" -> "PPTX"; "word" -> "DOC"; "excel" -> "XLS"
            "txt" -> "TXT"; "zip" -> "ZIP"; "audio" -> "AUDIO"; "video" -> "VIDEO"
            "image" -> "IMG"; "folder" -> "DIR"; else -> "FILE"
        }
    }
    return SearchFileInfo(label, color)
}
