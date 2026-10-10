package cn.appia.im.feature.web

import cn.appia.im.core.network.api.ExternalTokenResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * InAppWeb 拦截族单测（M7-T5，RN resolveWebViewUrl.ts appendShimoQueryIfNeeded/
 * rewriteWpsAccessEntryUrl + resolveInAppWebBackAction.ts + meetingUrlPatterns.ts +
 * vpnReachability.ts + webAuth.ts getFanweiWeaverToken 对照）：
 * URL 改写链 ≥12、返回决策全分支 ≥10、泛微 token 去重 ≥4、VPN 判定 ≥4。
 */
class InAppWebInterceptsTest {

    private val fanwei = "https://m.appia.vip"

    // ── ② appendShimoQueryIfNeeded（RN resolveWebViewUrl.ts:4-20）──

    @Test
    fun `shimo url appends org source userId token with lowercased org`() {
        val out = appendShimoQueryIfNeeded(
            "https://app.shimo.im/shimo-web/sheet/abc",
            enterpriseId = "BIT-01", userId = "u1", token = "tk",
        )
        assertEquals(
            "https://app.shimo.im/shimo-web/sheet/abc?org=bit-01&source=appia&userId=u1&token=tk",
            out,
        )
    }

    @Test
    fun `shimo url keeps existing params and replaces org in place`() {
        val out = appendShimoQueryIfNeeded(
            "https://s.shimo.im/shimo-web/f?x=1&org=OLD",
            enterpriseId = "Bit", userId = "u1", token = "tk",
        )
        assertEquals(
            "https://s.shimo.im/shimo-web/f?x=1&org=bit&source=appia&userId=u1&token=tk",
            out,
        )
    }

    @Test
    fun `shimo url already containing token or userId unchanged`() {
        val withToken = "https://app.shimo.im/shimo-web/a?token=1"
        assertEquals(withToken, appendShimoQueryIfNeeded(withToken, "E", "u", "t"))
        val withUserId = "https://app.shimo.im/shimo-web/a?userId=9"
        assertEquals(withUserId, appendShimoQueryIfNeeded(withUserId, "E", "u", "t"))
    }

    @Test
    fun `shimo substring token in path skips append RN quirk`() {
        // RN includes('token') 是全串子串判定，路径含 token 也跳过——锁行为
        val quirk = "https://app.shimo.im/shimo-web/tokens/list"
        assertEquals(quirk, appendShimoQueryIfNeeded(quirk, "E", "u", "t"))
    }

    @Test
    fun `non shimo and invalid urls unchanged`() {
        assertEquals("https://survey.appia.cn/x", appendShimoQueryIfNeeded("https://survey.appia.cn/x", "E", "u", "t"))
        assertEquals("shimo-web not a url", appendShimoQueryIfNeeded("shimo-web not a url", "E", "u", "t"))
    }

    // ── ③ rewriteWpsAccessEntryUrl（RN resolveWebViewUrl.ts:108-152）──

    @Test
    fun `wps org host hit builds getAccessToken entry with redirect`() {
        val out = rewriteWpsAccessEntryUrl("https://docs.bitmain.vip/doc/1", "Bitmain")
        assertEquals(
            "https://docs.bitmain.vip/c/bitedzservice/getAccessToken?org=bitmain" +
                "&redirect_url=https%3A%2F%2Fdocs.bitmain.vip%2Fdoc%2F1",
            out,
        )
    }

    @Test
    fun `wps appia vip maps enterprise id to org domain`() {
        val out = rewriteWpsAccessEntryUrl("https://docs.appia.vip/x/1", "SOPHGO")
        assertEquals(
            "https://docs.sophgo.com/c/bitedzservice/getAccessToken?org=sophgo" +
                "&redirect_url=https%3A%2F%2Fdocs.sophgo.com%2Fx%2F1",
            out,
        )
    }

