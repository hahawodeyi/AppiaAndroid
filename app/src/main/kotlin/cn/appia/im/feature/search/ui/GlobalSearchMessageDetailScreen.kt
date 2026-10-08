package cn.appia.im.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.chat.ui.presenceBadge
import cn.appia.im.feature.chatlist.chatAvatarUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Calendar
import java.util.TimeZone
import coil3.compose.AsyncImage

/** RN MessageItem（GlobalSearchMessageDetailScreen :36-50）——chat.search 原始字段。 */
internal data class ChatSearchMessage(
    val _id: String,
    val msg: String? = null,
    val tmsg: String? = null,
    val ts: String? = null,
    val attachments: JsonElement? = null,
    val md: JsonElement? = null,
    val msgData: JsonElement? = null,
    val senderId: String? = null,
    val senderUsername: String? = null,
    val senderName: String? = null,
)

internal val CHAT_SEARCH_PAGE_SIZE = 50

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** chat.search 响应 messages 数组解析（RN raw.messages 数组判空）。 */
internal fun parseChatSearchMessages(raw: JsonElement?): List<ChatSearchMessage> {
    val arr = (raw as? JsonObject)?.get("messages") as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val u = obj["u"] as? JsonObject
        ChatSearchMessage(
            _id = obj.str("_id").orEmpty(),
            msg = obj.str("msg"),
            tmsg = obj.str("tmsg"),
            ts = obj.str("ts"),
            attachments = obj["attachments"]?.takeIf { it !is JsonNull },
            md = obj["md"]?.takeIf { it !is JsonNull },
            msgData = obj["msgData"]?.takeIf { it !is JsonNull },
            senderId = u?.str("_id"),
            senderUsername = u?.str("username"),
            senderName = u?.str("name"),
        )
    }
}

/** RN append 分支（:127-140）：_id 去重 append；返回合并结果与新增数。 */
internal fun appendChatSearchPage(
    prev: List<ChatSearchMessage>,
    page: List<ChatSearchMessage>,
): Pair<List<ChatSearchMessage>, Int> {
    val seen = prev.mapTo(HashSet()) { it._id }
    val out = prev.toMutableList()
    var added = 0
    for (m in page) {
        if (m._id.isEmpty() || seen.add(m._id)) {
            out += m
            added++
        }
    }
    return out to added
}

/** RN hasMore 判定（:146-150）：0 条或 append 0 新增即止步；否则页满可续。 */
internal fun chatSearchHasMore(pageMessages: List<ChatSearchMessage>, addedCount: Int, pageSize: Int): Boolean =
    when {
        pageMessages.isEmpty() -> false
        addedCount == 0 -> false
        else -> pageMessages.size >= pageSize
    }

// ── snippet / 时间（RN messageSearchSnippet.ts / formatRoomSearchMessageTime.ts）──

private val whitespace = Regex("\\s+")

private fun normalizeWhitespace(value: String): String = whitespace.replace(value, " ").trim()

private fun pushText(out: MutableList<String>, value: String) {
    val text = normalizeWhitespace(value)
    if (text.isNotEmpty() && text !in out) out += text
}

/** RN collectTextFragments：字符串/数字/布尔入列（字符串再试 JSON.parse 递归）；数组/对象遍历。 */
private fun collectTextFragments(value: JsonElement?, out: MutableList<String>) {
    when (value) {
        null, is JsonNull -> {}
        is JsonArray -> value.forEach { collectTextFragments(it, out) }
        is JsonObject -> value.values.forEach { collectTextFragments(it, out) }
        is JsonPrimitive -> {
            if (value.isString) {
                val trimmed = value.content.trim()
                if (trimmed.isEmpty()) return
                pushText(out, trimmed)
                runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
                    ?.let { collectTextFragments(it, out) }
            } else {
                pushText(out, value.content)
            }
        }
    }
}

/** RN messageSearchSnippet：候选=组合串优先；命中含关键词候选，80 字窗口 + 前后省略。 */
internal fun messageSearchSnippet(message: ChatSearchMessage, keyword: String, maxLength: Int = 80): String {
    val rawCandidates = mutableListOf<String>()
    message.msg?.let { collectTextFragments(JsonPrimitive(it), rawCandidates) }
    message.tmsg?.let { collectTextFragments(JsonPrimitive(it), rawCandidates) }
    collectTextFragments(message.attachments, rawCandidates)
    collectTextFragments(message.msgData, rawCandidates)
    collectTextFragments(message.md, rawCandidates)
    val combined = normalizeWhitespace(rawCandidates.joinToString(" "))
    val candidates = (listOf(combined) + rawCandidates).filter { it.isNotEmpty() }.distinct()

    val q = keyword.trim()
    if (q.isEmpty()) return candidates.firstOrNull() ?: ""
    val matched = candidates.firstOrNull { it.lowercase().indexOf(q.lowercase()) >= 0 }
    val text = matched ?: candidates.firstOrNull() ?: ""
    if (text.isEmpty() || text.length <= maxLength) return text

    val idx = text.lowercase().indexOf(q.lowercase())
    if (idx < 0) return text.take(maxLength) + "..."
    val halfLen = maxOf(0, (maxLength - q.length) / 2)
    val start = maxOf(0, idx - halfLen)
    val end = minOf(text.length, idx + q.length + halfLen)
    val prefix = if (start > 0) "..." else ""
    val suffix = if (end < text.length) "..." else ""
    return prefix + text.substring(start, end) + suffix
}

