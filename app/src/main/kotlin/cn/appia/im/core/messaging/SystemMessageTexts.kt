package cn.appia.im.core.messaging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 系统消息 t→文案 映射（appiaMobile/src/lib/message/getInfoMessage.ts:40-166 **逐条移植**，
 * 分支顺序与参数名一一对应）：t(key, params) 由调用方注入（UI 侧 = 资源查 key + `{{x}}` 占位替换）。
 * 缺 key 回退由 t 实现决定（RN i18next 与 Android t() 同为回退显示 key 本身）。
 *
 * 条件分支保持 RN 原语义：
 * - `ru`：msg===作者名 → User_removed_by1（无 userBy）；否则 User_removed_by 双参；
 * - `rollback-message`：rollbacker 存在且 ≠ 作者 → Message_recalled_by_others；
 * - `subscription-role-*`：role==='leader' 走 pinned/unpinned（role **不**过 t，原文直传）；
 *   其余 role 过 ROLE_MAP（owner/moderator）二次 t，未命中原文直传；
 * - `system` 原样 msg、未知 t → Unsupported_system_message、无 t → 空串。
 */
object SystemMessageTexts {

    private val ROLE_MAP = mapOf(
        "owner" to "role_name_owner1",
        "moderator" to "role_name_moderator1",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** RN parseAuthorName：u JSON 的 name || username，坏 JSON → ''。 */
    private fun parseAuthorName(uRaw: String): String =
        runCatching { json.parseToJsonElement(uRaw).jsonObject }
            .map { o -> o.str("name") ?: o.str("username") ?: "" }
            .getOrDefault("")

    /** RN parseRollbackerName：rollbacker JSON 的 name || username，缺省/坏 JSON → ''。 */
    private fun parseRollbackerName(raw: String?): String =
        raw?.let { parseAuthorName(it) } ?: ""

    fun infoText(
        type: String?,
        msg: String?,
        u: String,
        role: String?,
        roleName: String?,
        rollbacker: String?,
        comment: String?,
        t: (key: String, params: Map<String, String>) -> String,
    ): String {
        if (type == null) return ""
        val username = parseAuthorName(u)
        val m = msg ?: ""
        val r = role ?: ""
        val rn = roleName ?: ""
        val rb = parseRollbackerName(rollbacker)
        val c = comment ?: ""
        return when (type) {
            // ── 有作者名 ──
            "rm" -> t("Message_removed", emptyMap())
            "uj" -> t("Has_joined_the_channel", emptyMap())
            "ujt" -> t("User_joined_the_team", emptyMap())
            "ut" -> t("User_joined_the_conversation", emptyMap())
            "r" -> t("Room_name_changed_to", mapOf("name" to m, "userBy" to username))
            "ru" ->
                if (m == username) {
                    t("User_removed_by1", mapOf("userRemoved" to m))
                } else {
                    t("User_removed_by", mapOf("userRemoved" to m, "userBy" to username))
                }
            "au" -> t("User_added_to", mapOf("userBy" to username, "userAdded" to m))
            "user-muted" -> t("User_has_been_muted", mapOf("userMuted" to m))
            "room_changed_description" -> t("changed_room_description", mapOf("description" to m))
            "room_created_announcement" -> t("Room_created_announcement", mapOf("announcement" to m, "userBy" to username))
            "room_deleted_announcement" -> t("Room_deleted_announcement", mapOf("announcement" to m, "userBy" to username))
            "room_changed_announcement" -> t("Room_changed_announcement", mapOf("announcement" to m, "userBy" to username))
            "room_created_value_proposition" -> t("room_created_value_proposition", mapOf("proposition" to m, "userBy" to username))
            "room_changed_value_proposition" -> t("room_changed_value_proposition", mapOf("proposition" to m, "userBy" to username))
            "room_deleted_value_proposition" -> t("room_deleted_value_proposition", mapOf("proposition" to m, "userBy" to username))
            "room_changed_topic" -> t("room_changed_topic", mapOf("topic" to m))
            "room_changed_privacy" -> t("room_changed_type", mapOf("type" to m))
            "room_changed_avatar" -> t("Room_changed_avatar", mapOf("userBy" to username))
            "message_snippeted" -> t("Created_snippet", emptyMap())
            "room_e2e_disabled" -> t("Disabled_E2E_Encryption_for_this_room", emptyMap())
            "room_e2e_enabled" -> t("Enabled_E2E_Encryption_for_this_room", emptyMap())
            "removed-user-from-team" -> t("Removed__username__from_team", mapOf("user_removed" to m))
            "added-user-to-team" -> t("Added__username__to_team", mapOf("user_added" to m))
            "rollback-message" ->
                if (rb.isNotEmpty() && rb != username) {
                    t("Message_recalled_by_others", mapOf("user" to username, "rollbacker" to rb))
                } else {
                    t("Message_recalled", mapOf("userBy" to username))
                }
            "user-added-room-to-team" -> t("added__roomName__to_this_team", mapOf("roomName" to m))
            "user-converted-to-team" -> t("Converted__roomName__to_a_team", mapOf("roomName" to m))
            "user-converted-to-channel" -> t("Converted__roomName__to_a_channel", mapOf("roomName" to m))
            "user-deleted-room-from-team" -> t("Deleted__roomName__", mapOf("roomName" to m))
            "user-removed-room-from-team" -> t("Removed__roomName__from_the_team", mapOf("roomName" to m))
            "room-disallowed-reacting" -> t("room_disallowed_reactions", emptyMap())
            "room-allowed-reacting" -> t("room_allowed_reactions", emptyMap())
            "room-set-read-only" -> t("Room_set_read_only", mapOf("userBy" to username))
            "room-removed-read-only" -> t("Room_removed_read_only", mapOf("userBy" to username))
            "user-unmuted" -> t("User_has_been_unmuted", mapOf("userUnmuted" to m))
            "room-archived" -> t("room_archived", emptyMap())
            "room-unarchived" -> t("room_unarchived", emptyMap())
            "subscription-role-added" ->
                if (r == "leader") {
                    t("User_is_pinned", linkedMapOf("userBy" to username, "user" to m, "role" to rn.ifEmpty { r }))
                } else {
                    val roleKey = rn.ifEmpty { r }
                    t(
                        "User_was_set_role_by_",
                        linkedMapOf("userBy" to username, "user" to m, "role" to t(ROLE_MAP[roleKey] ?: roleKey, emptyMap())),
                    )
                }
            "subscription-role-removed" ->
                if (r == "leader") {
                    t("User_is_unpinned", linkedMapOf("userBy" to username, "user" to m, "role" to rn.ifEmpty { r }))
                } else {
                    val roleKey = rn.ifEmpty { r }
                    t(
                        "User_is_no_longer_role_by_",
                        linkedMapOf("userBy" to username, "user" to m, "role" to t(ROLE_MAP[roleKey] ?: roleKey, emptyMap())),
                    )
                }
            "message_pinned" -> t("Message_pinned", emptyMap())

            // ── 无作者名 ──
            "ul" -> t("User_left_this_channel", emptyMap())
            "ult" -> t("Has_left_the_team", emptyMap())
            "jitsi_call_started" -> t("Started_call", mapOf("userBy" to username))
            "omnichannel_placed_chat_on_hold" -> t("Omnichannel_placed_chat_on_hold", mapOf("comment" to c))
            "omnichannel_on_hold_chat_resumed" -> t("Omnichannel_on_hold_chat_resumed", mapOf("comment" to c))
            "command" -> t("Livechat_transfer_return_to_the_queue", emptyMap())
            "livechat-started" -> t("Chat_started", emptyMap())
            "livechat-close" -> t("Conversation_closed", emptyMap())
            "livechat_transfer_history" -> t("New_chat_transfer", mapOf("agent" to username))
            "ca" -> t("Change_Agent", emptyMap())
            "system" -> m
            else -> t("Unsupported_system_message", emptyMap())
        }
    }

    /** MessageEntity 面入口（列名即 RN 字段）。 */
    fun infoText(
        message: cn.appia.im.core.database.entity.MessageEntity,
        t: (key: String, params: Map<String, String>) -> String,
    ): String = infoText(
        type = message.t,
        msg = message.msg,
        u = message.u,
        role = message.role,
        roleName = message.role_name,
        rollbacker = message.rollbacker,
        comment = message.comment,
        t = t,
    )

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
