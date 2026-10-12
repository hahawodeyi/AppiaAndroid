package cn.appia.im.domain.meeting

import cn.appia.im.core.meeting.MeetingCredentials
import cn.appia.im.core.meeting.rtc.AgoraMeetingEngine
import cn.appia.im.core.meeting.rtc.AgoraRtcApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加入流缝对照：credentials → engine.join → inMeeting；cert-less 门（token 空/等 appId）；
 * 失败/取消回 idle；leave 幂等。
 */
class MeetingSessionControllerTest {

    private class FakeRtc : AgoraRtcApi {
        var joinResult = 0
        val joins = mutableListOf<String>()

        override fun create(appId: String, onEvent: (cn.appia.im.core.meeting.rtc.MeetingRtcEvent) -> Unit) {}
        override fun joinChannel(token: String?, channelId: String, uid: Int): Int {
            joins += "$channelId/$uid/token=${token ?: "null"}"
            return joinResult
        }

        override fun leaveChannel(): Int = 0
        override fun muteLocalAudioStream(muted: Boolean): Int = 0
        override fun muteLocalVideoStream(muted: Boolean): Int = 0
        override fun destroy() {}
    }

    private val rtc = FakeRtc()
    private val machine = MeetingSessionStateMachine()
    private val engine = AgoraMeetingEngine(rtc)

    private fun controller(creds: MeetingCredentials?) =
        MeetingSessionController(engine, machine) { _, _ -> creds }

    // ---- happy path ----

    @Test
    fun `join fetches credentials then engine joins then state inMeeting`() = runBlocking {
        val result = controller(MeetingCredentials(appId = "app", token = "007t", uid = "5"))
            .join("room-1", uid = 5, title = "weekly")
        assertEquals(MeetingSessionController.JoinResult.Joined, result)
        assertEquals(MeetingPhase.InMeeting, machine.phase)
        assertEquals("weekly", machine.state.value.title)
        assertEquals("room-1/5/token=007t", rtc.joins.single())
    }

    @Test
    fun `join without preheat - state went through preheating only transiently`() = runBlocking {
        controller(MeetingCredentials(appId = "app", token = "t")).join("room", 1)
        // 终态 inMeeting，无 preheating 残留（title/meetingNumber 就位）
        assertEquals("room", machine.state.value.meetingNumber)
    }

    // ---- cert-less 门（spike §2.2）----

    @Test
    fun `token equal to appId joins with null token`() = runBlocking {
        controller(MeetingCredentials(appId = "app", token = "app")).join("room", 1)
        assertEquals("room/1/token=null", rtc.joins.single())
    }

    @Test
    fun `blank token joins with null token`() = runBlocking {
        controller(MeetingCredentials(appId = "app", token = "  ")).join("room", 1)
        assertEquals("room/1/token=null", rtc.joins.single())
    }

    // ---- 失败路径（回 idle）----

    @Test
    fun `credentials unavailable fails and resets to idle`() = runBlocking {
        val result = controller(null).join("room", 1)
        assertEquals(MeetingSessionController.JoinResult.Failed("credentials_unavailable"), result)
        assertEquals(MeetingPhase.Idle, machine.phase)
        assertTrue(rtc.joins.isEmpty())
    }

    @Test
    fun `missing appId fails and resets to idle`() = runBlocking {
        val result = controller(MeetingCredentials(token = "t")).join("room", 1)
        assertEquals(MeetingSessionController.JoinResult.Failed("credentials_missing_app_id"), result)
        assertEquals(MeetingPhase.Idle, machine.phase)
    }

    @Test
    fun `engine rejection fails and resets to idle`() = runBlocking {
        rtc.joinResult = -2
        val result = controller(MeetingCredentials(appId = "app", token = "t")).join("room", 1)
        assertEquals(MeetingSessionController.JoinResult.Failed("join_rejected"), result)
        assertEquals(MeetingPhase.Idle, machine.phase)
    }

    @Test
    fun `already in meeting fails without re-fetch`() = runBlocking {
        val c = controller(MeetingCredentials(appId = "app", token = "t"))
        c.join("room", 1)
        val again = c.join("room-2", 2)
        assertEquals(MeetingSessionController.JoinResult.Failed("meeting_already_active"), again)
        assertEquals("room/1/token=t", rtc.joins.single())
    }

    // ---- 取消安全 ----

    @Test
    fun `cancellation during fetch resets to idle and rethrows`() = runBlocking {
        val machineRef = machine
        val controller = MeetingSessionController(engine, machineRef) { _, _ ->
            delay(10_000)
            MeetingCredentials(appId = "app")
        }
        var cancelled = false
        coroutineScope {
            val job = async {
                try {
                    controller.join("room", 1)
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            kotlinx.coroutines.yield()
            job.cancel()
            try {
                job.await()
            } catch (_: CancellationException) {
            }
        }
        assertTrue(cancelled)
        assertEquals(MeetingPhase.Idle, machineRef.phase)
    }

    // ---- leave ----

    @Test
    fun `leave returns engine and machine to idle`() = runBlocking {
        val c = controller(MeetingCredentials(appId = "app", token = "t"))
        c.join("room", 1)
        assertTrue(engine.inChannel)
        c.leave()
        assertFalse(engine.inChannel)
        assertEquals(MeetingPhase.Idle, machine.phase)
        assertNull(machine.state.value.title)
    }
}
