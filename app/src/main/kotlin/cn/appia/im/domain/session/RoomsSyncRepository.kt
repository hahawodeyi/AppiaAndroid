package cn.appia.im.domain.session

import androidx.room.withTransaction
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.database.dao.ChatDao
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.domain.chat.ChatMerger
import cn.appia.im.domain.chat.ChatMerger.MergedChatRow
import cn.appia.im.domain.chat.ChatMerger.applyMergedChatFields
import cn.appia.im.domain.chat.ChatMerger.dedupeMergedChatsById
import cn.appia.im.domain.chat.ChatMerger.merge
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话同步落库：GET `subscriptions.get` + `rooms.get` 合并写 `chats`。
 * 逐行移植 appiaMobile：
 * - `services/api/chat.ts:14-29`（两端点并发、updatedSince）
 * - `lib/chat/roomSyncFromApi.ts:16-223`（响应三形态 + remove、activeDbMatchesAuth 守卫、
 *   只查涉及 rid、create/update/delete 三分、全量 prune、500/批）
 * - `services/realtime/session.ts:442-485`（有游标走增量；**增量空包自动全量重拉一次**，不循环；
 *   成功同步后写游标 = 本次开始时间）
 *
 * activeDbMatchesAuth 的 Kotlin 落法（绑定裁定）：本仓库构造时绑定目标 server（会话层在换服后
 * 重建实例），写入前校验 `DatabaseManager.active` 就是该 server 的库，不匹配则跳过并返回 false。
 */
class RoomsSyncRepository(
    private val sdk: RocketSdk,
    private val dbManager: DatabaseManager,
    private val kv: KvStore,
    private val serverUrl: String,
) {

    /** RN SyncChatsFromRestOptions.source：pull 下拉全量；bootstrap/background 有游标走增量。 */
    enum class Mode { PULL, BOOTSTRAP, BACKGROUND }

    private val cursor = RoomsSyncCursor(kv)

    private fun dao(): ChatDao = dbManager.active.chatDao()

    /** RN activeDbMatchesAuth 守卫等价：active 库必须就是本 repo 绑定 server 的库。 */
    private fun activeDbMatchesAuth(): Boolean =
        dbManager.active === dbManager.databaseFor(dbManager.normalizeServer(serverUrl))

    /** RN getRooms chat.ts:14-29：两端点并发（Promise.all 等价），updatedSince 为 ISO 串。 */
    private suspend fun getRooms(updatedSince: String?): Pair<JsonElement, JsonElement> =
        coroutineScope {
            if (updatedSince == null) {
                val subs = async { sdk.get("subscriptions.get") }
                val rooms = async { sdk.get("rooms.get") }
                subs.await() to rooms.await()
            } else {
                val params = mapOf("updatedSince" to updatedSince)
                val subs = async { sdk.get("subscriptions.get", params) }
                val rooms = async { sdk.get("rooms.get", params) }
                subs.await() to rooms.await()
            }
        }

    /**
     * 同步入口：拉取 + 落库 + 推游标。
     * @return 是否有变更落库（RN persisted）。无变更/被守卫跳过均返回 false，且不推游标。
     */
    suspend fun sync(mode: Mode = Mode.BACKGROUND): Boolean {
        val since = if (mode == Mode.PULL) null else cursor.get(serverUrl)
        val syncStartedAt = System.currentTimeMillis()

        val (subs0, rooms0) = if (since != null) getRooms(since) else getRooms(null)
        // 无游标 → 全量：服务端返回完整订阅集合，可 prune 本地多余行
        var isFullFetch = since == null
        var subscriptions = subs0
        var rooms = rooms0

        // 增量空包自动全量重拉一次（RN session.ts:464-471）；只重拉一次，不循环
        if (since != null && mode != Mode.PULL && !roomListRestPayloadHasChanges(subscriptions, rooms)) {
            val retry = getRooms(null)
            subscriptions = retry.first
            rooms = retry.second
            isFullFetch = true
        }

        val persisted = persist(subscriptions, rooms, isFullFetch)

        // RN session.ts:482-484：仅在守卫通过且有落库时推进游标（记录本次开始时间）
        if (persisted && activeDbMatchesAuth()) {
            cursor.set(serverUrl, syncStartedAt)
        }
        return persisted
    }

    /**
     * RN persistChatsFromSubscriptionsAndRooms roomSyncFromApi.ts:108-223。
     * 只查涉及 rid；字段级 diff 后 update（等价 mergedRowNeedsUpdate：这里对防御集合应用后的
     * 整个 ChatEntity 做列级 equals，比 RN 抽样 7 字段更严——只在真有列变化时才写库）；
     * 全量 prune（本地有服务端无），增量绝不 prune；写库分块 500。
     */
    suspend fun persist(
        subscriptionsPayload: JsonElement?,
        roomsPayload: JsonElement?,
        isFullFetch: Boolean,
    ): Boolean {
        val ids = collectRoomListSyncIds(subscriptionsPayload, roomsPayload)
        if (ids.isEmpty()) return false

        if (!activeDbMatchesAuth()) {
            android.util.Log.w(TAG, "skip persist: active db != auth serverUrl")
            return false
        }

        val subs = extractUpdateList(subscriptionsPayload)
        val roomById = HashMap<String, JsonObject>()
        for (r in extractUpdateList(roomsPayload)) roomById[r.str("_id")?.takeIf { it.isNotEmpty() } ?: continue] = r

        // rid 缺失跳过；merge 抛错（仅缺 rid 一途）跳过——RN 同款 try/catch
        val merged = ArrayList<MergedChatRow>()
        for (s in subs) {
            if (s.str("rid").isNullOrEmpty()) continue
            runCatching { merge(s, roomById[s.str("rid")]) }.getOrNull()?.let { merged.add(it) }
        }

        val deduped = dedupeMergedChatsById(merged)
        val removedRids = removeChatIds(extractRemoveList(subscriptionsPayload))

        val dao = dao()
        // SQLite 变量上限 999：IN 查询按批分块（RN Watermelon Q.oneOf 自行分块）
        val existing = ids.chunked(BATCH_SIZE).flatMap { dao.getByIds(it) }
        val existingById = existing.associateBy { it._id }
        val rowById = deduped.associateBy { it._id }
        val removedIdSet = removedRids.toSet()

        val toCreate = deduped.filter { !existingById.containsKey(it._id) }
            .map { applyMergedChatFields(null, it) }
        val toUpdate = existing.mapNotNull { model ->
            rowById[model._id]?.let { row ->
                val next = applyMergedChatFields(model, row)
                next.takeIf { it != model } // 字段级 diff：无列变化不写（mergedRowNeedsUpdate 等价）
            }
        }
        val toDelete = existing.filter { it._id in removedIdSet }

        // 全量拉取：服务端返回当前完整订阅集合，本地多余（离线期间被解散等）需裁剪；
        // 增量绝不能走此路径——增量只含变更项（RN :163-173）
        var toPrune: List<ChatEntity> = emptyList()
        if (isFullFetch && deduped.isNotEmpty()) {
            val serverRids = deduped.map { it._id }.toSet()
            toPrune = dao.getAll().filter { it._id !in serverRids && it._id !in removedIdSet }
        }

        if (toCreate.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty() && toPrune.isEmpty()) {
            return false
        }

        val db = dbManager.active
        db.withTransaction {
            toCreate.chunked(BATCH_SIZE).forEach { dao.insertAll(it) }
            toUpdate.chunked(BATCH_SIZE).forEach { dao.updateAll(it) }
            toDelete.chunked(BATCH_SIZE).forEach { dao.deleteAll(it) }
            toPrune.chunked(BATCH_SIZE).forEach { dao.deleteAll(it) }
        }

        // RN :214-220 syncVoiceCallMsgFromRoom（语音通话消息域）属 M6，此处不接
        return true
    }

    companion object {
        private const val TAG = "roomSync"
        const val BATCH_SIZE = 500
    }
}

