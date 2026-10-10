package cn.appia.im.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** resolveAiMsgKind（RN lib/appiaMessage/aiMsgType.ts 逐分支）。 */
class AiMsgKindTest {

    @Test
    fun `ai_response msgType wins`() {
        assertEquals(AiMsgKind.AI_RESPONSE, resolveAiMsgKind("ai_response", null))
        // msgType 优先于 msgData（RN 先判 msgType）
        assertEquals(AiMsgKind.AI_RESPONSE, resolveAiMsgKind("ai_response", """{"type":"fastModelMsg"}"""))
    }

    @Test
    fun `fastModelMsg msgType`() {
        assertEquals(AiMsgKind.AI_FAST_MODEL, resolveAiMsgKind("fastModelMsg", null))
    }

    @Test
    fun `msgData type fallback`() {
        assertEquals(AiMsgKind.AI_FAST_MODEL, resolveAiMsgKind(null, """{"type":"fastModelMsg"}"""))
        assertEquals(AiMsgKind.AI_FAST_MODEL, resolveAiMsgKind("", """{"refs":{},"type":"fastModelMsg"}"""))
    }

    @Test
    fun `none for regular messages`() {
        assertEquals(AiMsgKind.NONE, resolveAiMsgKind(null, null))
        assertEquals(AiMsgKind.NONE, resolveAiMsgKind("", ""))
        assertEquals(AiMsgKind.NONE, resolveAiMsgKind("t", """{"type":"other"}"""))
    }

    @Test
    fun `bad msgData json falls to none`() {
        assertEquals(AiMsgKind.NONE, resolveAiMsgKind(null, "not json{"))
        assertEquals(AiMsgKind.NONE, resolveAiMsgKind(null, "[1,2]"))
    }
}
