package cn.appia.im.core.voice.rtc

import android.content.Context
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.internal.EncryptionConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 语音 RTC 事件（engine 事件流唯一载荷；native 回调 → 本事件直译，
 * RN appiaMobile/src/services/voice/agoraRtc.ts:93-138 handler 同构）。
 * 回调来自 native 线程，SharedFlow tryEmit 缓冲 64，收集方自行切线程。
 */
sealed interface VoiceRtcEvent {
    /** 本端入频道成功（onJoinChannelSuccess）。 */
    data object Joined : VoiceRtcEvent

    data class UserJoined(val uid: Long) : VoiceRtcEvent

    /** 引擎照常上抛（RN 同构）；T7 Controller 对齐旧版按需忽略。 */
    data class UserOffline(val uid: Long) : VoiceRtcEvent

    data class UserMuteAudio(val uid: Long, val muted: Boolean) : VoiceRtcEvent

    /** 音量指示（onAudioVolumeIndication）；uid=0 本端映射归 Controller，engine 原样透传。 */
    data class VolumeIndication(val speakers: List<VolumeSpeaker>) : VoiceRtcEvent

    data class Error(val code: Int) : VoiceRtcEvent
}

/** RN onAudioVolumeIndication speakers 元素（uid/volume；vad 等字段语音场景未用）。 */
data class VolumeSpeaker(val uid: Long, val volume: Int)

/**
 * Agora 语音命令面（[VoiceRtcEngine] 驱动的最小闭包，镜像 M8 meeting AgoraRtcApi 先例）。
 * **JVM 测试以 fake 实现**；生产 [VoiceRtcImpl] 薄委托 RtcEngine，仅在 Android 运行时触达。
 * 与 meeting 引擎物理隔离（spec：语音独立 RtcEngine.create，不共享实例）。
 */
interface VoiceRtcApi {
    /** 创建/重建底层引擎（SDK 全局单例：appId 变更须先 destroy）。 */
    fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit)

    /** 入频道（LiveBroadcasting + Broadcaster + 加密 + 初值在 engine 侧组合后经 options 下发）。 */
    fun joinChannel(
        token: String?,
        channelId: String,
        uid: Long,
        encKey: String?,
        muted: Boolean,
        speakerOn: Boolean,
    ): Int

    fun leaveChannel(): Int

    fun muteLocalAudioStream(muted: Boolean): Int

    fun setEnableSpeakerphone(enabled: Boolean): Int

    fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean): Int

    /**
     * 混音回铃（RN agoraRtc.ts:230 startAudioMixing(path, true, -1, 0) 同构）：
     * loop 透传 SDK loopback 位（true=仅本地播放），cycle 固定 -1 无限循环。
     */
    fun startAudioMixing(path: String, loop: Boolean): Int

    fun stopAudioMixing(): Int

    fun adjustAudioMixingVolume(volume: Int): Int

    fun destroy()
}

/**
 * [VoiceRtcApi] 生产实现——刻意最薄（AgoraMeetingEngine.AgoraRtcImpl 先例）：方法一一委托
 * RtcEngine，无业务逻辑（引擎语义在 [VoiceRtcEngine]）。RtcEngine.create 全局单例、回调来自
 * native 线程，故 @Synchronized；失败 runCatching 兜底 -1。
 */
class VoiceRtcImpl(private val context: Context) : VoiceRtcApi {

    private var engine: RtcEngine? = null

    /** RN channelProfileSet（agoraRtc.ts:36）：直播 profile 只设置一次，避免重复切换。 */
    private var channelProfileSet = false

    @Synchronized
    override fun create(appId: String, onEvent: (VoiceRtcEvent) -> Unit) {
        destroy()
        val h = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channelName: String?, uid: Int, elapsed: Int) {
                onEvent(VoiceRtcEvent.Joined)
            }

            override fun onUserJoined(uid: Int, elapsed: Int) {
                onEvent(VoiceRtcEvent.UserJoined(uid.toLong()))
            }