/** RN formatRoomSearchMessageTime：`YYYY/MM/DD HH:mm (UTC±x)`；无效 ts 空串。 */
internal fun formatSearchMessageTime(tsMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (tsMillis <= 0) return ""
    val cal = Calendar.getInstance(zone).apply { timeInMillis = tsMillis }
    fun pad2(v: Int) = v.toString().padStart(2, '0')
    val tzHours = zone.getOffset(tsMillis) / 3_600_000.0
    val tzLabel = if (tzHours == kotlin.math.floor(tzHours)) tzHours.toInt().toString() else tzHours.toString()
    val tzString = if (tzHours >= 0) "(UTC+$tzLabel)" else "(UTC-$tzLabel)"
    return "${cal.get(Calendar.YEAR)}/${pad2(cal.get(Calendar.MONTH) + 1)}/${pad2(cal.get(Calendar.DAY_OF_MONTH))} " +
        "${pad2(cal.get(Calendar.HOUR_OF_DAY))}:${pad2(cal.get(Calendar.MINUTE))} $tzString"
}

/** RN Date.parse(item.ts) 等价（ISO 失败 → 0 → 空时间）；minSdk 24 无 java.time → 文本解析。 */
internal fun parseIsoTs(ts: String?): Long {
    val raw = ts?.trim().takeUnless { it.isNullOrEmpty() } ?: return 0L
    return runCatching {
        // ISO-8601: yyyy-MM-ddTHH:mm:ss[.SSS][Z|±HH:MM|±HHMM]
        val m = Regex(
            """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""",
        ).find(raw) ?: return 0L
        val (y, mo, d, h, mi, s) = m.destructured
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.isLenient = false
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val millis = cal.timeInMillis
        when (val zone = m.groupValues[8]) {
            "", "Z" -> millis
            else -> {
                val sign = if (zone[0] == '-') -1 else 1
                val zh = zone.substring(1, 3).toInt()
                val zm = if (zone.length >= 5) zone.substring(zone.length - 2).toInt() else 0
                millis - sign * (zh * 60 + zm) * 60_000L
            }
        }
    }.getOrDefault(0L)
}

/** RN GET chat.search（services/api/chat.ts :39-43：roomId/searchText/count/offset/notIncludeFile=true）。 */
internal suspend fun fetchChatSearch(
    sdk: RocketSdk,
    roomId: String,
    searchText: String,
    count: Int = CHAT_SEARCH_PAGE_SIZE,
    offset: Int,
): JsonElement? = sdk.get(
    "chat.search",
    mapOf(
        "roomId" to roomId,
        "searchText" to searchText,
        "count" to count.toString(),
        "offset" to offset.toString(),
        "notIncludeFile" to "true",
    ),
)

/**
 * 全局搜索消息详情（RN screens/GlobalSearchMessageDetailScreen）：
 * - chat.search 分页 50 / _id 去重 append / addedCount==0 止步
 * - 头部频道条 → 进房平跳；消息行 → onJumpTo（T6 跳转；缺省平跳进房）——两者均带
 *   fromGlobalSearch 由 Room 侧 ChatBumper 双 bump（M5-T8 收口）
 * - 发送者名 useRealName 门控（UI_Use_Real_Name 与房内一致，T4 评审转发要求）
 */
