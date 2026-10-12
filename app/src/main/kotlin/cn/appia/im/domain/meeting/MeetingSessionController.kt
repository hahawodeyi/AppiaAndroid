package cn.appia.im.domain.meeting

import cn.appia.im.core.meeting.MeetingCredentials
import cn.appia.im.core.meeting.rtc.AgoraMeetingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会议加入流缝（brief scope 4；T7 UI 消费）：credentials fetch → engine.join → 状态机
 * inMeeting；leave → engine.leave → idle。
 *
 * - **cert-less 兜底（spike §5-2）**：credentials.token 为空**或等于 appId** → 以 null token
 *   join（SDK App Certificate 未启用模式，`.token===this.context.appId` 门证据）；appId 缺失
 *   则无从建引擎 → [MeetingSessionController.JoinResult.Failed]。
 * - **取消安全**：join 挂起中取消（用户退出加入页）→ 状态机回 idle 后原样重抛
 *   （CancellationException 不吞）；已 inMeeting 后的取消不自动离会（离开语义归 [leave]/调用方）。
 * - 互斥前置（坑 6）在 UI 层查 [MeetingSessionStateMachine.blocksVoiceStart] /
 *   [meetingBlockedByVoice] 后才发起；本层只防线内重入（已在会中/最小化 → Failed）。
 */
class MeetingSessionController(
    private val engine: AgoraMeetingEngine,
    private val machine: MeetingSessionStateMachine,
    /** credentials 拉取缝（生产 = MeetingCredentialsApi.fetchMeetingCredentials + base 解析 + 会话头；测试注入 fake）。 */
    private val fetchCredentials: suspend (channelName: String, uid: Int) -> MeetingCredentials?,
) {

    sealed interface JoinResult {
        data object Joined : JoinResult
        data class Failed(val reason: String) : JoinResult
    }

    /**
     * 入会：预热态（phase=Preheating，加入页可据此渲染"连接中"）→ 拉凭据 → join → inMeeting。
     * 任一步失败/取消 → 状态机回 idle。
     */
    suspend fun join(channelName: String, uid: Int, title: String? = null): JoinResult {
        val phase = machine.phase
        if (phase == MeetingPhase.InMeeting || phase == MeetingPhase.Minimized) {
            return JoinResult.Failed("meeting_already_active")
        }
        machine.openPreheat(title, channelName)
        try {
            val creds = fetchCredentials(channelName, uid)
                ?: return fail("credentials_unavailable")
            val appId = creds.appId?.trim().orEmpty()
            if (appId.isEmpty()) return fail("credentials_missing_app_id")
            // spike §2.2：token 空/等于 appId → cert-less（null token 直入）
            val token = creds.token?.trim()?.takeIf { it.isNotEmpty() && it != appId }
            val ok = withContext(Dispatchers.IO) { engine.join(appId, channelName, token, uid) }
            if (!ok) return fail("join_rejected")
            machine.markInMeeting(title, channelName)
            return JoinResult.Joined
        } catch (e: CancellationException) {
            machine.end()
            throw e
        }
    }

    /** 离会（RN meeting:request-leave 确认通过后调用）：引擎离会 + 状态机回 idle。幂等。 */
    fun leave() {
        engine.leave()
        machine.end()
    }

    private fun fail(reason: String): JoinResult.Failed {
        machine.end()
        return JoinResult.Failed(reason)
    }
}
