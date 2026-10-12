package cn.appia.im.feature.meeting

import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.MeetingFormState
import cn.appia.im.core.meeting.MeetingValidationError
import cn.appia.im.core.meeting.MeetingUser
import cn.appia.im.core.meeting.MonthWeekly
import cn.appia.im.core.meeting.PeriodicInfo
import cn.appia.im.core.meeting.RepeatPreset
import cn.appia.im.core.meeting.buildMeetingPayload
import cn.appia.im.core.meeting.validateMeetingForm
import cn.appia.im.core.network.RocketSdk
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * T4 表单纯层测试：提交分派（create/update-single/update 接口选择 + payload 编辑字段注入）≥8、
 * 表单校验边界（T2 builder 的表单层包装：初始态/默认区间/周期反推/15 分钟步进）≥8、门控 ≥4。
 */
class MeetingSubmitPlanTest {

    private val zone = TimeZone.getTimeZone("GMT+8")

    // ── 提交分派（meetingSubmitPlan.ts）──

    private fun payload() = buildMeetingPayload(
        initialMeetingFormState(rid = "r1", nowMs = 1_800_000_061_000, zone = zone).copy(subject = "s"),
    )

    @Test
    fun `create dispatches Create when no edit record`() {
        assertTrue(resolveMeetingSubmitAction(payload(), editMeeting = null) is MeetingSubmitAction.Create)
    }

    @Test
    fun `updateSingle dispatches UpdateSingle for periodic single edit`() {
        val action = resolveMeetingSubmitAction(payload(), Meeting(), MeetingEditMode.PERIODIC_SINGLE)
        assertTrue(action is MeetingSubmitAction.UpdateSingle)
    }

    @Test
    fun `update dispatches Update for normal and series edit`() {
        assertTrue(resolveMeetingSubmitAction(payload(), Meeting(), MeetingEditMode.NORMAL) is MeetingSubmitAction.Update)
        assertTrue(resolveMeetingSubmitAction(payload(), Meeting(), MeetingEditMode.PERIODIC_ALL) is MeetingSubmitAction.Update)
    }

    @Test
    fun `editMode defaults to edit-normal so record yields Update`() {
        assertTrue(resolveMeetingSubmitAction(payload(), Meeting()) is MeetingSubmitAction.Update)
    }