@Composable
fun GlobalSearchMessageDetailScreen(
    rid: String,
    title: String,
    roomType: String,
    searchText: String,
    avatarName: String?,
    sdk: RocketSdk,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    useRealName: Boolean = true,
    onOpenRoom: (rid: String, title: String, roomType: String) -> Unit,
    onJumpTo: ((messageId: String) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var messages by remember { mutableStateOf<List<ChatSearchMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }

    suspend fun load(append: Boolean) {
        if (append) {
            if (!hasMore || loadingMore) return
            loadingMore = true
        } else {
            offset = 0
            hasMore = true
            loading = true
            error = null
        }
        val requestOffset = if (append) offset else 0
        try {
            val raw = fetchChatSearch(sdk, rid, searchText, offset = requestOffset)
            val obj = raw as? JsonObject
            if (obj == null || (obj["success"] as? JsonPrimitive)?.contentOrNull == "false") {
                if (!append) messages = emptyList()
                hasMore = false
                return
            }
            val page = parseChatSearchMessages(raw)
            val (merged, added) = if (append) appendChatSearchPage(messages, page) else page to page.size
            messages = merged
            offset = requestOffset + page.size
            hasMore = chatSearchHasMore(page, if (append) added else page.size, CHAT_SEARCH_PAGE_SIZE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!append) {
                messages = emptyList()
                error = e.message ?: ""
            }
            hasMore = false
        } finally {
            if (append) loadingMore = false else loading = false
        }
    }

    LaunchedEffect(rid, searchText) { load(append = false) }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, messages.size) {
        if (nearEnd && hasMore && !loadingMore) scope.launch { load(append = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-global-search-detail"),
    ) {
        RoomHeader(title = title, onBack = onBack)

        // 频道条（RN renderListHeader :312-343）：进房平跳（fromGlobalSearch 双 bump 在 Room 侧收口）
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.chatComponentBackground)
                .clickable { onOpenRoom(rid, title, roomType) }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("qa-global-search-detail-enter"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatarUrl = avatarName?.let {
                chatAvatarUrl(serverUrl, it, null, currentUserId, token, with(density) { 36.dp.roundToPx() })
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.backgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(title.take(1).ifEmpty { "#" }, color = colors.auxiliaryText, fontSize = 14.sp)
                if (avatarUrl != null) {
                    AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.size(36.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, color = colors.titleText, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(context.t("globalsearchmessagedetail_channelhint"), color = colors.auxiliaryText, fontSize = 12.sp)
            }
            Text("›", color = colors.auxiliaryText, fontSize = 18.sp)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        context.t("globalsearchmessagedetail_loading"),
                        color = colors.auxiliaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        context.t("globalsearchmessagedetail_error").replace("{{error}}", error ?: ""),
                        color = colors.dangerColor,
                        fontSize = 13.sp,
                    )
                    Text(
                        context.t("globalsearchmessagedetail_retry"),
                        color = colors.tintColor,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { scope.launch { load(append = false) } }
                            .padding(8.dp)
                            .testTag("qa-global-search-detail-retry"),
                    )
                }
            }

            messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("globalsearchmessagedetail_noresults"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-global-search-detail-empty"),
                )
            }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(messages, key = { it._id.ifEmpty { "idx-${messages.indexOf(it)}" } }) { m ->
                    MessageDetailRow(
                        message = m,
                        searchText = searchText,
                        useRealName = useRealName,
                        serverUrl = serverUrl,
                        currentUserId = currentUserId,
                        token = token,
                        onClick = {
                            if (!m._id.isEmpty()) {
                                // onJumpTo 缺省回退：平跳进房（同频道条）
                                onJumpTo?.invoke(m._id) ?: onOpenRoom(rid, title, roomType)
                            }
                        },
                    )
                }
                if (loadingMore) {
                    item(key = "detail-loading") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 消息行（RN renderMessageItem :202-274）：头像 + 发送者/时间 + snippet 高亮（keepMatchVisible）。 */
@Composable
private fun MessageDetailRow(
    message: ChatSearchMessage,
    searchText: String,
    useRealName: Boolean,
    serverUrl: String,
    currentUserId: String?,
    token: String?,
    onClick: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val density = LocalDensity.current

    // RN u.name || u.username || unknown；useRealName=false 时 username 优先（与房内一致）
    val senderName = when {
        useRealName && !message.senderName.isNullOrEmpty() -> message.senderName
        !message.senderUsername.isNullOrEmpty() -> message.senderUsername
        !message.senderName.isNullOrEmpty() -> message.senderName
        else -> context.t("globalsearchmessagedetail_unknownsender")
    }
    val avatarUrl = message.senderUsername?.let {
        chatAvatarUrl(serverUrl, it, null, currentUserId, token, with(density) { 36.dp.roundToPx() })
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("qa-global-search-detail-row-${message._id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.chatComponentBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(senderName.take(1).ifEmpty { "#" }, color = colors.auxiliaryText, fontSize = 14.sp)
            if (avatarUrl != null) {
                AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.size(36.dp))
            }
            // presence 绿点（M5-T3 rider / RN :234-235 DirectAvatar presenceUserId=presenceUsername）
            presenceBadge(
                userId = message.senderId,
                username = message.senderUsername,
                fallbackStatus = null,
                avatarSize = 36.dp,
            )()
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp)
                .background(colors.chatComponentBackground, CircleShape)
                .padding(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    senderName,
                    color = colors.titleText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    formatSearchMessageTime(parseIsoTs(message.ts)),
                    color = colors.auxiliaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            val snippet = messageSearchSnippet(message, searchText)
            if (snippet.isNotEmpty()) {
                HighlightText(
                    text = snippet,
                    keyword = searchText,
                    maxLines = 1,
                    keepMatchVisible = true,
                )
            }
        }
    }
}
