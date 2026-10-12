package cn.appia.im.feature.meeting

import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.MeetingFormState
import cn.appia.im.core.meeting.MeetingUser
import cn.appia.im.core.meeting.MonthWeekly
import cn.appia.im.core.meeting.PeriodicInfo
import cn.appia.im.core.meeting.RepeatPreset
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.MeetingApi
import cn.appia.im.core.network.api.MeetingMutationResponse
import java.util.Calendar
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 表单提交分派 + 表单层纯函数（移植 appiaMobile/src/lib/meeting/meetingSubmitPlan.ts +
 * meetingTime.ts default 段 + periodicFromRecord.ts + canShowMeetingAttachment.ts +
 * screens/MeetingFormScreen buildInitialForm/emptyPeriodicFields/formatAttendeeSummary 同族）。
 * 全部纯函数无 Compose/网络依赖；执行经 [executeMeetingSubmit] 调 T2 [MeetingApi]。
 */

/** RN MeetingSubmitAction（meetingSubmitPlan.ts:3-7）。 */
sealed interface MeetingSubmitAction {
    data class Create(val payload: Meeting) : MeetingSubmitAction
    data class Update(val payload: Meeting) : MeetingSubmitAction
    data class UpdateSingle(val payload: Meeting) : MeetingSubmitAction
}

/** RN editMode 路由参数字面量（'edit-normal' | 'edit-periodic-single' | 'edit-periodic-all'）。 */
object MeetingEditMode {
    const val NORMAL = "edit-normal"
    const val PERIODIC_SINGLE = "edit-periodic-single"
    const val PERIODIC_ALL = "edit-periodic-all"
}

/** RN resolveMeetingSubmitAction（meetingSubmitPlan.ts:10-25）。 */
fun resolveMeetingSubmitAction(
    payload: Meeting,
    editMeeting: Meeting?,
    editMode: String = MeetingEditMode.NORMAL,
): MeetingSubmitAction = when {
    editMeeting == null -> MeetingSubmitAction.Create(payload)
    editMode == MeetingEditMode.PERIODIC_SINGLE -> MeetingSubmitAction.UpdateSingle(payload)
    else -> MeetingSubmitAction.Update(payload)
}

/** 分派到 T2 五端点之三（RN MeetingFormScreen handleSubmit :300-309 同分支）。 */
suspend fun executeMeetingSubmit(sdk: RocketSdk, action: MeetingSubmitAction): MeetingMutationResponse =
    when (action) {
        is MeetingSubmitAction.Create -> MeetingApi.meetingRoomBook(sdk, action.payload)
        is MeetingSubmitAction.UpdateSingle -> MeetingApi.editPeriodicSingleMeeting(sdk, action.payload)
        is MeetingSubmitAction.Update -> MeetingApi.meetingRoomBookRecordUpdate(sdk, action.payload)
    }

// ── meetingTime.ts default 段（epoch millis + zone 版）──

/** 分钟取整到 15 步进（向下取整；RN TodoReminderPicker minuteInterval=15 原生轮等价）。 */
fun snapToQuarterHour(tsMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val c = Calendar.getInstance(zone).apply { timeInMillis = tsMs }
    c.set(Calendar.MINUTE, c.get(Calendar.MINUTE) / 15 * 15)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** RN defaultMeetingRange：起点向上取整到 15 分钟刻度，时长 60 分钟。 */
fun defaultMeetingRange(
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): Pair<Long, Long> {
    val startCal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    val remainder = startCal.get(Calendar.MINUTE) % 15
    if (remainder != 0) startCal.add(Calendar.MINUTE, 15 - remainder)
    startCal.set(Calendar.SECOND, 0)
    startCal.set(Calendar.MILLISECOND, 0)
    val endCal = (startCal.clone() as Calendar).apply { add(Calendar.MINUTE, 60) }
    return startCal.timeInMillis to endCal.timeInMillis
}

/** RN formatPeriodicEndDate：本地日终 `yyyy-MM-dd 23:59:59`。 */
fun formatPeriodicEndDate(tsMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = tsMs }
    return "%04d-%02d-%02d 23:59:59".format(
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH),
    )
}

/** RN defaultPeriodicEndDate：start + 30 天的日终。 */
fun defaultPeriodicEndDate(startMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = startMs }
    c.add(Calendar.DAY_OF_MONTH, 30)
    return formatPeriodicEndDate(c.timeInMillis, zone)
}

// ── periodicFromRecord.ts ──

/** RN PeriodicFormDefaults。 */
data class PeriodicFormDefaults(
    val periodicPreset: String,
    val monthWeekly: MonthWeekly,
    val periodicInfo: PeriodicInfo,
)

/** RN resolvePresetFromPeriodicInfo（periodicFromRecord.ts:9-16）。 */
internal fun resolvePresetFromPeriodicInfo(info: PeriodicInfo): String = when {
    info.periodicUnit == "DAILY" && info.periodicInterval == 1 -> "1"
    info.periodicUnit == "WORKDAY" && info.periodicInterval == 1 -> "2"
    info.periodicUnit == "WEEKLY" && info.periodicInterval == 1 -> "3"
    info.periodicUnit == "WEEKLY" && info.periodicInterval == 2 -> "4"
    info.periodicUnit == "MONTHLY" && info.periodicInterval == 1 -> "5"
    else -> "6"
}

