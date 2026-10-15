package cn.appia.im.domain.voice

import android.util.Log
import cn.appia.im.core.push.PushVoiceOncallParams
import cn.appia.im.core.voice.OncallContext
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.parseCallMsg
import cn.appia.im.core.voice.lib.shouldPresentIncomingForUser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 推送语音意图分流（RN voicePushIngress.ts + resolveVoiceCallFromPush.ts 移植）。
 *
 * handle 链（RN processVoicePushIntent :96-199 逐段对齐）：
 * isCallEnd → return（:100-102/:207-209）→ 未登录 → enqueue（:104-107）→
 * sessionContext null → enqueue（:109-119）→ resolve（嵌入 callMsg 优先短路 fetch，
 * :121-138 失败 enqueue）→ 无 channelId → enqueue（:151-154，图标前台重连场景）→
 * already-handling 判定跳过（:156-173）→ shouldPresentIncomingForUser 门（:175-184）→
 * presentIncoming（:186-199）。
 *
 * drainPending（RN :223-249）：登录后循环冲刷队列（drain 内再入队 → do-while 直到空）。
 *
 * resolve（RN resolveVoiceCallFromPush.ts:43-73）：嵌入 callMsg 有 channelId → 直用；
 * 否则 recordId 拉 getRecordStatusV2 → callMsgFromOncallRecordResponse → roomId 缺失回填 rid。
 * RN 的 v1/v2 org 分流收窄为恒 v2（AA 既有约定：Controller 轮询/joinExisting 均恒 v2）。
 *
 * deps 与 Controller 共用同一实例（Task 12 装配）——dismissed 必须同源
 * （rejectIncoming 标记的忽略记录，ingress 呈现门须可见）。
 */
