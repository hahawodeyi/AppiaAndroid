package cn.appia.im.feature.chat

import cn.appia.im.core.database.entity.MessageEntity
import cn.appia.im.core.messaging.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按菜单动作判定全集（messageActions.tsx getOptions :129-245 逐分支）+ 撤回快照往返 +
 * rollback 分组数据 + 引用文本构造 + 批量撤回文案 + 多选 store。
 */
class MessageActionControllerTest {

    private fun msg(
        _id: String = "m1",
        u: String = """{"_id":"u2","username":"bob","name":"Bob"}""",
        ts: Double = 1_000.0,
        t: String? = null,
        status: Double? = null,
        msg: String? = "hi",
        attachments: String? = null,
        originalContent: String? = null,
        rollbacker: String? = null,
        rid: String = "r1",
        tmid: String? = null,
        tmsg: String? = null,
        md: String? = null,
        files: String? = null,
        mentions: String? = null,
        msgType: String? = null,
        appiaTodo: String? = null,
    ) = MessageEntity(
        _id = _id, rid = rid, ts = ts, u = u, alias = "", parse_urls = "[]",
        _updated_at = ts, msg = msg, t = t, status = status, attachments = attachments,
        original_content = originalContent, rollbacker = rollbacker, tmid = tmid, tmsg = tmsg,
        md = md, files = files, mentions = mentions, msg_type = msgType, appia_todo = appiaTodo,
    )

    private val own = """{"_id":"me","username":"me","name":"Me"}"""
    private val other = """{"_id":"u2","username":"bob","name":"Bob"}"""
    private val ctx = MessageActionContext(currentUserId = "me")
    private val now = 100_000L

    // ── getOptions 判定全分支 ──

    @Test
    fun `own plain message has reply edit copy forward multiSelect recall in RN order`() {
        val options = getOptions(msg(u = own, ts = now.toDouble()), ctx, now)
        assertEquals(
            listOf(
                MessageAction.REPLY, MessageAction.EDIT, MessageAction.COPY,
                MessageAction.FORWARD, MessageAction.MULTI_SELECT, MessageAction.RECALL,
            ),
            options,
        )
    }

    @Test
    fun `reply forward multiSelect always present even for others plain message`() {
        val options = getOptions(msg(u = other), ctx, now)
        assertTrue(options.contains(MessageAction.REPLY))
        assertTrue(options.contains(MessageAction.FORWARD))
        assertTrue(options.contains(MessageAction.MULTI_SELECT))
        assertEquals(4, options.size) // + COPY(msg 有) - 无 EDIT/RECALL/RESEND
    }

    @Test
    fun `edit hidden for others message`() {
        assertFalse(getOptions(msg(u = other), ctx, now).contains(MessageAction.EDIT))
    }

    @Test
    fun `edit hidden when allowEditing false`() {
        assertFalse(
            getOptions(msg(u = own), ctx.copy(allowEditing = false), now).contains(MessageAction.EDIT),
        )
    }

    @Test
    fun `edit hidden when editBlockMinutes timed out, shown within window`() {
        val old = msg(u = own, ts = 0.0) // 100s 前
        // blockMinutes=1 → 60s 窗口已超
        assertFalse(getOptions(old, ctx.copy(editBlockMinutes = 1), now).contains(MessageAction.EDIT))
        // blockMinutes=2 → 120s 窗口未超
        assertTrue(getOptions(old, ctx.copy(editBlockMinutes = 2), now).contains(MessageAction.EDIT))
    }

    @Test
    fun `editBlockMinutes zero never times out regardless of age`() {
        assertTrue(
            getOptions(msg(u = own, ts = 0.0), ctx.copy(editBlockMinutes = 0), now)
                .contains(MessageAction.EDIT),
        )
    }

    @Test
    fun `edit hidden for videoconf message`() {
        assertFalse(
            getOptions(msg(u = own, t = "videoconf"), ctx, now).contains(MessageAction.EDIT),
        )
    }

    @Test
    fun `edit and recall hidden when noOtherUserMessagesAfter false`() {
        val options = getOptions(msg(u = own), ctx.copy(noOtherUserMessagesAfter = false), now)
        assertFalse(options.contains(MessageAction.EDIT))
        assertFalse(options.contains(MessageAction.RECALL))
    }

    @Test
    fun `copy hidden when no msg and no attachments`() {
        assertFalse(getOptions(msg(u = own, msg = null), ctx, now).contains(MessageAction.COPY))
    }

