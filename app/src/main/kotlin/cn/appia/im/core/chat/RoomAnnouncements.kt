package cn.appia.im.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 房间公告解析（逐行移植 appiaMobile/src/lib/chat/roomAnnouncements.ts）：
 * chats.announcement / chats.announcements 两列的存储形态不固定——纯文本 / JSON 对象 /
 * 双重编码 JSON 串 / `` 分隔符嵌入文件。会议字段（meetingRoomBookRecord/meeting）
 * 保留 JsonObject 原文（M8 会议面板消费，本层只做 normalize + 分类判定）。
 * 纯函数无 Compose/DB 依赖（总纲 §4.3-1）。
 */

/** RN ANNOUNCEMENT_FILE_SEPARATOR（）。 */
const val ANNOUNCEMENT_FILE_SEPARATOR = ""

/** RN AnnouncementType：normal=0 / meeting=1 / summary=2。 */
const val ANNOUNCEMENT_TYPE_NORMAL = 0
const val ANNOUNCEMENT_TYPE_MEETING = 1
const val ANNOUNCEMENT_TYPE_SUMMARY = 2

/** RN IRoomAnnouncementFile。 */
data class RoomAnnouncementFile(
    val fileName: String,
    val fileUrl: String,
    val fileType: String? = null,
)

/** RN IRoomAnnouncement（meeting 字段原文保留）。 */
data class RoomAnnouncement(
    val id: String? = null,
    val message: String? = null,
    val u: JsonObject? = null,
    val updateTime: String? = null,
    val files: List<RoomAnnouncementFile> = emptyList(),
    val announcementType: Int? = null,
    val meetingRoomBookRecord: JsonObject? = null,
    val meeting: JsonObject? = null,
)

/** RN getFileType：`split('.').pop()?.trim().toLowerCase()`（无点返回原文——JS pop 同义）。 */
private fun fileTypeOf(nameOrUrl: String): String? =
    nameOrUrl.split('.').last().trim().lowercase().takeIf { it.isNotEmpty() }

/**
 * RN parseAnnouncementEmbeddedFiles：`` 分隔——首段为正文，其后每两段
 * (fileName, fileUrl) 一对；缺段跳过。
 */
fun parseAnnouncementEmbeddedFiles(msg: String): Pair<String, List<RoomAnnouncementFile>> {
    val parts = msg.split(ANNOUNCEMENT_FILE_SEPARATOR)
    if (parts.size <= 1) return msg to emptyList()
    val message = parts.firstOrNull().orEmpty()
    val files = mutableListOf<RoomAnnouncementFile>()
    var i = 1
    while (i + 1 < parts.size) {
        val fileName = parts[i]
        val fileUrl = parts[i + 1]
        if (fileName.isNotEmpty() && fileUrl.isNotEmpty()) {
            files += RoomAnnouncementFile(fileName, fileUrl, fileTypeOf(fileName) ?: fileTypeOf(fileUrl))
        }
        i += 2
    }
    return message to files
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.intField(key: String): Int? =
    // RN `=== 1` 严格比较：字符串 "1" 不等——仅 JSON 数字（isString false）可转
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.contentOrNull?.toIntOrNull()

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** RN parseMaybeJsonObject（normalizeMeetingRecord.ts:5-20）：对象直用 / JSON 串再解一层。 */
private fun parseMaybeJsonObject(value: JsonElement?): JsonObject? = when (value) {
    null, is JsonNull -> null
    is JsonObject -> value
    is JsonPrimitive -> {
        val trimmed = value.contentOrNull?.trim().orEmpty()
        if (trimmed.isEmpty()) null
        else runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
    }
    else -> null
}

/**
 * RN parseAnnouncementField：announcement 列——空 → null；纯文本 → {message}；
 * JSON 对象 → 原样；JSON 串（双重编码）→ 内层再解对象，解不动按 {message: 内层串}。
 */
fun parseAnnouncementField(raw: String?): RoomAnnouncement? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null

    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
        ?: return RoomAnnouncement(message = trimmed)

    // RN typeof parsed === 'string'：数字/布尔/null 原始形态 → undefined 丢弃
    //（kotlinx JsonNull/数字/布尔/裸词均是 JsonPrimitive 且 isString=false——裸词须按 JS
    // JSON.parse 抛异常语义归 {message}，真原始字面量才丢弃）
    if (parsed is JsonPrimitive && parsed.isString) {
        val inner = parsed.contentOrNull?.trim().orEmpty()
        if (inner.isEmpty()) return null
        val again = runCatching { Json.parseToJsonElement(inner) }.getOrNull()
        if (again is JsonObject) return again.toAnnouncement()
        // RN 数组等非对象再解形态原样透传、下游 hasAnnouncementItemContent 过滤——同义丢弃
        if (again != null) return null
        return RoomAnnouncement(message = inner)
    }
    if (parsed is JsonObject) return parsed.toAnnouncement()
    if (parsed is JsonPrimitive) {
        val c = parsed.content
        val isTruePrimitive = parsed is JsonNull || c == "true" || c == "false" || c.toDoubleOrNull() != null
        return if (isTruePrimitive) null else RoomAnnouncement(message = trimmed)
    }
    return null
}