class VoicePushIngress(
    private val controller: VoiceSessionController,
    /** RN isAppVoiceReady/getVoiceSessionContext 的注入缝（null = 未登录/上下文不可用）。 */
    private val sessionContextProvider: () -> VoiceSessionContext?,
    private val queue: VoicePushQueue,
    private val deps: VoiceSessionDeps,
) {

    /** RN :223 drainInFlight 的 Mutex 形态（并发 drain 单飞）。 */
    private val drainMutex = Mutex()

    /**
     * RN voicePushIngress :201-221 入口（PushClickRouter.onVoiceOncall 接线）。
     * RN host 仅日志透传，AA 同。
     */
    suspend fun handle(rid: String, voiceOncall: PushVoiceOncallParams, host: String? = null) {
        if (voiceOncall.isCallEnd) return
        processIntent(
            VoicePushQueue.Intent(
                rid = rid,
                recordId = voiceOncall.recordId,
                org = voiceOncall.org,
                callMsg = voiceOncall.callMsg?.let { parseCallMsg(it.toString()) },
                callMsgRaw = voiceOncall.callMsgRaw,
                host = host,
            ),
        )
    }

    /** RN :96-199 processVoicePushIntent（skipReadyCheck：drain 路径登录已验）。 */
    private suspend fun processIntent(intent: VoicePushQueue.Intent, skipReadyCheck: Boolean = false) {
        if (intent.isCallEnd) return

        if (!skipReadyCheck && sessionContextProvider() == null) {
            queueRetryableIntent(intent, "not_authenticated")
            return
        }

        val sessionCtx = sessionContextProvider()
        if (sessionCtx == null) {
            queueRetryableIntent(intent, "context_unavailable")
            return
        }

        val callMsg: VoiceCallMsg?
        try {
            callMsg = resolveVoiceCallFromPush(
                ctx = sessionCtx,
                rid = intent.rid,
                recordId = intent.recordId,
                org = intent.org,
                callMsg = intent.callMsg,
                callMsgRaw = intent.callMsgRaw,
            )
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed, enqueue retry rid=${intent.rid} recordId=${intent.recordId}: $e")
            queueRetryableIntent(intent, "resolve_network_error")
            return
        }

        // RN :151-154 无 channelId（resolve null 或残缺）→ 入队重试
        if (callMsg?.recordDataObj?.channelId?.isNotEmpty() != true) {
            queueRetryableIntent(intent, "resolve_no_channel")
            return
        }

        if (isAlreadyHandling(intent, callMsg)) {
            Log.i(TAG, "skip already handling rid=${intent.rid} recordId=${intent.recordId}")
            return
        }

        if (!shouldPresentIncomingForUser(callMsg, sessionCtx.username, null, deps.dismissed)) {
            Log.i(TAG, "skip should-not-present rid=${intent.rid} recordId=${intent.recordId}")
            return
        }

        Log.i(TAG, "presentIncoming rid=${intent.rid} recordId=${intent.recordId}")
        controller.presentIncoming(autoAccept = false, callMsg = callMsg, rid = intent.rid)
    }

    /**
     * RN :26-56 describeAlreadyHandlingVoicePush：idle/ending 放行；
     * 无 recordId → 同 rid 跳过；有 recordId → 同 recordId 跳过
     * （incomingRinging 同记录不重复 presentIncoming）。
     */
    private fun isAlreadyHandling(intent: VoicePushQueue.Intent, callMsg: VoiceCallMsg): Boolean {
        val state = controller.store.state.value
        if (state.phase == VoicePhase.Idle || state.phase == VoicePhase.Ending) return false
        val incomingRecordId = intent.recordId
            ?: callMsg.recordId
            ?: callMsg.recordDataObj?.recordId
        if (incomingRecordId == null || state.recordId == null) {
            return state.rid == intent.rid
        }
        return state.recordId == incomingRecordId
    }

    /**
     * RN resolveVoiceCallFromPush.ts:43-73 + parseEmbeddedCallMsg :18-41。
     * 嵌入 callMsg（对象/字符串双源）有 channelId → 直用；否则 getRecordStatusV2 拉取
     * （recordOrg = org ?? embedded.org ?? ctx.org）。
     */
    private suspend fun resolveVoiceCallFromPush(
        ctx: VoiceSessionContext,
        rid: String,
        recordId: String?,
        org: String?,
        callMsg: VoiceCallMsg?,
        callMsgRaw: String?,
    ): VoiceCallMsg? {
        val embedded = parseEmbeddedCallMsg(callMsg, callMsgRaw)
        if (embedded?.recordDataObj?.channelId?.isNotEmpty() == true) return embedded

        val ridForFetch = recordId ?: embedded?.recordId ?: embedded?.recordDataObj?.recordId
            ?: return null

        val recordOrg = org ?: embedded?.org ?: ctx.org
        val res = deps.getRecordStatusV2(ctx.oncallContext(), ridForFetch, recordOrg)
        val fetched = callMsgFromOncallRecordResponse(res)
        if (fetched?.recordDataObj?.channelId?.isNotEmpty() != true) return null
        // RN :69-72 roomId 缺失回填 rid
        return if (fetched.roomId.isEmpty()) fetched.copy(roomId = rid) else fetched
    }

    /** RN parseEmbeddedCallMsg :18-41：callMsg 优先，callMsgRaw 字符串解析；recordData 字符串双层解码由 parseCallMsg 承载。 */
    private fun parseEmbeddedCallMsg(callMsg: VoiceCallMsg?, callMsgRaw: String?): VoiceCallMsg? {
        if (callMsg?.recordDataObj?.channelId?.isNotEmpty() == true) return callMsg
        if (callMsgRaw != null) {
            val parsed = parseCallMsg(callMsgRaw)
            if (parsed?.recordDataObj?.channelId?.isNotEmpty() == true) return parsed
        }
        return callMsg?.takeIf { it.recordDataObj != null }
    }

    /** RN :82-94 queueRetryableIntent（receivedAt 刷新 = dedupeKey 刷新）。 */
    private fun queueRetryableIntent(intent: VoicePushQueue.Intent, reason: String) {
        Log.i(TAG, "queued rid=${intent.rid} reason=$reason")
        queue.enqueue(intent.copy(receivedAt = System.currentTimeMillis()))
    }

    /**
     * RN :223-249 drainPendingVoicePushIntents：未登录直返；do-while 冲刷
     * （drain 后再入队的条目循环处理直到队列空）。Mutex = RN drainInFlight 单飞。
     */
    suspend fun drainPending() {
        if (sessionContextProvider() == null) return
        drainMutex.withLock {
            do {
                val batch = queue.drain()
                if (batch.isEmpty()) break
                for (intent in batch) {
                    processIntent(intent, skipReadyCheck = true)
                }
            } while (queue.pendingCount > 0)
        }
        // ponytail: RN verbatim 的 do-while 在「记录永不出现在服务端」时无限重试拉取
        // （每轮一次网络往返的慢轮询）；若需上限，加同 recordId 重试计数即可
    }

    /** 登出收尾（VoiceRoomHandle.shutdown）：清积压语音意图（RN clearVoicePushQueue 同位）。 */
    fun clearQueued() = queue.clear()

    /** 积压数（测试/观测面）。 */
    val queuedCount: Int get() = queue.pendingCount

    /** 测试夹具面：直入队。 */
    internal fun enqueueForTest(intent: cn.appia.im.domain.voice.VoicePushQueue.Intent) = queue.enqueue(intent)

    private companion object {
        private const val TAG = "VoicePushIngress"
    }
}
