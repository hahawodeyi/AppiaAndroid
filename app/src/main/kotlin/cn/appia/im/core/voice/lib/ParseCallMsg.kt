package cn.appia.im.core.voice.lib

import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.VoiceCallRecordData
import cn.appia.im.core.voice.voiceJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RN lib/voice/parseCallMsg.ts verbatim：双层 JSON 解析。
 * recordData 字符串解码失败 → 整条 null（parseCallMsg.ts:18-24）；
 * u 字符串解码失败 → 容忍（parseCallMsg.ts:34-36 legacy tolerates u parse failure）；
 * u.name 回填 recordData.initiatorName（parseCallMsg.ts:26-33，无条件覆盖）。
 */
fun parseCallMsg(callMsg: String?): VoiceCallMsg? {
    if (callMsg.isNullOrEmpty()) return null
    val decoded = runCatching {
        Json { ignoreUnknownKeys = true }.decodeFromString<VoiceCallMsg>(callMsg)
    }.getOrNull() ?: return null

    // parseCallMsg.ts:18-24：recordData 为字符串但内层 JSON 解析失败 → 整条返回 null
    if (decoded.recordData is JsonPrimitive && decoded.recordData.isString && decoded.recordDataObj == null) {
        return null
    }

    // parseCallMsg.ts:26-33：u 为字符串形态且解析成功、recordData 为对象 → 回填 initiatorName（无条件覆盖）
    val uWasString = decoded.u is JsonPrimitive && decoded.u.isString
    val uObj = decoded.uObj
    val recordObj = decoded.recordDataObj
    if (uWasString && uObj?.name != null && recordObj != null) {
        val base = voiceJson.encodeToJsonElement(VoiceCallRecordData.serializer(), recordObj) as JsonObject
        val backfilled = JsonObject(base + ("initiatorName" to JsonPrimitive(uObj.name)))
        return decoded.copy(recordData = backfilled)
    }
    return decoded
}
