package cn.appia.im.domain.voice

import android.util.Log
import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
import cn.appia.im.core.voice.JoinBody
import cn.appia.im.core.voice.OncallResponse
import cn.appia.im.core.voice.OncallTokenExpiredException
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.VoiceCallRecordData
import cn.appia.im.core.voice.voiceJson
import cn.appia.im.core.voice.VoiceChatReceiver
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.VoiceEnv
import cn.appia.im.core.voice.VoiceUserStatus
import cn.appia.im.core.voice.isOncallSuccess
import cn.appia.im.core.voice.lib.AgoraVolumeSpeaker
import cn.appia.im.core.voice.lib.applyAgoraVolumeToUsers
import cn.appia.im.core.voice.lib.applyRemoteMuteToUsers
import cn.appia.im.core.voice.lib.countGroupVoiceInvitees
import cn.appia.im.core.voice.lib.getOtherIncomingAlertType
import cn.appia.im.core.voice.lib.groupHasCalleeInVoiceChannel
import cn.appia.im.core.voice.lib.isCallRecordExplicitlyEnded
import cn.appia.im.core.voice.lib.isDirectVoicePeerInCall
import cn.appia.im.core.voice.lib.isGroupVoiceCallMsg
import cn.appia.im.core.voice.lib.isRoomCallMsgActive
import cn.appia.im.core.voice.lib.isStaleEndedCallSyncForHost
import cn.appia.im.core.voice.lib.isVoiceAnsweredOnOtherDevice
import cn.appia.im.core.voice.lib.mapCallMsgToVoiceUsers
import cn.appia.im.core.voice.lib.mergeVoiceUsersPreservingRtcState
import cn.appia.im.core.voice.lib.parseCallMsg
import cn.appia.im.core.voice.lib.pickRicherRoomCallMsgRaw
import cn.appia.im.core.voice.lib.prepareCallMsgForRoomVoiceJoin
import cn.appia.im.core.voice.lib.reconcileStaleLocalRoomCallMsg
import cn.appia.im.core.voice.lib.resolveAgoraJoinUid
import cn.appia.im.core.voice.lib.resolveVoiceCallRoomType
import cn.appia.im.core.voice.lib.shouldAcceptRemoteUserJoined
import cn.appia.im.core.voice.lib.shouldClearLocalRoomCallAfterEnd
import cn.appia.im.core.voice.lib.shouldEndCallFromCallMsg
import cn.appia.im.core.voice.lib.shouldPresentIncomingForUser
import cn.appia.im.core.voice.lib.shouldQueueOtherIncoming
import cn.appia.im.core.voice.lib.shouldShowRoomVoiceJoinBanner
import cn.appia.im.core.voice.lib.VoiceUidResolveException
import cn.appia.im.core.voice.lib.isVoluntaryGroupRejoinEligible
import cn.appia.im.core.voice.rtc.VoiceRtcEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * 语音会话编排（RN stores/voiceSessionStore.ts zustand store 的动作半部）——
 * I（主叫链）: :1034-1242 startOutgoing / :1585-1679 endCall / :629-744 ensureAgoraRtc·
 * joinAgoraChannel·runWithAgoraGeneration·enterInCallFromAgora·onEnteredInCall /
 * :749-758 resetSession / :760-781 setUiMinimized·setMuted·setSpeaker / :1681-1732 inviteUsers /
 * :399-452 轮询 / :401-406 stopCallStatusPolling / :489-536 releaseAgoraRtc。
 * II（被叫链,Task 8）: :806-1032 syncCallMsgFromRoom / :1244-1308 presentIncoming /
 * :1310-1319 open/closeIncomingConfirm / :1321-1409 acceptIncoming / :1411-1439 rejectIncoming /
 * :1441-1583 otherIncoming 六动作 / :1734-1798 startFromMessage / :1800-1881 joinOngoingCall /
 * :1883-2078 joinExisting。
 *
 * 状态在 [VoiceSessionStore]（Task 6 纯宿主），本类只持动作与副作用。
 *
 * 竞态防护（RN 语义 verbatim）：
 * - **sessionGeneration**（RN :244/:248-251）:每次会话建立/复位 bump；异步链各步骤间对代检查
 *   （[isOutgoingStartAborted]），过期链静默丢弃（token 返回不 init、init 返回补发 cancel 不 join）。
 * - **endCallInFlight 单飞**（RN :243/:1613-1678）:并发 endCall 共享同一次清理——
 *   Mutex + 共享 [Deferred]，后到调用 await 先到结果，清理序列只跑一次。
 * - **agoraGeneration**（RN :242/:493/:618-627）:RTC 事件处理器携带创建代数；endCall/
 *   resetSession 收尾时 bump，此后到达的引擎事件整体丢弃（[runWithAgoraGeneration]）。
 *
 * 引擎生命周期裁定（AA/Task 5 差异，审查记录）:RN releaseAgoraRtc 每 call 后 rtc.release()
 * （RN 引擎可重建）；AA [VoiceRtcEngine.release] 含 released 终态标志，会话级复用同一实例——
 * endCall/resetSession 做 stopHostRingback + leave + agoraGeneration bump（事件面等价 RN），
 * release 留给 [shutdown]（登出）。
 *
 * 铃声裁定（RN 差异，记录于 Task 8 报告）:RN store 只调 stopIncomingRingtone，
 * start 由 UI VoiceCallHost 在 phase==='incomingRinging' + AppState active 时驱动;
 * AA 无对应 UI 层时序缝，铃声起停收拢进 Controller（presentIncoming start /
 * accept·reject·timeout·sync-reset stop）。
 */
