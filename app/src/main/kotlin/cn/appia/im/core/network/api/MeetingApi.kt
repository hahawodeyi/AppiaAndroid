package cn.appia.im.core.network.api

import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.MeetingRoom
import cn.appia.im.core.meeting.meetingJson
import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** RN MeetingMutationResponse（meeting.ts:4）：{success?, message?}。 */
data class MeetingMutationResponse(val success: Boolean = false, val message: String? = null)

/**
 * 会议 wire 五端点 + 成员复用（逐字对照 appiaMobile/src/services/api/meeting.ts:8-38）。
 * 全部 `sdk.post` REST（RN sdk.post 同通道）；只发请求不做本地写。
 * 第 6 端点 `GET appia/room/members` 与已读回执 M3 完全同形 → 复用
 * [ReadReceiptsApi.getFederatedRoomMembers]，经 core/meeting 的
 * mapFederatedMembersToMeetingUsers 转 IMeetingUser。删除普通/整串走既有
 * [RoomSettingsApi.postSaveRoomSettings]（executeMeetingDelete.ts，T5 消费）；
 * RTC credentials 端点归 T6，不在本层。
 */
object MeetingApi {

    private fun parseMutation(res: Any?): MeetingMutationResponse {
        val o = res as? JsonObject ?: return MeetingMutationResponse()
        return MeetingMutationResponse(
            success = (o["success"] as? JsonPrimitive)?.booleanOrNull ?: false,
            message = (o["message"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private suspend fun postMutation(sdk: RocketSdk, endpoint: String, params: Meeting): MeetingMutationResponse =
        parseMutation(sdk.post(endpoint, meetingJson.encodeToJsonElement(params)))

    /** RN meeting.ts:8-11：`POST meetingRoomBook`（创建）。 */
    suspend fun meetingRoomBook(sdk: RocketSdk, params: Meeting): MeetingMutationResponse =
        postMutation(sdk, "meetingRoomBook", params)

    /** RN meeting.ts:13-16：`POST meetingRoomBookRecordUpdate`（编辑普通/周期整串）。 */
    suspend fun meetingRoomBookRecordUpdate(sdk: RocketSdk, params: Meeting): MeetingMutationResponse =
        postMutation(sdk, "meetingRoomBookRecordUpdate", params)

    /** RN meeting.ts:18-21：`POST editPeriodicSingleMeeting`（编辑周期单次）。 */
    suspend fun editPeriodicSingleMeeting(sdk: RocketSdk, params: Meeting): MeetingMutationResponse =
        postMutation(sdk, "editPeriodicSingleMeeting", params)

    /** RN meeting.ts:23-30：`POST cancelPeriodicSingleMeeting` body `{rid, announcementId}`。 */
    suspend fun cancelPeriodicSingleMeeting(sdk: RocketSdk, rid: String, announcementId: String): MeetingMutationResponse =
        parseMutation(
            sdk.post(
                "cancelPeriodicSingleMeeting",
                buildJsonObject {
                    put("rid", rid)
                    put("announcementId", announcementId)
                },
            ),
        )

    /**
     * RN meeting.ts:32-38：`POST meetingRoomList {startTime, endTime}` → `data` 数组，缺失回 []。
     * `sdk.post` 响应按 `data ?? resp` 平铺（RocketSdk 统一口径）——已平铺数组与未平铺对象两种都吃
     * （ReadReceiptsApi.getFederatedRoomMembers 同裁定）；单项解不动整表容忍（mapNotNull 语义近似）。
     */
    suspend fun meetingRoomList(sdk: RocketSdk, startTime: String, endTime: String): List<MeetingRoom> {
        val res = sdk.post(
            "meetingRoomList",
            buildJsonObject {
                put("startTime", startTime)
                put("endTime", endTime)
            },
        )
        val arr = when (res) {
            is JsonArray -> res
            is JsonObject -> res["data"] as? JsonArray
            else -> null
        } ?: return emptyList()
        return runCatching { meetingJson.decodeFromJsonElement<List<MeetingRoom>>(arr) }.getOrDefault(emptyList())
    }

    /** RN meeting 成员端点（readReceipts.ts:14-22 同端点，M3 已实现）：复用。 */
    suspend fun getRoomMembers(sdk: RocketSdk, rid: String): RoomMembersResult =
        ReadReceiptsApi.getFederatedRoomMembers(sdk, rid)
}
