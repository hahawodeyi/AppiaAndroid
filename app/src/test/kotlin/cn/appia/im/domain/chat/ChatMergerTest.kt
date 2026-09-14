package cn.appia.im.domain.chat

import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.domain.chat.ChatMerger.DedupeChatStrategy.LAST_IN_BATCH
import cn.appia.im.domain.chat.ChatMerger.applyMergedChatFields
import cn.appia.im.domain.chat.ChatMerger.dedupeMergedChatsById
import cn.appia.im.domain.chat.ChatMerger.merge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 移植 appiaMobile `src/database/mergeSubscriptionAndRoom.test.ts` 关键用例（mock model → ChatEntity）。
 * 覆盖：merge 链（底/覆盖/lm 链）、PRESERVE_WHEN_MERGED_UNDEFINED / PRESERVE_NONEMPTY_STRING / tSearch max、
 * 勿扰成对写、dedupe、rid 缺失抛错。
 */
class ChatMergerTest {

    // ---- JSON 构造助手 ----
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun s(v: String) = JsonPrimitive(v)
    private fun n(v: Long) = JsonPrimitive(v)
    private fun d(v: Double) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)
    private fun arr(vararg items: JsonElement) = JsonArray(items.toList())

    /** WatermelonDB sanitizedRaw 类型默认：string ''、boolean false、number 0。 */
    private fun entity(
        id: String,
        t: String = "",
        name: String = "",
    ): ChatEntity = ChatEntity(
        _id = id, rid = id, f = false, t = t, ts = 0.0, ls = 0.0,
        name = name, fname = "", open = false, alert = false,
        unread = 0.0, user_mentions = 0.0, group_mentions = 0.0,
        room_updated_at = 0.0, ro = false, archived = false,
        auto_translate_language = "", team_id = "",
    )

    // ---- PRESERVE_NONEMPTY_STRING_KEYS（name/fname/dname） ----

    @Test
    fun `empty name does not overwrite existing name`() {
        // RN 原用例用中文人名（张福超），仓库钩子禁代码区中文 → 等价 ASCII 名
        val model = entity("directRid1", t = "d", name = "Zhang Fuchao")
        val merged = merge(
            obj("_id" to s("subDoc1"), "rid" to s("directRid1"), "t" to s("d"), "name" to s(""), "fname" to s("")),
        )
        val next = applyMergedChatFields(model, merged)
        assertEquals("Zhang Fuchao", next.name)
    }

    @Test
    fun `room name fills empty name from room document`() {
        val model = entity("rid2", t = "d", name = "Gu Xuefeng")
        val merged = merge(
            obj("_id" to s("sub2"), "rid" to s("rid2"), "t" to s("d"), "name" to s(""), "fname" to s("")),
            obj("_id" to s("rid2"), "name" to s("Gu Xuefeng")),
        )
        val next = applyMergedChatFields(model, merged)
        assertEquals("Gu Xuefeng", next.name)
    }

    @Test
    fun `non-empty name overwrites local name`() {
        val model = entity("rid3", name = "OldName")
        val merged = merge(obj("_id" to s("sub3"), "rid" to s("rid3"), "t" to s("d"), "name" to s("NewName")))
        val next = applyMergedChatFields(model, merged)
        assertEquals("NewName", next.name)
    }

    // ---- tSearch max / PRESERVE_WHEN_MERGED_UNDEFINED ----

    @Test
    fun `preserves local tSearch when sync omits it`() {
        val bumpedAt = 1_700_000_000_000.0
        val model = entity("rid-search", t = "c").copy(tSearch = bumpedAt)
        val merged = merge(obj("_id" to s("sub-search"), "rid" to s("rid-search"), "t" to s("c"), "unread" to n(0)))
        assertNull(merged.tSearch)
        val next = applyMergedChatFields(model, merged)
        assertEquals(bumpedAt, next.tSearch)
    }

    @Test
    fun `tSearch takes max of local and incoming`() {
        val model = entity("rid-ts", t = "c").copy(tSearch = 1_700_000_000_000.0)
        val merged = merge(
            obj("_id" to s("sub-ts"), "rid" to s("rid-ts"), "t" to s("c"), "tSearch" to n(1_800_000_000_000)),
        )
        val next = applyMergedChatFields(model, merged)
        assertEquals(1_800_000_000_000.0, next.tSearch)
    }

    @Test
    fun `preserves announcements when merged is undefined`() {
        val existingList = """[{"_id":"a1","message":"keep me"}]"""
        val model = entity("rid6", t = "p").copy(announcements = existingList)
        val merged = merge(obj("rid" to s("rid6"), "t" to s("p")))
        assertNull(merged.announcements)
        val next = applyMergedChatFields(model, merged)
        assertEquals(existingList, next.announcements)
    }

    @Test
    fun `merges announcements from room document`() {
        val list = arr(obj("_id" to s("a1"), "message" to s("from room")))
        val merged = merge(obj("rid" to s("rid7"), "t" to s("p")), obj("_id" to s("rid7"), "announcements" to list))
        assertEquals(list.toString(), merged.announcements)
    }

    @Test
    fun `preserves local f when patch omits favorite`() {
        val model = entity("rid-fav", t = "d").copy(f = true)
        val merged = merge(obj("_id" to s("sub-fav"), "rid" to s("rid-fav"), "t" to s("d"), "unread" to n(0), "alert" to b(false)))
        assertNull(merged.f)
        val next = applyMergedChatFields(model, merged)
        assertEquals(true, next.f)
    }

    @Test
    fun `preserves local like when patch omits like`() {
        val model = entity("rid-like", t = "c").copy(like = true)
        val merged = merge(obj("_id" to s("sub-like"), "rid" to s("rid-like"), "t" to s("c"), "unread" to n(0)))
        val next = applyMergedChatFields(model, merged)
        assertEquals(true, next.like)
    }

    @Test
    fun `applies f=false when server explicitly unfavorites`() {
        val model = entity("rid-unfav", t = "c").copy(f = true)
        val merged = merge(obj("_id" to s("sub-unfav"), "rid" to s("rid-unfav"), "t" to s("c"), "f" to b(false)))
        val next = applyMergedChatFields(model, merged)
        assertEquals(false, next.f)
    }

    @Test
    fun `preserves local open when patch omits open`() {
        val model = entity("rid-open", t = "c").copy(open = true)
        val merged = merge(obj("_id" to s("sub-open"), "rid" to s("rid-open"), "t" to s("c"), "unread" to n(0)))
        assertNull(merged.open)
        val next = applyMergedChatFields(model, merged)
        assertEquals(true, next.open)
    }

    @Test
    fun `applies open=false when server explicitly closes`() {
        val model = entity("rid-closed", t = "c").copy(open = true)
        val merged = merge(obj("_id" to s("sub-closed"), "rid" to s("rid-closed"), "t" to s("c"), "open" to b(false)))
        val next = applyMergedChatFields(model, merged)
        assertEquals(false, next.open)
    }

    @Test
    fun `preserves unread and alert when lastMessage-only patch omits them`() {
        val model = entity("rid-bot", t = "d")
            .copy(open = true, unread = 1.0, alert = true, ls = 1_700_000_000_000.0)
        val merged = merge(
            obj(
                "_id" to s("sub-bot"), "rid" to s("rid-bot"), "t" to s("d"),
                "lastMessage" to obj("_id" to s("m1"), "msg" to s("bot reply"), "ts" to n(1_700_000_001_000)),
                "lm" to n(1_700_000_001_000),
            ),
        )
        assertNull(merged.unread)
        assertNull(merged.alert)
        val next = applyMergedChatFields(model, merged)
        assertEquals(1.0, next.unread)
        assertEquals(true, next.alert)
        assertEquals(1_700_000_001_000.0, next.lm)
    }

    @Test
    fun `applies unread=0 and alert=false when server marks read`() {
        val model = entity("rid-read", t = "d").copy(open = true, unread = 3.0, alert = true)
        val merged = merge(
            obj(
                "_id" to s("sub-read"), "rid" to s("rid-read"), "t" to s("d"),
                "unread" to n(0), "alert" to b(false), "ls" to n(1_700_000_002_000),
            ),
        )
        val next = applyMergedChatFields(model, merged)
        assertEquals(0.0, next.unread)
        assertEquals(false, next.alert)
    }

    @Test
    fun `preserves assistant t and uids when patch omits them`() {
        val model = entity("agent-rid", t = "d", name = "Real Name")
            .copy(uids = """["user-me"]""")
        val merged = merge(
            obj(
                "_id" to s("sub-agent"), "rid" to s("agent-rid"), "unread" to n(0),
                "name" to s("Real Name"), "fname" to s("Real Name"),
            ),
        )
        assertNull(merged.t)
        assertNull(merged.uids)
        val next = applyMergedChatFields(model, merged)
        assertEquals("d", next.t)
        assertEquals("""["user-me"]""", next.uids)
    }

    @Test
    fun `does not default t to channel on rid-only patch`() {
        val merged = merge(obj("rid" to s("agent-rid")))
        assertNull(merged.t)
    }

    @Test
    fun `preserves todoCount family when patch omits todo fields`() {
        val model = entity("rid-todo", t = "c")
            .copy(todoCount = 3.0, highTodoCount = 1.0, defaultTodoCount = 2.0, isRoomToDo = true)
        val merged = merge(
            obj(
                "_id" to s("sub-todo"), "rid" to s("rid-todo"), "t" to s("c"),
                "unread" to n(0), "alert" to b(false), "open" to b(true),
            ),
        )
        assertNull(merged.todoCount)
        val next = applyMergedChatFields(model, merged)
        assertEquals(3.0, next.todoCount)
        assertEquals(1.0, next.highTodoCount)
        assertEquals(2.0, next.defaultTodoCount)
        assertEquals(true, next.isRoomToDo)
    }

    @Test
    fun `preserves draft fields when patch omits them (client-only)`() {
        val draftJson = """{"type":"doc"}"""
        val model = entity("rid-draft", t = "d")
            .copy(draft_message = draftJson, draft_message_plain = "hello", draft_reply_msg_id = "msg-1")
        val merged = merge(obj("_id" to s("sub-draft"), "rid" to s("rid-draft"), "t" to s("d"), "unread" to n(1)))
        assertNull(merged.draftMessage)
        assertNull(merged.draftMessagePlain)
        assertNull(merged.draftReplyMessageId)
        val next = applyMergedChatFields(model, merged)
        assertEquals(draftJson, next.draft_message)
        assertEquals("hello", next.draft_message_plain)
        assertEquals("msg-1", next.draft_reply_msg_id)
    }

    // ---- 勿扰：成对写 + '1'/'0' 解析 ----

    @Test
    fun `parses notification booleans from string 1 and 0`() {
        val merged = merge(
            obj(
                "_id" to s("sub-mute3"), "rid" to s("rid-mute3"), "t" to s("c"),
                "disableNotifications" to s("1"), "hideUnreadStatus" to s("0"),
            ),
        )
        assertEquals(true, merged.disableNotifications)
        assertEquals(false, merged.hideUnreadStatus)
    }

    @Test
    fun `clears both mute fields when patch sends hideUnreadStatus false only`() {
        val model = entity("rid-unmute", t = "c").copy(hide_unread_status = true, disable_notifications = true)
        val merged = merge(obj("_id" to s("sub-unmute"), "rid" to s("rid-unmute"), "t" to s("c"), "hideUnreadStatus" to b(false)))
        val next = applyMergedChatFields(model, merged)
        assertEquals(false, next.hide_unread_status)
        assertEquals(false, next.disable_notifications)
    }

    @Test
    fun `clears both mute fields when patch sends disableNotifications 0 only`() {
        val model = entity("rid-unmute2", t = "c").copy(hide_unread_status = true, disable_notifications = true)
        val merged = merge(obj("_id" to s("sub-unmute2"), "rid" to s("rid-unmute2"), "t" to s("c"), "disableNotifications" to s("0")))
        val next = applyMergedChatFields(model, merged)
        assertEquals(false, next.hide_unread_status)
        assertEquals(false, next.disable_notifications)
    }

    @Test
    fun `sets both mute fields when patch sends disableNotifications 1 only`() {
        val model = entity("rid-mute-on", t = "c").copy(hide_unread_status = false, disable_notifications = false)
        val merged = merge(obj("_id" to s("sub-mute-on"), "rid" to s("rid-mute-on"), "t" to s("c"), "disableNotifications" to s("1")))
        val next = applyMergedChatFields(model, merged)
        assertEquals(true, next.hide_unread_status)
        assertEquals(true, next.disable_notifications)
    }

    @Test
    fun `preserves both mute fields when patch omits them`() {
        val model = entity("rid-mute", t = "c").copy(hide_unread_status = true, disable_notifications = true)
        val merged = merge(obj("_id" to s("sub-mute"), "rid" to s("rid-mute"), "t" to s("c"), "unread" to n(0)))
        val next = applyMergedChatFields(model, merged)
        assertEquals(true, next.hide_unread_status)
        assertEquals(true, next.disable_notifications)
    }

    // ---- updatedAt → subscription_updated_at、lm 链、roomUpdatedAt ----

    @Test
    fun `maps merged updatedAt to subscription_updated_at`() {
        val model = entity("rid5", t = "d").copy(subscription_updated_at = 100.0)
        val merged = merge(obj("_id" to s("sub5"), "rid" to s("rid5"), "t" to s("d"), "_updatedAt" to s("2026-05-27T00:00:00.000Z")))
        val next = applyMergedChatFields(model, merged)
        assertEquals(merged.updatedAt, next.subscription_updated_at)
    }

    @Test
    fun `roomUpdatedAt comes from sub lm not room _updatedAt`() {
        val merged = merge(
            obj(
                "_id" to s("sub1"), "rid" to s("rid1"), "t" to s("c"), "name" to s("chan"),
                "lm" to n(100), "ts" to n(50), "_updatedAt" to s("2020-01-01T00:00:00.000Z"),
            ),
            obj("_id" to s("rid1"), "_updatedAt" to s("2099-01-01T00:00:00.000Z"), "lm" to n(888)),
        )
        assertEquals(100.0, merged.lm)
        assertEquals(100.0, merged.roomUpdatedAt)
    }

    @Test
    fun `lastMessage falls back room then subscription wins when truthy`() {
        // room.lastMessage 先覆盖；sub 有真值 lastMessage 时最后再覆盖
        val merged = merge(
            obj("rid" to s("rid-lm"), "lastMessage" to obj("msg" to s("from sub"), "ts" to n(200))),
            obj("_id" to s("rid-lm"), "_updatedAt" to s("2020-01-01T00:00:00.000Z"), "lastMessage" to obj("msg" to s("from room"))),
        )
        assertTrue(merged.lastMessage!!.contains("from sub"))
        assertEquals(200.0, merged.lm) // lastMessage.ts 链
    }

    @Test
    fun `dollar-date ts inside lastMessage parses via chain`() {
        val merged = merge(
            obj(
                "rid" to s("rid-date"),
                "lastMessage" to obj("msg" to s("hi"), "ts" to obj("\$date" to n(1_700_000_001_000))),
            ),
        )
        assertEquals(1_700_000_001_000.0, merged.lm)
    }

    @Test
    fun `missing rid and room id throws`() {
        assertThrows(IllegalArgumentException::class.java) { merge(obj("name" to s("no-rid"))) }
    }

    @Test
    fun `room muted clears when room updated but muted empty`() {
        val merged = merge(
            obj("rid" to s("rid-muted"), "muted" to arr(s("a"), s("b"))),
            obj("_id" to s("rid-muted"), "_updatedAt" to s("2020-01-01T00:00:00.000Z"), "muted" to arr()),
        )
        assertNull(merged.muted)
    }

    // ---- dedupe ----

    @Test
    fun `dedupe latestRoomUpdated keeps higher roomUpdatedAt and later on tie`() {
        val a = merge(obj("rid" to s("r1"), "lm" to n(100)))
        val b = merge(obj("rid" to s("r1"), "lm" to n(50)))
        val c = merge(obj("rid" to s("r2"), "lm" to n(10)))
        val deduped = dedupeMergedChatsById(listOf(a, b, c))
        assertEquals(2, deduped.size)
        assertEquals(100.0, deduped.first { it._id == "r1" }.roomUpdatedAt)
    }

    @Test
    fun `dedupe lastInBatch keeps last occurrence`() {
        val a = merge(obj("rid" to s("r1"), "name" to s("first")))
        val b = merge(obj("rid" to s("r1"), "name" to s("second")))
        val deduped = dedupeMergedChatsById(listOf(a, b), LAST_IN_BATCH)
        assertEquals(1, deduped.size)
        assertEquals("second", deduped[0].name)
    }
}