/** RN parseAnnouncementsListField：announcements 列——数组（滤非对象项）/ 单对象 / 坏 JSON → 空。 */
fun parseAnnouncementsListField(raw: String?): List<RoomAnnouncement> {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return emptyList()
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
    return when (parsed) {
        is JsonArray -> parsed.mapNotNull { it as? JsonObject }.map { it.toAnnouncement() }
        is JsonObject -> listOf(parsed.toAnnouncement())
        else -> emptyList()
    }
}

private fun JsonObject.toAnnouncement(): RoomAnnouncement = RoomAnnouncement(
    id = str("_id"),
    message = str("message"),
    u = obj("u"),
    updateTime = str("updateTime"),
    files = (this["files"] as? JsonArray)
        ?.mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            val name = fo.str("fileName") ?: return@mapNotNull null
            val url = fo.str("fileUrl") ?: return@mapNotNull null
            RoomAnnouncementFile(name, url, fo.str("fileType"))
        }
        .orEmpty(),
    announcementType = intField("announcementType"),
    meetingRoomBookRecord = parseMaybeJsonObject(this["meetingRoomBookRecord"]),
    meeting = parseMaybeJsonObject(this["meeting"]),
)

/** RN normalizeItem：files 空且 message 含分隔符 → 拆分。 */
private fun normalizeItem(item: RoomAnnouncement): RoomAnnouncement {
    var next = item
    if (item.files.isEmpty()) {
        val msg = item.message.orEmpty()
        if (msg.contains(ANNOUNCEMENT_FILE_SEPARATOR)) {
            val (message, files) = parseAnnouncementEmbeddedFiles(msg)
            next = next.copy(message = message, files = files)
        }
    }
    return next
}

/** RN hasAnnouncementContent：正文或文件非空。 */
fun hasAnnouncementContent(item: RoomAnnouncement): Boolean =
    !item.message?.trim().isNullOrEmpty() || item.files.isNotEmpty()

/** RN hasMeetingRoomBookStructure：periodic 真值 → periodicInfo 真值；否则起止时间均非空串。 */
private fun hasMeetingRoomBookStructure(record: JsonObject?): Boolean {
    if (record == null) return false
    val periodic = record["periodic"]
    if (periodic != null && periodic !is JsonNull && (periodic as? JsonPrimitive)?.contentOrNull != "false") {
        val info = record["periodicInfo"]
        return info != null && info !is JsonNull
    }
    val start = record.str("startTime")
    val end = record.str("endTime")
    return !start.isNullOrBlank() && !end.isNullOrBlank()
}

/** RN hasMeetingSummaryPayload：id/topic/content 块/起止/place/files 任一有效。 */
private fun hasMeetingSummaryPayload(meeting: JsonObject?): Boolean {
    if (meeting == null) return false
    val hasContent = (meeting["content"] as? JsonArray)?.any { block ->
        (block as? JsonObject)?.str("content")?.trim()?.isNotEmpty() == true
    } == true
    return !meeting.str("id").isNullOrBlank() ||
        !meeting.str("topic").isNullOrBlank() ||
        hasContent ||
        !meeting.str("timeBegin").isNullOrBlank() ||
        !meeting.str("timeEnd").isNullOrBlank() ||
        !meeting.str("place").isNullOrBlank() ||
        ((meeting["files"] as? JsonArray)?.isEmpty() == false)
}

/** RN isMainAnnouncement：announcementType 缺省或 normal。 */
fun isMainAnnouncement(item: RoomAnnouncement): Boolean =
    item.announcementType == null || item.announcementType == ANNOUNCEMENT_TYPE_NORMAL

/** RN isMeetingBooking：type=meeting 且 booking 结构有效。 */
fun isMeetingBooking(item: RoomAnnouncement): Boolean =
    item.announcementType == ANNOUNCEMENT_TYPE_MEETING && hasMeetingRoomBookStructure(item.meetingRoomBookRecord)

