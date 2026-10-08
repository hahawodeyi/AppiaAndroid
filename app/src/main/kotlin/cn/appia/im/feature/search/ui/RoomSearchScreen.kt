package cn.appia.im.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.isSameCalendarDay
import cn.appia.im.feature.chat.RoomMemberRow
import cn.appia.im.feature.chat.ui.AttachmentNav
import cn.appia.im.feature.chat.ui.DateSeparator
import cn.appia.im.feature.chat.ui.MessageRow
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.chat.ui.buildDocPreviewParamsFromFileLink
import cn.appia.im.feature.chat.ui.presenceBadge
import cn.appia.im.feature.chatlist.chatAvatarUrl
import cn.appia.im.feature.search.RoomSearchFileRow
import cn.appia.im.feature.search.RoomSearchTab
import cn.appia.im.feature.search.RoomSearchUiState
import cn.appia.im.feature.search.RoomSearchViewModel
import cn.appia.im.feature.search.formatRoomSearchFileSize
import cn.appia.im.feature.search.formatRoomSearchFileSubtitle
import cn.appia.im.feature.search.groupRoomSearchMediaByDate
import cn.appia.im.feature.search.isMediaVideoCell
import cn.appia.im.feature.search.resolveRoomSearchFileUrl
import coil3.compose.AsyncImage
import java.util.Calendar
import java.util.TimeZone

private val FILE_ICON_SIZE = 44.dp
private val AVATAR_SIZE = 44.dp

/**
 * 房间内搜索屏（RN screens/RoomSearchScreen/index.tsx）：
 * - 6/4 tab by 房型（VM visibleTabs）；messages/links/mentions tab 复用 MessageRow 链
 *   （日期分隔 + 真名门控 useRealName，M4 前例 RoomSearchMessageTabList 同构）；
 * - files：文件行（扩展名角标 + 大小/发送者/时间）；media：4 列日历日分组网格；
 * - members：本地缓存过滤行（头像 + 高亮 + 职位）；
 * - 点击（回调参数化）：消息 → goBack+T6 跳转；；媒体 → UrlMedia   成员 → openDirectMessage 链；行内附件点击 → onAttachmentNav（T7 路由同款）。
 */
@Composable
fun RoomSearchScreen(
    rid: String,
    roomType: String,
    state: RoomSearchUiState,
    visibleTabs: List<RoomSearchTab>,
    activeTab: RoomSearchTab,
    useRealName: Boolean,
    serverUrl: String,
    currentUserId: String?,
    currentUsername: String?,
    token: String?,
    onQueryChanged: (String) -> Unit,
    onTabSelected: (RoomSearchTab) -> Unit,
    onLoadMoreMessages: () -> Unit,
    onLoadMoreFiles: () -> Unit,
    onLoadMoreMedia: () -> Unit,
    onLoadMoreMentions: () -> Unit,
    onMessageClick: (MessageEntity) -> Unit,
    onOpenFile: (RoomSearchFileRow) -> Unit,
    onOpenMedia: (RoomSearchFileRow) -> Unit,
    onOpenMember: (RoomMemberRow) -> Unit,
    onAttachmentNav: (AttachmentNav) -> Unit,
    onOpenForwardMerge: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val searchText = state.searchText
    val trimmed = query.trim()
    val showEmptyHint = trimmed.isEmpty()
    val currentRows: List<*> = when (activeTab) {
        RoomSearchTab.MESSAGES -> state.messageRows
        RoomSearchTab.LINKS -> state.messageRows.filter { it.hasLinkForSearch() }
        RoomSearchTab.FILES -> state.fileRows
        RoomSearchTab.MEDIA -> state.mediaRows
        RoomSearchTab.MENTIONS -> state.mentionRows
        RoomSearchTab.MEMBERS -> state.memberRows
    }
    val showNoResults = !showEmptyHint && !state.loading && currentRows.isEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-room-search-screen"),
    ) {
        // 头部：返回 + 搜索框 + 清空（RN :279-305）
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
                    .testTag("qa-room-search-back"),
            )
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChanged(it)
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("qa-room-search-input"),
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
                        .testTag("qa-room-search-clear"),
                )
            }
        }

        // tab 栏（RN :307-324；6 tab 均分横排）
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            visibleTabs.forEach { tab ->
                val active = activeTab == tab
                Text(
                    context.t(tab.labelKey),
                    color = if (active) colors.tintColor else colors.auxiliaryText,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = 8.dp)
                        .testTag("qa-room-search-tab-${tab.name.lowercase()}"),
                )
            }
        }

        when {
            showEmptyHint -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("roomsearch_emptyhint"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-room-search-empty-hint"),
                )
            }

            showNoResults -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("roomsearch_noresults"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-room-search-no-results"),
                )
            }

            else -> when (activeTab) {
                RoomSearchTab.MESSAGES -> MessageTabList(
                    rows = state.messageRows,
                    state = state,
                    hasMore = state.hasMoreMessages,
                    onLoadMore = onLoadMoreMessages,
                    useRealName = useRealName,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    currentUsername = currentUsername,
                    token = token,
                    onMessageClick = onMessageClick,
                    onAttachmentNav = onAttachmentNav,
                    onOpenForwardMerge = onOpenForwardMerge,
                )

                RoomSearchTab.LINKS -> MessageTabList(
                    rows = state.messageRows.filter { it.hasLinkForSearch() },
                    state = state,
                    hasMore = false, // links 无独立分页（RN linksRows 无 onEndReached）
                    onLoadMore = {},
                    useRealName = useRealName,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    currentUsername = currentUsername,
                    token = token,
                    onMessageClick = onMessageClick,
                    onAttachmentNav = onAttachmentNav,
                    onOpenForwardMerge = onOpenForwardMerge,
                )

                RoomSearchTab.MENTIONS -> MessageTabList(
                    rows = state.mentionRows,
                    state = state,
                    hasMore = state.hasMoreMentions,
                    onLoadMore = onLoadMoreMentions,
                    useRealName = useRealName,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    currentUsername = currentUsername,
                    token = token,
                    onMessageClick = onMessageClick,
                    onAttachmentNav = onAttachmentNav,
                    onOpenForwardMerge = onOpenForwardMerge,
                )

                RoomSearchTab.FILES -> FilesTabList(
                    rows = state.fileRows,
                    keyword = searchText,
                    hasMore = state.hasMoreFiles,
                    onLoadMore = onLoadMoreFiles,
                    onOpenFile = onOpenFile,
                )

                RoomSearchTab.MEDIA -> MediaTabList(
                    rows = state.mediaRows,
                    state = state,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    token = token,
                    onLoadMore = onLoadMoreMedia,
                    onOpenMedia = onOpenMedia,
                )

                RoomSearchTab.MEMBERS -> MembersTabList(
                    rows = state.memberRows,
                    keyword = searchText,
                    serverUrl = serverUrl,
                    currentUserId = currentUserId,
                    token = token,
                    onOpenMember = onOpenMember,
                )
            }
        }
    }
}

