package cn.appia.im.core.chat

import cn.appia.im.core.database.entity.ChatEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选人器判定/解析纯函数（RN src/lib/chat/createChannelSelection.ts +
 * CreateChannelMembersScreen partnerGroups/useRecentContacts 语义）自检。
 */
class CreateChannelSelectionTest {

    // ── canConfirmCreateChannel（RN :3-20）──

    @Test
    fun `members mode requires two users unless all`() {
        // 1 人（仅 me 预选）不可确认
        assertFalse(canConfirmCreateChannel(CreateChannelMode.MEMBERS, all = false, setOf("me"), emptySet()))
        // 2 人可确认
        assertTrue(canConfirmCreateChannel(CreateChannelMode.MEMBERS, all = false, setOf("me", "bob"), emptySet()))
        // all 恒可（0 人也真）
        assertTrue(canConfirmCreateChannel(CreateChannelMode.MEMBERS, all = true, emptySet(), emptySet()))
    }

    @Test
    fun `members mode all flag short-circuits user count`() {
        // all=true：0 人也可确认（RN :12 if (all) return true）
        assertTrue(canConfirmCreateChannel(CreateChannelMode.MEMBERS, all = true, emptySet(), emptySet()))
        // all=false：2 人可确认
        assertTrue(canConfirmCreateChannel(CreateChannelMode.MEMBERS, all = false, setOf("a", "b"), emptySet()))
    }

    @Test
    fun `org mode allows depIds alone or users alone`() {
        // org：任一部门即真（RN :19）
        assertTrue(canConfirmCreateChannel(CreateChannelMode.ORG, all = false, emptySet(), setOf("EMT-1")))
        // org：2 用户无部门也真
        assertTrue(canConfirmCreateChannel(CreateChannelMode.ORG, all = false, setOf("a", "b"), emptySet()))
        // org：1 用户 0 部门假
        assertFalse(canConfirmCreateChannel(CreateChannelMode.ORG, all = false, setOf("a"), emptySet()))
    }

    // ── canConfirmAddToRoom（RN :22-32）──

    @Test
    fun `addToRoom any depId confirms`() {
        assertTrue(canConfirmAddToRoom(setOf("x"), emptySet(), setOf("EMT-1")))
    }

    @Test
    fun `addToRoom needs a non-existing user when no depIds`() {
        // 全是既有成员 → 假
        assertFalse(canConfirmAddToRoom(setOf("x", "y"), setOf("x", "y"), emptySet()))
        // 有 1 个新用户 → 真
        assertTrue(canConfirmAddToRoom(setOf("x"), setOf("x", "new"), emptySet()))
        // 空选 → 假
        assertFalse(canConfirmAddToRoom(setOf("x"), emptySet(), emptySet()))
    }

    // ── mapRecentContactRows（RN useRecentContacts :51-70）──

    private fun directChat(id: String, usernames: String, name: String, fname: String = "") = ChatEntity(
        _id = id, rid = id, name = name, fname = fname, t = "d", open = true,
        archived = false, usernames = usernames, team_id = "",
        f = false, ts = 0.0, ls = 0.0, alert = false, unread = 0.0,
        user_mentions = 0.0, group_mentions = 0.0, room_updated_at = 0.0, ro = false,
        auto_translate_language = "en",
    )

    @Test
    fun `recent rows pick the other username and dedupe keeping first`() {
        val chats = listOf(
            directChat("1", """["me","bob"]""", "bob", "Bob B"),
            directChat("2", """["bob","me"]""", "bob"), // dup bob → dropped
            directChat("3", """["me"]""", "carol"), // usernames 无对方 → 回退 name
        )
        val rows = mapRecentContactRows(chats, me = "me")
        assertEquals(listOf(RecentContactRow("bob", "Bob B"), RecentContactRow("carol", "carol")), rows)
    }

    @Test
    fun `recent displayName falls back to other username when fname empty`() {
        val rows = mapRecentContactRows(listOf(directChat("1", """["me","dan"]""", "dan")), "me")
        assertEquals(listOf(RecentContactRow("dan", "dan")), rows)
    }

    @Test
    fun `parseJsonStringArray tolerates invalid json`() {
        assertEquals(emptyList<String>(), parseJsonStringArray("not json"))
        assertEquals(emptyList<String>(), parseJsonStringArray(null))
    }

    // ── partner groups（RN :664-674 useMemo + :678-709 三态/全选）──

    @Test
    fun `mapPartnerGroups parses envelope with title fallback and sort`() {
        val raw = Json.parseToJsonElement(
            """
            {"b": {"name": "Beta", "usersArray": [{"username": "u1", "name": "U1"}]},
             "a": {"companyName": "Alpha", "usersArray": [{"username": "u2"}]}}
            """.trimIndent(),
        )
        val groups = mapPartnerGroups(raw)
        assertEquals(listOf("Alpha", "Beta"), groups.map { it.title })
        assertEquals("u1", groups[1].users[0].username)
        assertEquals("U1", groups[1].users[0].name)
        assertEquals(null, groups[0].users[0].name)
    }

    @Test
    fun `mapPartnerGroups drops groups without users and non-object input`() {
        assertEquals(emptyList<PartnerGroup>(), mapPartnerGroups(Json.parseToJsonElement("[]")))
        val raw = Json.parseToJsonElement("""{"empty": {"usersArray": []}}""")
        assertEquals(emptyList<PartnerGroup>(), mapPartnerGroups(raw))
    }

    @Test
    fun `partner check state is tri-state`() {
        val g = PartnerGroup("k", "T", listOf(PartnerContact("a", "A"), PartnerContact("b", "B")))
        assertEquals(PartnerCheckState.UNCHECKED, partnerGroupCheckState(g, emptySet()))
        assertEquals(PartnerCheckState.CHECKED, partnerGroupCheckState(g, setOf("a", "b")))
        assertEquals(PartnerCheckState.INDETERMINATE, partnerGroupCheckState(g, setOf("a")))
    }

    @Test
    fun `togglePartnerGroupUsers keeps me when unchecking all`() {
        val g = PartnerGroup("k", "T", listOf(PartnerContact("me", "Me"), PartnerContact("a", "A")))
        // checked → 全清（me 保留）
        val after = togglePartnerGroupUsers(g, setOf("me", "a"), me = "me")
        assertEquals(setOf("me"), after)
        // indeterminate → 全补
        val after2 = togglePartnerGroupUsers(g, setOf("me"), me = "me")
        assertEquals(setOf("me", "a"), after2)
    }

    @Test
    fun `filterPartnerGroups filters users by name or username`() {
        val groups = listOf(
            PartnerGroup("k", "T", listOf(PartnerContact("u1", "Zhang San"), PartnerContact("u2", "Li"))),
        )
        val hit = filterPartnerGroups(groups, "zhang")
        assertEquals(1, hit[0].users.size)
        assertEquals(emptyList<PartnerGroup>(), filterPartnerGroups(groups, "nope"))
    }
}
