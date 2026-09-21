package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** isMessageEdited（RN isMessageEdited.ts）+ appendEditedTagToMd（md 注入）+ buildEditContent 用例。 */
class EditedTagTest {

    private fun entity(editedBy: String? = null, msg: String? = "hi", md: String? = null) = MessageEntity(
        _id = "m1", rid = "r1", ts = 1.0, u = """{"_id":"me"}""", alias = "", parse_urls = "[]",
        _updated_at = 1.0, msg = msg, md = md, edited_by = editedBy,
    )

    @Test
    fun `isMessageEdited truth table`() {
        assertFalse(isMessageEdited(entity(editedBy = null)))
        assertFalse(isMessageEdited(entity(editedBy = "")))
        assertTrue(isMessageEdited(entity(editedBy = """{"_id":"me"}""")))
    }

    // ── appendEditedTagToMd（RN appendEditedTagToMd.ts）──

    private fun para(vararg inlines: MdInline) = Paragraph(value = inlines.toList())
    private fun root(vararg blocks: MdBlock) = Root(blocks.toList())

    @Test
    fun `token shape matches rn bold color plus nested bold size`() {
        val token = buildEditedTagToken("(edited)")
        assertTrue(isEditedTagToken(token))
        assertEquals(EDITED_TAG_COLOR, token.color)
        val inner = token.value.single() as Bold
        assertEquals(EDITED_TAG_MD_INNER_SIZE, inner.size)
        assertEquals("(edited)", (inner.value.single() as PlainText).value)
    }

    @Test
    fun `appends to last paragraph inline list`() {
        val md = root(para(PlainText("hello")))
        val out = appendEditedTagToMd(md, "(edited)")
        val last = (out.blocks.single() as Paragraph).value
        assertEquals(2, last.size)
        assertTrue(isEditedTagToken(last[1]))
        assertTrue(hasEditedTagInMd(out))
    }

    @Test
    fun `appends into quote deepest list and list item paragraph`() {
        // 引用内段落是文档序最后目标
        val q = root(Quote(value = listOf(para(PlainText("in quote")))))
        val outQ = appendEditedTagToMd(q, "(edited)")
        val quotePara = (outQ.blocks.single() as Quote).value.single() as Paragraph
        assertEquals(2, quotePara.value.size)

        // 列表项段落同样可追加
        val list = root(UnorderedList(value = listOf(ListItem(value = listOf(para(PlainText("item")))))))
        val outL = appendEditedTagToMd(list, "(edited)")
        val itemPara = ((outL.blocks.single() as UnorderedList).value.single() as ListItem)
            .value.single() as Paragraph
        assertEquals(2, itemPara.value.size)
    }

    @Test
    fun `no appendable block adds standalone paragraph`() {
        // 仅 CODE 块：无 inline 目标 → 追加独立 PARAGRAPH
        val md = root(Code(language = "js", value = listOf(CodeLine(PlainText("x")))))
        val out = appendEditedTagToMd(md, "(edited)")
        assertEquals(2, out.blocks.size)
        assertTrue(out.blocks[1] is Paragraph)
        assertTrue(hasEditedTagInMd(out))
    }

    @Test
    fun `already tagged root returned unchanged`() {
        val md = appendEditedTagToMd(root(para(PlainText("hi"))), "(edited)")
        val again = appendEditedTagToMd(md, "(edited)")
        assertEquals(2, ((again.blocks.single() as Paragraph).value).size)
    }

    @Test
    fun `nested bold with color or size is leaf not descend target`() {
        // FontColor/FontSize 形态 BOLD（带 color/size）是叶子：注入点停在其父级数组
        val md = root(para(Bold(value = listOf(PlainText("styled")), color = "#ff0000")))
        val out = appendEditedTagToMd(md, "(edited)")
        val value = (out.blocks.single() as Paragraph).value
        assertEquals(2, value.size) // 追加在段落层，不进 BOLD 内部
        assertTrue(isEditedTagToken(value[1]))
    }

    // ── buildEditContent ──

    @Test
    fun `buildEditContent md path returns tip tap doc with mention restored`() {
        val message = entity(
            msg = "@john hi",
            md = """{"blocks":[{"type":"PARAGRAPH","value":[
                {"type":"MENTION_USER","value":{"type":"PLAIN_TEXT","value":"john"}},
                {"type":"PLAIN_TEXT","value":" hi"}]}]}""",
            ).copy(mentions = """[{"username":"john","name":"John"}]""")
        val doc = buildEditContent(message)
        assertTrue(doc is JsonObject)
        val content = (doc as JsonObject)["content"] as kotlinx.serialization.json.JsonArray
        val para = content.first() as JsonObject
        val mention = (para["content"] as kotlinx.serialization.json.JsonArray).first() as JsonObject
        assertEquals("mention", (mention["type"] as JsonPrimitive).content)
    }

    @Test
    fun `buildEditContent without md falls back to escaped html`() {
        assertEquals(
            "<p>&lt;b&gt;hi &amp; bye&lt;/b&gt;</p>",
            (buildEditContent(entity(msg = "<b>hi & bye</b>")) as JsonPrimitive).content,
        )
    }

    @Test
    fun `buildEditContent bad md falls back to html`() {
        assertEquals("<p>plain</p>", (buildEditContent(entity(msg = "plain", md = "{broken")) as JsonPrimitive).content)
    }
}
