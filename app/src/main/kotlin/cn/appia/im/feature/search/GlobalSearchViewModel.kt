package cn.appia.im.feature.search

import androidx.lifecycle.ViewModel
import cn.appia.im.core.chat.directChatIncludesUsername
import cn.appia.im.core.chat.isGroupDirectChat
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.network.api.FilesSearchApi
import cn.appia.im.feature.chatlist.isSelfDirectAssistantChat
import cn.appia.im.feature.chatlist.roomTitleFromChat
import cn.appia.im.feature.chatlist.parseLastMessageField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** RN GlobalSearchTabId（useGlobalSearch.ts:26）。 */
enum class GlobalSearchTab { ALL, MEMBERS, MESSAGES, FILES }

/** RN GlobalSearchListRow（spotlightV2GlobalSearch.ts:23-32）：联系人 / 频道两型。 */
data class GlobalSearchListRow(
    val key: String,
    val isContact: Boolean,
    val title: String,
    val subtitle: String? = null,
    /** spotlight 联系人 username；contact 行点击走 openDirectMessage 链（rid 是 username 回退，不可直用）。 */
    val username: String? = null,
    val rid: String,
    val roomType: String,
    val avatarName: String? = null,
)

/** RN MessageSearchRow（spotlightV2GlobalSearch.ts:96-102）：消息分区行（房间 + N 条 subtitle）。 */
data class MessageSearchRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val rid: String,
    val roomType: String,
    val avatarName: String? = null,
)

/** 文件行（RN GlobalSearchFileItem 渲染子集；raw 供 DocPreview 链接提取）。 */
data class GlobalSearchFileRow(
    val key: String,
    val name: String,
    val raw: JsonObject,
)

/** RN useGlobalSearch 出口状态（含 spotlight 默认段 + 完整消息段 + files 分页段）。 */
data class GlobalSearchUiState(
    /** 已发搜索的词（trim 后）；空 = 未搜索。 */
    val query: String = "",
    val loading: Boolean = false,
    /** 当前 query 的远端/回退结果已就绪（防抖结束且请求完成，RN isResultsReady）。 */
    val isResultsReady: Boolean = false,
    /** RN banner='error'：远端失败，memberRows 已回退本地分区。 */
    val error: Boolean = false,
    val memberRows: List<GlobalSearchListRow> = emptyList(),
    val messagePreviewRows: List<MessageSearchRow> = emptyList(),
    /** isMessageFull 完整消息行；null = 未加载完成（RN messageRowsFull）。 */
    val messageFullRows: List<MessageSearchRow>? = null,
    val messagesFullLoading: Boolean = false,
    /** spotlight messages.hasMore（预览分区「查看更多」判据之一）。 */
    val messagesPreviewHasMore: Boolean = false,
    val files: List<GlobalSearchFileRow> = emptyList(),
    val filesCursor: String? = null,
    val filesHasMore: Boolean = false,
    val filesLoadingMore: Boolean = false,
    val filesTotalCount: Int = 0,
)

const val GLOBAL_SEARCH_PREVIEW_LIMIT = 3
const val GLOBAL_SEARCH_DEBOUNCE_MS = 300L

/**
 * 全局搜索 VM（RN hooks/useGlobalSearch.ts 逐段移植）：
 * - 300ms 防抖（空词立即清空，RN useDebouncedTrimmed）
 * - spotlight 默认段 → memberRows（users/rooms/usersInRooms）+ messagePreviewRows + files 首页
 * - 就绪后自动预取 isMessageFull 完整消息段（RN :219-247 effect）
 * - files.search cursor 分页（loadMoreFiles）
 * - 竞态守卫：epoch 计数（RN requestIdRef 同义）+ Job 取消双保险
 * - 失败回退本地 chats 分区（RN banner='error' + mapLocalPartitionToRows）
 */
