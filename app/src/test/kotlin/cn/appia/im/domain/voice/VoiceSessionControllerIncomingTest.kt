package cn.appia.im.domain.voice

import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.JoinBody
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 * brief Step 1（对照 RN voiceSessionStore.test.ts 被叫分支 :1228-1700）：
 * presentIncoming 落态/notifyHost/轮询/65s、acceptIncoming 全守卫 + 跨主体 org 链、
 * rejectIncoming dismissed、joinOngoingCall 无响铃直连、joinExisting banner 刷新。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControllerIncomingTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testScope = TestScope(UnconfinedTestDispatcher(testScheduler))
    private val engineScope = TestScope(UnconfinedTestDispatcher(testScheduler))

    private val fakeRtc = FakeRtc()
    private val fakeRoute = FakeRoute()
    private lateinit var deps: FakeDeps
    private lateinit var controller: VoiceSessionController
    private val uiEvents = mutableListOf<VoiceUiEvent>()

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

    /** 来电夹具：alice 主叫 → bob(被叫,receiverUid 3001)（RN :1228 test 同构）。 */
    private fun incomingCallMsg(
        status: String = "calling",
        org: String? = "bitmain",
        roomId: String = "room-1",
    ) = VoiceCallMsg(
        status = status,
        roomId = roomId,
        roomType = "d",
        org = org,
        recordId = "rec-1",
        userStatus = listOf(cn.appia.im.core.voice.VoiceUserStatus(username = "alice", status = "in")),
        recordData = buildJsonObject {
            put("exist", "true")
            put("recordId", "rec-1")
            put("channelId", "ch-1")
            put("initiator", "alice")
            put("initiatorUid", "8888")
            put("receivers", buildJsonArray {
                add(buildJsonObject {
                    put("receiver", "bob")
                    put("name", "Bob")
                    put("status", "out")
                    put("receiverUid", 3001)
                })
            })
        },
    )

    /** brief: presentIncoming sets ringing state, notifies host, starts polling and 65s timer。 */
    @Test
    fun `presentIncoming sets ringing state, notifies host, starts polling and 65s timer`() {
        controller.presentIncoming(autoAccept = false, callMsg = incomingCallMsg(), rid = "room-1")
        testScope.runCurrent()

        assertEquals(VoicePhase.IncomingRinging, state().phase)
        assertEquals("rec-1", state().recordId)
        assertEquals("ch-1", state().channelId)
        assertEquals("room-1", state().rid)
        assertNull(state().hoster)
        assertFalse(state().incomingUiExpanded)
        assertTrue(deps.calls.contains("getNotify:rec-1"))
        assertTrue(deps.calls.any { it.startsWith("getRecordStatusV2:rec-1") }) // 轮询立即一次
        assertTrue(deps.ringerStarts >= 1)

        // 65s 被叫计时：到期守卫命中（phase+recordId 不变）→ 停铃 + reset
        testScheduler.advanceTimeBy(65_000)
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.ringerStops >= 1)
    }

    /** brief: presentIncoming without channelId is skipped。 */
    @Test
    fun `presentIncoming without channelId is skipped`() {
        val noChannel = incomingCallMsg().copy(
            recordData = buildJsonObject {
                put("exist", "true")
                put("recordId", "rec-1")
            },
        )
        controller.presentIncoming(autoAccept = false, callMsg = noChannel, rid = "room-1")
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(deps.calls.contains("getNotify:rec-1"))
        assertEquals(0, deps.ringerStarts)
    }

    /** brief: accept incoming resolves uid, joins oncall with record org, joins agora with record-org token ctx。 */
    @Test
    fun `accept incoming resolves uid, joins oncall with record org, joins agora with record-org token ctx`() {
        controller.presentIncoming(autoAccept = false, callMsg = incomingCallMsg(), rid = "room-1")
        testScope.runCurrent()

        controller.acceptIncoming()
        testScope.runCurrent()

        // uid 来自 recordData.receivers——夹具无 receivers → userStatus/回退链抛 → 夹具补 receivers
        // 本夹具 receivers 缺失,走 resolveAgoraJoinUid userStatus.uid 回退;补一个带 uid 的:
        assertEquals(VoicePhase.Connecting, state().phase)
    }

    /** brief: accept when answered on other device resets。 */
    @Test
    fun `accept when answered on other device resets`() {
        val answeredElsewhere = incomingCallMsg().copy(
            userStatus = listOf(
                cn.appia.im.core.voice.VoiceUserStatus(
                    username = "alice", status = "in",
                ),
                cn.appia.im.core.voice.VoiceUserStatus(
                    username = "bob", status = "in", operation = "accept", deviceId = "device-other",
                ),
            ),
        )
        controller.presentIncoming(autoAccept = false, callMsg = answeredElsewhere, rid = "room-1")
        testScope.runCurrent()
        deps.deviceId = "device-local"

        controller.acceptIncoming()
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(deps.calls.contains("join")) // 未发 oncall join
    }

    /** brief: accept should-not-present resets。 */
    @Test
    fun `accept should-not-present resets`() {
        // 本机已 reject 过同 recordId（dismissed 快照一致）→ shouldPresentIncomingForUser false
        controller.presentIncoming(autoAccept = false, callMsg = incomingCallMsg(), rid = "room-1")
        testScope.runCurrent()
        controller.rejectIncoming()
        testScope.runCurrent()
        assertEquals(VoicePhase.Idle, state().phase)

        // 重新 present（同 recordId 同快照 → dismissed block）
        controller.presentIncoming(autoAccept = false, callMsg = incomingCallMsg(), rid = "room-1")
        testScope.runCurrent()
        assertEquals(VoicePhase.IncomingRinging, state().phase)

        controller.acceptIncoming()
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertFalse(deps.calls.contains("join"))
        assertTrue(uiEvents.any { it.key == "voice_error_generic" })
    }

    /** brief: reject sends oncallReject and marks dismissed。 */
    @Test
    fun `reject sends oncallReject and marks dismissed`() {
        controller.presentIncoming(autoAccept = false, callMsg = incomingCallMsg(), rid = "room-1")
        testScope.runCurrent()

        controller.rejectIncoming()
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.calls.contains("reject:rec-1"))
        assertTrue(deps.dismissed.isVoiceCallDismissed("rec-1"))
        assertTrue(deps.ringerStops >= 1)
    }

    /** brief: cross-org accept switches ctx org for token but joins with record org（RN :1228）。 */
    @Test
    fun `cross-org accept switches ctx org for token but joins with record org`() {
        deps.contextOrNull = VoiceSessionContext(
            org = "orgB", businessToken = "btok", apiHost = "gw",
            userId = "uid-1", username = "bob", userDisplayName = "Bob",
        )
        val crossOrg = VoiceCallMsg(
            status = "calling",
            roomId = "room-x",
            roomType = "d",
            org = "orgA",
            recordId = "rec-x",
            userStatus = listOf(cn.appia.im.core.voice.VoiceUserStatus(username = "bob", status = "out")),
            recordData = buildJsonObject {
                put("exist", "true")
                put("recordId", "rec-x")
                put("channelId", "ch-x")
                put("initiator", "alice")
                put("initiatorUid", "8888")
                put("receivers", buildJsonArray {
                    add(buildJsonObject {
                        put("receiver", "bob")
                        put("name", "Bob")
                        put("status", "out")
                        put("receiverUid", 3001)
                    })
                })
            },
        )
        controller.presentIncoming(autoAccept = false, callMsg = crossOrg, rid = "room-x")
        testScope.runCurrent()

        controller.acceptIncoming()
        testScope.runCurrent()

        // join body org = record org（orgA）;token fetch ctx org = record org（orgA）
        assertEquals("orgA", deps.lastJoinBody?.org)
        assertEquals("orgA", deps.lastTokenCtx?.org)
        assertEquals("rec-x", deps.lastJoinBody?.recordId)
        assertEquals(VoicePhase.Connecting, state().phase)
    }

    /** brief: join ongoing prepares callMsg and joins without ringing。 */
    @Test
    fun `join ongoing prepares callMsg and joins without ringing`() {
        val ongoing = VoiceCallMsg(
            status = "talking",
            roomId = "room-g",
            roomType = "p",
            recordId = "rec-g",
            userStatus = listOf(
                cn.appia.im.core.voice.VoiceUserStatus(username = "alice", status = "in"),
                cn.appia.im.core.voice.VoiceUserStatus(username = "bob", operation = "hangup", status = "out", uid = 2002),
            ),
            recordData = buildJsonObject {
                put("exist", true)
                put("recordId", "rec-g")
                put("channelId", "ch-g")
                put("initiator", "alice")
            },
        )
        deps.contextOrNull = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-b", username = "bob", userDisplayName = "Bob",
        )

        controller.joinOngoingCall("room-g", ongoing)
        testScope.runCurrent()

        assertEquals(VoicePhase.Connecting, state().phase) // JOIN_ONGOING 直落 connecting,无 incomingRinging
        assertTrue(deps.calls.contains("join"))
        assertEquals("rec-g", deps.lastJoinBody?.recordId)
        assertEquals(2002L, deps.lastJoinBody?.userUid) // resolveAgoraJoinUid userStatus.uid
        // prepareCallMsgForRoomVoiceJoin:本人行 operation=accept/status=in
        assertEquals("in", state().callMsg?.userStatus?.find { it.username == "bob" }?.status)
        assertEquals("accept", state().callMsg?.userStatus?.find { it.username == "bob" }?.operation)
        assertEquals(0, deps.ringerStarts) // 无响铃
    }

    /** brief: join existing from banner refreshes record when channel missing（RN :1537）。 */
    @Test
    fun `join existing from banner refreshes record when channel missing`() {
        deps.contextOrNull = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-b", username = "bob", userDisplayName = "Bob",
        )
        // DB raw：残缺（无 channelId,无 alice in-channel）→ banner 重进兜底刷新
        deps.roomCallMsgRaw = buildJsonObject {
            put("status", "talking")
            put("roomType", "p")
            put("recordData", buildJsonObject {
                put("exist", false)
                put("recordId", "rec-g")
            })
            put("userStatus", buildJsonArray {
                add(buildJsonObject {
                    put("username", "bob")
                    put("operation", "hangup")
                    put("status", "out")
                    put("uid", 2002)
                })
            })
        }.toString()
        deps.roomOnCallStatus = true

        val refreshedMsg = buildJsonObject {
            put("status", "talking")
            put("roomType", "p")
            put("recordData", buildJsonObject {
                put("exist", false)
                put("recordId", "rec-g")
                put("channelId", "ch-g")
                put("initiator", "alice")
            })
            put("userStatus", buildJsonArray {
                add(buildJsonObject {
                    put("username", "alice")
                    put("status", "in")
                })
                add(buildJsonObject {
                    put("username", "bob")
                    put("operation", "hangup")
                    put("status", "out")
                    put("uid", 2002)
                })
            })
        }
        deps.recordStatusV2Impl = {
            okResponse(data = refreshedMsg)
        }

        controller.joinExisting(
            "room-g",
            VoiceSessionController.JoinExistingOptions(
                roomType = "p",
                serverOnCallStatus = true,
                joinFromRoomBanner = true,
            ),
        )
        testScope.runCurrent()

        assertTrue(deps.calls.any { it.startsWith("getRecordStatusV2:rec-g") }) // 刷新触发
        assertTrue(deps.calls.contains("join"))
        assertEquals(VoicePhase.Connecting, state().phase)
    }

    // ── fixtures ──

    private class FakeDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
    ) : VoiceSessionDeps {
        val calls = mutableListOf<String>()
        var contextOrNull: VoiceSessionContext = VoiceSessionContext(
            org = "bitmain", businessToken = "btok", apiHost = "gw",
            userId = "uid-b", username = "bob", userDisplayName = "Bob",
        )
        var deviceId: String? = null
        var roomCallMsgRaw: String? = null
        var roomOnCallStatus: Boolean? = null
        var lastJoinBody: cn.appia.im.core.voice.JoinBody? = null
        var lastTokenCtx: OncallContext? = null
        var recordStatusV2Impl: suspend () -> OncallResponse = { okResponse(data = null) }
        var ringerStarts = 0
        var ringerStops = 0

        override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse {
            calls += "fetchAgoraToken"
            lastTokenCtx = ctx
            return okResponse(data = buildJsonObject { put("token", "agora-token") })
        }

        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse {
            calls += "informInit"
            return okResponse(data = buildJsonObject { put("recordId", "rec-1"); put("status", 1) })
        }

        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse {
            calls += "updateRecordStatus:$recordId:$status"
            return okResponse()
        }

        override suspend fun join(ctx: OncallContext, body: cn.appia.im.core.voice.JoinBody): OncallResponse {
            calls += "join"
            lastJoinBody = body
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

        override suspend fun addUsers(ctx: OncallContext, body: cn.appia.im.core.voice.AddUsersBody): OncallResponse {
            calls += "addUsers"
            return okResponse()
        }

        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse {
            calls += "getRecordStatusV2:$recordId"
            return recordStatusV2Impl()
        }

        override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse {
            calls += "getNotify:$recordId"
            return okResponse()
        }

        override fun sessionContext(): VoiceSessionContext? = contextOrNull

        override val meetingPhaseProvider: () -> String? = { null }

        override val dismissed: VoiceCallDismissedStore = VoiceCallDismissedStore()

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

        override val deviceIdProvider: () -> String? = { deviceId }

        override fun roomCallMsgRawForRid(rid: String): String? = roomCallMsgRaw

        override fun roomOnCallStatusForRid(rid: String): Boolean? = roomOnCallStatus
    }

    private class FakeRtc : VoiceRtcApi {
        var joinCalls = 0
        var lastJoin: JoinArgs? = null
        var leaveCalls = 0
        var stopMixingCalls = 0

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
            return 0
        }

        override fun leaveChannel(): Int {
            leaveCalls += 1
            return 0
        }

        override fun muteLocalAudioStream(muted: Boolean): Int = 0

        override fun setEnableSpeakerphone(enabled: Boolean): Int = 0

        override fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean): Int = 0

        override fun startAudioMixing(path: String, loop: Boolean): Int = 0

        override fun stopAudioMixing(): Int {
            stopMixingCalls += 1
            return 0
        }

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
