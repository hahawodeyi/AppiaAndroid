package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 会话列表快捷操作三端点（逐字对照 appiaMobile `src/services/api/subscriptions.ts`）。
 * 只发请求不做本地写：REST 与本地 DB 的先后顺序归调用方（RN readMessages / subscriptionSwipeActions 同分工）。
 * 非 2xx 由 AuthInterceptor 抛 ApiException（RN restClient.ts:76-91 同语义）——调用方据此实现
 * 「服务端成功才写本地」。
 */
object SubscriptionsApi {

    /** `POST subscriptions.read`，body `{rid}`（RN subscriptions.ts:4-5）。 */
    suspend fun postSubscriptionsRead(sdk: RocketSdk, rid: String) {
        sdk.post("subscriptions.read", buildJsonObject { put("rid", rid) })
    }

    /**
     * `POST subscriptions.unread`，body `{roomId}`（RN subscriptions.ts:7-9）。
     * 注意 key 是 `roomId`（与 read 的 `rid` 不同，服务端两端点参数名不一致，RN 原样转录）。
     */
    suspend fun postSubscriptionsUnread(sdk: RocketSdk, rid: String) {
        sdk.post("subscriptions.unread", buildJsonObject { put("roomId", rid) })
    }

    /** `POST rooms.favorite`，body `{roomId, favorite}`（RN subscriptions.ts:11-13）。 */
    suspend fun postRoomsFavorite(sdk: RocketSdk, rid: String, favorite: Boolean) {
        sdk.post("rooms.favorite", buildJsonObject {
            put("roomId", rid)
            put("favorite", favorite)
        })
    }
}
