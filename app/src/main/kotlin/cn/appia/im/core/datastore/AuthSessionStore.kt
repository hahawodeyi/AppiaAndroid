package cn.appia.im.core.datastore

import cn.appia.im.core.network.AuthUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * 持久化登录会话（RN stores/authStore.ts:41-45 PersistedAuthState 三字段）。
 * 与 core.network.rest.AuthSession（REST 在途 token/userId）不同：这里是持久化态，含 user/serverUrl。
 */
@Serializable
data class AuthSession(
    val token: String,
    val user: AuthUser,
    val serverUrl: String,
)

/** RN persist name（authStore.ts:172）。 */
private const val KEY = "auth-storage"

private val sessionJson = Json { ignoreUnknownKeys = true }

/**
 * 登录会话持久化（MMKV 默认实例），等价 RN zustand persist `auth-storage`：
 * - JSON `{token, user, serverUrl}` 三字段（partialize authStore.ts:174-178）
 * - `isAuthenticated = token && user && serverUrl 三者齐全`（authStore.ts:73/187，不 ping 服务器）
 * - MMKV 同步读写、无 async hydrate（RN merge authStore.ts:182-189 同步算出 isAuthenticated 的等价）
 */
class AuthSessionStore @Inject constructor(private val kv: KvStore) {

    /** RN login authStore.ts:98-99：三字段整体覆盖写入（无增量合并）。 */
    fun save(session: AuthSession) {
        kv.putString(KEY, sessionJson.encodeToString(AuthSession.serializer(), session))
    }

    /**
     * RN merge/rehydrate authStore.ts:182-189：空存储/JSON 损坏/缺字段（含历史 user:null）→ null（未登录）。
     */
    fun load(): AuthSession? =
        kv.getString(KEY, "").takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { sessionJson.decodeFromString(AuthSession.serializer(), raw) }.getOrNull()
        }

    /** RN logout authStore.ts:157：三字段一并清空。 */
    fun clear() {
        kv.remove(KEY)
    }

    /** RN `Boolean(token && user && serverUrl)`（authStore.ts:187）：空串视为缺失。 */
    val isAuthenticated: Boolean
        get() = load()?.let { it.token.isNotEmpty() && it.serverUrl.isNotEmpty() } == true
}
