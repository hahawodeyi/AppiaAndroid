package cn.appia.im.domain.presence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * presence 判定链（RN src/lib/presence 各文件逐行移植；M4 落在 feature/contacts/TeamModels，
 * M5-T3 迁入 presence 域——TeamModels 的旧定义已删并反向引用本包）。
 */

/** RN TUserStatus（types/userStatus.ts 六值全集；通讯录 fallback 仅产前四值）。 */
enum class TUserStatus { ONLINE, AWAY, BUSY, OFFLINE, DISABLED, LOADING }

/** 17 位 Meteor/Rocket.Chat 常见 userId 形态（RN isRocketChatUserId 同款）。 */
private val METEOR_ID_RE = Regex("^[0-9a-zA-Z]{17}$")

private const val DEPT_KEY_PREFIX = "EMT-"

/** RN isBotUser.isBotUserId：含 '.bot' 即 bot。 */
fun isBotUserId(userId: String?): Boolean = userId?.contains(".bot") == true

/** RN isBotUser.isBotUsername。 */
fun isBotUsername(username: String?): Boolean = username?.contains(".bot") == true

/** 形如 benfu.wei 的账号名，不能当作 users.presence 的 id（RN looksLikeAppiaUsername）。 */
fun looksLikeAppiaUsername(value: String): Boolean = value.contains(".")

/**
 * RN isRocketChatUserId 全规则逐条：空/bot/EMT- 前缀/与 username 相同 → false；
 * 17 位 Meteor id → true；含 '.' 的 username 形态 → false；其余 ≥6 字符兜底 → true。
 */
fun isRocketChatUserId(id: String?, username: String? = null): Boolean {
    val trimmed = id?.trim().orEmpty()
    if (trimmed.isEmpty() || isBotUserId(trimmed)) return false
    if (trimmed.startsWith(DEPT_KEY_PREFIX)) return false
    val normalizedUsername = username?.trim().orEmpty()
    if (normalizedUsername.isNotEmpty() && trimmed == normalizedUsername) return false
    if (METEOR_ID_RE.matches(trimmed)) return true
    if (looksLikeAppiaUsername(trimmed)) return false
    return trimmed.length >= 6
}

/** RN pickContactPresenceRaw：statusConnection ?? onlineStatus ?? status（HRM/users.info 字段优先级）。 */
fun pickContactPresenceRaw(statusConnection: String?, onlineStatus: String?, status: String?): String? =
    statusConnection ?: onlineStatus ?: status

/** RN mapContactStatusToTUserStatus：已知四态直通（trim+lowercase）；未知/空 → null。 */
fun mapContactStatusToTUserStatus(raw: String?): TUserStatus? = when (raw?.trim()?.lowercase()) {
    "online" -> TUserStatus.ONLINE
    "away" -> TUserStatus.AWAY
    "busy" -> TUserStatus.BUSY
    "offline" -> TUserStatus.OFFLINE
    else -> null
}

/**
 * RN shouldShowOnlineDot：bot（id/username 含 '.bot'）剔除；**仅 online/away** 显示绿点。
 * 名片 isOnline 文字态（RN useMemberProfile/useTeamUser：仅 online）是另一判定，勿合并。
 */
fun shouldShowOnlineDot(status: TUserStatus?, userId: String? = null, username: String? = null): Boolean {
    if (isBotUserId(userId) || isBotUsername(username)) return false
    return status == TUserStatus.ONLINE || status == TUserStatus.AWAY
}

/**
 * RN resolveDirectPeerUserId（lib/presence/resolveDirectPeerUserId.ts）：
 * uids JSON 数组剔除自己取对端；空串/坏 JSON/非数组/空数组/仅自己/缺 currentUserId → null。
 * M4 判死未移植——M5-T3 单聊列表头像 peer 链激活。
 */
fun resolveDirectPeerUserId(uidsRaw: String?, currentUserId: String?): String? {
    if (uidsRaw.isNullOrEmpty() || currentUserId.isNullOrEmpty()) return null
    val uids = runCatching { Json.parseToJsonElement(uidsRaw) as? JsonArray }.getOrNull() ?: return null
    if (uids.isEmpty()) return null
    if (uids.size == 1 && (uids[0] as? JsonPrimitive)?.contentOrNull == currentUserId) return null
    return uids.firstNotNullOfOrNull { el ->
        (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it != currentUserId }
    }
}

/** RN useUserPresence pickEffectiveUserId（DirectAvatar bot 前置守卫合成）：直传 id 有效取之，否则已解析 id。 */
fun pickEffectivePresenceUserId(userId: String?, username: String?, resolvedId: String?): String? {
    if (userId != null && !isBotUserId(userId) && isRocketChatUserId(userId, username)) return userId
    return resolvedId
}

/** RN useUserPresence 双层降级：effectiveId 命中 → store ?? fallback；未命中 → 仅 fallback。 */
fun mergePresenceStatus(effectiveId: String?, storeStatus: TUserStatus?, fallbackStatus: TUserStatus?): TUserStatus? =
    if (effectiveId != null) storeStatus ?: fallbackStatus else fallbackStatus
