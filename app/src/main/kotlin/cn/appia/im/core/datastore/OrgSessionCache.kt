package cn.appia.im.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 组织会话行（RN services/api/orgSessionByHost.ts:6-11）；字段缺省化等价 JS undefined → falsy。 */
@Serializable
data class OrgSessionCacheRow(
    val token: String = "",
    val userId: String = "",
    val username: String = "",
    val name: String? = null,
)

/** RN normalizeOrgSessionHost（orgSessionByHost.ts:13）：只去一个尾斜杠。 */
fun normalizeOrgSessionHost(serverUrl: String): String = serverUrl.removeSuffix("/")

/** RN 单 key（orgSessionByHost.ts:4）。 */
private const val KEY = "sessions-v1"

private val cacheJson = Json { ignoreUnknownKeys = true }

/**
 * 按主机缓存各组织登录会话（MMKV 实例 `org-session-by-host`）——
 * 逐行为移植 appiaMobile/src/services/api/orgSessionByHost.ts:1-57：
 * 单 key `sessions-v1` 存 JSON `Record<host, row>`；get 对 token/userId 缺失的行返回 null（RN :36）；
 * clearAll 只移除该 key（RN :54-56）；坏 JSON 读作空表（RN :17-26）。
 */
class OrgSessionCache(private val kv: KvStore) {

    fun get(host: String): OrgSessionCacheRow? {
        val row = readMap()[normalizeOrgSessionHost(host)] ?: return null
        return row.takeIf { it.token.isNotEmpty() && it.userId.isNotEmpty() }
    }

    fun set(host: String, row: OrgSessionCacheRow) {
        val map = readMap()
        map[normalizeOrgSessionHost(host)] = row
        writeMap(map)
    }

    fun clear(host: String) {
        val map = readMap()
        map.remove(normalizeOrgSessionHost(host))
        writeMap(map)
    }

    fun clearAll() {
        kv.remove(KEY)
    }

    /** RN readMap :17-26：无值/解析失败 → 空表（下次 set 即自愈）。 */
    private fun readMap(): MutableMap<String, OrgSessionCacheRow> {
        val raw = kv.getString(KEY, "")
        if (raw.isEmpty()) return mutableMapOf()
        return runCatching { cacheJson.decodeFromString<Map<String, OrgSessionCacheRow>>(raw) }
            .getOrNull()
            ?.toMutableMap()
            ?: mutableMapOf()
    }

    private fun writeMap(map: Map<String, OrgSessionCacheRow>) {
        kv.putString(KEY, cacheJson.encodeToString(map))
    }
}
