package cn.appia.im.core.meeting.rtc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎封装对照（fake [AgoraRtcApi]——引擎语义可测，真实现仅在设备触达）：
 * 惰性建引擎（appId 到手才建/变更重建）、join/leave/mute 转发、事件流转发、幂等纪律。
 */
class AgoraMeetingEngineTest {

    /** 命令录音 fake：记录全部调用、存下 onEvent 供测试注入回调。 */
    private class FakeRtc : AgoraRtcApi {
        val calls = mutableListOf<String>()
        var onEvent: ((MeetingRtcEvent) -> Unit)? = null
        var joinResult = 0

        override fun create(appId: String, onEvent: (MeetingRtcEvent) -> Unit) {
            calls += "create:$appId"
            this.onEvent = onEvent
        }

        override fun joinChannel(token: String?, channelId: String, uid: Int): Int {
            calls += "join:$channelId/$uid/token=${token ?: "null"}"
            return joinResult
        }

        override fun leaveChannel(): Int {
            calls += "leave"
            return 0
        }

        override fun muteLocalAudioStream(muted: Boolean): Int {
            calls += "muteAudio:$muted"
            return 0
        }

        override fun muteLocalVideoStream(muted: Boolean): Int {
            calls += "muteVideo:$muted"
            return 0
        }

        override fun destroy() {
            calls += "destroy"
        }
    }

    private val rtc = FakeRtc()
    private val engine = AgoraMeetingEngine(rtc)

    // ---- join 生命周期 ----

    @Test
    fun `join lazily creates engine and forwards channel args`() {
        assertTrue(engine.join("app-1", "room-9", "007tok", 42))
        assertEquals(listOf("create:app-1", "join:room-9/42/token=007tok"), rtc.calls)
        assertTrue(engine.inChannel)
    }

    @Test
    fun `join with null token passes null through - cert-less path`() {
        assertTrue(engine.join("app-1", "room", null, 1))
        assertTrue(rtc.calls[1].endsWith("token=null"))
    }

    @Test
    fun `join with blank appId fails without touching rtc`() {
        assertFalse(engine.join("", "room", "t", 1))
        assertTrue(rtc.calls.isEmpty())
    }

    @Test
    fun `join rejection from sdk returns false`() {
        rtc.joinResult = -3
        assertFalse(engine.join("app-1", "room", "t", 1))
        assertFalse(engine.inChannel)
    }

    @Test
    fun `same appId reuses engine - different appId recreates`() {
        engine.join("app-1", "a", "t", 1)
        engine.join("app-1", "b", "t", 1)
        assertEquals(1, rtc.calls.count { it.startsWith("create") })
        engine.join("app-2", "b", "t", 1)
        assertEquals(listOf("destroy", "create:app-2"), rtc.calls.filter { it.startsWith("destroy") || it.startsWith("create") }.takeLast(2))
    }

    // ---- leave / mute 纪律 ----

    @Test
    fun `leave forwards once and is idempotent`() {
        engine.join("app-1", "room", "t", 1)
        engine.leave()
        engine.leave()
        assertEquals(listOf("leave"), rtc.calls.filter { it == "leave" })
        assertFalse(engine.inChannel)
    }

    @Test
    fun `leave without join is a no-op`() {
        engine.leave()
        assertTrue(rtc.calls.none { it == "leave" })
    }

    @Test
    fun `mute only takes effect in channel`() {
        engine.setMicMuted(true)
        engine.setCameraMuted(false)
        assertTrue(rtc.calls.none { it.startsWith("mute") })
        engine.join("app-1", "room", "t", 1)
        engine.setMicMuted(true)
        engine.setCameraMuted(false)
        assertEquals(listOf("muteAudio:true", "muteVideo:false"), rtc.calls.takeLast(2))
    }

    @Test
    fun `release leaves and destroys`() {
        engine.join("app-1", "room", "t", 1)
        engine.release()
        assertTrue(rtc.calls.containsAll(listOf("leave", "destroy")))
        engine.release() // 幂等
        assertEquals(1, rtc.calls.count { it == "destroy" })
    }

    // ---- 事件流转发 ----

    @Test
    fun `handler events flow through events`() = runBlocking {
        val received = mutableListOf<MeetingRtcEvent>()
        val collector = launch(Dispatchers.Unconfined) {
            engine.events.collect { received += it }
        }
        try {
            engine.join("app-1", "room", "t", 1)
            val fire = rtc.onEvent!!
            fire(MeetingRtcEvent.Joined)
            fire(MeetingRtcEvent.UserJoined(7))
            fire(MeetingRtcEvent.UserOffline(7, 1))
            fire(MeetingRtcEvent.RemoteAudioChanged(7, false))
            fire(MeetingRtcEvent.RemoteVideoChanged(7, true))
            fire(MeetingRtcEvent.Stats(125, 3))
            fire(MeetingRtcEvent.Error(102))
            withTimeout(2000) {
                while (received.size < 7) kotlinx.coroutines.delay(10)
            }
            assertEquals(
                listOf(
                    MeetingRtcEvent.Joined,
                    MeetingRtcEvent.UserJoined(7),
                    MeetingRtcEvent.UserOffline(7, 1),
                    MeetingRtcEvent.RemoteAudioChanged(7, false),
                    MeetingRtcEvent.RemoteVideoChanged(7, true),
                    MeetingRtcEvent.Stats(125, 3),
                    MeetingRtcEvent.Error(102),
                ),
                received,
            )
        } finally {
            collector.cancel()
        }
    }
}