/** RN isMeetingMinutes：summary 恒 true；meeting 非 booking 时 content/subject 兜底。 */
fun isMeetingMinutes(item: RoomAnnouncement): Boolean {
    if (item.announcementType == ANNOUNCEMENT_TYPE_SUMMARY) return true
    if (item.announcementType != ANNOUNCEMENT_TYPE_MEETING) return false
    if (isMeetingBooking(item)) return false
    return hasMeetingSummaryPayload(item.meeting) ||
        !item.message?.trim().isNullOrEmpty() ||
        !item.meetingRoomBookRecord?.str("subject").isNullOrBlank()
}

fun isMeetingPanelItem(item: RoomAnnouncement): Boolean = isMeetingBooking(item) || isMeetingMinutes(item)

/** RN hasAnnouncementItemContent：会议面板项按 subject/topic，普通项按 content。 */
fun hasAnnouncementItemContent(item: RoomAnnouncement): Boolean {
    if (isMeetingPanelItem(item)) {
        if (isMeetingBooking(item)) {
            return !item.meetingRoomBookRecord?.str("subject").isNullOrBlank() ||
                !item.message?.trim().isNullOrEmpty()
        }
        return !item.meeting?.str("topic").isNullOrBlank() ||
            !item.meetingRoomBookRecord?.str("subject").isNullOrBlank() ||
            !item.message?.trim().isNullOrEmpty()
    }
    return hasAnnouncementContent(item)
}

/** RN parseRoomAnnouncements：列表 + announcement 补位（_id 撞重不补）。 */
fun parseRoomAnnouncements(announcementRaw: String?, announcementsRaw: String?): List<RoomAnnouncement> {
    val list = parseAnnouncementsListField(announcementsRaw)
        .map(::normalizeItem)
        .filter(::hasAnnouncementItemContent)
        .toMutableList()

    val current = parseAnnouncementField(announcementRaw)
    if (current != null) {
        val normalized = normalizeItem(current)
        val dup = current.id != null && list.any { it.id != null && it.id == current.id }
        if (!dup && hasAnnouncementItemContent(normalized)) {
            list += normalized
        }
    }
    return list
}

fun parseMainAnnouncements(announcementRaw: String?, announcementsRaw: String?): List<RoomAnnouncement> =
    parseRoomAnnouncements(announcementRaw, announcementsRaw).filter(::isMainAnnouncement)

fun parseMeetingPanelAnnouncements(announcementRaw: String?, announcementsRaw: String?): List<RoomAnnouncement> =
    parseRoomAnnouncements(announcementRaw, announcementsRaw).filter(::isMeetingPanelItem)

private fun stripHtmlForPreview(raw: String): String =
    raw
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun previewTextFromMainItem(item: RoomAnnouncement): String {
    val message = item.message?.trim().orEmpty()
    if (message.isNotEmpty()) return stripHtmlForPreview(message)
    return item.files.firstOrNull()?.fileName?.trim().orEmpty()
}

private fun previewTextFromMeetingItem(item: RoomAnnouncement): String =
    if (isMeetingBooking(item)) {
        item.meetingRoomBookRecord?.str("subject")?.trim().orEmpty()
    } else {
        item.meeting?.str("topic")?.trim().orEmpty()
            .ifEmpty { item.meetingRoomBookRecord?.str("subject")?.trim().orEmpty() }
            .ifEmpty { item.message?.trim().orEmpty() }
    }

/** RN getAnnouncementPreviewText：主公告末项的单行预览（RoomInfo 公告行）。 */
fun getAnnouncementPreviewText(announcementRaw: String?, announcementsRaw: String?): String {
    val items = parseMainAnnouncements(announcementRaw, announcementsRaw)
    if (items.isEmpty()) return ""
    return previewTextFromMainItem(items.last())
}

/** RN getMeetingPreviewText：booking 优先末项，否则 minutes 末项（RoomInfo 会议行）。 */
fun getMeetingPreviewText(announcementRaw: String?, announcementsRaw: String?): String {
    val items = parseMeetingPanelAnnouncements(announcementRaw, announcementsRaw)
    if (items.isEmpty()) return ""
    val bookings = items.filter(::isMeetingBooking)
    if (bookings.isNotEmpty()) return previewTextFromMeetingItem(bookings.last())
    val minutes = items.filter(::isMeetingMinutes)
    return minutes.lastOrNull()?.let(::previewTextFromMeetingItem).orEmpty()
}

/** RN formatAnnouncementMessageForDisplay：换行 → `<br>`（legacy AnnouncementView 显示链）。 */
fun formatAnnouncementMessageForDisplay(message: String): String =
    message.replace("\r\n", "<br>").replace("\n", "<br>")
