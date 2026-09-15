package cn.appia.im.feature.chatlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.realtime.CONNECTING_BANNER_DEBOUNCE_MS
import cn.appia.im.core.realtime.ColdStartReconnectGrace
import cn.appia.im.core.realtime.ConnectionBannerMode
import cn.appia.im.core.realtime.RealtimeTransportPhase
import cn.appia.im.core.realtime.resolveConnectionBannerPresentation
import kotlinx.coroutines.delay

// RN RoomListConnectionBanner/styles.ts 字面色（wrap #FFF4F0/#F0D0C8、connecting #FFF9E6/#F5E6B8、
// 文本 #C2410C、connecting 文本/菊花 #92400E、动作 #1B5BFF）
private val WrapBackground = Color(0xFFFFF4F0)
private val WrapConnectingBackground = Color(0xFFFFF9E6)
private val WrapBorder = Color(0xFFF0D0C8)
private val WrapConnectingBorder = Color(0xFFF5E6B8)
private val TextColor = Color(0xFFC2410C)
private val TextConnectingColor = Color(0xFF92400E)
private val ActionColor = Color(0xFF1B5BFF)

/**
 * 会话列表连接状态横幅（M2 T5，逐行为移植 RN RoomListConnectionBanner/index.tsx）：
 * - networkOnline=false → 网络断开（立即）；phase=disconnected → 未连接标题 +「重试」（手动重连）；
 * - phase=connecting → 「正在重新连接…」+ 菊花，**防抖 2s**（RN :30/:46-53：回前台短重连不闪现），
 *   判定收在策略纯函数（connectingForMs 可单测），本组件只供时钟；
 * - 冷启动 4s 内本地有会话时 connecting 不展示（ColdStartReconnectGrace，RN coldStartReconnectGrace）。
 *
 * 防抖计时：进入 connecting 记起点 → 2s 后写 elapsed 触发重组放行；期间离开即清零（RN 定时器
 * cleanup 语义）。挂载（T11 ChatListScreen）传 manager.phase / NetworkMonitor.online /
 * manager::requestManualReconnect。
 */
@Composable
fun ConnectionBanner(
    phase: RealtimeTransportPhase,
    networkOnline: Boolean?,
    localHasRooms: Boolean,
    onManualReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // RN :39：每次组合即读宽限（consumed/过期后自然放行）
    val suppressConnecting = ColdStartReconnectGrace.shouldSuppressColdStartConnectingBanner(localHasRooms)

    var connectingElapsedMs by remember { mutableStateOf(0L) }
    // 键含 networkOnline/suppressConnecting（presentation.mode 的全部入参）：网络抖动
    // （offline→恢复）或宽限翻转时重置 2s 计时——RN :46-53 以 presentation.mode 为键的同款重启语义
    LaunchedEffect(phase, networkOnline, suppressConnecting) {
        if (phase == RealtimeTransportPhase.CONNECTING) {
            val startedAt = System.currentTimeMillis()
            connectingElapsedMs = 0L // RN connectingVisible=false 起步：首帧不展示
            delay(CONNECTING_BANNER_DEBOUNCE_MS)
            connectingElapsedMs = System.currentTimeMillis() - startedAt
        } else {
            connectingElapsedMs = 0L
        }
    }

    // RN :36/:90-96 busy+disabled 等价：点击即停用防双发；重连落定（phase 离开 connecting——
    // 成功 CONNECTED / 失败 DISCONNECTED）即恢复，对应 RN finally { setBusy(false) } 不卡死按钮
    var reconnectBusy by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase != RealtimeTransportPhase.CONNECTING) reconnectBusy = false
    }

    val presentation = resolveConnectionBannerPresentation(
        networkOnline = networkOnline,
        phase = phase,
        suppressConnecting = suppressConnecting,
        connectingForMs = connectingElapsedMs,
    )
    val mode = presentation.mode
    if (mode == ConnectionBannerMode.HIDDEN) return // RN :83-85 隐藏即不渲染

    val connecting = mode == ConnectionBannerMode.CONNECTING
    val networkOffline = mode == ConnectionBannerMode.NETWORK_OFFLINE

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (connecting) WrapConnectingBackground else WrapBackground)
                .testTag("qa-room-list-offline-banner") // RN testID 同名（Maestro resource-id）
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    networkOffline -> context.t("roomList_networkOffline_title")
                    connecting -> context.t("roomList_offlineBanner_connecting")
                    else -> context.t("roomList_offlineBanner_title")
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                color = if (connecting) TextConnectingColor else TextColor,
                fontSize = 13.sp,
                maxLines = 2, // RN numberOfLines={2}
            )
            when {
                connecting -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = TextConnectingColor,
                )
                !networkOffline -> Text(
                    text = context.t("roomList_offlineBanner_reconnect"),
                    modifier = Modifier.clickable(
                        enabled = !reconnectBusy, // RN :110 disabled={busy}
                        onClick = {
                            if (!reconnectBusy) {
                                reconnectBusy = true
                                onManualReconnect()
                            }
                        },
                        role = Role.Button,
                    ),
                    color = ActionColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, // RN action fontWeight '600'
                )
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp, // RN hairlineWidth
            color = if (connecting) WrapConnectingBorder else WrapBorder,
        )
    }
}
