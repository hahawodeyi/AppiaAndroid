package cn.appia.im.feature.meeting

import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.isMeetingBooking
import cn.appia.im.core.chat.isMeetingMinutes
import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.MeetingRoom
import cn.appia.im.core.meeting.MeetingUser
import cn.appia.im.core.meeting.meetingSummary
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 会议面板数据层（移植 appiaMobile/src/lib/meeting/meetingBookingDisplay.ts + chat/meetingAnnouncementSort.ts +
 * antMeetingUrl.ts（join 解析）+ meetingAnnouncementText.ts + meetingMinutesBody.ts + formatMeetingDisplayTime.ts）。
 * 纯函数无 Compose/DB/网络依赖（总纲 §4.3-1）；i18n 经 [resolve] 注入（"key + {{x}} args" 管线，
 * RoomListTime 同款），now/locale 注入便于测试。
 */

/** RN MeetingBookingStatus：endSec > now → upcoming，否则 past。 */
enum class MeetingBookingStatus { UPCOMING, PAST }

/** 预订分组（RN groupMeetingBookingsByStatus 返回形态）：upcoming 升序 / past 降序。 */
data class MeetingBookingGroups(val upcoming: List<RoomAnnouncement>, val past: List<RoomAnnouncement>)

/** 卡片数据（RN MeetingBookingCardContent）。 */
data class MeetingBookingCardContent(
    val title: String,
    val timeLine: String? = null,
    val attendeeLine: String? = null,
    val locationRoom: String? = null,
    val isPrivateNetwork: Boolean = false,
    val meetCode: String? = null,
    val meetUrl: String? = null,
)

/**
 * 坑 9 join 解析结果。裁定（M8 plan）：腾讯记录（tencentMeetingId 兜底码 / 腾讯 http、wemeet 外链）
 * 恒外跳（wemeet:// 或系统 https）；AntMeeting 形状 → [AntMeeting]（T7 原生会议室 seam 接管）。
 * 与 RN 的差异：RN 把腾讯码也喂 buildAntMeetingUrlFromCode（坑 9 原文）——按裁定不移植该缺陷。
 */
sealed interface MeetingJoinResolution {
    /** AntMeeting 入会 URL（T7 原生会议室；T7 前由装配处降级 toast）。 */
    data class AntMeeting(val url: String) : MeetingJoinResolution

    /** 系统外跳（wemeet:// scheme 或 https 外链）。 */
    data class External(val uri: String) : MeetingJoinResolution

    data object None : MeetingJoinResolution
}

// ── 时间解析（meetingTime.ts parseMeetingInstant + meetingBookingDisplay 数值档）──

private const val MS_THRESHOLD = 1e12
private const val SEC_THRESHOLD = 1e9
private const val MAX_EPOCH_SEC = Long.MAX_VALUE

/** RN parseMeetingInstant：纯数字 epoch（>1e12 视为 ms）/ ISO 串（空格→T、短串补秒）。失败 null。 */
internal fun parseMeetingInstantMillis(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.matches(Regex("^\\d+$"))) {
        val n = trimmed.toLongOrNull() ?: return null
        return if (n > MS_THRESHOLD) n else n * 1000
    }
    val normalized = if (trimmed.contains('T')) trimmed else trimmed.replace(' ', 'T')
    val withSeconds = if (normalized.length > 16) normalized else "$normalized:00"
    // minSdk 24 无 java.time desugaring（库内 TimeFormats 裁定走 Calendar/SimpleDateFormat）：
    // 带时区偏移形态（XXX）解析即 epoch；本地形态按设备时区（= RN OffsetDateTime/LocalDateTime+atZone 双档语义）。
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(withSeconds)?.time
    }.recoverCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(withSeconds)?.time
    }.recoverCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(withSeconds)?.time
    }.recoverCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).parse(withSeconds)?.time
    }.getOrNull()
}

