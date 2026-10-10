package cn.appia.im.feature.web

import cn.appia.im.core.network.api.ExternalTokenResult
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 白名单/needAuth 拼链/返回栈/降级判定单测（RN needAuthWhitelist.ts + resolveWebViewUrl.ts
 * buildNeedAuthUrl + resolveInAppWebBackAction.ts 对照；TDD ≥6 用例）。
 */
class InAppWebViewParamsTest {

    // ── matchNeedAuthHost（RN needAuthWhitelist.ts:22-33）──

    @Test
    fun `whitelist three domains exact host hit`() {
        assertEquals("survey.appia.cn", matchNeedAuthHost("https://survey.appia.cn/form/1")?.host)
        assertEquals("lexiang.appia.cn", matchNeedAuthHost("https://lexiang.appia.cn/page")?.host)
        assertEquals("ssc-docs.appia.vip", matchNeedAuthHost("http://ssc-docs.appia.vip/doc/x")?.host)
    }

    @Test
    fun `subdomain matches but unrelated domain does not`() {
        assertEquals("survey.appia.cn", matchNeedAuthHost("https://sub.survey.appia.cn/x")?.host)
        assertNull(matchNeedAuthHost("https://appia.cn/form")) // 上级域不命中
        assertNull(matchNeedAuthHost("https://evil-survey.appia.cn/x")) // 前缀伪装不命中
        assertNull(matchNeedAuthHost("https://survey.appia.cn.evil.com/x"))
    }

    @Test
    fun `invalid url returns null`() {
        assertNull(matchNeedAuthHost("not a url"))
        assertNull(matchNeedAuthHost(""))
    }

    // ── resolveInAppWebViewParams（RN needAuthWhitelist.ts:48-60，Triple 含 needVPN）──

    @Test
    fun `whitelist hit forces needAuth and overrides source`() {
        val (needAuth, src, vpn) = resolveInAppWebViewParams("https://survey.appia.cn/q", needAuth = false, source = "custom")
        assertTrue(needAuth)
        assertEquals("", src) // 白名单规则 source 覆盖入参
        assertFalse(vpn)
    }

    @Test
    fun `non whitelist keeps caller needAuth and source`() {
        val (a, b, v) = resolveInAppWebViewParams("https://example.com/x")
        assertFalse(a)
        assertNull(b)
        assertFalse(v)
        val (c, d, w) = resolveInAppWebViewParams("https://example.com/x", needAuth = true, source = "custom")
        assertTrue(c)
        assertEquals("custom", d)
        assertFalse(w)
    }

    @Test
    fun `lexiang whitelist rule and explicit param both force vpn`() {
        val (_, _, vpnRule) = resolveInAppWebViewParams("https://lexiang.appia.cn/p", needVPN = false)
        assertTrue(vpnRule)
        val (_, _, vpnParam) = resolveInAppWebViewParams("https://example.com/x", needVPN = true)
        assertTrue(vpnParam)
    }

    // ── buildNeedAuthUrl（RN resolveWebViewUrl.ts:26-103 最小面）──

