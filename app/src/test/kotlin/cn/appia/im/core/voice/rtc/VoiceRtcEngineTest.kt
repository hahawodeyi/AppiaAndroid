package cn.appia.im.core.voice.rtc

import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VoiceRtcEngine（RN appiaMobile/src/services/voice/agoraRtc.test.ts 全量移植 +
 * brief Step 1 用例）：同 appId 引擎只建一次 / join 失败 false / leave 幂等且停混音 /
 * Joined 后 500ms 音频路由 / 延迟窗口内切换不被覆盖 / 重拨清 pending 一次生效 /
 * leave 取消 pending / release 后回调丢弃 / appId 变更重建 / 回铃混音 + 音量 10。
 * RN 测试名以注释保留；500ms 用 TestScope 虚拟时间（注入 scope + delayMs）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRtcEngineTest {

    private val rtc = FakeRtc()

    private val route = FakeRoute()

    private val testScope = TestScope()

    private lateinit var engine: VoiceRtcEngine

    @Before
    fun setUp() {
        engine = VoiceRtcEngine(rtc, route, testScope, delayMs = 500L)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    /** brief: join creates engine once per appId and forwards channel options。 */
    @Test
    fun `join creates engine once per appId and forwards channel options`() {
        val ok = engine.join(APP_ID, "ch-1", "token", 1001L, "enc-key", muted = true, speakerOn = false)

        assertTrue(ok)
        assertTrue(engine.inChannel)
        assertEquals(1, rtc.createCalls)
        assertEquals(APP_ID, rtc.lastAppId)
        assertEquals(1, rtc.joinCalls)
        assertEquals(FakeRtc.JoinArgs("token", "ch-1", 1001L, "enc-key", true, false), rtc.lastJoin)
        assertEquals(1, route.startCalls)

        // 第二次 join 同 appId：不重建（RtcEngine 全局单例约束）
        engine.join(APP_ID, "ch-2", "token-2", 1002L, null, muted = false, speakerOn = true)
        assertEquals(1, rtc.createCalls)
        assertEquals(2, rtc.joinCalls)
    }

    /** brief: join failure returns false（非 0 → false，音频路由回退）。 */
    @Test
    fun `join failure returns false`() {
        rtc.joinResult = -1

        val ok = engine.join(APP_ID, "ch", "t", 1L, null, muted = false, speakerOn = false)

        assertFalse(ok)
        assertFalse(engine.inChannel)
        assertEquals(1, route.startCalls)
        assertEquals(1, route.stopCalls)
    }

    /** brief: leave is idempotent and stops audio mixing（brief 追加停回铃混音，RN leave 无此步）。 */
    @Test
    fun `leave is idempotent and stops audio mixing`() {
        engine.join(APP_ID, "ch", "t", 1L, null, muted = false, speakerOn = false)
        rtc.emit(VoiceRtcEvent.Joined)
        engine.startHostRingback("/raw/calling_sound.wav")

        engine.leave()

        assertFalse(engine.inChannel)
        assertEquals(1, rtc.leaveCalls)
        assertEquals(1, rtc.stopMixingCalls)
        assertEquals(1, route.stopCalls)

        engine.leave()
        engine.leave()

        assertEquals(1, rtc.leaveCalls)
        assertEquals(1, rtc.stopMixingCalls)
        assertEquals(1, route.stopCalls)
    }

    /** RN: waits for onJoinChannelSuccess to configure in-call audio（延迟 500ms 路由）。 */
    @Test
    fun `audio route applied 500ms after join success`() {
        engine.join(APP_ID, "ch", "token", 1001L, "key", muted = false, speakerOn = true)
        rtc.emit(VoiceRtcEvent.Joined)

        // 音量指示立即生效（RN :64 enableAudioVolumeIndication(500,1,true)）
        assertEquals(Triple(500, 1, true), rtc.lastVolumeIndication)
        // 入频道同步返回时未真正入频道：不允许提前动扬声器路由（RN :117）
        assertTrue(rtc.speakerCalls.isEmpty())
        assertTrue(rtc.muteCalls.isEmpty())
        assertTrue(route.speakerphoneCalls.isEmpty())

        testScope.advanceTimeBy(499)
        testScope.runCurrent()
        assertTrue(rtc.speakerCalls.isEmpty())

        testScope.advanceTimeBy(1)
        testScope.runCurrent()
        assertEquals(listOf(true), rtc.speakerCalls)
        assertEquals(listOf(false), rtc.muteCalls)
        assertEquals(listOf(true), route.speakerphoneCalls)
    }

    /** RN: user toggling mute/speaker within the delay window is not overridden。 */
    @Test
    fun `setMuted during delayed window syncs pending state`() {
        engine.join(APP_ID, "ch", "t", 1L, null, muted = false, speakerOn = false)
        rtc.emit(VoiceRtcEvent.Joined)

        // 用户在 500ms 延迟窗口内主动开免提并静音（立即生效 + 同步 pending）
        engine.setSpeaker(true)
        engine.setMuted(true)

        testScope.advanceTimeBy(500)
        testScope.runCurrent()

        // 延迟配置应用的是用户最新选择，而非 join 时的初值（各 2 次：立即 + 延迟）
        assertEquals(listOf(true, true), rtc.speakerCalls)
        assertEquals(listOf(true, true), rtc.muteCalls)
        assertEquals(listOf(true, true), route.speakerphoneCalls)
    }

    /** RN: rejoin clears pending audio config so it applies exactly once per join。 */
    @Test
    fun `rejoin clears pending audio config so it applies exactly once per join`() {
        engine.join(APP_ID, "a", "t", 1L, null, muted = false, speakerOn = false)
        rtc.emit(VoiceRtcEvent.Joined)
        // 第一次的延迟配置尚未生效就重新拨叫：应被清掉
        engine.join(APP_ID, "b", "t2", 2L, null, muted = false, speakerOn = false)
        rtc.emit(VoiceRtcEvent.Joined)
        testScope.advanceTimeBy(500)
        testScope.runCurrent()

        assertEquals(1, rtc.speakerCalls.size)
        assertEquals(1, rtc.muteCalls.size)
        assertEquals(1, route.speakerphoneCalls.size)
    }

    /** RN: leave cancels pending delayed audio config。 */
    @Test
    fun `leave cancels pending delayed audio config`() {
        engine.join(APP_ID, "a", "t", 1L, null, muted = false, speakerOn = false)
        rtc.emit(VoiceRtcEvent.Joined)
        engine.leave()
        testScope.advanceTimeBy(500)
        testScope.runCurrent()

        assertTrue(rtc.speakerCalls.isEmpty())
        assertTrue(rtc.muteCalls.isEmpty())
        assertTrue(route.speakerphoneCalls.isEmpty())
    }

    /** RN: released engine ignores join success callbacks + brief: release 后回调不上抛 events。 */
    @Test
    fun `events after release are dropped`() {
        val collected = mutableListOf<VoiceRtcEvent>()
        testScope.launch { engine.events.collect { collected += it } }
        testScope.runCurrent()

        engine.join(APP_ID, "ch", "t", 1L, null, muted = false, speakerOn = false)
        rtc.emit(VoiceRtcEvent.Joined)
        testScope.runCurrent()
        assertEquals(listOf<VoiceRtcEvent>(VoiceRtcEvent.Joined), collected)
        assertEquals(1, rtc.volumeIndicationCalls)

        engine.release()

        rtc.emit(VoiceRtcEvent.Joined)
        rtc.emit(VoiceRtcEvent.UserJoined(7L))
        rtc.emit(VoiceRtcEvent.UserOffline(7L))
        rtc.emit(VoiceRtcEvent.Error(3))

        // released 守卫：回调不上抛 events、不重开音量指示、不应用路由
        assertEquals(listOf<VoiceRtcEvent>(VoiceRtcEvent.Joined), collected)
        assertEquals(1, rtc.volumeIndicationCalls)
        assertTrue(rtc.speakerCalls.isEmpty())
        assertTrue(rtc.muteCalls.isEmpty())
        assertTrue(route.speakerphoneCalls.isEmpty())
        assertEquals(1, route.stopCalls)
    }

    /** brief: appId change destroys and recreates。 */
    @Test
    fun `appId change destroys and recreates`() {
        engine.join("app-a", "ch", "t", 1L, null, muted = false, speakerOn = false)
        assertEquals(1, rtc.createCalls)
        assertEquals("app-a", rtc.lastAppId)

        engine.join("app-b", "ch", "t", 1L, null, muted = false, speakerOn = false)

        assertEquals(2, rtc.createCalls)
        assertEquals("app-b", rtc.lastAppId)
        assertEquals(1, rtc.destroyCalls)
    }

    /** brief: host ringback starts mixing and sets volume（RN :230-231 loop + 音量 10）。 */
    @Test
    fun `host ringback starts mixing and sets volume`() {
        engine.startHostRingback("/raw/calling_sound.wav")

        assertEquals(listOf("/raw/calling_sound.wav" to true), rtc.startMixingCalls)
        assertEquals(listOf(10), rtc.adjustVolumeCalls)

        engine.stopHostRingback()
        assertEquals(1, rtc.stopMixingCalls)

        engine.release()
        engine.startHostRingback("/raw/calling_sound.wav")
        assertEquals(1, rtc.startMixingCalls.size)
    }

    private companion object {
        const val APP_ID = "test-app-id"
    }

    /** 记录全部命令调用；emit 模拟 native 回调线程触发 onEvent。 */
    private class FakeRtc : VoiceRtcApi {
        var createCalls = 0
        var destroyCalls = 0
        var lastAppId: String? = null
        var joinCalls = 0
        var joinResult = 0
        var lastJoin: JoinArgs? = null
        var leaveCalls = 0
        var volumeIndicationCalls = 0
        var lastVolumeIndication: Triple<Int, Int, Boolean>? = null
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

        fun emit(event: VoiceRtcEvent) = onEvent?.invoke(event)

        override fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit) {
            createCalls++
            lastAppId = appId
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
            joinCalls++
            lastJoin = JoinArgs(token, channelId, uid, encKey, muted, speakerOn)
            return joinResult
        }

        override fun leaveChannel(): Int {
            leaveCalls++
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
            volumeIndicationCalls++
            lastVolumeIndication = Triple(interval, smooth, reportVad)
            return 0
        }

        override fun startAudioMixing(path: String, loop: Boolean): Int {
            startMixingCalls += path to loop
            return 0
        }

        override fun stopAudioMixing(): Int {
            stopMixingCalls++
            return 0
        }

        override fun adjustAudioMixingVolume(volume: Int): Int {
            adjustVolumeCalls += volume
            return 0
        }

        override fun destroy() {
            destroyCalls++
        }
    }

    /** 记录 InCallAudioRoute 调用（生产 AudioManager 实现归 Task 12）。 */
    private class FakeRoute : InCallAudioRoute {
        var startCalls = 0
        var stopCalls = 0
        val speakerphoneCalls = mutableListOf<Boolean>()

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun setSpeakerphoneOn(enabled: Boolean) {
            speakerphoneCalls += enabled
        }
    }
}
