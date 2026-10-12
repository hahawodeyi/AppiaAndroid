package cn.appia.im.core.meeting.rtc

import android.content.Context
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * RTC 事件（engine 事件流唯一载荷；映射自 IRtcEngineEventHandler，T7/控制器按需消费）。
 * 原生回调线程投递（SharedFlow 缓冲 tryEmit），收集方自行切线程。
 */
sealed interface MeetingRtcEvent {
    /** 本端入会成功（onJoinChannelSuccess）。 */
    data object Joined : MeetingRtcEvent

    /** 本端离会（onLeaveChannel）。 */
    data object Left : MeetingRtcEvent

    data class UserJoined(val uid: Int) : MeetingRtcEvent

    /** reason 为 SDK 常量（USER_OFFLINE_QUIT/DROPPED 等），T7 展示层再释义。 */
    data class UserOffline(val uid: Int, val reason: Int) : MeetingRtcEvent

    /** 远端音频开/关（state==STARTED 即开）。 */
    data class RemoteAudioChanged(val uid: Int, val started: Boolean) : MeetingRtcEvent

    /** 远端视频出帧/停帧（DECODING 即出帧——共享/摄像头画面起止的 UI 信号）。 */
    data class RemoteVideoChanged(val uid: Int, val decoding: Boolean) : MeetingRtcEvent

    /** 周期统计：duration=会中累计秒、userCount=频道内人数（含本端）。 */
    data class Stats(val durationSec: Int, val userCount: Int) : MeetingRtcEvent

    data class Error(val code: Int) : MeetingRtcEvent
}

/**
 * Agora 命令面（[AgoraMeetingEngine] 驱动的最小闭包）。
 * **JVM 测试以 fake 实现**（引擎逻辑可测）；生产 [AgoraRtcImpl] 薄委托 RtcEngine，
 * 仅在 Android 运行时触达（单测不实例化）。签名镜像 SDK 4.5.2（T1 依赖）。
 */
interface AgoraRtcApi {
    /** 创建/重建底层引擎（SDK 全局单例：appId 变更须先 destroy）。 */
    fun create(appId: String, onEvent: (MeetingRtcEvent) -> Unit)

    /** token=null = cert-less 无凭据入会（spike §2.2：App Certificate 未启用模式）。 */
    fun joinChannel(token: String?, channelId: String, uid: Int): Int

    fun leaveChannel(): Int

    fun muteLocalAudioStream(muted: Boolean): Int

    fun muteLocalVideoStream(muted: Boolean): Int

    fun destroy()
}

/**
 * [AgoraRtcApi] 生产实现——**刻意最薄**：方法一一委托 RtcEngine，无业务逻辑（引擎语义在
 * [AgoraMeetingEngine]），native 回调 → [MeetingRtcEvent] 直译。RtcEngine.create 全局单例、
 * 回调来自 native 线程，故 @Synchronized 与 engine 侧串行化对齐。
 */
class AgoraRtcImpl(private val context: Context) : AgoraRtcApi {

    private var handler: IRtcEngineEventHandler? = null

    private var engine: RtcEngine? = null

    @Synchronized
    override fun create(appId: String, onEvent: (MeetingRtcEvent) -> Unit) {
        destroy()
        val h = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channelName: String?, uid: Int, elapsed: Int) {
                onEvent(MeetingRtcEvent.Joined)
            }

            override fun onLeaveChannel(stats: RtcStats?) {
                onEvent(MeetingRtcEvent.Left)
            }

            override fun onUserJoined(uid: Int, elapsed: Int) {
                onEvent(MeetingRtcEvent.UserJoined(uid))
            }

            override fun onUserOffline(uid: Int, reason: Int) {
                onEvent(MeetingRtcEvent.UserOffline(uid, reason))
            }

