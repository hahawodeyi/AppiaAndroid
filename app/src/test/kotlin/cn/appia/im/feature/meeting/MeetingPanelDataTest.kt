package cn.appia.im.feature.meeting

import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.parseMeetingPanelAnnouncements
import cn.appia.im.core.chat.isMeetingBooking
import cn.appia.im.core.chat.isMeetingMinutes
import cn.appia.im.core.meeting.toMeetingOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M8-T3 会议面板数据层测试（分组排序/加入解析/纪要正文——坑 9 加入次序含腾讯外跳）。
 * RoomAnnouncement fixture 经 announcementType/meetingRoomBookRecord/meeting 直接构造。
 */
class MeetingPanelDataTest {

    private fun booking(
        id: String,
        subject: String = "Weekly",
        startSec: Long,
        endSec: Long,
        meetCode: String? = null,
        meetUrl: String? = null,
        tencentMeetingId: String? = null,
        highOpt: Boolean? = null,
    ): RoomAnnouncement {
        val record: JsonObject = buildJsonObject {
            put("subject", JsonPrimitive(subject))
            put("startTime", JsonPrimitive(startSec))
            put("endTime", JsonPrimitive(endSec))
            meetCode?.let { put("meetCode", JsonPrimitive(it)) }
            meetUrl?.let { put("meetUrl", JsonPrimitive(it)) }
            tencentMeetingId?.let { put("tencentMeetingId", JsonPrimitive(it)) }
        }
        return RoomAnnouncement(
            id = id,
            announcementType = 1,
            meetingRoomBookRecord = record,
        )
    }

    private fun minutes(id: String, ymd: String, content: List<String> = emptyList()): RoomAnnouncement {
        val meeting: JsonObject = buildJsonObject {
            put("topic", JsonPrimitive("Minutes $ymd"))
            put(
                "content",
                kotlinx.serialization.json.JsonArray(
                    content.map { kotlinx.serialization.json.buildJsonObject { put("content", JsonPrimitive(it)) } },
                ),
            )
        }
        return RoomAnnouncement(
            id = id,
            message = "$ymd \u4f1a\u8bae\u7eaa\u8981",
            updateTime = ymd,
            announcementType = 2,
            meeting = meeting,
        )
    }

    @Test
    fun `grouping upcoming asc first past desc last`() {
        val now = 1_710_000_000L
        val pastLate = booking("p2", startSec = 1_700_000_000, endSec = 1_705_000_000)
        val pastEarly = booking("p1", startSec = 1_690_000_000, endSec = 1_695_000_000)
        val upEarly = booking("u1", startSec = 1_711_000_000, endSec = 1_712_000_000)
        val upLate = booking("u2", startSec = 1_712_000_000, endSec = 1_713_000_000)
        val groups = groupMeetingBookingsByStatus(listOf(upLate, pastLate, upEarly, pastEarly), now)
        assertEquals(listOf("u1", "u2"), groups.upcoming.map { it.id })
        assertEquals(listOf("p2", "p1"), groups.past.map { it.id })
    }

    @Test
    fun `missing endTime falls back to start missing start sinks to MAX`() {
        val noEnd = booking("a", startSec = 1_710_000_000, endSec = 1_710_000_000).let {
            it.copy(meetingRoomBookRecord = buildJsonObject {
                put("subject", JsonPrimitive("x"))
                put("startTime", JsonPrimitive(1_710_000_000))
            })
        }
        assertEquals(MeetingBookingStatus.UPCOMING, meetingBookingStatus(noEnd, nowSec = 10_000))
        val neither = booking("b", startSec = 1, endSec = 2).copy(meetingRoomBookRecord = buildJsonObject {
            put("subject", JsonPrimitive("x"))
        })
        assertEquals(Long.MAX_VALUE, parseBookingStartEpochSec(neither))
        assertEquals(MeetingBookingStatus.UPCOMING, meetingBookingStatus(neither, nowSec = 10_000))
    }

    @Test
    fun `join resolution meetUrl first`() {
        val r = resolveMeetingJoin(
            meetUrl = "https://appia.cn/AntMeeting/123",
            meetCode = "456",
            tencentMeetingId = null,
            serverUrl = "https://appia.cn",
        )
        assertTrue(r is MeetingJoinResolution.AntMeeting)
    }

