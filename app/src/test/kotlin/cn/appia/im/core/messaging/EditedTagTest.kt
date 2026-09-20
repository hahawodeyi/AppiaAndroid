package cn.appia.im.core.messaging

import cn.appia.im.core.database.entity.MessageEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** isMessageEdited（RN isMessageEdited.ts）+ buildEditContent（mdToTipTap 回填）用例。 */
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
