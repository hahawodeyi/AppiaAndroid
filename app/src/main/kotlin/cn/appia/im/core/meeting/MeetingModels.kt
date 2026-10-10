package cn.appia.im.core.meeting

import cn.appia.im.core.chat.RoomAnnouncement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 会议 wire 模型（逐字段对照 appiaMobile/src/types/meeting.ts + meetingSummary.ts）。
 * RN 全部字段 optional → Kotlin 可空 + 默认 null；`encodeDefaults=false + explicitNulls=false`
 * 对齐 RN `JSON.stringify` 丢 undefined 键（TipTapJsonConverter 同口径）。
 */

/** RN IMeetingUser。 */
@Serializable
data class MeetingUser(
    val id: String? = null,
    @SerialName("_id") val _id: String? = null,
    val username: String? = null,
    val name: String? = null,
    val source: String? = null,
)

/** RN IPeriodicInfo（periodicUnit/countType 保留 RN 字符串字面量：DAILY/WORKDAY/WEEKLY/MONTHLY/MONTHLYWEEK、TO_DATE/COUNT）。 */
@Serializable
data class PeriodicInfo(
    val id: String? = null,
    val periodicUnit: String? = null,
    val periodicInterval: Int? = null,
    val periodicCount: Int? = null,
    val periodicCountType: String? = null,
    val periodicEndDate: String? = null,
)

/** RN `roomId: string | number` wire 双形态 → 统一读为字符串（roomIdSerial）。 */
object StringOrNumberSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement()
        return when (el) {
            is JsonPrimitive -> el.contentOrNull.orEmpty()
            else -> decoder.decodeString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/** RN IMeeting（含 tencentMeetingId/periodicId/announcementId 编辑回传字段）。 */
@Serializable
data class Meeting(
    val id: String? = null,
    val meetingId: String? = null,
    val rid: String? = null,
    val subject: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val users: List<MeetingUser> = emptyList(),
    val meetCode: String? = null,
    val meetUrl: String? = null,
    val desc: String? = null,
    val meetingMaterials: String? = null,
    val periodic: Boolean? = null,
    val periodicInfo: PeriodicInfo? = null,
    val periodicId: String? = null,
    val createTxMeeting: Boolean? = null,
    val tencentMeetingId: String? = null,
    val announcementId: String? = null,
    val timezone: String? = null,
    @Serializable(with = StringOrNumberSerializer::class) val roomId: String? = null,
    val meetingRoomName: String? = null,
    val extraMeetingRoom: String? = null,
    val email: String? = null,
    val enableNotification: Boolean? = null,
    val mediaSetType: Int? = null,
)

/** RN IMeetingRoom（meetingRoomList 响应项，bookRecords 为该会议室已有预订）。 */
@Serializable
data class MeetingRoom(
    val id: String? = null,
    val floor: String? = null,
    val name: String? = null,
    val city: String? = null,
    val building: String? = null,
    val capacity: Int? = null,
    val device: String? = null,
    val enable: Boolean? = null,
    val bookRecords: List<Meeting> = emptyList(),
)

/** RN ISelectedUser（纪要 host/participant 等）。 */
@Serializable
data class SummaryUser(
    val username: String? = null,
    val name: String? = null,
    val source: String? = null,
)

/** RN ISummaryFile（id/ossKey/path 服务端形态与 uid/name 前端形态并存）。 */
@Serializable
data class SummaryFile(
    val uid: String? = null,
    val status: String? = null,
    val name: String? = null,
    val type: String? = null,
    val fileName: String? = null,
    val fileUrl: String? = null,
    val id: String? = null,
    val ossKey: String? = null,
    val path: String? = null,
)

/** RN IMeetingSummaryContentBlock。 */
@Serializable
data class SummaryContentBlock(
    val checked: Boolean = false,
    val content: String = "",
)

/** RN IMeetingSummary（会议纪要——面板数据来自房间公告 meeting 字段，无独立接口）。 */
@Serializable
data class MeetingSummary(
    val id: String? = null,
    val topic: String? = null,
    val rid: String? = null,
    val timeBegin: String? = null,
    val timeEnd: String? = null,
    val place: String? = null,
    val openRange: Int? = null,
    val appendix: String? = null,
    val host: List<SummaryUser> = emptyList(),
    val participant: List<SummaryUser> = emptyList(),
    val absentee: List<SummaryUser> = emptyList(),
    val leader: List<SummaryUser> = emptyList(),
    val cc: List<SummaryUser> = emptyList(),
    val fill: List<SummaryUser> = emptyList(),
    val content: List<SummaryContentBlock> = emptyList(),
    val status: Int? = null,
    val files: List<SummaryFile> = emptyList(),
)

/** 会议域共用 wire Json：未知键丢弃 + 缺省/空不编码（RN undefined 同形）。 */
val meetingJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** 宽容解 Meeting（公告保留的 JsonObject 原文可能残缺）。 */
fun JsonObject.toMeetingOrNull(): Meeting? =
    runCatching { meetingJson.decodeFromJsonElement<Meeting>(this) }.getOrNull()

/** 宽容解 MeetingSummary。 */
fun JsonObject.toMeetingSummaryOrNull(): MeetingSummary? =
    runCatching { meetingJson.decodeFromJsonElement<MeetingSummary>(this) }.getOrNull()

/** 批量宽容解（bookRecords 等数组）。 */
fun List<JsonObject>.toMeetingsOrNull(): List<Meeting> = mapNotNull { it.toMeetingOrNull() }

/**
 * RoomAnnouncements.kt 消费侧类型化访问（公告解析层保留的 meetingRoomBookRecord/meeting
 * JsonObject 原文在此转为 [Meeting]/[MeetingSummary]；解不动返回 null，面板按无数据降级）。
 */
fun RoomAnnouncement.meetingBooking(): Meeting? = meetingRoomBookRecord?.toMeetingOrNull()

/** 公告 meeting 字段 → [MeetingSummary]。 */
fun RoomAnnouncement.meetingSummary(): MeetingSummary? = meeting?.toMeetingSummaryOrNull()

/** [Meeting] 成功/消息宽容读（MeetingApi 响应共用）。 */
internal fun JsonObject.boolOrFalse(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull ?: false

/** 字符串宽容读。 */
internal fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