    @Test
    fun `join resolution meetCode builds native URL`() {
        val r = resolveMeetingJoin(meetUrl = null, meetCode = "888", tencentMeetingId = null, serverUrl = "https://appia.cn")
        assertEquals("https://appia.cn/AntMeeting/888", (r as MeetingJoinResolution.AntMeeting).url)
    }

    @Test
    fun `join resolution tencent record goes external wemeet`() {
        val r = resolveMeetingJoin(meetUrl = null, meetCode = null, tencentMeetingId = "777-abc", serverUrl = "https://appia.cn")
        assertTrue(r is MeetingJoinResolution.External)
        assertTrue((r as MeetingJoinResolution.External).uri.startsWith("wemeet://"))
    }

    @Test
    fun `join resolution all empty is None`() {
        assertEquals(
            MeetingJoinResolution.None,
            resolveMeetingJoin(meetUrl = null, meetCode = null, tencentMeetingId = null, serverUrl = "https://appia.cn"),
        )
    }

    @Test
    fun `tencent http link judged external`() {
        assertTrue(isExternalMeetingUrl("https://meeting.tencent.com/x"))
        assertTrue(!isExternalMeetingUrl("https://appia.cn/AntMeeting/1"))
    }

    @Test
    fun `AntMeeting relative path joins serverUrl`() {
        assertEquals(
            "https://appia.cn/AntMeeting/555",
            resolveAntMeetingUrl("/AntMeeting/555", "https://appia.cn"),
        )
        assertEquals(
            "https://appia.cn/AntMeeting/555",
            resolveAntMeetingUrl("https://appia.cn/AntMeeting/555", "https://appia.cn"),
        )
    }

    @Test
    fun `minutes body content blocks first else message strips hr lines`() {
        val withContent = minutes("m1", ymd = "2026-10-11", content = listOf("para one", "para two"))
        assertEquals("para one\n\npara two", buildMeetingMinutesBody(withContent))
        val noContent = minutes("m2", ymd = "2026-10-11").copy(message = "body\n---\ncont")
        assertEquals("body\ncont", buildMeetingMinutesBody(noContent))
    }

    @Test
    fun `minutes sort ymd descending`() {
        val a = minutes("a", ymd = "2026-09-01")
        val b = minutes("b", ymd = "2026-10-01")
        assertEquals(listOf("b", "a"), sortMeetingMinutes(listOf(a, b)).map { it.id })
    }


