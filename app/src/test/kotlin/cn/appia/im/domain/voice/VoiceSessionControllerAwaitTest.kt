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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * startFromMessage / joinExisting 的 await 语义（审查 Fix round 1 Finding 1）:
 * RN :1743-1748 await joinExisting 后才检查 phase——AA 不得在 lazy dispatcher 下
 * 早读 phase 导致 autoAccept 退化为 presentIncoming(autoAccept=false)。
 * 本类 controller 挂 StandardTestDispatcher（lazy）,模拟生产 dispatcher 的排队时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControllerAwaitTest {

    private val testScheduler = TestCoroutineScheduler()
    // lazy:scope.launch 不立即执行,须 runCurrent/advanceUntilIdle 推进
    private val testScope = TestScope(StandardTestDispatcher(testScheduler))
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

    /**
     * Finding 1 回归:消息点击（calling）在 lazy dispatcher 下自动接听。
     * DB raw 残缺（无 channelId）→ joinExisting 分支 5 刷新 → presentIncoming(autoAccept=true)
     * → acceptIncoming 全链 → connecting + join(record org)。全程无手动 accept。
     */
    @Test
    fun `startFromMessage calling tap auto-accepts on lazy dispatcher`() {
        // DB raw:calling 无 channelId → joinExistingInternal 走分支 5 刷新
        deps.roomCallMsgRaw = buildJsonObject {
            put("status", "calling")
            put("roomType", "d")
            put("recordId", "rec-1")
            put("userStatus", buildJsonArray {
                add(buildJsonObject {
                    put("username", "alice")
                    put("status", "in")
                })
            })
            put("recordData", buildJsonObject {
                put("exist", true)
                put("recordId", "rec-1")
            })
        }.toString()
        // 刷新返回:完整 callMsg（含 channelId + bob 的 receivers 行,uid 解析可用）
        deps.recordStatusV2Impl = {
            okResponse(
                data = buildJsonObject {
                    put("status", "calling")
                    put("roomType", "d")
                    put("org", "orgA")
                    put("recordId", "rec-1")
                    put("userStatus", buildJsonArray {
                        add(buildJsonObject {
                            put("username", "alice")
                            put("status", "in")
                        })
                        add(buildJsonObject {
                            put("username", "bob")
                            put("status", "out")
                            put("uid", 2002)
                        })
                    })
                    put("recordData", buildJsonObject {
                        put("exist", true)
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
                    })
                },
            )
        }

        controller.startFromMessage("room-1", VoiceCallMsg(status = "calling", roomId = "room-1"))
        // lazy dispatcher:此刻 phase 仍 idle（launch 尚未执行）——正是旧实现早读 phase 的时序
        assertEquals(VoicePhase.Idle, state().phase)

        // 全链 fake 均即时返回 → runCurrent 跑完 await 链（轮询 delay 属未来任务不阻塞）
        testScope.runCurrent()

        // await joinExisting 后 phase 非 idle → 不落入 calling 兜底 presentIncoming;
        // autoAccept=true → acceptIncoming 全链完成 → Connecting（无手动 accept）
        assertEquals(VoicePhase.Connecting, state().phase)
        assertTrue(deps.calls.contains("join"))
        assertEquals("rec-1", deps.lastJoinBody?.recordId)
        // join body org = record org（orgA,callMsg.org ?: ctx.org）
        assertEquals("orgA", deps.lastJoinBody?.org)
    }

    /**
     * 同 bug 的 talking 面:RN :1743 await joinExisting → joinOngoingCall 落态 connecting。
     * 旧实现 lazy 下早读 idle → 误入 receivers 回退 startOutgoing（发起新呼叫）。
     */
    @Test
    fun `startFromMessage talking tap joins ongoing on lazy dispatcher`() {
        // DB raw:talking、群聊、alice 在频道 → 分支 7 shouldShowRoomVoiceJoinBanner true → 9a
        deps.roomCallMsgRaw = buildJsonObject {
            put("status", "talking")
            put("roomType", "p")
            put("recordId", "rec-g")
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
            put("recordData", buildJsonObject {
                put("exist", true)
                put("recordId", "rec-g")
                put("channelId", "ch-g")
                put("initiator", "alice")
            })
        }.toString()
        deps.roomOnCallStatus = true

        controller.startFromMessage("room-g", VoiceCallMsg(status = "talking", roomId = "room-g"))
        assertEquals(VoicePhase.Idle, state().phase) // lazy:launch 尚未执行

        testScope.runCurrent()

        // joinOngoing 落态 connecting;绝不 startOutgoing（无 informInit）
        assertEquals(VoicePhase.Connecting, state().phase)
        assertTrue(deps.calls.contains("join"))
        assertFalse(deps.calls.contains("informInit"))
        assertEquals("rec-g", deps.lastJoinBody?.recordId)
        assertEquals("bitmain", deps.lastJoinBody?.org) // joinOngoing join org = ctx.org
    }

    /**
     * Finding 3 回归:joinExisting 分支 2/3 的 DB 读体抛错被吞（RN :1896-1913/:1920-1929
     * catch-ignore),不向调用方传播;idle 读体抛错 → mergedRaw 无 DB 份 → 不足以呈现 → generic toast。
     */
    @Test
    fun `joinExisting survives throwing DB reads`() {
        deps.throwOnRoomReads = true

        controller.joinExisting("room-1")
        testScope.runCurrent()

        // 读体抛错被 runCatching 吞,无异常传播;parsed null → voice_error_generic
        assertEquals(VoicePhase.Idle, state().phase)
        assertTrue(uiEvents.any { it.key == "voice_error_generic" })
    }

    // ── fixtures（IncomingTest FakeDeps 同构,读体可编程抛错） ──

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
        var throwOnRoomReads = false
        var lastJoinBody: JoinBody? = null
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

        override suspend fun join(ctx: OncallContext, body: JoinBody): OncallResponse {
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

        override fun roomCallMsgRawForRid(rid: String): String? {
            if (throwOnRoomReads) throw IllegalStateException("db read failed")
            return roomCallMsgRaw
        }

        override fun roomOnCallStatusForRid(rid: String): Boolean? {
            if (throwOnRoomReads) throw IllegalStateException("db read failed")
            return roomOnCallStatus
        }
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
