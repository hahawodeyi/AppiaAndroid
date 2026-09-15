package cn.appia.im.core.messaging

import androidx.room.withTransaction
import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.domain.chat.ChatMerger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Rocket.Chat 消息落库（逐行移植 appiaMobile/src/lib/chat/persistMessagesFromRocketApi.ts）：
 * - [applyApiFields] = RN applyApiFields :66-121 逐字段收敛表（字段缺失/类型不符的回退值与 RN 一致）
 * - [persist] = RN persistRocketChatMessages :126-175（isApiMessage 校验 → lastWinsByKey 去重 → 单事务批量 upsert）
 * - [persistFromUnknown] = RN persistRocketChatMessageFromUnknown :178-184（stream-room-messages 单条入口）
 *
 * 与 RN 的映射差异（对账 RN models/Message.ts MESSAGE_COLUMN_MAP）：
 * - `messageUpdatedAt` → 列 `_updated_at`（RN 同名映射）；测试经 [nowMs] 注入 Date.now()
 * - Watermelon 对 isOptional 列把 '' 归一为 null 落库：此处可空列写 '' 的语义等价为写 null
 * - `status` **不在字段表**（M0 实体列仅归 SendOrchestrator/T8 写）：update 沿用 prev.status，create 为 null
 * - `local_record_path` / `original_content` 为客户端独有列（RN applyApiFields 不赋值）：沿用 prev
 */
object MessageUpsert {

    /** RN isApiMessage :63-64：`_id` 为字符串才入库，返回强转后的对象。 */
    fun isApiMessage(raw: JsonElement?): JsonObject? {
        if (raw !is JsonObject) return null
        val id = raw["_id"] as? JsonPrimitive ?: return null
        return if (id.isString) raw else null
    }

    /**
     * RN applyApiFields :66-121：把 API 消息对象的字段收敛进一行 MessageEntity。
     * prev=null 即新建（RN prepareCreate）；否则按 `_id` 全行覆盖（RN prepareUpdate + Object.assign）。
     */
    fun applyApiFields(
        prev: MessageEntity?,
        data: JsonObject,
        messageRid: String,
        nowMs: Long = System.currentTimeMillis(),
    ): MessageEntity = MessageEntity(
        _id = prev?._id ?: data.str("_id").orEmpty(),
        msg = data.str("msg").orEmpty().ifEmptyToNull(), // RN :68 非串回 ''（可选列落 null，下同）
        rid = messageRid, // RN :69（调用方已按 data.rid ?? fallbackRid 折算）
        ts = parseRocketMessageTs(data["ts"], nowMs), // RN :70 三态解析，失败回退 now
        u = stringifyOr(data["u"], JsonObject(emptyMap())), // RN :71 JSON.stringify(u ?? {})
        t = data.str("t").orEmpty().ifEmptyToNull(),
        roomSender = data.str("roomSender").orEmpty().ifEmptyToNull(),
        rollbacker = data.str("rollbacker").orEmpty().ifEmptyToNull(),
        alias = data.str("alias").orEmpty(), // 非空列：'' 原样落库（RN 同）
        parse_urls = "[]", // RN :118 恒 '[]'
        groupable = data.optBool("groupable"), // RN :82 boolean 之外 undefined 化
        avatar = data.str("avatar").orEmpty().ifEmptyToNull(),
        emoji = data.str("emoji").orEmpty().ifEmptyToNull(),
        attachments = data.jsonField("attachments").ifEmptyToNull(), // RN :75 truthy 才 JSON 化，否则 ''（→null）
        files = data.jsonField("files").ifEmptyToNull(),
        urls = data.jsonField("urls").ifEmptyToNull(),
        _updated_at = nowMs.toDouble(), // RN :119 messageUpdatedAt: Date.now()
        status = prev?.status, // 不在字段表：仅 SendOrchestrator（T8）可写
        pinned = data.optBool("pinned"),
        starred = data.optBool("starred"),
        edited_by = when (val v = data["editedBy"]) { // RN :92-97 串原样 / 对象 JSON / 其余 ''
            is JsonPrimitive -> if (v.isString) v.content else ""
            is JsonObject -> v.toString()
            else -> ""
        }.ifEmptyToNull(),
        reactions = data.jsonField("reactions").ifEmptyToNull(),
        role = data.str("role").orEmpty().ifEmptyToNull(),
        role_name = data.str("roleName").orEmpty().ifEmptyToNull(),
        drid = data.str("drid").orEmpty().ifEmptyToNull(),
        dcount = data.optNum("dcount") ?: 0.0, // RN :99 非数字回 0
        dlm = data.optNum("dlm") ?: 0.0,
        tmid = data.str("tmid").orEmpty().ifEmptyToNull(),
        tcount = data.optNum("tcount") ?: 0.0,
        tlm = data.optNum("tlm") ?: 0.0,
        replies = data.str("replies").orEmpty().ifEmptyToNull(),
        mentions = data.jsonField("mentions").ifEmptyToNull(),
        channels = data.jsonField("channels").ifEmptyToNull(),
        unread = data.optBool("unread"),
        auto_translate = data.optBool("autoTranslate"),
        translations = data.str("translations").orEmpty().ifEmptyToNull(),
        tmsg = data.str("tmsg").orEmpty().ifEmptyToNull(),
        blocks = data.str("blocks").orEmpty().ifEmptyToNull(),
        e2e = data.str("e2e").orEmpty().ifEmptyToNull(),
        tshow = data.optBool("tshow"),
        md = data.jsonField("md").ifEmptyToNull(),
        comment = data.str("comment").orEmpty().ifEmptyToNull(),
        msg_type = data.str("msgType").orEmpty().ifEmptyToNull(),
        msg_data = data.str("msgData").orEmpty().ifEmptyToNull(),
        survey_status = data.optBool("surveyStatus"),
        appia_todo = when (val v = data["appiaTodo"]) { // RN :113 串原样 / 非 null JSON 化 / 其余 ''
            null, is JsonNull -> ""
            is JsonPrimitive -> if (v.isString) v.content else v.toString()
            else -> v.toString()
        }.ifEmptyToNull(),
        local_record_path = prev?.local_record_path, // 客户端独有：录音路径不覆盖
        show_image_summary = data.str("showImageSummary").orEmpty().ifEmptyToNull(),
        show_document_summary = data.str("showDocumentSummary").orEmpty().ifEmptyToNull(),
        appia_quick_replies = data.str("appiaQuickReplies").orEmpty().ifEmptyToNull(),
        original_content = prev?.original_content, // 客户端独有：转写文本不覆盖
    )

