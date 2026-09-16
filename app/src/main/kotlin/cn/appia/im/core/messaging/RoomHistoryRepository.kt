package cn.appia.im.core.messaging

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.rest.ApiException
import cn.appia.im.core.network.rest.AuthSessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Rocket.Chat REST：`channels.history` / `im.history` / `groups.history` 的路径前缀。
 * 逐行移植 appiaMobile/src/lib/chat/roomTypeToRestPrefix.ts:5-17（对齐旧版 ios roomTypeToApiType）。
 */
fun roomTypeToRestPrefix(t: String): String? = when (t) {
    "c", "l" -> "channels"
    "d" -> "im"
    "p" -> "groups"
    else -> null
}

/**
 * 房间历史拉取与落库（逐行为移植 appiaMobile/src/lib/chat/loadRoomHistory.ts + services/api/rooms.ts:12-22）：
 * `GET /api/v1/{prefix}.history`，参数 `roomId` + `count` + `latest`（本地最旧一条 ts 的 ISO 串——
 * 用 `latest` 不用 `oldest`）；瞬时失败重试 2 次退避 1s/2s（RN fetchRoomHistoryWithRetry :16-32 +
 * retryPolicy.isRetryableError）；消息经 [MessageUpsert.persist] upsert 落库（refresh 路径靠其去重）。
 */
class RoomHistoryRepository(
    private val sdk: RocketSdk,
    private val db: AppiaDatabase,
    /** 退避睡眠缝（测试注入免真实等待；生产 = delay）。 */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * 拉取房间最近历史并落库。
     * @return 本次 API 返回的消息条数；**失败返回 null**——调用方（loadEarlier）对失败不得置
     * hasMoreEarlier=false（派单裁定 1：失败 ≠ 服务端确凿无更多，refresh/loadEarlier 可重试）；
     * 服务端确认空、或不支持房间类型（不调网）返回 0。
     */
    suspend fun loadRoomHistory(
        rid: String,
        roomType: String,
        latest: String? = null,
        count: Int = DEFAULT_COUNT,
    ): Int? {
        val prefix = roomTypeToRestPrefix(roomType) ?: return 0 // RN :46-49 未知 t 返回 0 不调网
        var attempt = 0
        while (true) {
            try {
                val params = buildMap {
                    put("roomId", rid)
                    put("count", count.toString())
                    if (!latest.isNullOrEmpty()) put("latest", latest) // RN rooms.ts:18 if (latest)
                }
                val res = sdk.get("$prefix.history", params)
                val list = (res as? JsonObject)?.get("messages") as? JsonArray
                    ?: JsonArray(emptyList())
                if (list.isEmpty()) return 0
                MessageUpsert.persist(db, list, rid)
                return list.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isRetryableError(e) || attempt >= RETRY_MAX_ATTEMPTS) return null
                sleep((1L shl attempt) * 1_000) // RN :28 退避 1s/2s
                attempt += 1
            }
        }
    }

    companion object {
        const val DEFAULT_COUNT = 50

        /** RN HISTORY_RETRY_MAX_ATTEMPTS:12：含首次共 3 次尝试。 */
        const val RETRY_MAX_ATTEMPTS = 2

        /**
         * RN retryPolicy.isRetryableError：HTTP ≥500 重试、4xx 业务错不重试、网络/超时
         * （IOException 族）重试；401 会话失效（AuthSessionExpiredException）不重试——
         * 登出链自有处理，重试只会再次 401。
         */
        internal fun isRetryableError(e: Throwable): Boolean = when {
            e is ApiException -> (e.status ?: 0) >= 500
            e is IOException && e !is AuthSessionExpiredException -> true
            else -> false
        }
    }
}
