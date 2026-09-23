package cn.appia.im.core.network.api

import cn.appia.im.core.network.RocketSdk
import kotlinx.serialization.json.JsonElement

/**
 * 房间端点（RN services/api/rooms.ts 的 mention 候选子集）：
 * `GET appia/room/members/v2?rid` → `{success, memberSize, data:[{org,map,members,departments,isLocal}]}`。
 * Android `sdk.get` 平铺 `data ?? resp`（既有绑定）：返回可能直接是块数组或包 success 的对象，
 * 解析层（parseAppiaRoomMembersV2）两种形态都吃。
 */
object RoomsApi {
    suspend fun getAppiaRoomMembersV2(sdk: RocketSdk, rid: String): JsonElement =
        sdk.get("appia/room/members/v2", mapOf("rid" to rid))

    /**
     * `GET rooms.info?roomId`（RN rooms.ts:42-50 getRoomInfo）：房间详情
     * （含 announcement/announcements，refreshRoomAnnouncements 消费）。
     * 响应 `{success?, room?}`——sdk.get 平铺 `data ?? resp`，此处原样返回由调用方取 room。
     */
    suspend fun getRoomInfo(sdk: RocketSdk, roomId: String): JsonElement =
        sdk.get("rooms.info", mapOf("roomId" to roomId))
}