    /**
     * RN persistRocketChatMessages :126-175：过滤无 `_id` 项 → lastWinsByKey 去重 →
     * 单事务内查已有行再批量 upsert（`@Upsert` 全行覆盖；status 等客户端列经 prev 保住）。
     * @return 是否有行写入（RN 空批静默返回的布尔等价）
     */
    suspend fun persist(
        db: AppiaDatabase,
        rawList: List<JsonElement>,
        fallbackRid: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val valid = rawList.mapNotNull { isApiMessage(it) }
        if (valid.isEmpty()) return false
        // lastWinsByKey：同 `_id` 保留最后一次出现（associateBy 同为「后者覆盖、保留首现位次」）
        val unique = valid.associateBy { it.str("_id")!!.orEmpty() }.values.toList()

        val dao = db.messageDao()
        db.withTransaction {
            val existing = unique.map { it.str("_id")!! }
                .chunked(SQLITE_MAX_VARS)
                .flatMap { dao.getByIds(it) }
                .associateBy { it._id }
            val rows = unique.map { data ->
                val messageRid = data.str("rid") ?: fallbackRid // RN :156
                applyApiFields(existing[data.str("_id")], data, messageRid, nowMs)
            }
            rows.chunked(SQLITE_MAX_VARS).forEach { dao.upsertAll(it) }
        }
        return true
    }

    /** RN persistRocketChatMessageFromUnknown :178-184：单条流式消息（fields.args[0]）。 */
    suspend fun persistFromUnknown(
        db: AppiaDatabase,
        raw: JsonElement?,
        fallbackRid: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        raw ?: return false
        return persist(db, listOf(raw), fallbackRid, nowMs)
    }

    // ---- RN lib/message/parseTimestamp.ts ----

    /** RN parseTimestampMs：秒级数字（<1e12）×1000 / ISO 串 Date.parse / {$date:number}，其余 undefined。 */
    internal fun parseTimestampMs(ts: JsonElement?): Double? = when (ts) {
        null, is JsonNull -> null
        is JsonPrimitive -> when {
            ts.isString -> ts.content.takeIf { it.isNotEmpty() }?.let { ChatMerger.parseIsoMillis(it) }
            else -> ts.content.toDoubleOrNull()?.let { if (it < 1e12) it * 1_000.0 else it }
        }
        is JsonObject -> (ts["\$date"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content?.toDoubleOrNull()
        is JsonArray -> null
    }

    /** RN parseRocketMessageTs：解析失败回退当前时间（消息必带可用 ts 落库）。 */
    internal fun parseRocketMessageTs(ts: JsonElement?, nowMs: Long): Double =
        parseTimestampMs(ts) ?: nowMs.toDouble()

    // ---- 收敛助手（RN 逐字段三元表达的等价） ----

    /** JSON.stringify 语义：原始值原样（串带引号）、对象/数组压缩 JSON；fallback 对应 `?? {}`。 */
    private fun stringifyOr(v: JsonElement?, fallback: JsonElement): String =
        (v?.takeIf { it !is JsonNull } ?: fallback).toString()

    /** RN `data.x ? JSON.stringify(data.x) : ''`：JS truthy 判定后才序列化。 */
    private fun JsonObject.jsonField(key: String): String =
        this[key]?.takeIf { jsTruthy(it) }?.toString().orEmpty()

    /** JS truthiness：空串/0/false/null 假，其余（含空对象/数组）真。 */
    private fun jsTruthy(v: JsonElement): Boolean = when (v) {
        is JsonNull -> false
        is JsonPrimitive ->
            if (v.isString) {
                v.content.isNotEmpty()
            } else {
                val b = v.booleanOrNull
                when {
                    b != null -> b
                    else -> (v.content.toDoubleOrNull() ?: 0.0) != 0.0
                }
            }
        else -> true
    }

    /** RN `typeof x === 'string' ? x : ''`（仅真字符串通过，数字/布尔不透传）。 */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** RN `typeof x === 'boolean' ? x : undefined`（JsonNull content 'null' 的 booleanOrNull 天然为 null）。 */
    private fun JsonObject.optBool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** RN `typeof x === 'number' ? x : 0`（串型数字不透传；JsonNull → 0）。 */
    private fun JsonObject.optNum(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

    /**
     * Watermelon sanitizedRaw 口径：isOptional 字符串列落库时 '' 即 null。
     * 非空列（alias 等）不经过此函数，'' 原样保留。
     */
    private fun String.ifEmptyToNull(): String? = ifEmpty { null }

    private const val SQLITE_MAX_VARS = 500
}
