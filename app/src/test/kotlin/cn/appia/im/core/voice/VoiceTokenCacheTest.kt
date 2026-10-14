package cn.appia.im.core.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoiceTokenCache（RN voiceTokenCache.test.ts 全量移植：
 * TTL 内缓存命中 / key 变化重取 / 失败不缓存 / invalidate 后重取 / 成功字段省略容忍 /
 * TTL 过期重取——brief 追加 clock 注入用例）。RN 测试名以注释保留。
 */
class VoiceTokenCacheTest {

    /** RN fetchExternalTokenForVoice 等价注入：计数 + 可编程结果。 */
    private class FakeFetcher {
        var calls = 0
        var result: String? = "biz-token-1"
        var lastServerUrl: String? = null
        var lastUserId: String? = null
        var lastAuthToken: String? = null

        val fetch: suspend (String, String, String) -> String? = { serverUrl, userId, authToken ->
            calls++
            lastServerUrl = serverUrl
            lastUserId = userId
            lastAuthToken = authToken
            result
        }
    }

    private fun cache(fake: FakeFetcher, now: () -> Long) = VoiceTokenCache(fake.fetch, clock = now)

    // RN: fetches token on first getToken
    @Test
    fun `fetches token on first getToken and passes params through`() = runBlocking {
        val fake = FakeFetcher()
        val cache = cache(fake) { 1_000L }
        assertEquals("biz-token-1", cache.getToken(userId = "user-1", authToken = "auth-1", serverUrl = "https://chat.example.com"))
        assertEquals(1, fake.calls)
        assertEquals("https://chat.example.com", fake.lastServerUrl)
        assertEquals("user-1", fake.lastUserId)
        assertEquals("auth-1", fake.lastAuthToken)
    }

    // RN: reuses cache for same userId and authToken（2 天 TTL 内）
    @Test
    fun `reuses cache for same userId and authToken within TTL`() = runBlocking {
        var now = 1_000L
        val fake = FakeFetcher()
        val cache = cache(fake) { now }
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        now += 2 * 24 * 60 * 60 * 1000 - 1 // TTL 边界内（RN Date.now() < expiry 严格小于）
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        assertEquals(1, fake.calls)
    }

    // RN: refetches when userId changes（key=(userId,authToken) 变化即失效）
    @Test
    fun `refetches when userId changes`() = runBlocking {
        val fake = FakeFetcher()
        val cache = cache(fake) { 1_000L }
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        fake.result = "biz-token-2"
        assertEquals("biz-token-2", cache.getToken("user-2", "auth-1", "https://chat.example.com"))
        assertEquals(2, fake.calls)
    }

    // RN key 变化第二维：authToken 变化重取
    @Test
    fun `refetches when authToken changes`() = runBlocking {
        val fake = FakeFetcher()
        val cache = cache(fake) { 1_000L }
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        fake.result = "biz-token-3"
        assertEquals("biz-token-3", cache.getToken("user-1", "auth-2", "https://chat.example.com"))
        assertEquals(2, fake.calls)
    }

    // RN: refetches after invalidate
    @Test
    fun `refetches after invalidate`() = runBlocking {
        val fake = FakeFetcher()
        val cache = cache(fake) { 1_000L }
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        cache.invalidate()
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        assertEquals(2, fake.calls)
    }

    // brief：TTL 过期重取（clock 注入）
    @Test
    fun `refetches after TTL expiry`() = runBlocking {
        var now = 1_000L
        val fake = FakeFetcher()
        val cache = cache(fake) { now }
        cache.getToken("user-1", "auth-1", "https://chat.example.com")
        fake.result = "biz-token-fresh"
        now += 2 * 24 * 60 * 60 * 1000 // 恰过期（expiry = fetchNow + TTL）
        assertEquals("biz-token-fresh", cache.getToken("user-1", "auth-1", "https://chat.example.com"))
        assertEquals(2, fake.calls)
    }

    // RN: throws when external token request fails（AA 收窄：fetchToken null/blank → 抛，不缓存）
    @Test
    fun `throws and does not cache when fetchToken returns null`() = runBlocking {
        val fake = FakeFetcher().apply { result = null }
        val cache = cache(fake) { 1_000L }
        val err = runCatching { cache.getToken("user-1", "auth-1", "https://chat.example.com") }.exceptionOrNull()
        assertTrue(err is VoiceExternalTokenException)
        assertEquals("voice_external_token_failed", err!!.message)
        // 失败不缓存：下次重取
        fake.result = "biz-token-1"
        assertEquals("biz-token-1", cache.getToken("user-1", "auth-1", "https://chat.example.com"))
        assertEquals(2, fake.calls)
    }

    // brief：blank token 同 null 处理（RN token?.trim() 空判）
    @Test
    fun `throws on blank token`() = runBlocking {
        val fake = FakeFetcher().apply { result = "   " }
        val cache = cache(fake) { 1_000L }
        val err = runCatching { cache.getToken("user-1", "auth-1", "https://chat.example.com") }.exceptionOrNull()
        assertTrue(err is VoiceExternalTokenException)
    }
}
