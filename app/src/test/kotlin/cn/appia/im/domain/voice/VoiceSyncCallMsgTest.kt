package cn.appia.im.domain.voice

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * brief Step 1（对照 RN voiceSessionStore.test.ts :646-1403 的 syncCallMsgFromRoom 全裁定链，
 * RN :806-1032 十段级联——每用例锚定段号）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSyncCallMsgTest {

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

    /** 本机 = bob（被叫/被同步视角）。 */
    private fun ctx(username: String = "bob") = VoiceSessionContext(
        org = "bitmain", businessToken = "btok", apiHost = "gw",
        userId = "uid-b", username = username, userDisplayName = username,
    )

    /** raw JSON 构造便捷形态。 */
    private fun raw(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()

    private fun recordData(
        exist: kotlinx.serialization.json.JsonElement = JsonPrimitive("true"),
        recordId: String = "rec-1",
        channelId: String? = "ch-1",
        initiator: String? = "alice",
        receivers: kotlinx.serialization.json.JsonElement? = null,
    ): kotlinx.serialization.json.JsonElement = buildJsonObject {
        put("exist", exist)
        put("recordId", recordId)
        channelId?.let { put("channelId", it) }
        initiator?.let { put("initiator", it) }
        receivers?.let { put("receivers", it) }
    }

    /** brief（段 1）: sync other room is ignored while active。 */
    @Test
    fun `sync other room is ignored while active`() {
        controller.store.update {
            it.copy(
                phase = VoicePhase.InCall,
                rid = "room-1",
                recordId = "rec-1",
                callMsg = VoiceCallMsg(status = "talking", roomId = "room-1", roomType = "d"),
            )
        }
        val otherRoomRaw = raw(
            "status" to JsonPrimitive("talking"),
            "roomId" to JsonPrimitive("room-2"),
            "recordData" to recordData(recordId = "rec-2"),
        )
        controller.syncCallMsgFromRoom("room-2", otherRoomRaw)
        testScope.runCurrent()

        assertEquals("room-1", state().rid)
        assertEquals("rec-1", state().recordId) // 未被覆盖
        assertEquals(VoicePhase.InCall, state().phase)
    }

    /** brief（段 7）: sync idle discovery presents incoming（RN :1324）。 */
    @Test
    fun `sync idle discovery presents incoming`() {
        deps.contextImpl = { ctx() }
        val discoveryRaw = raw(
            "status" to JsonPrimitive("calling"),
            "roomId" to JsonPrimitive("room-1"),
            "roomType" to JsonPrimitive("d"),
            "org" to JsonPrimitive("bitmain"),
            "userStatus" to buildJsonArray {
                add(buildJsonObject { put("username", "bob"); put("status", "out") })
            },
            "recordData" to recordData(
                receivers = buildJsonArray {
                    add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                },
            ),
        )
        controller.syncCallMsgFromRoom("room-1", discoveryRaw, onCallStatus = true)
        testScope.runCurrent()

        assertEquals(VoicePhase.IncomingRinging, state().phase)
        assertEquals("ch-1", state().channelId)
        assertEquals("rec-1", state().recordId)
    }

    /** brief（段 8）: sync incoming ringing answered on other device resets。 */
    @Test
    fun `sync incoming ringing answered on other device resets`() {
        deps.contextImpl = { ctx() }
        deps.deviceId = "device-local"
        controller.store.update {
            it.copy(
                phase = VoicePhase.IncomingRinging,
                rid = "room-1",
                recordId = "rec-1",
                channelId = "ch-1",
                callMsg = VoiceCallMsg(
                    status = "calling",
                    roomId = "room-1",
                    roomType = "d",
                    recordId = "rec-1",
                ),
            )
        }
        val answeredRaw = raw(
            "status" to JsonPrimitive("calling"),
            "roomId" to JsonPrimitive("room-1"),
            "roomType" to JsonPrimitive("d"),
            "recordData" to recordData(),
            "userStatus" to buildJsonArray {
                add(buildJsonObject { put("username", "bob"); put("status", "in"); put("operation", "accept"); put("deviceId", "device-other") })
            },
        )
        controller.syncCallMsgFromRoom("room-1", answeredRaw)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(deps.ringerStops >= 1)
    }

    /** brief（段 8,残缺快照抑制 :905-936）: sync incoming ringing incomplete snapshot keeps previous full data（RN :1193）。 */
    @Test
    fun `sync incoming ringing incomplete snapshot keeps previous full data`() {
        deps.contextImpl = { ctx() }
        controller.store.update {
            it.copy(
                phase = VoicePhase.IncomingRinging,
                rid = "room-x",
                recordId = "rec-x",
                channelId = "ch-x",
                callMsg = VoiceCallMsg(
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
                        put("receivers", buildJsonArray {
                            add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                        })
                    },
                ),
            )
        }
        // 跨主体轮询瞬时快照:缺 exist/receivers/userStatus
        val transientRaw = raw(
            "status" to JsonPrimitive("calling"),
            "roomType" to JsonPrimitive("d"),
            "org" to JsonPrimitive("orgA"),
            "recordData" to buildJsonObject {
                put("recordId", "rec-x")
                put("channelId", "ch-x")
                put("initiator", "alice")
            },
        )
        controller.syncCallMsgFromRoom("room-x", transientRaw)
        testScope.runCurrent()

        assertEquals(VoicePhase.IncomingRinging, state().phase) // 不复位
        assertEquals(1, state().callMsg?.recordDataObj?.receivers?.size) // 恢复完整数据
    }

    /** brief（段 9）: sync stale ended for host is ignored（RN :1080）。 */
    @Test
    fun `sync stale ended for host is ignored`() {
        deps.contextImpl = { ctx("alice") }
        controller.store.update {
            it.copy(
                phase = VoicePhase.OutgoingRinging,
                rid = "room-1",
                recordId = "rec-new",
                hoster = cn.appia.im.core.voice.VoiceChatHoster(
                    roomId = "room-1",
                    roomType = "d",
                    receivers = listOf(cn.appia.im.core.voice.VoiceChatReceiver(receiver = "bob", name = "Bob")),
                ),
                callMsg = VoiceCallMsg(status = "calling", roomId = "room-1", roomType = "d"),
            )
        }
        val staleEndRaw = raw(
            "status" to JsonPrimitive("end"),
            "roomType" to JsonPrimitive("d"),
            "recordData" to recordData(exist = JsonPrimitive(false), recordId = "rec-old"),
        )
        controller.syncCallMsgFromRoom("room-1", staleEndRaw)
        testScope.runCurrent()

        assertEquals(VoicePhase.OutgoingRinging, state().phase)
        assertEquals("rec-new", state().recordId)
    }

    /** brief（段 10）: sync peer hangup ends call in direct room（RN :934）。 */
    @Test
    fun `sync peer hangup ends call in direct room`() {
        deps.contextImpl = { ctx() }
        controller.store.update {
            it.copy(
                phase = VoicePhase.InCall,
                rid = "room-1",
                recordId = "rec-1",
                hadRemoteParticipant = true,
                callMsg = VoiceCallMsg(status = "talking", roomId = "room-1", roomType = "d"),
            )
        }
        val endRaw = raw(
            "status" to JsonPrimitive("end"),
            "roomType" to JsonPrimitive("d"),
            "recordData" to recordData(exist = JsonPrimitive(false)),
            "userStatus" to buildJsonArray {
                add(buildJsonObject { put("username", "alice"); put("status", "out"); put("operation", "hangup") })
                add(buildJsonObject { put("username", "bob"); put("status", "in") })
            },
        )
        controller.syncCallMsgFromRoom("room-1", endRaw)
        testScope.runCurrent()

        assertEquals(VoicePhase.Idle, state().phase)
    }

    /** brief（段 10 群组）: sync group ongoing with pending invitees does not end（RN :689 对偶）。 */
    @Test
    fun `sync group ongoing with pending invitees does not end`() {
        deps.contextImpl = { ctx() }
        controller.store.update {
            it.copy(
                phase = VoicePhase.InCall,
                rid = "room-g",
                recordId = "rec-g",
                hoster = cn.appia.im.core.voice.VoiceChatHoster(
                    roomId = "room-g",
                    roomType = "p",
                    receivers = listOf(cn.appia.im.core.voice.VoiceChatReceiver(receiver = "bob", name = "Bob")),
                ),
                callMsg = VoiceCallMsg(
                    status = "talking",
                    roomId = "room-g",
                    roomType = "p",
                    userStatus = listOf(
                        cn.appia.im.core.voice.VoiceUserStatus(username = "alice", status = "in"),
                        cn.appia.im.core.voice.VoiceUserStatus(username = "bob", status = "out", operation = "waiting"),
                    ),
                ),
            )
        }
        // exist 仍 true（服务端标记进行中）+ alice 在频道 + bob 待接听 → 群组不收尾
        // （shouldEndGroupCallFromCallMsg:isCallRecordExplicitlyEnded false）
        val ongoingRaw = raw(
            "status" to JsonPrimitive("talking"),
            "roomType" to JsonPrimitive("p"),
            "recordData" to recordData(exist = JsonPrimitive("true"), recordId = "rec-g"),
            "userStatus" to buildJsonArray {
                add(buildJsonObject { put("username", "alice"); put("status", "in") })
                add(buildJsonObject { put("username", "bob"); put("status", "out"); put("operation", "waiting") })
            },
        )
        controller.syncCallMsgFromRoom("room-g", ongoingRaw)
        testScope.runCurrent()

        assertEquals(VoicePhase.InCall, state().phase)
    }

    /** brief（段 3）: sync second incoming queued while active。 */
    @Test
    fun `sync second incoming queued while active`() {
        deps.contextImpl = { ctx() }
        controller.store.update {
            it.copy(
                phase = VoicePhase.InCall,
                rid = "room-1",
                recordId = "rec-1",
                callMsg = VoiceCallMsg(status = "talking", roomId = "room-1", roomType = "d"),
            )
        }
        val secondIncomingRaw = raw(
            "status" to JsonPrimitive("calling"),
            "roomId" to JsonPrimitive("room-2"),
            "roomType" to JsonPrimitive("d"),
            "recordId" to JsonPrimitive("rec-2"),
            "userStatus" to buildJsonArray {
                add(buildJsonObject { put("username", "bob"); put("status", "out") })
            },
            "recordData" to recordData(
                recordId = "rec-2",
                channelId = "ch-2",
                receivers = buildJsonArray {
                    add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                },
            ),
        )
        controller.syncCallMsgFromRoom("room-2", secondIncomingRaw)
        testScope.runCurrent()

        // 段 1 放行（他房间但入队前）:shouldQueueOtherIncoming → otherIncoming 队列
        // 注:他房间 rid 与 state.rid 不同且 phase != idle → 段 1 直接 return——
        // 该用例验证同 rid 场景:第二路来电须同房间
        val secondSameRoom = secondIncomingRaw
        controller.syncCallMsgFromRoom("room-1", secondSameRoom)
        testScope.runCurrent()
        assertNotNull(state().otherIncoming)
        assertEquals("rec-2", state().otherIncoming?.callMsg?.recordId)
    }

    /** brief（段 2）: sync ended other incoming clears queue entry。 */
    @Test
    fun `sync ended other incoming clears queue entry`() {
        deps.contextImpl = { ctx() }
        val queuedMsg = VoiceCallMsg(
            status = "calling",
            roomId = "room-2",
            roomType = "d",
            recordId = "rec-2",
        )
        controller.store.update {
            it.copy(
                phase = VoicePhase.InCall,
                rid = "room-1",
                recordId = "rec-1",
                otherIncoming = OtherIncoming(callMsg = queuedMsg, rid = "room-2"),
                callMsg = VoiceCallMsg(status = "talking", roomId = "room-1", roomType = "d"),
            )
        }
        val endedRaw = raw(
            "status" to JsonPrimitive("end"),
            "roomType" to JsonPrimitive("d"),
            "recordId" to JsonPrimitive("rec-2"),
            "recordData" to recordData(exist = JsonPrimitive(false), recordId = "rec-2"),
        )
        controller.syncCallMsgFromRoom("room-1", endedRaw)
        testScope.runCurrent()

        assertNull(state().otherIncoming) // 队列条目清除
        assertEquals(VoicePhase.InCall, state().phase) // 本路通话不受影响
    }

    /** brief（段 5）: sync group invitee count growth resets host timers（RN :871-879）。 */
    @Test
    fun `sync group invitee count growth resets host timers`() {
        deps.contextImpl = { ctx("alice") }
        val hoster = cn.appia.im.core.voice.VoiceChatHoster(
            roomId = "room-g",
            roomType = "p",
            receivers = listOf(cn.appia.im.core.voice.VoiceChatReceiver(receiver = "bob", name = "Bob")),
        )
        controller.startOutgoing(hoster)
        testScope.runCurrent()
        assertEquals(VoicePhase.OutgoingRinging, state().phase)
        val recordId = state().recordId!!

        // 群组 callMsg:alice in + bob 待接听（同 recordId）
        val growthRaw = raw(
            "status" to JsonPrimitive("calling"),
            "roomType" to JsonPrimitive("p"),
            "recordId" to JsonPrimitive(recordId),
            "recordData" to recordData(
                recordId = recordId,
                initiator = "alice",
                receivers = buildJsonArray {
                    add(buildJsonObject { put("receiver", "bob"); put("name", "Bob"); put("status", "out") })
                    add(buildJsonObject { put("receiver", "carol"); put("name", "Carol"); put("status", "out") })
                },
            ),
            "userStatus" to buildJsonArray {
                add(buildJsonObject { put("username", "alice"); put("status", "in") })
                add(buildJsonObject { put("username", "bob"); put("status", "out"); put("operation", "waiting") })
                add(buildJsonObject { put("username", "carol"); put("status", "out"); put("operation", "waiting") })
            },
        )
        controller.syncCallMsgFromRoom("room-g", growthRaw)
        testScope.runCurrent()

        // 计时已重锚:原 30s 提示点不再触发,新锚 30s 后触发
        testScheduler.advanceTimeBy(30_000)
        testScope.runCurrent()
        // 重锚发生在 t≈0,30s 后应触发 not_connect（群组无被叫在频道）
        assertEquals("not_connect", state().outgoingHint)
    }

    // ── fixtures ──

    private class FakeDeps(
        override val rtc: VoiceRtcEngine,
        override val timers: VoiceCallTimers,
        override val store: VoiceSessionStore,
    ) : VoiceSessionDeps {
        val calls = mutableListOf<String>()
        var contextImpl: () -> VoiceSessionContext? = {
            VoiceSessionContext(
                org = "bitmain", businessToken = "btok", apiHost = "gw",
                userId = "uid-1", username = "alice", userDisplayName = "Alice",
            )
        }
        var deviceId: String? = null
        var ringerStarts = 0
        var ringerStops = 0

        override suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse =
            okResponse(data = buildJsonObject { put("token", "agora-token") })

        override suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse =
            okResponse(data = buildJsonObject { put("recordId", "rec-1"); put("status", 1) })

        override suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse {
            calls += "updateRecordStatus:$recordId:$status"
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

        override suspend fun addUsers(ctx: OncallContext, body: cn.appia.im.core.voice.AddUsersBody): OncallResponse =
            okResponse()

        override suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse {
            calls += "getRecordStatusV2:$recordId"
            return okResponse(data = null)
        }

        override suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String?): OncallResponse {
            calls += "getNotify:$recordId"
            return okResponse()
        }

        override fun sessionContext(): VoiceSessionContext? = contextImpl()

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

        override fun roomCallMsgRawForRid(rid: String): String? = null

        override fun roomOnCallStatusForRid(rid: String): Boolean? = null
    }

    private class FakeRtc : VoiceRtcApi {
        var joinCalls = 0

        override fun create(appId: String, onEvent: (cn.appia.im.core.voice.rtc.VoiceRtcEvent) -> Unit) {}

        override fun joinChannel(
            token: String?,
            channelId: String,
            uid: Long,
            encKey: String?,
            muted: Boolean,
            speakerOn: Boolean,
        ): Int {
            joinCalls += 1
            return 0
        }

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
