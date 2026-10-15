package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.lib.VoiceDisplayPeer
import cn.appia.im.core.voice.lib.resolveIncomingCallerIdentity
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionState

/**
 * RN VoiceCallHost/index.tsx:45-49/135-189 相位分流的纯映射面。
 * ACTIVE_CALL_PHASES 逐字 = {outgoingRinging, connecting, inCall}——ending/rejected/busy/
 * timeout/cancelled 全部不渲染（ending ~800ms linger 由 Controller 相位时序承载，UI 不感知）。
 */
sealed interface VoiceUiSurface {
    /** 不渲染（Idle / 非活跃相位 / 活跃相位且 uiMinimized——气泡归 Task 12）。 */
    data object None : VoiceUiSurface

    /** IncomingRinging + !expanded → 顶部横幅（RN bannerOverlay pointerEvents box-none 等价：仅横幅面板消费点击）。 */
    data object Banner : VoiceUiSurface

    /** IncomingRinging + expanded → 全屏确认页。 */
    data object Confirm : VoiceUiSurface

    /** 活跃相位全屏 overlay；otherIncoming 横幅叠加 / alertType 三型弹窗叠加标志透传。 */
    data class Overlay(val otherIncoming: Boolean, val alertType: Int?) : VoiceUiSurface
}

/**
 * RN index.tsx:153-189 分流逻辑（render 半部；AppState/FGS/ringer/float 副作用已归
 * Controller + 平台层 Task 9，此处纯 render dispatch）。
 */
internal fun voiceUiSurface(state: VoiceSessionState): VoiceUiSurface = when {
    state.phase == VoicePhase.Idle -> VoiceUiSurface.None
    state.phase == VoicePhase.IncomingRinging ->
        if (state.incomingUiExpanded) VoiceUiSurface.Confirm else VoiceUiSurface.Banner
    state.phase != VoicePhase.OutgoingRinging &&
        state.phase != VoicePhase.Connecting &&
        state.phase != VoicePhase.InCall -> VoiceUiSurface.None
    // RN :177-188 callLayer：uiMinimized 时 MinimizeShell 切气泡——AA Host 不渲染（气泡归 Task 12）
    state.uiMinimized -> VoiceUiSurface.None
    else -> VoiceUiSurface.Overlay(
        otherIncoming = state.otherIncoming != null,
        alertType = state.otherIncomingAlertType,
    )
}

/**
 * App 级装配组件（RN VoiceCallHost/index.tsx render 半部 1:1）：
 * Idle → 空；IncomingRinging → 横幅/确认；活跃相位 → 全屏 overlay + 第二路横幅 + 三型弹窗叠加。
 *
 * RN :82-151 的 FGS/AppState/ringer/float 副作用已由 Controller（Task 8/9 铃声归属裁定）与
 * 平台层承载——本组件纯 render，不重新实现。唯一例外（终审 H-2）：uiEvents 收集器——
 * Controller 的错误反馈通道（RN showVoiceError Alert 的等价），放 composable body 顶部
 * （surface 分流之前，Idle 相位也可见；Host 常驻 Main 根部 → LaunchedEffect 恒活）。
 * 针脚 8：ON_RESUME 音频重应用（RN AppState-active configureInCallAudio parity）。
 *
 * @param onOpenRoom 预留房间跳转缝（Task 12 路由接线；当前无 UI 消费点）
 * @param onPickMembers overlay 邀请钮路由：最小化后跳选人页（intent=invite，Task 12 接路由）
 */
