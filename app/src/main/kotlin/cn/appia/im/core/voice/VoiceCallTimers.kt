package cn.appia.im.core.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RN lib/voice/voiceCallTimers.ts verbatim（主叫 30s 提示 / 65s 挂断 + 被叫 65s 超时，recordId 锚定）。
 * RN setTimeout/clearTimeout → 注入 scope 的 delay 协程；回调在注入 scope 上执行（Task 12 生产接线传真实 scope）。
 * 锚规则（RN :47-49/:53-55/:74-76）：计时到期时锚仍等于发起时 recordId 才回调；start 先清旧计时并重锚；clear 全清且锚置空。
 */
class VoiceCallTimers(private val scope: CoroutineScope) {

    private val lock = Any()
    private var hostHintJob: Job? = null
    private var hostEndJob: Job? = null
    private var calleeEndJob: Job? = null

    /** RN :12 hostAnchoredRecordId。 */
    private var hostAnchoredRecordId: String? = null

    /** RN :13 calleeAnchoredRecordId（被叫侧独立锚）。 */
    private var calleeAnchoredRecordId: String? = null

    /** RN :40-58 startHostOutgoingTimers：清旧主叫计时 → 锚定 → 30s/65s 双计时。 */
    fun startHostOutgoingTimers(recordId: String, on30s: () -> Unit, on65s: () -> Unit) {
        synchronized(lock) {
            clearHostTimersLocked()
            hostAnchoredRecordId = recordId
            hostHintJob = scope.launch {
                delay(HOST_HINT_MS)
                if (anchorMatchesHost(recordId)) on30s()
            }
            hostEndJob = scope.launch {
                delay(HOST_END_MS)
                if (anchorMatchesHost(recordId)) on65s()
            }
        }
    }

    /** RN :60-65 resetHostOutgoingTimers = startHostOutgoingTimers（重锚）。 */
    fun resetHostOutgoingTimers(recordId: String, on30s: () -> Unit, on65s: () -> Unit) =
        startHostOutgoingTimers(recordId, on30s, on65s)

    /** RN :67-79 startCalleeIncomingTimer：被叫侧独立锚 + 单 65s 计时（不动主叫侧）。 */
    fun startCalleeIncomingTimer(recordId: String, on65s: () -> Unit) {
        synchronized(lock) {
            clearCalleeTimerLocked()
            calleeAnchoredRecordId = recordId
            calleeEndJob = scope.launch {
                delay(CALLEE_END_MS)
                if (synchronized(lock) { calleeAnchoredRecordId == recordId }) on65s()
            }
        }
    }

    /** RN :35-38 clearVoiceCallTimers：主叫 + 被叫全清。 */
    fun clear() {
        synchronized(lock) {
            clearHostTimersLocked()
            clearCalleeTimerLocked()
        }
    }

    private fun anchorMatchesHost(recordId: String): Boolean =
        synchronized(lock) { hostAnchoredRecordId == recordId }

    /** RN :15-25 clearHostTimers（含锚置空）。 */
    private fun clearHostTimersLocked() {
        hostHintJob?.cancel()
        hostHintJob = null
        hostEndJob?.cancel()
        hostEndJob = null
        hostAnchoredRecordId = null
    }

    /** RN :27-33 clearCalleeTimer（含锚置空）。 */
    private fun clearCalleeTimerLocked() {
        calleeEndJob?.cancel()
        calleeEndJob = null
        calleeAnchoredRecordId = null
    }

    companion object {
        /** RN :1-3 VOICE_CALL_HOST_HINT_MS / VOICE_CALL_HOST_END_MS / VOICE_CALL_CALLEE_END_MS。 */
        const val HOST_HINT_MS = 30_000L
        const val HOST_END_MS = 65_000L
        const val CALLEE_END_MS = 65_000L
    }
}
