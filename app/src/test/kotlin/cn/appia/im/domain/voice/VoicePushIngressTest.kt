package cn.appia.im.domain.voice

import cn.appia.im.core.push.PushVoiceOncallParams
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallTimers
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.VoiceCallDismissedStore
import cn.appia.im.core.voice.rtc.InCallAudioRoute
import cn.appia.im.core.voice.rtc.VoiceRtcApi
import cn.appia.im.core.voice.rtc.VoiceRtcEngine
import cn.appia.im.core.voice.rtc.VoiceRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * brief Step 1（对照 RN voicePushIngress.test.ts 八用例移植）：
 * callEnd 丢弃 / 未登录入队 / 嵌入 callMsg 短路 fetch / 无 channel 拉取后呈现 /
 * fetch 失败入队重试 / 同 recordId 在会话中跳过 / shouldPresent 不满足跳过 / 登录后 drain。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePushIngressTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))
    private val engineScope = TestScope(UnconfinedTestDispatcher(testScheduler))

    private val fakeRtc = FakeRtc()
    private val fakeRoute = FakeRoute()
    private lateinit var deps: FakeDeps
    private lateinit var controller: VoiceSessionController
    private lateinit var queue: VoicePushQueue
    private lateinit var ingress: VoicePushIngress

    /** 登录态开关（RN useAuthStore.isAuthenticated）。 */
    private var authenticated = true

    @Before
    fun setUp() {
        deps = FakeDeps(
            rtc = VoiceRtcEngine(fakeRtc, fakeRoute, engineScope, delayMs = 0),
            timers = VoiceCallTimers(testScope),
            store = VoiceSessionStore(),
        )
        controller = VoiceSessionController(deps, testScope)
        queue = VoicePushQueue()
        ingress = VoicePushIngress(
            controller = controller,
            sessionContextProvider = { if (authenticated) deps.contextOrNull else null },
            queue = queue,
            deps = deps,
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        engineScope.cancel()
    }

    private fun state() = controller.store.state.value

    /** RN callMsgFixture：alice → bob 直聊来电。 */
    private val callMsgFixture = buildJsonObject {
        put("status", "calling")
        put("rid", "r1")
        put("roomId", "r1")
        put("roomType", "d")
        put("org", "bitmain")
        put("recordData", buildJsonObject {
            put("exist", "true")
            put("recordId", "rec-1")
            put("channelId", "ch-1")
            put("initiator", "alice")
        })
        put("userStatus", buildJsonArray {
            add(buildJsonObject { put("username", "alice"); put("status", "in") })
            add(buildJsonObject { put("username", "bob"); put("status", "out") })
        })
    }

    private fun oncallParams(
        recordId: String? = "rec-1",
        callMsgRaw: String? = null,
        isCallEnd: Boolean = false,
        org: String? = null,
    ) = PushVoiceOncallParams(
        recordId = recordId,
        org = org,
        isCallEnd = isCallEnd,
        callMsgRaw = callMsgRaw,
    )

    /** RN ingressParams：{rid:'r1', voiceOncall:{msgType:'oncall', recordId:'rec-1'}}——launch+runCurrent 驱动。 */
    private fun handleDefault() {
        testScope.launch { ingress.handle("r1", oncallParams()) }
        testScope.runCurrent()
    }

    @Test
    fun `callEnd intent is dropped`() {
        testScope.launch { ingress.handle("r1", oncallParams(isCallEnd = true)) }
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `not authenticated enqueues`() {
        authenticated = false
        handleDefault()
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `embedded callMsg with channel short-circuits fetch`() {
        // fetch 恒返空数据：若 resolve 未被嵌入 callMsg 短路 → null → 入队 + 不呈现。
        // （presentIncoming 自带的轮询也会打 getRecordStatusV2,calls 计数不可作短路判据）
        deps.recordStatusV2Impl = { okResponse(data = null) }
        testScope.launch { ingress.handle("r1", oncallParams(callMsgRaw = callMsgFixture.toString())) }
        testScope.runCurrent()

        assertEquals(VoicePhase.IncomingRinging, state().phase)
        assertEquals("r1", state().rid)
        assertEquals("rec-1", state().recordId)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `no channel fetches record and presents when eligible`() {
        deps.recordStatusV2Impl = { okResponse(data = callMsgFixture) }
        handleDefault()

        assertTrue(deps.calls.any { it.startsWith("getRecordStatusV2:rec-1") })
        assertEquals(VoicePhase.IncomingRinging, state().phase)
        assertEquals("r1", state().rid)
    }

    @Test
    fun `fetch failure enqueues retry`() {
        deps.recordStatusV2Impl = { throw IllegalStateException("timeout") }
        handleDefault()
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertEquals(1, queue.pendingCount)

        // 重试成功 → 呈现（RN reconnect recovery 用例）
        deps.recordStatusV2Impl = { okResponse(data = callMsgFixture) }
        testScope.launch { ingress.drainPending() }
        testScope.runCurrent()

        assertEquals(VoicePhase.IncomingRinging, state().phase)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `already handling same recordId skips`() {
        deps.recordStatusV2Impl = { okResponse(data = callMsgFixture) }
        // incomingRinging 同 recordId（RN phase='incomingRinging' rid='r1' recordId='rec-1'）
        controller.store.update {
            it.copy(
                phase = VoicePhase.IncomingRinging,
                rid = "r1",
                recordId = "rec-1",
                callMsg = VoiceCallMsg(status = "calling", roomId = "r1"),
            )
        }
        val ringerStartsBefore = deps.ringerStarts
        handleDefault()

        // 已在响铃同记录 → 不重复 presentIncoming（铃声不重起）也不入队
        assertEquals(ringerStartsBefore, deps.ringerStarts)
        assertEquals(0, queue.pendingCount)
        assertEquals(VoicePhase.IncomingRinging, state().phase)
    }

    @Test
    fun `should not present skips`() {
        // status=end → shouldPresentIncomingForUser false → 不呈现也不入队（RN :194-209）
        deps.recordStatusV2Impl = {
            okResponse(data = buildJsonObject {
                put("status", "end")
                put("roomId", "r1")
                put("roomType", "d")
                put("recordData", buildJsonObject {
                    put("exist", "true")
                    put("recordId", "rec-1")
                    put("channelId", "ch-1")
                    put("initiator", "alice")
                })
            })
        }
        handleDefault()
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `drain processes queued intents after login`() {
        deps.recordStatusV2Impl = { okResponse(data = callMsgFixture) }
        authenticated = false
        handleDefault()
        assertEquals(1, queue.pendingCount)

        authenticated = true
        testScope.launch { ingress.drainPending() }
        testScope.runCurrent()

        assertEquals(VoicePhase.IncomingRinging, state().phase)
        assertEquals(0, queue.pendingCount)
    }

    // ── fixtures（沿用 domain/voice 既有测试的 FakeDeps/FakeRtc 形态） ──

    private class FakeDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore(),
    ) : VoiceSessionDeps {
        val calls = mutableListOf<String>()
        var contextOrNull: VoiceSessionContext? = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-b", username = "bob", userDisplayName = "Bob",
        )
        var recordStatusV2Impl: suspend () -> OncallResponse = { okResponse() }
        var ringerStarts = 0
        var ringerStops = 0

        override suspend fun fetchAgoraToken(
            ctx: OncallContext,
            channelName: String,
            uid: Long,
        ): OncallResponse = okResponse()

        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse = okResponse()

        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse =
            okResponse()

        override suspend fun join(
            ctx: OncallContext,
            body: cn.appia.im.core.voice.JoinBody,
        ): OncallResponse = okResponse()

        override suspend fun reject(
            ctx: OncallContext,
            recordId: String,
            username: String,
            notificationOrg: String?,
        ): OncallResponse = okResponse()

        override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse =
            okResponse()

        override suspend fun addUsers(
            ctx: OncallContext,
            body: cn.appia.im.core.voice.AddUsersBody,
        ): OncallResponse = okResponse()

        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse {
            calls += "getRecordStatusV2:$recordId"
            return recordStatusV2Impl()
        }

        override suspend fun getNotify(
            ctx: OncallContext,
            org: String,
            recordId: String,
            username: String,
            recordOrg: String?,
        ): OncallResponse {
            calls += "getNotify:$recordId"
            return okResponse()
        }

        override fun sessionContext(): VoiceSessionContext? = contextOrNull

        override val meetingPhaseProvider: () -> String? = { null }

        override fun roomCallMsgForRid(rid: String): VoiceCallMsg? = null

        override fun clearRoomCallMsg(rid: String) {}

        override fun onTokenExpired() {}

        override suspend fun switchToVoiceOrg(org: String): Boolean = true

        override val ringer: VoiceRinger = object : VoiceRinger {
            override fun start() {
                ringerStarts += 1
            }

            override fun stop() {
                ringerStops += 1
            }
        }

        override val deviceIdProvider: () -> String? = { null }

        override fun roomCallMsgRawForRid(rid: String): String? = null

        override fun roomOnCallStatusForRid(rid: String): Boolean? = null
    }

    private class FakeRtc : VoiceRtcApi {
        private var onEvent: ((VoiceRtcEvent) -> Unit)? = null

        override fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit) {
            this.onEvent = onEvent
        }

        override fun joinChannel(
            token: String?,
            channelId: String,
            uid: Long,
            encKey: String?,
            muted: Boolean,
            speakerOn: Boolean,
        ): Int = 0

        override fun leaveChannel(): Int = 0

        override fun muteLocalAudioStream(muted: Boolean): Int = 0

        override fun setEnableSpeakerphone(enabled: Boolean): Int = 0

        override fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean): Int = 0

        override fun startAudioMixing(path: String, loop: Boolean): Int = 0

        override fun stopAudioMixing(): Int = 0

        override fun adjustAudioMixingVolume(volume: Int): Int = 0

        override fun destroy() {}
    }

    private class FakeRoute : InCallAudioRoute {
        override fun start() {}
        override fun stop() {}
        override fun setSpeakerphoneOn(enabled: Boolean) {}
    }
}

private fun okResponse(
    code: String = "0",
    data: JsonObject? = null,
): OncallResponse = OncallResponse(code = JsonPrimitive(code), data = data, success = true)
