package cn.appia.im.feature.web

import cn.appia.im.core.network.api.ExternalTokenResult
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

    // ── resolveInAppWebViewParams（RN needAuthWhitelist.ts:48-60）──

    @Test
    fun `whitelist hit forces needAuth and overrides source`() {
        val (needAuth, src) = resolveInAppWebViewParams("https://survey.appia.cn/q", needAuth = false, source = "custom")
        assertTrue(needAuth)
        assertEquals("", src) // 白名单规则 source 覆盖入参
    }

    @Test
    fun `non whitelist keeps caller needAuth and source`() {
        val (a, b) = resolveInAppWebViewParams("https://example.com/x")
        assertFalse(a)
        assertNull(b)
        val (c, d) = resolveInAppWebViewParams("https://example.com/x", needAuth = true, source = "custom")
        assertTrue(c)
        assertEquals("custom", d)
    }

    // ── buildNeedAuthUrl（RN resolveWebViewUrl.ts:26-103 最小面）──

    private fun parse(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    @Test
    fun `accessUrl hit returns accessUrl directly for non LEXIANG`() {
        val out = buildNeedAuthUrl(
            "https://ssc-docs.appia.vip/doc",
            ExternalTokenResult(success = true, accessUrl = "https://sso.example/login?t=1"),
            source = null, userId = "u1", username = "alice", enterpriseId = "E1",
        )
        assertEquals("https://sso.example/login?t=1", out)
    }

    @Test
    fun `token appends from code userId username enterpriseId keeping existing params`() {
        val out = buildNeedAuthUrl(
            "https://survey.appia.cn/form?existing=1",
            ExternalTokenResult(success = true, token = "C1"),
            source = null, userId = "u1", username = "alice", enterpriseId = "E9",
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
            null, source = null, userId = "u1", username = "alice", enterpriseId = "E1",
        )
        assertEquals("https://survey.appia.cn/form", out)
    }

    @Test
    fun `empty auth fields degrade to bare url`() {
        val out = buildNeedAuthUrl(
            "https://survey.appia.cn/form",
            ExternalTokenResult(), // success=false 无 token/accessUrl
            source = null, userId = "u1", username = "alice", enterpriseId = "E1",
        )
        assertEquals("https://survey.appia.cn/form", out)
    }

    @Test
    fun `LEXIANG source appends params onto accessUrl`() {
        val out = buildNeedAuthUrl(
            "https://lexiang.appia.cn/x",
            ExternalTokenResult(success = true, accessUrl = "https://lexiang.appia.cn/enter", token = "C7"),
            source = "LEXIANG", userId = "u1", username = "alice", enterpriseId = "E3",
        )
        val u = parse(out)
        assertEquals("C7", u.queryParameter("code"))
        assertEquals("u1", u.queryParameter("userId"))
        assertEquals("alice", u.queryParameter("username"))
        assertEquals("E3", u.queryParameter("enterpriseId"))
        assertEquals("appia", u.queryParameter("from"))
    }

    // ── shouldWebViewGoBack（返回栈域外判定，RN resolveInAppWebBackAction 简明化）──

    @Test
    fun `cannot go back pops stack`() {
        assertFalse(shouldWebViewGoBack("https://a.com/", canGoBack = false, rootUrl = "https://a.com/"))
    }

    @Test
    fun `out of domain current page pops stack`() {
        assertFalse(shouldWebViewGoBack("https://other.com/x", canGoBack = true, rootUrl = "https://a.com/"))
    }

    @Test
    fun `same domain subpage goes webview back`() {
        assertTrue(shouldWebViewGoBack("https://a.com/list", canGoBack = true, rootUrl = "https://a.com/home"))
    }

    @Test
    fun `back at root page pops stack`() {
        assertFalse(shouldWebViewGoBack("https://a.com/home", canGoBack = true, rootUrl = "https://a.com/home"))
    }

    // ── urlsSameOrigin（RN urlsSameOrigin.ts:1-7）──

    @Test
    fun `same origin detection for cookie injection scope`() {
        assertTrue(urlsSameOrigin("https://im.example.com/x", "https://im.example.com"))
        assertFalse(urlsSameOrigin("https://other.com/x", "https://im.example.com"))
        assertFalse(urlsSameOrigin("bad url", "https://im.example.com"))
    }
}