    @Test
    fun `executeMeetingSubmit hits three distinct endpoints`() = runTest {
        val server = MockWebServer()
        server.start()
        val sdk = RocketSdk().also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}""")) }
            executeMeetingSubmit(sdk, MeetingSubmitAction.Create(payload()))
            executeMeetingSubmit(sdk, MeetingSubmitAction.UpdateSingle(payload()))
            executeMeetingSubmit(sdk, MeetingSubmitAction.Update(payload()))
            val paths = List(3) { server.takeRequest().path.orEmpty().substringBefore('?') }
            assertEquals(
                listOf("/api/v1/meetingRoomBook", "/api/v1/editPeriodicSingleMeeting", "/api/v1/meetingRoomBookRecordUpdate"),
                paths,
            )
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun `edit payload injects announcementId and edit ids via T2 extra`() {
        val extra = Meeting(
            announcementId = "ann1",
            id = "m1",
            meetingId = "mid1",
            tencentMeetingId = "tx1",
            periodicId = "p1",
        )
        val form = initialMeetingFormState(
            "r1",
            editMeeting = extra.copy(subject = "old subject", startTime = "2099-01-01 10:00:00", endTime = "2099-01-01 11:00:00"),
            zone = zone,
        )
        val p = buildMeetingPayload(form, extra)
        assertEquals("ann1", p.announcementId)
        assertEquals("m1", p.id)
        assertEquals("mid1", p.meetingId)
        assertEquals("tx1", p.tencentMeetingId)
        assertEquals("p1", p.periodicId)
        // 编辑态 createTxMeeting 恒 false（RN buildInitialForm :93）
        assertEquals(false, form.createTxMeeting)
    }

    @Test
    fun `periodic payload carries periodicInfo with preset override`() {
        val form = initialMeetingFormState("r1", zone = zone).copy(
            periodicEnabled = true,
            periodicPreset = "3",
            periodicInfo = PeriodicInfo(periodicCountType = "TO_DATE", periodicEndDate = defaultPeriodicEndDate(1_800_000_061_000, zone)),
        )
        val p = buildMeetingPayload(form, preset = RepeatPreset("3", "WEEKLY", 1))
        assertEquals("WEEKLY", p.periodicInfo?.periodicUnit)
        assertEquals(1, p.periodicInfo?.periodicInterval)
    }

    @Test
    fun `non-periodic payload drops periodicInfo and blanks manual codes`() {
        val form = initialMeetingFormState("r1", zone = zone).copy(meetCode = "123", meetUrl = "http://x")
        val p = buildMeetingPayload(form)
        assertEquals(true, p.createTxMeeting)
        assertEquals("", p.meetCode)
        assertEquals("", p.meetUrl)
        assertNull(p.periodicInfo)
    }

    // ── 表单初始态 / 时间默认（buildInitialForm + meetingTime defaults）──

    private fun cal(ms: Long): Calendar = Calendar.getInstance(zone).apply { timeInMillis = ms }

    @Test
    fun `defaultMeetingRange rounds up to quarter and adds 60min`() {
        val now = cal(0).apply {
            set(2099, 0, 1, 10, 7, 33)
            set(Calendar.MILLISECOND, 500)
        }.timeInMillis
        val (start, end) = defaultMeetingRange(now, zone)
        assertEquals(15, cal(start).get(Calendar.MINUTE))
        assertEquals(0, cal(start).get(Calendar.SECOND))
        assertEquals(11, cal(end).get(Calendar.HOUR_OF_DAY))
        assertEquals(15, cal(end).get(Calendar.MINUTE))
    }

    @Test
    fun `defaultMeetingRange keeps exact quarter start`() {
        val now = cal(0).apply { set(2099, 0, 1, 10, 30, 0) }.timeInMillis
        val (start, end) = defaultMeetingRange(now, zone)
        assertEquals(30, cal(start).get(Calendar.MINUTE))
        assertEquals(11, cal(end).get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal(end).get(Calendar.MINUTE))
    }

    @Test
    fun `snapToQuarterHour floors minutes to 15 step`() {
        val ms = cal(0).apply { set(2099, 0, 1, 10, 47, 12) }.timeInMillis
        val snapped = cal(snapToQuarterHour(ms, zone))
        assertEquals(45, snapped.get(Calendar.MINUTE))
        assertEquals(0, snapped.get(Calendar.SECOND))
    }

    @Test
    fun `defaultPeriodicEndDate is start plus 30 days end of day`() {
        val start = cal(0).apply { set(2099, 0, 1, 10, 0, 0) }.timeInMillis
        assertEquals("2099-01-31 23:59:59", defaultPeriodicEndDate(start, zone))
    }

    @Test
    fun `initial form create defaults tx on preset 0 notification on`() {
        val form = initialMeetingFormState("r1", nowMs = 1_800_000_061_000, zone = zone)
        assertTrue(form.createTxMeeting)
        assertFalse(form.periodicEnabled)
        assertEquals("0", form.periodicPreset)
        assertTrue(form.enableNotification)
        assertEquals(0, form.mediaSetType)
        assertEquals("r1", form.rid)
    }

    @Test
    fun `initial form edit backfills and resolves weekly preset 3`() {
        val record = Meeting(
            subject = "weekly sync",
            startTime = "2099-03-05 09:00:00",
            endTime = "2099-03-05 10:00:00",
            users = listOf(MeetingUser(id = "u1", username = "alice", name = "Alice")),
            meetCode = "abc",
            periodic = true,
            periodicInfo = PeriodicInfo(periodicUnit = "WEEKLY", periodicInterval = 1, periodicCountType = "TO_DATE", periodicEndDate = "2099-04-01 23:59:59"),
            enableNotification = false,
            mediaSetType = 1,
        )
        val form = initialMeetingFormState("r1", record, zone = zone)
        assertEquals("weekly sync", form.subject)
        assertEquals("abc", form.meetCode)
        assertFalse(form.createTxMeeting)
        assertTrue(form.periodicEnabled)
        assertEquals("3", form.periodicPreset)
        assertEquals(MonthWeekly.MONTHDAILY, form.monthWeekly)
        assertEquals(false, form.enableNotification)
        assertEquals(1, form.mediaSetType)
    }

    @Test
    fun `initial form MONTHLYWEEK resolves preset 6 and monthWeekly`() {
        val record = Meeting(
            startTime = "2099-03-05 09:00:00",
            endTime = "2099-03-05 10:00:00",
            periodic = true,
            periodicInfo = PeriodicInfo(periodicUnit = "MONTHLYWEEK", periodicInterval = 1),
        )
        val form = initialMeetingFormState("r1", record, zone = zone)
        assertEquals("6", form.periodicPreset)
        assertEquals(MonthWeekly.MONTHWEEKLY, form.monthWeekly)
    }

    @Test
    fun `non-periodic record leaves periodic disabled`() {
        val record = Meeting(
            startTime = "2099-03-05 09:00:00",
            endTime = "2099-03-05 10:00:00",
            periodic = false,
            periodicInfo = PeriodicInfo(periodicUnit = "DAILY", periodicInterval = 1),
        )
        val form = initialMeetingFormState("r1", record, zone = zone)
        assertFalse(form.periodicEnabled)
    }

    // ── 表单校验包装（T2 validateMeetingForm 边界经表单初始态）──

    private fun formWithUsers() = initialMeetingFormState("r1", nowMs = 1_800_000_061_000, zone = zone)
        .copy(users = listOf(MeetingUser(username = "a")))

    @Test
    fun `validation rejects empty attendees`() {
        assertEquals(MeetingValidationError.ATTENDEES_REQUIRED, validateMeetingForm(initialMeetingFormState("r1", zone = zone), zone = zone))
    }

    @Test
    fun `validation rejects start in the past`() {
        val form = formWithUsers().copy(startMs = 1_800_000_061_000 - 1, endMs = 1_800_000_061_000 + 1)
        assertEquals(MeetingValidationError.START_TIME_PAST, validateMeetingForm(form, nowMs = 1_800_000_061_000, zone = zone))
    }

    @Test
    fun `validation rejects end equal to start`() {
        val form = formWithUsers().copy(startMs = 1_800_000_100_000, endMs = 1_800_000_100_000)
        assertEquals(MeetingValidationError.END_BEFORE_START, validateMeetingForm(form, nowMs = 1_800_000_061_000, zone = zone))
    }

    @Test
    fun `validation manual mode requires both code and url`() {
        val base = formWithUsers().copy(createTxMeeting = false, meetUrl = "http://x")
        assertEquals(MeetingValidationError.MANUAL_CODE_REQUIRED, validateMeetingForm(base, nowMs = 1_800_000_061_000, zone = zone))
        assertEquals(MeetingValidationError.MANUAL_CODE_REQUIRED, validateMeetingForm(base.copy(meetUrl = "", meetCode = "1"), nowMs = 1_800_000_061_000, zone = zone))
        assertNull(validateMeetingForm(base.copy(meetCode = "1"), nowMs = 1_800_000_061_000, zone = zone))
    }

    @Test
    fun `validation rejects periodic TO_DATE end before session end`() {
        val form = formWithUsers().copy(
            startMs = 1_800_100_000_000,
            endMs = 1_800_500_000_000,
            periodicEnabled = true,
            periodicInfo = PeriodicInfo(periodicCountType = "TO_DATE", periodicEndDate = "2027-01-15 10:00:00"),
        )
        assertEquals(MeetingValidationError.PERIODIC_END_INVALID, validateMeetingForm(form, nowMs = 1_800_000_061_000, zone = zone))
    }

    @Test
    fun `validation attendees error precedes time error and valid passes`() {
        val bad = initialMeetingFormState("r1", zone = zone).copy(startMs = 0, endMs = 0)
        assertEquals(MeetingValidationError.ATTENDEES_REQUIRED, validateMeetingForm(bad, nowMs = 1_800_000_061_000, zone = zone))
        assertNull(validateMeetingForm(formWithUsers(), nowMs = 1_800_000_061_000, zone = zone))
    }

    @Test
    fun `attendee summary three names then count then empty`() {
        val users = (1..5).map { MeetingUser(username = "u$it", name = "n$it") }
        assertEquals("n1, n2, n3", formatMeetingAttendeeSummaryLine(users.take(3)) { k, _ -> k })
        assertEquals("meeting_attendeesSummary:5", formatMeetingAttendeeSummaryLine(users) { k, args -> "$k:${args["count"]}" })
        assertEquals("", formatMeetingAttendeeSummaryLine(emptyList()) { k, _ -> k })
    }

    // ── 门控（坑 8：canShowMeetingAttachment）──

    @Test
    fun `gate hides when setting disabled`() {
        assertFalse(canShowMeetingAttachment(meetingEnabled = false, roomType = "c", usernames = listOf("a", "b")))
    }

    @Test
    fun `gate hides omnichannel rooms`() {
        assertFalse(canShowMeetingAttachment(meetingEnabled = true, roomType = "l", usernames = listOf("a", "b")))
    }

    @Test
    fun `gate hides single-peer chat allows group and unknown`() {
        assertFalse(canShowMeetingAttachment(meetingEnabled = true, roomType = "c", usernames = listOf("peer")))
        assertTrue(canShowMeetingAttachment(meetingEnabled = true, roomType = "c", usernames = listOf("a", "b")))
        assertTrue(canShowMeetingAttachment(meetingEnabled = true, roomType = "p", usernames = null))
    }

    @Test
    fun `gate usernames JSON parse tolerates bad input`() {
        assertEquals(listOf("a", "b"), parseRoomUsernames("""["a","b"]"""))
        assertNull(parseRoomUsernames("not-json"))
        assertNull(parseRoomUsernames(null))
        assertEquals(emptyList<String>(), parseRoomUsernames("[]"))
    }

    @Test
    fun `MeetingFormState kotlinx JSON roundtrip`() {
        val form = initialMeetingFormState("r1", zone = zone).copy(
            users = listOf(MeetingUser(id = "u", username = "a", name = "A")),
            monthWeekly = MonthWeekly.MONTHWEEKLY,
            periodicPreset = "6",
        )
        val encoded = Json.encodeToString(MeetingFormState.serializer(), form)
        assertEquals(form, Json.decodeFromString(MeetingFormState.serializer(), encoded))
    }
}