    @Test
    fun `wps appia vip unknown org falls back to all host`() {
        val out = rewriteWpsAccessEntryUrl("https://docs.appia.vip/x", "zzz")
        assertEquals(
            "https://docs.appia.vip/c/bitedzservice/getAccessToken?org=zzz" +
                "&redirect_url=https%3A%2F%2Fdocs.appia.vip%2Fx",
            out,
        )
    }

    @Test
    fun `wps appia vip known org bitmain rewrites to bitmain domain`() {
        val out = rewriteWpsAccessEntryUrl("https://docs.appia.vip/x", "Bitmain")
        assertEquals(
            "https://docs.bitmain.vip/c/bitedzservice/getAccessToken?org=bitmain" +
                "&redirect_url=https%3A%2F%2Fdocs.bitmain.vip%2Fx",
            out,
        )
    }

    @Test
    fun `wps ssc and antpool hosts build entries with empty and lowercased org`() {
        assertEquals(
            "https://ssc-docs.appia.vip/c/bitedzservice/getAccessToken?org=" +
                "&redirect_url=https%3A%2F%2Fssc-docs.appia.vip%2Fd%2F1",
            rewriteWpsAccessEntryUrl("https://ssc-docs.appia.vip/d/1", ""),
        )
        assertEquals(
            "https://antpool-docs.appia.vip/c/bitedzservice/getAccessToken?org=antpool" +
                "&redirect_url=https%3A%2F%2Fantpool-docs.appia.vip%2Fdoc",
            rewriteWpsAccessEntryUrl("https://antpool-docs.appia.vip/doc", "AntPool"),
        )
    }

    @Test
    fun `wps redirect encodes query and fragment of original url`() {
        val out = rewriteWpsAccessEntryUrl("https://docs.bitmain.vip/d?a=1&b=2#f", "bitmain")
        assertTrue(out!!.endsWith("redirect_url=https%3A%2F%2Fdocs.bitmain.vip%2Fd%3Fa%3D1%26b%3D2%23f"))
    }

    @Test
    fun `non wps url returns null`() {
        assertNull(rewriteWpsAccessEntryUrl("https://example.com/x", "bitmain"))
    }

    // ── URL 改写链组合（RN InAppWebScreen :227-247 顺序：WPS → needAuth → 石墨）──

    @Test
    fun `chain wps rewrite then needAuth appends code onto entry url`() {
        val wps = rewriteWpsAccessEntryUrl("https://docs.bitmain.vip/x", "bitmain")!!
        val next = buildNeedAuthUrl(
            wps, ExternalTokenResult(success = true, token = "C1"),
            source = null, userId = "u1", username = "al", enterpriseId = "E1",
        )
        val u = next.toHttpUrlOrNull()!!
        assertEquals("C1", u.queryParameter("code"))
        assertEquals("getAccessToken", u.encodedPath.trim('/').split('/').last())
        // 石墨段对非石墨 URL 原样
        assertEquals(next, appendShimoQueryIfNeeded(next, "E1", "u1", "sess"))
    }

    @Test
    fun `chain needAuth token makes shimo skip RN quirk`() {
        // needAuth 先拼 token → 石墨段 includes('token') 命中跳过（RN 同序同果）
        val next = buildNeedAuthUrl(
            "https://k.shimo.im/shimo-web/s", ExternalTokenResult(success = true, token = "C1"),
            source = null, userId = "u1", username = "al", enterpriseId = "E1",
        )
        val out = appendShimoQueryIfNeeded(next, "E1", "u1", "sess")
        assertEquals(next, out)
        assertNull(out.toHttpUrlOrNull()?.queryParameter("org"))
    }

    // ── ⑥ resolveInAppWebBackAction（RN resolveInAppWebBackAction.ts:39-78 全分支）──

    private fun back(
        source: String? = null,
        current: String,
        canGoBack: Boolean,
        root: String? = "https://a.com/",
    ) = resolveInAppWebBackAction(source, current, canGoBack, fanwei, root)

