package cn.appia.im.core.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 语音通话协议层类型（逐字段对照 appiaMobile/src/lib/voice/voiceTypes.ts）。
 * RN 字段 optional → Kotlin 可空 + 默认 null；解析统一 [voiceJson]（ignoreUnknownKeys）。
 */

/** 全 voice 域统一解析口径：未知键忽略（服务端加字段不炸）。 */
val voiceJson: Json = Json { ignoreUnknownKeys = true }

/** voiceTypes.ts IVoiceChatReceiver —— 发起通话的被叫（对齐旧版 IVCReceiveUser）。 */
@Serializable
data class VoiceChatReceiver(
    val receiver: String,
    val receiverAppiaId: String? = null,
    val name: String,
    val receiverUid: Long? = null,
    val status: String? = null,
)

/** voiceTypes.ts IVoiceChatHoster —— 发起通话的主叫载荷（对齐旧版 IVChatHoster）。 */
@Serializable
data class VoiceChatHoster(
    val roomId: String,
    val roomType: String,
    val receivers: List<VoiceChatReceiver>,
)

/** types/appiaMessage.ts:110-117 IVoiceChatUserStatus。 */
@Serializable
data class VoiceUserStatus(
    val username: String? = null,
    val name: String? = null,
    val operation: String? = null,
    val deviceId: String? = null,
    val status: String? = null,
    val uid: Long? = null,
)

/**
 * voiceTypes.ts IVCUser —— 通话页展示用户（对齐旧版 IVCUser）。
 * uid 服务端 string/number 双形态，AA 统一字符串承载。
 */
@Serializable
data class VoiceUser(
    @SerialName("_id") val id: String? = null,
    val uid: String? = null,
    val name: String? = null,
    val username: String? = null,
    val status: String? = null,
    /** 对齐 IVoiceChatUserStatus.operation（waiting / reject / hangup 等）。 */
    val operation: String? = null,
    val isSpeaking: Boolean? = null,
    val isMuted: Boolean? = null,
)

/**
 * voiceTypes.ts IVoiceCallRecordData —— recordData 解析形态（exist 保留 raw JsonElement 双形态）。
 * recordId/channelId/initiator/initiatorUid：RN 类型必填但运行时不强制（TS 擦除），
 * 服务端/夹具可缺字段——AA 默认空串对齐 RN 宽松语义。
 */
@Serializable
data class VoiceCallRecordData(
    val exist: JsonElement? = null,
    val recordId: String = "",
    val channelId: String = "",
    val initiator: String = "",
    val initiatorUid: String = "",
    val initiatorName: String? = null,
    val receivers: List<VoiceChatReceiver>? = null,
    val roomType: String? = null,
)

/** voiceTypes.ts IVoiceCallUser —— callMsg 顶层 u（主叫摘要）。 */
@Serializable
data class VoiceCallUser(
    @SerialName("_id") val id: String? = null,
    val uid: String? = null,
    val name: String? = null,
    val username: String? = null,
    val status: String? = null,
)

/**
 * voiceTypes.ts IVoiceCallMsg —— 房间 callMsg 解析形态。
 *
 * RN 双层 JSON 结构，AA raw+惰性展开（Controller 裁定）：`recordData`/`u` 声明为 [JsonElement]
 * （对象原样，或字符串形态的 JsonPrimitive），展开经 [recordDataObj]/[uObj] 计算属性。
 */
@Serializable
data class VoiceCallMsg(
    /** RN TS 类型必填但运行时擦除——服务端/夹具常见缺字段（voiceTestFixtures.asVoiceCallMsg），AA 默认值对齐宽松语义。 */
    val status: String = "",
    val roomId: String = "",
    val roomType: String? = null,
    val org: String? = null,
    val userStatus: List<VoiceUserStatus>? = null,
    val recordData: JsonElement? = null,
    val receivers: List<VoiceChatReceiver> = emptyList(),
    val recordId: String? = null,
    val messageId: String? = null,
    val u: JsonElement? = null,
    val invitorUsername: String? = null,
    val invitorName: String? = null,
    val invitorOrg: String? = null,
) {
    /**
     * recordData 展开：对象原样；JsonPrimitive 字符串再解码为对象。
     * 解码失败整条返回 null（parseCallMsg.ts:18-24 的 return null 语义）。
     */
    val recordDataObj: VoiceCallRecordData? by lazy {
        expandJsonObject(recordData)?.let {
            runCatching { recordDataFromJson(it) }.getOrNull()
        }
    }

    /** u 展开：解码失败容忍返回 null（parseCallMsg.ts:26-37 legacy tolerates）。 */
    val uObj: VoiceCallUser? by lazy {
        expandJsonObject(u)?.let { runCatching { voiceJson.decodeFromJsonElement(VoiceCallUser.serializer(), it) }.getOrNull() }
    }

    /** 展开用解码辅助：JsonObject → [VoiceCallRecordData]；字符串形态（测试/内嵌 raw）先 parse。 */
    companion object {
        fun recordDataFromJson(json: JsonObject): VoiceCallRecordData =
            voiceJson.decodeFromJsonElement(VoiceCallRecordData.serializer(), json)

        fun recordDataFromJson(raw: String): VoiceCallRecordData =
            recordDataFromJson(voiceJson.parseToJsonElement(raw) as JsonObject)

        private fun expandJsonObject(element: JsonElement?): JsonObject? {
            if (element == null) return null
            if (element is JsonObject) return element
            if (element is JsonPrimitive && element.isString) {
                return runCatching { voiceJson.parseToJsonElement(element.content) as? JsonObject }.getOrNull()
            }
            return null
        }
    }
}

/** groupVoiceCall.ts:7-10 VoiceCallMsgContext —— 轮询/同步 payload 缺 roomType 时的兜底。 */
data class VoiceCallMsgContext(
    val fallbackRoomType: String? = null,
)
