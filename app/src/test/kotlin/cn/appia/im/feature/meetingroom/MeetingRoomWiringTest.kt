package cn.appia.im.feature.meetingroom

import cn.appia.im.core.meeting.DEFAULT_MEETING_API_BASE
import cn.appia.im.core.meeting.MeetingCredentials
import cn.appia.im.core.meeting.resolveMeetingApiBase
import cn.appia.im.core.meeting.rtc.AgoraMeetingEngine
import cn.appia.im.core.meeting.rtc.AgoraRtcApi
import cn.appia.im.core.meeting.rtc.MeetingRtcEvent
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionController
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 会议房间接线流（≥4）：生产装配形（credentials lambda + 引擎 + 状态机）下的房间生命周期——
 * uid 派生入 rtc、路由入参落状态机、join→mute→leave→rejoin 闭环、base 解析链（生产 lambda 输入）。
 * 凭据失败/cert-less/重入/取消语义由 T6 MeetingSessionControllerTest 全覆盖，此处不重复。
 */
class MeetingRoomWiringTest {

    private class FakeRtc : AgoraRtcApi {
        var onEvent: ((MeetingRtcEvent) -> Unit)? = null
        val joins = mutableListOf<String>()
        var joinResult = 0

        override fun create(appId: String, onEvent: (MeetingRtcEvent) -> Unit) {
            this.onEvent = onEvent
        }

        override fun joinChannel(token: String?, channelId: String, uid: Int): Int {
            joins += "$channelId/$uid/token=${token ?: "null"}"
            onEvent?.invoke(MeetingRtcEvent.Joined)
            return joinResult
        }

        val mutes = mutableListOf<Boolean>()
        override fun leaveChannel(): Int = 0
        override fun muteLocalAudioStream(muted: Boolean): Int { mutes += muted; return 0 }
        override fun muteLocalVideoStream(muted: Boolean): Int = 0
        override fun destroy() {}
    }

    private val rtc = FakeRtc()

    /** 生产装配同构：MeetingRoomHandle 三件套（credentials lambda 注入 fake）。 */
    private fun handle(creds: MeetingCredentials?): MeetingRoomHandle {
        val machine = MeetingSessionStateMachine()
        val engine = AgoraMeetingEngine(rtc)
        return MeetingRoomHandle(
            engine = engine,
            machine = machine,
            controller = MeetingSessionController(engine, machine) { _, _ -> creds },
        )
    }

    @Test
    fun `uid derivation reaches rtc joinChannel and cert-less token stays null`() = runBlocking {
        val h = handle(MeetingCredentials(appId = "app", token = ""))
        val uid = uidForMeeting("user-1")
        val result = h.controller.join("room-1", uid, title = "weekly")
        assertEquals(MeetingSessionController.JoinResult.Joined, result)
        assertEquals("room-1/$uid/token=null", rtc.joins.single())
    }

    @Test
    fun `join populates machine with route title and channel as meetingNumber`() = runBlocking {
        val h = handle(MeetingCredentials(appId = "app", token = "t"))
        h.controller.join("ch-9", uidForMeeting("u"), title = "weekly sync")
        assertEquals(MeetingPhase.InMeeting, h.machine.phase)
        assertEquals("weekly sync", h.machine.state.value.title)
        assertEquals("ch-9", h.machine.state.value.meetingNumber)
    }

    @Test
    fun `room lifecycle join mute leave rejoin`() = runBlocking {
        val h = handle(MeetingCredentials(appId = "app", token = "t"))
        h.controller.join("room", 1, "a")
        h.machine.setMicMuted(true)
        h.engine.setMicMuted(true)
        assertTrue(h.machine.state.value.isMuted)
        assertEquals(listOf(true), rtc.mutes)
        h.controller.leave()
        assertEquals(MeetingPhase.Idle, h.machine.phase)
        assertFalse(h.machine.state.value.isMuted)
        // 再入会（离开确认后重进同/另会）：engine 不 destroy 可复用
        val again = h.controller.join("room-2", 2, "b")
        assertEquals(MeetingSessionController.JoinResult.Joined, again)
        assertEquals(MeetingPhase.InMeeting, h.machine.phase)
        assertEquals("b", h.machine.state.value.title)
    }

