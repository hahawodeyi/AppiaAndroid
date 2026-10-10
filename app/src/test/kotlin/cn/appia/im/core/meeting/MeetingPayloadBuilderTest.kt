package cn.appia.im.core.meeting

import cn.appia.im.core.network.api.ReadReceiptUser
import cn.appia.im.core.network.api.RoomMembersGroup
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * payload 构建 + 校验（RN src/lib/meeting/buildMeetingPayload.test.ts 全量移植 + 坑 11 补强）：
 * 与会人必填 / 时间范围 / 手动号必填对 / 周期结束不早于单次 / createTxMeeting 置空串 /
 * periodicInfo 覆写 / 编辑 extras / timezone 格式。固定 GMT+08 时区保证确定性。
 */
class MeetingPayloadBuilderTest {

    private val zone: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    private fun ts(local: String): Long = parseMeetingDateTime(local, zone)!!

    private val base = MeetingFormState(
        rid = "r1",
        subject = "Weekly ",
        startMs = ts("2026-06-26 10:00"),
        endMs = ts("2026-06-26 11:00"),
        users = listOf(MeetingUser(id = "u1", _id = "u1", username = "a", name = "A", source = "org")),
        createTxMeeting = true,
        meetCode = "",
        meetUrl = "",
        desc = "",
        meetingMaterials = "",
        periodicEnabled = false,
        periodicPreset = "0",
        periodicInfo = PeriodicInfo(periodicCountType = "TO_DATE"),
        monthWeekly = MonthWeekly.MONTHDAILY,
        enableNotification = true,
        mediaSetType = 0,
    )

    private fun validated(form: MeetingFormState, nowMs: Long = ts("2026-06-26 09:00")) =
        validateMeetingForm(form, nowMs, zone)

    // ---- validateMeetingForm ----

    @Test
    fun `attendees required when empty`() {
        assertEquals(MeetingValidationError.ATTENDEES_REQUIRED, validated(base.copy(users = emptyList())))
    }

    @Test
    fun `attendees required when username blank`() {
        val users = listOf(MeetingUser(_id = "u1", username = "  "))
        assertEquals(MeetingValidationError.ATTENDEES_REQUIRED, validated(base.copy(users = users)))
    }

    @Test
    fun `start not after now rejected`() {
        assertEquals(MeetingValidationError.START_TIME_PAST, validated(base, nowMs = ts("2026-06-26 10:00")))
    }

    @Test
    fun `end before start rejected`() {
        assertEquals(
            MeetingValidationError.END_BEFORE_START,
            validated(base.copy(endMs = ts("2026-06-26 10:00"))),
        )
    }

    @Test
    fun `manual mode requires meetCode`() {
        val form = base.copy(createTxMeeting = false, meetCode = "", meetUrl = "https://m")
        assertEquals(MeetingValidationError.MANUAL_CODE_REQUIRED, validated(form))
    }

    @Test
    fun `manual mode requires meetUrl - pit 11 pair`() {
        val form = base.copy(createTxMeeting = false, meetCode = "123", meetUrl = " ")
        assertEquals(MeetingValidationError.MANUAL_CODE_REQUIRED, validated(form))
    }

    @Test
    fun `manual mode with both set passes`() {
        val form = base.copy(createTxMeeting = false, meetCode = "123", meetUrl = "https://m")
        assertNull(validated(form))
    }

    @Test
    fun `auto mode does not require code`() {
        assertNull(validated(base))
    }

    @Test
    fun `periodic end before session end rejected`() {
        val form = base.copy(
            periodicEnabled = true,
            periodicPreset = "1",
            periodicInfo = PeriodicInfo(
                periodicUnit = "DAILY",
                periodicInterval = 1,
                periodicCountType = "TO_DATE",
                periodicEndDate = "2026-06-20 23:59:59",
            ),
        )
        assertEquals(MeetingValidationError.PERIODIC_END_INVALID, validated(form))
    }

    @Test
    fun `periodic COUNT type skips end check`() {
        val form = base.copy(
            periodicEnabled = true,
            periodicInfo = PeriodicInfo(periodicUnit = "DAILY", periodicCountType = "COUNT", periodicCount = 5),
        )
        assertNull(validated(form))
    }

    @Test
    fun `attendees check runs before time check`() {
        val form = base.copy(users = emptyList(), startMs = ts("2026-01-01 00:00"))
        assertEquals(MeetingValidationError.ATTENDEES_REQUIRED, validated(form))
    }

    // ---- 时间工具 ----

    @Test
    fun `formats local datetime with zero seconds`() {
        assertEquals("2026-06-26 10:00:00", formatMeetingDateTime(ts("2026-06-26 10:00"), zone))
    }

    @Test
    fun `parse accepts space and T separators and rejects garbage`() {
        assertEquals(ts("2026-06-26 10:00:30"), parseMeetingDateTime("2026-06-26T10:00:30", zone))
        assertNull(parseMeetingDateTime("not a date", zone))
        assertNull(parseMeetingDateTime("2026-06-26", zone))
    }

    @Test
    fun `utc offset formats full half and negative hours`() {
        assertEquals("UTC+8", getUTCOffset(zone, ts("2026-06-26 10:00")))
        assertEquals("UTC+5.5", getUTCOffset(TimeZone.getTimeZone("GMT+05:30"), 0L))
        assertEquals("UTC-5", getUTCOffset(TimeZone.getTimeZone("GMT-05:00"), 0L))
    }

    // ---- buildMeetingPayload ----

