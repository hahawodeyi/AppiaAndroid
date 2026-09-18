package cn.appia.im.feature.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12 mention 数据源单测（RN parseAppiaRoomMembersV2 / agentBotList / MentionSuggestion
 * 过滤管线移植用例）。
 */
class MentionSourceTest {

    // ── appia/room/members/v2 解析 ──

    @Test
    fun `parses blocks map with orderNumber sort and fallback 9999`() {
        val raw = Json.parseToJsonElement(
            """{"success":true,"data":[
              {"org":"A","isLocal":true,"map":{
                 "bob":{"_id":"u-bob","username":"bob","name":"Bob","orderNumber":2},
                 "alice":{"_id":"u-alice","username":"alice","name":"Alice","orderNumber":1}},
               "members":["bob","alice"]},
              {"org":"B","map":{
                 "zoe":{"username":"zoe","name":"Zoe"}},
               "members":["zoe"]}
            ]}""",
        )
        val rows = parseAppiaRoomMembersV2(raw)
        assertEquals(listOf("alice", "bob", "zoe"), rows.map { it.username })
        assertEquals("Bob", rows[1].name)
    }

    @Test
    fun `orderNumber missing sorts last by name compare`() {
        val raw = Json.parseToJsonElement(
            """{"data":[{"map":{
              "b":{"username":"b","name":"B","orderNumber":5},
              "c":{"username":"c","name":"C"},
              "a":{"username":"a","name":"A","orderNumber":5}},
              "members":["b","c","a"]}]}""",
        )
        val rows = parseAppiaRoomMembersV2(raw)
        assertEquals(listOf("a", "b", "c"), rows.map { it.username }) // 5,5 → 名序；无 orderNumber → 9999 垫底
    }

    @Test
    fun `usernames outside map fall back to username row`() {
        val raw = Json.parseToJsonElement(
            """{"data":[{"map":{},"members":["ghost","  spaced  "]}]}""",
        )
        val rows = parseAppiaRoomMembersV2(raw)
        assertEquals(listOf("ghost", "spaced"), rows.map { it.username })
    }

    @Test
    fun `isLocal block overrides duplicated username`() {
        val raw = Json.parseToJsonElement(
            """{"data":[
              {"map":{"dup":{"username":"dup","name":"RemoteName"}},"members":["dup"]},
              {"isLocal":true,"map":{"dup":{"username":"dup","name":"LocalName"}},"members":["dup"]}
            ]}""",
        )
        assertEquals("LocalName", parseAppiaRoomMembersV2(raw).single().name)
    }

    @Test
    fun `departments members are collected and bad shapes tolerated`() {
        val raw = Json.parseToJsonElement(
            """{"data":[{"map":{"x":{"username":"x"}},"departments":[{"members":["x","y"]}]}]}""",
        )
        assertEquals(listOf("x", "y"), parseAppiaRoomMembersV2(raw).map { it.username })
        assertEquals(emptyList<RoomMemberRow>(), parseAppiaRoomMembersV2(null))
        assertEquals(emptyList<RoomMemberRow>(), parseAppiaRoomMembersV2(Json.parseToJsonElement("""{"success":false}""")))
    }

    // ── Agent_Bot_List × Appia_Claw_Agent_Visibility ──

    @Test
    fun `agent bot list parses json array of objects strings and dedupes`() {
        val bots = parseAgentBotMentionList("""[{"username":"claw","name":"Claw"},"helper","claw"]""")
        assertEquals(listOf("claw" to "Claw", "helper" to "helper"), bots.map { it.username to it.name })
    }

    @Test
    fun `agent bot list parses plain comma or newline list`() {
        // RN split(/[,\n]/)+trim：空格分段各成项（"a, b\n c" → a/b/c）
        assertEquals(listOf("a", "b", "c"), parseAgentBotMentionList("a, b\n c").map { it.username })
        assertTrue(parseAgentBotMentionList(null).isEmpty())
        assertTrue(parseAgentBotMentionList("  ").isEmpty())
        // 非合法 JSON 回退分隔符
        assertEquals(listOf("[x"), parseAgentBotMentionList("[x").map { it.username })
    }

    @Test
    fun `claw visibility filters non All configured bots`() {
        val bots = listOf(AgentBotMentionItem("claw", "Claw"), AgentBotMentionItem("helper", "Helper"))
        val vis = parseClawAgentVisibilityMap("""{"claw":"Creator","helper":"All","ghost":"Hidden"}""")
        assertEquals(listOf("helper"), filterBotsByClawAgentVisibility(bots, vis) { it.username }.map { it.username })
        // 未配置恒显示
        assertEquals(2, filterBotsByClawAgentVisibility(bots, emptyMap()) { it.username }.size)
        assertTrue(filterBotsByClawAgentVisibility(emptyList<AgentBotMentionItem>(), vis) { it.username }.isEmpty())
        // 坏 JSON → 空 map
        assertTrue(parseClawAgentVisibilityMap("not json").isEmpty())
    }

    @Test
    fun `agent bots map to candidates with id username`() {
        val c = agentBotsToCandidates(listOf(AgentBotMentionItem("claw", "Claw")))
        assertEquals("claw", c.single().id)
        assertEquals("Claw", c.single().displayName)
    }

    // ── 过滤管线 ──

    @Test
    fun `build candidates filters by name or username includes case insensitive`() {
        val all = listOf(
            MentionCandidate("1", "alice", "Alice Wang"),
            MentionCandidate("2", "bob", "Bob"),
        )
        assertEquals(2, buildMentionCandidates(all, "", includeAllMember = false).size)
        assertEquals(listOf("1"), buildMentionCandidates(all, "wang", includeAllMember = false).map { it.id })
        assertEquals(listOf("2"), buildMentionCandidates(all, "BOB", includeAllMember = false).map { it.id })
        assertTrue(buildMentionCandidates(all, "zzz", includeAllMember = false).isEmpty())
    }

    @Test
    fun `all member is fixed first item for non agent rooms`() {
        val all = listOf(MentionCandidate("1", "alice", "Alice"))
        val display = buildMentionCandidates(all, "", includeAllMember = true)
        assertEquals(ALL_MEMBER, display.first())
        assertEquals("1", display[1].id)
        // ALL 行不参与 query 过滤（写死首项）
        val filtered = buildMentionCandidates(all, "alice", includeAllMember = true)
        assertEquals(ALL_MEMBER, filtered.first())
        assertFalse(filtered.any { it.id == "all" && it !== ALL_MEMBER })
    }
}
