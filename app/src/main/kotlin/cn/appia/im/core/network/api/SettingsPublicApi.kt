package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.settings.ServerSettingRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `GET settings.public` 分批同步（RN services/settings/syncPublicSettingsFromRegistry.ts 逐行）。
 *
 * 单批不超过 [CHUNK_SIZE]（RN 注释：单次请求不宜过大）；query `{_id: {$in: [batch]}}` 经
 * REST GET 序列化为 `query=<urlencoded JSON>&count=<batch size>`（RN encodeQuery 对象 → JSON.stringify）。
 * 单条 prepare 失败跳过（warn）；单批网络失败仅 warn 继续下一批（RN chunk failed, continuing）。
 */
object SettingsPublicApi {

    /** RN PUBLIC_SETTINGS_REST_CHUNK_SIZE = 50。 */
    const val CHUNK_SIZE = 50

    /** RN chunkIds。 */
    fun <T> chunkIds(items: List<T>, size: Int = CHUNK_SIZE): List<List<T>> =
        items.chunked(size)

    /**
     * 按注册表分批拉取并写入 `settings` 表（设置行 id = Rocket.Chat `_id`）。
     * @return 实际写入行数（测试观测；RN 无返回值）。
     */
    suspend fun syncPublicSettings(
        sdk: RocketSdk,
        upsertAll: suspend (List<cn.appia.im.core.database.entity.SettingEntity>) -> Unit,
    ): Int {
        var written = 0
        for (chunk in chunkIds(ServerSettingRegistry.ids)) {
            try {
                val raw = sdk.get(
                    "settings.public",
                    mapOf(
                        "query" to """{"_id":{"${'$'}in":${JsonArray(chunk.map { JsonPrimitive(it) }).toString()}}}""",
                        "count" to chunk.size.toString(),
                    ),
                )
                val settings = (raw as? JsonObject)?.get("settings") as? JsonArray ?: continue
                val prepared = settings.mapNotNull { s ->
                    val obj = s as? JsonObject ?: return@mapNotNull null
                    val id = (obj["_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return@mapNotNull null
                    try {
                        ServerSettingRegistry.prepareSettingEntity(id, obj["value"]).also {
                            if (it == null) android.util.Log.w(
                                "settings",
                                "[syncPublicSettings] prepare skipped unregistered id=$id",
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // RN：单条 prepare 失败跳过不阻断整批
                        android.util.Log.w("settings", "[syncPublicSettings] prepare failed for $id", e)
                        null
                    }
                }
                if (prepared.isNotEmpty()) {
                    upsertAll(prepared)
                    written += prepared.size
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("settings", "[syncPublicSettings] chunk failed, continuing...", e)
            }
        }
        return written
    }
}
