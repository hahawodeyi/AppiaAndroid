package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionState

/**
 * 通话中另一路来电横幅（RN VoiceOtherCallBanner/index.tsx 74 行 1:1，对齐旧版 OtherVoiceChatNotifi）：
 * 顶部叠加（RN Modal 顶层）；标题「另一路来电」+ 主叫名 + 副标题；左拒右接（接 = beginOtherIncomingAccept）。
 */
@Composable
internal fun VoiceOtherCallBanner(
    state: VoiceSessionState,
    controller: VoiceSessionController,
    t: (String) -> String,
) {
    val other = state.otherIncoming ?: return
    val callerName = rememberStableIncomingCallerLabel(other.callMsg)
    val receiverCount = other.callMsg.recordDataObj?.receivers?.size ?: 0
    val subtitle = t(if (receiverCount > 1) "voice_incoming_multi" else "voice_incoming_from_appia")

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier
                    .fillMaxWidth(0.98f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF000000))
                    .padding(16.dp)
                    .testTag("qa-voice-other-incoming-banner"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(t("voice_other_incoming_title"), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    Text(
                        callerName,
                        color = Color.White,
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(subtitle, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(
                    Modifier.width(105.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OtherBannerAction(color = Color(0xFFF5455C), tag = "qa-voice-other-incoming-banner-reject") {
                        controller.rejectOtherIncoming()
                    }
                    OtherBannerAction(color = Color(0xFF2DE0A5), tag = "qa-voice-other-incoming-banner-accept") {
                        controller.beginOtherIncomingAccept()
                    }
                }
            }
        }
    }
}

@Composable
private fun OtherBannerAction(color: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(4.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", color = Color.White, fontSize = 16.sp)
    }
}
