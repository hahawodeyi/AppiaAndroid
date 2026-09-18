package cn.appia.im.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors

/**
 * RN useRoomUnreadBanner :24：`Boolean(lastUnreadMsgId && unreadCount >= 10)`——
 * 有 firstUnread 目标且未读数达 10 才显示横幅。
 */
internal fun unreadBannerVisible(lastUnreadMsgId: String?, unreadCount: Int): Boolean =
    !lastUnreadMsgId.isNullOrEmpty() && unreadCount >= 10

/**
 * 未读横幅（RN components/UnreadMessagesBanner）：顶部右上角 pill「↓ N条新消息」，
 * 颜色逐字（图标 #2878FF / 文字 #4E5969 / 边框 #CECECE）。显示与消失（viewability/点击）
 * 由 RoomScreen 裁定，本组件只渲染。
 */
@Composable
fun UnreadBanner(
    unreadCount: Int,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(LocalAppiaColors.current.backgroundColor)
            .border(1.dp, Color(0xFFCECECE), shape)
            .clickable(onClick = onPress)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("qa-unread-messages-banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("↓", color = Color(0xFF2878FF), fontSize = 12.sp)
        Text(
            "$unreadCount" + context.t("room_unreadmessagessuffix"),
            color = Color(0xFF4E5969),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}
