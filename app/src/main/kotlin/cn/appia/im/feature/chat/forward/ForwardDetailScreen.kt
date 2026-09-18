package cn.appia.im.feature.chat.forward

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.core.util.isSameCalendarDay
import cn.appia.im.feature.chat.ui.AttachmentNav
import cn.appia.im.feature.chat.ui.MessageRow
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.chat.ui.DateSeparator

/**
 * 合并转发详情（RN screens/ForwardMessageScreen 同构）：msgData.messages 映射为
 * MessageEntity（[mapForwardMessagesToEntities]）后**复用 M2 普通消息行渲染**（MessageRow）+
 * 日期分隔（与上一条不同历日即插，首条必有——RN :32-50 同序；列表自旧到新正排，不反转）。
 * msgData 解析失败 → `message_formaterror`。
 */
@Composable
fun ForwardDetailScreen(
    msgDataJson: String,
    title: String,
    currentUserId: String?,
    currentUsername: String?,
    serverUrl: String,
    token: String?,
    onAttachmentNav: (AttachmentNav) -> Unit = {},
    onOpenForwardMerge: (msgData: String, title: String) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val rows = remember(msgDataJson) {
        parseForwardMsgData(msgDataJson)?.let { mapForwardMessagesToEntities(it.messages, it.originRoomRid) }
            .orEmpty()
    }
    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = title, onBack = onBack)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("message_formaterror"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("qa-forward-msg-error"),
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("qa-forward-message-list"),
        ) {
            itemsIndexed(rows, key = { _, item -> item._id }, contentType = { _, _ -> "msg" }) { index, item ->
                val previous = rows.getOrNull(index - 1)
                if (previous == null || !isSameCalendarDay(previous.ts.toLong(), item.ts.toLong())) {
                    DateSeparator(item.ts.toLong())
                }
                MessageRow(
                    message = item,
                    currentUserId = currentUserId,
                    currentUsername = currentUsername,
                    serverUrl = serverUrl,
                    token = token,
                    onResend = {},
                    onAttachmentNav = onAttachmentNav,
                    onOpenForwardMerge = onOpenForwardMerge,
                )
            }
        }
    }
}
