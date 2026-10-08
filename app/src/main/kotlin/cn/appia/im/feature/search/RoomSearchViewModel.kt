package cn.appia.im.feature.search

import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * RN RoomSearchTabId（lib/chat/roomSearchTypes.ts:1-7）：六 tab id。
 * 排列序 = RN getVisibleRoomSearchTabs 的 GROUP_TABS 序（messages/members/files/media/mentions/links）。
 */
enum class RoomSearchTab(val labelKey: String) {
    MESSAGES("roomsearch_tabmessages"),
    MEMBERS("roomsearch_tabmembers"),
    FILES("roomsearch_tabfiles"),
    MEDIA("roomsearch_tabmedia"),
    MENTIONS("roomsearch_tabmentions"),
    LINKS("roomsearch_tablinks"),
}

/**
 * RN getVisibleRoomSearchTabs（lib/chat/getVisibleRoomSearchTabs.ts）：
 * 单聊（t='d'）4 tab；群房 6 tab。
 */
fun getVisibleRoomSearchTabs(roomType: String): List<RoomSearchTab> =
    if (roomType == "d") {
        listOf(RoomSearchTab.MESSAGES, RoomSearchTab.FILES, RoomSearchTab.MEDIA, RoomSearchTab.LINKS)
    } else {
        RoomSearchTab.entries.toList()
    }

/** RN resolveInitialRoomSearchTab：initialTab 在可见集内才采用，否则 messages。 */
fun resolveInitialRoomSearchTab(roomType: String, initialTab: RoomSearchTab?): RoomSearchTab {
    val visible = getVisibleRoomSearchTabs(roomType)
    return initialTab?.takeIf { it in visible } ?: RoomSearchTab.MESSAGES
}

// ── links 启发式（RN roomSearchLinksHeuristic.ts 逐条）──

private val URL_IN_MSG = Regex("https?://", RegexOption.IGNORE_CASE)

/** RN parseUrlsArray：urls JSON 字符串解析成数组（坏 JSON → 空）；数组非空即算（元素形状不筛）。 */
internal fun parseUrlsArray(urls: String?): List<kotlinx.serialization.json.JsonElement> {
    if (urls.isNullOrEmpty()) return emptyList()
    val parsed = runCatching { Json.parseToJsonElement(urls) }.getOrNull() ?: return emptyList()
    return (parsed as? JsonArray)?.toList() ?: emptyList()
}

/** RN filterMessagesWithLinks：urls 数组非空或 msg 含 http(s)://。临时方案（RN 注释同）。 */
internal fun filterMessagesWithLinks(rows: List<MessageEntity>): List<MessageEntity> =
    rows.filter { parseUrlsArray(it.urls).isNotEmpty() || URL_IN_MSG.containsMatchIn(it.msg.orEmpty()) }

// ── members 过滤（RN filterRoomMembersByQuery.ts）──

/** RN filterRoomMembersByQuery：空词空集；name/username/jobName 小写包含。 */
fun filterRoomMembersByQuery(
    members: List<cn.appia.im.feature.chat.RoomMemberRow>,
    query: String,
): List<cn.appia.im.feature.chat.RoomMemberRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return members.filter { m ->
        m.name.orEmpty().lowercase().contains(q) ||
            m.username.lowercase().contains(q) ||
            m.jobName.orEmpty().lowercase().contains(q)
    }
}

// ── 分页合并（RN mergeRoomSearchMessages / mergeRoomSearchFileRows）──

/** RN mergeRoomSearchMessages：_id 去重 append，保留已有顺序。返回 (merged, addedCount)。 */
internal fun <T> mergeById(prev: List<T>, next: List<T>, id: (T) -> String): Pair<List<T>, Int> {
    val seen = prev.mapTo(HashSet()) { id(it) }
    val out = prev.toMutableList()
    var added = 0
    for (item in next) {
        val key = id(item)
        if (key.isEmpty() || seen.add(key)) {
            out += item
            added++
        }
    }
    return out to added
}