/** 公告 meetingRoomBookRecord 字段宽容读（字符串/JSON 数字皆转原文）。 */
private fun JsonObject?.recordText(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** RN meetingAnnouncementSort.parseBookingStartEpochSec：无 startTime → MAX（升序沉尾）。 */
fun parseBookingStartEpochSec(item: RoomAnnouncement): Long =
    item.meetingRoomBookRecord.recordText("startTime")?.let(::parseBookingEpochSec) ?: Long.MAX_VALUE

/** RN parseBookingEndEpochSec：无 endTime 或不可解析 → 回退 start（meetingBookingDisplay.ts:39-52）。 */
fun parseBookingEndEpochSec(item: RoomAnnouncement): Long {
    val end = item.meetingRoomBookRecord.recordText("endTime")?.let(::parseBookingEpochSec)
    return if (end == null || end == MAX_EPOCH_SEC) parseBookingStartEpochSec(item) else end
}

private fun parseBookingEpochSec(raw: String): Long {
    val n = raw.toDoubleOrNull()
    if (n != null && n.isFinite()) {
        return when {
            n > MS_THRESHOLD -> (n / 1000).toLong()
            n > SEC_THRESHOLD -> n.toLong()
            else -> parseMeetingInstantMillis(raw)?.div(1000) ?: MAX_EPOCH_SEC
        }
    }
    return parseMeetingInstantMillis(raw)?.div(1000) ?: MAX_EPOCH_SEC
}

/** RN getMeetingBookingStatus（meetingBookingDisplay.ts:54-61）。 */
fun meetingBookingStatus(item: RoomAnnouncement, nowSec: Long): MeetingBookingStatus =
    if (parseBookingEndEpochSec(item) > nowSec) MeetingBookingStatus.UPCOMING else MeetingBookingStatus.PAST

/** RN sortMeetingBookings：booking 过滤 + start 升序。 */
fun sortMeetingBookings(items: List<RoomAnnouncement>): List<RoomAnnouncement> =
    items.filter(::isMeetingBooking).sortedBy(::parseBookingStartEpochSec)

/**
 * RN groupMeetingBookingsByStatus（meetingBookingDisplay.ts:137-153）：upcoming 保持 start 升序，
 * past 再按 start 降序重排。
 */
fun groupMeetingBookingsByStatus(items: List<RoomAnnouncement>, nowSec: Long): MeetingBookingGroups {
    val upcoming = mutableListOf<RoomAnnouncement>()
    val past = mutableListOf<RoomAnnouncement>()
    for (item in sortMeetingBookings(items)) {
        if (meetingBookingStatus(item, nowSec) == MeetingBookingStatus.UPCOMING) upcoming += item else past += item
    }
    past.sortByDescending(::parseBookingStartEpochSec)
    return MeetingBookingGroups(upcoming, past)
}

// ── 会议首页（M8-T5：RN MEETING_HOME_URL H5 首页的原生最小替代）──

/** Meeting 时间读数（bookRecords 与公告同 IMeeting 字符串档，复用 parseBookingEpochSec）。 */
private fun meetingStartSec(m: Meeting): Long =
    m.startTime?.let(::parseBookingEpochSec) ?: Long.MAX_VALUE

/** RN parseBookingEndEpochSec 语义：end 缺失/不可解析 → 回退 start。 */
private fun meetingEndSec(m: Meeting): Long {
    val end = m.endTime?.let(::parseBookingEpochSec)
    return if (end == null || end == MAX_EPOCH_SEC) meetingStartSec(m) else end
}

/**
 * 首页预订列表：meetingRoomList 窗口内 bookRecords 跨会议室平铺，仅保留 upcoming
 * （endSec > now）升序。RN 首页 H5 全量预订列表 + 创建——AA M8 最小等价取未来窗口
 * （past 归各房间 RoomMeeting 面板，不在此重复）。
 */
fun meetingHomeBookings(rooms: List<MeetingRoom>, nowSec: Long): List<Meeting> =
    rooms.asSequence()
        .flatMap { it.bookRecords.asSequence() }
        .filter { meetingEndSec(it) > nowSec }
        .sortedBy(::meetingStartSec)
        .toList()

// ── 纪要排序（meetingAnnouncementSort.ts:4-39）──

private val HEADING_YMD_RE = Regex("^#{1,6}\\s*\\*{0,2}(\\d{4}-\\d{2}-\\d{2})\\*{0,2}\\b")
private val INLINE_YMD_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s*\u4f1a\u8bae\u7eaa\u8981\\b")
private val SUBJECT_YMD_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s*\u4f1a\u8bae\u7eaa\u8981")

/** RN extractMinutesYmdFromMessage：前 20 行找 `# 2026-01-01` 标题或 `2026-01-01 \u4f1a\u8bae\u7eaa\u8981` 行首。 */
fun extractMinutesYmdFromMessage(message: String?): String? {
    if (message?.trim().isNullOrEmpty()) return null
    for (line in message!!.split("\n").take(20)) {
        HEADING_YMD_RE.find(line.trim())?.groupValues?.get(1)?.let { return it }
        INLINE_YMD_RE.find(line.trim())?.groupValues?.get(1)?.let { return it }
    }
    return null
}

fun extractMinutesYmdFromBookSubject(subject: String?): String? =
    subject?.trim()?.let { SUBJECT_YMD_RE.find(it)?.groupValues?.get(1) }

/** RN parseMinutesSortEpochSec：ymd（本地正午）→ updateTime → 0。 */
fun parseMinutesSortEpochSec(item: RoomAnnouncement): Long {
    val ymd = extractMinutesYmdFromMessage(item.message)
        ?: extractMinutesYmdFromBookSubject(item.meetingRoomBookRecord.recordText("subject"))
    if (ymd != null) {
        val ms = runCatching {
            // RN ymdT12:00:00 本地正午（LocalDateTime+atZone 默认时区）——minSdk24 走 SimpleDateFormat 本地时区等价
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse("${ymd}T12:00:00")?.time
        }.getOrNull()
        if (ms != null) return ms / 1000
    }
    val update = item.updateTime
    if (!update.isNullOrEmpty()) return parseMeetingInstantMillis(update)?.div(1000) ?: 0
    return 0
}

/** RN sortMeetingMinutes：minutes 过滤 + 倒序（最新在前）。 */
fun sortMeetingMinutes(items: List<RoomAnnouncement>): List<RoomAnnouncement> =
    items.filter(::isMeetingMinutes).sortedByDescending(::parseMinutesSortEpochSec)

// ── 坑 9 join 解析（antMeetingUrl.ts + RoomMeetingScreen:146-149 + M8 裁定）──

private val ANT_MEETING_PATH_RE = Regex("/AntMeeting(?:/|\$)", RegexOption.IGNORE_CASE)
private val TENCENT_MEETING_HTTP_RE = Regex("^https://meeting\\.tencent\\.com", RegexOption.IGNORE_CASE)
private val WEMEET_SCHEME_RE = Regex("^wemeet://", RegexOption.IGNORE_CASE)

/** RN isExternalMeetingUrl（meetingUrlPatterns.ts:3,5）：腾讯 http 链接或 wemeet scheme。 */
fun isExternalMeetingUrl(url: String): Boolean {
    val t = url.trim()
    return TENCENT_MEETING_HTTP_RE.containsMatchIn(t) || WEMEET_SCHEME_RE.containsMatchIn(t)
}

/** RN isAntMeetingHttpUrl：URL path 匹配 /AntMeeting(/|$)；解不动按原文匹配（RN catch 同）。 */
fun isAntMeetingHttpUrl(url: String): Boolean {
    val t = url.trim()
    if (t.isEmpty()) return false
    val path = runCatching { URI(t).path }.getOrNull().orEmpty()
    return ANT_MEETING_PATH_RE.containsMatchIn(if (path.isEmpty()) t else path)
}

/**
 * RN resolveAntMeetingUrl（antMeetingUrl.ts:22-40）：相对路径拼 serverUrl；绝对 URL 校验 path。
 * 非 AntMeeting 形状 → null。
 */
fun resolveAntMeetingUrl(raw: String, serverUrl: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("/")) {
        val base = serverUrl.trimEnd('/')
        if (base.isEmpty()) return null
        val resolved = base + trimmed
        return if (isAntMeetingHttpUrl(resolved)) resolved else null
    }
    return runCatching {
        val uri = URI(trimmed)
        if (ANT_MEETING_PATH_RE.containsMatchIn(uri.path.orEmpty())) uri.toString() else null
    }.getOrNull()
}

