package cn.appia.im.core.messaging

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SystemMessageTexts 映射逐条测试（appiaMobile getInfoMessage.ts:40-166 移植对账）：
 * 每个 t 分支一条带参数断言（:40-166 每个返回 t(...) 的 case 一测），另含
 * ru/rollback/role 三处条件分支、system 原样、default、无 t、坏 JSON。
 */
class SystemMessageTextsTest {

    private val calls = mutableListOf<Pair<String, Map<String, String>>>()

    /** 缺 key 回退 "K:<key>"；ROLE_MAP 命中的 role key 返回 "R:<key>"（模拟 t() 二次查表+小写化）。 */
    private fun t(key: String, params: Map<String, String> = emptyMap()): String {
        calls += key.lowercase() to params
        val k = key.lowercase()
        return if (k == "role_name_owner1" || k == "role_name_moderator1") "R:$k" else "K:$k"
    }

    private fun lastCall(): Pair<String, Map<String, String>> = calls.last()

    /** u JSON：name 缺省时不带 name 键（JSON.stringify 语义）。 */
    private fun u(id: String = "u1", username: String = "bob", name: String? = "Bob"): String =
        buildJsonObject {
            put("_id", id)
            put("username", username)
            name?.let { put("name", it) }
        }.toString()

    private fun info(
        type: String?,
        msg: String? = "",
        uJson: String = u(),
        role: String? = null,
        roleName: String? = null,
        rollbacker: String? = null,
        comment: String? = null,
    ): String = SystemMessageTexts.infoText(
        type = type, msg = msg, u = uJson, role = role, roleName = roleName,
        rollbacker = rollbacker, comment = comment, t = ::t,
    )

    // ── 有作者名族 ──

    @Test
    fun rm() = assertEquals("K:message_removed", info("rm"))

    @Test
    fun uj() = assertEquals("K:has_joined_the_channel", info("uj"))

    @Test
    fun ujt() = assertEquals("K:user_joined_the_team", info("ujt"))

    @Test
    fun ut() = assertEquals("K:user_joined_the_conversation", info("ut"))

    @Test
    fun `r carries name and userBy`() {
        assertEquals("K:room_name_changed_to", info("r", msg = "new-name"))
        assertEquals("room_name_changed_to" to mapOf("name" to "new-name", "userBy" to "Bob"), lastCall())
    }

    @Test
    fun `ru with different author carries userRemoved and userBy`() {
        assertEquals("K:user_removed_by", info("ru", msg = "carol"))
        assertEquals("user_removed_by" to mapOf("userRemoved" to "carol", "userBy" to "Bob"), lastCall())
    }

    @Test
    fun `ru self-removal collapses to by1`() {
        assertEquals("K:user_removed_by1", info("ru", msg = "Bob"))
        assertEquals("user_removed_by1" to mapOf("userRemoved" to "Bob"), lastCall())
    }

    @Test
    fun `au carries userBy and userAdded`() {
        assertEquals("K:user_added_to", info("au", msg = "carol"))
        assertEquals("user_added_to" to mapOf("userBy" to "Bob", "userAdded" to "carol"), lastCall())
    }

    @Test
    fun `user-muted carries userMuted`() {
        assertEquals("K:user_has_been_muted", info("user-muted", msg = "carol"))
        assertEquals(mapOf("userMuted" to "carol"), lastCall().second)
    }

    @Test
    fun `room_changed_description carries description`() {
        assertEquals("K:changed_room_description", info("room_changed_description", msg = "desc"))
        assertEquals(mapOf("description" to "desc"), lastCall().second)
    }

    @Test
    fun `announcement trio carries announcement and userBy`() {
        for (type in listOf("room_created_announcement", "room_changed_announcement", "room_deleted_announcement")) {
            calls.clear()
            assertEquals("K:$type", info(type, msg = "hello"))
            assertEquals(mapOf("announcement" to "hello", "userBy" to "Bob"), lastCall().second)
        }
    }

    @Test
    fun `value proposition trio carries proposition and userBy`() {
        for (type in listOf(
            "room_created_value_proposition",
            "room_changed_value_proposition",
            "room_deleted_value_proposition",
        )) {
            calls.clear()
            assertEquals("K:$type", info(type, msg = "prop"))
            assertEquals(mapOf("proposition" to "prop", "userBy" to "Bob"), lastCall().second)
        }
    }

