package cn.appia.im.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.domain.voice.VoiceSessionController

/**
 * 第二路接听三型确认弹窗（RN VoiceOtherCallAlert/index.tsx 1:1）：
 * type 1=接新来电（accept）2=加入（join）3=跨主体切换（switch_org）；
 * cancel=cancelOtherIncomingAlert（1/3 顺带拒接）、confirm=confirmOtherIncomingAlert。
 */
@Composable
internal fun VoiceOtherCallAlert(
    alertType: Int,
    controller: VoiceSessionController,
    t: (String) -> String,
) {
    val bodyKey = when (alertType) {
        1 -> "voice_other_alert_accept_body"
        2 -> "voice_other_alert_join_body"
        else -> "voice_other_alert_switch_org_body"
    }
    val confirmKey = when (alertType) {
        1 -> "voice_other_alert_accept_confirm"
        2 -> "voice_other_alert_join_confirm"
        else -> "voice_other_alert_switch_org_confirm"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .pointerInput(Unit) {}
            .testTag("qa-voice-other-call-alert"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 35.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White),
        ) {
            Text(
                t(bodyKey),
                color = Color(0xFF1D2129),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 15.dp)
                    .padding(top = 30.dp, bottom = 15.dp)
                    .fillMaxWidth()
                    .height(90.dp),
                // RN minHeight 90 + center
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E6EB)))
            Row(Modifier.fillMaxWidth().height(60.dp)) {
                Text(
                    t("cancel"),
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { controller.cancelOtherIncomingAlert() }
                        .testTag("qa-voice-other-call-alert-cancel")
                        .padding(vertical = 16.dp),
                )
                Spacer(Modifier.width(1.dp).height(60.dp).background(Color(0xFFE5E6EB)))
                Text(
                    t(confirmKey),
                    color = Color(0xFF1B5BFF),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { controller.confirmOtherIncomingAlert() }
                        .testTag("qa-voice-other-call-alert-confirm")
                        .padding(vertical = 16.dp),
                )
            }
        }
    }
}
