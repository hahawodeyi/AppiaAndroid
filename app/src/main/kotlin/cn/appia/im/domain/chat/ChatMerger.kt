package cn.appia.im.domain.chat

import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 逐行移植 appiaMobile `src/database/mergeSubscriptionAndRoom.ts`（对照旧版 ios
 * `mergeSubscriptionsRooms.ts`）：以 subscription 为底、room 覆盖房间侧字段，
 * subscription 的 `lastMessage` 最后再覆盖。
 *
 * 合并结果写 `chats`：`_id`/`rid` = 房间 id（M0 语义：`sub.rid || room._id`），
 * `subscription_doc_id` = subscription 文档 `_id`（RN merged `updatedAt` → 列 `subscription_updated_at`）。
 *
 * 列映射对账：RN CHAT_ASSIGN_KEYS 84 键 → ChatEntity 84 列（实体另有客户端独有 `draft_attachments`
 * 不在键集、永不覆盖）；`updatedAt` → `subscription_updated_at`，其余 camelCase→snake_case，
 * todoCount/onCallStatus 等 Appia 列保持原名（与 schema.ts 列名一致）。
 */
object ChatMerger {

    /** RN types/subscriptionType.ts：t 已知值集，未知回退 CHANNEL。 */
    object SubscriptionType {
        const val GROUP = "p"
        const val DIRECT = "d"
        const val CHANNEL = "c"
        const val OMNICHANNEL = "l"
        const val E2E = "e2e"
        const val THREAD = "thread"
        const val BOT = "b"
        val KNOWN = setOf(GROUP, DIRECT, CHANNEL, OMNICHANNEL, E2E, THREAD, BOT)
    }

    /**
     * merge 过程载体：RN 为普通对象逐字段赋值，这里用 var 数据载体等价表达。
     * 非空类型 = RN MergedChatFields 的非 optional 字段（merge 末尾强制归一）。
     */
    class MergedChatRow(
        val _id: String,
        val rid: String,
        var subscriptionDocId: String? = null,
        var f: Boolean? = null,
        var t: String? = null,
        var ts: Double = 0.0,
        var ls: Double = 0.0,
        var name: String = "",
        var fname: String = "",
        var open: Boolean? = null,
        var alert: Boolean? = null,
        var roles: String? = null,
        var unread: Double? = null,
        var userMentions: Double = 0.0,
        var groupMentions: Double = 0.0,
        var tunread: String? = null,
        var tunreadUser: String? = null,
        var tunreadGroup: String? = null,
        var lm: Double? = null,
        var roomUpdatedAt: Double = 0.0,
        var updatedAt: Double? = null,
        var ro: Boolean = false,
        var lastOpen: Double? = null,
        var lastMessage: String? = null,
        var description: String? = null,
        var announcement: String? = null,
        var announcements: String? = null,
        var roomValueProposition: String? = null,
        var bannerClosed: Boolean? = null,
        var topic: String? = null,
        var blocked: Boolean = false,
        var blocker: Boolean = false,
        var reactWhenReadOnly: Boolean? = null,
        var archived: Boolean = false,
        var joinCodeRequired: Boolean? = null,
        var muted: String? = null,
        var ignored: String? = null,
        var broadcast: Boolean? = null,
        var prid: String? = null,
        var draftMessage: String? = null,
        var draftMessagePlain: String? = null,
        var draftReplyMessageId: String? = null,
        var lastThreadSync: Double? = null,
        var jitsiTimeout: Double? = null,
        var autoTranslate: Boolean = false,
        var autoTranslateLanguage: String = "",
        var hideUnreadStatus: Boolean? = null,
        var disableNotifications: Boolean? = null,
        var sysMes: String? = null,
        var uids: String? = null,
        var usernames: String? = null,
        var visitor: String? = null,
        var departmentId: String? = null,
        var servedBy: String? = null,
        var livechatData: String? = null,
        var tags: String? = null,
        var e2eKey: String? = null,
        var e2eSuggestedKey: String? = null,
        var encrypted: Boolean? = null,
        var e2eKeyId: String? = null,
        var avatarEtag: String? = null,
        var teamId: String = "",
        var teamMain: Boolean? = null,
        var onHold: Boolean? = null,
        var source: String? = null,
        var hideMentionStatus: Boolean = false,
        var usersCount: Double? = null,
        var federated: Boolean? = null,
        var rt: String? = null,
        var todoCount: Double? = null,
        var dname: String? = null,
        var onCallStatus: Boolean? = null,
        var callMsg: String? = null,
        var bot: Boolean? = null,
        var welcomeMsg: String? = null,
        var memberName: String? = null,
        var memberNumber: Double? = null,
        var showAppiaTag: Double? = null,
        var isRoomToDo: Boolean? = null,
        var highTodoCount: Double? = null,
        var defaultTodoCount: Double? = null,
        var like: Boolean? = null,
        var tSearch: Double? = null,
        var appiaUsage: String? = null,
    )

