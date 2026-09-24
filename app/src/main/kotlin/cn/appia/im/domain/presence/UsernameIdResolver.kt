package cn.appia.im.domain.presence

import android.util.Log
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * username → Rocket.Chat userId 解析器（RN src/services/presence/resolvePresenceUserIdByUsername.ts
 * 逐行移植）：
 * - scheduleResolve：400ms 防抖合并 → `GET users.info {userId: username}`；返回 _id 与
 *   username 相同（HRM key 形态）或缺失时丢弃
 * - cacheRcUserId：users.info 已确认 (username, _id) 的调用方回写（RN cachePresenceRcUserId
 *   ——MemberProfile 拉到 profile 后回写先例）；经 [isRocketChatUserId] 守卫，命中即触发
 *   presence 拉取 + 版本号 bump
 * - 解析结果读取：[resolvedRcUserId]（配 [resolvedVersion] 订阅 bump；RN useSyncExternalStore
 *   + listeners 的 StateFlow 等价）
 * - 静默失败（RN 同：解析不到仅依赖通讯录 fallback）
 */
object UsernameIdResolver {

    private const val TAG = "presence"
    private const val RESOLVE_DEBOUNCE_MS = 400L

    @Volatile
    private var sdk: RocketSdk? = null

    private var scope: CoroutineScope? = null
    private var resolveJob: Job? = null
    private val pendingUsernames = LinkedHashSet<String>()
    private val usernameToRcUserId = HashMap<String, String>()
    private val lock = Any()

    /** 解析缓存版本号（写后 bump；UI 订阅驱动重读 resolvedRcUserId）。 */
    private val _resolvedVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val resolvedVersion: kotlinx.coroutines.flow.StateFlow<Int> = _resolvedVersion

    /** 装配注入（与 PresenceBatcher.attach 同点）。 */
    fun attach(sdk: RocketSdk?, scope: CoroutineScope) {
        synchronized(lock) {
            this.sdk = sdk
            this.scope = scope
        }
    }

    /** RN getResolvedRcUserId。 */
    fun resolvedRcUserId(username: String?): String? {
        val key = username?.trim().orEmpty()
        return if (key.isEmpty()) null else synchronized(lock) { usernameToRcUserId[key] }
    }

    /** RN cachePresenceRcUserId：守卫 → 回写 → 触发 presence 拉取 → bump。已同值幂等跳过。 */
    fun cacheRcUserId(username: String, rcUserId: String) {
        val name = username.trim()
        val id = rcUserId.trim()
        if (name.isEmpty() || id.isEmpty() || !isRocketChatUserId(id, name)) return
        synchronized(lock) {
            if (usernameToRcUserId[name] == id) return
            usernameToRcUserId[name] = id
        }
        PresenceBatcher.requestUserPresence(id)
        _resolvedVersion.value += 1
    }

    /** RN schedulePresenceUserIdResolve：已缓存/空跳过；防抖窗内重复 schedule 重置计时。 */
    fun scheduleResolve(username: String?) {
        val key = username?.trim().orEmpty()
        if (key.isEmpty()) return
        val s = scope ?: return
        synchronized(lock) {
            if (usernameToRcUserId.containsKey(key)) return
            pendingUsernames.add(key)
            resolveJob?.cancel()
            resolveJob = s.launch { delay(RESOLVE_DEBOUNCE_MS); runResolve() }
        }
    }

    private suspend fun runResolve() {
        val usernames: List<String> = synchronized(lock) {
            if (pendingUsernames.isEmpty()) return
            pendingUsernames.toList().also { pendingUsernames.clear() }
        }
        val currentSdk = sdk ?: return
        for (username in usernames) {
            try {
                val result = currentSdk.get("users.info", mapOf("userId" to username))
                val rcId = ((result as? JsonObject)?.get("user") as? JsonObject)
                    ?.let { (it["_id"] as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
                if (rcId.isEmpty() || rcId == username) continue
                synchronized(lock) { usernameToRcUserId[username] = rcId }
                PresenceBatcher.requestUserPresence(rcId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resolve presence id failed for $username (silent)", e)
            }
        }
        _resolvedVersion.value += 1
    }

    /** 登出清空（SessionModule teardown 挂点，与 PresenceBatcher.reset 同位）。 */
    fun reset() {
        synchronized(lock) {
            resolveJob?.cancel()
            pendingUsernames.clear()
            usernameToRcUserId.clear()
            resolveJob = null
        }
    }

    // ---- 测试观测 ----

    internal fun cachedForTest(): Map<String, String> = synchronized(lock) { usernameToRcUserId.toMap() }

    internal fun pendingForTest(): Set<String> = synchronized(lock) { pendingUsernames.toSet() }

    /** 测试直取 resolve（跳过防抖等待）。 */
    internal suspend fun resolveNow() = runResolve()
}
