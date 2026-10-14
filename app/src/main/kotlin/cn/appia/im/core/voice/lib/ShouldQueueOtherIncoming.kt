package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoicePhase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * RN lib/voice/shouldQueueOtherIncoming.ts verbatim。
 */

/** shouldQueueOtherIncoming.ts:4-8。 */
private val ACTIVE_ON_CALL_PHASES = setOf(VoicePhase.InCall, VoicePhase.Connecting, VoicePhase.OutgoingRinging)

/**
 * shouldQueueOtherIncoming.ts:22 `!parsed?.recordData?.exist || parsed.recordData.exist === 'false'`：
 * JS falsy（null/undefined/false/0/''）+ 显式字符串 'false'（非空串在 JS 为真值，需单独判）。
 */
private fun isQueueExistFalsy(exist: JsonElement?): Boolean {
    if (exist == null) return true
    if (exist !is JsonPrimitive) return false // JS 对象/数组恒为真值
    return when {
        exist.isString -> exist.content.isEmpty() || exist.content == "false"
        else -> exist.content == "false" || exist.content == "0" // 布尔 false / 数字 0
    }
}

/** shouldQueueOtherIncoming.ts:10-34：通话中收到另一路来电（对齐旧版 `OtherVoiceChatNotifi` 触发条件）。 */
fun shouldQueueOtherIncoming(
    phase: VoicePhase,
    currentRecordId: String?,
    parsed: VoiceCallMsg,
    username: String,
    onCallStatus: Boolean?,
): Boolean {
    if (!ACTIVE_ON_CALL_PHASES.contains(phase) || currentRecordId.isNullOrEmpty()) return false // RN `!currentRecordId` falsy
    if (isQueueExistFalsy(parsed.recordDataObj?.exist)) return false
    val incomingRecordId = parsed.recordId ?: parsed.recordDataObj?.recordId ?: ""
    if (incomingRecordId.isEmpty() || incomingRecordId == currentRecordId) return false
    if (parsed.recordDataObj?.initiator == username) return false
    return shouldPresentIncomingFromCallMsg(parsed, username, onCallStatus)
}
