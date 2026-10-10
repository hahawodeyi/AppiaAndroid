package cn.appia.im.core.meeting

import cn.appia.im.core.network.api.RoomMembersGroup
import cn.appia.im.core.util.trimDecimal
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * 会议表单 payload 构建 + 校验（逐行移植 appiaMobile/src/lib/meeting/buildMeetingPayload.ts
 * :39-108 + meetingTime.ts + meetingTimezone.ts + periodicPayload.ts）。
 * 校验返回 RN i18n key 同名枚举（T4 表单直接映射文案）；时间统一 epoch millis + 显式 zone
 * （minSdk 24 无 java.time，TimeFormats.kt 同裁定；RN Date 本地墙钟语义由 zone 缺省本地面持有）。
 */

/** RN MeetingFormState（start/end → epoch millis；periodicPreset '0'-'6' 字符串字面量保留）。 */
enum class MonthWeekly { MONTHDAILY, MONTHWEEKLY }

data class MeetingFormState(
    val rid: String,
    val subject: String,
    val startMs: Long,
    val endMs: Long,
    val users: List<MeetingUser>,
    val createTxMeeting: Boolean,
    val meetCode: String,
    val meetUrl: String,
    val desc: String,
    val meetingMaterials: String,
    val periodicEnabled: Boolean,
    val periodicPreset: String,
    val periodicInfo: PeriodicInfo,
    val monthWeekly: MonthWeekly,
    val roomId: String? = null,
    val meetingRoomName: String? = null,
    val extraMeetingRoom: String? = null,
    val enableNotification: Boolean,
    val mediaSetType: Int,
)

/** RN RepeatPresetOption（periodicOptions.ts）——构建只用 unit/interval，value '6'=自定义。 */
data class RepeatPreset(val value: String, val unit: String? = null, val interval: Int? = null)

/** 校验错误（rnKey 与 RN i18n key 一一对应）。 */
enum class MeetingValidationError(val rnKey: String) {
    ATTENDEES_REQUIRED("meeting_attendeesRequired"),
    START_TIME_PAST("meeting_startTimePast"),
    END_BEFORE_START("meeting_endBeforeStart"),
    MANUAL_CODE_REQUIRED("meeting_manualCodeRequired"),
    PERIODIC_END_INVALID("meeting_periodicEndInvalid"),
}

/** RN formatMeetingDateTime：`yyyy-MM-dd HH:mm:00`（秒恒 00——选择器分钟步进 15）。 */
fun formatMeetingDateTime(tsMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = tsMs }
    return "%04d-%02d-%02d %02d:%02d:00".format(
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE),
    )
}

private val meetingDateTimeRegex =
    Regex("""^\s*(\d{4})-(\d{1,2})-(\d{1,2})[T ](\d{1,2}):(\d{2})(?::(\d{2}))?\s*$""")

/** RN parseMeetingDateTime：`yyyy-MM-dd HH:mm[:ss]` 本地墙钟 → epoch，坏串返回 null。 */
fun parseMeetingDateTime(raw: String, zone: TimeZone = TimeZone.getDefault()): Long? {
    val m = meetingDateTimeRegex.find(raw) ?: return null
    val c = Calendar.getInstance(zone)
    c.clear()
    c.set(
        m.groupValues[1].toInt(),
        m.groupValues[2].toInt() - 1,
        m.groupValues[3].toInt(),
        m.groupValues[4].toInt(),
        m.groupValues[5].toInt(),
        m.groupValues[6].ifEmpty { "0" }.toInt(),
    )
    return c.timeInMillis
}

/** RN getUTCOffset（meetingTimezone.ts:1-6）：`UTC+8` / `UTC-5.5`（JS 数值 toString）。 */
fun getUTCOffset(zone: TimeZone = TimeZone.getDefault(), atMs: Long = System.currentTimeMillis()): String {
    val hours = zone.getOffset(atMs) / 3_600_000.0
    return "UTC${if (hours >= 0) "+" else "-"}${trimDecimal(abs(hours))}"
}

/** RN validateMeetingTimeRange：start≤now → 过去；end≤start → 倒挂。 */
fun validateMeetingTimeRange(startMs: Long, endMs: Long, nowMs: Long): MeetingValidationError? =
    if (startMs <= nowMs) {
        MeetingValidationError.START_TIME_PAST
    } else if (endMs <= startMs) {
        MeetingValidationError.END_BEFORE_START
    } else {
        null
    }

/** RN isPeriodicEndBeforeSessionEnd（buildMeetingPayload.ts:39-48）：仅 TO_DATE 且解析成功才比较。 */
private fun isPeriodicEndBeforeSessionEnd(
    periodicInfo: PeriodicInfo,
    sessionEndMs: Long,
    zone: TimeZone,
): Boolean {
    if (periodicInfo.periodicCountType != "TO_DATE" || periodicInfo.periodicEndDate == null) return false
    val periodicEndMs = parseMeetingDateTime(periodicInfo.periodicEndDate, zone) ?: return false
    return periodicEndMs < sessionEndMs
}