    @Test
    fun `chat-gpt source always pops even with history`() {
        assertEquals(InAppWebBackAction.STACK_POP, back(source = "chat-gpt", current = "https://a.com/deep", canGoBack = true))
    }

    @Test
    fun `back close segments pop with history`() {
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/approve/list?id=1", canGoBack = true))
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/x/error", canGoBack = true))
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/404", canGoBack = true))
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/500", canGoBack = true))
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/403", canGoBack = true))
    }

    @Test
    fun `segment substring semantics RN indexOf lock`() {
        // indexOf > 0 全串子串判定：query 内出现段也命中（RN 同）
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/page?next=/error", canGoBack = true))
        // "/terror" 不含 "/error" → 不触发（锁非命中侧）
        assertEquals(InAppWebBackAction.WEBVIEW_BACK, back(current = "https://a.com/terror", canGoBack = true))
    }

    @Test
    fun `canGoBack deep page goes webview back`() {
        assertEquals(InAppWebBackAction.WEBVIEW_BACK, back(current = "https://a.com/list", canGoBack = true))
    }

    @Test
    fun `canGoBack back at root path pops`() {
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/", canGoBack = true))
    }

    @Test
    fun `same path different query counts as root RN comparable`() {
        // comparable 去 query：origin+path 一致即视为回根
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/list?b=2", canGoBack = true, root = "https://a.com/list?a=1"))
    }

    @Test
    fun `hash route base equals root after stripping hash query`() {
        assertEquals(
            InAppWebBackAction.STACK_POP,
            back(current = "https://a.com/#/list?x=1", canGoBack = true, root = "https://a.com/#/list"),
        )
    }

    @Test
    fun `different hash route goes webview back`() {
        assertEquals(
            InAppWebBackAction.WEBVIEW_BACK,
            back(current = "https://ssc.antagent.space/#/search?x=1", canGoBack = true, root = "https://ssc.antagent.space/#/home"),
        )
    }

    @Test
    fun `antagent force pop urls pop exact match only`() {
        assertEquals(
            InAppWebBackAction.STACK_POP,
            back(current = "https://ssc.antagent.space/#/search", canGoBack = true, root = "https://a.com/"),
        )
        assertEquals(
            InAppWebBackAction.STACK_POP,
            back(current = "https://ssc.antagent.space/#/login", canGoBack = true, root = null),
        )
        // 带 query 非精确命中 → 正常 webview 返回
        assertEquals(
            InAppWebBackAction.WEBVIEW_BACK,
            back(current = "https://ssc.antagent.space/#/search?x=1", canGoBack = true, root = "https://a.com/"),
        )
    }

    @Test
    fun `no history always pops fanwei or not`() {
        assertEquals(InAppWebBackAction.STACK_POP, back(current = fanwei, canGoBack = false, root = null))
        assertEquals(InAppWebBackAction.STACK_POP, back(current = "https://a.com/", canGoBack = false, root = null))
    }

    // ── ④ 会议外链匹配（RN meetingUrlPatterns.ts）──

    @Test
    fun `tencent meeting http url detection case insensitive`() {
        assertTrue(isTencentMeetingHttpUrl("https://meeting.tencent.com/dm/r/abc"))
        assertTrue(isTencentMeetingHttpUrl("HTTPS://MEETING.TENCENT.COM/x"))
        assertFalse(isTencentMeetingHttpUrl("http://meeting.tencent.com/x")) // RN 正则仅 https
        assertFalse(isTencentMeetingHttpUrl("https://evil.com/meeting.tencent.com"))
    }

    @Test
    fun `wemeet scheme detection`() {
        assertTrue(isWemeetSchemeUrl("wemeet://join/abc"))
        assertTrue(isWemeetSchemeUrl("WEMEET://x"))
        assertFalse(isWemeetSchemeUrl("https://wemeet.com"))
        assertFalse(isWemeetSchemeUrl("xwemeet://join"))
    }

