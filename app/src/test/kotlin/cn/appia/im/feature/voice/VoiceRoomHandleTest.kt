package cn.appia.im.feature.voice

import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.JoinBody
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.VoiceCallTimers
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.VoiceCallDismissedStore
import cn.appia.im.core.voice.rtc.InCallAudioRoute
import cn.appia.im.core.voice.rtc.VoiceRtcApi
import cn.appia.im.core.voice.rtc.VoiceRtcEngine
import cn.appia.im.core.voice.rtc.VoiceRtcEvent
import cn.appia.im.domain.voice.CallSyncWatcher
import cn.appia.im.domain.voice.VoiceRinger
import cn.appia.im.domain.voice.VoiceSessionContext
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionDeps
import cn.appia.im.domain.voice.VoiceSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * brief Step 1（MeetingRoomWiringTest 同位）：装配束语义——
 * - controller/store 同源（deps.store 即 handle.controller.store）
 * - shutdown 联动（endCall → idle + watcher.stop + FGS stop；Task 10 handoff ③）
 * - phase → FGS 驱动（active 相位起、idle 停——RN index.tsx:45-49/82-89）
 * - 互斥小写缝（坑 6）：VoicePhase.Idle.name == "Idle"——Meeting 阻塞判定输入必须 lowercase
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRoomHandleTest {

    private class FakeRtc : VoiceRtcApi {
        override fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit) {}
        override fun joinChannel(token: String?, channelId: String, uid: Long, encKey: String?, muted: Boolean, speakerOn: Boolean): Int = 0
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

    /** 装配语义测试 deps：端点 error（Controller 全链已有专测，此处只验装配束）。 */
    private class AssemblyDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
        override val ringer: VoiceRinger,
    ) : VoiceSessionDeps {
        var ringerStarts = 0
        var ringerStops = 0
        override val meetingPhaseProvider: () -> String? = { null }
        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore()
        override val deviceIdProvider: () -> String? = { null }
        override fun sessionContext(): VoiceSessionContext? = null
        override fun roomCallMsgForRid(rid: String): cn.appia.im.core.voice.VoiceCallMsg? = null
        override fun clearRoomCallMsg(rid: String) {}
        override fun onTokenExpired() {}
        override suspend fun switchToVoiceOrg(org: String): Boolean = false
        override fun roomCallMsgRawForRid(rid: String): String? = null
        override fun roomOnCallStatusForRid(rid: String): Boolean? = null
        override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse = error("not under test")
        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse = error("not under test")
        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse = error("not under test")
        override suspend fun join(ctx: OncallContext, body: JoinBody): OncallResponse = error("not under test")
        override suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String?): OncallResponse = error("not under test")
        override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse = error("not under test")
        override suspend fun addUsers(ctx: OncallContext, body: AddUsersBody): OncallResponse = error("not under test")
        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse = error("not under test")
        override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse = error("not under test")
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private val scheduler = TestCoroutineScheduler()

    /** FGS 启停记录器（VoiceFloatServiceStarter 语义替身）。 */
    private class FgsRecorder {
        var started = false
        var startCount = 0
        var stopCount = 0
    }

    private class TestHandle(
        scope: CoroutineScope,
        val fgs: FgsRecorder,
    ) {
        val deps = AssemblyDeps(
            rtc = VoiceRtcEngine(FakeRtc(), FakeRoute(), scope, delayMs = 0),
            timers = VoiceCallTimers(scope),
            store = VoiceSessionStore(),
            ringer = object : VoiceRinger {
                override fun start() {}
                override fun stop() {}
            },
        )
        val controller = VoiceSessionController(deps, scope)
        val watcher = CallSyncWatcher(scope, { _ -> error("no db in assembly test") }, controller, { null })
        val pushIngress = cn.appia.im.domain.voice.VoicePushIngress(
            controller = controller,
            sessionContextProvider = { null },
            queue = cn.appia.im.domain.voice.VoicePushQueue(),
            deps = deps,
        )
        val crossOrgNotifier = cn.appia.im.feature.voice.ui.VoiceCrossOrgNotifier(
            controller = controller,
            deps = deps,
            scope = scope,
        )
        val handle = VoiceRoomHandle(
            controller = controller,
            watcher = watcher,
            pushIngress = pushIngress,
            crossOrgNotifier = crossOrgNotifier,
            scope = scope,
            fgsStarter = { fgs.started = true; fgs.startCount += 1 },
            fgsStopper = { fgs.started = false; fgs.stopCount += 1 },
        )
    }

    @Test
    fun `controller and handle share one store instance`() = runTest(scheduler) {
        val scope = TestScope(UnconfinedTestDispatcher(scheduler))
        val th = TestHandle(scope, FgsRecorder())
        try {
            // deps 同源：controller.store 即 deps.store（Task 10 handoff ① 的装配面验证）
            assertEquals(th.deps.store, th.controller.store)
            assertEquals(th.deps.store, th.handle.controller.store)
            assertEquals(VoicePhase.Idle, th.controller.store.state.value.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `shutdown ends active call stops watcher and fgs`() = runTest(scheduler) {
        val scope = TestScope(UnconfinedTestDispatcher(scheduler))
        val fgs = FgsRecorder()
        val th = TestHandle(scope, fgs)
        try {
            // 置位非 idle + FGS 运行中（模拟通话中登出）
            th.controller.store.update { it.copy(phase = VoicePhase.InCall) }
            fgs.started = true
            th.handle.shutdown()
            scope.runCurrent()
            assertEquals(VoicePhase.Idle, th.controller.store.state.value.phase)
            assertFalse(fgs.started)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `active phase drives fgs start and idle drives stop`() = runTest(scheduler) {
        val scope = TestScope(UnconfinedTestDispatcher(scheduler))
        val fgs = FgsRecorder()
        val th = TestHandle(scope, fgs)
        try {
            th.handle.startPlatformWiring(scope)
            scope.runCurrent()
            assertFalse(fgs.started) // idle：不启

            // outgoingRinging（active 相位）→ FGS 起
            th.controller.store.update { it.copy(phase = VoicePhase.OutgoingRinging) }
            scope.runCurrent()
            assertTrue(fgs.started)

            // idle 回落 → FGS 停
            th.controller.store.update { it.copy(phase = VoicePhase.Idle) }
            scope.runCurrent()
            assertFalse(fgs.started)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `meeting blocked by voice requires lowercase phase name`() {
        // 坑 6 反例：未 lowercase 的 "Idle" 会被 meetingBlockedByVoice 误判阻塞
        assertTrue(cn.appia.im.domain.meeting.meetingBlockedByVoice("inCall"))
        assertFalse(cn.appia.im.domain.meeting.meetingBlockedByVoice(VoicePhase.Idle.name.lowercase()))
        assertTrue(cn.appia.im.domain.meeting.meetingBlockedByVoice(VoicePhase.Idle.name)) // "Idle" != "idle" → 阻塞误报——装配缝必须 lowercase
    }
}
