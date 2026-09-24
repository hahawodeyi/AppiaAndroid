package cn.appia.im.domain.presence

import android.util.Log
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * presence 批量请求器（RN src/services/presence/batchRequestPresence.ts 逐行移植）：
 * - requestUserPresence：pending 去重集合 + 2s 防抖合并
 * - flush：`GET users.presence {ids: 逗号串}` → 请求批次全量落 store（响应缺失条目 OFFLINE）；
 *   未订阅 id 增量 `subscribeRaw('stream-user-presence', ['', {added: newIds}])` ——
 *   **added 数组形态**（非 eventName 形态）。Android DdpClient.subscribeRaw params 原样透传
 *   （与 RN ddpClient.subscribeRaw 同构），无需扩 DdpClient——M3 sdk.subscribeRoom 的
 *   eventName 形态走 ddp.subscribe 包装层，两者并存不冲突
 * - 静默失败（RN catch 同义：不阻断 UI）；订阅失败不标记（下一批重试）
 *
 * JS setTimeout → scope 协程 delay；sdk 由 [attach] 注入进程级单例（SessionModule 收口）。
 * 未 attach 时全部 no-op（M1 管理器测试先行 bootstrap 不受影响）。
 */
object PresenceBatcher {

    private const val TAG = "presence"
    private const val DEBOUNCE_MS = 2000L

    @Volatile
    private var sdk: RocketSdk? = null

    private var scope: CoroutineScope? = null
    private var debounceJob: Job? = null
    private val pendingIds = LinkedHashSet<String>()
    private val subscribedIds = HashSet<String>()
    private val lock = Any()

    /** 装配注入（SessionModule 提供管理器时一并收口）。 */
    fun attach(sdk: RocketSdk?, scope: CoroutineScope) {
        synchronized(lock) {
            this.sdk = sdk
            this.scope = scope
        }
    }

    /** RN requestUserPresence：空 id 忽略；防抖窗内重复请求重置计时。 */
    fun requestUserPresence(userId: String) {
        if (userId.isEmpty()) return
        val s = scope ?: return
        synchronized(lock) {
            pendingIds.add(userId)
            debounceJob?.cancel()
            debounceJob = s.launch { flush() }
        }
    }

    /** RN flush 主体（计时到点触发；测试经 [flushNow] 直取跳过防抖）。 */
    private suspend fun flush() {
        kotlinx.coroutines.delay(DEBOUNCE_MS)
        runFlush()
    }

    internal suspend fun runFlush() {
        val ids: List<String> = synchronized(lock) {
            if (pendingIds.isEmpty()) return
            pendingIds.toList().also { pendingIds.clear() }
        }
        val currentSdk = sdk ?: return
        try {
            val result = currentSdk.get("users.presence", mapOf("ids" to ids.joinToString(",")))
            if ((result as? JsonObject)?.get("success")?.jsonPrimitive?.booleanOrNull == true) {
                val users = (result["users"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val batch = ids.associateWith { id ->
                    val user = users.firstOrNull { it.str("_id") == id }
                    PresenceStore.ActiveUserEntry(
                        status = mapContactStatusToTUserStatus(user?.str("status")) ?: TUserStatus.OFFLINE,
                        statusText = user?.str("statusText"),
                    )
                }
                PresenceStore.mergeActiveUsers(batch)
            }

            val newIds = ids.filter { synchronized(lock) { !subscribedIds.contains(it) } }
            if (newIds.isNotEmpty()) {
                // RN :47 sdk.subscribeRaw('stream-user-presence', ['', { added: newIds }])
                val ddp = currentSdk.ddp ?: return // 断连（未 initialize）跳过订阅；未标记 → 下批重试
                ddp.subscribeRaw(
                    "stream-user-presence",
                    listOf(
                        JsonPrimitive(""),
                        buildJsonObject { put("added", JsonArray(newIds.map { JsonPrimitive(it) })) },
                    ),
                )
                synchronized(lock) { subscribedIds.addAll(newIds) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "presence flush failed (silent)", e) // RN 静默失败同义
        }
    }

    /** 登出/切主体清空（SessionModule teardown 挂点）。 */
    fun reset() {
        synchronized(lock) {
            debounceJob?.cancel()
            pendingIds.clear()
            subscribedIds.clear()
            debounceJob = null
        }
        PresenceStore.clear() // RN authStore logout :153
    }

    // ---- 测试观测 ----

    internal fun subscribedIdsForTest(): Set<String> = synchronized(lock) { subscribedIds.toSet() }

    internal fun pendingIdsForTest(): Set<String> = synchronized(lock) { pendingIds.toSet() }

    /** 测试直取 flush（跳过防抖等待）。 */
    internal suspend fun flushNow() = runFlush()

    private fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
}
