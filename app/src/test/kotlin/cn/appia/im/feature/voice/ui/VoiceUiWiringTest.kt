package cn.appia.im.feature.voice.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.JoinBody
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
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
import cn.appia.im.domain.voice.OtherIncoming
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * brief Step 1（RN VoiceCallHost.test + VoiceIncomingBanner.test 同位）：
 * 真 VoiceSessionStore + 真 Controller（FakeDeps，VoiceSessionControllerOutgoingTest 夹具模式）
 * → 驱动相位 → testTag 存在性 + 点击 → Controller 动作落 store。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceUiWiringTest {

    @get:org.junit.Rule
    val rule = createComposeRule()

    private val testScheduler = TestCoroutineScheduler()
    private val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))
    private val engineScope = TestScope(UnconfinedTestDispatcher(testScheduler))

    private val fakeRtc = FakeRtc()
    private lateinit var deps: FakeDeps
    private lateinit var controller: VoiceSessionController

    private val pickedMembers = mutableListOf<Pair<String, String>>()
    private val openedRooms = mutableListOf<String>()
    private var toastKey: String? = null

    private val t: (String) -> String = { key ->
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.resources.getIdentifier(key.lowercase(), "string", context.packageName)
            .takeIf { it != 0 }?.let { context.getString(it) } ?: key
    }

    @Before
    fun setUp() {
        deps = FakeDeps(
            rtc = VoiceRtcEngine(fakeRtc, FakeRoute(), engineScope, delayMs = 0),
            timers = VoiceCallTimers(testScope),
            store = VoiceSessionStore(),
            ok = { code, data ->
                OncallResponse(code = JsonPrimitive(code), data = data ?: JsonNull, success = true)
            },
        )
        controller = VoiceSessionController(deps, testScope)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        engineScope.cancel()
    }

    private fun state() = controller.store.state.value

    private fun setContent() {
        rule.setContent {
            AppiaTheme(isDark = false) {
                VoiceCallHost(
                    controller = controller,
                    t = t,
                    onOpenRoom = { openedRooms += it },
                    onPickMembers = { rid, roomType -> pickedMembers += rid to roomType },
                )
            }
        }
        rule.waitForIdle()
    }

    /** 主叫链最小置位：hoster + OutgoingRinging + 群组 callMsg（invite 按钮可见性依赖 rid）。 */
    private fun startOutgoingGroup() {
        controller.startOutgoing(
            VoiceChatHoster(
                roomId = "room-1",
                roomType = "p",
                receivers = listOf(VoiceChatReceiver(receiver = "bob", name = "Bob")),
            ),
        )
        testScope.runCurrent()
    }

    // ── 相位分流 testTag 存在性 ──

    @Test
    fun `idle renders nothing`() {
        setContent()
        rule.onNodeWithTag("qa-voice-overlay").assertDoesNotExist()
        rule.onNodeWithTag("qa-voice-incoming-banner").assertDoesNotExist()
    }

    @Test
    fun `incoming ringing collapsed shows banner`() {
        controller.store.update {
            it.copy(
                phase = VoicePhase.IncomingRinging,
                callMsg = VoiceCallMsg(status = "calling", roomId = "room-1"),
                rid = "room-1",
            )
        }
        setContent()
        rule.onNodeWithTag("qa-voice-incoming-banner").assertExists()
        rule.onNodeWithTag("qa-voice-confirm").assertDoesNotExist()
    }

    @Test
    fun `banner body tap opens confirm`() {
        controller.store.update {
            it.copy(
                phase = VoicePhase.IncomingRinging,
                callMsg = VoiceCallMsg(status = "calling", roomId = "room-1"),
            )
        }
        setContent()
        rule.onNodeWithTag("qa-voice-incoming-banner-body").performClick()
        rule.waitForIdle()
        assertTrue(state().incomingUiExpanded)
        rule.onNodeWithTag("qa-voice-confirm").assertExists()
        rule.onNodeWithTag("qa-voice-incoming-banner").assertDoesNotExist()
    }

    @Test
    fun `active phase shows overlay with controls`() {
        startOutgoingGroup()
        setContent()
        for (tag in listOf(
            "qa-voice-overlay",
            "qa-voice-mute",
            "qa-voice-speaker",
            "qa-voice-minimize",
            "qa-voice-hangup",
            "qa-voice-invite",
        )) {
            rule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun `minimized renders nothing`() {
        startOutgoingGroup()
        controller.setUiMinimized(true)
        setContent()
        rule.onNodeWithTag("qa-voice-overlay").assertDoesNotExist()
    }

    @Test
    fun `ending phase renders nothing`() {
        controller.store.update { it.copy(phase = VoicePhase.Ending) }
        setContent()
        rule.onNodeWithTag("qa-voice-overlay").assertDoesNotExist()
    }

    // ── 点击 → Controller 动作落 store ──

    @Test
    fun `mute speaker minimize clicks hit controller`() {
        startOutgoingGroup()
        setContent()

        rule.onNodeWithTag("qa-voice-mute").performClick()
        rule.waitForIdle()
        assertTrue(state().muteLocal)

        rule.onNodeWithTag("qa-voice-speaker").performClick()
        rule.waitForIdle()
        assertTrue(state().speakerOn)

        rule.onNodeWithTag("qa-voice-minimize").performClick()
        rule.waitForIdle()
        assertTrue(state().uiMinimized)
    }

    @Test
    fun `hangup click ends call`() {
        startOutgoingGroup()
        setContent()
        rule.onNodeWithTag("qa-voice-hangup").performClick()
        testScope.runCurrent()
        rule.waitForIdle()
        // endCall('ui') → 清算后 idleState（异步收尾，Unconfined 调度器立即跑完）
        assertEquals(VoicePhase.Idle, state().phase)
    }

    @Test
    fun `invite click minimizes and routes to picker`() {
        startOutgoingGroup()
        // recordId 已由 startOutgoing 写入（init 返回 rec-1）→ inviteEnabled
        setContent()
        rule.onNodeWithTag("qa-voice-invite").performClick()
        rule.waitForIdle()
        assertTrue(state().uiMinimized)
        assertEquals(listOf("room-1" to "p"), pickedMembers)
    }

    @Test
    fun `banner reject fires controller reject`() {
        controller.store.update {
            it.copy(
                phase = VoicePhase.IncomingRinging,
                callMsg = VoiceCallMsg(status = "calling", roomId = "room-1"),
                recordId = "rec-9",
            )
        }
        setContent()
        rule.onNodeWithTag("qa-voice-incoming-banner-reject").performClick()
        testScope.runCurrent()
        rule.waitForIdle()
        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.calls.any { it.startsWith("reject:rec-9") })
    }

    @Test
    fun `other incoming banner accept and reject wire through`() {
        startOutgoingGroup()
        controller.store.update {
            it.copy(
                otherIncoming = OtherIncoming(
                    callMsg = VoiceCallMsg(status = "calling", roomId = "room-2"),
                    rid = "room-2",
                ),
            )
        }
        setContent()
        rule.onNodeWithTag("qa-voice-other-incoming-banner").assertExists()

        // rejectOtherIncoming → otherIncoming 清空
        rule.onNodeWithTag("qa-voice-other-incoming-banner-reject").performClick()
        testScope.runCurrent()
        rule.waitForIdle()
        assertFalse(state().otherIncoming != null)

        // 重新入队 → accept 弹 type-1 alert（同 org 接听确认）
        controller.store.update {
            it.copy(
                otherIncoming = OtherIncoming(
                    callMsg = VoiceCallMsg(status = "calling", roomId = "room-3"),
                    rid = "room-3",
                ),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithTag("qa-voice-other-incoming-banner-accept").performClick()
        testScope.runCurrent()
        rule.waitForIdle()
        assertEquals(1, state().otherIncomingAlertType)
        rule.onNodeWithTag("qa-voice-other-call-alert").assertExists()

        // confirm → confirmOtherIncomingAlert（endCall + 400ms delay + presentIncoming autoAccept）
        rule.onNodeWithTag("qa-voice-other-call-alert-confirm").performClick()
        testScope.runCurrent()
        rule.waitForIdle()
        assertEquals(null, state().otherIncomingAlertType)
    }

    @Test
    fun `other call alert cancel clears alert`() {
        startOutgoingGroup()
        // type-1 需 otherIncoming 在队（cancel → rejectOtherIncoming 顺带拒接清弹窗）
        controller.store.update {
            it.copy(
                otherIncoming = OtherIncoming(
                    callMsg = VoiceCallMsg(status = "calling", roomId = "room-2"),
                    rid = "room-2",
                ),
                otherIncomingAlertType = 1,
            )
        }
        setContent()
        rule.onNodeWithTag("qa-voice-other-call-alert").assertExists()
        rule.onNodeWithTag("qa-voice-other-call-alert-cancel").performClick()
        testScope.runCurrent()
        rule.waitForIdle()
        assertEquals(null, state().otherIncomingAlertType)
    }

    // ── FakeDeps（VoiceSessionControllerOutgoingTest 夹具复制精简） ──

    private class FakeDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
        private val ok: (String, JsonObject?) -> OncallResponse,
    ) : VoiceSessionDeps {
        val calls = mutableListOf<String>()

        override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse =
            ok("0", kotlinx.serialization.json.buildJsonObject { put("token", "agora-token") })

        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse {
            calls += "informInit"
            return ok("0", kotlinx.serialization.json.buildJsonObject { put("recordId", "rec-1"); put("status", 1) })
        }

        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse =
            ok("0", null)

        override suspend fun join(ctx: OncallContext, body: JoinBody): OncallResponse {
            calls += "join"
            return ok("0", null)
        }

        override suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String?): OncallResponse {
            calls += "reject:$recordId"
            return ok("0", null)
        }

        override suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse =
            ok("0", null)

        override suspend fun addUsers(ctx: OncallContext, body: AddUsersBody): OncallResponse =
            ok("0", kotlinx.serialization.json.buildJsonObject { put("status", 1) })

        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse =
            ok("0", null)

        override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse =
            ok("0", null)
        override fun sessionContext(): VoiceSessionContext = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-1", username = "alice", userDisplayName = "Alice",
        )

        override val meetingPhaseProvider: () -> String? = { null }
        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore()
        override fun roomCallMsgForRid(rid: String): VoiceCallMsg? = null
        override fun clearRoomCallMsg(rid: String) {}
        override fun onTokenExpired() {}
        override suspend fun switchToVoiceOrg(org: String): Boolean = false
        override val ringer: VoiceRinger = object : VoiceRinger {
            override fun start() {}
            override fun stop() {}
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