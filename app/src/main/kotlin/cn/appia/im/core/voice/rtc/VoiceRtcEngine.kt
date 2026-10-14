package cn.appia.im.core.voice.rtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Agora 语音引擎封装（M10 T5；逐行为移植 RN appiaMobile/src/services/voice/agoraRtc.ts）：
 * - 生命周期按 appId 惰性创建（appId 变更先 destroy 再建——RtcEngine 全局单例约束），
 *   与 meeting 引擎物理隔离（独立 RtcEngine.create，spec 裁定不共享实例）；
 * - join：音频路由启动 + joinChannel（LiveBroadcasting/Broadcaster/加密/初值归 [VoiceRtcApi]
 *   组合，RN agoraRtc.ts:152-169）；失败返回 false 且无音频路由；
 * - 入频道成功后音量指示立即开启（enableAudioVolumeIndication(500,1,true)，RN :64），
 *   扬声器路由/静音延迟 [delayMs] 应用（RN :68 setTimeout 500ms）；
 * - 延迟窗口内 setMuted/setSpeaker 同步 pending 状态（RN delayedAudio :194/:209），
 *   用户切换不被 join 初值覆盖；
 * - 主叫回铃 startAudioMixing + adjustAudioMixingVolume(10)（RN :230-231）；
 * - released 守卫：release 后全部回调与命令静默（RN handler :95-137 各分支首行）。
 *
 * 500ms 音频路由延迟为 RN iOS AVAudioSession 竞争补偿，Android 真机验证前保留（spec §4）。
 */
class VoiceRtcEngine(
    private val rtc: VoiceRtcApi,
    private val audioRoute: InCallAudioRoute,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val delayMs: Long = 500L,
) {

    private val _events = MutableSharedFlow<VoiceRtcEvent>(extraBufferCapacity = 64)

    /** RTC 事件流（SharedFlow；T7 Controller 订阅）。 */
    val events: SharedFlow<VoiceRtcEvent> = _events.asSharedFlow()

    @Volatile
    private var released = false

    @Volatile
    private var createdAppId: String? = null

    @Volatile
    private var joined = false

    /** 是否在频道内（join 成功即 true；leave 幂等/mute 守卫依据）。 */
    val inChannel: Boolean get() = joined

    /** RN pendingJoinAudio（agoraRtc.ts:38）：最近一次 join 携带的初始音频状态，Joined 后应用。 */
    @Volatile
    private var pendingJoinAudio: Pair<Boolean, Boolean> = false to false

    /** RN delayedAudio（agoraRtc.ts:40）：延迟窗口内待应用状态；用户切换时同步，防旧值覆盖。 */
    @Volatile
    private var delayedAudio: Pair<Boolean, Boolean>? = null

    private var routeJob: Job? = null

    /** join（RN join :152-169；失败返回 false 且不启动音频路由）。 */
    @Synchronized
    fun join(
        appId: String,
        channelName: String,
        token: String?,
        uid: Long,
        encKey: String?,
        muted: Boolean,
        speakerOn: Boolean,
    ): Boolean {
        if (released) return false
        if (appId.isEmpty()) return false
        if (createdAppId != appId) {
            if (createdAppId != null) rtc.destroy()
            runCatching { rtc.create(appId, ::onEvent) }.onFailure { return false }
            createdAppId = appId
        }
        clearPendingAudioRoute()
        pendingJoinAudio = muted to speakerOn
        audioRoute.start()
        joined = rtc.joinChannel(token, channelName, uid, encKey, muted, speakerOn) == 0
        if (!joined) audioRoute.stop()
        return joined
    }

    /** 离频道（幂等；停回铃混音与音频路由，RN leave :171-188 + brief 停混音）。 */
    @Synchronized
    fun leave() {
        if (released || !joined) return
        clearPendingAudioRoute()
        rtc.stopAudioMixing()
        rtc.leaveChannel()
        audioRoute.stop()
        joined = false
    }

    /** 静音（延迟窗口内同步 pending，RN setMuted :189-202）。 */
    @Synchronized
    fun setMuted(muted: Boolean) {
        if (released) return
        delayedAudio?.let { delayedAudio = muted to it.second }
        rtc.muteLocalAudioStream(muted)
    }

    /** 免提（延迟窗口内同步 pending，RN setSpeaker :203-217）。 */
    @Synchronized
    fun setSpeaker(enabled: Boolean) {
        if (released) return
        delayedAudio?.let { delayedAudio = it.first to enabled }
        rtc.setEnableSpeakerphone(enabled)
        audioRoute.setSpeakerphoneOn(enabled)
    }

    /** 主叫回铃（RN startHostRingback :224-237：loop + 音量 10）。 */
    @Synchronized
    fun startHostRingback(assetPath: String) {
        if (released) return
        rtc.startAudioMixing(assetPath, loop = true)
        rtc.adjustAudioMixingVolume(RINGBACK_VOLUME)
    }

    /** 停回铃（RN stopHostRingback :238-247）。 */
    @Synchronized
    fun stopHostRingback() {
        if (released) return
        rtc.stopAudioMixing()
    }

    /** 释放引擎（登出/进程收尾；幂等；此后回调全部丢弃，RN release :248-282）。 */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        clearPendingAudioRoute()
        rtc.stopAudioMixing()
        rtc.leaveChannel()
        rtc.destroy()
        audioRoute.stop()
        joined = false
        createdAppId = null
        scope.cancel()
    }

    /** rtc 回调入口（native 线程；released 全回调丢弃，RN handler :95-137）。 */
    @Synchronized
    private fun onEvent(event: VoiceRtcEvent) {
        if (released) return
        if (event is VoiceRtcEvent.Joined && joined) {
            applyInCallAudio(pendingJoinAudio)
        }
        _events.tryEmit(event)
    }

    /**
     * 音量回调立即生效；扬声器路由/静音延迟 [delayMs] 应用（RN applyInCallAudio :58-91）。
     * joinChannel 同步返回时引擎尚未真正入频道，提前操作 setEnableSpeakerphone / 路由
     * 会与 Agora 启动流程竞争 AVAudioSession（iOS），导致接通后数秒听不到对方声音。
     */
    @Synchronized
    private fun applyInCallAudio(audio: Pair<Boolean, Boolean>) {
        clearPendingAudioRoute()
        rtc.enableAudioVolumeIndication(500, 1, true)
        delayedAudio = audio
        routeJob = scope.launch {
            delay(delayMs)
            val apply = delayedAudio
            delayedAudio = null
            if (released || apply == null) return@launch
            rtc.setEnableSpeakerphone(apply.second)
            rtc.muteLocalAudioStream(apply.first)
            audioRoute.setSpeakerphoneOn(apply.second)
        }
    }

    /** RN clearPendingAudioRoute（agoraRtc.ts:44-50）：取消延迟路由并清 pending。 */
    @Synchronized
    private fun clearPendingAudioRoute() {
        routeJob?.cancel()
        routeJob = null
        delayedAudio = null
    }

    private companion object {
        /** RN agoraRtc.ts:231 adjustAudioMixingVolume(10) 同值。 */
        const val RINGBACK_VOLUME = 10
    }
}