// ── 文件行解析（RN parseRoomFilesResponse.ts）──

/** RN RoomSearchFileRow（roomSearchTypes.ts:9-17）。 */
data class RoomSearchFileRow(
    val id: String,
    val name: String,
    val senderName: String,
    val uploadedAt: String? = null,
    val size: Double? = null,
    val url: String? = null,
    val typeGroup: String? = null,
)

private fun JsonObject.fStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.fNum(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()

/** RN mapFiles：_id 缺失剔除；user.name ?? user.username 兜底 senderName。 */
internal fun parseRoomFilesPage(raw: Any?): Pair<List<RoomSearchFileRow>, FilesPageMeta> {
    val res = raw as? JsonObject
    if (res == null || (res["success"] as? JsonPrimitive)?.contentOrNull == "false" || res["files"] !is JsonArray) {
        return emptyList<RoomSearchFileRow>() to FilesPageMeta(total = 0, totalFromApi = false)
    }
    val files = (res["files"] as? JsonArray)
        ?.mapNotNull { el -> el as? JsonObject }
        ?.mapNotNull { f ->
            val id = f.fStr("_id") ?: return@mapNotNull null
            val user = f["user"] as? JsonObject
            RoomSearchFileRow(
                id = id,
                name = f.fStr("name").orEmpty(),
                senderName = user?.fStr("name") ?: user?.fStr("username").orEmpty(),
                uploadedAt = f.fStr("uploadedAt"),
                size = f.fNum("size"),
                url = f.fStr("url"),
                typeGroup = f.fStr("typeGroup"),
            )
        }
        .orEmpty()
    val totalFromApi = (res["total"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull() != null
    val total = if (totalFromApi) {
        (res["total"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: files.size
    } else {
        files.size
    }
    return files to FilesPageMeta(total = total, totalFromApi = totalFromApi)
}

/** RN ParsedRoomFilesPage 的 total 两态。 */
data class FilesPageMeta(val total: Int, val totalFromApi: Boolean)

/** RN resolveRoomFilesHasMore：空页 false；total 可信时 loaded<total；否则页满可续。 */
internal fun resolveRoomFilesHasMore(loadedCount: Int, page: List<RoomSearchFileRow>, meta: FilesPageMeta, pageSize: Int): Boolean {
    if (page.isEmpty()) return false
    if (meta.totalFromApi) return loadedCount < meta.total
    return page.size >= pageSize
}

// ── 文件元信息格式化（RN formatRoomSearchFileMeta.ts）──

/** RN formatRoomSearchFileSize：`26.6KB` / `1.1M`；<=0/非数 → ""。 */
internal fun formatRoomSearchFileSize(bytes: Double?): String {
    if (bytes == null || bytes <= 0 || bytes.isNaN()) return ""
    val kb = bytes / 1024
    if (kb >= 1024) return String.format("%.1fM", kb / 1024)
    return String.format("%.1fKB", kb)
}

/** RN formatRoomSearchFileUploadedAt：`YYYY/MM/DD HH:mm (UTC±N)`；无效 → ""。 */
internal fun formatRoomSearchFileUploadedAt(uploadedAt: String?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    val raw = uploadedAt?.trim().takeUnless { it.isNullOrEmpty() } ?: return ""
    val ms = parseIsoTsMillis(raw) ?: return ""
    val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }
    val offsetHr = zone.getOffset(ms) / 3_600_000.0
    val tz = if (offsetHr >= 0) "(UTC+$offsetHr)" else "(UTC${offsetHr})"
    fun pad2(v: Int) = v.toString().padStart(2, '0')
    return "${cal.get(java.util.Calendar.YEAR)}/${pad2(cal.get(java.util.Calendar.MONTH) + 1)}/" +
        "${pad2(cal.get(java.util.Calendar.DAY_OF_MONTH))} ${pad2(cal.get(java.util.Calendar.HOUR_OF_DAY))}:" +
        "${pad2(cal.get(java.util.Calendar.MINUTE))} $tz"
}

/** RN formatRoomSearchFileSubtitle：`sender | time`，缺侧取另一侧。 */
internal fun formatRoomSearchFileSubtitle(senderName: String, uploadedAt: String?): String {
    val sender = senderName.trim()
    val time = formatRoomSearchFileUploadedAt(uploadedAt)
    if (sender.isNotEmpty() && time.isNotEmpty()) return "$sender | $time"
    return sender.ifEmpty { time }
}

/** RN `new Date(...)` + isNaN 判定（复用 T5 parseIsoTs：无效 0 → 空串/unknown 语义）。 */
internal fun parseIsoTsMillis(ts: String): Long? {
    val ms = cn.appia.im.feature.search.ui.parseIsoTs(ts)
    return ms.takeIf { it > 0 }
}

/** RN queryLocalRoomMessagesByText：加密房本地 LIKE 搜索（一次性结果，不分页）。 */
internal fun buildLocalLikePattern(searchText: String): String = "%" + sanitizeLike(searchText.trim()) + "%"

// ── 媒体分组（RN groupRoomSearchMediaByDate.ts）──

/** RN MEDIA_GRID_COLUMNS = 4。 */
const val ROOM_SEARCH_MEDIA_GRID_COLUMNS = 4

/** RN RoomSearchMediaSection。 */
data class RoomSearchMediaSection(val sectionKey: String, val title: String, val rows: List<List<RoomSearchFileRow>>)

/** RN formatRoomSearchMediaSectionTitle：`MM月DD日`（sectionTitle 由 UI 层 t() 插值）。 */
internal fun mediaSectionDateKey(uploadedAt: String?): String {
    val ms = parseIsoTsMillis(uploadedAt?.trim().takeUnless { it.isNullOrEmpty() } ?: return "") ?: return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun pad2(v: Int) = v.toString().padStart(2, '0')
    return "${pad2(cal.get(java.util.Calendar.MONTH) + 1)}-${pad2(cal.get(java.util.Calendar.DAY_OF_MONTH))}"
}

/** RN groupRoomSearchMediaByDate：按日历日分组（保持 API 倒序）→ 4 列网格行。 */
internal fun groupRoomSearchMediaByDate(items: List<RoomSearchFileRow>): List<RoomSearchMediaSection> {
    data class Acc(var sectionKey: String, val title: String, val items: MutableList<RoomSearchFileRow>)

    val sections = mutableListOf<Acc>()
    for (item in items) {
        val key = mediaSectionDateKey(item.uploadedAt).ifEmpty { "__unknown__" }
        val last = sections.lastOrNull()
        if (last?.sectionKey == key) {
            last.items += item
        } else {
            sections += Acc(key, key, mutableListOf(item))
        }
    }
    return sections.map { s ->
        RoomSearchMediaSection(
            sectionKey = s.sectionKey,
            title = s.title,
            rows = s.items.chunked(ROOM_SEARCH_MEDIA_GRID_COLUMNS),
        )
    }
}

// ── 文件 URL 解析（RN resolveRoomSearchFileUrl.ts）──

private const val UFS_UPLOADS = "ufs/FileSystem:Uploads"

/** RN pickLink：url → `/file-proxy/{id}/{name}` 合成。 */
internal fun pickRoomSearchFileLink(file: RoomSearchFileRow): String {
    file.url?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (file.id.isNotEmpty() && file.name.isNotEmpty()) return "/file-proxy/${file.id}/${file.name}"
    return ""
}

/** RN resolveRoomSearchFileUrl：pickLink → ufs/FileSystem:Uploads 换 file-proxy → formatAttachmentUrl。 */
internal fun resolveRoomSearchFileUrl(
    file: RoomSearchFileRow,
    userId: String,
    token: String,
    baseUrl: String,
): String {
    var link = pickRoomSearchFileLink(file)
    if (link.isEmpty()) return ""
    if (link.contains(UFS_UPLOADS)) link = link.replace(UFS_UPLOADS, "file-proxy")
    return cn.appia.im.core.media.AttachmentUrlFormatter.format(link, userId, token, baseUrl)
}

/** RN isVideoCell 判定（media tab 播放角标）：typeGroup video/audio。 */
internal fun isMediaVideoCell(file: RoomSearchFileRow): Boolean =
    file.typeGroup == "video" || file.typeGroup == "audio"

// ── mentions 双层语义（RN useRoomSearch fetchMentions 客户端过滤）──

/** RN mentions 客户端过滤：msg 小写包含关键词。 */
internal fun filterMentionRowsByQuery(rows: List<MessageEntity>, query: String): List<MessageEntity> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return rows.filter { it.msg.orEmpty().lowercase().contains(q) }
}

// ── API wire（RN getRoomFiles / getRoomMessages 逐参；chat.search 复用 T5 ui.fetchChatSearch）──

/** 与 `chat.search` 一致的分页大小（RN useRoomSearch PAGE_SIZE）。 */
internal const val ROOM_SEARCH_PAGE_SIZE = 50

/** $regex 值的 JSON 字符串转义（用户输入进 JSON 串，引号/反斜杠先转义）。 */
internal fun escapeRegexInput(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * RN getRoomFiles：`GET {prefix}.files`，query=`{name:{$regex,$options:'i'},typeGroup}` +
 * sort=`{uploadedAt:-1}`。query/sort 经 RN encodeQuery 的 JSON.stringify 序列化进 query 串。
 */
internal suspend fun fetchRoomFiles(
    sdk: cn.appia.im.core.network.RocketSdk,
    roomId: String,
    roomType: String,
    offset: Int,
    name: String,
    fileType: String,
    count: Int = ROOM_SEARCH_PAGE_SIZE,
): JsonElement? {
    val prefix = cn.appia.im.core.messaging.roomTypeToRestPrefix(roomType)
        ?: throw IllegalArgumentException("Unsupported room type: $roomType")
    val query = """{"name":{"${'$'}regex":"${escapeRegexInput(name)}","${'$'}options":"i"},"typeGroup":"$fileType"}"""
    return sdk.get(
        "$prefix.files",
        mapOf(
            "roomId" to roomId,
            "offset" to offset.toString(),
            "count" to count.toString(),
            "query" to query,
            "sort" to """{"uploadedAt":-1}""",
        ),
    )
}

/** RN getRoomMessages（mentions 源）：`GET {prefix}.messages`，query + sort=`{ts:-1}`。 */
internal suspend fun fetchRoomMentionsPage(
    sdk: cn.appia.im.core.network.RocketSdk,
    roomId: String,
    roomType: String,
    offset: Int,
    currentUserId: String,
    count: Int = ROOM_SEARCH_PAGE_SIZE,
): JsonElement? {
    val prefix = cn.appia.im.core.messaging.roomTypeToRestPrefix(roomType)
        ?: throw IllegalArgumentException("Unsupported room type: $roomType")
    return sdk.get(
        "$prefix.messages",
        mapOf(
            "roomId" to roomId,
            "offset" to offset.toString(),
            "query" to """{"mentions._id":{"${'$'}in":["$currentUserId"]}}""",
            "sort" to """{"ts":-1}""",
        ),
    )
}

/**
 * LIKE 通配符转义（`\ % _`），配合 MessageDao getByRidLikeText 的 `ESCAPE '\'` 成字面匹配。
 * **故意分歧（评审 I1）**：RN watermelondb@0.28 sanitizeLikeString 实为「非字母数字 → `_` 通配」
 * ——中文搜索退化为通配匹配；Android 为字面匹配（修正 RN 缺陷，T1 asBoolean 先例同款裁定）。
 */
internal fun sanitizeLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** RN isApiMessage + parseRoomSearchChatResponse → MessageEntity（applyApiFields，不落库）。 */
internal fun parseRoomSearchChatResponse(raw: JsonElement?, fallbackRid: String): List<MessageEntity> {
    val res = raw as? JsonObject ?: return emptyList()
    if ((res["success"] as? JsonPrimitive)?.contentOrNull == "false") return emptyList()
    val messages = res["messages"] as? JsonArray ?: return emptyList()
    return messages.mapNotNull { el ->
        val obj = cn.appia.im.core.messaging.MessageUpsert.isApiMessage(el) ?: return@mapNotNull null
        val messageRid = (obj["rid"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fallbackRid
        cn.appia.im.core.messaging.MessageUpsert.applyApiFields(prev = null, data = obj, messageRid = messageRid)
    }
}

// ── ViewModel ──

/** RN useRoomSearch 出口状态（六 tab 行集 + 分页元数据）。 */
data class RoomSearchUiState(
    /** 防抖后的搜索词（trim 后）；空 = 未搜索。 */
    val searchText: String = "",
    val loading: Boolean = false,
    val messageRows: List<MessageEntity> = emptyList(),
    val fileRows: List<RoomSearchFileRow> = emptyList(),
    val mediaRows: List<RoomSearchFileRow> = emptyList(),
    val mentionRows: List<MessageEntity> = emptyList(),
    /** members tab 过滤后行（RN filteredMemberRows）。 */
    val memberRows: List<cn.appia.im.feature.chat.RoomMemberRow> = emptyList(),
    val hasMoreMessages: Boolean = false,
    val hasMoreFiles: Boolean = false,
    val hasMoreMedia: Boolean = false,
    val hasMoreMentions: Boolean = false,
)

/**
 * 房间内搜索 VM（RN hooks/useRoomSearch.ts 逐段移植）：
 * - 6/4 tab by 房型（getVisibleRoomSearchTabs）；links tab = messageRows 启发式过滤（不发请求）；
 * - messages：chat.search notIncludeFile:true 分页（offset 累进 + _id 去重 + addedCount==0 止步）；
 *   加密房（encrypted）改本地 LIKE（一次性结果，不分页、loadMore 短路）；
 * - files/media：{prefix}.files name regex query + typeGroup；total 可信走 total，否则页满续；
 * - mentions：{prefix}.messages mentions._id query + 客户端 msg 过滤双层（0 新增止步）；
 * - members：appia/room/members/v2 一次性缓存 + 本地 name/username/jobName 过滤；
 * - 竞态：epoch 计数（RN requestIdRef）+ debounceJob 取消；空词立即清空。
 */
class RoomSearchViewModel(
    private val rid: String,
    private val roomType: String,
    private val encrypted: Boolean,
    private val currentUserId: String?,
    /** 网络缝（装配处 sdk；测试注入 fake——测试不触网）。 */
    private val fetchChatSearch: suspend (searchText: String, offset: Int) -> JsonElement?,
    private val fetchFilesPage: suspend (searchText: String, offset: Int, fileType: String) -> JsonElement?,
    private val fetchMentionsPage: suspend (offset: Int) -> JsonElement?,
    private val fetchMembers: suspend () -> JsonElement?,
    /** 加密房本地 LIKE 数据缝（装配处 MessageDao；null = 测试注入本地行）。 */
    private val localMessageSearch: suspend (rid: String, searchText: String) -> List<MessageEntity>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RoomSearchUiState())
    val state: StateFlow<RoomSearchUiState> = _state.asStateFlow()

    private val isGroupRoom = roomType != "d"

    /** RN visibleTabs（useMemo [t]）。 */
    val visibleTabs: List<RoomSearchTab> = getVisibleRoomSearchTabs(roomType)

    private val _activeTab = MutableStateFlow(resolveInitialRoomSearchTab(roomType, null))
    val activeTab: StateFlow<RoomSearchTab> = _activeTab.asStateFlow()

    private var debounceJob: Job? = null
    private var requestId = 0
    private var messageOffset = 0
    private var fileOffset = 0
    private var mediaOffset = 0
    private var mentionOffset = 0
    private var membersLoaded = false
    private var memberCache: List<cn.appia.im.feature.chat.RoomMemberRow> = emptyList()

    /** RN setActiveTab；切 tab 即刷新（RN effect [activeTab, searchText]）。 */
    fun setActiveTab(tab: RoomSearchTab) {
        if (tab !in visibleTabs) return
        _activeTab.value = tab
        val q = _state.value.searchText
        if (q.isNotEmpty()) {
            debounceJob?.cancel()
            scope.launch { runTabFetch(tab, q, append = false) }
        }
    }

    /** 空词立即清空；非空 300ms 防抖后按当前 tab 搜索（RN useDebouncedTrimmed + effect）。 */
    fun onQueryChanged(text: String) {
        val q = text.trim()
        debounceJob?.cancel()
        if (q.isEmpty()) {
            requestId++
            _state.value = RoomSearchUiState()
            return
        }
        debounceJob = scope.launch {
            delay(ROOM_SEARCH_DEBOUNCE_MS)
            _state.value = _state.value.copy(searchText = q)
            runTabFetch(_activeTab.value, q, append = false)
        }
    }

    private suspend fun runTabFetch(tab: RoomSearchTab, q: String, append: Boolean) {
        when (tab) {
            RoomSearchTab.MESSAGES, RoomSearchTab.LINKS -> fetchMessages(q, append)
            RoomSearchTab.FILES -> fetchFiles(q, "file", append)
            RoomSearchTab.MEDIA -> fetchFiles(q, "media", append)
            RoomSearchTab.MENTIONS -> fetchMentions(q, append)
            RoomSearchTab.MEMBERS -> loadMembersCache(q)
        }
    }

    private suspend fun fetchMessages(searchText: String, append: Boolean) {
        if (append && !canLoadMoreMessages()) return
        val reqId = ++requestId
        _state.value = _state.value.copy(loading = true)
        try {
            if (encrypted) {
                val rows = localMessageSearch(rid, searchText)
                if (reqId != requestId) return
                _state.value = _state.value.copy(messageRows = rows, hasMoreMessages = false)
                return
            }
            val offset = if (append) messageOffset else 0
            if (!append) messageOffset = 0
            val raw = fetchChatSearch(searchText, offset)
            if (reqId != requestId) return
            val rows = parseRoomSearchChatResponse(raw, rid)
            val (merged, added) = if (append) {
                mergeById(_state.value.messageRows, rows) { it._id }
            } else {
                rows to rows.size
            }
            val hasMore = when {
                rows.isEmpty() -> false
                append && added == 0 -> false
                else -> rows.size >= ROOM_SEARCH_PAGE_SIZE
            }
            if (rows.isNotEmpty()) messageOffset = offset + rows.size
            _state.value = _state.value.copy(messageRows = merged, hasMoreMessages = hasMore)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (reqId != requestId) return
            if (!append) _state.value = _state.value.copy(messageRows = emptyList(), hasMoreMessages = false)
        } finally {
            if (reqId == requestId) _state.value = _state.value.copy(loading = false)
        }
    }

    private suspend fun fetchFiles(searchText: String, fileType: String, append: Boolean) {
        val isFile = fileType == "file"
        if (append && (if (isFile) !_state.value.hasMoreFiles else !_state.value.hasMoreMedia)) return
        val reqId = ++requestId
        _state.value = _state.value.copy(loading = true)
        try {
            val offset = if (append) (if (isFile) fileOffset else mediaOffset) else 0
            if (!append) {
                if (isFile) fileOffset = 0 else mediaOffset = 0
            }
            val raw = fetchFilesPage(searchText, offset, fileType)
            if (reqId != requestId) return
            val (page, meta) = parseRoomFilesPage(raw)
            val prev = if (isFile) _state.value.fileRows else _state.value.mediaRows
            val (merged, added) = if (append) mergeById(prev, page) { it.id } else page to page.size
            val hasMore = if (append && added == 0) {
                false
            } else {
                resolveRoomFilesHasMore(merged.size, page, meta, ROOM_SEARCH_PAGE_SIZE)
            }
            if (page.isNotEmpty()) {
                if (isFile) fileOffset = offset + page.size else mediaOffset = offset + page.size
            }
            _state.value = if (isFile) {
                _state.value.copy(fileRows = merged, hasMoreFiles = hasMore)
            } else {
                _state.value.copy(mediaRows = merged, hasMoreMedia = hasMore)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (reqId != requestId) return
            if (!append) {
                _state.value = if (isFile) {
                    _state.value.copy(fileRows = emptyList(), hasMoreFiles = false)
                } else {
                    _state.value.copy(mediaRows = emptyList(), hasMoreMedia = false)
                }
            }
        } finally {
            if (reqId == requestId) _state.value = _state.value.copy(loading = false)
        }
    }

    private suspend fun fetchMentions(searchText: String, append: Boolean) {
        if (!isGroupRoom || currentUserId.isNullOrEmpty()) return
        if (append && !_state.value.hasMoreMentions) return
        val reqId = ++requestId
        _state.value = _state.value.copy(loading = true)
        try {
            val offset = if (append) mentionOffset else 0
            if (!append) mentionOffset = 0
            val raw = fetchMentionsPage(offset)
            if (reqId != requestId) return
            val rows = filterMentionRowsByQuery(parseRoomSearchChatResponse(raw, rid), searchText)
            val (merged, added) = if (append) {
                mergeById(_state.value.mentionRows, rows) { it._id }
            } else {
                rows to rows.size
            }
            val hasMore = when {
                rows.isEmpty() -> false
                append && added == 0 -> false
                else -> rows.size >= ROOM_SEARCH_PAGE_SIZE
            }
            if (rows.isNotEmpty()) mentionOffset = offset + rows.size
            _state.value = _state.value.copy(mentionRows = merged, hasMoreMentions = hasMore)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (reqId != requestId) return
            if (!append) _state.value = _state.value.copy(mentionRows = emptyList(), hasMoreMentions = false)
        } finally {
            if (reqId == requestId) _state.value = _state.value.copy(loading = false)
        }
    }

    /** RN loadMembersCache（一次性缓存）+ filteredMemberRows（每次按词过滤）。 */
    private suspend fun loadMembersCache(searchText: String) {
        if (!isGroupRoom) return
        if (!membersLoaded) {
            try {
                memberCache = cn.appia.im.feature.chat.parseAppiaRoomMembersV2(fetchMembers())
                membersLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                memberCache = emptyList()
            }
        }
        _state.value = _state.value.copy(memberRows = filterRoomMembersByQuery(memberCache, searchText))
    }

    private fun canLoadMoreMessages(): Boolean = _state.value.hasMoreMessages && !encrypted

    /** links tab（RN linksRows = filterMessagesWithLinks(messageRows)）：派生只读。 */
    val linksRows: List<MessageEntity>
        get() = filterMessagesWithLinks(_state.value.messageRows)

    // ── loadMore 入口（RN loadMoreMessages/Files/Media/Mentions；loading 守卫）──

    fun loadMoreMessages() {
        if (!canLoadMoreMessages() || _state.value.loading) return
        scope.launch { fetchMessages(_state.value.searchText, append = true) }
    }

    fun loadMoreFiles() {
        if (!_state.value.hasMoreFiles || _state.value.loading) return
        scope.launch { fetchFiles(_state.value.searchText, "file", append = true) }
    }

    fun loadMoreMedia() {
        if (!_state.value.hasMoreMedia || _state.value.loading) return
        scope.launch { fetchFiles(_state.value.searchText, "media", append = true) }
    }

    fun loadMoreMentions() {
        if (!_state.value.hasMoreMentions || _state.value.loading) return
        scope.launch { fetchMentions(_state.value.searchText, append = true) }
    }

    companion object {
        /** RN useRoomSearch DEBOUNCE_MS = 300。 */
        const val ROOM_SEARCH_DEBOUNCE_MS = 300L
    }
}
