package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.appia.im.domain.presence.PresenceBatcher
import cn.appia.im.domain.presence.PresenceStore
import cn.appia.im.domain.presence.TUserStatus
import cn.appia.im.domain.presence.UsernameIdResolver
import cn.appia.im.domain.presence.mergePresenceStatus
import cn.appia.im.domain.presence.pickEffectivePresenceUserId
import cn.appia.im.domain.presence.shouldShowOnlineDot
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * presence 头像徽章（RN components/DirectAvatar presence 部分 + useUserPresence 的 Android 落法）：
 * - 直径 `max(8, round(size*0.28))` dp、颜色恒 #2de0a5（RN STATUS_COLORS.online，不随主题）、右下角
 * - 可见性判定 = [shouldShowOnlineDot]（bot 剔除 + **仅 online/away**）——与名片 isOnline
 *   文字态（仅 online，useMemberProfile 口径）是两个判定，勿合并
 * - id 链（RN useUserPresence）：直传 id 有效取之 → username 已解析缓存 → 否则
 *   [UsernameIdResolver.scheduleResolve]（400ms 防抖 users.info）；请求/解析副作用挂
 *   LaunchedEffect（deps=effectiveId/username，RN useEffect 同键——重组不再重置 batcher 防抖）
 * - status 双层合成 store ?? fallback（[mergePresenceStatus]）
 *
 * 用法：`val badge = presenceBadge(...)`；头像 Box 内 `badge()`（绿点叠 BottomEnd）。
 * domain/presence 纯 Kotlin，Compose 缝归本包（RoomHeader 跨屏共享先例同位）。
 */
@Composable
fun presenceBadge(
    userId: String?,
    username: String?,
    fallbackStatus: TUserStatus?,
    avatarSize: Dp,
): @Composable BoxScope.() -> Unit {
    // 订阅解析版本号：resolve 完成后 bump → 重读缓存（RN usePresenceIdResolved 同义）
    val resolvedVersion by UsernameIdResolver.resolvedVersion.collectAsState()
    val resolvedId = remember(resolvedVersion, username) { UsernameIdResolver.resolvedRcUserId(username) }
    val effectiveId = remember(resolvedId, userId, username) {
        pickEffectivePresenceUserId(userId, username.orEmpty(), resolvedId)
    }

    LaunchedEffect(effectiveId, username) {
        if (effectiveId != null) {
            PresenceBatcher.requestUserPresence(effectiveId)
        } else if (!username.isNullOrEmpty() && !cn.appia.im.domain.presence.isBotUserId(userId)) {
            UsernameIdResolver.scheduleResolve(username)
        }
    }

    val storeEntry by remember(effectiveId) {
        if (effectiveId != null) PresenceStore.status(effectiveId) else MutableStateFlow<TUserStatus?>(null)
    }.collectAsState()
    val status = mergePresenceStatus(effectiveId, storeEntry, fallbackStatus)
    val show = shouldShowOnlineDot(status, effectiveId, username)
    return {
        if (show) {
            PresenceDot(avatarSize, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** 绿点本体（RN PresenceDot）：恒 #2de0a5，直径 max(8, round(size*0.28))。 */
@Composable
fun PresenceDot(avatarSize: Dp, modifier: Modifier = Modifier) {
    val dot = presenceDotDiameter(avatarSize)
    Box(
        modifier
            .size(dot)
            .clip(CircleShape)
            .background(Color(0xFF2DE0A5))
            .testTag("qa-presence-dot"),
    )
}

/** RN presenceDotDiameter：max(8, round(size*0.28))。 */
fun presenceDotDiameter(avatarSize: Dp): Dp =
    maxOf(8f, kotlin.math.round(avatarSize.value * 0.28f)).dp