    private fun parse(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun `accessUrl hit returns accessUrl directly for non LEXIANG`() {
        val out = buildNeedAuthUrl(
            "https://ssc-docs.appia.vip/doc",
            ExternalTokenResult(success = true, accessUrl = "https://sso.example/login?t=1"),
            needAuth = true, source = null, userId = "u1", username = "alice", enterpriseId = "E1",
        )
        assertEquals("https://sso.example/login?t=1", out)
    }

    @Test
    fun `token appends from code userId username enterpriseId keeping existing params`() {
        val out = buildNeedAuthUrl(
            "https://survey.appia.cn/form?existing=1",
            ExternalTokenResult(success = true, token = "C1"),
            needAuth = true, source = null, userId = "u1", username = "alice", enterpriseId = "E9",
        )
        val u = parse(out)
        assertEquals("appia", u.queryParameter("from"))
        assertEquals("C1", u.queryParameter("code"))
        assertEquals("u1", u.queryParameter("userId"))
        assertEquals("alice", u.queryParameter("username"))
        assertEquals("E9", u.queryParameter("enterpriseId"))
        assertEquals("1", u.queryParameter("existing"))
    }

    @Test
    fun `null auth degrades to bare url`() {
        val out = buildNeedAuthUrl(
            "https://survey.appia.cn/form",
            null, needAuth = true, source = null, userId = "u1", username = "alice", enterpriseId = "E1",
        )
        assertEquals("https://survey.appia.cn/form", out)
    }

    @Test
    fun `empty auth fields degrade to bare url`() {
        val out = buildNeedAuthUrl(
            "https://survey.appia.cn/form",
            ExternalTokenResult(), // success=false 无 token/accessUrl
            needAuth = true, source = null, userId = "u1", username = "alice", enterpriseId = "E1",
        )
        assertEquals("https://survey.appia.cn/form", out)
    }

    @Test
    fun `chat-gpt source uses session token as code`() {
        val out = buildNeedAuthUrl(
            "https://bot.example/app",
            ExternalTokenResult(), // 换码空结果降级，chat-gpt 用 IM sessionToken
            needAuth = true, source = "chat-gpt", userId = "u1", username = "alice", enterpriseId = "E1",
            sessionToken = "sess-tok",
        )
        assertEquals("sess-tok", parse(out).queryParameter("code"))
    }

    @Test
    fun `chat-gpt without session token degrades to bare url`() {
        val out = buildNeedAuthUrl(
            "https://bot.example/app",
            null,
            needAuth = true, source = "chat-gpt", userId = "u1", username = "alice", enterpriseId = "E1",
            sessionToken = "",
        )
        assertEquals("https://bot.example/app", out)
    }

    @Test
    fun `chat-gpt with needAuth false returns url unchanged RN gate`() {
        // RN :40 `if (!needAuth) return rawUrl`：评审 I-1 反例——sessionToken 不得静默拼上
        val out = buildNeedAuthUrl(
            "https://bot.example/app",
            null,
            needAuth = false, source = "chat-gpt", userId = "u1", username = "alice", enterpriseId = "E1",
            sessionToken = "sess-tok",
        )
        assertEquals("https://bot.example/app", out)
    }

    @Test
    fun `LEXIANG source appends params onto accessUrl`() {
        val out = buildNeedAuthUrl(
            "https://lexiang.appia.cn/x",
            ExternalTokenResult(success = true, accessUrl = "https://lexiang.appia.cn/enter", token = "C7"),
            needAuth = true, source = "LEXIANG", userId = "u1", username = "alice", enterpriseId = "E3",
        )
        val u = parse(out)
        assertEquals("C7", u.queryParameter("code"))
        assertEquals("u1", u.queryParameter("userId"))
        assertEquals("alice", u.queryParameter("username"))
        assertEquals("E3", u.queryParameter("enterpriseId"))
        assertEquals("appia", u.queryParameter("from"))
    }

    // ── shouldWebViewGoBack 已删除：M7-T5 起 BackHandler 走 resolveInAppWebBackAction 全分支
    //（chat-gpt/BACK_CLOSE_URL_SEGMENTS/根页 origin+hash 去 query 比较/ANT_AGENT_FORCE_POP_URLS），
    // 对应用例迁至 InAppWebInterceptsTest。

    // ── urlsSameOrigin（RN urlsSameOrigin.ts:1-7）──

    @Test
    fun `same origin detection for cookie injection scope`() {
        assertTrue(urlsSameOrigin("https://im.example.com/x", "https://im.example.com"))
        assertFalse(urlsSameOrigin("https://other.com/x", "https://im.example.com"))
        assertFalse(urlsSameOrigin("bad url", "https://im.example.com"))
    }

    // ── buildMeteorLocalStorageScript（RN buildInjectedScripts.ts:62-75 localStorage 段）──

    @Test
    fun `script writes four meteor localstorage keys with json escaped values`() {
        val s = buildMeteorLocalStorageScript("tok-1", "uid-1", "E1")
        assertTrue(s.contains("localStorage.setItem('Meteor.loginToken', \"tok-1\")"))
        assertTrue(s.contains("localStorage.setItem('Meteor.userId', \"uid-1\")"))
        assertTrue(s.contains("localStorage.setItem('source', 'appia')"))
        assertTrue(s.contains("localStorage.setItem('org', \"E1\")"))
        // 条件写 + try/catch（RN 同形）
        assertTrue(s.contains("!== \"tok-1\""))
        assertTrue(s.contains("try {"))
    }

    @Test
    fun `quote injection in values is json escaped`() {
        // RN JSON.stringify 同防引号注入：值内引号转义后入脚本，原样值不得裸现
        val raw = """a"; alert(1); """
        val s = buildMeteorLocalStorageScript(raw, "u", "E")
        assertTrue(s.contains(JsonPrimitive(raw).toString()))
        assertFalse(s.contains(raw))
    }
}
