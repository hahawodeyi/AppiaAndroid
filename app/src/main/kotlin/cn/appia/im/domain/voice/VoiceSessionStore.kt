package cn.appia.im.domain.voice

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceChatHoster
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.VoiceUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * RN stores/voiceSessionStore.ts VoiceSessionState（:155-180）全字段 —— 纯状态宿主。
 * zustand get/set 对应：[update] 是 set(partial)，无动作无副作用（动作归 Task 7 Controller）。
 */
data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Idle,
    val hoster: VoiceChatHoster? = null,
    val callMsg: VoiceCallMsg? = null,
    val recordId: String? = null,
    val channelId: String? = null,
    val rid: String? = null,
    val muteLocal: Boolean = false,
    val speakerOn: Boolean = false,
    val users: List<VoiceUser> = emptyList(),
    /** 远端已加入频道；用于避免未接通时上传录音附件（RN :165-166）。 */
    val hadRemoteParticipant: Boolean = false,
    /** 本端 Agora UID（音量/静音状态映射，RN :167-168）。 */
    val agoraLocalUid: Long? = null,
    /** 用户手动开启的频道混音录音（RN :169-170）。 */
    val mediaRecordingActive: Boolean = false,
    val uiMinimized: Boolean = false,
    /** 来电横幅点击进入全屏确认（对齐旧版 openChatView + isAccept: false，RN :172-173）。 */
    val incomingUiExpanded: Boolean = false,
    val otherIncoming: OtherIncoming? = null,
    /** 对齐旧版 showOtherCallAlert + otherCallAlertType（RN getOtherIncomingAlertType: 1|2|3）。 */
    val otherIncomingAlertType: Int? = null,
    val outgoingHint: String? = null,
    /** 本会话代数；endCall 仅在同代时 idleState（RN :178-179）。代数语义归 Controller（Task 7）。 */
    val sessionGeneration: Int = 0,
)

/** RN :150-153 VoiceOtherIncoming。 */
data class OtherIncoming(
    val callMsg: VoiceCallMsg,
    val rid: String,
)

/**
 * RN stores/voiceSessionStore.ts zustand store 的状态宿主（:455-473 初始 idle 态）。
 * update 线程安全（synchronized —— Controller 多协程并发写）；纯状态，无动作无副作用。
 */
class VoiceSessionStore {

    private val lock = Any()

    private val _state = MutableStateFlow(VoiceSessionState())

    val state: StateFlow<VoiceSessionState> = _state

    /** zustand set(fn) 等价：synchronized 原子读改写（并发 update 安全）+ 同值不重发（StateFlow 语义）。 */
    fun update(transform: (VoiceSessionState) -> VoiceSessionState) {
        synchronized(lock) {
            val next = transform(_state.value)
            if (next != _state.value) _state.value = next
        }
    }

    /** RN :449-473 idleState() —— 全字段重置。 */
    fun idleState(): VoiceSessionState = VoiceSessionState()
}