    // ── ⑦ isVpnReachable（RN vpnReachability.ts：10s 超时判定）──

    @Test
    fun `vpn probe any response means reachable`() = runTest {
        assertTrue(isVpnReachable("u", probe = { _, _ -> true }))
    }

    @Test
    fun `vpn probe failure means unreachable`() = runTest {
        assertFalse(isVpnReachable("u", probe = { _, _ -> false }))
    }

    @Test
    fun `vpn probe beyond timeout is unreachable`() = runTest {
        assertFalse(isVpnReachable("u", timeoutMs = 10_000, probe = { _, _ -> delay(20_000); true }))
    }

    @Test
    fun `vpn probe within timeout is reachable`() = runTest {
        assertTrue(isVpnReachable("u", timeoutMs = 10_000, probe = { _, _ -> delay(5_000); true }))
    }

    // ── ① 泛微 token：响应解析 + 进程内去重（RN webAuth.ts:30-52）──

    @Test
    fun `weaver token parses result data`() {
        val raw = buildJsonObject { putJsonObject("result") { put("data", "tok-9") } }
        assertEquals("tok-9", parseWeaverToken(raw))
        assertEquals("", parseWeaverToken(buildJsonObject { putJsonObject("result") { put("data", "") } }))
        assertEquals("", parseWeaverToken(buildJsonObject { putJsonObject("result") { put("data", JsonNull) } }))
        assertEquals("", parseWeaverToken(buildJsonObject {}))
        assertEquals("", parseWeaverToken(null))
    }

    @Test
    fun `gate dedups concurrent fetches into one`() = runTest {
        var calls = 0
        val gate = FanweiWeaverTokenGate(scope = backgroundScope)
        val a = async { gate.get { calls++; delay(1_000); "T1" } }
        val b = async { gate.get { calls++; delay(1_000); "T1" } }
        assertEquals("T1", a.await())
        assertEquals("T1", b.await())
        assertEquals(1, calls)
    }

    @Test
    fun `gate refetches after previous request settled`() = runTest {
        var calls = 0
        val gate = FanweiWeaverTokenGate(scope = backgroundScope)
        assertEquals("A", gate.get { calls++; "A" })
        assertEquals("B", gate.get { calls++; "B" })
        assertEquals(2, calls)
    }

    @Test
    fun `gate returns empty string on fetch failure`() = runTest {
        val gate = FanweiWeaverTokenGate(scope = backgroundScope)
        assertEquals("", gate.get { error("boom") })
    }

    @Test
    fun `gate failure is silent and next call recovers`() = runTest {
        var fail = true
        val gate = FanweiWeaverTokenGate(scope = backgroundScope)
        assertEquals("", gate.get { if (fail) error("boom") else "OK" })
        fail = false
        assertEquals("OK", gate.get { if (fail) error("boom") else "OK" })
    }

    // ── 注入脚本拼装库存（RN buildInjectedScripts.ts 段序）──

    @Test
    fun `injection scripts carry expected markers and escaped values`() {
        val nav = buildNavigationStateScript()
        assertTrue(nav.contains("history.pushState = wrap(history.pushState)"))
        assertTrue(nav.contains("navigationStateChange"))

        val xhr = buildAndroidFanweiNetworkPatch("t\"1")
        assertTrue(xhr.contains("var tok = \"t\\\"1\";"))
        assertTrue(xhr.contains("setRequestHeader('WEAVERTOKEN', tok);"))
        assertTrue(xhr.contains("originFetch(resource, params)"))

        val hide = buildRecruitmentHideChromeScript()
        assertTrue(hide.contains("recruit-paas-mobile-head-tabs__container-left"))
        assertTrue(hide.contains("setTimeout"))

        val reload = buildFanweiCookieReloadScript("https://m.appia.vip", "tk")
        assertTrue(reload.contains("hasReloaded_' + currentUrl"))
        assertTrue(reload.contains("window.location.reload()"))
    }
}
