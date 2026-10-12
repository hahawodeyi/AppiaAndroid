package cn.appia.im.feature.meetingroom

import cn.appia.im.core.meeting.rtc.MeetingRtcEvent
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionState
import cn.appia.im.feature.meeting.extractMeetingNumberFromUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会议房间 UI 状态映射（M8-T7）：宫格折叠（引擎事件 → participants）、本端静音/摄像头回写、
 * uid 派生、时长格式化、kicked/ended 信号分叉、AntMeeting 会议号解析（入会路由入参）。
 */
class MeetingRoomStateTest {

    private val self = 100

    // ---- 宫格折叠 ----

    @Test
    fun `joined inserts self tile once`() {
        var list = emptyList<MeetingRoomParticipant>()
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.Joined)
        assertEquals(listOf(MeetingRoomParticipant(self, isSelf = true)), list)
        // 重复 Joined（引擎重连回调）幂等
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.Joined)
        assertEquals(1, list.size)
    }

    @Test
    fun `userJoined adds remote tile and duplicates are ignored`() {
        var list = foldMeetingRoomParticipants(emptyList(), self, MeetingRtcEvent.Joined)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(7))
        assertEquals(2, list.size)
        assertEquals(MeetingRoomParticipant(7), list[1])
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(7))
        assertEquals(2, list.size)
        // 本端 uid 自报 UserJoined 不重复成 tile
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(self))
        assertEquals(2, list.size)
    }

    @Test
    fun `userOffline removes remote and keeps self`() {
        var list = foldMeetingRoomParticipants(emptyList(), self, MeetingRtcEvent.Joined)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(7))
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(8))
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserOffline(7, 0))
        assertEquals(listOf(self, 8), list.map { it.uid })
    }

    @Test
    fun `left removes self tile`() {
        var list = foldMeetingRoomParticipants(emptyList(), self, MeetingRtcEvent.Joined)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.Left)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `remote video changed toggles decoding flag on matching tile`() {
        var list = foldMeetingRoomParticipants(emptyList(), self, MeetingRtcEvent.Joined)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(7))
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.RemoteVideoChanged(7, true))
        assertTrue(list.first { it.uid == 7 }.videoDecoding)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.RemoteVideoChanged(7, false))
        assertFalse(list.first { it.uid == 7 }.videoDecoding)
        // 共享/摄像头停帧不误伤他端
        assertTrue(list.first { it.isSelf }.micOn)
    }

    @Test
    fun `remote audio changed toggles mic flag`() {
        var list = foldMeetingRoomParticipants(emptyList(), self, MeetingRtcEvent.Joined)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(7))
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.RemoteAudioChanged(7, false))
        assertFalse(list.first { it.uid == 7 }.micOn)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.RemoteAudioChanged(7, true))
        assertTrue(list.first { it.uid == 7 }.micOn)
    }

    // ---- 本端控制回写 ----

    @Test
    fun `self mic and camera writes flip only self tile`() {
        var list = foldMeetingRoomParticipants(emptyList(), self, MeetingRtcEvent.Joined)
        list = foldMeetingRoomParticipants(list, self, MeetingRtcEvent.UserJoined(7))
        list = applySelfMic(list, muted = true)
        assertFalse(list.first { it.isSelf }.micOn)
        assertTrue(list.first { it.uid == 7 }.micOn)
        list = applySelfCamera(list, cameraOn = true)
        assertTrue(list.first { it.isSelf }.videoDecoding)
        assertFalse(list.first { it.uid == 7 }.videoDecoding)
        list = applySelfMic(list, muted = false)
        assertTrue(list.first { it.isSelf }.micOn)
    }

    // ---- uid 派生 / 时长格式化 ----

    @Test
    fun `uid for meeting is stable nonzero and empty-safe`() {
        assertEquals(uidForMeeting("user-1"), uidForMeeting("user-1"))
        assertTrue(uidForMeeting("user-1") != uidForMeeting("user-2"))
        assertTrue(uidForMeeting("user-1") != 0)
        assertTrue(uidForMeeting(null) != 0)
        assertEquals(uidForMeeting("  "), uidForMeeting(""))
        assertTrue(uidForMeeting("") != 0)
    }

    @Test
    fun `duration formats mmss under an hour and hmmss above`() {
        assertEquals("00:00", formatMeetingDuration(0))
        assertEquals("00:59", formatMeetingDuration(59))
        assertEquals("12:05", formatMeetingDuration(725))
        assertEquals("1:00:00", formatMeetingDuration(3600))
        assertEquals("1:01:01", formatMeetingDuration(3661))
        assertEquals("00:00", formatMeetingDuration(-3))
    }

    // ---- kicked / ended 信号（RN meeting:kicked / meeting:ended 对位）----

    @Test
    fun `error while inMeeting maps to kicked`() {
        val inMeeting = MeetingSessionState(phase = MeetingPhase.InMeeting)
        assertEquals(MeetingRoomSignal.KICKED, meetingRoomSignalFor(inMeeting, MeetingRtcEvent.Error(-5)))
        assertEquals(MeetingRoomSignal.NONE, meetingRoomSignalFor(MeetingSessionState(), MeetingRtcEvent.Error(-5)))
    }

    @Test
    fun `left while non-idle maps to ended`() {
        assertEquals(MeetingRoomSignal.ENDED, meetingRoomSignalFor(MeetingSessionState(phase = MeetingPhase.InMeeting), MeetingRtcEvent.Left))
        assertEquals(MeetingRoomSignal.ENDED, meetingRoomSignalFor(MeetingSessionState(phase = MeetingPhase.Preheating), MeetingRtcEvent.Left))
        assertEquals(MeetingRoomSignal.NONE, meetingRoomSignalFor(MeetingSessionState(), MeetingRtcEvent.Left))
        assertEquals(MeetingRoomSignal.NONE, meetingRoomSignalFor(MeetingSessionState(phase = MeetingPhase.InMeeting), MeetingRtcEvent.Stats(10, 1)))
    }

    // ---- 入会路由入参（antMeetingUrl.ts extractMeetingNumberFromUrl 移植）----

    @Test
    fun `extract meeting number from ant meeting url`() {
        assertEquals("123456", extractMeetingNumberFromUrl("https://s1.appia.cn/AntMeeting/123456"))
        assertEquals("123456", extractMeetingNumberFromUrl("https://s1.appia.cn/AntMeeting/123456?x=1"))
        assertEquals("a b", extractMeetingNumberFromUrl("https://s1.appia.cn/AntMeeting/a%20b"))
        assertEquals("123456", extractMeetingNumberFromUrl("/AntMeeting/123456"))
        assertNull(extractMeetingNumberFromUrl("https://s1.appia.cn/AntMeeting/"))
        assertNull(extractMeetingNumberFromUrl("https://s1.appia.cn/other/123"))
    }
}
