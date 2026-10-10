package cn.appia.im.core.network.api

import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.MeetingUser
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.runBlocking
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
 * 会议 wire 五端点对照（RN services/api/meeting.ts:8-38）：
 * POST 路径与 body 逐字（book 参数即 IMeeting / cancel {rid, announcementId} / list {startTime,endTime}
 * → data 数组缺失回 []）；成员端点复用 M3（appia/room/members）。
 */
class MeetingApiTest {

    private val server = MockWebServer()
    private lateinit var sdk: RocketSdk

    @BeforeEach
    fun setUp() {
        server.start()
        sdk = RocketSdk().also { it.hydrateRestSession(server.url("/").toString(), "tok", "uid") }
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun payload() = Meeting(
        rid = "r1",
        subject = "WeeklySync",
        startTime = "2026-06-26 10:00:00",
        endTime = "2026-06-26 11:00:00",
        users = listOf(MeetingUser(id = "u1", _id = "u1", username = "a", name = "A", source = "org")),
        createTxMeeting = true,
        periodic = false,
        meetCode = "",
        meetUrl = "",
        timezone = "UTC+8",
        enableNotification = true,
        mediaSetType = 0,
    )

    @Test
    fun `meetingRoomBook posts IMeeting body verbatim`() = runBlocking {
        enqueue("""{"success":true,"message":"ok"}""")
        val res = MeetingApi.meetingRoomBook(sdk, payload())
        val req = server.takeRequest()
        assertEquals("/api/v1/meetingRoomBook", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains(""""subject":"WeeklySync""""))
        assertTrue(body.contains(""""createTxMeeting":true"""))
        assertTrue(body.contains(""""_id":"u1""""))
        assertTrue(body.contains(""""mediaSetType":0"""))
        assertTrue(res.success)
        assertEquals("ok", res.message)
    }

    @Test
    fun `mutation endpoints post to verbatim paths and parse response`() = runBlocking {
        enqueue("""{"success":false}""")
        val res = MeetingApi.meetingRoomBookRecordUpdate(sdk, payload())
        assertEquals("/api/v1/meetingRoomBookRecordUpdate", server.takeRequest().path)
        assertFalse(res.success)
        assertNull(res.message)

        enqueue("""{"success":true}""")
        val res2 = MeetingApi.editPeriodicSingleMeeting(sdk, payload())
        assertEquals("/api/v1/editPeriodicSingleMeeting", server.takeRequest().path)
        assertTrue(res2.success)
    }

    @Test
    fun `cancelPeriodicSingleMeeting posts rid and announcementId`() = runBlocking {
        enqueue("""{"success":true,"message":"cancelled"}""")
        val res = MeetingApi.cancelPeriodicSingleMeeting(sdk, "r1", "ann1")
        val req = server.takeRequest()
        assertEquals("/api/v1/cancelPeriodicSingleMeeting", req.path)
        assertEquals("""{"rid":"r1","announcementId":"ann1"}""", req.body.readUtf8())
        assertTrue(res.success)
        assertEquals("cancelled", res.message)
    }

    @Test
    fun `meetingRoomList parses data array with rooms and empty fallback`() = runBlocking {
        enqueue(
            """{"data":[{"id":"room1","name":"Taishan","capacity":12,"enable":true,
                "bookRecords":[{"rid":"r1","subject":"Blocked"}]}]}""",
        )
        val rooms = MeetingApi.meetingRoomList(sdk, "2026-06-26 00:00:00", "2026-06-27 00:00:00")
        val req = server.takeRequest()
        assertEquals("/api/v1/meetingRoomList", req.path)
        assertTrue(req.body.readUtf8().contains(""""startTime":"2026-06-26 00:00:00""""))
        assertEquals(1, rooms.size)
        assertEquals("Taishan", rooms[0].name)
        assertEquals("Blocked", rooms[0].bookRecords.first().subject)

        enqueue("""{"success":true}""")
        assertEquals(0, MeetingApi.meetingRoomList(sdk, "a", "b").size)
        server.takeRequest()
    }

    @Test
    fun `getRoomMembers reuses federated members endpoint`() = runBlocking {
        enqueue("""{"success":true,"data":[{"members":[{"_id":"1","username":"a","name":"A"}]}]}""")
        val res = MeetingApi.getRoomMembers(sdk, "r1")
        assertEquals("/api/v1/appia/room/members?rid=r1", server.takeRequest().path)
        assertTrue(res.success)
        assertEquals(1, res.data.size)
        assertEquals("a", res.data[0].members[0].username)
    }
}