    @Test
    fun `card content title falls back subject message then create title`() {
        val resolve: (String, Map<String, String>) -> String = { k, _ -> k }
        val rec = kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"subject\":\"Board meeting\",\"startTime\":\"2026-10-11T10:00:00+08:00\",\"endTime\":\"2026-10-11T11:00:00+08:00\",\"meetCode\":\"888\",\"meetUrl\":\"https://appia.cn/AntMeeting/888\"}",
        ) as JsonObject
        val withSubject = RoomAnnouncement(id = "1", announcementType = 1, meetingRoomBookRecord = rec)
        assertEquals("Board meeting", meetingBookingCardContent(withSubject, java.util.Locale.CHINESE, rec.toMeetingOrNull(), resolve).title)
        val noSubjectRecord = kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"startTime\":\"2026-10-11T10:00:00+08:00\"}",
        ) as JsonObject
        val noSubject = RoomAnnouncement(id = "2", message = "first line\nsecond", announcementType = 1, meetingRoomBookRecord = noSubjectRecord)
        assertEquals("first line", meetingBookingCardContent(noSubject, java.util.Locale.CHINESE, noSubjectRecord.toMeetingOrNull(), resolve).title)
    }

    @Test
    fun `card content time range zh uses dateMdZh key en uses dateMdEn`() {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val resolve: (String, Map<String, String>) -> String = { k, m -> seen += k to m; k }
        val rec = kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"subject\":\"Board meeting\",\"startTime\":\"2026-10-11T10:00:00+08:00\",\"endTime\":\"2026-10-11T11:00:00+08:00\",\"meetCode\":\"888\",\"meetUrl\":\"https://appia.cn/AntMeeting/888\"}",
        ) as JsonObject
        val item = RoomAnnouncement(id = "3", announcementType = 1, meetingRoomBookRecord = rec)
        meetingBookingCardContent(item, java.util.Locale.CHINESE, rec.toMeetingOrNull(), resolve)
        assertTrue(seen.any { it.first == "meeting_dateMdZh" && it.second["month"] == "10" && it.second["day"] == "11" })
        seen.clear()
        meetingBookingCardContent(item, java.util.Locale.ENGLISH, rec.toMeetingOrNull(), resolve)
        assertTrue(seen.any { it.first == "meeting_dateMdEn" })
    }

    @Test
    fun `card content attendee summary caps at three and private network flag`() {
        val resolve: (String, Map<String, String>) -> String = { k, _ -> k }
        val rec = kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"subject\":\"s\",\"users\":[{\"username\":\"a\"},{\"username\":\"b\"},{\"username\":\"c\"},{\"username\":\"d\"}],\"mediaSetType\":1,\"meetCode\":\"888\"}",
        ) as JsonObject
        val c = meetingBookingCardContent(
            RoomAnnouncement(id = "4", announcementType = 1, meetingRoomBookRecord = rec),
            java.util.Locale.CHINESE,
            rec.toMeetingOrNull(),
            resolve,
        )
        assertTrue(c.attendeeLine!!.contains("meeting_attendeesSummary"))
        assertTrue(c.isPrivateNetwork)
        assertEquals("888", c.meetCode)
        assertEquals(null, c.meetUrl)
    }

    @Test
    fun `end time unparseable falls back to start not MAX`() {
        val rec = kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"subject\":\"s\",\"startTime\":1700000000,\"endTime\":\"not-a-date\"}",
        ) as JsonObject
        val item = RoomAnnouncement(id = "5", announcementType = 1, meetingRoomBookRecord = rec)
        assertEquals(parseBookingStartEpochSec(item), parseBookingEndEpochSec(item))
    }

    @Test
    fun `panel parse keeps only meeting announcement kinds`() {
        val booking = booking("bk", startSec = 1, endSec = 2)
        val minute = minutes("mn", ymd = "2026-10-11")
        val normal = RoomAnnouncement(id = "n", announcementType = 0, message = "plain announcement")
        val parsed = parseMeetingPanelAnnouncements(
            announcementRaw = null,
            announcementsRaw = kotlinx.serialization.json.JsonArray(
                listOf(booking, minute, normal).map { kotlinx.serialization.json.Json.parseToJsonElement(rawOf(it)) },
            ).toString(),
        )
        assertTrue(parsed.any { it.id == "bk" && isMeetingBooking(it) })
        assertTrue(parsed.any { it.id == "mn" && isMeetingMinutes(it) })
        assertTrue(parsed.none { it.id == "n" })
    }

    private fun rawOf(a: RoomAnnouncement): String {
        val obj = buildJsonObject {
            a.id?.let { put("_id", JsonPrimitive(it)) }
            a.message?.let { put("message", JsonPrimitive(it)) }
            a.updateTime?.let { put("updateTime", JsonPrimitive(it)) }
            a.announcementType?.let { put("announcementType", JsonPrimitive(it)) }
            a.meetingRoomBookRecord?.let { put("meetingRoomBookRecord", it) }
            a.meeting?.let { put("meeting", it) }
        }
        return obj.toString()
    }

    // ── M8-T5 会议首页（meetingHomeBookings：跨会议室平铺 upcoming 升序）──

    private fun room(vararg records: cn.appia.im.core.meeting.Meeting) =
        cn.appia.im.core.meeting.MeetingRoom(id = "room", bookRecords = records.toList())

    @Test
    fun `home bookings flatten rooms keep upcoming only sorted asc`() {
        val now = 400_000L
        val past = cn.appia.im.core.meeting.Meeting(subject = "past", startTime = "100", endTime = "200")
        val later = cn.appia.im.core.meeting.Meeting(subject = "later", startTime = "900000", endTime = "1000000")
        val sooner = cn.appia.im.core.meeting.Meeting(subject = "sooner", startTime = "500000", endTime = "600000")
        val out = meetingHomeBookings(listOf(room(past, later), room(sooner)), now)
        assertEquals(listOf("sooner", "later"), out.mapNotNull { it.subject })
    }

    @Test
    fun `home bookings missing end falls back to start missing start sinks`() {
        val now = 1_000_000L
        // 无 endTime → 回退 start（start>now 保留）；start/endTime 全缺 → MAX 沉尾仍 upcoming（RN 同义）
        val noEnd = cn.appia.im.core.meeting.Meeting(subject = "noEnd", startTime = "1000500")
        val bare = cn.appia.im.core.meeting.Meeting(subject = "bare")
        val out = meetingHomeBookings(listOf(room(noEnd, bare)), now)
        assertEquals(listOf("noEnd", "bare"), out.mapNotNull { it.subject })
    }
}