    @Test
    fun `tx meeting empties meetCode and meetUrl`() {
        val form = base.copy(createTxMeeting = true, meetCode = " 123 ", meetUrl = "https://m")
        val payload = buildMeetingPayload(form, zone = zone)
        assertEquals("", payload.meetCode)
        assertEquals("", payload.meetUrl)
        assertEquals(true, payload.createTxMeeting)
    }

    @Test
    fun `manual mode keeps trimmed code and url`() {
        val form = base.copy(createTxMeeting = false, meetCode = " 123 ", meetUrl = " https://m ")
        val payload = buildMeetingPayload(form, zone = zone)
        assertEquals("123", payload.meetCode)
        assertEquals("https://m", payload.meetUrl)
    }

    @Test
    fun `trims subject and drops blank free text fields`() {
        val form = base.copy(desc = "  ", meetingMaterials = " ", extraMeetingRoom = " ")
        val payload = buildMeetingPayload(form, zone = zone)
        assertEquals("Weekly", payload.subject)
        assertNull(payload.desc)
        assertNull(payload.meetingMaterials)
        assertNull(payload.extraMeetingRoom)
    }

    @Test
    fun `notification and private network flags roundtrip`() {
        val payload = buildMeetingPayload(base.copy(enableNotification = false, mediaSetType = 1), zone = zone)
        assertEquals(false, payload.enableNotification)
        assertEquals(1, payload.mediaSetType)
    }

    @Test
    fun `payload timestamps and timezone use local zone`() {
        val payload = buildMeetingPayload(base, zone = zone)
        assertEquals("2026-06-26 10:00:00", payload.startTime)
        assertEquals("2026-06-26 11:00:00", payload.endTime)
        assertEquals("UTC+8", payload.timezone)
    }

    @Test
    fun `non periodic payload has no periodicInfo`() {
        val payload = buildMeetingPayload(base, zone = zone)
        assertNull(payload.periodicInfo)
        assertEquals(false, payload.periodic)
    }

    @Test
    fun `periodic preset overrides unit and interval`() {
        val form = base.copy(
            periodicEnabled = true,
            periodicPreset = "1",
            periodicInfo = PeriodicInfo(
                periodicUnit = "DAILY",
                periodicInterval = 1,
                periodicCountType = "TO_DATE",
                periodicEndDate = "2026-07-26 23:59:59",
            ),
        )
        val payload = buildMeetingPayload(
            form,
            preset = RepeatPreset(value = "4", unit = "WEEKLY", interval = 2),
            zone = zone,
        )
        assertEquals(true, payload.periodic)
        assertEquals("WEEKLY", payload.periodicInfo?.periodicUnit)
        assertEquals(2, payload.periodicInfo?.periodicInterval)
        assertEquals("TO_DATE", payload.periodicInfo?.periodicCountType)
    }

    @Test
    fun `monthly times monthly-weekly becomes MONTHLYWEEK`() {
        val form = base.copy(
            periodicEnabled = true,
            periodicPreset = "6",
            monthWeekly = MonthWeekly.MONTHWEEKLY,
            periodicInfo = PeriodicInfo(
                periodicUnit = "MONTHLY",
                periodicInterval = 1,
                periodicCountType = "COUNT",
                periodicCount = 4,
            ),
        )
        val payload = buildMeetingPayload(form, preset = RepeatPreset(value = "6"), zone = zone)
        assertEquals("MONTHLYWEEK", payload.periodicInfo?.periodicUnit)
    }

    @Test
    fun `edit extras merge five identity fields`() {
        val extra = Meeting(
            announcementId = "ann1",
            id = "m1",
            meetingId = "mid1",
            tencentMeetingId = "tx1",
            periodicId = "p1",
        )
        val payload = buildMeetingPayload(base, extra = extra, zone = zone)
        assertEquals("ann1", payload.announcementId)
        assertEquals("m1", payload.id)
        assertEquals("mid1", payload.meetingId)
        assertEquals("tx1", payload.tencentMeetingId)
        assertEquals("p1", payload.periodicId)
    }

    @Test
    fun `wire json omits unset fields and keeps _id`() {
        val json = meetingJson
            .encodeToJsonElement(buildMeetingPayload(base.copy(desc = "", roomId = "room1"), zone = zone))
            .toString()
        assertTrue(json.contains(""""_id":"u1""""))
        assertTrue(json.contains(""""roomId":"room1""""))
        assertTrue(!json.contains(""""desc""""))
        assertTrue(!json.contains(""""meetingMaterials""""))
        assertTrue(!json.contains(""""periodicInfo""""))
    }

    // ---- mapFederatedMembersToMeetingUsers ----

    @Test
    fun `maps federated members dedup by trimmed username`() {
        val groups = listOf(
            RoomMembersGroup(
                members = listOf(
                    ReadReceiptUser(_id = "1", username = " a ", name = null),
                    ReadReceiptUser(_id = "2", username = "a", name = "Dup"),
                    ReadReceiptUser(_id = "3", username = "", name = "Skip"),
                ),
            ),
            RoomMembersGroup(members = listOf(ReadReceiptUser(_id = "4", username = "b", name = "B"))),
        )
        val users = mapFederatedMembersToMeetingUsers(groups, source = "ent")
        assertEquals(2, users.size)
        assertEquals("a", users[0].username)
        assertEquals("a", users[0].name)
        assertEquals("ent", users[0].source)
        assertEquals("1", users[0]._id)
        assertEquals("b", users[1].username)
        assertEquals("B", users[1].name)
    }
}