    @Test
    fun `production base resolution chain for credentials lambda`() {
        // 同源 appia 域 → 原样；非 appia 域（私有化/测试环境）→ 兜底；无源 → 兜底
        assertEquals("https://s1.appia.cn", resolveMeetingApiBase(null, "https://s1.appia.cn"))
        assertEquals(DEFAULT_MEETING_API_BASE, resolveMeetingApiBase(null, "https://im.internal.corp"))
        assertEquals(DEFAULT_MEETING_API_BASE, resolveMeetingApiBase(null, null))
        // 覆盖源优先于同源
        assertEquals("https://meeting.appia.vip", resolveMeetingApiBase("https://meeting.appia.vip", "https://s1.appia.cn"))
    }

    // ---- 进程级事件兜底（终审 I-2：Minimized 下 kicked/ended → leave）----

    @Test
    fun `minimized error falls back to leave and clears mutex`() = runBlocking {
        coroutineScope {
        val h = handle(MeetingCredentials(appId = "app", token = "t"))
        h.controller.join("room", 1, "a")
        h.machine.minimize()
        assertEquals(MeetingPhase.Minimized, h.machine.phase)
        assertTrue(h.machine.blocksVoiceStart())
        val collector = launch { collectRoomEndFallback(h) }
        yield()
        // 服务端踢出/SDK onError（房间屏不在栈上收集）——兜底回 idle
        rtc.onEvent?.invoke(MeetingRtcEvent.Error(-5))
        yield()
        assertEquals(MeetingPhase.Idle, h.machine.phase)
        assertFalse(h.machine.blocksVoiceStart())
        assertFalse(h.engine.inChannel)
        collector.cancel()
        }
    }

    @Test
    fun `minimized left event falls back to leave`() = runBlocking {
        coroutineScope {
        val h = handle(MeetingCredentials(appId = "app", token = "t"))
        h.controller.join("room", 1, "a")
        h.machine.minimize()
        val collector = launch { collectRoomEndFallback(h) }
        yield()
        rtc.onEvent?.invoke(MeetingRtcEvent.Left)
        yield()
        assertEquals(MeetingPhase.Idle, h.machine.phase)
        collector.cancel()
        }
    }

    @Test
    fun `inMeeting and idle events are not intercepted by fallback`() = runBlocking {
        coroutineScope {
        // InMeeting：房间屏在场自会处理（toast+pop）——兜底不重复动作
        val h = handle(MeetingCredentials(appId = "app", token = "t"))
        h.controller.join("room", 1, "a")
        val collector = launch { collectRoomEndFallback(h) }
        yield()
        rtc.onEvent?.invoke(MeetingRtcEvent.Error(-5))
        yield()
        assertEquals(MeetingPhase.InMeeting, h.machine.phase)
        assertTrue(h.engine.inChannel)
        collector.cancel()
        // 判定表：Minimized 只认 Error/Left；Idle 一律不兜底
        assertTrue(processLevelRoomEndNeeded(MeetingPhase.Minimized, MeetingRtcEvent.Error(-5)))
        assertTrue(processLevelRoomEndNeeded(MeetingPhase.Minimized, MeetingRtcEvent.Left))
        assertFalse(processLevelRoomEndNeeded(MeetingPhase.Minimized, MeetingRtcEvent.Stats(1, 1)))
        assertFalse(processLevelRoomEndNeeded(MeetingPhase.InMeeting, MeetingRtcEvent.Error(-5)))
        assertFalse(processLevelRoomEndNeeded(MeetingPhase.Idle, MeetingRtcEvent.Left))
        }
    }
}