class GlobalSearchViewModel(
    private val fetchGlobalSearch: suspend (String) -> JsonElement?,
    private val fetchMessagesFull: suspend (String) -> JsonElement?,
    private val fetchFilesPage: suspend (String, String?) -> FilesSearchApi.Page,
    chatsFlow: kotlinx.coroutines.flow.Flow<List<ChatEntity>>,
    private val currentUserId: String?,
    private val scope: CoroutineScope,
    /** RN t(key, opts)：args 即 {{k}} 插值表。 */
    private val t: (key: String, args: Map<String, String>) -> String,
    /**
     * 深链 initialQuery（RN route.params.initialQuery，backlog #10 / 评审 I-1）：构造器消费一次——
     * entry 级 VM 活过旋转，构造后不存在重播种入口（旋转/重组无法复活已清空的词）。
     * 词同步入 state（屏侧输入框初值取 state.query，t0 即见词，同 RN useState 初值），
     * 网络部分仍走 300ms 防抖。
     */
    private val initialQuery: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    /**
     * 搜索 VM 生命周期收口（M6 ② / 终审 M-2 / RN unmount-cancel 同义）：协程跑注入 scope
     * （app 级 BackgroundScope，测试注入 TestScope 同款），包一层子 SupervisorJob——
     * popBackStack 随 entry ViewModelStore 释放触发 onCleared → [dispose] 取消在飞
     * 防抖/网络/收集（app 级 scope 本身不可取消）。
     */
    private val vmScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

    /** 本地会话快照（分区回退 + 头像/类型 enrich；RN chats ref）。 */
    private var chats: List<ChatEntity> = emptyList()
    private var debounceJob: Job? = null
    private var messagesFullJob: Job? = null
    private var searchEpoch = 0
    private var messagesFullEpoch = 0
    private var filesEpoch = 0

    init {
        vmScope.launch { chatsFlow.collect { chats = it } }
        // 深链种子：词立即入 state（loading=true 防抖窗口即 pending，RN 同），取词不经过
        // onQueryChanged（其防抖前不动 state.query——竞态守卫测试锁定的行为）
        val q = initialQuery.trim()
        if (q.isNotEmpty()) {
            _state.value = GlobalSearchUiState(query = q, loading = true)
            debounceJob = vmScope.launch {
                delay(GLOBAL_SEARCH_DEBOUNCE_MS)
                runSearch(q)
            }
        }
    }

    /** RN unmount cancel 等价收口（宿主 onCleared 与测试缝共用）。 */
    internal fun dispose() {
        vmScope.cancel()
    }

    override fun onCleared() = dispose()

    /** 空词立即清空；非空 300ms 防抖后搜索（RN useDebouncedTrimmed + effect）。 */
    fun onQueryChanged(text: String) {
        val q = text.trim()
        debounceJob?.cancel()
        if (q.isEmpty()) {
            searchEpoch++; messagesFullEpoch++; filesEpoch++
            _state.value = GlobalSearchUiState()
            return
        }
        debounceJob = vmScope.launch {
            delay(GLOBAL_SEARCH_DEBOUNCE_MS)
            runSearch(q)
        }
    }

    private suspend fun runSearch(q: String) {
        val epoch = ++searchEpoch
        messagesFullJob?.cancel()
        messagesFullEpoch++
        filesEpoch++
        _state.value = GlobalSearchUiState(query = q, loading = true)
        try {
            val raw = (fetchGlobalSearch(q) as? JsonObject) ?: emptySpotlightObject
            if (epoch != searchEpoch) return
            val meta = spotlightFilesMeta(raw)
            _state.value = GlobalSearchUiState(
                query = q,
                loading = false,
                isResultsReady = true,
                memberRows = mapSpotlightV2ToRows(raw, chats, t),
                messagePreviewRows = enrichMessageRows(mapSpotlightMessagesToRows(raw, q, t), chats),
                messagesPreviewHasMore = messagesHasMore(raw),
                files = meta.files.mapIndexed { i, f -> f.toRow(i) },
                filesCursor = meta.nextCursor,
                filesHasMore = meta.hasMore,
                filesTotalCount = meta.totalHint,
            )
            prefetchMessagesFull(q)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (epoch != searchEpoch) return
            val local = localFallbackRows(chats, q, currentUserId, t)
            _state.value = GlobalSearchUiState(
                query = q,
                loading = false,
                isResultsReady = true,
                error = true,
                memberRows = local,
            )
        }
    }

    /** RN :219-247：主响应就绪后预取完整消息段（失败 → 空列表，不阻塞 UI）。 */
    private fun prefetchMessagesFull(q: String) {
        messagesFullJob?.cancel()
        val epoch = ++messagesFullEpoch
        messagesFullJob = vmScope.launch {
            _state.value = _state.value.copy(messagesFullLoading = true, messageFullRows = null)
            try {
                val raw = (fetchMessagesFull(q) as? JsonObject) ?: emptySpotlightObject
                if (epoch != messagesFullEpoch) return@launch
                _state.value = _state.value.copy(
                    messagesFullLoading = false,
                    messageFullRows = enrichMessageRows(mapSpotlightMessagesToRows(raw, q, t), chats),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch != messagesFullEpoch) return@launch
                _state.value = _state.value.copy(messagesFullLoading = false, messageFullRows = emptyList())
            }
        }
    }

    /** RN loadMoreFiles（:306-327）：hasMore/loadingMore 双守卫 + epoch 竞态守卫。 */
    fun loadMoreFiles() {
        val s = _state.value
        if (s.query.isEmpty() || !s.filesHasMore || s.filesLoadingMore) return
        val epoch = ++filesEpoch
        vmScope.launch {
            _state.value = _state.value.copy(filesLoadingMore = true)
            try {
                val page = fetchFilesPage(s.query, s.filesCursor)
                if (epoch != filesEpoch) return@launch
                val existing = _state.value.files
                _state.value = _state.value.copy(
                    files = existing + page.files.mapIndexed { i, f -> f.toRow(existing.size + i) },
                    filesCursor = page.nextCursor,
                    filesHasMore = page.hasMore,
                    filesLoadingMore = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (epoch != filesEpoch) return@launch
                _state.value = _state.value.copy(filesLoadingMore = false)
            }
        }
    }
}

// ── 纯映射（RN spotlightV2GlobalSearch.ts / globalSearchFilter.ts 逐条移植）──

internal val emptySpotlightObject: JsonObject = JsonObject(
    mapOf(
        "users" to JsonArray(emptyList()),
        "rooms" to JsonArray(emptyList()),
        "usersInRooms" to JsonArray(emptyList()),
        "messages" to JsonObject(mapOf("rooms" to JsonArray(emptyList()))),
        "files" to JsonArray(emptyList()),
    ),
)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** RN coerceSubscriptionType：已知 t 保留，未知回退 fallback。 */
internal fun coerceRoomType(raw: String?, fallback: String): String {
    val known = setOf("p", "d", "c", "l", "e2e", "thread", "b")
    return if (raw != null && raw in known) raw else fallback
}

/** RN stripMarkdownLite（globalSearchFormat.ts:4-5）。 */
internal fun stripMarkdownLite(s: String): String =
    s.replace(Regex("\\*\\*|__|~~|`"), "").replace('\n', ' ')

/** RN roomTitleFromSpotlight：fname → dname → name → _id。 */
internal fun roomTitleFromSpotlight(room: JsonObject): String =
    room.str("fname") ?: room.str("dname") ?: room.str("name") ?: (room.str("_id") ?: "")

private fun snippetFromSpotlightLastMessage(room: JsonObject): String? {
    val msg = ((room["lastMessage"] as? JsonObject)?.str("msg")) ?: return null
    val stripped = stripMarkdownLite(msg)
    return if (stripped.isNotEmpty()) stripped.take(120) else null
}

/** RN resolveDmRid：本地 t='d' 非多人直聊含目标用户 → rid；否则回退 username（openDirectMessage 链消费）。 */
internal fun resolveDmRid(username: String, chats: List<ChatEntity>): String {
    val hit = chats.firstOrNull { c ->
        c.t == "d" && !isGroupDirectChat(c) && directChatIncludesUsername(c.usernames, username)
    }
    return hit?.let { it._id.ifEmpty { it.rid } } ?: username
}

/** RN mapSpotlightV2ToRows（:290-347）：users → contact；rooms → channel；usersInRooms 去重补 channel。 */
internal fun mapSpotlightV2ToRows(
    raw: JsonObject,
    chats: List<ChatEntity>,
    t: (key: String, args: Map<String, String>) -> String,
): List<GlobalSearchListRow> {
    val rows = mutableListOf<GlobalSearchListRow>()
    val seenRoomIds = mutableSetOf<String>()

    ((raw["users"] as? JsonArray))?.forEach { value ->
        val u = value as? JsonObject ?: return@forEach
        val id = u.str("_id") ?: return@forEach
        val username = u.str("username").orEmpty()
        val title = u.str("name") ?: username
        rows += GlobalSearchListRow(
            key = "spotlight-user-$id",
            isContact = true,
            title = title,
            username = username.ifEmpty { null },
            rid = resolveDmRid(username, chats),
            roomType = "d",
            avatarName = username.ifEmpty { null },
        )
    }

    ((raw["rooms"] as? JsonArray))?.forEach { value ->
        val room = value as? JsonObject ?: return@forEach
        val id = room.str("_id") ?: return@forEach
        seenRoomIds += id
        rows += GlobalSearchListRow(
            key = "spotlight-room-$id",
            isContact = false,
            title = roomTitleFromSpotlight(room),
            subtitle = snippetFromSpotlightLastMessage(room),
            rid = id,
            roomType = coerceRoomType(room.str("t"), "c"),
            avatarName = room.str("name"),
        )
    }

    ((raw["usersInRooms"] as? JsonArray))?.forEach { value ->
        val block = value as? JsonObject ?: return@forEach
        val room = block["room"] as? JsonObject ?: return@forEach
        val id = room.str("_id") ?: return@forEach
        if (!seenRoomIds.add(id)) return@forEach
        val names = ((block["users"] as? JsonArray) ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.str("name") }
        rows += GlobalSearchListRow(
            key = "spotlight-uir-$id",
            isContact = false,
            title = roomTitleFromSpotlight(room),
            subtitle = t(
                "globalsearch_matchedmembersline",
                mapOf("names" to names.joinToString(t("globalsearch_nameseparator", emptyMap())).ifEmpty { "…" }),
            ),
            rid = id,
            roomType = coerceRoomType(room.str("t"), "p"),
            avatarName = room.str("name"),
        )
    }
    return rows
}

/** RN mapSpotlightMessagesToRows（:195-228）：messages.rooms → 房间行（N 条 subtitle）。 */
internal fun mapSpotlightMessagesToRows(
    raw: JsonObject,
    searchText: String,
    t: (key: String, args: Map<String, String>) -> String,
): List<MessageSearchRow> {
    val rooms = ((raw["messages"] as? JsonObject)?.get("rooms") as? JsonArray) ?: return emptyList()
    val out = mutableListOf<MessageSearchRow>()
    for (value in rooms) {
        val room = value as? JsonObject ?: continue
        val rid = room.str("_id") ?: continue
        val roomType = coerceRoomType(room.str("t"), "c")
        val n = (room["messageLength"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        val avatarName = if (roomType == "d") {
            room.str("username") ?: room.str("name")
        } else {
            room.str("name")
        }
        out += MessageSearchRow(
            key = "msg-room-$rid",
            title = roomTitleFromSpotlight(room),
            subtitle = t("globalsearch_messageroomline", mapOf("count" to n.toString(), "q" to searchText.trim())),
            rid = rid,
            roomType = roomType,
            avatarName = avatarName,
        )
    }
    return out
}

/** RN enrichMessageSearchRowsWithChats（:148-168）：本地会话补 roomType/avatarName。 */
internal fun enrichMessageRows(rows: List<MessageSearchRow>, chats: List<ChatEntity>): List<MessageSearchRow> {
    if (chats.isEmpty()) return rows
    val byRid = buildMap(chats.size * 2) {
        for (c in chats) {
            put(c._id, c)
            if (c.rid.isNotEmpty()) put(c.rid, c)
        }
    }
    return rows.map { row ->
        val chat = byRid[row.rid] ?: return@map row
        row.copy(
            roomType = chat.t,
            avatarName = chat.name.takeIf { it.isNotEmpty() } ?: row.avatarName,
        )
    }
}

/** RN spotlightFilesMeta（useGlobalSearch.ts:43-61）。 */
internal data class SpotlightFilesMeta(
    val files: List<JsonObject>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val totalHint: Int,
)

internal fun spotlightFilesMeta(raw: JsonObject): SpotlightFilesMeta {
    val files = (raw["files"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    return SpotlightFilesMeta(
        files = files,
        nextCursor = raw.str("filesNextCursor"),
        hasMore = (raw["filesHasMore"] as? JsonPrimitive)?.content == "true",
        totalHint = (raw["filesLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: files.size,
    )
}

/** RN messages.hasMore（useGlobalSearch :197 setMessagesPreviewHasMore）。 */
internal fun messagesHasMore(raw: JsonObject): Boolean =
    ((raw["messages"] as? JsonObject)?.get("hasMore") as? JsonPrimitive)?.content == "true"

/** RN fileItemDisplayName（:238-248）：嵌套 file.name → 顶层 name → 嵌套 file._id → _id。 */
internal fun fileItemDisplayName(f: JsonObject): String {
    val nested = f["file"] as? JsonObject
    return nested?.str("name") ?: f.str("name") ?: nested?.str("_id") ?: f.str("_id") ?: ""
}

/** RN fileRowKey（GlobalSearchScreen :223-236）：messageId:fileId 组合去重键。 */
internal fun fileRowKey(f: JsonObject, index: Int): String {
    val nestedFileId = (f["file"] as? JsonObject)?.str("_id")
    val messageId = f.str("messageId") ?: f.str("_id")
    return when {
        messageId != null && nestedFileId != null -> "$messageId:$nestedFileId"
        nestedFileId != null -> nestedFileId
        messageId != null -> "$messageId:$index"
        else -> "file-$index"
    }
}

/** RN fileRowKey index 语义：全列表位次（跨页唯一，RN FlatList keyExtractor index）。 */
internal fun JsonObject.toRow(index: Int): GlobalSearchFileRow = GlobalSearchFileRow(
    key = fileRowKey(this, index),
    name = fileItemDisplayName(this),
    raw = this,
)

// ── 文件链接提取（RN lib/chat/globalSearchFilePreview.ts 逐条移植）──

private fun nestedFileStr(f: JsonObject, key: String): String? =
    (f["file"] as? JsonObject)?.str(key)

/** RN globalSearchUploadFileId：嵌套 file._id → 顶层 _id。 */
internal fun globalSearchUploadFileId(f: JsonObject): String? =
    nestedFileStr(f, "_id") ?: f.str("_id")

private fun attachmentLink(f: JsonObject): String? {
    val first = (f["attachments"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    return first.str("title_link") ?: first.str("titleLink") ?: first.str("url") ?: first.str("path")
}

/** RN pickGlobalSearchFileLink：url→path→title_link→fileUrl→link→downloadUrl→嵌套 file.*→AmazonS3.path→附件→合成 file-proxy。 */
internal fun pickGlobalSearchFileLink(f: JsonObject): String? {
    val fileId = globalSearchUploadFileId(f)
    val fileName = nestedFileStr(f, "name") ?: f.str("name")
    val synthesized = if (fileId != null && fileName != null) "/file-proxy/$fileId/$fileName" else null
    return f.str("url")
        ?: f.str("path")
        ?: f.str("title_link")
        ?: f.str("fileUrl")
        ?: f.str("link")
        ?: f.str("downloadUrl")
        ?: nestedFileStr(f, "url")
        ?: nestedFileStr(f, "path")
        ?: nestedFileStr(f, "link")
        ?: nestedFileStr(f, "title_link")
        ?: nestedFileStr(f, "fileUrl")
        ?: (f["AmazonS3"] as? JsonObject)?.str("path")
        ?: attachmentLink(f)
        ?: synthesized
}

// ── 离线回退（RN globalSearchFilter.ts + mapLocalPartitionToRows）──

/** RN chatMatchesGlobalSearchQuery：标题小写包含。 */
internal fun chatMatchesQuery(chat: ChatEntity, q: String, currentUserId: String?): Boolean {
    if (q.isEmpty()) return false
    val title = roomTitleFromChat(chat, currentUserId, "")
    return title.lowercase().contains(q.lowercase())
}

/**
 * RN partitionChatsForGlobalSearch：联系人 = t='d' 非自助手命中；频道 = c/p/l 命中。
 * 返回 (contacts, channels)。
 */
internal fun partitionChatsForGlobalSearch(
    chats: List<ChatEntity>,
    rawQuery: String,
    currentUserId: String?,
): Pair<List<ChatEntity>, List<ChatEntity>> {
    val q = rawQuery.trim()
    val match = { c: ChatEntity -> chatMatchesQuery(c, q, currentUserId) }
    val contacts = chats.filter { c ->
        c.t == "d" && !isSelfDirectAssistantChat(c, currentUserId) && match(c)
    }
    val channels = chats.filter { c -> (c.t == "c" || c.t == "p" || c.t == "l") && match(c) }
    return contacts to channels
}

/** RN mapLocalPartitionToRows（:350-373）：本地分区 → 与远端同构的行。 */
internal fun localFallbackRows(
    chats: List<ChatEntity>,
    q: String,
    currentUserId: String?,
    t: (key: String, args: Map<String, String>) -> String,
): List<GlobalSearchListRow> {
    val (contacts, channels) = partitionChatsForGlobalSearch(chats, q, currentUserId)
    val out = mutableListOf<GlobalSearchListRow>()
    for (c in contacts) {
        val parts = listOfNotNull(
            c.description?.trim()?.takeIf { it.isNotEmpty() },
            c.topic?.trim()?.takeIf { it.isNotEmpty() },
        )
        out += GlobalSearchListRow(
            key = "local-dm-${c._id}",
            isContact = true,
            title = roomTitleFromChat(c, currentUserId, t("agent", emptyMap())),
            subtitle = parts.joinToString(" · ").ifEmpty { null },
            rid = c._id,
            roomType = c.t,
            avatarName = c.name.takeIf { it.isNotEmpty() },
        )
    }
    for (c in channels) {
        out += GlobalSearchListRow(
            key = "local-ch-${c._id}",
            isContact = false,
            title = roomTitleFromChat(c, currentUserId, t("agent", emptyMap())),
            subtitle = channelSubtitleFromChat(c, t),
            rid = c._id,
            roomType = c.t,
            avatarName = c.name.takeIf { it.isNotEmpty() },
        )
    }
    return out
}

/** RN channelSubtitleFromChat（globalSearchFormat.ts:17-30）：「姓名：消息」/ roomItem_noMessage。 */
internal fun channelSubtitleFromChat(
    c: ChatEntity,
    t: (key: String, args: Map<String, String>) -> String,
): String {
    val lm = parseLastMessageField(c.last_message)
    val u = lm?.u ?: return t("roomitem_nomessage", emptyMap())
    val msg = lm.msg?.let { stripMarkdownLite(it) }.orEmpty()
    val name = u.name?.trim()?.takeIf { it.isNotEmpty() } ?: u.username?.trim().orEmpty()
    return ("$name：$msg").trim().ifEmpty { t("roomitem_nomessage", emptyMap()) }
}

/** {{k}} 插值（RN i18next opts 等价；screen 组装 t() 时使用）。 */
internal fun interpolate(template: String, args: Map<String, String>): String =
    args.entries.fold(template) { acc, (k, v) -> acc.replace("{{$k}}", v) }
