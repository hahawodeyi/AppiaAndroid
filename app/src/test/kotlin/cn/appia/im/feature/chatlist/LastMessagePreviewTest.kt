package cn.appia.im.feature.chatlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 移植 RN `resolveLastMessagePreview.ts:92-124` 全规则：
 * 草稿优先 → 无 `lastMessage.u` 显 roomItem_noMessage → formatSpecialMsg（带/不带前缀逐条）
 * → 普通消息 senderPrefix（自己/rollback 无前缀，他人 `名字：`）→ 正文 md AST 首个可见 block，无 md 回退纯文本。
 */
class LastMessagePreviewTest {

    private fun resolve(
        lastMessage: String?,
        currentUserId: String? = "me",
        previewTableLabel: String? = null,
        useRealName: Boolean = true,
    ) = resolveLastMessagePreview(chatRow("r1", last_message = lastMessage), currentUserId, previewTableLabel, useRealName)

    private fun text(result: PreviewResult): String = (result as PreviewResult.Text).text

    private fun template(result: PreviewResult): PreviewResult.Template = result as PreviewResult.Template

    // ---------- 草稿优先 ----------

    @Test
    fun `draft plain wins over lastMessage`() {
        val result = resolveLastMessagePreview(
            chatRow("r1", draft_message_plain = "my draft", last_message = """{"msg":"hi"}"""),
            "me",
        )
        assertEquals("my draft", (result as PreviewResult.Text).text)
    }

    @Test
    fun `draft message fallback when plain is empty`() {
        val result = resolveLastMessagePreview(
            chatRow("r1", draft_message_plain = "", draft_message = "plain draft"),
            "me",
        )
        assertEquals("plain draft", (result as PreviewResult.Text).text)
    }

    // ---------- 无消息 ----------

    @Test
    fun `null lastMessage yields noMessage template`() {
        val t = template(resolve(null))
        assertEquals("roomItem_noMessage", t.key)
        assertEquals("", t.prefix)
    }

    @Test
    fun `lastMessage without u yields noMessage`() {
        assertEquals("roomItem_noMessage", template(resolve("""{"msg":"hi"}""")).key)
    }

    @Test
    fun `invalid lastMessage json yields noMessage`() {
        assertEquals("roomItem_noMessage", template(resolve("not-json")).key)
    }

    // ---------- 特殊消息 ----------

    @Test
    fun `pinned yields noMessage without prefix`() {
        val t = template(resolve("""{"pinned":true,"u":{"name":"Bob","username":"bob"}}"""))
        assertEquals("roomItem_noMessage", t.key)
        assertEquals("", t.prefix)
    }

    @Test
    fun `jitsi call started uses username arg without prefix`() {
        val t = template(resolve("""{"t":"jitsi_call_started","u":{"username":"bob"}}"""))
        assertEquals("roomItem_startedCall", t.key)
        assertEquals(mapOf("user" to "bob"), t.args)
        assertEquals("", t.prefix)
    }

    @Test
    fun `image attachment from other gets sender prefix`() {
        val t = template(
            resolve(
                """{"msg":"","u":{"name":"Bob","username":"bob"},"attachments":[{"image_url":"http://x/a.png"}]}""",
            ),
        )
        assertEquals("roomItem_sentAttachment", t.key)
        assertEquals(mapOf("kind" to "roomItem_attachmentImage"), t.args)
        assertEquals("Bob：", t.prefix)
    }

    @Test
    fun `file attachment as object gets prefix with username fallback`() {
        val t = template(
            resolve("""{"u":{"username":"carol"},"attachments":{"file":{"name":"a.pdf"}}}"""),
        )
        assertEquals("roomItem_sentAttachment", t.key)
        assertEquals(mapOf("kind" to "roomItem_attachmentFile"), t.args)
        assertEquals("carol：", t.prefix)
    }

    @Test
    fun `attachment from self has no prefix`() {
        val t = template(
            resolve(
                """{"u":{"username":"me"},"attachments":[{"file":{"name":"a.pdf"}}]}""",
            ),
        )
        assertEquals("", t.prefix)
    }

    @Test
    fun `docCloud keeps msg as kind with prefix`() {
        val t = template(resolve("""{"msgType":"docCloud","msg":"contract.pdf","u":{"username":"bob"}}"""))
        assertEquals("roomItem_sentAttachment", t.key)
        assertEquals(mapOf("kind" to "contract.pdf"), t.args)
        assertEquals("bob：", t.prefix)
    }

    @Test
    fun `oncall yields bracketed voiceCall without prefix`() {
        val t = template(resolve("""{"msgType":"oncall","u":{"username":"bob"}}"""))
        assertEquals("roomItem_voiceCall", t.key)
        assertEquals(true, t.brackets)
        assertEquals("", t.prefix)
    }

