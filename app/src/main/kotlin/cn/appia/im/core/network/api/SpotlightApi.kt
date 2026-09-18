package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 转发选择页聚合搜索行（RN spotlight.ts ForwardSearchRow 同构：用户 / 房间两型）。 */
sealed interface ForwardSearchRow {
    /** RN key：`user:{username}` / `room:{rid}`（列表 key 与勾选去重共用）。 */
    val key: String

    data class User(
        val userId: String,
        val username: String,
        val displayName: String,
        val subtitle: String? = null,
    ) : ForwardSearchRow {
        override val key: String get() = "user:$username"
    }

    data class Room(val rid: String, val title: String, val t: String) : ForwardSearchRow {
        override val key: String get() = "room:$rid"
    }
}

/**
 * spotlightv2 REST 包 DDP call（RN src/services/api/spotlight.ts）。
 * 仅移植 T9 所需的 `fetchForwardSelectSearch`（fetchSpotlightV2Users 归 M4 联系人域补）。
 */
object SpotlightApi {

    /**
     * 转发弹窗聚合搜索（RN fetchForwardSelectSearch spotlight.ts:67-151 同参逐位）：
     * `[text, [], {users,rooms,includeFederatedRooms,isMessageFull}, null, 50,60,60, true]`。
     */
    suspend fun fetchForwardSelectSearch(sdk: RocketSdk, searchText: String): List<ForwardSearchRow> {
        val text = searchText.trim()
        if (text.isEmpty()) return emptyList()
        val raw = sdk.methodCall(
            "spotlightv2",
            listOf(
                JsonPrimitive(text),
                JsonArray(emptyList()),
                buildJsonObject {
                    put("users", true)
                    put("rooms", true)
                    put("includeFederatedRooms", true)
                    put("isMessageFull", false)
                },
                JsonNull,
                JsonPrimitive(50),
                JsonPrimitive(60),
                JsonPrimitive(60),
                JsonPrimitive(true),
            ),
        )
        return parseForwardSelectSearch(raw)
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }

private fun JsonObject.boolFalse(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content == "false"

/** RN parseForwardSelectSearch 逐条移植（user 去重/联邦剔除/inactive 剔除/d 型房转 user/排序）。 */
internal fun parseForwardSelectSearch(raw: JsonElement?): List<ForwardSearchRow> {
    val res = raw as? JsonObject ?: JsonObject(emptyMap())
    val rows = mutableListOf<ForwardSearchRow>()
    val seen = mutableSetOf<String>()

    fun pushUser(userId: String, username: String, displayName: String, subtitle: String? = null) {
        // RN :97 联邦用户剔除（username 含 ':'）+ 空 id/username 剔除 + key 去重
        if (userId.isEmpty() || username.isEmpty() || username.contains(":")) return
        val key = "user:$username"
        if (!seen.add(key)) return
        rows += ForwardSearchRow.User(userId, username, displayName, subtitle)
    }

    fun pushRoom(value: JsonElement?) {
        val row = value as? JsonObject ?: return
        val rid = row.str("_id") ?: return
        val t = row.str("t") ?: "p"
        if (t == "d") {
            // d 型房间：name 即对方 username（RC 约定），fname 为显示名；userId 取 rid（RN 同）
            val username = row.str("username") ?: row.str("name") ?: return
            val fname = row.str("fname")
            pushUser(rid, username, fname ?: username)
            return
        }
        val key = "room:$rid"
        if (!seen.add(key)) return
        rows += ForwardSearchRow.Room(rid, row.str("fname") ?: row.str("dname") ?: row.str("name") ?: rid, t)
    }

    (res["users"] as? JsonArray)?.forEach { value ->
        val row = value as? JsonObject ?: return@forEach
        if (row.boolFalse("active")) return@forEach // RN :134 inactive 剔除
        val userId = row.str("_id") ?: ""
        val username = row.str("username") ?: ""
        val name = row.str("name")
        pushUser(userId, username, name ?: username, row.str("primaryOrgName"))
    }
    (res["rooms"] as? JsonArray)?.forEach(::pushRoom)
    (res["usersInRooms"] as? JsonArray)?.forEach { block ->
        pushRoom((block as? JsonObject)?.get("room"))
    }

    // RN forwardRowRank：user=1、房间 t=='p'=2、其余=3；JS sort 稳定，同序
    return rows.sortedBy { row ->
        when (row) {
            is ForwardSearchRow.User -> 1
            is ForwardSearchRow.Room -> if (row.t == "p") 2 else 3
        }
    }
}
