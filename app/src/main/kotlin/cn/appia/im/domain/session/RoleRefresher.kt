package cn.appia.im.domain.session

import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.parseUserRoles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局角色运行时刷新，对照 RN services/auth/syncCurrentUserRoles.ts：
 * users.info 自查 → parseUserRoles → AuthSessionStore.mergeUserRoles（整体替换 roles）。
 * 30s 节流（MIN_INTERVAL_MS）+ inflight 去重 + force 绕过节流；mutex 串行化 check-and-set 与
 * await（等价 JS 单线程时序）。触发点：bootstrap extras force / ON_RESUME / 装配层 focus。
 */
@Singleton
class RoleRefresher @Inject constructor(
    private val sdk: RocketSdk,
    private val store: AuthSessionStore,
    @BackgroundScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var lastSyncAt = 0L
    private var inflight: Deferred<Unit>? = null

    /** RN syncCurrentUserRoles.ts:17-51 同构。失败静默吞（RN `.catch(() => undefined)`）。 */
    suspend fun refresh(force: Boolean = false) {
        val userId = store.load()?.user?.id ?: return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastSyncAt < MIN_INTERVAL_MS) {
                inflight?.let { runCatching { it.await() } }
                return
            }
            if (inflight != null) {
                runCatching { inflight!!.await() }
                if (!force && System.currentTimeMillis() - lastSyncAt < MIN_INTERVAL_MS) return
            }
            lastSyncAt = now
            val job = scope.async { fetchRoles(userId) }
            inflight = job
            try {
                runCatching { job.await() }
            } finally {
                if (inflight === job) inflight = null
            }
        }
    }

    private suspend fun fetchRoles(userId: String) {
        try {
            val raw = sdk.get("users.info", mapOf("userId" to userId))
            val roles = parseUserRoles(((raw as? JsonObject)?.get("user") as? JsonObject)?.get("roles"))
            store.mergeUserRoles(userId, roles)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // RN syncCurrentUserRoles.ts:43-44 空 catch：失败不打扰用户，下次触发再试
        }
    }

    private companion object {
        /** RN syncCurrentUserRoles.ts:5。 */
        const val MIN_INTERVAL_MS = 30_000L
    }
}
