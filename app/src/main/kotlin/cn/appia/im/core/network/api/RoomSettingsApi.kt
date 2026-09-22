package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** RN IRoomAnnouncementFile（lib/chat/roomAnnouncements.ts:16-20）：{fileName, fileUrl, fileType?}。 */
data class RoomAnnouncementFile(
    val fileName: String,
    val fileUrl: String,
    val fileType: String? = null,
)

/** RN SaveRoomSettingsParams.roomAnnouncementData（roomSettings.ts:12-17）；type 仅 'delete'。 */
data class RoomAnnouncementData(
    val id: String? = null,
    val message: String? = null,
    val files: List<RoomAnnouncementFile>? = null,
    val type: String? = null,
)

/** RN SaveRoomSettingsParams（roomSettings.ts:4-18）——缺省字段不编码（JS undefined 同义）。 */
data class SaveRoomSettingsParams(
    val appiaUsage: List<String>? = null,
    val roomName: String? = null,
    val roomAvatar: String? = null,
    val roomDescription: String? = null,
    val roomTopic: String? = null,
    val encrypted: Boolean? = null,
    val federated: Boolean? = null,
    val roomAnnouncementData: RoomAnnouncementData? = null,
)

/** RN RoomNotificationSettings（roomSettings.ts:30-34）：值为字符串（调用方传 '1'/'0'）。 */
data class RoomNotificationSettings(
    val disableNotifications: String? = null,
    val muteGroupMentions: String? = null,
    val hideUnreadStatus: String? = null,
)

/**
 * 群组设置/角色端点（RN src/services/api/roomSettings.ts 逐端点对照）。
 * 传输定案（读 RN 源）：saveRoomSettings/addUsersToRoom 走 Meteor method（RN 前者 callMethodRest
 * REST 信封、后者 callMethod DDP 直调——Android 统一走 [RocketSdk.methodCall] REST 信封，RecallApi.editMessage
 * 先例）；其余端点纯 REST。非 2xx 抛 ApiException（AuthInterceptor）——只发请求不做本地写，归调用方。
 */
object RoomSettingsApi {

    /** RN roomTypeToPrefix（roomSettings.ts:61-64，postLeaveRoom 内联同 map）：c→channels p→groups d→im，未命中兜底 channels。 */
    private fun roomTypeToPrefix(t: String): String = when (t) {
        "c" -> "channels"
        "p" -> "groups"
        "d" -> "im"
        else -> "channels"
    }

    /** RN role 类端点统一 body `{roomId, userId}`。 */
    private fun roleBody(rid: String, userId: String): JsonElement = buildJsonObject {
        put("roomId", rid)
        put("userId", userId)
    }

    /**
     * Meteor `saveRoomSettings`（RN :21-28，callMethodRest `[rid, params]` 双元素参数组）。
     * 响应 `{result?: boolean, rid?: string}`（method.call 信封解析后）。
     */
    suspend fun postSaveRoomSettings(sdk: RocketSdk, rid: String, settings: SaveRoomSettingsParams): JsonElement? =
        sdk.methodCall("saveRoomSettings", listOf(JsonPrimitive(rid), settings.toJson()))

    /** `POST rooms.saveNotification` body `{roomId, notifications}`（RN :37-40）。 */
    suspend fun postSaveRoomNotification(sdk: RocketSdk, rid: String, notifications: RoomNotificationSettings): JsonElement =
        sdk.post("rooms.saveNotification", buildJsonObject {
            put("roomId", rid)
            put("notifications", notifications.toJson())
        })

    /**
     * `POST rooms.favorite`（RN :43-44）。RN 在 subscriptions.ts:11-13 与 roomSettings.ts:42-44
     * 两处同 wire 重复定义——Android 单实现（[SubscriptionsApi.postRoomsFavorite]，ChatRowActions 已消费），此处转发。
     */
    suspend fun postRoomsFavorite(sdk: RocketSdk, rid: String, favorite: Boolean) =
        SubscriptionsApi.postRoomsFavorite(sdk, rid, favorite)

    /**
     * `POST rooms.like` body `{roomId, like}`（RN :57-59）。
     * RN 无 UI 调用方（roomListClassification.ts:44 注明 like 是「关注频道」与置顶无关，置顶走 favorite）——
     * API 层落地不接线（同 GhostOwner 口径）。
     */
    suspend fun postRoomsLike(sdk: RocketSdk, rid: String, like: Boolean): JsonElement =
        sdk.post("rooms.like", buildJsonObject {
            put("roomId", rid)
            put("like", like)
        })

    /** `POST {channels|groups|im}.leave` body `{roomId}`（RN :46-55，t 前缀映射）。 */
    suspend fun postLeaveRoom(sdk: RocketSdk, rid: String, roomType: String): JsonElement =
        sdk.post("${roomTypeToPrefix(roomType)}.leave", buildJsonObject { put("roomId", rid) })

    /**
     * `GET {prefix}.roles?roomId=`（RN :67-70）。响应 `{roles:[{u:{_id}, roles:string[]}]}`——
     * 合并/筛当前用户归 T5 消费方（RN useCanRemoveRoomMember :22 `roles.find(r => r.u._id===...)`）。
     */
    suspend fun getRoomRoles(sdk: RocketSdk, rid: String, t: String): JsonElement =
        sdk.get("${roomTypeToPrefix(t)}.roles", mapOf("roomId" to rid))

