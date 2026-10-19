package cn.appia.im.feature.privacy.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.feature.login.ui.LoginTheme

/**
 * 首启隐私同意屏（M11-T5 Slot3 / RN PrivacyConsentActivity + activity_privacy_consent.xml 对齐）。
 * RN 是 View 时代独立 Activity（抢 LAUNCHER intent-filter），AA 单 Activity 形态裁定为
 * MainActivity setContent 根部整树替换（门禁接线见 MainActivity）；本屏只管渲染与回调。
 * 布局结构对齐：title 20sp bold 居中 / message 15sp 可滚动 / 两链接 15sp 蓝字点击 ACTION_VIEW /
 * 同意主按钮 48dp 高（LoginTheme.actionTint 底 + buttonTextOnPrimary 字）/ 不同意次按钮灰字 44dp。
 * 链接蓝 RN #2878FF 与 AA 登录品牌色 #1D74F5 差异按裁定取 LoginTheme.actionTint（跨屏一致优先）。
 * 返回键 = 不同意退出（RN OnBackPressedCallback 语义）经 BackHandler。
 */
@Composable
fun PrivacyConsentScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    // RN OnBackPressedCallback：系统返回 = 退出（不同意）
    BackHandler(onBack = onDisagree)

    Column(
        modifier
            .fillMaxSize()
            .background(LoginTheme.screenBackground)
            .padding(24.dp)
            .testTag("privacy_consent_screen"),
    ) {
        Text(
            context.t("privacy_consent_title"),
            modifier = Modifier.fillMaxWidth(),
            color = LoginTheme.titleText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                context.t("privacy_consent_message"),
                modifier = Modifier.fillMaxWidth(),
                color = LoginTheme.bodyText,
                fontSize = 15.sp,
                lineHeight = 23.sp,
            )
            Text(
                context.t("privacy_consent_terms_link"),
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clickable { openUrl(context.t("privacy_consent_terms_url")) }
                    .testTag("privacy_consent_terms_link"),
                color = LoginTheme.actionTint,
                fontSize = 15.sp,
            )
            Text(
                context.t("privacy_consent_privacy_link"),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { openUrl(context.t("privacy_consent_privacy_url")) }
                    .testTag("privacy_consent_privacy_link"),
                color = LoginTheme.actionTint,
                fontSize = 15.sp,
            )
        }
        Button(
            onClick = onAgree,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LoginTheme.actionTint,
                contentColor = LoginTheme.buttonTextOnPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .height(48.dp)
                .testTag("privacy_consent_agree"),
        ) {
            Text(context.t("privacy_consent_agree"), fontSize = 16.sp)
        }
        Text(
            context.t("privacy_consent_disagree"),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .heightIn(min = 44.dp)
                .clickable(onClick = onDisagree)
                .testTag("privacy_consent_disagree"),
            color = LoginTheme.auxiliaryText,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}