/** RN extractUpdateList：兼容 `update[] / subscriptions[] / rooms[] / 裸数组` 四形态。 */
internal fun extractUpdateList(payload: JsonElement?): List<JsonObject> = when (payload) {
    null, is JsonNull -> emptyList()
    is JsonArray -> payload.filterIsInstance<JsonObject>()
    is JsonObject -> listOf("update", "subscriptions", "rooms")
        .firstNotNullOfOrNull { key -> payload[key] as? JsonArray }
        ?.filterIsInstance<JsonObject>()
        ?: emptyList()
    else -> emptyList()
}

/** RN extractRemoveList：`remove[]` 中的对象项。 */
internal fun extractRemoveList(payload: JsonElement?): List<JsonObject> {
    val remove = (payload as? JsonObject)?.get("remove") as? JsonArray ?: return emptyList()
    return remove.filterIsInstance<JsonObject>()
}

/** RN removeChatIds：REST remove 项优先用 `rid`。 */
internal fun removeChatIds(removeList: List<JsonObject>): List<String> =
    removeList.mapNotNull { it.str("rid")?.takeIf { rid -> rid.isNotEmpty() } }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** RN collectRoomListSyncIds：本次 update 合并行 id + remove rid 的去重并集。 */
internal fun collectRoomListSyncIds(subscriptionsPayload: JsonElement?, roomsPayload: JsonElement?): List<String> {
    val roomById = HashMap<String, JsonObject>()
    for (r in extractUpdateList(roomsPayload)) {
        r.str("_id")?.let { roomById[it] = r }
    }
    val mergedIds = ArrayList<String>()
    for (s in extractUpdateList(subscriptionsPayload)) {
        val rid = s.str("rid") ?: continue
        runCatching { merge(s, roomById[rid]) }.getOrNull()?.let { mergedIds.add(it._id) }
    }
    val removedRids = removeChatIds(extractRemoveList(subscriptionsPayload))
    return LinkedHashSet(mergedIds + removedRids).toList()
}

/** RN roomListRestPayloadHasChanges：合并后是否存在 update 或 remove（增量空包判定用）。 */
internal fun roomListRestPayloadHasChanges(subscriptionsPayload: JsonElement?, roomsPayload: JsonElement?): Boolean =
    collectRoomListSyncIds(subscriptionsPayload, roomsPayload).isNotEmpty()

/** RN lib/chat/roomsSyncCursor.ts：MMKV `rooms-sync-cursor`，key `roomsUpdatedAt:${server}`，存 ISO 串。 */
class RoomsSyncCursor(private val kv: KvStore) {

    private fun key(serverUrl: String): String = "roomsUpdatedAt:${serverUrl.trimEnd('/')}"

    /** 无值或非法 ISO 返回 null（RN getRoomsUpdatedAt 的 NaN → undefined）。 */
    fun get(serverUrl: String): String? {
        val raw = kv.getString(key(serverUrl), "")
        if (raw.isEmpty()) return null
        return parseIsoToMillis(raw)?.let { raw }
    }

    /** 成功同步后写入本次开始时间（毫秒，对齐旧版 servers 表 roomsUpdatedAt）。 */
    fun set(serverUrl: String, atMillis: Long) {
        kv.putString(key(serverUrl), ChatMerger.formatIsoMillis(atMillis))
    }

    fun clear(serverUrl: String) {
        kv.remove(key(serverUrl))
    }

    companion object {
        internal fun parseIsoToMillis(raw: String): Long? = ChatMerger.parseIsoMillis(raw)?.toLong()
    }
}