    /** `POST {prefix}.{add|remove}Owner`（RN :73-77）。 */
    suspend fun postToggleRoomOwner(sdk: RocketSdk, rid: String, t: String, userId: String, isOwner: Boolean): JsonElement =
        sdk.post("${roomTypeToPrefix(t)}.${if (isOwner) "addOwner" else "removeOwner"}", roleBody(rid, userId))

    /** `POST {prefix}.{add|remove}Moderator`（RN :80-84）。 */
    suspend fun postToggleRoomModerator(sdk: RocketSdk, rid: String, t: String, userId: String, isModerator: Boolean): JsonElement =
        sdk.post("${roomTypeToPrefix(t)}.${if (isModerator) "addModerator" else "removeModerator"}", roleBody(rid, userId))

    /**
     * `POST {prefix}.{add|remove}GhostOwner`（RN :87-91）。RN API 层存在但**无任何 UI 调用方**
     * （grep postToggleRoomGhostOwner 零消费）——Android 落地不接 UI，供后续接线。
     */
    suspend fun postToggleRoomGhostOwner(sdk: RocketSdk, rid: String, t: String, userId: String, isGhostOwner: Boolean): JsonElement =
        sdk.post("${roomTypeToPrefix(t)}.${if (isGhostOwner) "addGhostOwner" else "removeGhostOwner"}", roleBody(rid, userId))

    /**
     * 从房间移除成员（RN :94-107）：team 主房先 `POST teams.removeMember {teamId, userId}`
     * （teamId trim 后空串跳过），再 `POST {prefix}.kick {roomId, userId}`——顺序执行，
     * teams.removeMember 失败即抛出、**不发 kick**（RN await 链同语义）。
     */
    suspend fun postRemoveUserFromRoom(
        sdk: RocketSdk,
        rid: String,
        t: String,
        userId: String,
        teamId: String? = null,
    ): JsonElement {
        val trimmed = teamId?.trim()
        if (!trimmed.isNullOrEmpty()) {
            sdk.post("teams.removeMember", buildJsonObject {
                put("teamId", trimmed)
                put("userId", userId)
            })
        }
        return sdk.post("${roomTypeToPrefix(t)}.kick", roleBody(rid, userId))
    }

    /**
     * 向已有房间添加成员（RN :110-113，Meteor `addUsersToRoom` 单对象参数
     * `{rid, users, depIds, isShareRecord?}`——isShareRecord 缺省不编码；RN 为 DDP 直调，
     * Android methodCall REST 信封等价）。
     */
    suspend fun postAddUsersToRoom(
        sdk: RocketSdk,
        rid: String,
        users: List<String>,
        depIds: List<String>,
        isShareRecord: Boolean? = null,
    ): JsonElement? = sdk.methodCall("addUsersToRoom", listOf(buildJsonObject {
        put("rid", rid)
        put("users", JsonArray(users.map(::JsonPrimitive)))
        put("depIds", JsonArray(depIds.map(::JsonPrimitive)))
        isShareRecord?.let { put("isShareRecord", it) }
    }))

    /** `POST local.removeGroupUsersToRoom` body `{rid, depIds}`（RN :116-119）。 */
    suspend fun postRemoveDepartmentFromRoom(sdk: RocketSdk, rid: String, depIds: List<String>): JsonElement =
        sdk.post("local.removeGroupUsersToRoom", buildJsonObject {
            put("rid", rid)
            put("depIds", JsonArray(depIds.map(::JsonPrimitive)))
        })
}

/** RN SaveRoomSettingsParams 序列化：仅编码非 null 字段（JS undefined 键缺省同义）。 */
private fun SaveRoomSettingsParams.toJson(): JsonElement = buildJsonObject {
    appiaUsage?.let { put("appiaUsage", JsonArray(it.map(::JsonPrimitive))) }
    roomName?.let { put("roomName", it) }
    roomAvatar?.let { put("roomAvatar", it) }
    roomDescription?.let { put("roomDescription", it) }
    roomTopic?.let { put("roomTopic", it) }
    encrypted?.let { put("encrypted", it) }
    federated?.let { put("federated", it) }
    roomAnnouncementData?.let { data ->
        put("roomAnnouncementData", buildJsonObject {
            data.id?.let { put("_id", it) }
            data.message?.let { put("message", it) }
            data.files?.let { files -> put("files", JsonArray(files.map { it.toJson() })) }
            data.type?.let { put("type", it) }
        })
    }
}

private fun RoomAnnouncementFile.toJson(): JsonElement = buildJsonObject {
    put("fileName", fileName)
    put("fileUrl", fileUrl)
    fileType?.let { put("fileType", it) }
}

private fun RoomNotificationSettings.toJson(): JsonElement = buildJsonObject {
    disableNotifications?.let { put("disableNotifications", it) }
    muteGroupMentions?.let { put("muteGroupMentions", it) }
    hideUnreadStatus?.let { put("hideUnreadStatus", it) }
}