    @Test
    fun `room_changed_topic carries topic`() {
        assertEquals("K:room_changed_topic", info("room_changed_topic", msg = "topic1"))
        assertEquals(mapOf("topic" to "topic1"), lastCall().second)
    }

    @Test
    fun `room_changed_privacy maps to room_changed_type`() {
        assertEquals("K:room_changed_type", info("room_changed_privacy", msg = "p"))
        assertEquals(mapOf("type" to "p"), lastCall().second)
    }

    @Test
    fun `room_changed_avatar carries userBy`() {
        assertEquals("K:room_changed_avatar", info("room_changed_avatar"))
        assertEquals(mapOf("userBy" to "Bob"), lastCall().second)
    }

    @Test
    fun message_snippeted() = assertEquals("K:created_snippet", info("message_snippeted"))

    @Test
    fun e2e_disabled() = assertEquals("K:disabled_e2e_encryption_for_this_room", info("room_e2e_disabled"))

    @Test
    fun e2e_enabled() = assertEquals("K:enabled_e2e_encryption_for_this_room", info("room_e2e_enabled"))

    @Test
    fun `removed-user-from-team carries user_removed`() {
        assertEquals("K:removed__username__from_team", info("removed-user-from-team", msg = "carol"))
        assertEquals(mapOf("user_removed" to "carol"), lastCall().second)
    }

    @Test
    fun `added-user-to-team carries user_added`() {
        assertEquals("K:added__username__to_team", info("added-user-to-team", msg = "carol"))
        assertEquals(mapOf("user_added" to "carol"), lastCall().second)
    }

    @Test
    fun `rollback by others when rollbacker differs from author`() {
        val rb = """{"username":"carol","name":"Carol"}"""
        assertEquals("K:message_recalled_by_others", info("rollback-message", rollbacker = rb))
        assertEquals("message_recalled_by_others" to mapOf("user" to "Bob", "rollbacker" to "Carol"), lastCall())
    }

    @Test
    fun `rollback by self when rollbacker missing or equals author`() {
        assertEquals("K:message_recalled", info("rollback-message"))
        assertEquals(mapOf("userBy" to "Bob"), lastCall().second)
        calls.clear()
        val rb = """{"username":"Bob"}"""
        assertEquals("K:message_recalled", info("rollback-message", rollbacker = rb))
        assertEquals(mapOf("userBy" to "Bob"), lastCall().second)
    }

    @Test
    fun `team room trio carries roomName`() {
        for ((type, key) in listOf(
            "user-added-room-to-team" to "added__roomname__to_this_team",
            "user-converted-to-team" to "converted__roomname__to_a_team",
            "user-converted-to-channel" to "converted__roomname__to_a_channel",
            "user-deleted-room-from-team" to "deleted__roomname__",
            "user-removed-room-from-team" to "removed__roomname__from_the_team",
        )) {
            calls.clear()
            assertEquals("K:$key", info(type, msg = "room-a"))
            assertEquals(mapOf("roomName" to "room-a"), lastCall().second)
        }
    }

    @Test
    fun reacting_flags() {
        assertEquals("K:room_disallowed_reactions", info("room-disallowed-reacting"))
        assertEquals("K:room_allowed_reactions", info("room-allowed-reacting"))
    }

    @Test
    fun read_only_pair_carries_userBy() {
        assertEquals("K:room_set_read_only", info("room-set-read-only"))
        assertEquals(mapOf("userBy" to "Bob"), lastCall().second)
        calls.clear()
        assertEquals("K:room_removed_read_only", info("room-removed-read-only"))
        assertEquals(mapOf("userBy" to "Bob"), lastCall().second)
    }

    @Test
    fun `user-unmuted carries userUnmuted`() {
        assertEquals("K:user_has_been_unmuted", info("user-unmuted", msg = "carol"))
        assertEquals(mapOf("userUnmuted" to "carol"), lastCall().second)
    }

    @Test
    fun archived_flags() {
        assertEquals("K:room_archived", info("room-archived"))
        assertEquals("K:room_unarchived", info("room-unarchived"))
    }

    @Test
    fun `role-added leader branch pins untranslated role`() {
        assertEquals("K:user_is_pinned", info("subscription-role-added", msg = "carol", role = "leader", roleName = "Leader"))
        assertEquals("user_is_pinned" to mapOf("userBy" to "Bob", "user" to "carol", "role" to "Leader"), lastCall())
    }

