package cn.appia.im.feature.agents

import cn.appia.im.core.network.api.ClawAgentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Claw Agent 快捷添加/重复 id/管理判定（RN lib/agents/clawAgents.ts +
 * AgentEditorScreen 判定面）：decodeBase64 注入 java.util.Base64（生产用 android.util.Base64 同语义）。
 */
class ClawAgentEditTest {

    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    private val jsonConfig = """{"agentId":"AG-1","apiSecret":"sec","streamUrl":"https://a.example.com"}"""
    private val b64Config: String = Base64.getEncoder().encodeToString(jsonConfig.toByteArray())

    // ── parseClawAgentQuickAddInput ──

    @Test
    fun `empty input yields empty error`() {
        assertEquals(
            ClawAgentQuickAddResult.Failed(ClawAgentQuickAddError.EMPTY),
            parseClawAgentQuickAddInput("   ", ::decode),
        )
    }

    @Test
    fun `plain json parses with apiSecret and streamUrl aliases`() {
        val r = parseClawAgentQuickAddInput(jsonConfig, ::decode)
        assertEquals(ClawAgentQuickAddResult.Ok("AG-1", "sec", "https://a.example.com"), r)
    }

    @Test
    fun `base64 json takes priority over raw candidate`() {
        val r = parseClawAgentQuickAddInput(b64Config, ::decode)
        assertEquals(ClawAgentQuickAddResult.Ok("AG-1", "sec", "https://a.example.com"), r)
    }

    @Test
    fun `base64 with inner whitespace is compacted before decode`() {
        val wrapped = b64Config.chunked(4).joinToString("\n")
        val r = parseClawAgentQuickAddInput(wrapped, ::decode)
        assertTrue(r is ClawAgentQuickAddResult.Ok)
    }

    @Test
    fun `apiKey and url aliases accepted`() {
        val r = parseClawAgentQuickAddInput(
            """{"agentId":"A","apiKey":"k","serviceUrl":"https://u"}""",
            ::decode,
        )
        assertEquals(ClawAgentQuickAddResult.Ok("A", "k", "https://u"), r)
    }

    @Test
    fun `missing field yields missing_fields`() {
        val r = parseClawAgentQuickAddInput("""{"agentId":"A","apiKey":"k"}""", ::decode)
        assertEquals(ClawAgentQuickAddResult.Failed(ClawAgentQuickAddError.MISSING_FIELDS), r)
    }

    @Test
    fun `non-json garbage yields invalid_json`() {
        assertEquals(
            ClawAgentQuickAddResult.Failed(ClawAgentQuickAddError.INVALID_JSON),
            parseClawAgentQuickAddInput("hello world!!", ::decode),
        )
    }

    @Test
    fun `values are trimmed`() {
        val r = parseClawAgentQuickAddInput(
            """{"agentId":"  A  ","apiKey":" k ","url":" https://u "}""",
            ::decode,
        )
        assertEquals(ClawAgentQuickAddResult.Ok("A", "k", "https://u"), r)
    }

    // ── isDuplicateClawAgentIdError ──

    @Test
    fun `duplicate agent id error matches both field spellings`() {
        assertTrue(isDuplicateClawAgentIdError("appiaOpenClawAgentId: already exists"))
        assertTrue(isDuplicateClawAgentIdError("AppiaClawAgentUniqueId already exists"))
        assertTrue(isDuplicateClawAgentIdError("APPIAOPENCLAWAGENTID  :  ALREADY EXISTS"))
    }

    @Test
    fun `unrelated error is not duplicate`() {
        assertFalse(isDuplicateClawAgentIdError("network unreachable"))
        assertFalse(isDuplicateClawAgentIdError("agentId already exists")) // 缺 appia 前缀
    }

    // ── canManageClawAgent ──

    @Test
    fun `manage permission follows creator id`() {
        val item = ClawAgentItem(id = "1", username = "claw.a.bot", name = "A", active = true, creatorUserId = "u-1")
        assertTrue(canManageClawAgent("u-1", item))
        assertFalse(canManageClawAgent("u-2", item))
        // 未标创建者（旧目录行）→ 任何登录者可管理
        val legacy = ClawAgentItem(id = "2", username = "claw.b.bot", name = "B", active = true)
        assertTrue(canManageClawAgent("u-2", legacy))
        assertFalse(canManageClawAgent("", legacy))
    }
}
