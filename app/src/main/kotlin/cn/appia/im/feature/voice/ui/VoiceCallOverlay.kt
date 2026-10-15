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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.voice.VoicePhase
import cn.appia.im.core.voice.VoiceCallMsgContext
import cn.appia.im.core.voice.lib.VoiceDisplayPeer
import cn.appia.im.core.voice.lib.isGroupVoiceRoom
import cn.appia.im.core.voice.lib.resolveVoiceCallRoomType
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionState
import kotlinx.coroutines.delay

/**
 * 活跃相位全屏 overlay（RN VoiceCallOverlay/index.tsx 224 行 1:1）：
 * call_bg 背景 + 顶部计时 pill（hadRemoteParticipant）+ 参与者中心 + isWaitingRemote 拨号提示 +
 * 控制行（speaker/hangup/mute）+ 次行（invite/minimize；录音钮裁定③不渲染）。
 */
@Composable
internal fun VoiceCallOverlay(
    state: VoiceSessionState,
    controller: VoiceSessionController,
    t: (String) -> String,
    onPickMembers: (rid: String, roomType: String) -> Unit,
    currentUsername: String? = null,
) {
    val phase = state.phase
    val hoster = state.hoster
    val callMsg = state.callMsg
    val isHost = hoster != null
    val isWaitingRemote = isHost && !state.hadRemoteParticipant && phase != VoicePhase.InCall

    val roomType = resolveVoiceCallRoomType(callMsg, VoiceCallMsgContext(hoster?.roomType))
    val isActiveCallPhase = phase == VoicePhase.OutgoingRinging || phase == VoicePhase.Connecting || phase == VoicePhase.InCall
    val showInvite = isActiveCallPhase && state.rid != null
    val inviteEnabled = isGroupVoiceRoom(roomType) && state.recordId != null

    // RN :76-87 waitingCallee（主叫等待中：第一个被叫）
    val waitingCallee = if (isWaitingRemote) {
        val r = hoster?.receivers?.firstOrNull() ?: callMsg?.recordDataObj?.receivers?.firstOrNull()
        r?.takeIf { it.receiver.isNotBlank() }?.let { VoiceDisplayPeer(it.receiver, it.name) }
    } else {
        null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .testTag("qa-voice-overlay"),
    ) {
        VoiceCallBackground()

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // RN top：接通后计时 pill（录音点裁定③跳过）
            Box(Modifier.fillMaxWidth().height(45.dp), contentAlignment = Alignment.Center) {
                if (state.hadRemoteParticipant) {
                    ConnectTimer()
                }
            }

            // RN center
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VoiceParticipantCenter(
                    users = state.users,
                    roomType = roomType,
                    localUsername = currentUsername,
                    callMsg = callMsg,
                    hoster = hoster,
                    waitingCallee = waitingCallee,
                    t = t,
                )
                if (isWaitingRemote) {
                    ConnectStatus(state.outgoingHint, t)
                }
            }

            // RN bottom
            Column(Modifier.fillMaxWidth().padding(horizontal = 38.dp).navigationBarsPadding().padding(bottom = 50.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ControlColumn(
                        tag = "qa-voice-speaker",
                        label = if (state.speakerOn) t("voice_chat_speaker_on") else t("voice_chat_speaker_off"),
                    ) {
                        controller.setSpeaker(!state.speakerOn)
                    }
                    EndButton(tag = "qa-voice-hangup", t = t) { controller.endCall("ui") }
                    ControlColumn(
                        tag = "qa-voice-mute",
                        label = if (state.muteLocal) t("voice_chat_mic_off") else t("voice_chat_mic_on"),
                    ) {
                        controller.setMuted(!state.muteLocal)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 19.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showInvite) {
                        ControlColumn(
                            tag = "qa-voice-invite",
                            enabled = inviteEnabled,
                            label = t("voice_overlay_invite"),
                        ) {
                            // RN :89-102 先最小化再跳选人页（requestAnimationFrame 归 Compose 帧调度）
                            controller.setUiMinimized(true)
                            onPickMembers(state.rid!!, roomType ?: "")
                        }
                    } else {
                        Spacer(Modifier.size(74.dp))
                    }
                    // RN VoiceRecordControl 位置：录音 UI 裁定③不渲染（backlog-m10）
                    Spacer(Modifier.size(74.dp))
                    ControlColumn(tag = "qa-voice-minimize", label = t("voice_overlay_shrink")) {
                        controller.setUiMinimized(true)
                    }
                }
            }
        }
    }
}

/** RN controlsRow 侧列（speaker/mute）。 */
@Composable
private fun ControlColumn(tag: String, enabled: Boolean = true, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
        Box(
            Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
                .clickable(enabled = enabled, onClick = onClick)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Text("◦", color = Color.White, fontSize = 26.sp)
        }
        Text(label, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
    }
}

/** RN endWrap：call_end.png 74dp 挂断大钮（flex:1 居中由外层 Row SpaceBetween 承担）。 */
@Composable
private fun EndButton(tag: String, t: (String) -> String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(VoiceAssets.CALL_END),
            contentDescription = null,
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag(tag),
        )
        Text(t("voice_overlay_end"), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

/** RN VoiceConnectTimer（45 行）：接通后 mm:ss 顶部 pill（1s ticker；录音点跳过）。 */
@Composable
private fun ConnectTimer() {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    val minutes = seconds / 60
    val text = "%02d:%02d".format(minutes, seconds % 60)
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x4CFFFFFF))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

/** RN VoiceConnectStatus（38 行）：主叫等待「拨号中...」动画（outgoingHint 驱动）。 */
@Composable
private fun ConnectStatus(outgoingHint: String?, t: (String) -> String) {
    var dots by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            dots = if (dots.length < 3) "$dots." else ""
        }
    }
    val notConnect = outgoingHint == "not_connect"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            t(if (notConnect) "voice_chat_not_connect" else "voice_chat_oncall_tip") + if (notConnect) "" else dots,
            color = Color(0xFFC0C0C0),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 35.dp),
        )
    }
}
