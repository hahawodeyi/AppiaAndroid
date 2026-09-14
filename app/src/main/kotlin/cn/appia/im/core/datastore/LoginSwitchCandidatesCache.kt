package cn.appia.im.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** RN types/company.ts `ILoginSwitchCandidate`（login.getSwitchCandidate 返回项，字段名逐一对齐）。 */
@Serializable
data class LoginSwitchCandidate(
    val userId: String = "",
    val appiaUrl: String = "",
    val phone: String = "",
    val companyName: String = "",
    val companyNameCn: String = "",
    val companyLogo: String = "",
)

/**
 * 可切换主体列表缓存（逐行为移植 appiaMobile/src/lib/auth/loginSwitchCandidatesCache.ts）：
 * MMKV 默认实例 key `login-switch-candidates-${username}`，存 JSON 数组——**不含 token**。
 * read 可选 serverUrl 校验：列表中须存在 appiaUrl（规范化后）等于当前主体，否则视为过期返回 null。
 */
class LoginSwitchCandidatesCache(private val kv: KvStore) {

    /** RN loginSwitchCandidatesCache.ts:7-8 的 key 形态。 */
    fun key(username: String): String = "login-switch-candidates-$username"

    /**
     * RN readLoginSwitchCandidatesCache :15-32：无值/非数组/（给了 serverUrl 时）不含当前主体 → null。
     * RN normalizeServerUrl 只去**一个**尾斜杠（replace(/\/$/, '')）。
     */
    fun read(username: String, serverUrl: String? = null): List<LoginSwitchCandidate>? {
        val raw = kv.getString(key(username), "")
        if (raw.isEmpty()) return null
        val list = runCatching { json.decodeFromString(ListSerializer(LoginSwitchCandidate.serializer()), raw) }
            .getOrNull()
            ?: return null
        if (serverUrl != null) {
            val current = normalizeServerUrl(serverUrl)
            if (list.none { normalizeServerUrl(it.appiaUrl) == current }) return null
        }
        return list
    }

    /** RN writeLoginSwitchCandidatesCache :34-42：序列化写库，失败吞（与 RN 同，缓存永不阻断主流程）。 */
    fun write(username: String, data: List<LoginSwitchCandidate>) {
        runCatching { kv.putString(key(username), json.encodeToString(ListSerializer(LoginSwitchCandidate.serializer()), data)) }
    }

    /** RN clearLoginSwitchCandidatesCache :44-50：空 username 不动 key。 */
    fun clear(username: String) {
        if (username.isEmpty()) return
        kv.remove(key(username))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** RN normalizeServerUrl :10：只去一个尾斜杠。 */
        private fun normalizeServerUrl(url: String): String = url.removeSuffix("/")
    }
}
