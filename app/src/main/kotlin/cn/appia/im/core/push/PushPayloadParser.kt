package cn.appia.im.core.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 语音 oncall 意图（RN pushTypes.ts:32-39 PushVoiceOncallParams；callMsg/callMsgRaw 解析归 M10 语音分流）。 */
data class PushVoiceOncallParams(
    val recordId: String? = null,
    val org: String? = null,
    val isCallEnd: Boolean = false,
)

/** 推送点击导航参数（RN pushTypes.ts:45-53 PushNavigationParams；t 为 c/d/p/l 小写字母，AA 路由原样使用）。 */
data class PushNavigationParams(
    val rid: String,
    val t: String,
    val title: String? = null,
    val messageId: String? = null,
    val host: String? = null,
    val isCall: Boolean = false,
    val voiceOncall: PushVoiceOncallParams? = null,
)

/** 阿里云 ejson 业务体（RN pushTypes.ts:13-30 PushPayload）。 */
data class PushPayload(
    val rid: String,
    val type: String,
    val name: String? = null,
    val senderUsername: String? = null,
    val senderName: String? = null,
    val host: String? = null,
    val messageType: String? = null,
    val messageId: String? = null,
    val msgType: String? = null,
    /** merge 后的语音标记：RN `oncall === true || oncall === 'oncall'`（post-merge 等价布尔） */
    val oncallFlag: Boolean = false,
    val recordId: String? = null,
    val channelId: String? = null,
    val org: String? = null,
    val isCallEnd: Boolean = false,
)

/**
 * 阿里云推送 payload 解析器（RN extractPushEjsonPayload.ts 全量移植，兜底层优先级逐条对齐）。
 *
 * 到达/点击事件形态（RN :95-103 注释）：
 * - 顶层 `ejson`（JSON 字符串或对象）；
 * - Android 点击：`{title, summary, extra: '{"ejson":"{...}"}'}`（extra 为 JSON 字符串袋，袋内 ejson 再是 JSON 字符串，双重转义）；
 * - 厂商通道退化：顶层裸 rid（type 丢失）、顶层 roomId+msgType='oncall' 最小体。
 */
object PushPayloadParser {

    /** RN pushNavigation.ts:24-29 ROOM_TYPE_MAP（SubscriptionType 值即小写字母）；未知名回落 'c'。 */
    private val ROOM_TYPE_MAP = mapOf("c" to "c", "d" to "d", "p" to "p", "l" to "l")

    // ---- JSON 基元助手（RN asString/asRecord 等价） ----

    private fun asString(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString && it.content.isNotEmpty() }?.content

