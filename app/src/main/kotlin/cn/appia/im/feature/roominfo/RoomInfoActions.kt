package cn.appia.im.feature.roominfo

import cn.appia.im.core.database.AppiaDatabase
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.RoomNotificationSettings
import cn.appia.im.core.network.api.RoomSettingsApi
import cn.appia.im.core.network.api.SaveRoomSettingsParams
import cn.appia.im.core.network.api.SubscriptionsApi

/**
 * RN roomInfoSettingsActions.ts 逐行：mute 乐观写本地（hideUnreadStatus+disableNotifications 双列）
 * → POST rooms.saveNotification → 失败回滚旧值重抛；pin（rooms.favorite）服务端成功才写 chats.f
 * （RN :53-62 同序——favorite 非乐观，与 setRoomAppiaUsageOnServerAndLocal 同款「先服务端后本地」）。
 * 屏内 pendingMute/pendingPin 覆盖 toggle 显示（RN resolveRoomInfoMuteToggle/PinToggle）。
 */
class RoomInfoActions(internal val sdk: RocketSdk, private val db: AppiaDatabase) {

    /** RN setRoomMutedOnServerAndLocal :25-51。行不存在静默跳过本地写（RN find 失败 catch 同义口径）。 */
    suspend fun setRoomMuted(rid: String, muted: Boolean) {
        val dao = db.chatDao()
        val prev = dao.getById(rid)
        if (prev != null) {
            dao.update(prev.copy(hide_unread_status = muted, disable_notifications = muted))
        }
        try {
            RoomSettingsApi.postSaveRoomNotification(
                sdk, rid,
                RoomNotificationSettings(
                    disableNotifications = if (muted) "1" else "0",
                    hideUnreadStatus = if (muted) "1" else "0",
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (prev != null) {
                // RN :45-48 回滚 previous ?? false；prev 取自库行即原值（含 null → 本地 copy 回原 null）
                dao.update(prev)
            }
            throw e
        }
    }

    /** RN setRoomPinnedOnServerAndLocal :53-62：favorite 成功后写 chats.f（失败本地零改动、异常上抛）。 */
    suspend fun setRoomPinned(rid: String, pinned: Boolean) {
        SubscriptionsApi.postRoomsFavorite(sdk, rid, pinned)
        db.chatDao().getById(rid)?.let { db.chatDao().update(it.copy(f = pinned)) }
    }

    /**
     * RN setRoomAppiaUsageOnServerAndLocal（setRoomAppiaUsage.ts:23-29）：
     * saveRoomSettings {appiaUsage} 成功后写 chats.appiaUsage（JSON 数组串；空 → null）。
     */
    suspend fun setRoomUsage(rid: String, usage: List<String>) {
        RoomSettingsApi.postSaveRoomSettings(
            sdk, rid, SaveRoomSettingsParams(appiaUsage = usage),
        )
        db.chatDao().getById(rid)?.let {
            db.chatDao().update(it.copy(appiaUsage = usage.takeIf { u -> u.isNotEmpty() }?.let(::encodeUsageJson)))
        }
    }

    /** `POST {prefix}.leave`（T3 API wire 已钉）；标记管理归 [PendingSelfLeave]，栈清理归 T10。 */
    suspend fun leaveRoom(rid: String, roomType: String) {
        RoomSettingsApi.postLeaveRoom(sdk, rid, roomType)
    }
}

/** RN updateChatAppiaUsageLocal :17：JSON.stringify(usage)（无空数组路径——空存 undefined）。 */
private fun encodeUsageJson(usage: List<String>): String =
    kotlinx.serialization.json.JsonArray(usage.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()

// ── usage 门与格式化（RN roomUsageOptions.ts / formatRoomUsageDisplay.ts / parseAppiaUsage.ts）──

/** RN ROOM_USAGE_OPTIONS（RoomsSortView/Config.ts SortMap.Usage 顺序）。 */
val ROOM_USAGE_OPTIONS = listOf("Room_Sort_COP", "Room_Sort_Organize", "Room_Sort_Meeting", "Room_Sort_Others")

/** RN DEFAULT_ROOM_USAGE_KEY：未设置时回退（renderGroupAvatarGlyph 对齐）。 */
const val DEFAULT_ROOM_USAGE_KEY = "Room_Sort_Others"

/** RN canEditRoomUsage：分类行仅 c|p。 */
fun canEditRoomUsage(roomType: String): Boolean = roomType == "c" || roomType == "p"

/** RN parseAppiaUsageList：JSON 数组串或单 key；坏 JSON 数组串回退 [原文]。 */
fun parseAppiaUsageList(raw: String?): List<String> {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return emptyList()
    if (t.startsWith("[")) {
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(t) }.getOrNull()
        if (parsed is kotlinx.serialization.json.JsonArray) {
            return parsed.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            }
        }
        return listOf(t) // JSON.parse 抛出 → [t]（RN :14-16 catch 同）
    }
    return listOf(t)
}

/** RN resolveAppiaUsageKeys：无有效项回退默认类别。 */
fun resolveAppiaUsageKeys(raw: String?): List<String> =
    parseAppiaUsageList(raw).ifEmpty { listOf(DEFAULT_ROOM_USAGE_KEY) }

// ── 槽位计算（RN index.tsx:182-192）──

/** RN MAX_DISPLAY_ITEMS = 20。 */
const val MAX_MEMBER_DISPLAY_ITEMS = 20

/**
 * RN slotsForMembers：20 − showAdd − showRemove；displayMembers = members.slice(0, slots)。
 */
fun memberDisplaySlots(showAdd: Boolean, showRemove: Boolean): Int =
    MAX_MEMBER_DISPLAY_ITEMS - (if (showAdd) 1 else 0) - (if (showRemove) 1 else 0)

// ── 权限（T2 裁定组合：chat 查不到 false / prid 恒 true / hasRoomPermission("edit-room")）──

/** RN parseRoomRoles：chats.roles JSON 串 → 字符串列表；坏 JSON/非数组 → 空。 */
fun parseRoomRoles(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    val el = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
    return (el as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: emptyList()
}

/**
 * RN canEditRoomSettings（canEditRoomSettings.ts:40-55）的同步半程：
 * chats 行查不到 → false；prid（讨论房）→ 恒 true；否则 edit-room 权限判定。
 *
 * T2 评审裁定（binding ①）：mapping 走「键未命中传 null」——store 命中且非空才作为
 * mapping 传入，否则传 null 触发 [cn.appia.im.core.permissions.getDefaultPermissionMapping]
 * 兜底（[owner,moderator,admin]），**不传非空 store map**（避免空 roles 键命中 isNullOrEmpty 提前 false）。
 * RN 侧 getEditRoomAllowedRoles 的 permissions.listAll 兜底同步归 [cn.appia.im.core.network.api.PermissionsApi.syncPermissions]
 * （进入页面时装配处调用一次，RN useFocusEffect 同位）。
 */
fun canEditRoomSettings(
    chat: cn.appia.im.core.database.entity.ChatEntity?,
    globalRoles: List<String>,
    permissionMapping: Map<String, List<String>>?, // 装配处传 PermissionsStore.permissions.value
): Boolean {
    if (chat == null) return false
    if (chat.prid != null) return true
    val roomRoles = parseRoomRoles(chat.roles)
    // binding ①：edit-room 键未命中/空 roles → null（触发 getDefaultPermissionMapping 兜底）
    val mapping = permissionMapping?.get("edit-room")?.takeIf { it.isNotEmpty() }?.let { mapOf("edit-room" to it) }
    return cn.appia.im.core.permissions.hasRoomPermission(
        "edit-room", roomRoles, globalRoles, mapping,
    )
}

/**
 * RN useCanRemoveRoomMember :16-31：getRoomRoles(rid,t) + remove-user 权限判定
 * （房间角色取 roles.find(u._id==me)，映射键未命中同样传 null 走兜底）。
 * 响应形状 `{roles:[{u:{_id}, roles:string[]}]}`（T3 getRoomRoles wire 测试钉死）。
 * 失败 → false（RN catch 同）。
 */
suspend fun canRemoveRoomMember(
    sdk: RocketSdk,
    rid: String,
    roomType: String,
    currentUserId: String?,
    globalRoles: List<String>,
    permissionMapping: Map<String, List<String>>?,
): Boolean {
    if (currentUserId.isNullOrEmpty()) return false
    return try {
        val raw = RoomSettingsApi.getRoomRoles(sdk, rid, roomType)
        val roles = (raw as? kotlinx.serialization.json.JsonObject)?.get("roles")
            as? kotlinx.serialization.json.JsonArray ?: return false
        val mine = roles.mapNotNull { entry ->
            val obj = entry as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val uid = (obj["u"] as? kotlinx.serialization.json.JsonObject)
                ?.let { u -> (u["_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content }
            val rs = (obj["roles"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { r -> (r as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?: emptyList()
            if (uid == currentUserId) rs else null
        }.firstOrNull() ?: emptyList()
        val removeUserRoles = permissionMapping?.get("remove-user")?.takeIf { it.isNotEmpty() }
            ?.let { mapOf("remove-user" to it) }
        cn.appia.im.core.permissions.hasRoomPermission("remove-user", mine, globalRoles, removeUserRoles)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
