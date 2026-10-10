package cn.appia.im.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RN lib/ai/parseBotMentions.ts / hooks/useAiTrigger.ts / screens/RoomScreen/aiTriggerWiring.ts
 * 触发判定对照（M7-T6）。
 */
class AiTurnResolverTest {

    // ── extractBotMentionsFromText（保序去重）──

    @Test
    fun `extract preserves order and dedups`() {
        assertEquals(
            listOf("b.bot", "a.bot"),
            extractBotMentionsFromText("@b.bot hi @a.bot and @b.bot again"),
        )
    }

    @Test
    fun `extract ignores non-bot mentions`() {
        assertEquals(emptyList<String>(), extractBotMentionsFromText("@zhangsan @lisi hello"))
    }

    @Test
    fun `extract requires word boundary after bot`() {
        assertEquals(emptyList<String>(), extractBotMentionsFromText("@a.botx mail"))
        assertEquals(listOf("a.bot"), extractBotMentionsFromText("@a.bot, ping"))
    }

    @Test
    fun `extract empty msg is empty`() {
        assertEquals(emptyList<String>(), extractBotMentionsFromText(""))
    }

    // ── stripBotMentionsFromText ──

    @Test
    fun `strip removes bot mention with following space`() {
        assertEquals("帮我看下", stripBotMentionsFromText("@a.bot 帮我看下"))
    }

    @Test
    fun `strip removes all occurrences and trims`() {
        assertEquals("a b", stripBotMentionsFromText(" @x.bot a @y.bot b "))
    }

    @Test
    fun `strip keeps human mentions`() {
        assertEquals("@zhangsan 在吗", stripBotMentionsFromText("@zhangsan @a.bot 在吗"))
    }

    // ── shouldTriggerAi ──

    @Test
    fun `staffService triggers always in non-human state`() {
        assertTrue(shouldTriggerAi(true, null, "查询进度", emptyList(), emptyList()))
        assertTrue(shouldTriggerAi(true, "urobot", "查询进度", emptyList(), emptyList()))
    }

    @Test
    fun `staffService does not trigger after transferred to agent`() {
        assertFalse(shouldTriggerAi(true, "agent", "查询进度", emptyList(), emptyList()))
    }

    @Test
    fun `staffService does not trigger on transfer keyword`() {
        assertFalse(shouldTriggerAi(true, "urobot", "我要转人工", emptyList(), emptyList()))
    }

    @Test
    fun `normal room needs bot mention`() {
        assertFalse(shouldTriggerAi(false, null, "hello", emptyList(), listOf("a.bot")))
    }

    @Test
    fun `normal room unresolvable bot does not trigger`() {
        assertFalse(shouldTriggerAi(false, null, "@x.bot hi", listOf("x.bot"), listOf("a.bot")))
    }

    @Test
    fun `normal room resolvable bot triggers`() {
        assertTrue(shouldTriggerAi(false, null, "@a.bot hi", listOf("a.bot"), listOf("a.bot")))
    }

    @Test
    fun `normal room triggers when any mention resolvable`() {
        assertTrue(shouldTriggerAi(false, null, "@x.bot @a.bot", listOf("x.bot", "a.bot"), listOf("a.bot")))
    }

    // ── resolveAiTurn ──

    private val input = AiTurnInput(rid = "r1", fromAgent = false, isStaffService = false, msg = "@a.bot 帮忙")

    @Test
    fun `hit returns all mentions stripped prompt and ids`() {
        val turn = resolveAiTurn(
            input.copy(msg = "@x.bot @a.bot 帮忙"),
            agentBotList = listOf("a.bot"),
            siteUrl = "https://s.cn",
            userMessageId = "m9",
        )
        assertEquals(listOf("x.bot", "a.bot"), turn!!.bots)
        assertEquals("帮忙", turn.prompt)
        assertEquals("m9", turn.relatedUserMessageId)
        assertFalse(turn.inAgentRoom)
    }

    @Test
    fun `agent room marks inAgentRoom`() {
        val turn = resolveAiTurn(
            input.copy(fromAgent = true, msg = "@agent.bot 你好"),
            agentBotList = emptyList(),
            siteUrl = "",
            userMessageId = "m1",
        )
        assertTrue(turn!!.inAgentRoom)
        assertEquals(listOf("agent.bot"), turn.bots)
        assertEquals("你好", turn.prompt)
    }

    @Test
    fun `staffService turn uses fixed bot and raw prompt`() {
        val turn = resolveAiTurn(
            AiTurnInput(rid = "r2", fromAgent = false, isStaffService = true, staffAssignType = "urobot", msg = "查进度"),
            agentBotList = emptyList(),
            siteUrl = "",
            userMessageId = "m2",
        )
        assertEquals(listOf("staffService.bot"), turn!!.bots)
        assertEquals("查进度", turn.prompt)
        assertFalse(turn.inAgentRoom)
    }

    @Test
    fun `staffService transferred or keyword returns null`() {
        assertNull(
            resolveAiTurn(
                AiTurnInput("r", false, true, staffAssignType = "agent", msg = "x"),
                emptyList(), "", "m",
            ),
        )
        assertNull(
            resolveAiTurn(
                AiTurnInput("r", false, true, msg = "转人工"),
                emptyList(), "", "m",
            ),
        )
    }

    @Test
    fun `no resolvable bot returns null`() {
        assertNull(resolveAiTurn(input.copy(msg = "plain"), listOf("a.bot"), "", "m"))
        assertNull(resolveAiTurn(input.copy(msg = "@x.bot hi"), listOf("a.bot"), "", "m"))
    }
}
