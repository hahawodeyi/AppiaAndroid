package cn.appia.im.domain.voice

import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.OncallTokenExpiredException
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallTimers
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.VoiceCallDismissedStore
import cn.appia.im.core.voice.rtc.InCallAudioRoute
import cn.appia.im.core.voice.rtc.VoiceRtcApi
import cn.appia.im.core.voice.rtc.VoiceRtcEngine
import cn.appia.im.core.voice.rtc.VoiceRtcEvent
import cn.appia.im.core.voice.rtc.VolumeSpeaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * brief Step 1（对照 RN voiceSessionStore.test.ts 主叫分支）：
 * 主叫链全编排——守卫（会议/房间 callMsg/凭证）、token 40002 重试一次、
 * init 失败/中止补 cancel、Agora 事件接线（Joined 回铃/UserJoined 接通/音量/静默）、
 * 30s 提示 + 65s 超时收尾、endCall 分支（cancel/leave/单飞）、
 * 代数防竞态（sessionGeneration/agoraGeneration）、inviteUsers 重锚计时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControllerOutgoingTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))

    /** 引擎独立 scope（共享调度器）：shutdown→engine.release() 的 scope.cancel 不波及 Controller。 */
    private val engineScope = TestScope(UnconfinedTestDispatcher(testScheduler))

    private val fakeRtc = FakeRtc()
    private val fakeRoute = FakeRoute()
    private lateinit var deps: FakeDeps
    private lateinit var controller: VoiceSessionController
    private val uiEvents = mutableListOf<VoiceUiEvent>()

    private val hoster = VoiceChatHoster(
        roomId = "room-1",
        roomType = "d",
        receivers = listOf(VoiceChatReceiver(receiver = "bob", name = "Bob")),
    )

    @Before
    fun setUp() {
        deps = FakeDeps(
            rtc = VoiceRtcEngine(fakeRtc, fakeRoute, engineScope, delayMs = 0),
            timers = VoiceCallTimers(testScope),
            store = VoiceSessionStore(),
        )
        controller = VoiceSessionController(deps, testScope)
        testScope.launch { controller.uiEvents.collect { uiEvents += it } }
    }

    @After
    fun tearDown() {
        testScope.cancel()
        engineScope.cancel()
    }

    private fun state() = controller.store.state.value

    /** brief: startOutgoing happy path——占位 callMsg → token → init → join(8888) → Joined → ringback 开启 → timers 启动。 */
    @Test
    fun `startOutgoing happy path`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        assertEquals(VoicePhase.OutgoingRinging, state().phase)
        assertEquals("rec-1", state().recordId)
        assertEquals("dialing", state().outgoingHint)
        assertEquals(8888L, state().agoraLocalUid)
        assertEquals("room-1", state().rid)
        assertTrue(state().channelId!!.isNotEmpty())

        // 占位 callMsg → users（被叫 out，receiverUid 已由 buildInitReceivers 分配）
        assertEquals(1, state().users.size)
        assertEquals("bob", state().users[0].username)
        assertEquals("out", state().users[0].status)
        assertTrue(state().users[0].uid!!.isNotEmpty())

        // join(8888)：token 来自 fetch，appId 由引擎侧持有
        assertEquals(1, fakeRtc.joinCalls)
        assertEquals(8888L, fakeRtc.lastJoin!!.uid)
        assertEquals("agora-token", fakeRtc.lastJoin!!.token)
        assertEquals(state().channelId, fakeRtc.lastJoin!!.channelId)
        assertEquals("bob", deps.lastInitBody!!.receivers[0].receiver)
        assertEquals(8888L, deps.lastInitBody!!.initiatorUid)

        // Joined → 主叫回铃开启（loop + 音量 10）
        fakeRtc.emit(VoiceRtcEvent.Joined)
        testScope.runCurrent()
        assertEquals(listOf("voice_calling" to true), fakeRtc.startMixingCalls)
        assertEquals(listOf(10), fakeRtc.adjustVolumeCalls)
        assertEquals(VoicePhase.OutgoingRinging, state().phase)

        // 计时/轮询已启动：30s 未到仍 dialing；10s 后已轮询一次
        testScheduler.advanceTimeBy(10_000)
        testScope.runCurrent()
        assertTrue(deps.calls.any { it.startsWith("getRecordStatusV2:rec-1") })
        testScheduler.advanceTimeBy(19_000)
        testScope.runCurrent()
        assertEquals("dialing", state().outgoingHint)
    }

    /** brief: meeting active blocks start——inMeeting/minimized 拦截 + meeting_end_voice_first。 */
    @Test
    fun `meeting active blocks start`() {
        deps.meetingPhase = "inMeeting"
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.calls.isEmpty())
        assertEquals(listOf("meeting_end_voice_first"), uiEvents.map { it.key })

        deps.meetingPhase = "minimized"
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)

        // preheating 放行（RN :1040 注释）
        deps.meetingPhase = "preheating"
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.OutgoingRinging, state().phase)
    }

    /** brief: room callMsg active blocks start——canStartOutgoing false → toast 事件。 */
    @Test
    fun `room callMsg active blocks start`() {
        deps.roomCallMsg = VoiceCallMsg(status = "calling", roomId = "room-1")
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(deps.calls.contains("informInit"))
        assertEquals(listOf("voice_calling_alert_title"), uiEvents.map { it.key })
    }

    /** brief: token failure resets session。 */
    @Test
    fun `token failure resets session`() {
        deps.tokenImpl = { okResponse() } // data 无 token → credentials null
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertNull(state().hoster)
        assertFalse(deps.calls.contains("informInit"))
        assertEquals(0, fakeRtc.joinCalls)
        assertEquals(listOf("voice_error_token_failed"), uiEvents.map { it.key })
    }

    /** brief: token 40002 retries once then succeeds。 */
    @Test
    fun `token 40002 retries once then succeeds`() {
        var calls = 0
        deps.tokenImpl = {
            calls += 1
            if (calls == 1) throw OncallTokenExpiredException()
            okResponse(data = buildJsonObject { put("token", "agora-token-2") })
        }
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        assertEquals(2, calls)
        assertEquals(1, deps.onTokenExpiredCalls)
        assertEquals("agora-token-2", fakeRtc.lastJoin!!.token)
        assertEquals(VoicePhase.OutgoingRinging, state().phase)
        assertTrue(deps.calls.contains("informInit"))
    }

    /** brief: init failure resets session with toast。 */
    @Test
    fun `init failure resets session with toast`() {
        deps.initResponse = okResponse(code = "1", data = buildJsonObject { put("message", "busy") })
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertEquals(0, fakeRtc.joinCalls)
        assertEquals(listOf("voice_error_init_failed"), uiEvents.map { it.key })
        assertEquals("busy", uiEvents[0].detail)
    }

    /** brief: abort after init cancels record——init 返回前 reset → 补发 updateRecordStatus(cancel)，不 join。 */
    @Test
    fun `abort after init cancels record`() {
        val gate = CompletableDeferred<Unit>()
        deps.initGate = gate
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.OutgoingRinging, state().phase)

        controller.resetSession()
        assertEquals(VoicePhase.Idle, state().phase)

        gate.complete(Unit)
        testScope.runCurrent()

        assertTrue(deps.calls.contains("updateRecordStatus:rec-1:cancel"))
        assertEquals(0, fakeRtc.joinCalls)
        assertEquals(VoicePhase.Idle, state().phase)
    }

    /** brief: remote joined enters inCall and stops ringback。 */
    @Test
    fun `remote joined enters inCall and stops ringback`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        fakeRtc.emit(VoiceRtcEvent.Joined)
        testScope.runCurrent()
        assertEquals(1, fakeRtc.startMixingCalls.size)

        fakeRtc.emit(VoiceRtcEvent.UserJoined(2001L))
        testScope.runCurrent()

        assertEquals(VoicePhase.InCall, state().phase)
        assertTrue(state().hadRemoteParticipant)
        assertNull(state().outgoingHint)
        assertTrue(fakeRtc.stopMixingCalls >= 1)
        // onEnteredInCall：入呼后轮询开启
        assertTrue(deps.calls.any { it.startsWith("getRecordStatusV2:rec-1") })
    }

    /** brief: 30s hint and 65s timeout end call。 */
    @Test
    fun `30s hint and 65s timeout end call`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        testScheduler.advanceTimeBy(30_000)
        testScope.runCurrent()
        assertEquals("not_connect", state().outgoingHint)
        assertEquals(VoicePhase.OutgoingRinging, state().phase)

        testScheduler.advanceTimeBy(35_000)
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.calls.contains("updateRecordStatus:rec-1:cancel"))
        assertEquals(1, deps.calls.count { it.startsWith("updateRecordStatus") })
    }

    /** brief: endCall in ringing phase sends cancel——shouldOncallCancelOnEndCall。 */
    @Test
    fun `endCall in ringing phase sends cancel`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        controller.endCall(null)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.calls.contains("updateRecordStatus:rec-1:cancel"))
        assertFalse(deps.calls.any { it.startsWith("leave:") })
    }

    /** brief: endCall after remote joined sends leave——shouldOncallLeaveOnEndCall。 */
    @Test
    fun `endCall after remote joined sends leave`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        fakeRtc.emit(VoiceRtcEvent.UserJoined(2001L))
        testScope.runCurrent()
        assertEquals(VoicePhase.InCall, state().phase)

        controller.endCall("user")
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.calls.contains("leave:rec-1"))
        assertFalse(deps.calls.any { it.startsWith("updateRecordStatus") })
        assertEquals(1, fakeRtc.leaveCalls)
    }

    /** brief: endCall single flight——重复调用只跑一次清理序列。 */
    @Test
    fun `endCall single flight`() {
        val gate = CompletableDeferred<Unit>()
        deps.updateStatusGate = gate
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        controller.endCall(null)
        controller.endCall(null)
        controller.endCall(null)
        testScope.runCurrent()

        gate.complete(Unit)
        testScope.runCurrent()

        assertEquals(1, deps.calls.count { it.startsWith("updateRecordStatus") })
        assertEquals(1, fakeRtc.leaveCalls)
        // stopHostRingback + leave 各触一次 stopAudioMixing（引擎幂等，RN runCatching 同容忍）
        assertTrue(fakeRtc.stopMixingCalls >= 1)
        assertEquals(VoicePhase.Idle, state().phase)
    }

    /** brief: generation mismatch drops late async——token 返回时已 reset → 不 init/join。 */
    @Test
    fun `generation mismatch drops late async`() {
        val gate = CompletableDeferred<Unit>()
        deps.tokenGate = gate
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.OutgoingRinging, state().phase)

        controller.resetSession()
        gate.complete(Unit)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(deps.calls.contains("informInit"))
        assertEquals(0, fakeRtc.joinCalls)
    }

    /** brief: inviteUsers adds users and resets host timers。 */
    @Test
    fun `inviteUsers adds users and resets host timers`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        testScheduler.advanceTimeBy(20_000)
        testScope.runCurrent()
        assertEquals("dialing", state().outgoingHint)

        controller.inviteUsers(listOf(VoiceChatReceiver(receiver = "carol", name = "Carol")))
        testScope.runCurrent()
        assertEquals(1, deps.calls.count { it == "addUsers" })
        assertEquals("carol", deps.lastAddUsersBody!!.receivers[0].receiver) // RN :1697 仅新被叫入 body

        // 计时已重锚：原 30s 提示点（t=30s）不再触发；新锚 30s 后（t=50s）触发
        testScheduler.advanceTimeBy(10_000)
        testScope.runCurrent()
        assertEquals("dialing", state().outgoingHint)
        testScheduler.advanceTimeBy(20_000)
        testScope.runCurrent()
        assertEquals("not_connect", state().outgoingHint)
    }

    /** brief: agora events after release are dropped——agoraGeneration。 */
    @Test
    fun `agora events after release are dropped`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()

        controller.endCall(null)
        testScope.runCurrent()
        fakeRtc.emit(VoiceRtcEvent.UserJoined(2001L))
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(state().hadRemoteParticipant)

        // shutdown 释放引擎（登出）：引擎自身 released 守卫丢弃回调
        controller.shutdown()
        fakeRtc.emit(VoiceRtcEvent.UserJoined(2002L))
        fakeRtc.emit(VoiceRtcEvent.Joined)
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(state().hadRemoteParticipant)
    }

    /** 自评补充：重拨（同 Controller 第二通）后 RTC 事件仍接通——事件代数 join 时重臂。 */
    @Test
    fun `redial after endCall accepts rtc events again`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        controller.endCall(null)
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)

        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.OutgoingRinging, state().phase)
        assertEquals("rec-1", state().recordId)

        fakeRtc.emit(VoiceRtcEvent.Joined)
        testScope.runCurrent()
        fakeRtc.emit(VoiceRtcEvent.UserJoined(2001L))
        testScope.runCurrent()
        assertEquals(VoicePhase.InCall, state().phase)
        assertTrue(state().hadRemoteParticipant)
    }

    /** brief: volume indication maps to users isSpeaking。 */
    @Test
    fun `volume indication maps to users isSpeaking`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        fakeRtc.emit(VoiceRtcEvent.UserJoined(2001L))
        testScope.runCurrent()

        val userUid = state().users[0].uid!!.toLong()
        fakeRtc.emit(VoiceRtcEvent.VolumeIndication(listOf(VolumeSpeaker(userUid, 77))))
        testScope.runCurrent()
        assertEquals(true, state().users[0].isSpeaking)

        fakeRtc.emit(VoiceRtcEvent.VolumeIndication(listOf(VolumeSpeaker(userUid, 0))))
        testScope.runCurrent()
        assertEquals(false, state().users[0].isSpeaking)
    }

    /** brief: user mute audio maps to users isMuted。 */
    @Test
    fun `user mute audio maps to users isMuted`() {
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        fakeRtc.emit(VoiceRtcEvent.UserJoined(2001L))
        testScope.runCurrent()

        val userUid = state().users[0].uid!!.toLong()
        fakeRtc.emit(VoiceRtcEvent.UserMuteAudio(userUid, true))
        testScope.runCurrent()
        assertEquals(true, state().users[0].isMuted)

        fakeRtc.emit(VoiceRtcEvent.UserMuteAudio(userUid, false))
        testScope.runCurrent()
        assertEquals(false, state().users[0].isMuted)
    }

    // ── fixtures ──

    private class FakeDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
    ) : VoiceSessionDeps {
        val calls = mutableListOf<String>()
        var onTokenExpiredCalls = 0
        var meetingPhase: String? = null
        var roomCallMsg: VoiceCallMsg? = null
        var contextOrNull: VoiceSessionContext = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-1", username = "alice", userDisplayName = "Alice",
        )

        var tokenGate: CompletableDeferred<Unit>? = null
        var tokenImpl: suspend () -> OncallResponse =
            { okResponse(data = buildJsonObject { put("token", "agora-token") }) }
        var initGate: CompletableDeferred<Unit>? = null
        var initResponse: OncallResponse =
            okResponse(data = buildJsonObject { put("recordId", "rec-1"); put("status", 1) })
        var updateStatusGate: CompletableDeferred<Unit>? = null
        var lastInitBody: InformInitBody? = null
        var lastAddUsersBody: AddUsersBody? = null

        override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse {
            calls += "fetchAgoraToken"
            tokenGate?.await()
            return tokenImpl()
        }

        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse {
            calls += "informInit"
            lastInitBody = body
            initGate?.await()
            return initResponse
        }

        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse {
            calls += "updateRecordStatus:$recordId:$status"
            updateStatusGate?.await()
            return okResponse()
        }

        override suspend fun join(ctx: OncallContext, body: cn.appia.im.core.voice.JoinBody): OncallResponse {
            calls += "join"
            return okResponse()
        }

        override suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String?): OncallResponse {
            calls += "reject:$recordId"
            return okResponse()
        }

        override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse {
            calls += "leave:$recordId"
            return okResponse()
        }

        override suspend fun addUsers(ctx: OncallContext, body: AddUsersBody): OncallResponse {
            calls += "addUsers"
            lastAddUsersBody = body
            return okResponse(data = buildJsonObject { put("status", 1) })
        }

        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse {
            calls += "getRecordStatusV2:$recordId"
            return okResponse(data = buildJsonObject { })
        }

        override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse {
            calls += "getNotify:$recordId"
            return okResponse()
        }

        override fun sessionContext(): VoiceSessionContext? = contextOrNull

        override val meetingPhaseProvider: () -> String? = { meetingPhase }

        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore()

        override fun roomCallMsgForRid(rid: String): VoiceCallMsg? = roomCallMsg

        override fun onTokenExpired() {
            onTokenExpiredCalls += 1
        }
    }

    private class FakeRtc : VoiceRtcApi {
        var createCalls = 0
        var joinCalls = 0
        var joinResult = 0
        var lastJoin: JoinArgs? = null
        var leaveCalls = 0
        var volumeIndicationCalls = 0
        var stopMixingCalls = 0
        val muteCalls = mutableListOf<Boolean>()
        val speakerCalls = mutableListOf<Boolean>()
        val startMixingCalls = mutableListOf<Pair<String, Boolean>>()
        val adjustVolumeCalls = mutableListOf<Int>()

        private var onEvent: ((VoiceRtcEvent) -> Unit)? = null

        data class JoinArgs(
            val token: String?,
            val channelId: String,
            val uid: Long,
            val encKey: String?,
            val muted: Boolean,
            val speakerOn: Boolean,
        )

        fun emit(event: VoiceRtcEvent) {
            onEvent?.invoke(event)
        }

        override fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit) {
            createCalls += 1
            this.onEvent = onEvent
        }

        override fun joinChannel(
            token: String?,
            channelId: String,
            uid: Long,
            encKey: String?,
            muted: Boolean,
            speakerOn: Boolean,
        ): Int {
            joinCalls += 1
            lastJoin = JoinArgs(token, channelId, uid, encKey, muted, speakerOn)
            return joinResult
        }

        override fun leaveChannel(): Int {
            leaveCalls += 1
            return 0
        }

        override fun muteLocalAudioStream(muted: Boolean): Int {
            muteCalls += muted
            return 0
        }

        override fun setEnableSpeakerphone(enabled: Boolean): Int {
            speakerCalls += enabled
            return 0
        }

        override fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean): Int {
            volumeIndicationCalls += 1
            return 0
        }

        override fun startAudioMixing(path: String, loop: Boolean): Int {
            startMixingCalls += path to loop
            return 0
        }

        override fun stopAudioMixing(): Int {
            stopMixingCalls += 1
            return 0
        }

        override fun adjustAudioMixingVolume(volume: Int): Int {
            adjustVolumeCalls += volume
            return 0
        }

        override fun destroy() {}
    }

    private class FakeRoute : InCallAudioRoute {
        var startCalls = 0
        var stopCalls = 0
        val speakerphoneCalls = mutableListOf<Boolean>()

        override fun start() {
            startCalls += 1
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun setSpeakerphoneOn(enabled: Boolean) {
            speakerphoneCalls += enabled
        }
    }
}

/** 测试夹具共享：code "0" + success true + 可选 data。 */
private fun okResponse(
    code: String = "0",
    data: JsonObject? = null,
): OncallResponse = OncallResponse(code = JsonPrimitive(code), data = data, success = true)