/** RN buildAntMeetingUrlFromCode（antMeetingUrl.ts:46-54）：去空白 + `${serverUrl}/AntMeeting/{code}`。 */
fun buildAntMeetingUrlFromCode(meetingCode: String, serverUrl: String): String? {
    val code = meetingCode.replace(Regex("\\s+"), "").trim()
    if (code.isEmpty() || serverUrl.isBlank()) return null
    val base = serverUrl.trimEnd('/')
    return base + "/AntMeeting/" + URLEncoder.encode(code, "UTF-8")
}

/**
 * 坑 9 加入解析次序（RN resolveMeetingJoinUrl :73-88 + RoomMeetingScreen:146-149 `meetCode ?? tencentMeetingId`）：
 * 1. meetUrl 腾讯外链（腾讯 http / wemeet）→ [MeetingJoinResolution.External]（裁定：恒外跳）；
 * 2. meetUrl AntMeeting 形状（相对拼 serverUrl / 绝对校验）→ [MeetingJoinResolution.AntMeeting]；
 * 3. meetUrl 非 AntMeeting 形状 → 落码分支（RN fall-through 同义）；
 * 4. meetCode 非空 → AntMeeting 码 URL；meetCode 空且 tencentMeetingId 兜底命中（腾讯代建记录）→ wemeet 外跳；
 * 5. 全空 → [MeetingJoinResolution.None]（RN resolve 失败 → toast feature_not_implemented）。
 */
