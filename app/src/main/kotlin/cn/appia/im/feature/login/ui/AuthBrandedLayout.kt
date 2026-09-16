package cn.appia.im.feature.login.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** RN loginBrandingStyles.ts LOGIN_THEME 的登录域色值（字段名保持 RN 命名）。 */
internal object LoginTheme {
    val screenBackground = Color(0xFFFFFFFF)
    val titleText = Color(0xFF0D0E12)
    val bodyText = Color(0xFF2F343D)
    val auxiliaryText = Color(0xFF9CA2A8)
    val separator = Color(0xFFCBCBCC)
    val actionTint = Color(0xFF1D74F5)
    val buttonTextOnPrimary = Color(0xFFFFFFFF)
    val statusBarSolid = Color(0xFF1E82F0)
}

/**
 * Auth 域共享品牌布局（对照 RN AuthBrandedLayout.tsx）：
 * 蓝色品牌头（RN headerImageTouchPassthrough 214dp 蓝底）+ 白色欢迎标题 + 白色圆角卡片表单区。
 * 企业码页与登录页共用；`footer` 承载版本行等卡片底部内容。
 * RN 的 `headerAccessory`（AuthHeaderLanguageSwitch 应用内语言切换）未移植：
 * Android 侧 i18n 尚无应用内语言覆盖基建（M0 跟随系统语言），待语言基建任务补齐。
 */
@Composable
fun AuthBrandedLayout(
    title: String,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(LoginTheme.screenBackground)
            .verticalScroll(rememberScrollState())
            .testTag("auth_branded_scroll"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // 品牌蓝头延至屏幕顶（RN 同）：蓝底在 inset padding 之前刷，内容垫 statusBar 安全区——
                // app 边缘到边缘绘制时头部文字不得顶进状态栏（M2-T11 评审 Important 跨屏跟进）
                .background(LoginTheme.statusBarSolid)
                .statusBarsPadding()
                .height(214.dp)
                .padding(horizontal = 35.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            // RN welcome：24sp/32 行高 600 白字，距卡片 52dp
            Text(
                title,
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 52.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(LoginTheme.screenBackground)
                .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
        ) {
            content()
            footer?.invoke(this)
        }
    }
}

/** 卡片底部版本行（RN EnterpriseCodeScreen:174-178 enterpriseVersionRow/Text）。 */
@Composable
internal fun VersionLine(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, fontSize = 12.sp, color = LoginTheme.auxiliaryText)
}