/**
 * RN validateMeetingForm（:50-67）次序照抄：与会人 → 时间范围 → 手动号必填对（坑 11：
 * meetCode/meetUrl **都**必填）→ 周期结束不早于单次结束。
 */
fun validateMeetingForm(
    form: MeetingFormState,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): MeetingValidationError? {
    if (form.users.none { !it.username.isNullOrBlank() }) return MeetingValidationError.ATTENDEES_REQUIRED
    validateMeetingTimeRange(form.startMs, form.endMs, nowMs)?.let { return it }
    if (!form.createTxMeeting && (form.meetCode.isBlank() || form.meetUrl.isBlank())) {
        return MeetingValidationError.MANUAL_CODE_REQUIRED
    }
    if (form.periodicEnabled && isPeriodicEndBeforeSessionEnd(form.periodicInfo, form.endMs, zone)) {
        return MeetingValidationError.PERIODIC_END_INVALID
    }
    return null
}

/** RN buildPeriodicInfoForSubmit（periodicPayload.ts）：预设非自定义且有 unit/interval → 覆写；MONTHLY×MONTHWEEKLY → MONTHLYWEEK。 */
private fun periodicInfoForSubmit(form: MeetingFormState, preset: RepeatPreset?): PeriodicInfo {
    val info = form.periodicInfo
    if (preset != null && preset.value != "6" && !preset.unit.isNullOrEmpty() && preset.interval != null) {
        return info.copy(periodicUnit = preset.unit, periodicInterval = preset.interval)
    }
    val unit =
        if (info.periodicUnit == "MONTHLY" && form.monthWeekly == MonthWeekly.MONTHWEEKLY) "MONTHLYWEEK"
        else info.periodicUnit
    return info.copy(periodicUnit = unit)
}

/**
 * RN buildMeetingPayload（:69-108）：createTxMeeting 时 meetCode/meetUrl 置空串；desc/meetingMaterials/
 * extraMeetingRoom trim 后空→null（不编码）；timezone=getUTCOffset；编辑场景 extra 覆写
 * announcementId/id/meetingId/tencentMeetingId/periodicId（MeetingFormScreen:285-293）；周期追加 periodicInfo。
 */
fun buildMeetingPayload(
    form: MeetingFormState,
    extra: Meeting? = null,
    preset: RepeatPreset? = null,
    zone: TimeZone = TimeZone.getDefault(),
): Meeting {
    val base = Meeting(
        rid = form.rid,
        subject = form.subject.trim(),
        startTime = formatMeetingDateTime(form.startMs, zone),
        endTime = formatMeetingDateTime(form.endMs, zone),
        users = form.users,
        createTxMeeting = form.createTxMeeting,
        periodic = form.periodicEnabled,
        meetCode = if (form.createTxMeeting) "" else form.meetCode.trim(),
        meetUrl = if (form.createTxMeeting) "" else form.meetUrl.trim(),
        desc = form.desc.trim().ifEmpty { null },
        meetingMaterials = form.meetingMaterials.trim().ifEmpty { null },
        timezone = getUTCOffset(zone),
        roomId = form.roomId,
        meetingRoomName = form.meetingRoomName,
        extraMeetingRoom = form.extraMeetingRoom?.trim()?.ifEmpty { null },
        enableNotification = form.enableNotification,
        mediaSetType = form.mediaSetType,
    )
    val withExtra = if (extra == null) base else base.copy(
        announcementId = extra.announcementId ?: base.announcementId,
        id = extra.id ?: base.id,
        meetingId = extra.meetingId ?: base.meetingId,
        tencentMeetingId = extra.tencentMeetingId ?: base.tencentMeetingId,
        periodicId = extra.periodicId ?: base.periodicId,
    )
    if (!form.periodicEnabled) return withExtra
    return withExtra.copy(periodicInfo = periodicInfoForSubmit(form, preset))
}

/** RN mapFederatedMembersToMeetingUsers（mapFederatedMembersToMeetingUsers.ts:4-25）：username trim 去重，name 缺省回 username。 */
fun mapFederatedMembersToMeetingUsers(
    groups: List<RoomMembersGroup>,
    source: String,
): List<MeetingUser> {
    val seen = mutableSetOf<String>()
    val users = mutableListOf<MeetingUser>()
    groups.forEach { group ->
        group.members.forEach { member ->
            val username = member.username?.trim()
            if (username.isNullOrEmpty() || !seen.add(username)) return@forEach
            users += MeetingUser(
                id = member._id,
                _id = member._id,
                username = username,
                name = member.name ?: username,
                source = source,
            )
        }
    }
    return users
}
