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

    /**
     * RN clearLocalRoomCallAfterEnd（groupVoiceCall.ts）：清除房间本地 callMsg,避免 DB 仍显示
     * calling 导致无法立即重拨（voiceSessionStore.ts:1631-1643 endCall 收尾,Task 12 生产接线）。
     */
    fun clearRoomCallMsg(rid: String)

    /** 40002 → 缓存失效回调（生产 = VoiceTokenCache.invalidate()，oncallApi.ts:63 同位）。 */
    fun onTokenExpired()

    /**
     * RN lib/voice/switchSessionToVoiceCallOrg（跨主体来电确认后切换登录主体到记录 org）。
     * 返回 true = 切换成功且 [sessionContext] 已指向新 org；生产装配走 OrgSwitchCoordinator
     * （Task 12）。RN 的 no_candidate/switch_failed 细分收窄为 Boolean——false 时 Controller
     * 统一 toast voice_crossOrg_switchUnavailable（RN :1542-1549）。
     */
    suspend fun switchToVoiceOrg(org: String): Boolean

    /**
     * 来电铃声/震动缝（RN services/voice/voiceRingtone.ts 的 start/stopIncomingRingtone）。
     * AppState-active 分流（后台交 Telecom 系统铃声）归 Task 9 AndroidVoiceRinger 内部；
     * Task 9 实现于 core/voice/platform 时可整体上移 core/voice（依赖方向）。
     */
    val ringer: VoiceRinger

    /**
     * RN utils/deviceInfo.getDeviceId（语音多端接听判定 + oncallJoin deviceId 字段）。
     * 生产 = ANDROID_ID 读取（Task 12 装配）；空/失败返回 null（RN '' falsy 同位）。
     */
    val deviceIdProvider: () -> String?

    /** RN joinExisting :1918-1929 / startFromMessage :1751-1754 直读房间行 callMsg raw
     * （chats.callMsg 原文，无 store 合并——与 [roomCallMsgForRid] 的 effective 合并语义不同）。
     */
    fun roomCallMsgRawForRid(rid: String): String?

    /** RN joinExisting :1924-1926 `options?.serverOnCallStatus ?? chat.onCallStatus` 的 DB 回退。 */
    fun roomOnCallStatusForRid(rid: String): Boolean?

    /**
     * 麦克风运行时权限门（RN requestVoiceMicrophonePermission——startOutgoing :1070 /
     * acceptIncoming :1334 / joinOngoingCall :1806 三处 store 内置门，Task 12 装配）。
     * true = 已授权（含已授予过的同步短路）；false = 拒绝（RN 侧已弹 voice_error_mic_denied
     * Alert，AA 装配层同义）。默认恒 true（测试/JVM 无权限域）。
     */
    suspend fun requestMicPermission(): Boolean = true
}

/** RN voiceRingtone.ts 最小缝（来电铃声 + 震动；Task 9 AndroidVoiceRinger 生产实现）。 */
interface VoiceRinger {
    fun start()
    fun stop()
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
