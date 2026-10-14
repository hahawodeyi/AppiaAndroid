package cn.appia.im.core.voice

/**
 * voiceTokenCache.ts:21 voice_external_token_failed（fetchToken 返回 null/blank）。
 */
class VoiceExternalTokenException : Exception("voice_external_token_failed")

/**
 * oncall 业务 token 缓存（逐行为移植 appiaMobile/src/services/voice/voiceTokenCache.ts）：
 * - 2 天 TTL（:3 CACHE_TTL_MS）
 * - key=(userId, authToken) 变化即失效（:35-45）
 * - 失败不缓存（fetchToken null/blank → 抛 [VoiceExternalTokenException]，cached 不动）
 * - [invalidate] 供 Controller 在 OncallTokenExpiredException 后调用
 *
 * fetchToken 生产装配在 Task 12（RocketSdk users.externalToken 通道）。
 */
class VoiceTokenCache(
    private val fetchToken: suspend (serverUrl: String, userId: String, authToken: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cached: CachedToken? = null

    private data class CachedToken(
        val token: String,
        val expiry: Long,
        val userId: String,
        val authToken: String,
    )

    /** voiceTokenCache.ts:30-47 getToken：命中条件 = key 相同且 now < expiry（严格小于，RN 同构）。 */
    suspend fun getToken(userId: String, authToken: String, serverUrl: String): String {
        val hit = cached
        if (hit != null && hit.userId == userId && hit.authToken == authToken && clock() < hit.expiry) {
            return hit.token
        }
        val token = fetchToken(serverUrl, userId, authToken)?.trim()
        if (token.isNullOrEmpty()) throw VoiceExternalTokenException()
        cached = CachedToken(token = token, expiry = clock() + CACHE_TTL_MS, userId = userId, authToken = authToken)
        return token
    }

    /** voiceTokenCache.ts:48-50 invalidate。 */
    fun invalidate() {
        cached = null
    }

    companion object {
        /** voiceTokenCache.ts:3：2 天。 */
        const val CACHE_TTL_MS: Long = 2 * 24 * 60 * 60 * 1000L
    }
}
