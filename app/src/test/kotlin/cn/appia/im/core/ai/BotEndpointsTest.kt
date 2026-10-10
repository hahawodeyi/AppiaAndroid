package cn.appia.im.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * RN lib/ai/botConfig.ts resolveBotEndpoints + useAiTrigger parseAgentBotList 对照（M7-T6）。
 * 端点表完整（staffService 客服域 stop 端点保留，UI 归 M10）。
 */
class BotEndpointsTest {

    @Test
    fun `staffService dot bot maps to saveToStaffServiceAgent with stop`() {
        val ep = resolveBotEndpoints("staffService.bot", emptyList())
        assertEquals("/api/v1/bot.saveToStaffServiceAgent", ep!!.stream)
        assertEquals("/api/v1/bot.stopToStaffServiceAgent", ep.stop)
    }

    @Test
    fun `agent dot bot maps to sendToAI without stop`() {
        val ep = resolveBotEndpoints("agent.bot", emptyList())
        assertEquals("/api/v1/bot.sendToAI", ep!!.stream)
        assertNull(ep.stop)
    }

    @Test
    fun `personal dot bot maps to sendToAI without stop`() {
        val ep = resolveBotEndpoints("personal.bot", emptyList())
        assertEquals("/api/v1/bot.sendToAI", ep!!.stream)
        assertNull(ep.stop)
    }

    @Test
    fun `custom bot in agentBotList maps to saveToClawAgent`() {
        val ep = resolveBotEndpoints("claw.bot", listOf("claw.bot"))
        assertEquals("/api/v1/bot.saveToClawAgent", ep!!.stream)
        assertNull(ep.stop)
    }

    @Test
    fun `custom bot not in agentBotList resolves null`() {
        assertNull(resolveBotEndpoints("stranger.bot", listOf("claw.bot")))
    }

    @Test
    fun `empty agentBotList custom bot resolves null`() {
        assertNull(resolveBotEndpoints("claw.bot", emptyList()))
    }

    @Test
    fun `builtin config wins even if listed in agentBotList`() {
        val list = listOf("staffService.bot", "agent.bot", "personal.bot")
        assertEquals("/api/v1/bot.saveToStaffServiceAgent", resolveBotEndpoints("staffService.bot", list)!!.stream)
        assertEquals("/api/v1/bot.sendToAI", resolveBotEndpoints("agent.bot", list)!!.stream)
    }

    @Test
    fun `lookup is case sensitive like RN record`() {
        assertNull(resolveBotEndpoints("StaffService.bot", emptyList()))
        assertNull(resolveBotEndpoints("AGENT.BOT", emptyList()))
    }

    @Test
    fun `parseAgentBotList json string array dedups in order`() {
        assertEquals(listOf("a.bot", "b.bot"), parseAgentBotList("""["a.bot", "b.bot", "a.bot"]"""))
    }

    @Test
    fun `parseAgentBotList comma and newline mixed`() {
        assertEquals(listOf("a.bot", "b.bot", "c.bot"), parseAgentBotList("a.bot,\nb.bot , c.bot"))
    }

    @Test
    fun `parseAgentBotList invalid json falls back to separator split`() {
        assertEquals(listOf("[a.bot", "b.bot"), parseAgentBotList("[a.bot,b.bot"))
    }

    @Test
    fun `parseAgentBotList blank and null are empty`() {
        assertEquals(emptyList<String>(), parseAgentBotList(null))
        assertEquals(emptyList<String>(), parseAgentBotList("  "))
    }

    @Test
    fun `parseAgentBotList json non-string elements are skipped`() {
        assertEquals(listOf("a.bot"), parseAgentBotList("""["a.bot", 1, null, {"username":"o.bot"}]"""))
    }
}
