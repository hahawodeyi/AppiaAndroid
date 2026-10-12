package cn.appia.im.domain.meeting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 会议会话相位（RN stores/meetingSessionStore.ts phase 同值域）。 */
enum class MeetingPhase { Idle, Preheating, InMeeting, Minimized }

/**
 * 会议会话态（RN meetingSessionStore 字段子集：title/meetingNumber/duration/participantCount/
 * isMuted）。**偏离注记**：RN `duration` 是 web 上报的格式化串；原生由 RTC [MeetingSessionStateMachine.applyStats]
 * 喂秒数，格式化归 UI。RN `page/webReady/pendingJoinNumber` 为 WebView 预热桥概念，greenfield 砍除。
 */
data class MeetingSessionState(
    val phase: MeetingPhase = MeetingPhase.Idle,
    val title: String? = null,
    val meetingNumber: String? = null,
    /** 会中累计时长（秒，RTC onRtcStats 喂入；进会即 0）。 */
    val durationSec: Long = 0,
    /** 与会人数（RTC onRtcStats.users，含本端）。 */
    val participantCount: Int = 0,
    /** 本端麦克风静音（RN meeting:state.isMuted 同义，UI 动作回写）。 */
    val isMuted: Boolean = false,
)

/**
 * 会议会话状态机（RN stores/meetingSessionStore.ts 的原生等价，WebView 桥消息改为驱动原生
 * RTC 回调：meeting:state→applyStats/setMicMuted、meeting:ended→end、meeting:kicked→kicked）。
 *
 * 迁移表：
 * ```
 * openPreheat   idle → preheating（其余相位 no-op——RN 预热恒单次）
 * markInMeeting idle|preheating → inMeeting（inMeeting/minimized no-op，保留首次 title）
 * minimize      inMeeting → minimized（其余 no-op）
 * restore       minimized → inMeeting（其余 no-op）
 * end           任意非 idle → idle（清字段；RN meeting:ended）
 * kicked        任意非 idle → idle（清字段；RN meeting:kicked）
 * ```
 *
 * **互斥不对称（坑 6）**：[blocksVoiceStart] 仅 inMeeting|minimized（preheating 不算"进行中"，
 * 不阻塞语音）；会议被语音阻塞的判定在 [meetingBlockedByVoice]（任意非 idle 语音相位）——
 * 方向不能抄反（RN openAntMeetingWeb.ts:29-40 / voiceSessionStore.ts:1039-1047）。
 *
 * **返回键（坑 4）**：[consumesBack] 仅 inMeeting 为真——preheating/minimized 必须 false 交还
 * 导航（RN MeetingMinimizeShell:79-100 两次回归记录）；会中返回≠退出，T7 UI 据此弹离开确认。
 */
class MeetingSessionStateMachine {

    private val _state = MutableStateFlow(MeetingSessionState())

    /** 会话态流（T7 UI collectAsState 单一事实源）。 */
    val state: StateFlow<MeetingSessionState> = _state.asStateFlow()

    /** 当前相位（互斥/返回键热路径同步读）。 */
    val phase: MeetingPhase get() = _state.value.phase

    /** RN openAntMeetingWeb 方案 A 预热（原生语义：会议层已打开、入会在途/待入会）。 */
    fun openPreheat(title: String? = null, meetingNumber: String? = null) {
        _state.update { cur ->
            if (cur.phase != MeetingPhase.Idle) cur
            else MeetingSessionState(phase = MeetingPhase.Preheating, title = title, meetingNumber = meetingNumber)
        }
    }

    /** 入会成功（RTC joinChannel 受理后调用；从 idle 冷进会与从 preheating 推进两路同入）。 */
    fun markInMeeting(title: String? = null, meetingNumber: String? = null) {
        _state.update { cur ->
            when (cur.phase) {
                MeetingPhase.Idle, MeetingPhase.Preheating -> MeetingSessionState(
                    phase = MeetingPhase.InMeeting,
                    title = title ?: cur.title,
                    meetingNumber = meetingNumber ?: cur.meetingNumber,
                    isMuted = cur.isMuted,
                )
                MeetingPhase.InMeeting, MeetingPhase.Minimized -> cur
            }
        }
    }

    /** RN meeting:minimize。 */
    fun minimize() {
        _state.update { cur -> if (cur.phase == MeetingPhase.InMeeting) cur.copy(phase = MeetingPhase.Minimized) else cur }
    }

    /** RN 最小化气泡点击/恢复。 */
    fun restore() {
        _state.update { cur -> if (cur.phase == MeetingPhase.Minimized) cur.copy(phase = MeetingPhase.InMeeting) else cur }
    }

    /** RN meeting:ended / 离开确认通过：回 idle 并清会话字段（isMuted 一并复位）。 */
    fun end() {
        _state.value = MeetingSessionState()
    }

    /** RN meeting:kicked（被踢出——与 end 终态相同，分立方法供 T7 上报/提示分叉）。 */
    fun kicked() {
        end()
    }

    /** RTC onRtcStats → duration/participantCount（RN meeting:state.data 两字段的原生来源）。 */
    fun applyStats(durationSec: Long, participantCount: Int) {
        _state.update { cur ->
            if (cur.phase == MeetingPhase.Idle) cur
            else cur.copy(durationSec = durationSec, participantCount = participantCount)
        }
    }

    /** 本端静音回写（RN meeting:state.isMuted）。 */
    fun setMicMuted(muted: Boolean) {
        _state.update { it.copy(isMuted = muted) }
    }

    /**
     * 会议是否阻塞语音外呼（坑 6 会议侧）：仅 inMeeting|minimized；**preheating 不算**——
     * RN voiceSessionStore.ts:1039-1047 注释明说对齐 openAntMeetingWeb。
     */
    fun blocksVoiceStart(): Boolean =
        phase == MeetingPhase.InMeeting || phase == MeetingPhase.Minimized

    /**
     * 会议是否消费返回键（坑 4）：仅 inMeeting。preheating/minimized 必须放行导航
     * （RN MeetingMinimizeShell:79-100——连续两次全 App 返回失效回归的教训）。
     */
    fun consumesBack(): Boolean = phase == MeetingPhase.InMeeting
}

/**
 * 会议是否被语音阻塞（坑 6 语音侧，M10 对接缝）：语音**任意非 idle** 相位阻塞会议打开
 * （RN openAntMeetingWeb.ts:29-33）。M10 尚未落地——[voicePhase] 暂收字符串（M10
 * voiceSessionStore.phase 同值域），M10 落地时换其枚举，规则不变（!= idle 即阻塞）。
 */
fun meetingBlockedByVoice(voicePhase: String?): Boolean =
    !voicePhase.isNullOrEmpty() && voicePhase != "idle"