    /** 原样字符串（不滤空串）：sender/name 等标题字段用（RN 直接属性访问，`??` 不回落空串）。 */
    private fun str(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun asRecord(value: JsonElement?): JsonObject? = value as? JsonObject

    private fun isBooleanTrue(value: JsonElement?): Boolean =
        value is JsonPrimitive && value !is JsonNull && !value.isString && value.content == "true"

    private fun isOncallString(value: JsonElement?): Boolean {
        val s = str(value)
        return s == "oncall"
    }

    /** RN tryParseEjsonValue :3-19：字符串则 JSON.parse；必须为对象且 rid 为非空字符串。 */
    private fun tryParseEjsonValue(value: JsonElement?): JsonObject? {
        val parsed: JsonElement = when {
            value == null || value is JsonNull -> return null
            value is JsonPrimitive && value.isString ->
                runCatching { Json.parseToJsonElement(value.content) }.getOrNull() ?: return null
            else -> value
        }
        val obj = parsed as? JsonObject ?: return null
        return if (asString(obj["rid"]) != null) obj else null
    }

    /** RN parsePushExtraBag :28-42：extra/extras 常为 JSON 字符串，解析为对象袋。 */
    private fun parsePushExtraBag(value: JsonElement?): JsonObject? {
        if (value == null || value is JsonNull) return null
        if (value is JsonPrimitive && value.isString) {
            return runCatching { Json.parseToJsonElement(value.content) }.getOrNull() as? JsonObject
        }
        return value as? JsonObject
    }

    /**
     * RN mergeTopLevelVoiceFields :49-76：阿里云事件顶层常带 msgType/recordId/channelId（与 ejson
     * 内字段重复或互补），合并进解析结果（parsed 优先，袋值兜底）。rawOncallFlag 四形态见 :53-57。
     */
    private fun mergeTopLevelVoiceFields(bag: JsonObject, parsed: JsonObject): PushPayload {
        val oncallRaw = bag["oncall"]
        val rawOncallFlag = isBooleanTrue(oncallRaw) || isOncallString(oncallRaw) ||
            str(oncallRaw) == "true" || str(oncallRaw) == "1"

        val parsedOncallVoice = isBooleanTrue(parsed["oncall"]) || isOncallString(parsed["oncall"])

        val msgType = str(parsed["msgType"])
            ?: (if (str(parsed["messageType"]) == "oncall") "oncall" else null)
            ?: (if (parsedOncallVoice) "oncall" else null)
            ?: asString(bag["msgType"])
            ?: (if (str(bag["messageType"]) == "oncall") "oncall" else null)
            ?: (if (rawOncallFlag) "oncall" else null)

        val sender = asRecord(parsed["sender"])
        return PushPayload(
            rid = asString(parsed["rid"])
                ?: asString(bag["roomId"])
                ?: asString(bag["rid"])
                ?: "",
            type = str(parsed["type"]) ?: "",
            name = str(parsed["name"]),
            senderUsername = sender?.let { str(it["username"]) },
            senderName = sender?.let { str(it["name"]) },
            host = str(parsed["host"]),
            messageType = str(parsed["messageType"]) ?: asString(bag["messageType"]),
            messageId = str(parsed["messageId"]),
            msgType = msgType,
            oncallFlag = parsedOncallVoice || rawOncallFlag,
            recordId = asString(parsed["recordId"]) ?: asString(bag["recordId"]),
            channelId = asString(parsed["channelId"]) ?: asString(bag["channelId"]),
            org = asString(parsed["org"]) ?: asString(bag["org"]),
            isCallEnd = isBooleanTrue(parsed["isCallEnd"]),
        )
    }

    /** RN buildMinimalOncallPayload :78-93。 */
    private fun buildMinimalOncallPayload(bag: JsonObject, roomId: String): JsonObject = buildJsonObject {
        put("rid", JsonPrimitive(roomId))
        put("name", JsonPrimitive(asString(bag["title"]) ?: asString(bag["name"]) ?: ""))
        put("sender", buildJsonObject {
            put("username", JsonPrimitive(""))
            put("name", JsonPrimitive(""))
        })
        put("type", JsonPrimitive(asString(bag["type"]) ?: "p"))
        put("host", JsonPrimitive(asString(bag["host"]) ?: ""))
        put("messageType", JsonPrimitive(asString(bag["messageType"]) ?: "oncall"))
        put("messageId", JsonPrimitive(asString(bag["messageId"]) ?: ""))
        put("msgType", JsonPrimitive("oncall"))
        asString(bag["recordId"])?.let { put("recordId", JsonPrimitive(it)) }
        asString(bag["channelId"])?.let { put("channelId", JsonPrimitive(it)) }
        asString(bag["org"])?.let { put("org", JsonPrimitive(it)) }
    }

    /**
     * RN extractPushEjsonPayload :104-167 全兜底层，优先级逐条对齐：
     * raw.ejson → extraBag.ejson → extrasObj.ejson → payload.ejson → raw.data →
     * extraBag.data → extrasObj.data → extra 整袋 → raw.extra；
     * 然后顶层 rid+type / payload.rid+type / 裸 rid 兜底 'c' / roomId+msgType=oncall。
     */
    fun extractPushEjsonPayload(raw: JsonObject): PushPayload? {
        val extraBag = parsePushExtraBag(raw["extra"]) ?: parsePushExtraBag(raw["extras"])
        val extrasObj = asRecord(raw["extras"]) ?: extraBag
        val payload = asRecord(raw["payload"])

        // 顶层字段 + extra 袋合并，供 voice 字段 / rid 兜底（raw 覆盖同名 key，RN :112-116）
        val bag = JsonObject((extraBag ?: emptyMap()) + raw)

        val fromEjson = tryParseEjsonValue(raw["ejson"])
            ?: tryParseEjsonValue(extraBag?.get("ejson"))
            ?: tryParseEjsonValue(extrasObj?.get("ejson"))
            ?: tryParseEjsonValue(payload?.get("ejson"))
            ?: tryParseEjsonValue(raw["data"])
            ?: tryParseEjsonValue(extraBag?.get("data"))
            ?: tryParseEjsonValue(extrasObj?.get("data"))
            ?: tryParseEjsonValue(extraBag)   // Android：extra 整袋即业务体（RN :127）
            ?: tryParseEjsonValue(raw["extra"]) // extra 本身即 ejson 字符串（RN :128）

        if (fromEjson != null) {
            return mergeTopLevelVoiceFields(bag, fromEjson)
        }

        if (asString(bag["rid"]) != null && asString(bag["type"]) != null) {
            return mergeTopLevelVoiceFields(bag, bag)
        }

        if (asString(payload?.get("rid")) != null && asString(payload?.get("type")) != null) {
            return mergeTopLevelVoiceFields(bag, payload!!)
        }

        // 仅有 rid、无 type 时按频道兜底（部分厂商通道会丢 type，RN :142-157）
        val bareRid = asString(bag["rid"])
        if (bareRid != null) {
            return mergeTopLevelVoiceFields(bag, buildJsonObject {
                put("rid", JsonPrimitive(bareRid))
                put("type", JsonPrimitive(asString(bag["type"]) ?: "c"))
                put("name", JsonPrimitive(asString(bag["name"]) ?: ""))
                put("sender", asRecord(bag["sender"]) ?: buildJsonObject {
                    put("username", JsonPrimitive(""))
                    put("name", JsonPrimitive(""))
                })
                put("host", JsonPrimitive(asString(bag["host"]) ?: ""))
                put("messageType", JsonPrimitive(asString(bag["messageType"]) ?: ""))
                put("messageId", JsonPrimitive(asString(bag["messageId"]) ?: ""))
            })
        }

        // 仅顶层 oncall 字段 + roomId（RN :159-164）
        val roomId = asString(bag["roomId"]) ?: asString(bag["rid"])
        val msgType = asString(bag["msgType"])
        if (roomId != null && msgType == "oncall") {
            return mergeTopLevelVoiceFields(bag, buildMinimalOncallPayload(bag, roomId))
        }

        return null
    }

    /** RN resolvePushRoomTitle :39-49：d=sender.username；l=sender.name ?? name；其余 name。 */
    fun resolvePushRoomTitle(payload: PushPayload): String? = when (payload.type) {
        "d" -> payload.senderUsername
        "l" -> payload.senderName ?: payload.name
        else -> payload.name
    }

    /** RN buildVoiceOncallParams :88-110（callMsg 解析归 M10，仅识别+透传标志）。 */
    private fun buildVoiceOncallParams(payload: PushPayload): PushVoiceOncallParams? {
        val isOncall = payload.msgType == "oncall" ||
            payload.messageType == "oncall" ||
            payload.oncallFlag
        if (!isOncall) return null
        return PushVoiceOncallParams(
            recordId = payload.recordId,
            org = payload.org,
            isCallEnd = payload.isCallEnd,
        )
    }

    /**
     * RN parsePushPayload :125-148：ejson 提取 → ROOM_TYPE_MAP → 标题 → 导航参数。
     * messageId 为空容忍缺失（坑 8：无高亮仍进房）。
     */
    fun parsePushPayload(raw: JsonObject): PushNavigationParams? {
        val payload = extractPushEjsonPayload(raw)
        if (payload == null || payload.rid.isEmpty()) return null

        return PushNavigationParams(
            rid = payload.rid,
            t = ROOM_TYPE_MAP[payload.type] ?: "c",
            title = resolvePushRoomTitle(payload),
            messageId = payload.messageId?.takeIf { it.isNotEmpty() },
            host = payload.host?.takeIf { it.isNotEmpty() },
            isCall = payload.messageType == "jitsi_call_started",
            voiceOncall = buildVoiceOncallParams(payload),
        )
    }
}