    @Test
    fun `meeting_room shows raw msg without prefix`() {
        assertEquals(
            "Meeting Room A",
            text(resolve("""{"msgType":"meeting_room","msg":"Meeting Room A","u":{"username":"bob"}}""")),
        )
        // 无 u 在 formatSpecialMsg 之前就落 noMessage（RN :102 顺序）
        assertEquals("roomItem_noMessage", template(resolve("""{"msgType":"meeting_room","msg":"x"}""")).key)
    }

    @Test
    fun `forwardMergeMessage gets prefix`() {
        val t = template(resolve("""{"msgType":"forwardMergeMessage","u":{"name":"Bob"}}"""))
        assertEquals("roomItem_forwardRecord", t.key)
        assertEquals("Bob：", t.prefix)
    }

    // ---------- 普通消息前缀 ----------

    @Test
    fun `normal message from other uses name then username`() {
        assertEquals("Bob：hello", text(resolve("""{"msg":"hello","u":{"name":"Bob","username":"bob"}}""")))
        assertEquals("bob：hello", text(resolve("""{"msg":"hello","u":{"username":"bob"}}""")))
    }

    @Test
    fun `normal message from self has no prefix`() {
        assertEquals("hello", text(resolve("""{"msg":"hello","u":{"username":"me"}}""")))
    }

    @Test
    fun `rollback message has no prefix`() {
        assertEquals("gone", text(resolve("""{"t":"rollback-message","msg":"gone","u":{"username":"bob"}}""")))
    }

    @Test
    fun `empty msg keeps bare sender prefix like RN`() {
        assertEquals("bob：", text(resolve("""{"u":{"username":"bob"}}""")))
    }

    @Test
    fun `empty body and empty prefix falls back to noMessage`() {
        // 自己的消息且无正文：RN `${prefix}${body}`.trim() || noMessage
        assertEquals("roomItem_noMessage", template(resolve("""{"u":{"username":"me"}}""")).key)
    }

    // ---------- md AST 预览（M2 关键分支） ----------

