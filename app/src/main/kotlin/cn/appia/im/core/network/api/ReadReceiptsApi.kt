package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 已读回执用户（RN types/readReceipt.ts IReadReceiptUser；明细页只显头像+名字，ts 不渲染）。 */
data class ReadReceiptUser(
    val _id: String? = null,
    val username: String? = null,
    val name: String? = null,
)

/** 单条回执（RN IReadReceipt 子集：roomId/messageId/ts 明细页不消费，不建列）。 */
data class ReadReceipt(
    val _id: String? = null,
    val userId: String? = null,
    val user: ReadReceiptUser? = null,
)

/** GET appia/room/members 的分组 members（RN IFederatedMembersGroup）。 */
data class RoomMembersGroup(val members: List<ReadReceiptUser> = emptyList())

/** RN {success, data}；Android `sdk.get` 把 `data` 平铺出去，见 [ReadReceiptsApi.getFederatedRoomMembers]。 */
data class RoomMembersResult(val success: Boolean = false, val data: List<RoomMembersGroup> = emptyList())

/** room.firsUnread 结果（RN FirstUnreadResponse；`message._id` → messageId）。 */
data class FirstUnread(val success: Boolean = false, val messageId: String? = null, val unread: Int = 0)

/**
 * 已读回执三端点（逐字对照 appiaMobile `src/services/api/readReceipts.ts` / `roomFirstUnread.ts`）。
 * 只发请求不做本地写；列表过滤/差集归 UI 层（RN ReadReceiptScreen 同分工）。
 */
object ReadReceiptsApi {

    /** RN readReceipts.ts:4-11：`GET chat.getMessageReadReceipts?messageId` → receipts[]（缺失回 []）。 */
    suspend fun getMessageReadReceipts(sdk: RocketSdk, messageId: String): List<ReadReceipt> {
        val res = sdk.get("chat.getMessageReadReceipts", mapOf("messageId" to messageId))
        val arr = (res as? JsonObject)?.get("receipts") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ReadReceipt(_id = o.str("_id"), userId = o.str("userId"), user = (o["user"] as? JsonObject)?.toUser())
        }
    }

    /**
     * RN readReceipts.ts:14-22：`GET appia/room/members?rid` → `{success, data:[{members}]}`。
     * Android `sdk.get` 按 `data ?? resp` 平铺（绑定裁定）：响应只剩 data 数组时直接当分组列表；
     * 未平铺形态（无 data 键的 `{success}` 等）按对象读 success/data，两种 wire 都吃。
     */
    suspend fun getFederatedRoomMembers(sdk: RocketSdk, rid: String): RoomMembersResult =
        when (val res = sdk.get("appia/room/members", mapOf("rid" to rid))) {
            is JsonArray -> RoomMembersResult(success = true, data = parseGroups(res))
            is JsonObject -> RoomMembersResult(
                success = res.bool("success"),
                data = parseGroups(res["data"] as? JsonArray),
            )
            else -> RoomMembersResult()
        }

    /** RN roomFirstUnread.ts:9-16：`GET room.firsUnread`（**拼写保留**）→ `{success,message?:{_id},unread}`。 */
    suspend fun getFirstUnread(sdk: RocketSdk, rid: String): FirstUnread {
        val res = sdk.get("room.firsUnread", mapOf("rid" to rid)) as? JsonObject ?: return FirstUnread()
        return FirstUnread(
            success = res.bool("success"),
            messageId = (res["message"] as? JsonObject)?.str("_id"),
            unread = (res["unread"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt() ?: 0,
        )
    }

    private fun parseGroups(arr: JsonArray?): List<RoomMembersGroup> =
        arr?.mapNotNull { el ->
            (el as? JsonObject)?.let { o ->
                RoomMembersGroup((o["members"] as? JsonArray)?.mapNotNull { m -> (m as? JsonObject)?.toUser() }.orEmpty())
            }
        }.orEmpty()

    private fun JsonObject.toUser() = ReadReceiptUser(_id = str("_id"), username = str("username"), name = str("name"))

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