            override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                onEvent(MeetingRtcEvent.RemoteAudioChanged(uid, state == Constants.REMOTE_AUDIO_STATE_DECODING))
            }

            override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                onEvent(MeetingRtcEvent.RemoteVideoChanged(uid, state == Constants.REMOTE_VIDEO_STATE_DECODING))
            }

            override fun onRtcStats(stats: RtcStats?) {
                if (stats != null) onEvent(MeetingRtcEvent.Stats(stats.totalDuration, stats.users))
            }

            override fun onError(err: Int) {
                onEvent(MeetingRtcEvent.Error(err))
            }
        }
        handler = h
        engine = runCatching { RtcEngine.create(context, appId, h) }.getOrNull()
    }

    @Synchronized
    override fun joinChannel(token: String?, channelId: String, uid: Int): Int {
        val e = engine ?: return -1
        val options = ChannelMediaOptions().apply {
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
            autoSubscribeVideo = true
        }
        return e.joinChannel(token, channelId, uid, options)
    }

    @Synchronized
    override fun leaveChannel(): Int =
        engine?.let { runCatching { it.leaveChannel() }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun muteLocalAudioStream(muted: Boolean): Int =
        engine?.let { runCatching { it.muteLocalAudioStream(muted) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun muteLocalVideoStream(muted: Boolean): Int =
        engine?.let { runCatching { it.muteLocalVideoStream(muted) }.getOrDefault(-1) } ?: -1

    @Synchronized
    override fun destroy() {
        handler = null
        engine = null
        runCatching { RtcEngine.destroy() }
    }
}

/**
 * Agora 会议引擎封装（M8 T6；brief `core/meeting/rtc/AgoraMeetingEngine.kt`）：
 * - 生命周期按 appId 惰性创建（credentials.appId 到手才建；appId 变更先 destroy 再建——
 *   RtcEngine 全局单例约束），join/leave/mute 命令转发 [AgoraRtcApi]；
 * - 事件经 [events] SharedFlow 转发（native 线程 tryEmit、缓冲 64，丢帧优于阻塞回调线程）；
 * - 刻意薄：无重试、无状态机（相位归 domain/meeting），T7 UI 只对接本类 + 状态机。
 *
 * join 返回 false（appId 空 / SDK 拒绝）即入会失败；[leave] 幂等。
 */
class AgoraMeetingEngine(private val rtc: AgoraRtcApi) {

    private val _events = MutableSharedFlow<MeetingRtcEvent>(extraBufferCapacity = 64)

    /** RTC 事件流（SharedFlow；T7 视频宫格/成员/共享订阅）。 */
    val events: SharedFlow<MeetingRtcEvent> = _events.asSharedFlow()

    @Volatile
    private var createdAppId: String? = null

    @Volatile
    private var joined: Boolean = false

    /** 是否在频道内（leave 幂等/mute 守卫依据）。 */
    val inChannel: Boolean get() = joined

    /** cert-less：token 可为 null（spike §2.2 兜底路径）。失败返回 false（不抛）。 */
    @Synchronized
    fun join(appId: String, channelName: String, token: String?, uid: Int): Boolean {
        if (appId.isEmpty()) return false
        if (createdAppId != appId) {
            if (createdAppId != null) rtc.destroy()
            runCatching { rtc.create(appId, _events::tryEmit) }.onFailure { return false }
            createdAppId = appId
        }
        joined = rtc.joinChannel(token, channelName, uid) == 0
        return joined
    }

    /** 离会（幂等；不 destroy——复用引擎待下次入会）。 */
    @Synchronized
    fun leave() {
        if (!joined) return
        rtc.leaveChannel()
        joined = false
    }

    @Synchronized
    fun setMicMuted(muted: Boolean) {
        if (joined) rtc.muteLocalAudioStream(muted)
    }

    /** 摄像头发布默认关（ChannelMediaOptions），T7 开视频时置 false（muteLocalVideoStream(false)）。 */
    @Synchronized
    fun setCameraMuted(muted: Boolean) {
        if (joined) rtc.muteLocalVideoStream(muted)
    }

    /** 离会并销毁引擎（登出/进程收尾；幂等）。 */
    @Synchronized
    fun release() {
        leave()
        if (createdAppId != null) {
            rtc.destroy()
            createdAppId = null
        }
    }
}
