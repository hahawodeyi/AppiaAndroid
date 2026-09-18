package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RN appendEditedTagToMd / isMessageEdited / buildEditContent 移植用例（T12 编辑链）。 */
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

    @Test
    fun `edited tag token shape matches RN buildEditedTagToken`() {
        val token = buildEditedTagToken("(edited)")
        assertEquals(EDITED_TAG_COLOR, token.color)
        assertEquals(12, token.size)
        assertEquals("(edited)", (token.value.single() as PlainText).value)
        assertTrue(isEditedTagToken(token))
        assertFalse(isEditedTagToken(Bold(value = listOf(PlainText("x"))))) // 普通加粗非标记
    }

    @Test
    fun `appends tag into last inline array of last paragraph`() {
        val root = parseMdJson("""{"blocks":[
            {"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"one"}]},
            {"type":"PARAGRAPH","value":[{"type":"BOLD","value":[{"type":"PLAIN_TEXT","value":"two"}]}]}
        ]}""")!!
        val tagged = appendEditedTagToMd(root, "(edited)")
        val last = tagged.blocks.last() as Paragraph
        // 目标是「最后一个可追加 inline 数组」的最深层（RN findLastInlineArray）：标记进 BOLD 内层
        val wrapper = last.value.last() as Bold
        assertTrue(isEditedTagToken(wrapper.value.last() as Bold))
        assertTrue(hasEditedTagInMd(tagged))
        // 首段不动
        assertEquals("one", ((tagged.blocks.first() as Paragraph).value.single() as PlainText).value)
    }

    @Test
    fun `appends into nested list last item inline`() {
        val root = parseMdJson("""{"blocks":[
            {"type":"UNORDERED_LIST","value":[
                {"type":"LIST_ITEM","number":1,"value":[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"item"}]}]}
            ]}
        ]}""")!!
        val tagged = appendEditedTagToMd(root, "(edited)")
        val list = tagged.blocks.single() as UnorderedList
        val item = list.value.single() as ListItem
        val para = item.value.filterIsInstance<Paragraph>().first()
        assertTrue(isEditedTagToken(para.value.last() as Bold))
    }

    @Test
    fun `no inline target falls back to standalone paragraph`() {
        val root = parseMdJson("""{"blocks":[{"type":"CODE","value":[]}]}""")!!
        val tagged = appendEditedTagToMd(root, "(edited)")
        assertEquals(2, tagged.blocks.size)
        val tag = (tagged.blocks.last() as Paragraph).value.single()
        assertTrue(isEditedTagToken(tag as Bold))
    }

    @Test
    fun `idempotent when tag already present`() {
        val root = parseMdJson("""{"blocks":[{"type":"PARAGRAPH","value":[{"type":"PLAIN_TEXT","value":"x"}]}]}""")!!
        val once = appendEditedTagToMd(root, "(edited)")
        val twice = appendEditedTagToMd(once, "(edited)")
        assertEquals(once, twice)
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
        val para = ((doc as JsonObject)["content"] as JsonArray).first() as kotlinx.serialization.json.JsonObject
        val mention = (para["content"] as JsonArray).first() as kotlinx.serialization.json.JsonObject
        assertEquals("mention", (mention["type"] as kotlinx.serialization.json.JsonPrimitive).content)
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
