package cn.appia.im.feature.meeting

import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.parseMeetingPanelAnnouncements
import cn.appia.im.core.chat.isMeetingBooking
import cn.appia.im.core.chat.isMeetingMinutes
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
        assertEquals(MeetingBookingStatus.UPCOMING, meetingBookingStatus(neither, nowSec = 10_000)) // æ åæ¶é´åé MAX=è¿æªæ¥
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
    fun `minutes sort updateTime descending`() {
        val a = minutes("a", ymd = "2026-09-01")
        val b = minutes("b", ymd = "2026-10-01")
        assertEquals(listOf("b", "a"), sortMeetingMinutes(listOf(a, b)).map { it.id })
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
}