/** links tab 行过滤（VM linksRows 派生：urls 数组非空或 msg 含 http(s)://）。 */
private fun MessageEntity.hasLinkForSearch(): Boolean =
    cn.appia.im.feature.search.filterMessagesWithLinks(listOf(this)).isNotEmpty()

/** messages/links/mentions 共用：MessageRow 复用链（RN RoomSearchMessageTabList）。 */
@Composable
private fun MessageTabList(
    rows: List<MessageEntity>,
    state: RoomSearchUiState,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    useRealName: Boolean,
    serverUrl: String,
    currentUserId: String?,
    currentUsername: String?,
    token: String?,
    onMessageClick: (MessageEntity) -> Unit,
    onAttachmentNav: (AttachmentNav) -> Unit,
    onOpenForwardMerge: (String, String) -> Unit,
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
    LaunchedEffect(nearEnd, rows.size, hasMore, state.loading) {
        if (nearEnd && hasMore && !state.loading) onLoadMore()
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("qa-room-search-message-list")) {
        itemsIndexed(rows, key = { _, item -> item._id }, contentType = { _, _ -> "msg" }) { index, item ->
            // RN renderItem：与上一条（更旧）非同历日插分隔（RN olderMessage = rows[index+1]，
            // 列表正 老在上倒序对Screen
            val older = rows.getOrNull(index + 1)
            if (older == null || !isSameCalendarDay(older.ts.toLong(), item.ts.toLong())) {
                DateSeparator(item.ts.toLong())
            }
            MessageRow(
                message = item,
                currentUserId = currentUserId,
                currentUsername = currentUsername,
                serverUrl = serverUrl,
                token = token,
                useRealName = useRealName,
                onResend = {},
                onClick = onMessageClick,
                onAttachmentNav = onAttachmentNav,
                onOpenForwardMerge = onOpenForwardMerge,
            )
        }
        if (state.loading) {
            item(key = "search-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
        }
    }
}

/** files tab（RN FlatList + RoomSearchFileListItem）。 */
@Composable
private fun FilesTabList(
    rows: List<RoomSearchFileRow>,
    keyword: String,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpenFile: (RoomSearchFileRow) -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, rows.size, hasMore) {
        if (nearEnd && hasMore) onLoadMore()
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("qa-room-search-files-list")) {
        items(rows, key = { it.id }) { file ->
            RoomSearchFileListItem(file = file, keyword = keyword, onClick = { onOpenFile(file) })
        }
    }
}

