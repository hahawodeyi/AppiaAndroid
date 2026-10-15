package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.voice.VoiceCallMsg
import cn.appia.im.core.voice.lib.countUsersInVoiceChannel
import cn.appia.im.core.voice.lib.parseCallMsg
import cn.appia.im.core.voice.lib.reconcileStaleLocalRoomCallMsg
import cn.appia.im.core.voice.lib.shouldShowRoomVoiceJoinBanner

/**
 * 房间顶「加入语音」横幅（M10-T12 / RN RoomVoiceJoinBanner/index.tsx + useStableRoomVoiceJoinBanner）：
 * 群聊通话进行中且频道有人 → 展示「当前 N 人正在语音通话，点击加入」；点击 → onJoin。
 * 显隐/人数稳定化见 [stableJoinBannerResult]。
 */
/**
 * RN useStableRoomVoiceJoinBanner（:48-107）的稳定化状态机：同 recordId 下 callMsg
 * 同步抖动（userStatus 短暂掉零/消失）沿用缓存；recordId 变化即重置。Compose 侧由
 * 装配处 `remember { StableJoinBannerCache() }` 承载缓存生命周期（等价 RN ref 跨渲染存活）。
 */
internal class StableJoinBannerCache {
    var recordId: String = ""
    var visible: Boolean = false
    var inCallCount: Int = 0

    fun reset() {
        recordId = ""
        visible = false
        inCallCount = 0
    }
}

/** RN getCallRecordId（:22-27）：recordId 空串归一。 */
internal fun getCallRecordId(callMsg: VoiceCallMsg?): String =
    callMsg?.recordId?.trim().orEmpty()
        .ifEmpty { callMsg?.recordDataObj?.recordId?.trim().orEmpty() }

/** RN resolveStableInCallCount（:30-33）：有实时人数用当前值，掉零沿用缓存。 */
private fun resolveStableInCallCount(rawCount: Int, cachedCount: Int): Int =
    if (rawCount > 0) rawCount else cachedCount

/** RN useMemo 主体（:48-107）——不纯（写 cache），故 cache 由调用方持有传入。 */
internal fun stableJoinBannerResult(
    cache: StableJoinBannerCache,
    callMsgRaw: String?,
    roomType: String?,
    username: String?,
    serverOnCallStatus: Boolean?,
): Pair<Boolean, Int> {
    val parsed = parseCallMsg(callMsgRaw)
    val effective = parsed?.let { reconcileStaleLocalRoomCallMsg(it) ?: it }
    val recordId = getCallRecordId(effective)

    if (effective == null) {
        if (cache.visible && cache.inCallCount > 0) return true to cache.inCallCount
        cache.reset()
        return false to 0
    }

    val rawVisible = shouldShowRoomVoiceJoinBanner(effective, username, roomType, serverOnCallStatus = serverOnCallStatus)
    val rawCount = countUsersInVoiceChannel(effective)

    if (recordId != cache.recordId) {
        cache.recordId = recordId
        cache.visible = rawVisible
        cache.inCallCount = rawCount
        return rawVisible to rawCount
    }

    if (rawVisible) {
        val inCallCount = resolveStableInCallCount(rawCount, cache.inCallCount)
        cache.visible = true
        cache.inCallCount = inCallCount
        return true to inCallCount
    }

    // userStatus 短暂不完整时保持展示（RN :93-104）
    if (cache.visible && (effective.status == "talking" || effective.status == "calling") &&
        (rawCount > 0 || cache.inCallCount > 0)
    ) {
        val inCallCount = resolveStableInCallCount(rawCount, cache.inCallCount)
        cache.inCallCount = inCallCount
        return true to inCallCount
    }

    cache.visible = false
    cache.inCallCount = rawCount
    return false to rawCount
}

@Composable
fun RoomVoiceJoinBanner(
    callMsgRaw: String?,
    roomType: String?,
    username: String?,
    serverOnCallStatus: Boolean? = null,
    t: (String) -> String,
    onJoin: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val cache = remember { StableJoinBannerCache() }
    val (visible, inCallCount) = remember(callMsgRaw, roomType, username, serverOnCallStatus) {
        stableJoinBannerResult(cache, callMsgRaw, roomType, username, serverOnCallStatus)
    }

    if (!visible) return

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chatComponentBackground)
            .clickable(onClick = onJoin)
            .testTag("qa-room-voice-join-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✆",
            color = colors.tintColor,
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            t("room_joinVoiceChat_inCall").replace("{{count}}", inCallCount.toString()),
            color = colors.tintColor,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
        )
    }
}

/** RN handleRoomVoiceJoinBannerPress 的纯判定半部（join 动作由装配处 controller 承载）。 */
fun roomVoiceJoinBannerVisible(
    callMsg: VoiceCallMsg?,
    roomType: String?,
    username: String?,
    serverOnCallStatus: Boolean? = null,
): Boolean = shouldShowRoomVoiceJoinBanner(callMsg, username, roomType, serverOnCallStatus = serverOnCallStatus)
