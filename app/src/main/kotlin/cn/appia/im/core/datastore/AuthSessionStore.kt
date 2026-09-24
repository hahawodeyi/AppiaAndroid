package cn.appia.im.core.datastore

import cn.appia.im.core.network.AuthUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * 当前用户名热路径缓存（M4-T10 fix Minor-2）：RoomStreamManager 每帧 hint 记录读取，
     * 免逐帧 KV 读 + 全 session JSON 反序列化（DDP IO 线程）。save/clear 即失效点
     * （登录/组织切换 applySession 汇入 login→save，登出→clear，M5-T2 mergeUserRoles 亦经 save——
     * 无绕过 save 的写路径）；
     * 首帧惰性回填：进程重启恢复（只 load 不 save）后首读现读一次。@Volatile：主线程写、IO 线程读。
     */
    @Volatile
    private var cachedUsername: String? = null

    /**
     * 全局角色响应式读（M5-T2：RoomInfo/RoomMembers 等装配层 collectAsState）。
     * 初始值取持久化会话（进程重启恢复等价 RN rehydrate 后 store 内 roles）。
     */
    private val rolesState = MutableStateFlow(load()?.user?.roles.orEmpty())

    /** 角色流：save/clear/mergeUserRoles 三条写路径均汇入，UI collect 自动刷新。 */
    val roles: StateFlow<List<String>> get() = rolesState

    /** RN login authStore.ts:98-99：三字段整体覆盖写入（无增量合并）。 */
    fun save(session: AuthSession) {
        kv.putString(KEY, sessionJson.encodeToString(AuthSession.serializer(), session))
        cachedUsername = session.user.username
        rolesState.value = session.user.roles.orEmpty()
    }

    /**
     * RN mergeUserRoles authStore.ts:123-127（语义是整体替换 roles 数组，非并集）+ userId 防串写加固：
     * 组织切换/登出后在途响应不得把旧用户角色写到新会话（RN 无此守卫）。未登录静默忽略。
     */
    fun mergeUserRoles(userId: String, roles: List<String>) {
        val session = load() ?: return
        if (session.user.id != userId) return
        save(session.copy(user = session.user.copy(roles = roles.ifEmpty { null })))
    }

    /**
     * RN merge/rehydrate authStore.ts:182-189：空存储/JSON 损坏/缺字段（含历史 user:null）→ null（未登录）。
     */
    fun load(): AuthSession? =
        kv.getString(KEY, "").takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { sessionJson.decodeFromString(AuthSession.serializer(), raw) }.getOrNull()
        }

    /**
     * 每帧用户名读（RoomStreamManager currentUsernameProvider 缝；RN 每帧
     * useAuthStore.getState().user?.username 的内存读等价）。未登录返回 null。
     */
    val currentUsername: String?
        get() = cachedUsername ?: load()?.user?.username?.also { cachedUsername = it }

    /** RN logout authStore.ts:157：三字段一并清空。 */
    fun clear() {
        kv.remove(KEY)
        cachedUsername = null
        rolesState.value = emptyList()
    }

    /** RN `Boolean(token && user && serverUrl)`（authStore.ts:187）：空串视为缺失。 */
    val isAuthenticated: Boolean
        get() = load()?.let { it.token.isNotEmpty() && it.serverUrl.isNotEmpty() } == true
}
