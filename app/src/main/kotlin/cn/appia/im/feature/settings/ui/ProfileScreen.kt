package cn.appia.im.feature.settings.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.AuthSession
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.Colors
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.presenceBadge
import coil3.compose.AsyncImage

/**
 * 我的资料页（M5-T9，RN screens/ProfileScreen/index.tsx 逐行对照——**只读基线**）：
 * 头像行（DirectAvatar 60dp + presence 绿点）/ 姓名行（name 只读——RN 无编辑端点，
 * plan Global Constraint 不发明）/ 用户名行 / 我的二维码行（→ MyCard——M4 临时顶栏入口
 * 本任务移除，此为 RN 唯一正入口的迁正落点）/ 邮箱行（非空才渲染，join('\n')）/ 设置行。
 */
@Composable
fun ProfileScreen(
    session: AuthSession?,
    serverUrl: String,
    token: String?,
    onBack: () -> Unit,
    onOpenMyCard: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val density = LocalDensity.current
    val user = session?.user
    val username = user?.username?.trim().orEmpty()
    val name = user?.name?.trim().orEmpty()
    val displayName = name.ifEmpty { username }
    val emails = user?.emails.orEmpty()

    val avatarUrl = if (serverUrl.isBlank() || username.isBlank()) null else buildString {
        append(serverUrl.trimEnd('/')).append("/avatar/").append(username)
        append("?version=1&format=png&size=").append(with(density) { 60.dp.roundToPx() })
        if (!user?.id.isNullOrEmpty() && !token.isNullOrEmpty()) {
            append("&rc_token=").append(token).append("&rc_uid=").append(user!!.id)
        }
    }
    val presence = presenceBadge(
        userId = user?.id,
        username = username.takeIf { it.isNotEmpty() },
        fallbackStatus = null,
        avatarSize = 60.dp,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.backgroundColor)
            .testTag("qa-profile-screen"),
    ) {
        SettingsHeader(title = context.t("profile_title"), onBack = onBack, tag = "qa-profile-header")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // 头像行（RN avatarRow）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    context.t("profile_row_avatar"),
                    color = colors.titleText,
                    fontSize = 16.sp,
                    modifier = Modifier.testTag("qa-profile-row-avatar"),
                )
                Box {
                    Box(
                        Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName.take(1).ifEmpty { "?" },
                            color = colors.infoText,
                            fontSize = 22.sp,
                        )
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    presence()
                }
            }
            ProfileSeparator()

            ProfileTextRow(context.t("profile_row_name"), name)
            ProfileSeparator()
            ProfileTextRow(context.t("profile_row_username"), username)
            ProfileSeparator()
            ProfileNavRow(context.t("profile_row_qrcode"), "qa-profile-row-qrcode", onOpenMyCard)
            ProfileSeparator()

            if (emails.isNotEmpty()) {
                ProfileTextRow(context.t("memberprofile_email"), emails.joinToString("\n") { it.address })
                ProfileSeparator()
            }

            ProfileNavRow(context.t("settings_title"), "qa-profile-row-settings", onOpenSettings)
            ProfileSeparator()
        }
    }
}

@Composable
private fun ProfileTextRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalAppiaColors.current.titleText, fontSize = 16.sp)
        Text(
            value,
            color = LocalAppiaColors.current.auxiliaryText,
            fontSize = 15.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileNavRow(label: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalAppiaColors.current.titleText, fontSize = 16.sp)
        Text("›", color = LocalAppiaColors.current.auxiliaryText, fontSize = 18.sp)
    }
}

@Composable
private fun ProfileSeparator() {
    HorizontalDivider(
        Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = Colors.border,
    )
}