/** RN RoomSearchFileListItem：扩展名角标 + 名称高亮 + meta + 大小。 */
@Composable
internal fun RoomSearchFileListItem(file: RoomSearchFileRow, keyword: String, onClick: () -> Unit) {
    val colors = LocalAppiaColors.current
    val info = searchFileInfo(file.name)
    val meta = formatRoomSearchFileSubtitle(file.senderName, file.uploadedAt)
    val sizeLabel = formatRoomSearchFileSize(file.size)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("qa-room-search-file-${file.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(FILE_ICON_SIZE)
                    .clip(RoundedCornerShape(8.dp))
                    .background(info.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(info.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                HighlightText(text = file.name, keyword = keyword, maxLines = 1)
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        color = colors.auxiliaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (sizeLabel.isNotEmpty()) {
            Text(sizeLabel, color = colors.auxiliaryText, fontSize = 12.sp)
        }
    }
}

/** media tab（RN RoomSearchMediaTabList：4 列网格 + 日历日分区 + 播放角标）。 */
@Composable
private fun MediaTabList(
    rows: List<RoomSearchFileRow>,
    state: RoomSearchUiState,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onLoadMore: () -> Unit,
    onOpenMedia: (RoomSearchFileRow) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, rows.size, state.hasMoreMedia, state.loading) {
        if (nearEnd && state.hasMoreMedia && !state.loading) onLoadMore()
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("qa-room-search-media-list")) {
        groupRoomSearchMediaByDate(rows).forEach { section ->
            item(key = "media-section-${section.sectionKey}") {
                val title = if (section.sectionKey == "__unknown__") {
                    context.t("roomsearch_mediadateunknown")
                } else {
                    roomSearchMediaSectionTitle(context, section.title)
                }
                if (title.isNotEmpty()) {
                    Text(
                        title,
                        color = colors.auxiliaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            section.rows.forEach { row ->
                item(key = "media-row-${row.firstOrNull()?.id ?: row.hashCode()}", contentType = { "media-row" }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.forEach { file ->
                            MediaCell(
                                file = file,
                                serverUrl = serverUrl,
                                currentUserId = currentUserId,
                                token = token,
                                onOpenMedia = onOpenMedia,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(4 - row.size) { Box(Modifier.weight(1f)) } // 占位补列
                    }
                }
            }
        }
        if (state.loading) {
            item(key = "media-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
        }
    }
}

/** RN renderCell：缩略图 + video/audio 播放角标；无 URL 灰块。 */
@Composable
private fun MediaCell(
    file: RoomSearchFileRow,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenMedia: (RoomSearchFileRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val density = LocalDensity.current
    val uri = remember(file.id, serverUrl, token) {
        if (serverUrl.isBlank() || currentUserId == null || token == null) "" else resolveRoomSearchFileUrl(file, currentUserId, token, serverUrl)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.chatComponentBackground)
            .clickable(enabled = uri.isNotEmpty()) { onOpenMedia(file) }
            .testTag("qa-room-search-media-${file.id}"),
    ) {
        if (uri.isNotEmpty()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (uri.isNotEmpty() && isMediaVideoCell(file)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x40000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

/** members tab（RN renderMemberRow：头像 + 高亮 + 职位；点击 → openDirectMessage 链）。 */
@Composable
private fun MembersTabList(
    rows: List<RoomMemberRow>,
    keyword: String,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onOpenMember: (RoomMemberRow) -> Unit,
) {
    val colors = LocalAppiaColors.current
    val density = LocalDensity.current
    LazyColumn(Modifier.fillMaxSize().testTag("qa-room-search-members-list")) {
        items(rows, key = { it._id }) { member ->
            val title = member.name ?: member.username
            val position = member.jobName?.trim().orEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMember(member) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("qa-room-search-member-${member._id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val avatarUrl = member.username.takeIf { it.isNotEmpty() }?.let {
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
                        (title ?: "?").take(1).ifEmpty { "?" },
                        color = colors.auxiliaryText,
                        fontSize = 16.sp,
                    )
                    if (avatarUrl != null) {
                        AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.size(AVATAR_SIZE).clip(CircleShape))
                    }
                    presenceBadge(userId = member._id, username = member.username, fallbackStatus = null, avatarSize = AVATAR_SIZE)()
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    HighlightText(text = title.orEmpty().ifEmpty { "?" }, keyword = keyword, maxLines = 1)
                    if (position.isNotEmpty()) {
                        HighlightText(text = position, keyword = keyword, maxLines = 1)
                    }
                }
                Text("›", color = colors.auxiliaryText, fontSize = 18.sp)
            }
        }
    }
}

/** RN mediaSectionDate 插值：`MM月DD日`（sectionKey = MM-dd 拆插值）。 */
internal fun roomSearchMediaSectionTitle(context: android.content.Context, sectionKey: String): String {
    val parts = sectionKey.split("-")
    if (parts.size != 2) return ""
    return context.t("roomsearch_mediasectiondate")
        .replace("{{month}}", parts[0])
        .replace("{{day}}", parts[1])
}
