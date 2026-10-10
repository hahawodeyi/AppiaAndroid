package cn.appia.im.core.meeting

import cn.appia.im.core.chat.RoomAnnouncement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会议 wire 模型 + 公告消费侧访问器：RN types/meeting.ts / meetingSummary.ts 字段逐字
 * （tencentMeetingId/periodicInfo/roomId 双形态）、unknown keys 容忍、undefined 键不编码、
 * RoomAnnouncements.kt 保留的 JsonObject 原文 → 类型化。
 */
class MeetingModelsTest {

    // ---- Meeting 解析 ----

    @Test
    fun `parses meeting with tencent and periodic fields ignoring unknowns`() {
        val json = """
            {"id":"m1","rid":"r1","subject":"WeeklySync","startTime":"2026-06-26 10:00:00",
             "endTime":"2026-06-26 11:00:00","createTxMeeting":true,"periodic":true,
             "periodicId":"p1","tencentMeetingId":"tx1","announcementId":"a1",
             "periodicInfo":{"id":"pi1","periodicUnit":"WEEKLY","periodicInterval":2,
                             "periodicCountType":"COUNT","periodicCount":4},
             "someFutureField":123}
        """.trimIndent()
        val meeting = meetingJson.decodeFromString(Meeting.serializer(), json)
        assertEquals("m1", meeting.id)
        assertEquals("tx1", meeting.tencentMeetingId)
        assertEquals("p1", meeting.periodicId)
        assertEquals("WEEKLY", meeting.periodicInfo?.periodicUnit)
        assertEquals(2, meeting.periodicInfo?.periodicInterval)
        assertNull(meeting.meetCode)
    }

    @Test
    fun `roomId numeric form reads as string`() {
        val meeting = meetingJson.decodeFromString(Meeting.serializer(), """{"rid":"r1","roomId":42}""")
        assertEquals("42", meeting.roomId)
    }

    @Test
    fun `roomId string form stays string`() {
        val meeting = meetingJson.decodeFromString(Meeting.serializer(), """{"roomId":"abc"}""")
        assertEquals("abc", meeting.roomId)
    }

    @Test
    fun `missing fields default null and users empty`() {
        val meeting = meetingJson.decodeFromString(Meeting.serializer(), "{}")
        assertNull(meeting.rid)
        assertNull(meeting.enableNotification)
        assertNull(meeting.mediaSetType)
        assertEquals(0, meeting.users.size)
    }

    @Test
    fun `meeting room parses with book records`() {
        val json = """
            {"id":"room1","floor":"3","name":"Taishan","city":"Beijing","building":"A",
             "capacity":12,"device":"TV","enable":true,
             "bookRecords":[{"rid":"r1","subject":"Blocked","startTime":"2026-06-26 10:00:00",
                             "endTime":"2026-06-26 11:00:00","users":[]}]}
        """.trimIndent()
        val room = meetingJson.decodeFromString(MeetingRoom.serializer(), json)
        assertEquals("Taishan", room.name)
        assertEquals(12, room.capacity)
        assertEquals(true, room.enable)
        assertEquals(1, room.bookRecords.size)
        assertEquals("Blocked", room.bookRecords[0].subject)
    }

    @Test
    fun `meeting summary parses content blocks files and roles`() {
        val json = """
            {"id":"s1","topic":"MinutesTopic","timeBegin":"2026-06-26 10:00:00","place":"Room1",
             "host":[{"username":"a","name":"A"}],"participant":[{"username":"b"}],
             "content":[{"checked":true,"content":"item-one"}],
             "files":[{"name":"a.pdf","fileUrl":"https://x/a.pdf","type":"pdf"}],
             "status":1}
        """.trimIndent()
        val summary = meetingJson.decodeFromString(MeetingSummary.serializer(), json)
        assertEquals("MinutesTopic", summary.topic)
        assertEquals(1, summary.host.size)
        assertEquals("A", summary.host[0].name)
        assertNull(summary.participant[0].name)
        assertEquals(1, summary.content.size)
        assertEquals(true, summary.content[0].checked)
        assertEquals(1, summary.files.size)
        assertEquals("pdf", summary.files[0].type)
    }

    @Test
    fun `encode omits unset keys like JSON stringify`() {
        val payload = Meeting(rid = "r1", subject = "s", meetCode = "", tencentMeetingId = null)
        val json = meetingJson.encodeToJsonElement(payload).toString()
        assertTrue(json.contains(""""meetCode":"""))
        assertTrue(!json.contains("tencentMeetingId"))
        assertTrue(!json.contains("users"))
    }

    @Test
    fun `tolerant decode returns null on garbage object`() {
        val raw = """{"startTime":123,"users":"nope"}"""
        assertNull((meetingJson.parseToJsonElement(raw) as? JsonObject)?.toMeetingOrNull())
    }

    // ---- RoomAnnouncement 消费侧访问器 ----

    private fun announcement(
        meetingRoomBookRecord: String? = null,
        meeting: String? = null,
    ) = RoomAnnouncement(
        id = "ann1",
        announcementType = 1,
        meetingRoomBookRecord = meetingRoomBookRecord?.let { meetingJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject },
        meeting = meeting?.let { meetingJson.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject },
    )

    @Test
    fun `accessor maps booking record to typed meeting`() {
        val announcement = announcement(
            meetingRoomBookRecord = """{"rid":"r1","subject":"Launch","meetUrl":"https://m","meetCode":"c1",
                "users":[{"id":"u","_id":"u","username":"a","name":"A","source":"org"}]}""",
        )
        val booking = announcement.meetingBooking()
        assertEquals("Launch", booking?.subject)
        assertEquals("https://m", booking?.meetUrl)
        assertEquals("a", booking?.users?.first()?.username)
    }

    @Test
    fun `accessor maps summary payload to typed summary`() {
        val announcement = announcement(
            meeting = """{"topic":"MinutesTopic","content":[{"checked":true,"content":"x"}]}""",
        )
        val summary = announcement.meetingSummary()
        assertEquals("MinutesTopic", summary?.topic)
        assertEquals("x", summary?.content?.first()?.content)
    }

    @Test
    fun `accessors return null when fields absent`() {
        val announcement = announcement()
        assertNull(announcement.meetingBooking())
        assertNull(announcement.meetingSummary())
    }

    @Test
    fun `accessor degrades to null on malformed record`() {
        val announcement = announcement(meetingRoomBookRecord = """{"users":"bad","periodic":"yes"}""")
        assertNull(announcement.meetingBooking())
    }
}