    @Test
    fun `role-added with owner resolves role through nested t`() {
        assertEquals("K:user_was_set_role_by_", info("subscription-role-added", msg = "carol", role = "owner"))
        assertEquals(
            "user_was_set_role_by_" to mapOf("userBy" to "Bob", "user" to "carol", "role" to "R:role_name_owner1"),
            lastCall(),
        )
    }

    @Test
    fun `role-added with unmapped role passes raw text through t`() {
        assertEquals("K:user_was_set_role_by_", info("subscription-role-added", msg = "carol", role = "god"))
        // RN t(ROLE_MAP[role] || role)：未命中也包 t()；真实 t() 缺 key 回退 key 本身，观感即原文
        assertEquals(
            "user_was_set_role_by_" to mapOf("userBy" to "Bob", "user" to "carol", "role" to "K:god"),
            lastCall(),
        )
    }

    @Test
    fun `role-removed leader branch and moderator branch`() {
        assertEquals("K:user_is_unpinned", info("subscription-role-removed", msg = "carol", role = "leader"))
        assertEquals(
            "user_is_unpinned" to mapOf("userBy" to "Bob", "user" to "carol", "role" to "leader"),
            lastCall(),
        )
        calls.clear()
        assertEquals("K:user_is_no_longer_role_by_", info("subscription-role-removed", msg = "carol", role = "moderator"))
        assertEquals(
            "user_is_no_longer_role_by_" to mapOf("userBy" to "Bob", "user" to "carol", "role" to "R:role_name_moderator1"),
            lastCall(),
        )
    }

    @Test
    fun message_pinned() = assertEquals("K:message_pinned", info("message_pinned"))

    // ── 无作者名族 ──

    @Test
    fun ul() = assertEquals("K:user_left_this_channel", info("ul"))

    @Test
    fun ult() = assertEquals("K:has_left_the_team", info("ult"))

    @Test
    fun `jitsi_call_started carries userBy`() {
        assertEquals("K:started_call", info("jitsi_call_started"))
        assertEquals(mapOf("userBy" to "Bob"), lastCall().second)
    }

    @Test
    fun `omnichannel pair carries comment`() {
        assertEquals("K:omnichannel_placed_chat_on_hold", info("omnichannel_placed_chat_on_hold", comment = "c1"))
        assertEquals(mapOf("comment" to "c1"), lastCall().second)
        calls.clear()
        assertEquals("K:omnichannel_on_hold_chat_resumed", info("omnichannel_on_hold_chat_resumed", comment = "c2"))
        assertEquals(mapOf("comment" to "c2"), lastCall().second)
    }

    @Test
    fun livechat_trio() {
        assertEquals("K:livechat_transfer_return_to_the_queue", info("command"))
        assertEquals("K:chat_started", info("livechat-started"))
        assertEquals("K:conversation_closed", info("livechat-close"))
    }

    @Test
    fun `livechat_transfer_history carries agent`() {
        assertEquals("K:new_chat_transfer", info("livechat_transfer_history"))
        assertEquals(mapOf("agent" to "Bob"), lastCall().second)
    }

    @Test
    fun ca() = assertEquals("K:change_agent", info("ca"))

    // ── 条件与兜底 ──

    @Test
    fun `system renders raw msg without t`() {
        calls.clear()
        assertEquals("raw text", info("system", msg = "raw text"))
        assertEquals(0, calls.size)
    }

    @Test
    fun `unknown type falls back to unsupported`() =
        assertEquals("K:unsupported_system_message", info("totally-unknown"))

    @Test
    fun `null type renders empty without t`() {
        calls.clear()
        assertEquals("", info(null))
        assertEquals(0, calls.size)
    }

    @Test
    fun `malformed u json degrades author to empty string`() {
        assertEquals("K:room_name_changed_to", info("r", msg = "x", uJson = "{oops"))
        assertEquals(mapOf("name" to "x", "userBy" to ""), lastCall().second)
    }

    @Test
    fun `author falls back to username when name missing`() {
        assertEquals("K:room_changed_avatar", info("room_changed_avatar", uJson = u(name = null)))
        assertEquals(mapOf("userBy" to "bob"), lastCall().second)
    }
}