            override fun onUserOffline(uid: Int, reason: Int) {
                onEvent(VoiceRtcEvent.UserOffline(uid.toLong()))
            }

            override fun onUserMuteAudio(uid: Int, muted: Boolean) {
                onEvent(VoiceRtcEvent.UserMuteAudio(uid.toLong(), muted))
            }

            override fun onAudioVolumeIndication(
                speakers: Array<out IRtcEngineEventHandler.AudioVolumeInfo>?,
                totalVolume: Int,
            ) {
                onEvent(
                    VoiceRtcEvent.VolumeIndication(
                        speakers.orEmpty().map { VolumeSpeaker(it.uid.toLong(), it.volume) },
                    ),
                )
            }

            override fun onError(err: Int) {
                onEvent(VoiceRtcEvent.Error(err))
            }
        }
        engine = runCatching { RtcEngine.create(context, appId, h) }.getOrNull()
        // RN initialize（agoraRtc.ts:144-151）：create 后即 enableAudio
        engine?.let { runCatching { it.enableAudio() } }
    }

    @Synchronized
    override fun joinChannel(
        token: String?,
        channelId: String,
        uid: Long,
        encKey: String?,
        muted: Boolean,
        speakerOn: Boolean,
    ): Int {
        val e = engine ?: return -1
        // RN agoraRtc.ts:159-163：直播 profile 只在 join 前设置一次（channelProfileSet 标记）
        if (!channelProfileSet) {
            e.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            channelProfileSet = true
        }
        // RN agoraRtc.ts:18-30 applyChannelEncryption：encKey 非空 Aes128Gcm，否则关
        val config = EncryptionConfig()
        if (!encKey.isNullOrEmpty()) {
            config.encryptionMode = EncryptionConfig.EncryptionMode.AES_128_GCM
            config.encryptionKey = encKey
        }
        e.enableEncryption(!encKey.isNullOrEmpty(), config)
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            enableAudioRecordingOrPlayout = true
        }
        // muted/speakerOn 初值此处不立即生效：RN 语义为 pendingJoinAudio，Joined 回调后经
        // 500ms 延迟窗口应用（agoraRtc.ts:38/:58-91——提前动音频路由会与 Agora 启动流程
        // 竞争 AVAudioSession）。参数保留在签名中随 join 记录透传。
        return e.joinChannel(token, channelId, uid.toInt(), options)
    }

    @Synchronized
    override fun leaveChannel(): Int =
        engine?.let { runCatching { it.leaveChannel() }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun muteLocalAudioStream(muted: Boolean): Int =
        engine?.let { runCatching { it.muteLocalAudioStream(muted) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun setEnableSpeakerphone(enabled: Boolean): Int =
        engine?.let { runCatching { it.setEnableSpeakerphone(enabled) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun enableAudioVolumeIndication(interval: Int, smooth: Int, reportVad: Boolean): Int =
        engine?.let { runCatching { it.enableAudioVolumeIndication(interval, smooth, reportVad) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun startAudioMixing(path: String, loop: Boolean): Int =
        engine?.let { runCatching { it.startAudioMixing(path, loop, -1, 0) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun stopAudioMixing(): Int =
        engine?.let { runCatching { it.stopAudioMixing() }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun adjustAudioMixingVolume(volume: Int): Int =
        engine?.let { runCatching { it.adjustAudioMixingVolume(volume) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun destroy() {
        engine = null
        channelProfileSet = false
        runCatching { RtcEngine.destroy() }
    }
}

/**
 * 通话音频路由抽象（RN react-native-incall-manager 同位面；生产 AudioManager 实现归 Task 12）。
 */
interface InCallAudioRoute {
    /** RN InCallManager.start({media:'audio'}) 同位（join 时调）。 */
    fun start()

    /** RN InCallManager.stop() 同位（leave/release 时调）。 */
    fun stop()

    fun setSpeakerphoneOn(enabled: Boolean)
}
