package cn.appia.im.feature.chatlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chatlist.chatListActivityMillis
import cn.appia.im.feature.chatlist.formatRoomListTime
import cn.appia.im.feature.chatlist.tunreadLength

// RN MailChannelItem/styles.ts 硬编码色（#2878FF 恰为 Colors.primary，保持字面量同源注释）
private val MailBlue = Color(0xFF2878FF)
private val Hairline = Color(0xFFE5E5E5)

/**
 * 虚拟邮件行（RN components/MailChannelItem/index.tsx 移植，减滑动手势——T6 接 MailBadgeStore 后再议）：
 * - 头像：48dp 蓝底圆 + 白色信封字形（RN EmailIcon）
 * - 首行：mail_channel_title（未读加粗，RoomItemTitle 同语义）+ 相对时间
 * - 二行：预览副题（lastMessage = `{fromName}：{subject}`）+ 未读 pill（16/24/28dp 宽、>99 显 99+）
 * - 置顶 `f` 叠金色星标（RN isChatPinned → shadowBackGround + StarIcon 等义位）
 */
@Composable
fun MailChannelRow(
    chat: ChatEntity,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val unread = chat.unread.toInt()
    val highlighted = chat.alert || tunreadLength(chat.tunread) > 0
    val title = context.t("mail_channel_title")
    val date = formatRoomListTime(chatListActivityMillis(chat).toLong()) { key, args ->
        args.entries.fold(context.t(key)) { acc, (k, v) -> acc.replace("{{$k}}", v) }
    }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 75.dp)
            .clickable(onClick = onPress)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MailBlue),
                contentAlignment = Alignment.Center,
            ) {
                // 信封字形占位（RN EmailIcon；无 icons-extended 依赖，字形等义）
                Text("✉", color = Color.White, fontSize = 20.sp)
            }
            if (chat.f) {
                Text("★", Modifier.align(Alignment.TopEnd), color = Color(0xFFFFBB00), fontSize = 14.sp)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f, fill = false),
                    color = colors.titleText,
                    fontSize = 18.sp,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.weight(1f))
                if (date.isNotEmpty()) {
                    Text(
                        date,
                        color = colors.auxiliaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chat.last_message.orEmpty(),
                    Modifier.weight(1f),
                    color = colors.auxiliaryText,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread > 0) {
                    UnreadPill(unread)
                }
            }
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = Hairline,
        modifier = Modifier.padding(start = 14.dp + 58.dp, end = 16.dp),
    )
}

/** RN unreadPill（MailChannelItem/styles.ts）：高 16 圆角 8，宽三档 16/24/28，>99 显 `99+`。 */
@Composable
private fun UnreadPill(unread: Int) {
    val width = when {
        unread > 99 -> 28
        unread > 9 -> 24
        else -> 16
    }
    Box(
        Modifier
            .padding(start = 5.dp, end = 10.dp)
            .size(width = width.dp, height = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MailBlue),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (unread > 99) "99+" else unread.toString(),
            color = Color.White,
            fontSize = 10.sp,
        )
    }
}