    enum class DedupeChatStrategy { LAST_IN_BATCH, LATEST_ROOM_UPDATED }

    // ---- JSON 读取助手（RN read/str/optStr/num/optNum/bool/optBool/… 的逐行等价） ----

    /** RN read：按序取第一个非 undefined/null 的键值。 */
    private fun read(obj: JsonObject?, keys: List<String>): JsonElement? {
        if (obj == null) return null
        for (k in keys) {
            val v = obj[k]
            if (v != null && v !is JsonNull) return v
        }
        return null
    }

    /**
     * JS Date.parse 的务实子集（RC 服务端恒为 ISO-Z）：优先带偏移（XXX 兼容 `Z`/`+08:00`），
     * 再无偏移/date-only 按 UTC。SimpleDateFormat 每次 new（非线程安全）；minSdk 24 无 java.time 可用。
     */
    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )

    internal fun parseIsoMillis(s: String): Double? {
        for (pattern in ISO_PATTERNS) {
            val fmt = SimpleDateFormat(pattern, Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            try {
                return fmt.parse(s)!!.time.toDouble()
            } catch (_: ParseException) {
                // 换下一形态
            }
        }
        return null
    }

    /** toISOString 等价：UTC ISO-8601 毫秒（游标存储格式，RN roomsSyncCursor 同为 ISO 串）。 */
    internal fun formatIsoMillis(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    private fun JsonElement?.asStringValue(): String? =
        (this as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }

    private fun str(obj: JsonObject?, keys: List<String>, fallback: String = ""): String =
        optStr(obj, keys) ?: fallback

    private fun optStr(obj: JsonObject?, keys: List<String>): String? = read(obj, keys).asStringValue()

    private fun optNum(obj: JsonObject?, keys: List<String>): Double? {
        val v = read(obj, keys) as? JsonPrimitive ?: return null
        if (!v.isString) return v.content.toDoubleOrNull() // JS number（JSON 无 NaN）
        if (v.content.isEmpty()) return null
        parseIsoMillis(v.content)?.let { return it } // JS：先 Date.parse 再 Number()
        return v.content.toDoubleOrNull()
    }

    private fun num(obj: JsonObject?, keys: List<String>, fallback: Double = 0.0): Double =
        optNum(obj, keys) ?: fallback

    /** RN bool：仅接受真 boolean（JS typeof 检查），否则 fallback。 */
    private fun bool(obj: JsonObject?, keys: List<String>, fallback: Boolean = false): Boolean =
        read(obj, keys)?.let { v -> if (v is JsonPrimitive && !v.isString) v.booleanOrNull else null } ?: fallback

    private fun optBool(obj: JsonObject?, keys: List<String>): Boolean? =
        read(obj, keys)?.let { v -> if (v is JsonPrimitive && !v.isString) v.booleanOrNull else null }

    /** RN optNotificationBool：rooms.saveNotification 增量常见 '1'/'0' 或 0/1。 */
    private fun optNotificationBool(obj: JsonObject?, keys: List<String>): Boolean? {
        val v = read(obj, keys) as? JsonPrimitive ?: return null
        if (!v.isString) {
            v.booleanOrNull?.let { return it }
            return when (v.content.toDoubleOrNull()) {
                1.0 -> true
                0.0 -> false
                else -> null
            }
        }
        return when (v.content) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    /** RN optSubscriptionType：增量补丁无 `t` 时返回 null（不默认 c，防助手私聊被挤出「我的助手」段）。 */
    private fun optSubscriptionType(obj: JsonObject?, keys: List<String>): String? {
        val raw = read(obj, keys).asStringValue() ?: return null
        if (raw.isEmpty()) return null
        return if (raw in SubscriptionType.KNOWN) raw else SubscriptionType.CHANNEL
    }

    private fun toMillis(v: JsonElement?): Double? = when (v) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (v.isString) parseIsoMillis(v.content) else v.content.toDoubleOrNull()
        is JsonObject -> toMillis(v["\$date"]) // Mongo EJSON 兼容
        is JsonArray -> null
    }

    /** RN optJson：与旧版 `@json` 列一致，对象/数组落库为原文串；字符串原样。 */
    private fun optJson(v: JsonElement?): String? = when (v) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.content
        else -> v.toString()
    }

    private fun optJsonField(obj: JsonObject?, keys: List<String>): String? = optJson(read(obj, keys))

    private fun lastMessageTs(msg: JsonElement?): Double? = toMillis(read(msg as? JsonObject, listOf("ts")))

    /** RN hasTruthyLastMessage：lastMessage 存在且（非字符串或非空串）才算真值。 */
    private fun hasTruthyLastMessage(sub: JsonObject): Boolean {
        val lm = read(sub, listOf("lastMessage", "last_message")) ?: return false
        return when (lm) {
            is JsonPrimitive -> if (lm.isString) lm.content.isNotEmpty() else true
            else -> true
        }
    }

    /** 空 roles/ignored 数组归一为 null（RN subscriptionBase 同款三分支）。 */
    private fun optJsonOrEmptyToNull(raw: JsonElement?): String? =
        if (raw == null) {
            null
        } else if (raw is JsonArray && raw.isEmpty()) {
            null
        } else {
            optJson(raw)
        }

    private fun subscriptionBase(sub: JsonObject, id: String, subscriptionDocId: String?): MergedChatRow =
        MergedChatRow(
            _id = id,
            rid = id,
            subscriptionDocId = subscriptionDocId,
            f = optBool(sub, listOf("f")),
            t = optSubscriptionType(sub, listOf("t")),
            ts = num(sub, listOf("ts")),
            ls = num(sub, listOf("ls")),
            name = str(sub, listOf("name")),
            fname = str(sub, listOf("fname")),
            open = optBool(sub, listOf("open")),
            alert = optBool(sub, listOf("alert")),
            roles = optJsonOrEmptyToNull(read(sub, listOf("roles"))),
            unread = optNum(sub, listOf("unread")),
            userMentions = num(sub, listOf("userMentions", "user_mentions")),
            groupMentions = num(sub, listOf("groupMentions", "group_mentions")),
            tunread = optJsonField(sub, listOf("tunread")),
            tunreadUser = optJsonField(sub, listOf("tunreadUser", "tunread_user")),
            tunreadGroup = optJsonField(sub, listOf("tunreadGroup", "tunread_group")),
            lastOpen = optNum(sub, listOf("lastOpen", "last_open")),
            lastMessage = optJsonField(sub, listOf("lastMessage", "last_message")),
            description = optStr(sub, listOf("description")),
            announcement = optJsonField(sub, listOf("announcement")),
            announcements = optJsonField(sub, listOf("announcements")),
            roomValueProposition = optJsonField(sub, listOf("roomValueProposition", "room_value_proposition")),
            bannerClosed = optBool(sub, listOf("bannerClosed", "banner_closed")),
            topic = optStr(sub, listOf("topic")),
            blocked = optBool(sub, listOf("blocked")) ?: false,
            blocker = optBool(sub, listOf("blocker")) ?: false,
            reactWhenReadOnly = optBool(sub, listOf("reactWhenReadOnly", "react_when_read_only")),
            archived = bool(sub, listOf("archived")),
            joinCodeRequired = optBool(sub, listOf("joinCodeRequired", "join_code_required")),
            muted = optJsonField(sub, listOf("muted")),
            ignored = optJsonOrEmptyToNull(read(sub, listOf("ignored"))),
            broadcast = optBool(sub, listOf("broadcast")),
            prid = optStr(sub, listOf("prid")),
            draftMessage = optStr(sub, listOf("draftMessage", "draft_message")),
            draftMessagePlain = optStr(sub, listOf("draftMessagePlain", "draft_message_plain")),
            draftReplyMessageId = optStr(sub, listOf("draftReplyMessageId", "draft_reply_msg_id")),
            lastThreadSync = optNum(sub, listOf("lastThreadSync", "last_thread_sync")),
            jitsiTimeout = optNum(sub, listOf("jitsiTimeout", "jitsi_timeout")),
            autoTranslate = optBool(sub, listOf("autoTranslate", "auto_translate")) ?: false,
            autoTranslateLanguage = str(sub, listOf("autoTranslateLanguage", "auto_translate_language")),
            hideUnreadStatus = optNotificationBool(sub, listOf("hideUnreadStatus", "hide_unread_status")),
            disableNotifications = optNotificationBool(sub, listOf("disableNotifications", "disable_notifications")),
            sysMes = optJsonField(sub, listOf("sysMes", "sys_mes")),
            uids = optJsonField(sub, listOf("uids")),
            usernames = optJsonField(sub, listOf("usernames")),
            visitor = optJsonField(sub, listOf("visitor", "v")),
            departmentId = optStr(sub, listOf("departmentId", "department_id")),
            servedBy = optJsonField(sub, listOf("servedBy", "served_by")),
            livechatData = optJsonField(sub, listOf("livechatData", "livechat_data")),
            tags = optJsonField(sub, listOf("tags")),
            e2eKey = optStr(sub, listOf("e2eKey", "e2e_key")),
            e2eSuggestedKey = optStr(sub, listOf("e2eSuggestedKey", "e2e_suggested_key")),
            encrypted = optBool(sub, listOf("encrypted")),
            e2eKeyId = optStr(sub, listOf("e2eKeyId", "e2e_key_id")),
            avatarEtag = optStr(sub, listOf("avatarETag", "avatar_etag")),
            teamId = str(sub, listOf("teamId", "team_id")),
            teamMain = optBool(sub, listOf("teamMain", "team_main")),
            onHold = optBool(sub, listOf("onHold", "on_hold")),
            source = optJsonField(sub, listOf("source")),
            hideMentionStatus = bool(sub, listOf("hideMentionStatus", "hide_mention_status")),
            usersCount = optNum(sub, listOf("usersCount", "users_count")),
            federated = optBool(sub, listOf("federated")),
            rt = optStr(sub, listOf("rt")),
            todoCount = optNum(sub, listOf("todoCount")),
            dname = optStr(sub, listOf("dname")),
            onCallStatus = optBool(sub, listOf("onCallStatus")),
            callMsg = optStr(sub, listOf("callMsg")),
            bot = optBool(sub, listOf("bot")),
            welcomeMsg = optStr(sub, listOf("welcomeMsg")),
            memberName = optStr(sub, listOf("memberName")),
            memberNumber = optNum(sub, listOf("memberNumber")),
            showAppiaTag = optNum(sub, listOf("showAppiaTag")),
            isRoomToDo = optBool(sub, listOf("isRoomToDo")),
            highTodoCount = optNum(sub, listOf("highTodoCount")),
            defaultTodoCount = optNum(sub, listOf("defaultTodoCount")),
            like = optBool(sub, listOf("like")),
            tSearch = optNum(sub, listOf("tSearch")),
            appiaUsage = optJsonField(sub, listOf("appiaUsage")),
        )

    /** RN applyRoomToMerged：room 文档覆盖房间侧字段（第一段以 room `_updatedAt` 存在为门）。 */
    private fun applyRoomToMerged(m: MergedChatRow, rm: JsonObject, sub: JsonObject) {
        val roomUpdated = read(rm, listOf("_updatedAt", "_updated_at"))
        if (roomUpdated != null) {
            val roomLm = optJsonField(rm, listOf("lastMessage", "last_message"))
            if (roomLm != null) m.lastMessage = roomLm
            m.description = optStr(rm, listOf("description")) ?: m.description
            m.topic = optStr(rm, listOf("topic")) ?: m.topic
            m.announcement = optJsonField(rm, listOf("announcement")) ?: m.announcement
            m.reactWhenReadOnly =
                optBool(rm, listOf("reactWhenReadOnly", "react_when_read_only")) ?: m.reactWhenReadOnly
            m.archived = bool(rm, listOf("archived")) || m.archived
            m.joinCodeRequired =
                optBool(rm, listOf("joinCodeRequired", "join_code_required")) ?: m.joinCodeRequired
            m.jitsiTimeout = optNum(rm, listOf("jitsiTimeout", "jitsi_timeout")) ?: m.jitsiTimeout
            m.usernames = optJsonField(rm, listOf("usernames")) ?: m.usernames
            m.uids = optJsonField(rm, listOf("uids")) ?: m.uids
            m.callMsg = optStr(rm, listOf("callMsg")) ?: m.callMsg
            m.onCallStatus = optBool(rm, listOf("onCallStatus")) ?: m.onCallStatus
            optNum(rm, listOf("ts"))?.let { m.ts = it }
        }

        // subscription 有真值 lastMessage 时最后覆盖（RN 同序）
        if (hasTruthyLastMessage(sub)) {
            optJsonField(sub, listOf("lastMessage", "last_message"))?.let { m.lastMessage = it }
        }

        m.ro = bool(rm, listOf("ro"))
        if (rm.containsKey("broadcast")) m.broadcast = optBool(rm, listOf("broadcast"))
        m.encrypted = optBool(rm, listOf("encrypted")) ?: m.encrypted
        m.e2eKeyId = optStr(rm, listOf("e2eKeyId", "e2e_key_id")) ?: m.e2eKeyId
        m.avatarEtag = optStr(rm, listOf("avatarETag", "avatar_etag")) ?: m.avatarEtag
        m.teamId = str(rm, listOf("teamId", "team_id"), m.teamId)
        m.teamMain = optBool(rm, listOf("teamMain", "team_main")) ?: m.teamMain

        val subRoles = read(sub, listOf("roles"))
        if (subRoles == null || (subRoles is JsonArray && subRoles.isEmpty())) m.roles = null

        val subIgnored = read(sub, listOf("ignored"))
        if (subIgnored == null || (subIgnored is JsonArray && subIgnored.isEmpty())) m.ignored = null

        val rmMuted = read(rm, listOf("muted"))
        if (rmMuted is JsonArray && rmMuted.isNotEmpty()) {
            // RN filter(Boolean) 去 falsy；此处去 null 与空串（数字 0/false 成员实务上不存在）
            m.muted = JsonArray(rmMuted.filter { it !is JsonNull && !(it is JsonPrimitive && it.isString && it.content.isEmpty()) })
                .toString()
        } else if (read(rm, listOf("_updatedAt", "_updated_at")) != null) {
            m.muted = null
        }

        val rv = read(rm, listOf("v"))
        if (rv != null) m.visitor = optJson(rv)
        m.departmentId = optStr(rm, listOf("departmentId", "department_id")) ?: m.departmentId
        m.servedBy = optJsonField(rm, listOf("servedBy", "served_by")) ?: m.servedBy
        m.livechatData = optJsonField(rm, listOf("livechatData", "livechat_data")) ?: m.livechatData
        m.tags = optJsonField(rm, listOf("tags")) ?: m.tags
        m.sysMes = optJsonField(rm, listOf("sysMes", "sys_mes")) ?: m.sysMes
        if (rm.containsKey("source")) m.source = optJsonField(rm, listOf("source"))
        if (rm.containsKey("usersCount")) m.usersCount = optNum(rm, listOf("usersCount", "users_count"))
        if (rm.containsKey("federated")) m.federated = optBool(rm, listOf("federated"))
        if (rm.containsKey("bot")) m.bot = optBool(rm, listOf("bot"))
        if (rm.containsKey("rt")) m.rt = optStr(rm, listOf("rt"))
        if (rm.containsKey("dname")) m.dname = optStr(rm, listOf("dname"))
        if (rm.containsKey("welcomeMsg")) m.welcomeMsg = optStr(rm, listOf("welcomeMsg"))
        if (rm.containsKey("showAppiaTag")) m.showAppiaTag = optNum(rm, listOf("showAppiaTag"))
        if (rm.containsKey("announcement")) m.announcement = optJsonField(rm, listOf("announcement")) ?: m.announcement
        if (rm.containsKey("announcements")) m.announcements = optJsonField(rm, listOf("announcements"))
        if (rm.containsKey("appiaUsage")) m.appiaUsage = optJsonField(rm, listOf("appiaUsage"))

        val nextT = optSubscriptionType(sub, listOf("t")) ?: optSubscriptionType(rm, listOf("t"))
        if (nextT != null) m.t = nextT
        if (m.name.isEmpty()) m.name = str(rm, listOf("name"))
        if (m.fname.isEmpty()) m.fname = str(rm, listOf("fname"))
    }

    /** RN mergeSubscriptionAndRoom：`_id = sub.rid || room._id`（缺则抛）。 */
    fun merge(subscription: JsonObject?, room: JsonObject? = null): MergedChatRow {
        val sub = subscription ?: JsonObject(emptyMap())
        val rid = str(sub, listOf("rid"))
        val roomId = if (room != null) str(room, listOf("_id")) else ""
        val id = rid.ifEmpty { roomId }
        if (id.isEmpty()) throw IllegalArgumentException("mergeSubscriptionAndRoom: missing rid / room._id")

        val m = subscriptionBase(sub, id, str(sub, listOf("_id")).ifEmpty { null })
        if (room != null) applyRoomToMerged(m, room, sub)
        // RN 末尾 `autoTranslate ?? false`、`blocker/blocked/hideMentionStatus !!` 的归一
        // 已由 subscriptionBase 的非空默认值完成（autoTranslate=false、blocked/blocker/hideMentionStatus=false）。

        val subLm = toMillis(read(sub, listOf("lm")))
        val roomLm = room?.let { toMillis(read(it, listOf("lm"))) }
        val subLastTs = lastMessageTs(read(sub, listOf("lastMessage", "last_message")))
        val roomLastTs = room?.let { lastMessageTs(read(it, listOf("lastMessage", "last_message"))) }
        val subRoomUpdated = num(sub, listOf("roomUpdatedAt", "room_updated_at"))

        // ?? 语义：0 亦是合法值；链尾才看 roomUpdatedAt
        m.lm = subLm ?: roomLm ?: subLastTs ?: roomLastTs ?: subRoomUpdated.takeIf { it > 0.0 }
        m.updatedAt = toMillis(read(sub, listOf("_updatedAt", "_updated_at")))
        m.roomUpdatedAt = m.lm ?: m.ts ?: m.updatedAt ?: 0.0
        return m
    }

    /** RN dedupeMergedChatsById：默认 latestRoomUpdated（`>=` 使同分时保留后者）。 */
    fun dedupeMergedChatsById(
        merged: List<MergedChatRow>,
        strategy: DedupeChatStrategy = DedupeChatStrategy.LATEST_ROOM_UPDATED,
    ): List<MergedChatRow> {
        if (merged.size <= 1) return merged
        return when (strategy) {
            DedupeChatStrategy.LAST_IN_BATCH -> {
                val seen = HashSet<String>()
                val out = ArrayList<MergedChatRow>()
                for (i in merged.indices.reversed()) {
                    val row = merged[i]
                    if (seen.add(row._id)) out.add(row)
                }
                out.reverse()
                out
            }
            DedupeChatStrategy.LATEST_ROOM_UPDATED -> {
                val map = LinkedHashMap<String, MergedChatRow>()
                for (row in merged) {
                    val prev = map[row._id]
                    if (prev == null || row.roomUpdatedAt >= prev.roomUpdatedAt) map[row._id] = row
                }
                map.values.toList()
            }
        }
    }

    // ---- 防御集合（RN applyMergedChatFields :754-839 逐成员转录） ----

    /** 增量补丁常不带这些键；merged 为 null 时保留本地值，不清空。 */
    private val PRESERVE_WHEN_MERGED_UNDEFINED_KEYS = setOf(
        "announcement",
        "announcements",
        "description",
        "topic",
        "roomValueProposition",
        "appiaUsage",
        // 客户端全局搜索进房 bump；服务端 subscription 不带此字段
        "tSearch",
        // 已读/未读等增量补丁常不带待办计数；避免进房返回后待办房间落入频道段
        "todoCount",
        "highTodoCount",
        "defaultTodoCount",
        "isRoomToDo",
        // 列表查询 open=true；增量补丁省略 open 时不应把房间挤出列表
        "open",
        "archived",
        "bot",
        // 未读/alert；lastMessage 等增量常不带，默认 0/false 会清掉刚到的未读角标
        "unread",
        "alert",
        // 置顶 f / like；已读等增量常不带，默认 false 会清掉置顶排序
        "f",
        "like",
        // 免打扰；notification 增量常不带 hideUnreadStatus / disableNotifications
        "hideUnreadStatus",
        "disableNotifications",
        // 我的助手：t/uids 决定分段与标题，增量省略时不应回落为频道或清空成员
        "t",
        "uids",
        "usernames",
        // 草稿为客户端独有字段，服务端从不下发；增量补丁省略时不应覆盖本地草稿
        "draftMessage",
        "draftMessagePlain",
        "draftReplyMessageId",
    )

    /** 不用空串覆盖已有非空字符串（避免 DDP 增量把 name/fname 等清空）。 */
    private val PRESERVE_NONEMPTY_STRING_KEYS = setOf("name", "fname", "dname")

    /** PRESERVE_NONEMPTY 规则：仅当新值是空串且旧值非空串时保留旧值。 */
    private fun keepNonEmptyString(next: String?, prev: String?): String? =
        if (next != null && next.isEmpty() && !prev.isNullOrEmpty()) prev else next

    /**
     * 勿扰 hideUnreadStatus / disableNotifications 成对写入：关闭时常只下发其中一个 false，
     * 独立 merge 会保留另一字段 true 导致仍显示勿扰。返回 null = 两字段均未下发（循环里走 preserve）。
     */
    private fun mutePair(row: MergedChatRow): Pair<Boolean, Boolean>? {
        val hide = row.hideUnreadStatus
        val disable = row.disableNotifications
        return when {
            hide == null && disable == null -> null
            (hide == false || disable == false) && hide != true && disable != true -> false to false
            else -> true to true
        }
    }

    /**
     * RN applyMergedChatFields 的 Kotlin 等价：ChatEntity 不可变，故返回新实体
     * （prev=null 即 create：缺省值用 Watermelon sanitizedRaw 类型默认 '' / false / 0）。
     * `draft_attachments` 不在 CHAT_ASSIGN_KEYS，天然保留。
     */
    fun applyMergedChatFields(prev: ChatEntity?, row: MergedChatRow): ChatEntity {
        val p = prev
        val mute = mutePair(row)
        return ChatEntity(
            _id = row._id,
            subscription_doc_id = row.subscriptionDocId,
            f = row.f ?: p?.f ?: false, // preserve
            t = row.t ?: p?.t ?: "", // preserve
            ts = row.ts,
            ls = row.ls,
            name = keepNonEmptyString(row.name, p?.name) ?: row.name, // preserve non-empty
            fname = keepNonEmptyString(row.fname, p?.fname) ?: row.fname, // preserve non-empty
            rid = row.rid,
            open = row.open ?: p?.open ?: false, // preserve
            alert = row.alert ?: p?.alert ?: false, // preserve
            roles = row.roles,
            unread = row.unread ?: p?.unread ?: 0.0, // preserve
            user_mentions = row.userMentions,
            group_mentions = row.groupMentions,
            tunread = row.tunread,
            tunread_user = row.tunreadUser,
            tunread_group = row.tunreadGroup,
            lm = row.lm,
            room_updated_at = row.roomUpdatedAt,
            subscription_updated_at = row.updatedAt,
            ro = row.ro,
            last_open = row.lastOpen,
            last_message = row.lastMessage,
            description = row.description ?: p?.description, // preserve
            announcement = row.announcement ?: p?.announcement, // preserve
            announcements = row.announcements ?: p?.announcements, // preserve
            room_value_proposition = row.roomValueProposition ?: p?.room_value_proposition, // preserve
            banner_closed = row.bannerClosed,
            topic = row.topic ?: p?.topic, // preserve
            blocked = row.blocked,
            blocker = row.blocker,
            react_when_read_only = row.reactWhenReadOnly,
            archived = row.archived, // preserve 集合成员，但 merged 恒为非空 bool，规则不触发
            join_code_required = row.joinCodeRequired,
            muted = row.muted,
            ignored = row.ignored,
            broadcast = row.broadcast,
            prid = row.prid,
            draft_message = row.draftMessage ?: p?.draft_message, // preserve（客户端独有）
            draft_message_plain = row.draftMessagePlain ?: p?.draft_message_plain, // preserve
            draft_reply_msg_id = row.draftReplyMessageId ?: p?.draft_reply_msg_id, // preserve
            last_thread_sync = row.lastThreadSync,
            jitsi_timeout = row.jitsiTimeout,
            auto_translate = row.autoTranslate,
            auto_translate_language = row.autoTranslateLanguage,
            hide_unread_status = mute?.first ?: (row.hideUnreadStatus ?: p?.hide_unread_status), // 成对写 > preserve
            disable_notifications = mute?.second ?: (row.disableNotifications ?: p?.disable_notifications),
            sys_mes = row.sysMes,
            uids = row.uids ?: p?.uids, // preserve
            usernames = row.usernames ?: p?.usernames, // preserve
            visitor = row.visitor,
            department_id = row.departmentId,
            served_by = row.servedBy,
            livechat_data = row.livechatData,
            tags = row.tags,
            e2e_key = row.e2eKey,
            e2e_suggested_key = row.e2eSuggestedKey,
            encrypted = row.encrypted,
            e2e_key_id = row.e2eKeyId,
            avatar_etag = row.avatarEtag,
            team_id = row.teamId,
            team_main = row.teamMain,
            on_hold = row.onHold,
            source = row.source,
            hide_mention_status = row.hideMentionStatus,
            users_count = row.usersCount,
            federated = row.federated,
            rt = row.rt,
            todoCount = row.todoCount ?: p?.todoCount, // preserve
            dname = keepNonEmptyString(row.dname, p?.dname), // preserve non-empty
            onCallStatus = row.onCallStatus,
            callMsg = row.callMsg,
            bot = row.bot ?: p?.bot, // preserve
            welcomeMsg = row.welcomeMsg,
            memberName = row.memberName,
            memberNumber = row.memberNumber,
            showAppiaTag = row.showAppiaTag,
            isRoomToDo = row.isRoomToDo ?: p?.isRoomToDo, // preserve
            highTodoCount = row.highTodoCount ?: p?.highTodoCount, // preserve
            defaultTodoCount = row.defaultTodoCount ?: p?.defaultTodoCount, // preserve
            like = row.like ?: p?.like, // preserve
            tSearch = maxOf(p?.tSearch ?: 0.0, row.tSearch ?: 0.0).takeIf { it > 0.0 }, // 取 max
            appiaUsage = row.appiaUsage ?: p?.appiaUsage, // preserve
            draft_attachments = p?.draft_attachments, // 客户端独有，不在键集，永不覆盖
        )
    }
}