@Composable
fun VoiceCallHost(
    controller: VoiceSessionController?,
    t: (String) -> String,
    onOpenRoom: (String) -> Unit,
    onPickMembers: (rid: String, roomType: String) -> Unit,
    /** Task 11 遗留 1：本机 username（过滤参与者中心本地自己；装配层自 session 注入）。 */
    currentUsername: String? = null,
) {
    if (controller == null) return

    // H-2：emitUi → AlertDialog（RN voiceUserFeedback.ts showVoiceError 逐字对齐——title =
    // t(key)，单确认钮 t("voice_ok")；detail 仅 DEV 调试面，不进用户 UI）
    var errorEvent by remember { mutableStateOf<cn.appia.im.domain.voice.VoiceUiEvent?>(null) }
    LaunchedEffect(controller) {
        controller.uiEvents.collect { errorEvent = it }
    }
    errorEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { errorEvent = null },
            title = { Text(t(event.key)) },
            confirmButton = {
                TextButton(onClick = { errorEvent = null }) {
                    Text(t("voice_ok"))
                }
            },
        )
    }

    // 针脚 8：回前台重应用音频路由（RN AppState-active configureInCallAudio parity）。
    // reapplyInCallAudio 四相位门为 RN 三相位的良性超集（终审核证无副作用），KDoc 载明
    // Task 12 UI 层 resume 时直调。
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) {
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    controller.reapplyInCallAudio()
                }
            },
        )
    }

    // M-2：Host 级接通锚点（SystemClock 域 wall-clock）——进入活跃相位打点、Idle 复位；
    // ConnectTimer/气泡共用（最小化期间 overlay 卸载，恢复即正确值）
    val connectedAtRef = remember { mutableStateOf(0L) }
    val state0 = controller.store.state.collectAsStateCompat().value
    LaunchedEffect(state0.phase) {
        when (state0.phase) {
            VoicePhase.InCall ->
                if (connectedAtRef.value == 0L) {
                    connectedAtRef.value = android.os.SystemClock.elapsedRealtime()
                }
            VoicePhase.Idle -> connectedAtRef.value = 0L
            else -> Unit
        }
    }

    val state = state0
    when (val surface = voiceUiSurface(state)) {
        VoiceUiSurface.None -> Unit
        VoiceUiSurface.Banner -> VoiceIncomingBanner(state, controller, t)
        VoiceUiSurface.Confirm -> VoiceIncomingConfirm(state, controller, t, currentUsername)
        is VoiceUiSurface.Overlay -> {
            BoxFill {
                VoiceCallOverlay(state, controller, t, onPickMembers, currentUsername, connectedAtRef.value)
                if (surface.otherIncoming) {
                    VoiceOtherCallBanner(state, controller, t)
                }
                if (surface.alertType != null) {
                    VoiceOtherCallAlert(surface.alertType, controller, t)
                }
            }
        }
    }
}

/**
 * 来电全屏确认页（RN VoiceIncomingConfirm/index.tsx 99 行 1:1）：
 * 横幅点击展开。call_bg 背景 + 参与者中心（主叫排前）+ 底部拒/接。
 */
@Composable
internal fun VoiceIncomingConfirm(
    state: VoiceSessionState,
    controller: VoiceSessionController,
    t: (String) -> String,
    currentUsername: String? = null,
) {
    val callMsg = state.callMsg
    val isDirect = callMsg?.roomType == "d"
    val initiator = remember(callMsg) { resolveIncomingCallerIdentity(callMsg) }
    val waitingCallee = if (isDirect && initiator.username.isNotBlank()) {
        VoiceDisplayPeer(initiator.username, initiator.displayName)
    } else {
        null
    }

    // RN :41-49 displayUsers：群聊主叫排最前
    val displayUsers = remember(state.users, initiator.username) {
        if (isDirect) {
            state.users
        } else {
            val host = state.users.filter { it.username == initiator.username }
            val rest = state.users.filter { it.username != initiator.username }
            if (host.isNotEmpty()) host + rest else state.users
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .pointerInput(Unit) {} // RN Modal 承载：拦截底层触摸（发现 2）
            .testTag("qa-voice-confirm"),
    ) {
        VoiceCallBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VoiceParticipantCenter(
                    users = displayUsers,
                    roomType = callMsg?.roomType,
                    localUsername = currentUsername,
                    callMsg = callMsg,
                    waitingCallee = waitingCallee,
                    t = t,
                )
                if (!isDirect && displayUsers.size > 1) {
                    Text(
                        t("voice_incoming_also_on_call"),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 38.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 60.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConfirmSideColumn(tint = Color(0xFFF5455C), tag = "qa-voice-confirm-reject", label = t("voice_overlay_reject")) {
                    controller.rejectIncoming()
                }
                Spacer(Modifier.weight(1f))
                ConfirmSideColumn(tint = Color(0xFF2DE0A5), tag = "qa-voice-confirm-accept", label = t("voice_overlay_accept")) {
                    controller.acceptIncoming()
                }
            }
        }
    }
}

/** RN sideCol：74 图钮 + 下方文案（call_end.png / VoiceIncomingAcceptIcon）。 */
@Composable
private fun ConfirmSideColumn(tint: Color, tag: String, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (tint == Color(0xFFF5455C)) {
            Image(
                painter = painterResource(VoiceAssets.CALL_END),
                contentDescription = null,
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
                    .testTag(tag),
            )
        } else {
            Box(
                Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(tint)
                    .clickable(onClick = onClick)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text("✆", color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center)
            }
        }
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
    }
}