fun resolveMeetingJoin(
    meetUrl: String?,
    meetCode: String?,
    tencentMeetingId: String?,
    serverUrl: String,
): MeetingJoinResolution {
    val url = meetUrl?.trim()
    if (!url.isNullOrEmpty()) {
        if (isExternalMeetingUrl(url)) return MeetingJoinResolution.External(url)
        resolveAntMeetingUrl(url, serverUrl)?.let { return MeetingJoinResolution.AntMeeting(it) }
        if (isAntMeetingHttpUrl(url)) return MeetingJoinResolution.AntMeeting(url)
    }
    val trimmedCode = meetCode?.trim()?.takeIf { it.isNotEmpty() }
    val code = trimmedCode ?: tencentMeetingId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return MeetingJoinResolution.None
    if (trimmedCode == null) {
        // 码来自 tencentMeetingId 兜底 → 腾讯代建记录：wemeet 拉起（审批卡 useApprovalCardActions 同构）
        return MeetingJoinResolution.External("wemeet://page/inmeeting?meeting_code=" + URLEncoder.encode(code, "UTF-8"))
    }
    return buildAntMeetingUrlFromCode(code, serverUrl)?.let { MeetingJoinResolution.AntMeeting(it) }
        ?: MeetingJoinResolution.None
}

// ── 卡片文本（meetingAnnouncementText.ts + formatAttendeeSummary.ts + formatMeetingDisplayTime.ts）──

/** RN stripMeetingAnnouncementPlainText（meetingAnnouncementText.ts:11-19）。 */
fun stripMeetingAnnouncementPlainText(raw: String?): String =
    (raw ?: "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("\r\n", "\n")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** RN firstLineOfPlainText。 */
fun firstLineOfPlainText(raw: String?): String =
    stripMeetingAnnouncementPlainText(raw).split("\n").firstOrNull { it.isNotBlank() }?.trim() ?: ""

private val WEEKDAY_KEYS = listOf(
    "meeting_weekdaySun", "meeting_weekdayMon", "meeting_weekdayTue",
    "meeting_weekdayWed", "meeting_weekdayThu", "meeting_weekdayFri", "meeting_weekdaySat",
)

/**
 * RN formatMeetingTimeRangeDisplay（formatMeetingDisplayTime.ts:73-107）：{{date}} {{weekday}} {{start}}–{{end}}，
 * zh 月日走 meeting_dateMdZh、其余 meeting_dateMdEn（en-US 短月名）。解析失败回显原文（RN String(startRaw)）。
 * [resolve] 注入 i18n（key + args → 文案）。
 */
fun formatMeetingTimeRangeDisplay(
    startRaw: String?,
    endRaw: String?,
    locale: Locale,
    resolve: (key: String, args: Map<String, String>) -> String,
): String {
    if (startRaw.isNullOrBlank()) return ""
    val startMs = parseMeetingInstantMillis(startRaw) ?: return startRaw
    val endMs = if (!endRaw.isNullOrBlank()) parseMeetingInstantMillis(endRaw) else null
    if (!endRaw.isNullOrBlank() && endMs == null) return startRaw

    val cal = Calendar.getInstance().apply { timeInMillis = startMs }
    val weekday = resolve(WEEKDAY_KEYS[cal.get(Calendar.DAY_OF_WEEK) - 1], emptyMap())
    val day = cal.get(Calendar.DAY_OF_MONTH).toString()
    val datePart = if (locale.language.startsWith("zh")) {
        resolve("meeting_dateMdZh", mapOf("month" to (cal.get(Calendar.MONTH) + 1).toString(), "day" to day))
    } else {
        resolve(
            "meeting_dateMdEn",
            mapOf("month" to SimpleDateFormat("MMM", Locale.US).format(Date(startMs)), "day" to day),
        )
    }
    val hm = SimpleDateFormat("HH:mm", locale)
    val startHm = hm.format(Date(startMs))
    val endHm = endMs?.let { hm.format(Date(it)) }
    return if (endHm != null) {
        resolve("meeting_timeRange", mapOf("date" to datePart, "weekday" to weekday, "start" to startHm, "end" to endHm))
    } else {
        resolve("meeting_timeStartOnly", mapOf("date" to datePart, "weekday" to weekday, "start" to startHm))
    }
}

/** RN formatMeetingStartLegacyDisplay（缺 end 的旧列表式：locale 日期时间串）。 */
fun formatMeetingStartLegacyDisplay(startRaw: String?, locale: Locale): String {
    if (startRaw.isNullOrBlank()) return ""
    val ms = parseMeetingInstantMillis(startRaw) ?: return startRaw
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.DEFAULT, java.text.DateFormat.MEDIUM, locale)
        .format(Date(ms))
}

