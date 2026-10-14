package cn.appia.im.domain.voice

import android.util.Log
import cn.appia.im.core.voice.AddUsersBody
import cn.appia.im.core.voice.InformInitBody
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
import cn.appia.im.core.voice.lib.groupHasCalleeInVoiceChannel
import cn.appia.im.core.voice.lib.isGroupVoiceCallMsg
import cn.appia.im.core.voice.lib.mapCallMsgToVoiceUsers
import cn.appia.im.core.voice.lib.shouldAcceptRemoteUserJoined
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
 * 语音会话编排 I——主叫链（RN stores/voiceSessionStore.ts zustand store 的动作半部，
 * :1034-1242 startOutgoing / :1585-1679 endCall / :629-744 ensureAgoraRtc·joinAgoraChannel·
 * runWithAgoraGeneration·enterInCallFromAgora·onEnteredInCall / :749-758 resetSession /
 * :760-781 setUiMinimized·setMuted·setSpeaker / :1681-1732 inviteUsers / :399-452 轮询 /
 * :401-406 stopCallStatusPolling / :489-536 releaseAgoraRtc）。
 *
 * 状态在 [VoiceSessionStore]（Task 6 纯宿主），本类只持动作与副作用；Task 8 同类扩展被叫链。
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
            emitUi(VoiceUiEvent("meeting_end_voice_first"))
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
     * 引擎实例由 [deps.rtc] 常驻（ensureAgoraRtc 的 AA 形态）,事件流订阅在 [collectRtcEvents]。
     */
    private fun joinAgoraChannel(
        channelId: String,
        uid: Long,
        ctx: cn.appia.im.core.voice.OncallContext,
        prefetched: AgoraJoinCredentials,
    ): Boolean {
        store.update { it.copy(agoraLocalUid = uid) }
        rtcEventGeneration = ++agoraGeneration
        val s = store.state.value
        return deps.rtc.join(
            appId = VoiceEnv.AGORA_APP_ID,
            channelName = channelId,
            token = prefetched.token,
            uid = uid,
            encKey = prefetched.encKey,
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
                        // RN :669-680:群聊须已有被叫在频道（groupHasCalleeInVoiceChannel）
                        if (s.hoster != null &&
                            isGroupVoiceCallMsg(s.callMsg, VoiceCallMsgContextFallback(s.hoster.roomType)) &&
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
     * RN :446-452 startCallStatusPolling:立即一次 + 间隔轮询。
     * 单次轮询（RN :408-444）:ACTIVE_CALL_POLL_PHASES 守卫 → getRecordStatusV2 →
     * [onPollResult] 缝（Task 8 接入完整 syncCallMsgFromRoom 链）。
     * Task 8 将开放为 public;T7 起点为内联启动（主叫链 + onEnteredInCall）。
     */
    private fun startCallStatusPollingInternal() {
        stopCallStatusPolling()
        pollCallRecordStatusOnce()
        pollJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(CALL_STATUS_POLL_MS)
                pollCallRecordStatusOnce()
            }
        }
    }

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
     * 轮询结果缝（Task 8 替换为完整 syncCallMsgFromRoom 链:callMsgFromOncallRecordResponse →
     * parse → merge → endCall 判定）。Task 7 阶段默认丢弃——RN pollCallRecordStatusOnce 的
     * callMsgFromOncallRecordResponse 逻辑随被叫链落地。
     */
    fun onPollResult(rid: String, res: OncallResponse) {
        // Task 8: syncCallMsgFromRoom(rid, res)
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
            releaseAgoraRtc()

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

        /** RN agoraRtc.ts:14 HOST_RINGBACK_FILE('calling_sound.wav') → AA raw 资源名(Task 11 约定)。 */
        private const val HOST_RINGBACK_ASSET = "voice_calling"

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