/** RN mapPeriodicInfoToFormDefaults（非周期 / 缺 periodicInfo → null）。 */
fun mapPeriodicInfoToFormDefaults(record: Meeting): PeriodicFormDefaults? {
    if (record.periodic != true || record.periodicInfo == null) return null
    val info = record.periodicInfo
    return PeriodicFormDefaults(
        periodicPreset = resolvePresetFromPeriodicInfo(info),
        monthWeekly = if (info.periodicUnit == "MONTHLYWEEK") MonthWeekly.MONTHWEEKLY else MonthWeekly.MONTHDAILY,
        periodicInfo = info,
    )
}

// ── buildInitialForm（MeetingFormScreen/index.tsx:80-128）──

/** RN emptyPeriodicFields。 */
fun emptyPeriodicFields(): PeriodicFormDefaults =
    PeriodicFormDefaults(
        periodicPreset = "0",
        monthWeekly = MonthWeekly.MONTHDAILY,
        periodicInfo = PeriodicInfo(periodicCountType = "TO_DATE", periodicCount = 7),
    )

/**
 * RN buildInitialForm：编辑记录（带 startTime/endTime）→ parse 回填 + createTxMeeting=false +
 * 周期从记录反推；新建 → defaultMeetingRange + createTxMeeting=true + 周期预设 0。
 */
fun initialMeetingFormState(
    rid: String,
    editMeeting: Meeting? = null,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): MeetingFormState {
    val empty = emptyPeriodicFields()
    if (editMeeting?.startTime != null && editMeeting.endTime != null) {
        val fromRecord = mapPeriodicInfoToFormDefaults(editMeeting)
        return MeetingFormState(
            rid = rid,
            subject = editMeeting.subject.orEmpty(),
            startMs = cn.appia.im.core.meeting.parseMeetingDateTime(editMeeting.startTime, zone) ?: nowMs,
            endMs = cn.appia.im.core.meeting.parseMeetingDateTime(editMeeting.endTime, zone) ?: nowMs,
            users = editMeeting.users,
            createTxMeeting = false,
            meetCode = editMeeting.meetCode.orEmpty(),
            meetUrl = editMeeting.meetUrl.orEmpty(),
            desc = editMeeting.desc.orEmpty(),
            meetingMaterials = editMeeting.meetingMaterials.orEmpty(),
            periodicEnabled = fromRecord != null,
            periodicPreset = fromRecord?.periodicPreset ?: empty.periodicPreset,
            periodicInfo = fromRecord?.periodicInfo ?: empty.periodicInfo,
            monthWeekly = fromRecord?.monthWeekly ?: empty.monthWeekly,
            roomId = editMeeting.roomId,
            meetingRoomName = editMeeting.meetingRoomName,
            extraMeetingRoom = editMeeting.extraMeetingRoom,
            enableNotification = editMeeting.enableNotification ?: true,
            mediaSetType = if (editMeeting.mediaSetType == 1) 1 else 0,
        )
    }
    val (start, end) = defaultMeetingRange(nowMs, zone)
    return MeetingFormState(
        rid = rid,
        subject = "",
        startMs = start,
        endMs = end,
        users = emptyList(),
        createTxMeeting = true,
        meetCode = "",
        meetUrl = "",
        desc = "",
        meetingMaterials = "",
        periodicEnabled = false,
        periodicPreset = empty.periodicPreset,
        periodicInfo = empty.periodicInfo,
        monthWeekly = empty.monthWeekly,
        roomId = null,
        meetingRoomName = null,
        extraMeetingRoom = null,
        enableNotification = true,
        mediaSetType = 0,
    )
}

/** RN formatAttendeeSummary（index.tsx:67-78）：前 3 名字；>3 → "{{names}} 等 {{count}} 人"。 */
fun formatMeetingAttendeeSummaryLine(
    users: List<MeetingUser>,
    resolve: (key: String, args: Map<String, String>) -> String,
): String {
    if (users.isEmpty()) return ""
    val names = users.take(3).map { u -> u.name?.takeIf { it.isNotBlank() } ?: u.username.orEmpty() }
    if (users.size <= 3) return names.joinToString(", ")
    return resolve("meeting_attendeesSummary", mapOf("names" to names.joinToString(", "), "count" to users.size.toString()))
}

// ── canShowMeetingAttachment.ts（坑 8）──

/** RN parse chat.usernames JSON 数组（RoomScreen showMeeting useMemo 同构）；坏/缺 → null。 */
fun parseRoomUsernames(raw: String?): List<String>? {
    if (raw.isNullOrBlank()) return null
    val arr = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
    return arr.filterIsInstance<JsonPrimitive>().mapNotNull { it.contentOrNull }
}

/**
 * RN canShowMeetingAttachment（canShowMeetingAttachment.ts:3-17，坑 8）：开关关 / omnichannel(l) /
 * 恰单聊（usernames.size==1，未知视为放行）任一命中即隐藏。
 */
fun canShowMeetingAttachment(
    meetingEnabled: Boolean,
    roomType: String?,
    usernames: List<String>?,
): Boolean {
    if (!meetingEnabled) return false
    if (roomType == "l") return false
    if (usernames?.size == 1) return false
    return true
}
