package cn.appia.im.domain.presence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * stream-user-presence 帧解析（RN src/services/presence/parseStreamUserPresenceMessage.ts 逐行）：
 * `fields.uid` + `fields.args[0] = [?, statusIndex, statusText?]` —— **索引协议照抄**：
 * statusIndex 经 [USER_STATUS_INDEX]（RN USER_STATUSES 数组序）映射；越界回退 OFFLINE
 * （RN `USER_STATUSES[statusIndex] ?? 'offline'`）；statusIndex 非数字原始量（字符串直传）
 * 整帧丢弃——协议红线：服务端只发索引，字符串直传全 miss。
 */
object PresenceStreamParser {

    /** RN USER_STATUSES 数组序：['offline','online','away','busy','disabled','loading']。 */
    val USER_STATUS_INDEX: List<TUserStatus> = listOf(
        TUserStatus.OFFLINE,
        TUserStatus.ONLINE,
        TUserStatus.AWAY,
        TUserStatus.BUSY,
        TUserStatus.DISABLED,
        TUserStatus.LOADING,
    )

    /** 解析产物（RN ParsedStreamUserPresence）。 */
    data class Parsed(val userId: String, val status: TUserStatus, val statusText: String? = null)

    /** DDP changed 帧（collection 路由整帧 JSON）→ 单条状态；坏帧/字段缺失/索引非数字 → null（RN 同静默丢弃）。 */
    fun parse(ddpMessage: JsonElement): Parsed? {
        val fields = (ddpMessage as? JsonObject)?.get("fields") as? JsonObject ?: return null
        val uid = fields.str("uid") ?: return null
        val userStatus = fields["args"] as? JsonArray ?: return null
        val entry = userStatus.firstOrNull() as? JsonArray ?: return null
        // RN typeof statusIndex !== 'number' → null：仅数字原始量通过（字符串 "1" 也 miss）
        val statusIndex = (entry.getOrNull(1) as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && !it.isString }
            ?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return null
        val status = USER_STATUS_INDEX.getOrNull(statusIndex) ?: TUserStatus.OFFLINE // 越界回退
        val statusText = (entry.getOrNull(2) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
        return Parsed(userId = uid, status = status, statusText = statusText)
    }

    private fun JsonObject.str(key: String): String? = when (val v = get(key)) {
        null, is JsonNull -> null
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotEmpty() }
        else -> null
    }
}
