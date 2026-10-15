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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.domain.voice.VoiceSessionController
import cn.appia.im.domain.voice.VoiceSessionState

/**
 * 来电顶部横幅（RN VoiceIncomingBanner/index.tsx 74 行 1:1）：
 * 点正文 → openIncomingConfirm 全屏确认；左拒右接。
 * RN bannerOverlay pointerEvents box-none → Compose 全屏 Box 不消费点击，仅面板 clickable。
 */
@Composable
internal fun VoiceIncomingBanner(
    state: VoiceSessionState,
    controller: VoiceSessionController,
    t: (String) -> String,
) {
    val callMsg = state.callMsg
    val callerName = rememberStableIncomingCallerLabel(callMsg)
    val receiverCount = callMsg?.recordDataObj?.receivers?.size ?: 0
    val subtitle = t(if (receiverCount > 1) "voice_incoming_multi" else "voice_incoming_from_appia")

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // RN panel：98% 宽黑底圆角面板
            Row(
                Modifier
                    .fillMaxWidth(0.98f)
                        .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF000000))
                    .padding(16.dp)
                    .testTag("qa-voice-incoming-banner"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { controller.openIncomingConfirm() }
                        .padding(end = 20.dp)
                        .testTag("qa-voice-incoming-banner-body"),
                ) {
                    Text(
                        callerName,
                        color = Color.White,
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(
                    Modifier.width(105.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BannerAction(color = Color(0xFFF5455C), tag = "qa-voice-incoming-banner-reject") {
                        controller.rejectIncoming()
                    }
                    BannerAction(color = Color(0xFF2DE0A5), tag = "qa-voice-incoming-banner-accept") {
                        controller.acceptIncoming()
                    }
                }
            }
        }
    }
}

/** RN VoiceCallRefuseIcon/VoiceCallAcceptIcon 36dp 圆钮（Material 图标族无资产——色块圆钮 + testTag 承担语义）。 */
@Composable
private fun BannerAction(color: Color, tag: String, onClick: () -> Unit) {
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
        Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
