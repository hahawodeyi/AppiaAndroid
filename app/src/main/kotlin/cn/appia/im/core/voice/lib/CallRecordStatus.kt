package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * RN lib/voice/callRecordStatus.ts verbatim。
 */

/** callRecordStatus.ts:4-14：服务端标记通话仍进行中（`exist` 字段形态不一，兼容 boolean / string）。
 *  RN 严格相等（`=== true / 'true' / '1'`）：数字 1/0 不在匹配集——布尔字面量与字符串分别判定。 */
fun isCallRecordActive(exist: JsonElement?): Boolean {
    if (exist !is JsonPrimitive) return false
    return if (exist.isString) {
        exist.content == "true" || exist.content == "1"
    } else {
        exist.content == "true" // 布尔字面量 true（数字 1 的 content 为 "1"，不匹配）
    }
}

/**
 * callRecordStatus.ts:20-44：仅当明确结束信号时返回 true。
 * `exist` 缺失且 status 仍为 calling/talking 时不视为结束——
 * 避免主叫等待接听时房间 `callMsg` 尚未带 `exist` 导致误 `endCall`。
 */
fun isCallRecordExplicitlyEnded(callMsg: VoiceCallMsg?): Boolean {
    val recordData = callMsg?.recordDataObj
    if (recordData == null) {
        return callMsg?.status == "end" || callMsg?.status == "cancel"
    }

    val exist = recordData.exist
    if (isCallRecordActive(exist)) {
        return false
    }
    // callRecordStatus.ts:31：严格相等 false/'false'/'0'/''——数字 0 不在集合（布尔 false 与字符串形态）
    if (exist is JsonPrimitive) {
        val explicitlyEnded = if (exist.isString) {
            exist.content == "false" || exist.content == "0" || exist.content.isEmpty()
        } else {
            exist.content == "false"
        }
        if (explicitlyEnded) return true
    }

    return when (callMsg.status) {
        "calling", "talking" -> false
        "end", "cancel", "reject" -> true
        else -> false
    }
}