/** RN formatMeetingAttendeeSummary（formatAttendeeSummary.ts:6-19）：前 3 个名字；>3 人追加等 N 人。 */
fun formatMeetingAttendeeSummary(
    users: List<MeetingUser>?,
    resolve: (key: String, args: Map<String, String>) -> String,
): String? {
    if (users.isNullOrEmpty()) return null
    val names = users.mapNotNull { u -> u.name?.trim()?.takeIf { it.isNotEmpty() } ?: u.username?.trim()?.takeIf { it.isNotEmpty() } }
        .take(3)
    if (names.isEmpty()) return null
    if (users.size <= 3) return names.joinToString(", ")
    return resolve(
        "meeting_attendeesSummary",
        mapOf("names" to names.joinToString(", "), "count" to users.size.toString()),
    )
}

/** RN getMeetingBookingLocationParts：meetingRoomName ?? extraMeetingRoom（trim 后非空）。 */
fun meetingBookingLocationRoom(record: Meeting?): String? =
    record?.meetingRoomName?.trim()?.takeIf { it.isNotEmpty() }
        ?: record?.extraMeetingRoom?.trim()?.takeIf { it.isNotEmpty() }

/** RN getMeetingBookingOnlineParts：meetCode ?? tencentMeetingId（trim 后非空）。 */
fun meetingBookingOnlineCode(record: Meeting?): String? =
    record?.meetCode?.trim()?.takeIf { it.isNotEmpty() }
        ?: record?.tencentMeetingId?.trim()?.takeIf { it.isNotEmpty() }

/** RN getMeetingBookingOnlineParts：meetUrl（trim 后非空）。 */
fun meetingBookingOnlineUrl(record: Meeting?): String? = record?.meetUrl?.trim()?.takeIf { it.isNotEmpty() }

/**
 * RN getMeetingBookingCardContent（meetingBookingDisplay.ts:100-135）：标题 subject → message 首行 →
 * meeting_createTitle；时间行区间式（起止）/单起式；与会人摘要；地点/私网；码/链接。
 */
fun meetingBookingCardContent(
    item: RoomAnnouncement,
    locale: Locale,
    record: Meeting?,
    resolve: (key: String, args: Map<String, String>) -> String,
): MeetingBookingCardContent {
    val title = record?.subject?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstLineOfPlainText(item.message).takeIf { it.isNotEmpty() }
        ?: resolve("meeting_createTitle", emptyMap())

    val start = record?.startTime?.trim()?.takeIf { it.isNotEmpty() }
    val end = record?.endTime?.trim()?.takeIf { it.isNotEmpty() }
    val timeLine = when {
        start != null && end != null -> formatMeetingTimeRangeDisplay(start, end, locale, resolve)
        start != null -> formatMeetingStartLegacyDisplay(start, locale)
        else -> ""
    }

    return MeetingBookingCardContent(
        title = title,
        timeLine = timeLine.takeIf { it.isNotEmpty() },
        attendeeLine = formatMeetingAttendeeSummary(record?.users?.takeIf { it.isNotEmpty() }, resolve),
        locationRoom = meetingBookingLocationRoom(record),
        isPrivateNetwork = record?.mediaSetType == 1,
        meetCode = meetingBookingOnlineCode(record),
        meetUrl = meetingBookingOnlineUrl(record),
    )
}

// ── 纪要正文（meetingMinutesBody.ts）──

/** RN stripMeetingMinutesMarkdownHrLines：剔除 `---` 分隔行。 */
fun stripMeetingMinutesMarkdownHrLines(message: String): String =
    message.split(Regex("\r?\n"))
        .filterNot { Regex("^\\s*---+\\s*$").matches(it) }
        .joinToString("\n")

/**
 * RN buildMeetingMinutesBody（meetingMinutesBody.ts:6-17）：content 块非空 → 块文 join 空行；
 * 否则 message 去 hr 行。纯本地渲染（纪要无独立接口，研究 §2.4）。
 */
fun buildMeetingMinutesBody(item: RoomAnnouncement): String {
    val blocks = item.meetingSummary()?.content
    if (!blocks.isNullOrEmpty()) {
        val joined = blocks.map { it.content.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
        if (joined.isNotEmpty()) return joined
    }
    val message = item.message?.trim() ?: return ""
    return stripMeetingMinutesMarkdownHrLines(message)
}