    @Test
    fun `copy shown via attachments first description even without msg`() {
        val m = msg(
            u = own, msg = null,
            attachments = """[{"title":"a","description":"\u8bf4\u660e\u6587\u5b57"}]""",
        )
        assertTrue(getOptions(m, ctx, now).contains(MessageAction.COPY))
        assertEquals("\u8bf4\u660e\u6587\u5b57", copyableText(m))
    }

    @Test
    fun `empty description attachments does not enable copy`() {
        val m = msg(u = own, msg = null, attachments = """[{"title":"a","description":""}]""")
        assertFalse(getOptions(m, ctx, now).contains(MessageAction.COPY))
    }

    @Test
    fun `bad attachments json does not crash and falls back to msg copy`() {
        val m = msg(u = own, msg = "hello", attachments = "not-json")
        assertTrue(getOptions(m, ctx, now).contains(MessageAction.COPY))
        assertEquals("hello", copyableText(m))
    }

    @Test
    fun `recall hidden for others message`() {
        assertFalse(getOptions(msg(u = other), ctx, now).contains(MessageAction.RECALL))
    }

    @Test
    fun `recall hidden when deleteBlockMinutes timed out`() {
        assertFalse(
            getOptions(msg(u = own, ts = 0.0), ctx.copy(deleteBlockMinutes = 1), now)
                .contains(MessageAction.RECALL),
        )
    }

    @Test
    fun `resend only for own message in ERROR status`() {
        assertTrue(
            getOptions(msg(u = own, status = MessageStatus.ERROR.toDouble()), ctx, now)
                .contains(MessageAction.RESEND),
        )
        assertFalse(
            getOptions(msg(u = other, status = MessageStatus.ERROR.toDouble()), ctx, now)
                .contains(MessageAction.RESEND),
        )
        assertFalse(
            getOptions(msg(u = own, status = MessageStatus.SENT.toDouble()), ctx, now)
                .contains(MessageAction.RESEND),
        )
    }

    @Test
    fun `todo and summary actions are M7 placeholders and never emitted`() {
        val m = msg(
            u = own,
            appiaTodo = """{"status":0}""",
            attachments = """[{"image_url":"x","type":"image/jpeg"}]""",
        )
        val titles = getOptions(m, ctx, now).map { it.titleKey }
        assertFalse(titles.any { it.contains("todo") || it.contains("summary") })
    }

    @Test
    fun `isRoomReadOnly is archived or ro`() {
        assertTrue(isRoomReadOnly(archived = true, ro = false))
        assertTrue(isRoomReadOnly(archived = false, ro = true))
        assertFalse(isRoomReadOnly(archived = false, ro = false))
        assertFalse(isRoomReadOnly(archived = null, ro = null))
    }

    // ── formatCopyText（RN formatCopyText.ts 逐条）──

    @Test
    fun `formatCopyText strips link size color quote and collapses whitespace`() {
        assertEquals(
            "Text hello red",
            formatCopyText("<https://s.io|Text> [size-24:hello] [color-#FF0000:red]"),
        )
        // 引用前缀只在串首剥除（RN ^ 锚定）：发送时引用消息文本即此形态
        assertEquals(
            "done",
            formatCopyText("[ ](https://s.io/group/r?msg=m1) done"),
        )
        assertEquals("", formatCopyText(null))
        assertEquals("", formatCopyText(""))
    }

    // ── 撤回快照序列化往返 ──

    @Test
    fun `serializeOriginalContent snapshots all eight fields`() {
        val m = msg(
            u = own, msg = "hello", rid = "r1",
            attachments = """[{"a":1}]""", mentions = """[{"_id":"u9"}]""",
            tmid = "t1", tmsg = "reply-text",
            md = """[{"type":"PARAGRAPH"}]""", files = """[{"name":"f"}]""",
            msgType = "merged",
        )
        val json = serializeOriginalContent(m)
        assertTrue(
            json.contains("\"msg\":\"hello\"") && json.contains("\"tmid\":\"t1\"") &&
                json.contains("\"tmsg\":\"reply-text\"") && json.contains("\"msgType\":\"merged\"") &&
                json.contains("\"attachments\"") && json.contains("\"files\"") &&
                json.contains("\"mentions\"") && json.contains("\"md\""),
        )
        val back = deserializeOriginalContent(m.copy(original_content = json))
        assertEquals("hello", back?.msg)
        assertEquals("merged", back?.msgType)
        assertEquals("t1", back?.tmid)
    }

