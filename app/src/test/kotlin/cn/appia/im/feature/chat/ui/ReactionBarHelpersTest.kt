package cn.appia.im.feature.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * reactions 解析与 toggle 翻转纯函数测试（brief 绑定裁定 2/3）：
 * JSON 形状 `{'<shortname>': {_id, emoji, usernames[], names?[]}}`；
 * 自己已回应判定 = usernames.includes(username)（**username 非 userId**，legacy Reactions.tsx:51）；
 * 解析失败/空返回空表；toggle 翻转后回推校正前的乐观过渡态形状。
 */
class ReactionBarHelpersTest {

    // ── parseReactions ──

    @Test
    fun `parse full shape keeps order and fields`() {
        val json = """
            {":tada:":{"_id":"m1:tada:","emoji":":tada:","usernames":["bob","alice"],"names":["Bob","Alice"]},
             ":+1:":{"_id":"m1:+1:","emoji":":+1:","usernames":["carol"]}}
        """.trimIndent()
        val parsed = parseReactions(json)
        assertEquals(2, parsed.size)
        assertEquals(":tada:", parsed[0].shortname)
        assertEquals(":tada:", parsed[0].emoji)
        assertEquals(listOf("bob", "alice"), parsed[0].usernames)
        assertEquals(listOf("Bob", "Alice"), parsed[0].names)
        assertEquals(":+1:", parsed[1].shortname)
        assertNull(parsed[1].names)
    }

    @Test
    fun `parse null blank bad json or non object returns empty`() {
        assertEquals(emptyList<Reaction>(), parseReactions(null))
        assertEquals(emptyList<Reaction>(), parseReactions(""))
        assertEquals(emptyList<Reaction>(), parseReactions("  "))
        assertEquals(emptyList<Reaction>(), parseReactions("not json {"))
        assertEquals(emptyList<Reaction>(), parseReactions("""["a","b"]"""))
        assertEquals(emptyList<Reaction>(), parseReactions("42"))
    }

    @Test
    fun `parse missing or malformed usernames yields empty and skips non string entries`() {
        val parsed = parseReactions(
            """{":tada:":{"_id":"m1:tada:","emoji":":tada:"}}""",
        )
        assertEquals(listOf(":tada:"), parsed.map { it.shortname })
        assertEquals(emptyList<String>(), parsed[0].usernames)

        val mixed = parseReactions(
            """{":tada:":{"usernames":["bob",42,null,"alice"]}}""",
        )
        assertEquals(listOf("bob", "alice"), mixed[0].usernames)
    }

    @Test
    fun `parse emoji falls back to key when inner emoji missing`() {
        val parsed = parseReactions("""{":heart:":{"usernames":["bob"]}}""")
        assertEquals(":heart:", parsed[0].emoji)
    }

    // ── isMine：username 非 userId ──

    @Test
    fun `isMine matches username not userId`() {
        val r = parseReactions("""{":tada:":{"usernames":["bob"]}}""")[0]
        assertTrue(r.isMine("bob"))
        // legacy Reactions.tsx:51 对照：usernames 存 username，userId 传入不命中（不误判已回应）
        assertFalse(r.isMine("u-1"))
        assertFalse(r.isMine(null))
    }

    // ── toggleReactionJson ──

    @Test
    fun `toggle on empty adds entry with server shape`() {
        val json = toggleReactionJson(null, "m1", ":tada:", "bob")
        assertEquals(
            """{":tada:":{"_id":"m1:tada:","emoji":":tada:","usernames":["bob"]}}""",
            json,
        )
    }

    @Test
    fun `toggle appends username to existing entry preserving order`() {
        val json = """{":tada:":{"_id":"m1:tada:","emoji":":tada:","usernames":["bob"]},":fire:":{"usernames":["carol"]}}"""
        val next = parseReactions(toggleReactionJson(json, "m1", ":tada:", "alice"))
        assertEquals(2, next.size)
        assertEquals(listOf("bob", "alice"), next[0].usernames)
        assertEquals(":fire:", next[1].shortname) // 原条目顺序保持
    }

    @Test
    fun `toggle removes own username and drops entry when last`() {
        val json = """{":tada:":{"usernames":["bob"]}}"""
        assertNull(toggleReactionJson(json, "m1", ":tada:", "bob"))
    }

    @Test
    fun `toggle removal keeps other users`() {
        val json = """{":tada:":{"usernames":["bob","alice"]}}"""
        val next = parseReactions(toggleReactionJson(json, "m1", ":tada:", "bob"))
        assertEquals(listOf("alice"), next.single().usernames)
    }

    @Test
    fun `toggle removal keeps other entries and clears column when all gone`() {
        val json = """{":tada:":{"usernames":["bob"]},":fire:":{"usernames":["alice"]}}"""
        val one = toggleReactionJson(json, "m1", ":tada:", "bob")
        assertEquals(listOf(":fire:"), parseReactions(one).map { it.shortname })
        // 仅剩一条再撤 → 列清空（null）
        assertNull(toggleReactionJson(one, "m1", ":fire:", "alice"))
    }

    @Test
    fun `toggle is idempotent per pair add then remove restores null`() {
        val added = toggleReactionJson(null, "m1", ":rocket:", "bob")
        assertEquals(listOf("bob"), parseReactions(added).single().usernames)
        assertNull(toggleReactionJson(added, "m1", ":rocket:", "bob"))
    }
}
