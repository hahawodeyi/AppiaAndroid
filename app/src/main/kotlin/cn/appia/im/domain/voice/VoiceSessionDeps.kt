package cn.appia.im.domain.voice

import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.JoinBody
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallTimers
import cn.appia.im.core.voice.lib.VoiceCallDismissedStore
import cn.appia.im.core.voice.rtc.VoiceRtcEngine

/**
 * RN stores/voiceSessionStore.ts:224-239 VoiceSessionDeps + :276-301 getVoiceSessionContext 等价。
 *
 * oncall 九端点一一镜像 [cn.appia.im.core.voice.OncallApi]（生产装配直接委托；
 * `suspend fun sessionContext(): VoiceSessionContext?` 为 RN getVoiceSessionContext 的
 * 同步化形态——RN 内部 await tokenCache/DB，AA 装配层（Task 12）持有 suspend 依赖，
 * Controller 调用处统一 withContext 承接）。
 *
 * RN 40002 处置分工（oncallApi.ts:62-64 vs Controller）：RN 在 oncallApi 层
 * `voiceTokenCache.invalidate()` 后抛 OncallTokenExpiredError；AA 裁定 OncallApi 不持
 * cache 引用（Task 4 实现锁定），invalidate 上移为 [onTokenExpired] 回调——
 * 生产装配 `VoiceTokenCache::invalidate`，Controller 的 fetchAgoraTokenWithRetry 捕获
 * OncallTokenExpiredException 后调用并重试一次。
 */
interface VoiceSessionDeps {
    /** oncallApi.ts:127-136 GET /api/v2/token。 */
    suspend fun fetchAgoraToken(ctx: OncallContext, channelName: String, uid: Long): OncallResponse

    /** oncallApi.ts:116-125 POST /api/v1/oncall/init。 */
    suspend fun informInit(ctx: OncallContext, body: InformInitBody): OncallResponse

    /** oncallApi.ts:138-148 POST /api/v1/oncall/record/status/update。 */
    suspend fun updateRecordStatus(ctx: OncallContext, recordId: String, status: String): OncallResponse

    /** oncallApi.ts:192-207 POST /api/v1/oncall/user/join。 */
    suspend fun join(ctx: OncallContext, body: JoinBody): OncallResponse

    /** oncallApi.ts:209-224 POST /api/v1/oncall/user/reject。 */
    suspend fun reject(ctx: OncallContext, recordId: String, username: String, notificationOrg: String? = null): OncallResponse

    /** oncallApi.ts:226-240 POST /api/v1/oncall/user/leave。 */
    suspend fun leave(ctx: OncallContext, recordId: String, username: String): OncallResponse

    /** oncallApi.ts:242-251 POST /api/v1/oncall/add/user。 */
    suspend fun addUsers(ctx: OncallContext, body: AddUsersBody): OncallResponse

    /** oncallApi.ts:160-169 GET /api/v2/oncall/record/status。 */
    suspend fun getRecordStatusV2(ctx: OncallContext, recordId: String, recordOrg: String): OncallResponse

    /** oncallApi.ts:253-268 POST /api/v1/oncall/user/get/notify。 */
    suspend fun getNotify(ctx: OncallContext, org: String, recordId: String, username: String, recordOrg: String? = null): OncallResponse

    /** Agora 语音引擎（Task 5；会话生命周期归 Controller）。 */
    val rtc: VoiceRtcEngine

    /** 通话计时器（Task 6；recordId 锚定 30s/65s）。 */
    val timers: VoiceCallTimers

    /** 会话状态宿主（Task 6）。 */
    val store: VoiceSessionStore

    /**
     * RN :276-301 getVoiceSessionContext：登录态缺 serverUrl/token/user → null；
     * 生产装配 = VoiceTokenCache.getToken + SettingDao Enterprise_ID + VoiceEnv。
     */
    fun sessionContext(): VoiceSessionContext?

    /**
     * 会议相位互斥（RN :1040-1046 useMeetingSessionStore.phase，preheating 放行）。
     * 生产读 MeetingSessionStateMachine.phase.name（小写串比对 "inMeeting"/"minimized"）。
     */
    val meetingPhaseProvider: () -> String?

    /** 本地忽略来电记录（voiceCallDismissed.ts 模块级 Map 的实例化宿主）。 */
    val dismissed: VoiceCallDismissedStore

    /**
     * 房间行 callMsg 状态缝（RN :1051-1063 经 watermelondb 读 chats.callMsg +
     * parseEffectiveRoomCallMsg 合并 store 快照；AA 生产装配在 Task 12 接 RoomRepository）。
     */
    fun roomCallMsgForRid(rid: String): VoiceCallMsg?

    /** 40002 → 缓存失效回调（生产 = VoiceTokenCache.invalidate()，oncallApi.ts:63 同位）。 */
    fun onTokenExpired()
}

/**
 * RN :138-142 VoiceSessionContext（OncallApiContext 扩展 userId/username/userDisplayName）。
 * Kotlin 侧组合替代继承（OncallContext 为纯数据载体）。
 */
data class VoiceSessionContext(
    val org: String,
    val businessToken: String,
    val apiHost: String,
    val userId: String,
    val username: String,
    val userDisplayName: String,
) {
    /** RN deps 端点签名收 OncallApiContext——AA 组合转发。 */
    fun oncallContext(): OncallContext = OncallContext(org = org, businessToken = businessToken, apiHost = apiHost)
}