    @Test
    fun `serialize maps empty strings to null keys like JSON stringify`() {
        val json = serializeOriginalContent(msg(u = own, msg = "", tmid = "t1"))
        assertTrue("json=$json", json.contains("\"msg\":null"))
        assertTrue(json.contains("\"tmid\":\"t1\""))
    }

    @Test
    fun `deserialize returns null for missing or broken originalContent`() {
        assertNull(deserializeOriginalContent(msg(originalContent = null)))
        assertNull(deserializeOriginalContent(msg(originalContent = "")))
        assertNull(deserializeOriginalContent(msg(originalContent = "not-json")))
    }

    @Test
    fun `isReeditableRollback requires rollback type plus snapshot plus own`() {
        val rollback = msg(u = own, t = "rollback-message", originalContent = """{"msg":"hi"}""")
        assertTrue(isReeditableRollback(rollback, "me"))
        assertFalse(isReeditableRollback(rollback.copy(original_content = null), "me"))
        assertFalse(isReeditableRollback(rollback.copy(u = other), "me"))
        assertFalse(isReeditableRollback(rollback.copy(t = "wm"), "me"))
        // 快照反序列化 → 重新编辑语义（RN handleReedit :806-812：回填输入框作新消息，不进编辑模式）
        assertEquals("hi", deserializeOriginalContent(rollback)?.msg)
    }

    // ── rollback 分组渲染数据（applyDisplayMessageTransforms :15-53）──

    @Test
    fun `consecutive same rollbacker messages group into head plus hidden`() {
        val rollbacker = """{"_id":"me","name":"Me"}"""
        val messages = listOf(
            msg(_id = "a", u = other, msg = "one", t = "rollback-message", rollbacker = rollbacker),
            msg(_id = "b", u = other, msg = "two", t = "rollback-message", rollbacker = rollbacker),
            msg(_id = "c", u = other, msg = "normal"),
        )
        val display = applyDisplayMessageTransforms(messages)
        assertEquals(listOf("a", "b", "c"), display.map { it.message._id })
        assertEquals(listOf("a", "b"), display[0].rollbackGroup?.map { it._id })
        assertTrue(display[1].hiddenInRollbackGroup)
        assertNull(display[2].rollbackGroup)
        val visible = filterVisibleDisplayMessages(display)
        assertEquals(listOf("a", "c"), visible.map { it.message._id })
    }

    @Test
    fun `different rollbacker or non rollback rows break the group`() {
        val me = """{"_id":"me","name":"Me"}"""
        val bob = """{"_id":"bob","name":"Bob"}"""
        val messages = listOf(
            msg(_id = "a", t = "rollback-message", rollbacker = me),
            msg(_id = "b", t = "rollback-message", rollbacker = bob),
            msg(_id = "c", t = "wm"),
            msg(_id = "d", t = "rollback-message", rollbacker = me),
        )
        val display = applyDisplayMessageTransforms(messages)
        assertNull(display[0].rollbackGroup) // 单条不成组
        assertNull(display[1].rollbackGroup)
        assertNull(display[3].rollbackGroup)
        assertTrue(display.none { it.hiddenInRollbackGroup })
    }

    @Test
    fun `rollbacker falls back to u when rollbacker missing`() {
        val me = """{"_id":"me","name":"Me"}"""
        val messages = listOf(
            msg(_id = "a", u = me, t = "rollback-message", rollbacker = null),
            msg(_id = "b", u = me, t = "rollback-message", rollbacker = null),
        )
        val display = applyDisplayMessageTransforms(messages)
        assertEquals(listOf("a", "b"), display[0].rollbackGroup?.map { it._id })
    }

    @Test
    fun `empty input transforms to empty`() {
        assertTrue(applyDisplayMessageTransforms(emptyList()).isEmpty())
    }

    // ── 回复引用 composeQuotedMessageText ──

    @Test
    fun `no reply message returns plain text`() {
        assertEquals("hi", composeQuotedMessageText("hi", null, "https://s", "r1", "c", "me"))
    }

    @Test
    fun `group room quotes permalink with channel path and no self mention`() {
        val reply = msg(u = own, _id = "m9")
        val out = composeQuotedMessageText("hi", reply, "https://s.io", "r1", "c", "me")
        assertEquals("[ ](https://s.io/channel/r1?msg=m9) hi", out)
    }