class VoiceSessionController(
    private val deps: VoiceSessionDeps,
    private val scope: CoroutineScope,
) {

    /** 会话内 UI 事件（i18n key 直发，Task 11/12 UI 层翻译；showVoiceError :9-17/showAppToast 同位）。 */
    private val _uiEvents = MutableSharedFlow<VoiceUiEvent>(extraBufferCapacity = 16)

    val uiEvents: SharedFlow<VoiceUiEvent> = _uiEvents.asSharedFlow()

    /** RN :241-246 模块级可变状态 → Controller 字段。 */
    private var agoraGeneration = 0

    /**
     * 当前 join 的事件代数（RN ensureAgoraRtc :629-715 在引擎创建时捕获 generation——
     * RN 每会话释放重建引擎故一次性捕获;AA 引擎常驻,改为 joinAgoraChannel 时重臂,
     * releaseAgoraRtc/shutdown 的 agoraGeneration+=1 使此后事件失效,重拨再 join 重新接通）。
     */
    @Volatile
    private var rtcEventGeneration: Int? = null

    /** RN :244 voiceSessionGeneration（模块级计数器，测试可重置）。 */
    private var voiceSessionGeneration = 0

    /** RN :243 endCallInFlight（Promise → 共享 Deferred）。 */
    private var endCallInFlight: Deferred<Unit>? = null
    private val endCallMutex = kotlinx.coroutines.sync.Mutex()

    /** RN :252 callStatusPollTimer（setInterval → 协程 Job）。 */
    private var pollJob: Job? = null

    private val eventJob: Job = scope.launch { collectRtcEvents() }

    /** store 透传（Task 10/12 消费）。 */
    val store: VoiceSessionStore = deps.store

    // ─────────────────────────────────────────────────────────────────────
    // startOutgoing（RN :1034-1242）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :1034-1242。守卫链:phase != idle → return（:1035-1039）→ 会议互斥（:1040-1046,
     * preheating 放行）→ endCallInFlight 等待（:1047-1049）→ 房间 callMsg 检查（:1051-1068,
     * canStartOutgoing）→ sessionContext（:1076-1081）→ 占位 callMsg + outgoingRinging（:1086-1116）
     * → token（:1119-1129,失败 toast+reset）→ 代检查（:1131-1134）→ init（:1136-1154,失败 toast+reset）
     * → 代检查（:1159-1172,中止补发 cancel）→ 写 recordId/callMsg（:1189-1193）→ joinAgoraChannel
     * （:1195-1206,失败 toast+endCall）→ 30s/65s 计时 + 轮询（:1208-1212）。
     *
     * RN requestVoiceMicrophonePermission（:1070-1074）归 Task 12 UI 层（Caller 前置）。
     */
    fun startOutgoing(hoster: cn.appia.im.core.voice.VoiceChatHoster) {
        if (store.state.value.phase != VoicePhase.Idle) return
        val meetingPhase = deps.meetingPhaseProvider()
        if (meetingPhase == "inMeeting" || meetingPhase == "minimized") {
            emitUi(VoiceUiEvent("meeting_already_active"))
            return
        }
        scope.launch { runStartOutgoing(hoster) }
    }

    private suspend fun runStartOutgoing(hoster: cn.appia.im.core.voice.VoiceChatHoster) {
        endCallInFlight?.await()

        // RN :1051-1063:DB 房间行 callMsg + store 同 rid callMsg 合并（parseEffectiveRoomCallMsg）
        // AA:deps.roomCallMsgForRid 承载 DB 侧,store 侧在此合并
        val stateBefore = store.state.value
        val roomCallMsg = deps.roomCallMsgForRid(hoster.roomId)
            ?: stateBefore.rid?.takeIf { it == hoster.roomId }?.let { stateBefore.callMsg }
        if (!canStartOutgoing(StartOutgoingParams(callMsg = roomCallMsg))) {
            emitUi(VoiceUiEvent("voice_calling_alert_title"))
            return
        }

        // RN :1070-1074 requestVoiceMicrophonePermission（mic 拒绝静默 return——RN Alert 归 gate 内）
        if (!deps.requestMicPermission()) return

        val ctx = deps.sessionContext() ?: run {
            emitUi(VoiceUiEvent("voice_error_not_logged_in"))
            return
        }

        // RN :1083 channelId = Date.parse(new Date().toString()).toString() —— 毫秒时间戳字符串
        val channelId = System.currentTimeMillis().toString()
        val receivers = buildInitReceivers(hoster.receivers)

        // RN :1093-1104 占位 callMsg（calling + 被叫 out）
        val placeholderCallMsg = VoiceCallMsg(
            status = "calling",
            roomId = hoster.roomId,
            roomType = hoster.roomType,
            org = ctx.org,
            receivers = receivers,
            userStatus = receivers.map { VoiceUserStatus(username = it.receiver, name = it.name, status = "out") },
        )

        val myGeneration = bumpVoiceSessionGeneration()
        store.update {
            it.copy(
                phase = voiceTransition(VoicePhase.Idle, VoiceEvent.StartOutgoing),
                hoster = hoster.copy(receivers = receivers),
                rid = hoster.roomId,
                channelId = channelId,
                users = mapCallMsgToVoiceUsers(placeholderCallMsg),
                outgoingHint = OUTGOING_HINT_DIALING,
                uiMinimized = false,
                sessionGeneration = myGeneration,
            )
        }

        try {
            val creds = fetchAgoraTokenWithRetry(ctx.oncallContext(), channelId, HOSTER_AGORA_UID)
            if (creds == null) {
                emitUi(VoiceUiEvent("voice_error_token_failed"))
                resetSession()
                return
            }
            if (isOutgoingStartAborted(myGeneration, store.state.value)) return

            val initRes = deps.informInit(
                ctx.oncallContext(),
                InformInitBody(
                    channelId = channelId,
                    initiator = ctx.username,
                    initiatorName = ctx.userDisplayName,
                    initiatorAppiaId = ctx.userId,
                    initiatorUid = HOSTER_AGORA_UID,
                    org = ctx.org,
                    receivers = receivers,
                    roomId = hoster.roomId,
                    roomType = hoster.roomType,
                ),
            )
            if (!isInformInitSuccess(initRes)) {
                emitUi(VoiceUiEvent("voice_error_init_failed", getOncallErrorMessage(initRes)))
                resetSession()
                return
            }
            val recordId = extractRecordId(initRes) ?: run {
                emitUi(VoiceUiEvent("voice_error_init_failed", null))
                resetSession()
                return
            }
            if (isOutgoingStartAborted(myGeneration, store.state.value)) {
                // RN :1159-1172:中止链丢弃会话,但服务端 record 已建立——补发 cancel
                runCatching { deps.updateRecordStatus(ctx.oncallContext(), recordId, "cancel") }
                return
            }

            // RN :1174-1193:init 成功后的 callMsg(recordData.exist='true' + recordId)
            val callMsgAfterInit = placeholderCallMsg.copy(
                recordId = recordId,
                recordData = voiceJson.encodeToJsonElement(
                    VoiceCallRecordData.serializer(),
                    VoiceCallRecordData(
                        exist = JsonPrimitive("true"),
                        recordId = recordId,
                        channelId = channelId,
                        initiator = ctx.username,
                        initiatorName = ctx.userDisplayName,
                        initiatorUid = HOSTER_AGORA_UID.toString(),
                        receivers = receivers,
                        roomType = hoster.roomType,
                    ),
                ),
            )
            store.update {
                it.copy(recordId = recordId, callMsg = callMsgAfterInit, users = mapCallMsgToVoiceUsers(callMsgAfterInit))
            }

            val joined = joinAgoraChannel(channelId, HOSTER_AGORA_UID, ctx.oncallContext(), creds)
            if (!joined) {
                emitUi(VoiceUiEvent("voice_error_agora_join_failed"))
                endCallInternal("startOutgoingJoinFailed")
                return
            }
            store.state.value.recordId?.let { startHostOutgoingTimers(it) }
            startCallStatusPollingInternal()
        } catch (e: Exception) {
            when {
                e is OncallTokenExpiredException ->
                    emitUi(VoiceUiEvent("voice_error_token_failed"))
                e.message?.startsWith("voice_external_token") == true ->
                    emitUi(VoiceUiEvent("voice_error_token_failed", e.message))
                e.message?.startsWith("oncall_http_error") == true ->
                    emitUi(VoiceUiEvent("voice_error_init_failed", e.message))
                else -> emitUi(VoiceUiEvent("voice_error_generic", e.message))
            }
            if (store.state.value.recordId != null) {
                endCallInternal("startOutgoingFailed")
            } else {
                resetSession()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // joinAgoraChannel / token（RN :570-587 / :717-744）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :570-587 fetchAgoraJoinCredentialsWithRetry:OncallTokenExpiredError 捕获后重试一次
     * （RN invalidate 在 oncallApi 层;AA 上移为 [VoiceSessionDeps.onTokenExpired] 回调,见 deps KDoc）。
     * 返回 null = 成功响应但无 token（RN creds?.token falsy 分支）。
     */
    private suspend fun fetchAgoraTokenWithRetry(
        ctx: cn.appia.im.core.voice.OncallContext,
        channelId: String,
        uid: Long,
    ): AgoraJoinCredentials? {
        val attempt: suspend () -> AgoraJoinCredentials? =
            { extractAgoraJoinCredentials(deps.fetchAgoraToken(ctx, channelId, uid)) }
        return try {
            attempt()
        } catch (e: OncallTokenExpiredException) {
            deps.onTokenExpired()
            attempt()
        }
    }

    /**
     * RN :717-744 joinAgoraChannel:token 前置已取 → setState agoraLocalUid → 引擎 join。
     * prefetched 缺省（被叫/joinOngoing 路径）→ 现取 token（40002 重试一次,RN :723-725）;
     * 无 token → false（RN !creds?.token）。
     * 引擎实例由 [deps.rtc] 常驻（ensureAgoraRtc 的 AA 形态）,事件流订阅在 [collectRtcEvents]。
     */
    private suspend fun joinAgoraChannel(
        channelId: String,
        uid: Long,
        ctx: cn.appia.im.core.voice.OncallContext,
        prefetched: AgoraJoinCredentials? = null,
    ): Boolean {
        val creds = prefetched ?: fetchAgoraTokenWithRetry(ctx, channelId, uid) ?: return false
        store.update { it.copy(agoraLocalUid = uid) }
        rtcEventGeneration = ++agoraGeneration
        val s = store.state.value
        return deps.rtc.join(
            appId = VoiceEnv.AGORA_APP_ID,
            channelName = channelId,
            token = creds.token,
            uid = uid,
            encKey = creds.encKey,
            muted = s.muteLocal,
            speakerOn = s.speakerOn,
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Agora 事件接线（RN :629-715 ensureAgoraRtc handlers）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :618-627 runWithAgoraGeneration:代数不匹配（引擎已释放/换代）或 phase idle/ending → 丢弃。
     */
    private fun runWithAgoraGeneration(generation: Int, fn: () -> Unit) {
        if (generation != agoraGeneration) return
        val phase = store.state.value.phase
        if (phase == VoicePhase.Idle || phase == VoicePhase.Ending) return
        fn()
    }

    private suspend fun collectRtcEvents() {
        deps.rtc.events.collect { event ->
            val generation = rtcEventGeneration ?: return@collect
            when (event) {
                is VoiceRtcEvent.Joined -> runWithAgoraGeneration(generation) {
                    val s = store.state.value
                    // RN :642-649:主叫未接通（hoster && !hadRemoteParticipant）→ 回铃;否则 connecting → 接通
                    if (s.hoster != null && !s.hadRemoteParticipant) {
                        deps.rtc.startHostRingback(HOST_RINGBACK_ASSET)
                        return@runWithAgoraGeneration
                    }
                    if (s.phase == VoicePhase.Connecting) enterInCallFromAgora()
                }

                is VoiceRtcEvent.UserJoined -> runWithAgoraGeneration(generation) {
                    val s = store.state.value
                    if (!shouldAcceptRemoteUserJoined(event.uid, s.agoraLocalUid)) return@runWithAgoraGeneration
                    if (s.phase == VoicePhase.OutgoingRinging || s.phase == VoicePhase.Connecting) {
                        // RN :671-678:群聊须已有被叫在频道（groupHasCalleeInVoiceChannel）——
                        // hoster-null（被叫 connecting）同受此门（RN s.hoster?.roomType fallback 同构）
                        if (isGroupVoiceCallMsg(s.callMsg, VoiceCallMsgContextFallback(s.hoster?.roomType)) &&
                            !groupHasCalleeInVoiceChannel(s.callMsg, sessionUsername())
                        ) {
                            return@runWithAgoraGeneration
                        }
                        enterInCallFromAgora()
                    }
                }

                is VoiceRtcEvent.UserOffline -> Unit
                // RN :702-704 no-op:单聊对齐旧版,远端 offline 不触发本端 endCall,由 exist/轮询收尾。

                is VoiceRtcEvent.UserMuteAudio -> runWithAgoraGeneration(generation) {
                    val s = store.state.value
                    store.update { it.copy(users = applyRemoteMuteToUsers(s.users, event.uid, event.muted)) }
                }

                is VoiceRtcEvent.VolumeIndication -> runWithAgoraGeneration(generation) {
                    val s = store.state.value
                    val localUid = s.agoraLocalUid ?: return@runWithAgoraGeneration
                    store.update {
                        it.copy(
                            users = applyAgoraVolumeToUsers(
                                s.users,
                                event.speakers.map { AgoraVolumeSpeaker(it.uid, it.volume) },
                                localUid,
                            ),
                        )
                    }
                }

                is VoiceRtcEvent.Error -> Log.w(TAG, "agora error ${event.code} phase=${store.state.value.phase}")
            }
        }
    }

    /**
     * RN :589-616 enterInCallFromAgora:群聊需已有被叫在频道;清计时 + 停回铃;
     * outgoingRinging → ACCEPT → connecting,再 AGORA_JOINED → inCall;hadRemoteParticipant=true。
     */
    private fun enterInCallFromAgora() {
        val s = store.state.value
        if (s.hoster != null &&
            isGroupVoiceCallMsg(s.callMsg, VoiceCallMsgContextFallback(s.hoster.roomType)) &&
            !groupHasCalleeInVoiceChannel(s.callMsg, sessionUsername())
        ) {
            return
        }
        deps.timers.clear()
        deps.rtc.stopHostRingback()
        var phase = s.phase
        if (phase == VoicePhase.OutgoingRinging) phase = voiceTransition(phase, VoiceEvent.Accept)
        val next = voiceTransition(phase, VoiceEvent.AgoraJoined)
        store.update { it.copy(phase = next, hadRemoteParticipant = true, outgoingHint = null) }
        if (next == VoicePhase.InCall) onEnteredInCall()
    }

    /** RN :538-557 onEnteredInCall:轮询启动;CallKit/录音保持归后续任务。 */
    private fun onEnteredInCall() {
        startCallStatusPollingInternal()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 计时器（RN :350-399 hostOutgoingTimerCallbacks）
    // ─────────────────────────────────────────────────────────────────────

    private fun startHostOutgoingTimers(recordId: String) {
        deps.timers.startHostOutgoingTimers(
            recordId,
            on30s = { onHostHint30s(recordId) },
            on65s = { onHostEnd65s(recordId) },
        )
    }

    /** RN :350-371 on30s:主叫会话内且仍无人接通 → not_connect 提示。 */
    private fun onHostHint30s(recordId: String) {
        val s = store.state.value
        if (s.hoster == null) return
        if (s.phase != VoicePhase.OutgoingRinging && s.phase != VoicePhase.Connecting) return
        if (hostOutgoingNobodyConnected(s.callMsg, s.hoster, s.hadRemoteParticipant)) {
            store.update { it.copy(outgoingHint = OUTGOING_HINT_NOT_CONNECT) }
        }
    }

    /** RN :372-398 on65s:主叫会话内且仍无人接通 → endCall(hostTimeout65s)。 */
    private fun onHostEnd65s(recordId: String) {
        val s = store.state.value
        if (s.hoster == null) return
        if (s.phase != VoicePhase.OutgoingRinging && s.phase != VoicePhase.Connecting && s.phase != VoicePhase.InCall) return
        if (hostOutgoingNobodyConnected(s.callMsg, s.hoster, s.hadRemoteParticipant)) {
            scope.launch { endCallInternal("hostTimeout65s") }
        }
    }

    /** RN :335-348 hostOutgoingNobodyConnected:群聊 = 无被叫在频道;单聊 = !hadRemoteParticipant。 */
    private fun hostOutgoingNobodyConnected(
        callMsg: VoiceCallMsg?,
        hoster: cn.appia.im.core.voice.VoiceChatHoster,
        hadRemoteParticipant: Boolean,
    ): Boolean {
        val voiceCtx = VoiceCallMsgContextFallback(hoster.roomType)
        if (isGroupVoiceCallMsg(callMsg, voiceCtx)) {
            return !groupHasCalleeInVoiceChannel(callMsg, sessionUsername())
        }
        return !hadRemoteParticipant
    }

    // ─────────────────────────────────────────────────────────────────────
    // 轮询（RN :399-452）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :446-452 startCallStatusPolling:立即一次 + 间隔轮询（brief Task 8 开放为 public）。
     * 单次轮询（RN :408-444）:ACTIVE_CALL_POLL_PHASES 守卫 → getRecordStatusV2 →
     * [onPollResult] → syncCallMsgFromRoom 全裁定链。
     */
    fun startCallStatusPolling() {
        stopCallStatusPolling()
        pollCallRecordStatusOnce()
        pollJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(CALL_STATUS_POLL_MS)
                pollCallRecordStatusOnce()
            }
        }
    }

    private fun startCallStatusPollingInternal() = startCallStatusPolling()

    /** RN :401-406 stopCallStatusPolling。 */
    fun stopCallStatusPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollCallRecordStatusOnce() {
        val s = store.state.value
        if (s.phase !in ACTIVE_CALL_POLL_PHASES) {
            stopCallStatusPolling()
            return
        }
        val recordId = s.recordId ?: return
        val rid = s.rid ?: return
        scope.launch {
            val ctx = deps.sessionContext() ?: return@launch
            val recordOrg = s.callMsg?.org ?: ctx.org
            val res = runCatching { deps.getRecordStatusV2(ctx.oncallContext(), recordId, recordOrg) }
                .getOrNull() ?: return@launch
            if (!isOncallSuccess(res)) return@launch
            onPollResult(rid, res)
        }
    }

    /**
     * 轮询结果接缝（RN :408-444 pollCallRecordStatusOnce 后半）:callMsgFromOncallRecordResponse →
     * syncCallMsgFromRoom(rid, raw, onCallStatus = isRoomCallMsgActive(parsed, voiceCtx))。
     * RN JSON.stringify(parsed) → AA VoiceCallMsg 序列化（parseCallMsg 双向兼容）。
     */
    fun onPollResult(rid: String, res: OncallResponse) {
        val parsed = callMsgFromOncallRecordResponse(res) ?: return
        val s = store.state.value
        val voiceCtx = VoiceCallMsgContextFallback(
            s.hoster?.roomType ?: resolveVoiceCallRoomType(s.callMsg, null),
        )
        val raw = voiceJson.encodeToString(VoiceCallMsg.serializer(), parsed)
        syncCallMsgFromRoom(rid, raw, isRoomCallMsgActive(parsed, voiceCtx))
    }

    // ─────────────────────────────────────────────────────────────────────
    // 被叫链（RN :1244-1583 / Task 8）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :1244-1308 presentIncoming。phase != idle 跳过（:1247-1250）→
     * callMsg = 入参 ?? state.callMsg,recordData.channelId 缺 → 跳过（:1252-1256）→
     * PRESENT_INCOMING 落态（callMsg/rid/recordId/channelId/users,sessionGeneration bump）→
     * notifyHost fire-and-forget（:1282-1289）→ 轮询启动（:1291）→ 65s 被叫计时（:1293-1305,
     * phase+recordId 守卫 → 停铃 + resetSession）→ autoAccept → acceptIncoming（:1307）。
     * RN 铃声由 UI VoiceCallHost 驱动（差异裁定,类 KDoc）;AA 在此 start。
     */
    fun presentIncoming(autoAccept: Boolean, callMsg: VoiceCallMsg?, rid: String?) {
        if (!presentIncomingBody(callMsg, rid)) return
        if (autoAccept) {
            scope.launch { acceptIncomingInternal() }
        }
    }

    /**
     * RN `await presentIncoming({autoAccept:true})` 的 await 面（:1307）:
     * joinExisting 分支 9b / otherIncoming 切换链在 await 后才检查 phase。
     * 落态同步完成,autoAccept 的 acceptIncoming 全链 await 完成后返回
     * （accept 成功 → connecting;失败 → reset 回 idle）。
     */
    private suspend fun presentIncomingInternal(autoAccept: Boolean, callMsg: VoiceCallMsg?, rid: String?) {
        if (!presentIncomingBody(callMsg, rid)) return
        if (autoAccept) {
            acceptIncomingInternal()
        }
    }

    /** :1247-1305 落态 + notifyHost + 轮询 + 65s 计时 + 铃声;accept 分发归调用方（同步/异步两形态）。 */
    private fun presentIncomingBody(callMsg: VoiceCallMsg?, rid: String?): Boolean {
        if (store.state.value.phase != VoicePhase.Idle) return false
        val parsed = callMsg ?: store.state.value.callMsg
        if (parsed?.recordDataObj?.channelId?.isNotEmpty() != true) return false

        val myGeneration = bumpVoiceSessionGeneration()
        store.update {
            it.copy(
                phase = voiceTransition(VoicePhase.Idle, VoiceEvent.PresentIncoming),
                callMsg = parsed,
                rid = rid ?: parsed.roomId,
                recordId = parsed.recordId ?: parsed.recordDataObj?.recordId,
                channelId = parsed.recordDataObj?.channelId,
                hoster = null,
                users = mapCallMsgToVoiceUsers(parsed),
                incomingUiExpanded = false,
                sessionGeneration = myGeneration,
            )
        }

        val notifyRecordId = parsed.recordId ?: parsed.recordDataObj?.recordId
        if (notifyRecordId != null) {
            scope.launch {
                val ctx = deps.sessionContext() ?: return@launch
                runCatching { deps.getNotify(ctx.oncallContext(), ctx.org, notifyRecordId, ctx.username) }
            }
        }

        startCallStatusPollingInternal()

        val incomingRecordId = parsed.recordId?.takeIf { it.isNotEmpty() }
            ?: parsed.recordDataObj?.recordId?.takeIf { it.isNotEmpty() }
        if (incomingRecordId != null) {
            deps.timers.startCalleeIncomingTimer(incomingRecordId) {
                val s = store.state.value
                if (s.phase == VoicePhase.IncomingRinging && s.recordId == incomingRecordId) {
                    deps.ringer.stop()
                    resetSession()
                }
            }
        }

        deps.ringer.start()
        return true
    }

    /** RN :1310-1314 openIncomingConfirm:incomingRinging 才展开。 */
    fun openIncomingConfirm() {
        if (store.state.value.phase != VoicePhase.IncomingRinging) return
        store.update { it.copy(incomingUiExpanded = true) }
    }

    /** RN :1316-1319 closeIncomingConfirm。 */
    fun closeIncomingConfirm() {
        store.update { it.copy(incomingUiExpanded = false) }
    }

    /** RN :1321-1409 acceptIncoming（麦克风权限归 Task 12 UI 前置）。 */
    fun acceptIncoming() {
        scope.launch { acceptIncomingInternal() }
    }

    private suspend fun acceptIncomingInternal() {
        // RN :1323-1325:先停计时/铃声/收起确认面板,与后续守卫结果无关
        deps.timers.clear()
        deps.ringer.stop()
        store.update { it.copy(incomingUiExpanded = false) }

        // RN :1326-1330:idle 但 callMsg 仍在（跨进程/铃停后的补接听）→ 重新 PRESENT_INCOMING
        var phase = store.state.value.phase
        if (phase == VoicePhase.Idle && store.state.value.callMsg != null) {
            store.update { it.copy(phase = voiceTransition(VoicePhase.Idle, VoiceEvent.PresentIncoming)) }
            phase = store.state.value.phase
        }
        if (phase != VoicePhase.IncomingRinging) return

        // RN :1334-1338 requestVoiceMicrophonePermission（mic 拒绝静默 return）
        if (!deps.requestMicPermission()) return

        val ctx = deps.sessionContext()
        val s = store.state.value
        val recordId = s.recordId
        val channelId = s.channelId
        val callMsg = s.callMsg
        if (ctx == null || recordId == null || channelId == null || callMsg == null) {
            emitUi(VoiceUiEvent("voice_error_generic"))
            return
        }

        // RN :1345-1351:shouldPresentIncomingForUser 复核（dismissed 等）
        if (!shouldPresentIncomingForUser(callMsg, ctx.username, null, deps.dismissed)) {
            emitUi(VoiceUiEvent("voice_error_generic"))
            resetSession()
            return
        }

        // RN :1353-1358:本人已在其他设备接听
        if (isAnsweredOnOtherDevice(callMsg, ctx.username)) {
            resetSession()
            return
        }

        val uid = try {
            resolveAgoraJoinUid(callMsg, ctx.username)
        } catch (e: VoiceUidResolveException) {
            emitUi(VoiceUiEvent("voice_error_token_failed"))
            resetSession()
            return
        }

        store.update { it.copy(phase = voiceTransition(phase, VoiceEvent.Accept)) }

        val deviceId = deps.deviceIdProvider()
        try {
            deps.join(
                ctx.oncallContext(),
                JoinBody(
                    // 跨主体来电记录在对方 org 上,join 必须用记录所在 org（RN :1373-1381）
                    org = callMsg.org ?: ctx.org,
                    recordId = recordId,
                    username = ctx.username,
                    userUid = uid,
                    deviceId = deviceId?.takeIf { it.isNotEmpty() },
                ),
            )

            // 跨主体 token 必须用记录 org 签发,否则进的是本机 org 的频道空间（RN :1384-1390）
            val joinCtx = if (callMsg.org != null && callMsg.org != ctx.org) {
                ctx.oncallContext().copy(org = callMsg.org!!)
            } else {
                ctx.oncallContext()
            }
            val joined = joinAgoraChannel(channelId, uid, joinCtx)
            if (!joined) {
                emitUi(VoiceUiEvent("voice_error_agora_join_failed"))
                resetSession()
            }
        } catch (e: Exception) {
            emitUi(VoiceUiEvent("voice_error_generic", e.message))
            resetSession()
        }
    }

    /** RN :1411-1439 rejectIncoming。 */
    fun rejectIncoming() {
        scope.launch {
            deps.timers.clear()
            store.update { it.copy(incomingUiExpanded = false) }
            deps.ringer.stop()
            val state = store.state.value
            val ctx = deps.sessionContext()
            val recordId = state.recordId

            if (ctx != null && recordId != null) {
                runCatching { deps.reject(ctx.oncallContext(), recordId, ctx.username) }
            }

            if (recordId != null) {
                val localUsername = sessionUsername() ?: ctx?.username
                if (localUsername != null) {
                    deps.dismissed.markVoiceCallDismissed(recordId, state.callMsg, localUsername)
                }
            }

            store.update { it.copy(phase = voiceTransition(state.phase, VoiceEvent.Reject)) }
            resetSession()
        }
    }

    /**
     * RN :806-1032 syncCallMsgFromRoom——房间 callMsg 同步的裁定级联,逐段移植
     * （段号 = RN 行号区间）:
     * 1. :814-817 他房间且活动相位 → 整体忽略。
     * 2. :825-837 结束快照命中 otherIncoming 队列条目 → 清队列。
     * 3. :839-855 通话中来第二路 → 入 otherIncoming 队列后 return。
     * 4. :857-869 落 callMsg/rid/users（parsed null 时 incomingRinging 保留 callMsg）。
     * 5. :871-879 主叫振铃/连接中,群组新受邀人增长 → 重锚主叫计时。
     * 6. :881-897 主叫振铃/连接中,群组已有被叫进频道/单聊对方已 in → enterInCallFromAgora。
     * 7. :899-912 idle 收活动 callMsg → presentIncoming(autoAccept=false)。
     * 8. :914-936 incomingRinging 快照复核:他设备已接 → 复位;不再响铃 →
     *    残缺快照抑制（同 recordId 的 calling/talking 缺 exist/userStatus → 恢复旧数据）或复位。
     * 9. :938-947 主叫 stale-end 同步（旧 record 的 end）→ 忽略。
     * 10. :949-1031 shouldEndCallFromCallMsg → incomingRinging 停铃复位 / 其余相位 endCall。
     */
    fun syncCallMsgFromRoom(rid: String, callMsgRaw: String?, onCallStatus: Boolean? = null) {
        val parsed = parseCallMsg(callMsgRaw)
        val state = store.state.value

        // RN :814-817（段 1）
        if (state.rid != null && state.rid != rid && state.phase != VoicePhase.Idle) return

        val username = sessionUsername()

        // RN :825-837（段 2）
        if (parsed != null && isCallRecordExplicitlyEnded(parsed) && state.otherIncoming != null) {
            val otherId = state.otherIncoming!!.callMsg.recordId
                ?: state.otherIncoming!!.callMsg.recordDataObj?.recordId
            val endedId = parsed.recordId ?: parsed.recordDataObj?.recordId
            if (otherId != null && endedId != null && otherId == endedId) {
                store.update { it.copy(otherIncoming = null) }
            }
        }

        // RN :839-855（段 3）
        if (parsed != null && username != null &&
            shouldQueueOtherIncoming(state.phase, state.recordId, parsed, username, onCallStatus)
        ) {
            store.update { it.copy(otherIncoming = OtherIncoming(callMsg = parsed, rid = rid)) }
            return
        }

        // RN :857-869（段 4）——写入用入口快照 state 的字段做保留判定
        val prevInviteeCount = countGroupVoiceInvitees(state.callMsg)
        store.update {
            it.copy(
                callMsg = parsed ?: state.callMsg.takeIf { _ -> state.phase == VoicePhase.IncomingRinging },
                rid = parsed?.let { rid } ?: state.rid,
                users = if (parsed != null) {
                    mergeVoiceUsersPreservingRtcState(mapCallMsgToVoiceUsers(parsed), state.users)
                } else {
                    state.users
                },
            )
        }

        // RN :871-879（段 5）
        if (parsed != null && state.hoster != null && state.recordId != null &&
            (state.phase == VoicePhase.OutgoingRinging || state.phase == VoicePhase.Connecting) &&
            countGroupVoiceInvitees(parsed) > prevInviteeCount
        ) {
            startHostOutgoingTimers(state.recordId!!)
        }

        // RN :881-897（段 6）
        if (parsed != null && state.hoster != null && username != null &&
            (state.phase == VoicePhase.OutgoingRinging || state.phase == VoicePhase.Connecting)
        ) {
            val voiceCtx = VoiceCallMsgContextFallback(state.hoster.roomType)
            val shouldEnter = if (isGroupVoiceCallMsg(parsed, voiceCtx)) {
                groupHasCalleeInVoiceChannel(parsed, username)
            } else {
                isDirectVoicePeerInCall(parsed, username, state.hoster)
            }
            if (shouldEnter) enterInCallFromAgora()
        }

        // RN :899-912（段 7）
        if (parsed != null && store.state.value.phase == VoicePhase.Idle) {
            val localName = sessionUsername()
            if (localName != null &&
                !isAnsweredOnOtherDevice(parsed, localName) &&
                shouldPresentIncomingForUser(parsed, localName, onCallStatus, deps.dismissed)
            ) {
                presentIncoming(autoAccept = false, callMsg = parsed, rid = rid)
            }
        }

        // RN :914-936（段 8）
        if (store.state.value.phase == VoicePhase.IncomingRinging && parsed != null) {
            val localName = sessionUsername()
            if (localName != null) {
                if (isAnsweredOnOtherDevice(parsed, localName)) {
                    deps.ringer.stop()
                    resetSession()
                    return
                }
                val sameIncomingRid = store.state.value.rid == rid
                val stillRing = shouldPresentIncomingForUser(
                    parsed,
                    localName,
                    if (sameIncomingRid) null else onCallStatus,
                    deps.dismissed,
                )
                if (!stillRing) {
                    // 残缺快照抑制:同 recordId 的瞬时快照不视为停止振铃,恢复之前的完整数据
                    val parsedRecordId = parsed.recordId ?: parsed.recordDataObj?.recordId
                    if (parsedRecordId != null &&
                        store.state.value.recordId == parsedRecordId &&
                        !isCallRecordExplicitlyEnded(parsed) &&
                        (parsed.status == "calling" || parsed.status == "talking")
                    ) {
                        store.update { it.copy(callMsg = state.callMsg, users = state.users) }
                        return
                    }
                    deps.ringer.stop()
                    resetSession()
                    return
                }
            }
        }

        val localUsername = sessionUsername()
        val voiceCtx = VoiceCallMsgContextFallback(
            state.hoster?.roomType ?: resolveVoiceCallRoomType(state.callMsg, null),
        )

        // RN :949-957（段 9）
        if (parsed != null && isStaleEndedCallSyncForHost(state.phase, state.hoster != null, state.recordId, parsed)) {
            return
        }

        // RN :959-1031（段 10）
        if (parsed != null &&
            shouldEndCallFromCallMsg(parsed, localUsername, voiceCtx) &&
            (store.state.value.phase == VoicePhase.InCall ||
                store.state.value.phase == VoicePhase.Connecting ||
                store.state.value.phase == VoicePhase.OutgoingRinging ||
                store.state.value.phase == VoicePhase.IncomingRinging)
        ) {
            val endedId = parsed.recordId ?: parsed.recordDataObj?.recordId
            if (state.recordId != null && (endedId == null || state.recordId != endedId)) return
            // 主叫振铃中,同步仍是 calling/talking（非 timeout/cancel 的显式结束）→ 不收尾
            if (state.hoster != null && state.phase == VoicePhase.OutgoingRinging &&
                parsed.status != "timeout" && parsed.status != "cancel" &&
                (parsed.status == "calling" || parsed.status == "talking")
            ) {
                return
            }
            if (store.state.value.phase == VoicePhase.Idle || store.state.value.phase == VoicePhase.Ending) return
            val latest = store.state.value
            if ((latest.phase == VoicePhase.InCall || latest.phase == VoicePhase.Connecting ||
                    latest.phase == VoicePhase.OutgoingRinging) &&
                resolveVoiceCallRoomType(parsed, voiceCtx) == "d" && localUsername != null
            ) {
                val others = parsed.userStatus?.filter { !it.username.isNullOrEmpty() && it.username != localUsername }
                val hasExplicitPeerEnd = others?.any { it.operation == "hangup" || it.operation == "reject" } == true
                if (latest.hoster != null &&
                    (latest.phase == VoicePhase.OutgoingRinging || latest.phase == VoicePhase.Connecting) &&
                    !hasExplicitPeerEnd
                ) {
                    return
                }
                val peerStillActive = isDirectVoicePeerInCall(parsed, localUsername, latest.hoster) || latest.hadRemoteParticipant
                if (!hasExplicitPeerEnd && peerStillActive) return
            }
            if (store.state.value.phase == VoicePhase.IncomingRinging) {
                deps.ringer.stop()
                resetSession()
            } else {
                // RN :1030 `void get().endCall('syncCallMsg')` —— fire-and-forget
                scope.launch { endCallInternal("syncCallMsg") }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // otherIncoming 六动作（RN :1441-1583）
    // ─────────────────────────────────────────────────────────────────────

    /** RN :1441-1461 rejectOtherIncoming:oncall reject 带 otherIncoming.callMsg.org。 */
    fun rejectOtherIncoming() {
        scope.launch {
            val other = store.state.value.otherIncoming ?: return@launch
            val ctx = deps.sessionContext()
            val recordId = other.callMsg.recordId ?: other.callMsg.recordDataObj?.recordId
            if (ctx != null && recordId != null) {
                runCatching { deps.reject(ctx.oncallContext(), recordId, ctx.username, other.callMsg.org) }
            }
            store.update { it.copy(otherIncoming = null, otherIncomingAlertType = null) }
        }
    }

    /** RN :1463-1489 beginOtherIncomingAccept:alertType 判定,1/2/3 弹窗否则直接切换。 */
    fun beginOtherIncomingAccept() {
        val state = store.state.value
        if (state.otherIncoming == null) return
        scope.launch {
            val ctx = deps.sessionContext() ?: return@launch
            val otherStatus = store.state.value.otherIncoming?.callMsg?.status ?: return@launch
            val alertType = getOtherIncomingAlertType(
                phase = store.state.value.phase,
                currentRecordId = store.state.value.recordId,
                currentOrg = ctx.org,
                otherCallMsg = store.state.value.otherIncoming!!.callMsg,
                intent = if (otherStatus == "talking") "join" else "accept",
            )
            if (alertType != null) {
                store.update { it.copy(otherIncomingAlertType = alertType) }
                return@launch
            }
            switchToOtherIncomingInternal()
        }
    }

    /** RN :1491-1515 beginOtherIncomingJoin:intent 固定 join。 */
    fun beginOtherIncomingJoin() {
        val state = store.state.value
        if (state.otherIncoming == null) return
        scope.launch {
            val ctx = deps.sessionContext() ?: return@launch
            val alertType = getOtherIncomingAlertType(
                phase = store.state.value.phase,
                currentRecordId = store.state.value.recordId,
                currentOrg = ctx.org,
                otherCallMsg = store.state.value.otherIncoming!!.callMsg,
                intent = "join",
            )
            if (alertType != null) {
                store.update { it.copy(otherIncomingAlertType = alertType) }
                return@launch
            }
            switchToOtherIncomingInternal()
        }
    }

    /** RN :1517-1524 cancelOtherIncomingAlert:1/3 → 顺带拒接;2 → 仅清弹窗。 */
    fun cancelOtherIncomingAlert() {
        val alertType = store.state.value.otherIncomingAlertType
        if (alertType == 1 || alertType == 3) {
            rejectOtherIncoming()
            return
        }
        store.update { it.copy(otherIncomingAlertType = null) }
    }

    /**
     * RN :1526-1559 confirmOtherIncomingAlert。type-3（跨主体）:
     * 快照 otherIncoming → 清队列 → endCall → delay(400) → switchToVoiceOrg(targetOrg) →
     * 失败 toast voice_crossOrg_switchUnavailable;成功 presentIncoming(autoAccept=true)。
     * 其余 type → switchToOtherIncoming。
     */
    fun confirmOtherIncomingAlert() {
        val snapshot = store.state.value.otherIncoming ?: return
        val alertType = store.state.value.otherIncomingAlertType ?: return
        scope.launch {
            store.update { it.copy(otherIncomingAlertType = null) }

            if (alertType == 3) {
                val targetOrg = snapshot.callMsg.org ?: ""
                store.update { it.copy(otherIncoming = null, uiMinimized = false) }
                endCallInternal("confirmOtherIncomingOrgSwitch")
                kotlinx.coroutines.delay(OTHER_INCOMING_SWITCH_DELAY_MS)
                val switched = deps.switchToVoiceOrg(targetOrg)
                if (!switched) {
                    emitUi(VoiceUiEvent("voice_crossOrg_switchUnavailable"))
                    return@launch
                }
                presentIncoming(autoAccept = true, callMsg = snapshot.callMsg, rid = snapshot.rid)
                return@launch
            }

            switchToOtherIncomingInternal()
        }
    }

    /** RN :1561-1583 switchToOtherIncoming:快照 → 清队列 → endCall → delay(400) → presentIncoming(autoAccept=true)。 */
    fun switchToOtherIncoming() {
        if (store.state.value.otherIncoming == null) return
        scope.launch { switchToOtherIncomingInternal() }
    }

    private suspend fun switchToOtherIncomingInternal() {
        val other = store.state.value.otherIncoming ?: return
        store.update { it.copy(otherIncoming = null, otherIncomingAlertType = null, uiMinimized = false) }
        endCallInternal("switchToOtherIncoming")
        kotlinx.coroutines.delay(OTHER_INCOMING_SWITCH_DELAY_MS)
        presentIncoming(autoAccept = true, callMsg = other.callMsg, rid = other.rid)
    }

    // ─────────────────────────────────────────────────────────────────────
    // joinOngoing / joinExisting / startFromMessage（RN :1800-2078）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :1800-1881 joinOngoingCall:prepare → 守卫（answeredOnOtherDevice/recordId/channelId/uid）→
     * JOIN_ONGOING 直落 connecting（无响铃计时）→ join（ctx.org）→ agora join（ctx）。
     * 失败路径:toast + resetSession（uid 解析失败仅 toast,RN :1828-1832 无 reset——
     * AA 对齐 RN 不 reset;join/agora 失败 toast + resetSession）。
     */
    fun joinOngoingCall(rid: String, callMsg: VoiceCallMsg) {
        if (store.state.value.phase != VoicePhase.Idle) return
        scope.launch { joinOngoingCallInternal(rid, callMsg) }
    }

    /** joinOngoingCall 的 suspend 核心:joinExisting 分支 9a `await joinOngoingCall` 的 await 面。 */
    private suspend fun joinOngoingCallInternal(rid: String, callMsg: VoiceCallMsg) {
        // RN :1806-1810 requestVoiceMicrophonePermission（mic 拒绝 toast voice_error_mic_denied + return）
        if (!deps.requestMicPermission()) {
            emitUi(VoiceUiEvent("voice_error_mic_denied"))
            return
        }
        val ctx = deps.sessionContext() ?: run {
            emitUi(VoiceUiEvent("voice_error_generic"))
            return
        }
        if (isAnsweredOnOtherDevice(callMsg, ctx.username)) return

        val preparedCallMsg = prepareCallMsgForRoomVoiceJoin(callMsg, ctx.username)
        val recordId = preparedCallMsg.recordId ?: preparedCallMsg.recordDataObj?.recordId
        val channelId = preparedCallMsg.recordDataObj?.channelId
        if (recordId.isNullOrEmpty() || channelId.isNullOrEmpty()) {
            emitUi(VoiceUiEvent("voice_error_generic"))
            return
        }

        val uid = try {
            resolveAgoraJoinUid(preparedCallMsg, ctx.username)
        } catch (e: VoiceUidResolveException) {
            emitUi(VoiceUiEvent("voice_error_token_failed"))
            return
        }

        val myGeneration = bumpVoiceSessionGeneration()
        store.update {
            it.copy(
                phase = voiceTransition(VoicePhase.Idle, VoiceEvent.JoinOngoing),
                callMsg = preparedCallMsg,
                rid = rid,
                recordId = recordId,
                channelId = channelId,
                hoster = null,
                users = mapCallMsgToVoiceUsers(preparedCallMsg),
                incomingUiExpanded = false,
                uiMinimized = false,
                sessionGeneration = myGeneration,
            )
        }
        startCallStatusPollingInternal()

        val deviceId = deps.deviceIdProvider()
        try {
            deps.join(
                ctx.oncallContext(),
                JoinBody(
                    org = ctx.org,
                    recordId = recordId,
                    username = ctx.username,
                    userUid = uid,
                    deviceId = deviceId?.takeIf { it.isNotEmpty() },
                ),
            )
            val joined = joinAgoraChannel(channelId, uid, ctx.oncallContext())
            if (!joined) {
                emitUi(VoiceUiEvent("voice_error_agora_join_failed"))
                resetSession()
            }
        } catch (e: Exception) {
            emitUi(VoiceUiEvent("voice_error_generic", e.message))
            resetSession()
        }
    }

    /** RN joinExistingVoiceCall.ts:10-18 JoinExistingVoiceOptions。 */
    data class JoinExistingOptions(
        val callMsgRaw: String? = null,
        val roomType: String? = null,
        val serverOnCallStatus: Boolean? = null,
        val joinFromRoomBanner: Boolean = false,
    )

    /**
     * RN :1883-2078 joinExisting。分支序 verbatim:
     * 1. :1886-1889 incomingRinging 同 rid → acceptIncoming。
     * 2. :1891-1915 活动 phase + recordId → 读 DB raw,shouldQueueOtherIncoming → 入队;return。
     * 3. :1917-1940 idle:DB raw + onCallStatus 回退合并（mergeJoinExistingCallMsgRaw）。
     * 4. :1942-1953 无 username → not_logged_in;parsed null → generic。
     * 5. :1959-1983 channelId 缺 → getRecordStatusV2 刷新一次;仍缺 → generic。
     * 6. :1985-1990 blockVoiceJoinIfAnsweredOnOtherDevice（banner 才弹窗）→ 中止。
     * 7. :1992-2018 joinOngoing/presentIncoming 判定（横幅/主动重进资格）;
     *    banner 重进且首判双 false → 再刷一次 record（同 :2010-2016）。
     * 8. :2020-2023 双 false 且 callMsg 非活动 → generic。
     * 9. :2025-2031 joinOngoing → joinOngoingCall;:2033-2038 presentIncoming →
     *    syncCallMsgFromRoom + presentIncoming(autoAccept=true);否则 generic。
     */
    fun joinExisting(rid: String, options: JoinExistingOptions = JoinExistingOptions()) {
        // RN joinExisting 为 async;外部调用方均不依赖其完成时序 → fire-and-forget
        scope.launch { joinExistingInternal(rid, options) }
    }

    /**
     * joinExisting 的 suspend 核心（RN :1883-2078 全分支）。RN 调用方中
     * startFromMessage（:1743/:1748）await 本链后才检查 phase——AA 同构:
     * [startFromMessage] 经 launch + await 本函数实现「join 上则后续入口跳过」。
     */
    private suspend fun joinExistingInternal(rid: String, options: JoinExistingOptions = JoinExistingOptions()) {
        val state = store.state.value

        // RN :1886-1889（分支 1）
        if (state.phase == VoicePhase.IncomingRinging && state.rid == rid) {
            acceptIncomingInternal()
            return
        }

        // RN :1891-1915（分支 2）:读 DB raw 由 try/catch-ignore 包裹（:1896-1913）
        if (state.phase != VoicePhase.Idle) {
            val recordId = state.recordId
            if (recordId != null) {
                val raw = runCatching { deps.roomCallMsgRawForRid(rid) }.getOrNull()
                val parsed = parseCallMsg(raw)
                val username = sessionUsername()
                if (parsed != null && username != null &&
                    shouldQueueOtherIncoming(state.phase, recordId, parsed, username, null)
                ) {
                    store.update { it.copy(otherIncoming = OtherIncoming(callMsg = parsed, rid = rid)) }
                }
            }
            return
        }

        // RN :1917-1929（分支 3）:DB raw + onCallStatus 读体 try/catch-ignore（:1920-1929）。
        // RN 单个 try 同时覆盖两读,catch 显式置 dbCallMsgRaw = undefined（已赋值也作废）;
        // AA 同构:onFailure 重置,两读体抛错时皆空。
        var dbCallMsgRaw: String? = null
        var serverOnCallStatus: Boolean? = null
        runCatching {
            dbCallMsgRaw = deps.roomCallMsgRawForRid(rid)
            serverOnCallStatus = options.serverOnCallStatus ?: deps.roomOnCallStatusForRid(rid)
        }.onFailure { dbCallMsgRaw = null }

        val storeCallMsg = state.rid?.takeIf { it == rid }?.let { state.callMsg }
        // RN mergeJoinExistingCallMsgRaw:banner 与 store 取富,再与 DB 取富
        val fromBannerStore = pickRicherRoomCallMsgRaw(options.callMsgRaw, storeCallMsg)
        val mergedRaw = pickRicherRoomCallMsgRaw(dbCallMsgRaw, parseCallMsg(fromBannerStore) ?: storeCallMsg)
        var parsed: VoiceCallMsg? = parseCallMsg(mergedRaw)

        // RN :1931-1953（分支 4/5）
        val username = sessionUsername()?.trim()
        if (username.isNullOrEmpty()) {
            emitUi(VoiceUiEvent("voice_error_not_logged_in"))
            return
        }
        if (parsed == null) {
            emitUi(VoiceUiEvent("voice_error_generic"))
            return
        }
        var msg: VoiceCallMsg = parsed

        if (msg.recordDataObj?.channelId.isNullOrEmpty()) {
            val recordId = msg.recordId ?: msg.recordDataObj?.recordId
            if (recordId != null) {
                val ctx = deps.sessionContext()
                if (ctx != null) {
                    val refreshed = runCatching {
                        deps.getRecordStatusV2(ctx.oncallContext(), recordId, msg.org ?: ctx.org)
                    }.getOrNull()
                    callMsgFromOncallRecordResponse(refreshed)?.let { msg = it }
                }
            }
        }

        if (msg.recordDataObj?.channelId.isNullOrEmpty()) {
            emitUi(VoiceUiEvent("voice_error_generic"))
            return
        }

        // RN :1985-1990（分支 6）:他设备已接听 → 中止（banner 场景弹 message_voiceOtherDeviceAccepted）
        if (isAnsweredOnOtherDevice(msg, username)) {
            if (options.joinFromRoomBanner) {
                emitUi(VoiceUiEvent("message_voiceOtherDeviceAccepted"))
            }
            return
        }

        // RN :1992-2018（分支 7）
        val roomType = options.roomType ?: msg.roomType ?: resolveVoiceCallRoomType(msg, null)
        val isVoluntaryBannerRejoin: (VoiceCallMsg) -> Boolean = { callMsg ->
            options.joinFromRoomBanner && isVoluntaryGroupRejoinEligible(callMsg, username, roomType)
        }
        val shouldJoinOngoingFromCallMsg: (VoiceCallMsg) -> Boolean = { callMsg ->
            if (shouldShowRoomVoiceJoinBanner(callMsg, username, roomType, null, serverOnCallStatus)) {
                true
            } else {
                isVoluntaryBannerRejoin(callMsg) && isRoomCallMsgActive(callMsg, null)
            }
        }

        var joinOngoing = shouldJoinOngoingFromCallMsg(msg)
        var presentIncoming = shouldPresentIncomingForUser(msg, username, serverOnCallStatus, deps.dismissed)

        if (!joinOngoing && !presentIncoming && isVoluntaryBannerRejoin(msg)) {
            val recordId = msg.recordId ?: msg.recordDataObj?.recordId
            val recordOrg = msg.org
            val ctx = deps.sessionContext()
            if (ctx != null && recordId != null) {
                val refreshed = runCatching {
                    deps.getRecordStatusV2(ctx.oncallContext(), recordId, recordOrg ?: ctx.org)
                }.getOrNull()
                val refreshedMsg = callMsgFromOncallRecordResponse(refreshed)
                if (refreshedMsg != null) {
                    msg = refreshedMsg
                    if (msg.recordDataObj?.channelId.isNullOrEmpty()) {
                        emitUi(VoiceUiEvent("voice_error_generic"))
                        return
                    }
                    joinOngoing = shouldJoinOngoingFromCallMsg(msg)
                    presentIncoming = shouldPresentIncomingForUser(msg, username, serverOnCallStatus, deps.dismissed)
                    if (isAnsweredOnOtherDevice(msg, username)) {
                        if (options.joinFromRoomBanner) {
                            emitUi(VoiceUiEvent("message_voiceOtherDeviceAccepted"))
                        }
                        return
                    }
                }
            }
        }

        // RN :2020-2023（分支 8）
        if (!joinOngoing && !presentIncoming && !isRoomCallMsgActive(msg, null)) {
            emitUi(VoiceUiEvent("voice_error_generic"))
            return
        }

        // RN :2025-2031（分支 9a）:await joinOngoingCall
        if (joinOngoing) {
            joinOngoingCallInternal(rid, msg)
            return
        }

        // RN :2033-2038（分支 9b）:sync + await presentIncoming(autoAccept=true)
        if (presentIncoming) {
            syncCallMsgFromRoom(rid, mergedRaw)
            presentIncomingInternal(autoAccept = true, callMsg = msg, rid = rid)
            return
        }

        emitUi(VoiceUiEvent("voice_error_generic"))
    }

    /**
     * RN :1734-1798 startFromMessage（消息点击入口）。msgData.status ∈ calling/talking 对应
     * RN msgData.type（AA 签名收 VoiceCallMsg,status 承载 type 语义）:
     * 活动 phase → calling/talking 走 joinExisting;idle → calling/talking 先 await joinExisting
     * （:1743-1748,join 上则后续入口整体跳过;join 不上且 calling 再补被叫呈现,直读 DB raw 复核）;
     * 其余 → receivers 回退 startOutgoing。全链 async → AA launch 包裹。
     */
    fun startFromMessage(rid: String, msgData: VoiceCallMsg) {
        val isCallingOrTalking = msgData.status == "calling" || msgData.status == "talking"

        if (store.state.value.phase != VoicePhase.Idle) {
            if (isCallingOrTalking) joinExisting(rid)
            return
        }

        scope.launch {
            if (isCallingOrTalking) {
                // RN :1743-1748 await joinExisting——phase 检查在 await 之后:
                // joinExisting 内部落态（accept/joinOngoing/presentIncoming）完成后才判定
                joinExistingInternal(rid)
                if (store.state.value.phase != VoicePhase.Idle) return@launch
            }

            if (msgData.status == "calling") {
                val callMsgRaw = runCatching { deps.roomCallMsgRawForRid(rid) }.getOrNull()
                val parsed = parseCallMsg(callMsgRaw)
                if (parsed?.recordDataObj == null || !isRoomCallMsgActive(parsed, null)) return@launch
                syncCallMsgFromRoom(rid, callMsgRaw)
                presentIncoming(autoAccept = false, callMsg = parsed, rid = rid)
                return@launch
            }

            val ctx = deps.sessionContext() ?: return@launch

            val receivers = msgData.userStatus.orEmpty()
                .filter { !it.username.isNullOrEmpty() && it.username != ctx.username }
                .map { VoiceChatReceiver(receiver = it.username!!, name = it.name ?: it.username!!) }
            if (receivers.isEmpty()) return@launch

            startOutgoing(
                cn.appia.im.core.voice.VoiceChatHoster(
                    roomId = rid,
                    roomType = msgData.roomType ?: "d",
                    receivers = receivers,
                ),
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // endCall / resetSession（RN :1585-1679 / :749-758）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :1585-1679 endCall。单飞:并发调用共享同一次清理;phase idle/ending 直接返回。
     * 快照判定:leave（hadRemoteParticipant || >1 in users）> cancel（hoster && 响铃中）> 默认 leave。
     * 收尾:停轮询/计时 → rtc 清理（agoraGeneration bump + 停回铃 + leave）→
     * 同代才 idleState → dismissed 清除 → oncall leave/cancel（fire-and-forget）。
     */
    fun endCall(reason: String?) {
        scope.launch { endCallInternal(reason) }
    }

    private suspend fun endCallInternal(reason: String?) {
        val phase = store.state.value.phase
        if (phase == VoicePhase.Idle || phase == VoicePhase.Ending) return

        val inFlight = endCallMutex.withLock {
            val existing = endCallInFlight
            if (existing != null) return@withLock existing
            // 双检:等锁期间他方可能已完成清理
            val recheck = store.state.value.phase
            if (recheck == VoicePhase.Idle || recheck == VoicePhase.Ending) return@withLock null
            val snapshot = store.state.value
            val generation = snapshot.sessionGeneration
            val work = scope.async { runEndCallCleanup(snapshot, generation) }
            endCallInFlight = work
            work
        } ?: return
        inFlight.await()
    }

    private suspend fun runEndCallCleanup(
        snapshot: VoiceSessionState,
        generation: Int,
    ) {
        try {
            if (store.state.value.sessionGeneration == generation) {
                store.update { it.copy(phase = VoicePhase.Ending) }
            }
            stopCallStatusPolling()
            deps.timers.clear()
            deps.ringer.stop()
            releaseAgoraRtc()

            // RN :1630-1643:乐观清除房间 callMsg,避免 DB 仍显示 calling 导致无法立即重拨。
            // gate = shouldClearLocalRoomCallAfterEnd(endedCallMsg, ctx?.username, clearCtx);
            // clearCtx.fallbackRoomType = hoster?.roomType ?? resolveVoiceCallRoomType(callMsg)。
            val rid = snapshot.rid
            if (rid != null &&
                shouldClearLocalRoomCallAfterEnd(
                    snapshot.callMsg,
                    deps.sessionContext()?.username,
                    VoiceCallMsgContextFallback(
                        snapshot.hoster?.roomType
                            ?: resolveVoiceCallRoomType(snapshot.callMsg, null),
                    ),
                )
            ) {
                deps.clearRoomCallMsg(rid)
            }

            if (store.state.value.sessionGeneration == generation) {
                store.update { store.idleState() }
            }

            val ctx = deps.sessionContext()
            val recordId = snapshot.recordId
            if (ctx != null && recordId != null) {
                deps.dismissed.clearVoiceCallDismissed(recordId)
                // RN :1648-1671 `void deps.…` fire-and-forget:清理不等网络回包,
                // 慢 leave 不阻塞 endCallInFlight 释放（quick-redial 立即可行,RN 测试同语义）
                val oncallAction: suspend () -> Unit = when {
                    shouldOncallLeaveOnEndCall(snapshot) -> {
                        { deps.leave(ctx.oncallContext(), recordId, ctx.username); Unit }
                    }

                    shouldOncallCancelOnEndCall(snapshot) -> {
                        { deps.updateRecordStatus(ctx.oncallContext(), recordId, "cancel"); Unit }
                    }

                    else -> {
                        { deps.leave(ctx.oncallContext(), recordId, ctx.username); Unit }
                    }
                }
                scope.launch { runCatching { oncallAction() } }
            }
        } finally {
            endCallMutex.withLock { endCallInFlight = null }
        }
    }

    /** RN :489-536 releaseAgoraRtc(AA 引擎常驻差异,见类 KDoc):停回铃 + leave + 代数 bump。 */
    private fun releaseAgoraRtc() {
        agoraGeneration += 1
        deps.rtc.stopHostRingback()
        deps.rtc.leave()
    }

    /**
     * RN :749-758 resetSession:endCallInFlight 进行中不动作;停轮询/计时 + rtc 清理 + idleState。
     * 与 endCall 不同:**不 bump sessionGeneration、不发 oncall 收尾**——用于链内自愈复位
     * (token/init 失败)。RN 该路径不 bump(bump 只发生在新会话建立),对代检查靠
     * `phase == idle` 分支命中。Task 8 被叫链复用。
     */
    fun resetSession() {
        if (endCallInFlight != null) return
        deps.ringer.stop()
        stopCallStatusPolling()
        deps.timers.clear()
        releaseAgoraRtc()
        store.update { store.idleState() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 音频开关（RN :760-781）
    // ─────────────────────────────────────────────────────────────────────

    /** RN :760-762 setUiMinimized。 */
    fun setUiMinimized(minimized: Boolean) {
        store.update { it.copy(uiMinimized = minimized) }
    }

    /** RN :764-776 setMuted:rtc + 本端 users isMuted + store。 */
    fun setMuted(muted: Boolean) {
        deps.rtc.setMuted(muted)
        val s = store.state.value
        val nextUsers = s.agoraLocalUid?.let { localUid ->
            s.users.map { u ->
                if (u.uid != null && u.uid == localUid.toString()) u.copy(isMuted = muted) else u
            }
        } ?: s.users
        store.update { it.copy(muteLocal = muted, users = nextUsers) }
    }

    /** RN :778-781 setSpeaker。 */
    fun setSpeaker(enabled: Boolean) {
        deps.rtc.setSpeaker(enabled)
        store.update { it.copy(speakerOn = enabled) }
    }

    /**
     * RN AppState-active 时 configureInCallAudio 重应用(voiceSessionStore.ts:540 /
     * VoiceCallHost index.tsx:95-106)。Task 5 遗留裁定:AA Controller 提供显式方法,
     * Task 12 UI 层在生命周期 resume 时调用。当前 mute/speaker 状态经 rtc 重放。
     */
    fun reapplyInCallAudio() {
        val s = store.state.value
        if (s.phase !in ACTIVE_CALL_POLL_PHASES) return
        deps.rtc.setMuted(s.muteLocal)
        deps.rtc.setSpeaker(s.speakerOn)
    }

    // ─────────────────────────────────────────────────────────────────────
    // inviteUsers（RN :1681-1732）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * RN :1681-1732:recordId 存在且 phase ∈ {outgoingRinging, connecting, inCall} 才有效;
     * buildInitReceivers 分配 uid;addUsers 成功 → 重锚主叫计时(RN 群组重邀后等待新被叫接听)。
     */
    fun inviteUsers(receivers: List<VoiceChatReceiver>) {
        scope.launch {
            val s = store.state.value
            if (s.recordId == null) return@launch
            if (s.phase != VoicePhase.OutgoingRinging && s.phase != VoicePhase.Connecting && s.phase != VoicePhase.InCall) return@launch

            val ctx = deps.sessionContext() ?: return@launch
            val newReceivers = buildInitReceivers(receivers)
            if (newReceivers.isEmpty()) return@launch

            runCatching {
                val res = deps.addUsers(
                    ctx.oncallContext(),
                    AddUsersBody(
                        receivers = newReceivers,
                        recordId = s.recordId,
                        org = ctx.org,
                        initiatorAppiaId = ctx.userId,
                        initiator = ctx.username,
                        initiatorName = ctx.userDisplayName,
                    ),
                )
                val status = (res.data as? JsonObject)?.get("status")?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
                if (isOncallSuccess(res) && status == 1 && store.state.value.recordId != null) {
                    startHostOutgoingTimers(store.state.value.recordId!!)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // shutdown（登出）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 登出收尾:停轮询/计时 → rtc release(RN release 终态,引擎此后全静默——
     * RN logout 链 voiceCallKeep + rtc.release 同位) → 事件订阅取消 → idleState。
     */
    fun shutdown() {
        stopCallStatusPolling()
        deps.timers.clear()
        agoraGeneration += 1
        eventJob.cancel()
        deps.rtc.release()
        store.update { store.idleState() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 私有辅助
    // ─────────────────────────────────────────────────────────────────────

    private fun emitUi(event: VoiceUiEvent) {
        _uiEvents.tryEmit(event)
    }

    private fun sessionUsername(): String? = deps.sessionContext()?.username

    /**
     * RN blockVoiceJoinIfAnsweredOnOtherDevice.ts:4-13 isLocalVoiceAnsweredOnOtherDevice:
     * username 空 → false;deviceId 取 [VoiceSessionDeps.deviceIdProvider]（RN getDeviceId）。
     */
    private fun isAnsweredOnOtherDevice(callMsg: VoiceCallMsg?, username: String?): Boolean {
        val name = username?.trim()
        if (name.isNullOrEmpty()) return false
        return isVoiceAnsweredOnOtherDevice(callMsg, name, deps.deviceIdProvider() ?: "")
    }

    /** RN 各处 `{ fallbackRoomType: hoster?.roomType }` 内联形态。 */
    private fun VoiceCallMsgContextFallback(roomType: String?) = VoiceCallMsgContext(fallbackRoomType = roomType)

    private fun bumpVoiceSessionGeneration(): Int {
        voiceSessionGeneration += 1
        return voiceSessionGeneration
    }

    private companion object {
        private const val TAG = "VoiceSession"

        /** RN :1083 channelId 毫秒时间戳;RN :148 VoiceOutgoingHint 字面量。 */
        private const val OUTGOING_HINT_DIALING = "dialing"
        private const val OUTGOING_HINT_NOT_CONNECT = "not_connect"

        /**
         * RN agoraRtc.ts:14 HOST_RINGBACK_FILE('calling_sound.wav') → AA assets 寻址
         * （Task 11 裁定：Agora startAudioMixing 只认绝对路径或 /assets/ 前缀，res/raw 不可寻址；
         * 文件 = app/src/main/assets/voice_calling.wav）。
         */
        private const val HOST_RINGBACK_ASSET = "/assets/voice_calling.wav"

        /** RN agoraVoiceConstants.ts:1-2(= VoiceEnv.VOICE_HOSTER_AGORA_UID)。 */
        private const val HOSTER_AGORA_UID = 8888L

        /** RN :254 CALL_STATUS_POLL_MS = 1000（1:1 移植;plan 文本 10s 为笔误,审查裁定以 RN 为准）。 */
        private const val CALL_STATUS_POLL_MS = 1_000L

        /** RN :256-261 ACTIVE_CALL_POLL_PHASES。 */
        private val ACTIVE_CALL_POLL_PHASES = setOf(
            VoicePhase.InCall,
            VoicePhase.Connecting,
            VoicePhase.OutgoingRinging,
            VoicePhase.IncomingRinging,
        )

        /** RN :1531-1533/:1572-1574 `new Promise(setTimeout 400)`（切换 second call 前缓冲）。 */
        private const val OTHER_INCOMING_SWITCH_DELAY_MS = 400L
    }
}

/** RN :148 VoiceOutgoingHint(字符串字面量,Kotlin 不建枚举——RN 同为裸串)。 */
data class VoiceUiEvent(val key: String, val detail: String? = null)

/** RN oncallResponse.ts:56-60 AgoraJoinCredentials。 */
data class AgoraJoinCredentials(val token: String, val encKey: String? = null)

/** RN isOutgoingStartAborted.ts:5-13 verbatim——代数不匹配或已离开活动相位。 */
internal fun isOutgoingStartAborted(myGeneration: Int, snapshot: VoiceSessionState): Boolean =
    snapshot.sessionGeneration != myGeneration ||
        snapshot.phase == VoicePhase.Idle ||
        snapshot.phase == VoicePhase.Ending

/** RN shouldOncallLeaveOnEndCall.ts verbatim——已接通(hadRemoteParticipant 或 >1 in)。 */
internal fun shouldOncallLeaveOnEndCall(snapshot: VoiceSessionState): Boolean {
    if (snapshot.hadRemoteParticipant) return true
    val inUsers = snapshot.callMsg?.userStatus?.filter { it.status == "in" } ?: emptyList()
    return inUsers.size > 1
}

/** RN shouldOncallCancelOnEndCall.ts verbatim——主叫响铃中未接通走 cancel。 */
internal fun shouldOncallCancelOnEndCall(snapshot: VoiceSessionState): Boolean {
    if (snapshot.hoster == null) return false
    if (shouldOncallLeaveOnEndCall(snapshot)) return false
    return snapshot.phase == VoicePhase.OutgoingRinging
}

/** RN oncallResponse.ts:15-27 isInformInitSuccess——code 0 + data.recordId + status ∈ {1,'1',null}。 */
internal fun isInformInitSuccess(res: OncallResponse): Boolean {
    if (!isOncallSuccess(res)) return false
    val data = res.data as? JsonObject ?: return false
    val recordId = data["recordId"]?.jsonPrimitiveOrNull()?.takeIf { it.isString }?.content
    if (recordId.isNullOrEmpty()) return false
    val status = data["status"]?.jsonPrimitiveOrNull() ?: return true
    if (status is kotlinx.serialization.json.JsonNull) return true
    return status.content == "1"
}

/** JsonPrimitive 严格提取（JsonNull 是 JsonPrimitive 子类,先排除 null 再取串/数字形态）。 */
private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): kotlinx.serialization.json.JsonPrimitive? =
    this as? kotlinx.serialization.json.JsonPrimitive

/** RN oncallResponse.ts:40-48 extractAgoraJoinCredentials——data.token trim 后非空。 */
internal fun extractAgoraJoinCredentials(res: OncallResponse): AgoraJoinCredentials? {
    val data = res.data as? JsonObject ?: return null
    val token = (data["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    if (token.isNullOrEmpty()) return null
    val encKey = (data["derivedKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    return AgoraJoinCredentials(token, encKey?.takeIf { it.isNotEmpty() })
}

/** RN oncallResponse.ts:50-58 getOncallErrorMessage——data.message 优先,res.message 回退。 */
internal fun getOncallErrorMessage(res: OncallResponse): String? {
    val dataMessage = (res.data as? JsonObject)?.get("message")?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    }
    return dataMessage ?: res.message
}

/** init 响应 data.recordId 提取。 */
private fun extractRecordId(res: OncallResponse): String? =
    (res.data as? JsonObject)?.get("recordId")?.let {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    }

/**
 * RN lib/voice/callMsgFromOncallRecordResponse.ts verbatim（domain 私有实现——
 * core/voice/lib 未移植该 helper,Task 8 scope 守卫禁改 core,收拢在此）:
 * data 非对象 → null;展开为 VoiceCallMsg;recordData 字符串解码失败 → 整条 null
 * （AA:VoiceCallMsg.recordDataObj lazy 已承载该语义,此处只需解码成功性复核）。
 */
internal fun callMsgFromOncallRecordResponse(res: OncallResponse?): VoiceCallMsg? {
    val data = res?.data as? JsonObject ?: return null
    val msg = runCatching { voiceJson.decodeFromJsonElement(VoiceCallMsg.serializer(), data) }.getOrNull() ?: return null
    // recordData 为字符串但内层解析失败（recordDataObj null）→ 整条 null（ParseCallMsg.ts:18-24 同位）
    if (msg.recordData is JsonPrimitive && msg.recordData.isString && msg.recordDataObj == null) return null
    return msg
}

/**
 * RN :559-568 buildInitReceivers:HHmmssSS 时间基 + 索引分配被叫 Agora uid;status 缺省 'out'。
 * AA:minSdk 24 无 java.time,SimpleDateFormat 同位。
 */
private fun buildInitReceivers(receivers: List<VoiceChatReceiver>): List<VoiceChatReceiver> {
    val timeNum = runCatching {
        java.text.SimpleDateFormat("HHmmssSSS").format(java.util.Date()).toLong()
    }.getOrDefault(0L)
    return receivers.mapIndexed { index, receiver ->
        receiver.copy(receiverUid = timeNum + index, status = receiver.status ?: "out")
    }
}