    @Test
    fun `md paragraph takes first visible block only`() {
        val lm = """{"msg":"ignored","u":{"username":"bob"},"md":[
            {"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"first"}]},
            {"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"second"}]}]}"""
        assertEquals("bob：first", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `md blank paragraph is skipped`() {
        val lm = """{"msg":"ignored","u":{"username":"bob"},"md":[
            {"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"  "}]},
            {"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"real"}]}]}"""
        assertEquals("bob：real", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `md unordered list uses bullet prefix`() {
        // 真实 AST 列表项带 LIST_ITEM 判别符（RN quoteLinkListLastMessage fixture 同款）
        val lm = """{"u":{"username":"bob"},"md":[
            {"type":"UNORDERED_LIST","value":[{"type":"LIST_ITEM","value":[{"type":"PLAIN_TEXT","value":"item1"}]}]}]}"""
        assertEquals("bob：• item1", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `md ordered list uses number prefix`() {
        val lm = """{"u":{"username":"bob"},"md":[
            {"type":"ORDERED_LIST","value":[{"type":"LIST_ITEM","number":3,"value":[{"type":"PLAIN_TEXT","value":"third"}]}]}]}"""
        assertEquals("bob：3) third", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `md table paragraph falls through to its inlines like RN`() {
        // RN inlinesFromBlock：无 previewTableLabel 时 TABLE 段落落穿 PARAGRAPH 分支取 block.value
        val lm = """{"u":{"username":"bob"},"md":[
            {"type":"PARAGRAPH","subType":"TABLE","value":[{"type":"PLAIN_TEXT","value":"cell"}]}]}"""
        assertEquals("bob：cell", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `table label replaces table inlines with prefix like RN`() {
        // 总纲 §4.3-2：previewTableLabel 进参数（RN inlinesFromBlock :24-31 label 直出）
        val lm = """{"u":{"username":"bob"},"md":[
            {"type":"PARAGRAPH","subType":"TABLE","value":[{"type":"PLAIN_TEXT","value":"cell"}]}]}"""
        val raw = lm.replace("\n", "")
        assertEquals("bob：[table]", text(resolve(raw, previewTableLabel = "[table]")))
    }

    @Test
    fun `table label only applies to table subtype`() {
        // 非 TABLE 段落不吃 label；label 也不阻断后续块判定
        val lm = """{"u":{"username":"bob"},"md":[
            {"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"para"}]},
            {"type":"PARAGRAPH","subType":"TABLE","value":[{"type":"PLAIN_TEXT","value":"cell"}]}]}"""
        assertEquals("bob：para", text(resolve(lm.replace("\n", ""), previewTableLabel = "[table]")))
    }

    @Test
    fun `blank table label skips the table block like RN truthy-but-blank`() {
        // RN：truthy 但空白 → hasVisiblePreviewInlines false → 整块跳过（不落穿、不回退同块）→ 回退纯文本
        val lm = """{"msg":"plain","u":{"username":"bob"},"md":[
            {"type":"PARAGRAPH","subType":"TABLE","value":[{"type":"PLAIN_TEXT","value":"cell"}]}]}"""
        assertEquals("bob：plain", text(resolve(lm.replace("\n", ""), previewTableLabel = "  ")))
    }

    @Test
    fun `md with unknown block only falls back to plain text`() {
        // RN message-parser 实测：多行 msg → 多 PARAGRAPH → 首个可见块 "plain"（首块预览语义）
        val lm = """{"msg":"plain\nfallback","u":{"username":"bob"},"md":[
            {"type":"CODE","value":[{"type":"PLAIN_TEXT","value":"code"}]}]}"""
        assertEquals("bob：plain", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `no md shows first paragraph block only`() {
        // RN parse('a\nb') 实测出两个 PARAGRAPH → inlinesFromFirstBlock 取 "a"；
        // 换行拍平为空格仅发生在 md 全程不可见时的兜底文案路径
        assertEquals("bob：a", text(resolve("""{"msg":"a\nb","u":{"username":"bob"}}""")))
    }

    @Test
    fun `md stored as escaped json string is parsed`() {
        val lm = """{"u":{"username":"bob"},"md":"[{\"type\":\"PARAGRAPH\",\"value\":[{\"type\":\"PLAIN_TEXT\",\"value\":\"str\"}]}]"}"""
        assertEquals("bob：str", text(resolve(lm)))
    }

    // ---------- mention 显示名（总纲 §4.4-1；RN RoomItemLastMessage :54 lastMessage.mentions） ----------
    // RN AtMention plainMode：命中 → `@${name||username}`；未命中 → `@token`；@all/@here 裸文本。
    // 真实载荷 md+msg 并存：纯 mention md 的 plainTextFromMd 为空 → resolveMdFromMsgFields
    // 落 msg 回退（MENTION_TOKEN 解析）；文本+mention md 走 AST 直读——两条路径都覆盖。

    @Test
    fun `mention resolves display name from lastMessage mentions`() {
        // lastMessage JSON 内含 mentions（RN chats 表无该列，免迁移直接解析）
        val lm = """{"msg":"@alice hi","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}],
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"alice"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：@Alice hi", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `mention in msg fallback resolves display name without md`() {
        val lm = """{"msg":"@alice hello","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}]}"""
        assertEquals("bob：@Alice hello", text(resolve(lm)))
    }

    @Test
    fun `mention falls back to username when name missing`() {
        val lm = """{"msg":"@alice hi","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice"}],
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"alice"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：@alice hi", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `unresolved mention keeps at-token`() {
        // mentions 数组未命中（RN AtMention 兜底 @mention）
        val lm = """{"msg":"@zed hi","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}],
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"zed"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：@zed hi", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `all and here mentions render bare like RN`() {
        // RN AtMention all/here 分支：plainMode 也无 @ 前缀
        val lm = """{"msg":"@all hi","u":{"username":"bob"},"mentions":[],
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"all"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：all hi", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `mention inside bold resolves display name`() {
        val lm = """{"msg":"@alice hi","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}],
            "md":[{"type":"PARAGRAPH","value":[{"type":"BOLD","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"alice"}}]},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：@Alice hi", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `bad mentions array is ignored`() {
        val lm = """{"msg":"@alice hi","u":{"username":"bob"},"mentions":"not-an-array",
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"alice"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：@alice hi", text(resolve(lm.replace("\n", ""))))
    }

    // ---------- M5-T4：UI_Use_Real_Name 两态（RN RoomItemLastMessage:44 → AtMention:36） ----------

    @Test
    fun `useRealName false preview mention label shows username`() {
        val lm = """{"msg":"@alice hi","u":{"username":"bob","name":"Bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}],
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"alice"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        // false → @username；发送人前缀 otherSenderPrefix（name||username）不读 useRealName，仍显 name
        assertEquals("Bob：@alice hi", text(resolve(lm.replace("\n", ""), useRealName = false)))
    }

    @Test
    fun `useRealName true default keeps name in preview`() {
        val lm = """{"msg":"@alice hi","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}],
            "md":[{"type":"PARAGRAPH","value":[{"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"alice"}},{"type":"PLAIN_TEXT","value":" hi"}]}]}"""
        assertEquals("bob：@Alice hi", text(resolve(lm.replace("\n", ""))))
    }

    @Test
    fun `useRealName false msg fallback mention shows username`() {
        val lm = """{"msg":"@alice hello","u":{"username":"bob"},"mentions":[{"_id":"u1","username":"alice","name":"Alice"}]}"""
        assertEquals("bob：@alice hello", text(resolve(lm, useRealName = false)))
    }

    @Test
    fun `useRealName false sender prefix still prefers name`() {
        // RN otherSenderPrefix 与 useRealName 无关：u.name 恒优先
        val lm = """{"msg":"plain","u":{"username":"bob","name":"Bob"},"mentions":[]}"""
        assertEquals("Bob：plain", text(resolve(lm, useRealName = false)))
    }
}