    @Test
    fun `other sender in group room prepends mention`() {
        val reply = msg(u = other, _id = "m9")
        val out = composeQuotedMessageText("hi", reply, "https://s.io", "r1", "p", "me")
        assertEquals("[ ](https://s.io/group/r1?msg=m9) @Bob hi", out)
    }

    @Test
    fun `direct room never mentions and maps to direct path`() {
        val reply = msg(u = other, _id = "m9")
        val out = composeQuotedMessageText("hi", reply, "https://s.io", "r1", "d", "me")
        assertEquals("[ ](https://s.io/direct/r1?msg=m9) hi", out)
    }

    @Test
    fun `unknown roomType falls back to group path and nameless sender skips mention`() {
        val reply = msg(u = """{"_id":"u2","username":"bob"}""", _id = "m9")
        val out = composeQuotedMessageText("hi", reply, "https://s.io", "r1", "l", "me")
        assertEquals("[ ](https://s.io/group/r1?msg=m9) @bob hi", out)
        val nameless = msg(u = """{"_id":"u2"}""", _id = "m9")
        assertEquals(
            "[ ](https://s.io/channel/r1?msg=m9) hi",
            composeQuotedMessageText("hi", nameless, "https://s.io", "r1", "c", "me"),
        )
    }

    // ── 批量撤回确认文案 ──

    @Test
    fun `batch recall tip picks key by distinct sender count with self as self-label`() {
        val me = """{"_id":"me","name":"Me"}"""
        val bob = """{"_id":"u2","name":"Bob"}"""
        // 单人（自己）
        val solo = summarizeSenders(listOf(msg(u = me), msg(u = me)), "Me")
        assertEquals("multiselect_batchrecalltip1", buildBatchRecallTip(solo).key)
        assertEquals("\u60a8", buildBatchRecallTip(solo).params["user"])
        assertEquals("2", buildBatchRecallTip(solo).params["num"])
        // 两人（自己 + Bob）
        val pair = summarizeSenders(listOf(msg(u = me), msg(u = bob)), "Me")
        val tip2 = buildBatchRecallTip(pair)
        assertEquals("multiselect_batchrecalltip2", tip2.key)
        assertEquals("\u60a8", tip2.params["user1"])
        assertEquals("Bob", tip2.params["user2"])
        // 三人
        val carol = """{"_id":"u3","name":"Carol"}"""
        val trio = summarizeSenders(listOf(msg(u = bob), msg(u = carol), msg(u = me)), "Me")
        val tip3 = buildBatchRecallTip(trio)
        assertEquals("multiselect_batchrecalltip3", tip3.key)
        assertEquals("\u60a8", tip3.params["user1"]) // RN user1 = hasMe ? You : allSenders[0]
        assertEquals("Bob", tip3.params["user2"]) // user2 = others[0]
        assertEquals("3", tip3.params["userNum"])
        assertEquals("3", tip3.params["num"])
    }

    @Test
    fun `canRecallSelection requires every selected message recallable`() {
        val messages = listOf(msg(u = own), msg(u = own))
        val mixed = listOf(msg(u = own), msg(u = other))
        assertTrue(messages.all { canRecallMessage(it, ctx, now) })
        assertFalse(mixed.all { canRecallMessage(it, ctx, now) })
    }

    // ── 多选 store（StateFlow 等价 RN messageMultiSelectStore）──

    @Test
    fun `multi select store enter toggle exit with rid guard`() {
        val store = MessageMultiSelectStore()
        val first = msg(_id = "a", rid = "r1")
        val second = msg(_id = "b", rid = "r1")
        val otherRoom = msg(_id = "x", rid = "r2")

        store.enter("r1", first)
        assertEquals(listOf("a"), store.state.value.selectedIds)
        assertTrue(store.state.value.active)

        store.toggle(otherRoom) // 跨房忽略
        assertEquals(listOf("a"), store.state.value.selectedIds)

        store.toggle(second)
        assertEquals(listOf("a", "b"), store.state.value.selectedIds)
        assertEquals(2, store.state.value.selectedMap.size)

        store.toggle(first) // 已选移除、保持顺序
        assertEquals(listOf("b"), store.state.value.selectedIds)

        store.exit()
        assertFalse(store.state.value.active)
        assertTrue(store.state.value.selectedIds.isEmpty())

        store.toggle(second) // 未激活忽略
        assertTrue(store.state.value.selectedIds.isEmpty())
    }
}
