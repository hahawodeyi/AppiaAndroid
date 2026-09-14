package cn.appia.im.feature.org

import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.datastore.LoginSwitchCandidate
import cn.appia.im.core.datastore.LoginSwitchCandidatesCache
import cn.appia.im.core.network.RocketSdk
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject

/** waitSdkRestLogin 轮询间隔（RN SDK_REST_LOGIN_POLL_MS）。 */
const val SDK_REST_LOGIN_POLL_MS = 50L

/** waitSdkRestLogin 上限（RN SDK_REST_LOGIN_MAX_WAIT_MS）。 */
const val SDK_REST_LOGIN_MAX_WAIT_MS = 15_000L

private val orgListJson = Json { ignoreUnknownKeys = true }

/**
 * 可切换主体列表（逐行为移植 appiaMobile/src/services/api/company.ts:8-16 +
 * hooks/useLoginSwitchCandidates.ts 的数据面）：
 * - `sdk.get('login.getSwitchCandidate')` → `ILoginSwitchCandidate[]` 等价。T2 裁定：
 *   Kotlin sdk.get 已做 `data ?? resp` 平铺，这里直接读平铺后形态（非数组 → 空列表，RN :13-14 同）。
 * - 缓存 key `login-switch-candidates-${username}`（不含 token），登录成功时写、登出时清。
 */
class OrgListRepository @Inject constructor(
    private val sdk: RocketSdk,
    kv: KvStore,
) {

    private val cache = LoginSwitchCandidatesCache(kv)

    /**
     * RN waitSdkRestLogin（lib/auth/waitSdkRestLogin.ts:23-40）：等待 REST 会话写入后再发请求——
     * 冷启动时持久化恢复早于 `sdk.current`，直接 sdk.get 会抛 Not logged in。
     * 判定信号：`sdk.currentAuthToken() == token`（RN `sdk.current?.authToken === token`）。
     * @return 是否在 maxWaitMs 内就绪（超时/取消返回 false，调用方放弃本次拉取，RN :41-43 同）。
     */
    suspend fun waitSdkRestLogin(
        token: String,
        isCancelled: () -> Boolean = { false },
        pollMs: Long = SDK_REST_LOGIN_POLL_MS,
        maxWaitMs: Long = SDK_REST_LOGIN_MAX_WAIT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (!isCancelled() && System.currentTimeMillis() < deadline) {
            if (sdk.currentAuthToken() == token) return true
            delay(pollMs)
        }
        return false
    }

    /** RN getLoginSwitchCandidates company.ts:9-16；单条字段缺失/多余均容忍（ignoreUnknownKeys + 缺省空串）。 */
    suspend fun fetchCandidates(): List<LoginSwitchCandidate> {
        val data = sdk.get("login.getSwitchCandidate")
        if (data !is JsonArray) return emptyList()
        return runCatching {
            orgListJson.decodeFromJsonElement(ListSerializer(LoginSwitchCandidate.serializer()), data)
        }.getOrDefault(emptyList())
    }

    /** RN readLoginSwitchCandidatesCache：serverUrl 给定时校验列表含当前主体，过期返回 null。 */
    fun readCache(username: String, serverUrl: String? = null): List<LoginSwitchCandidate>? =
        cache.read(username, serverUrl)

    /** RN writeLoginSwitchCandidatesCache：登录成功时写（useLoginSwitchCandidates.ts:46-49）。 */
    fun writeCache(username: String, data: List<LoginSwitchCandidate>) = cache.write(username, data)

    /** RN clearLoginSwitchCandidatesCache：登出时清（authStore.ts:144-146）。 */
    fun clearCache(username: String) = cache.clear(username)
}
