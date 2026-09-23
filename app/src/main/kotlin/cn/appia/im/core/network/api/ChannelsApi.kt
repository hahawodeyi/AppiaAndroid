package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** RN CreateChannelByMembersResult（services/api/channels.ts:7-12）：channel/group 二选一。 */
data class CreateChannelResult(
    val rid: String?,
    val name: String?,
)

/**
 * 建频道/发起 DM（RN src/services/api/channels.ts createChannelByMembers +
 * src/services/api/im.ts createDirectRoom 对照）：
 * - `POST channels.create`：`{name:'', members, depIds, readOnly:false,
 *   extraData:{broadcast:false, encrypted:false, federated:false, all, rt:''}}`（RN :21-38 逐字段）
 * - `POST im.create`：`{username}`（RN im.ts:13-15；M3 openDirectMessage 链复用）
 * 非 2xx 抛 ApiException（AuthInterceptor）。
 */
object ChannelsApi {

    /** RN createChannelByMembers（channels.ts:19-39）：不填名建频道（type=false public）。 */
    suspend fun createChannelByMembers(
        sdk: RocketSdk,
        users: List<String>,
        depIds: List<String> = emptyList(),
        all: Boolean,
    ): JsonElement = sdk.post(
        "channels.create",
        buildJsonObject {
            put("name", "")
            put("members", kotlinx.serialization.json.JsonArray(users.map(::JsonPrimitive)))
            put("depIds", kotlinx.serialization.json.JsonArray(depIds.map(::JsonPrimitive)))
            put("readOnly", false)
            put(
                "extraData",
                buildJsonObject {
                    put("broadcast", false)
                    put("encrypted", false)
                    put("federated", false)
                    put("all", all)
                    put("rt", "")
                },
            )
        },
    )

    /** RN createDirectRoom（im.ts:13-15）：`POST im.create {username}`。 */
    suspend fun createDirectRoom(sdk: RocketSdk, username: String): JsonElement =
        sdk.post("im.create", buildJsonObject { put("username", username) })

    /**
     * RN handleConfirm 响应解析（CreateChannelMembersScreen/index.tsx:282-294）：
     * rid = channel._id || group._id；名称 = channel/group 的 fname || dname || name。
     */
    fun parseCreateChannelResult(raw: JsonElement?): CreateChannelResult {
        val res = raw as? JsonObject ?: return CreateChannelResult(null, null)

        fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.contentOrNull?.trim()

        val channel = res["channel"] as? JsonObject
        val group = res["group"] as? JsonObject
        val rid = channel?.str("_id") ?: group?.str("_id")
        val name = channel?.str("fname") ?: channel?.str("dname") ?: channel?.str("name")
            ?: group?.str("fname") ?: group?.str("dname") ?: group?.str("name")
        return CreateChannelResult(rid, name)
    }
}
