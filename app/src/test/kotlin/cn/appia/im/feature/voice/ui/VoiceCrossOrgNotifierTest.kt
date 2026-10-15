package cn.appia.im.feature.voice.ui

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
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VoiceCrossOrgNotifier 轮询过滤 + 动作语义（RN index.tsx :46-77/:90-124）：
 * - idle 相位门（非 idle 不拉）
 * - 外来 org 判定（org 不同 + calling + 非本人发起）
 * - ignore → reject(record.org) + 收横幅
 * - switch 失败 → onError(voice_crossorg_switchunavailable)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCrossOrgNotifierTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(UnconfinedTestDispatcher(scheduler))

    private class NotifierDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
        override val ringer: VoiceRinger,
    ) : VoiceSessionDeps {
        var rejectCalls = mutableListOf<String>()
        var switchResult = false
        var switchedOrgs = mutableListOf<String>()
        override val meetingPhaseProvider: () -> String? = { null }
        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore()
        override val deviceIdProvider: () -> String? = { null }
        override fun sessionContext(): VoiceSessionContext? = VoiceSessionContext(
            org = "bitmain", businessToken = "bt", apiHost = "gw",
            userId = "u1", username = "alice", userDisplayName = "Alice",
        )
        override fun roomCallMsgForRid(rid: String): cn.appia.im.core.voice.VoiceCallMsg? = null
        override fun clearRoomCallMsg(rid: String) {}
        override fun onTokenExpired() {}
        override suspend fun switchToVoiceOrg(org: String): Boolean {
            switchedOrgs += org
            return switchResult
        }
        override fun roomCallMsgRawForRid(rid: String): String? = null
        override fun roomOnCallStatusForRid(rid: String): Boolean? = null
        override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse = error("unused")
        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse = error("unused")
        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse = error("unused")
        override suspend fun join(ctx: OncallContext, body: JoinBody): OncallResponse = error("unused")
        override suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String?): OncallResponse {
            rejectCalls += "$recordId:$notificationOrg"
            return OncallResponse(code = JsonPrimitive("0"), data = JsonNull, success = true)
        }
        override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse = error("unused")
        override suspend fun addUsers(ctx: OncallContext, body: AddUsersBody): OncallResponse = error("unused")
        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse = error("unused")
        override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse = error("unused")
    }

    private lateinit var deps: NotifierDeps
    private lateinit var controller: VoiceSessionController
    private var fetched = 0
    private val notified = mutableListOf<String>()
    private lateinit var notifier: VoiceCrossOrgNotifier

    private fun record(org: String, recordId: String = "r-1", status: String? = "calling", initiator: String? = "bob") =
        VoiceCrossOrgNotifier.CrossOrgRecord(org = org, recordId = recordId, status = status, initiatorUsername = initiator)

    @Before
    fun setUp() {
        deps = NotifierDeps(
            rtc = VoiceRtcEngine(FakeRtc(), FakeRoute(), scope, delayMs = 0),
            timers = VoiceCallTimers(scope),
            store = VoiceSessionStore(),
            ringer = object : VoiceRinger {
                override fun start() {}
                override fun stop() {}
            },
        )
        controller = VoiceSessionController(deps, scope)
        notifier = VoiceCrossOrgNotifier(
            controller = controller,
            deps = deps,
            scope = scope,
            fetchCurrentRecords = { _, _ -> fetched += 1; lastRecords },
            notifyHost = { _, org, recordId, _ -> notified += "$org:$recordId" },
        )
    }

    private var lastRecords: List<VoiceCrossOrgNotifier.CrossOrgRecord> = emptyList()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `foreign org calling record shows banner and notifies host`() = runTest(scheduler) {
        lastRecords = listOf(record("sophgo"), record("bitmain"))
        notifier.poll()
        scope.runCurrent()
        val banner = notifier.banner.value
        assertEquals("sophgo", banner?.record?.org)
        assertEquals(listOf("sophgo:r-1"), notified)
    }

    @Test
    fun `local org and self-initiated and non-calling rows are ignored`() = runTest(scheduler) {
        lastRecords = listOf(
            record("bitmain"), // 本 org
            record("sophgo", initiator = "alice"), // 本人发起
            record("sophgo", status = "ended"), // 非 calling
        )
        notifier.poll()
        scope.runCurrent()
        assertEquals(null, notifier.banner.value)
    }

    @Test
    fun `non idle phase skips fetch`() = runTest(scheduler) {
        controller.store.update { it.copy(phase = VoicePhase.InCall) }
        notifier.poll()
        scope.runCurrent()
        assertEquals(0, fetched)
    }

    @Test
    fun `ignore rejects with record org and clears banner`() = runTest(scheduler) {
        lastRecords = listOf(record("sophgo"))
        notifier.poll()
        scope.runCurrent()
        notifier.ignore(notifier.banner.value!!.record)
        scope.runCurrent()
        assertEquals(listOf("r-1:sophgo"), deps.rejectCalls)
        assertEquals(null, notifier.banner.value)
    }

    @Test
    fun `switch org failure surfaces switchunavailable error`() = runTest(scheduler) {
        lastRecords = listOf(record("sophgo"))
        notifier.poll()
        scope.runCurrent()
        deps.switchResult = false
        var errorKey: String? = null
        notifier.switchOrg(notifier.banner.value!!.record) { errorKey = it }
        scope.runCurrent()
        assertEquals(listOf("sophgo"), deps.switchedOrgs)
        assertEquals("voice_crossorg_switchunavailable", errorKey)
        assertEquals(null, notifier.banner.value)
    }

    @Test
    fun `switch org success clears banner without error`() = runTest(scheduler) {
        lastRecords = listOf(record("sophgo"))
        notifier.poll()
        scope.runCurrent()
        deps.switchResult = true
        var errorKey: String? = null
        notifier.switchOrg(notifier.banner.value!!.record) { errorKey = it }
        scope.runCurrent()
        assertEquals(null, errorKey)
        assertEquals(null, notifier.banner.value)
    }

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
}
