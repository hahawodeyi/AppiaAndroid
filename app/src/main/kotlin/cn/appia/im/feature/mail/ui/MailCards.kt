package cn.appia.im.feature.mail.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.mail.MailDraft
import cn.appia.im.core.mail.MailMessage
import cn.appia.im.core.theme.Colors
import cn.appia.im.feature.mail.MailChannelViewModel.Companion.mailIsoMillis
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 邮件卡片（RN MailMessageCard + buildMailCardData.ts 移植）：
 * 标题 + read/unread 标签（蓝未读/灰已读）+ From/Time 两行 + 按钮排
 * （详情 bold 蓝 / 回复 / 全部回复 / 有线程时「查看线程」）。
 * 按钮动作为 T4/T5 路由（detail/reply/replyAll/thread），本任务回调上抛屏侧接线。
 */

/** RN colorMap：mail_read 灰 / mail_unread 蓝；draft 橙。 */
private val MailTagReadColor = Color(0xFF86909C)
private val MailTagUnreadColor = Color(0xFF2878FF)
private val MailTagDraftColor = Color(0xFFFF7D00)

/** buildMailCardData.ts defaultFormatMailAddress：`name <email>` 或裸 email。 */
internal fun formatMailAddress(email: String, name: String?): String =
    if (name != null) "$name <$email>" else email

/** buildMailCardData defaultFormatDateTime：zh-CN `yyyy/MM/dd HH:mm`（本地时区）。 */
internal fun formatMailDateTime(iso: String): String {
    val ms = mailIsoMillis(iso)
    if (ms <= 0) return ""
    return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINESE)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date(ms))
}

/** 卡片按钮（RN MailCardBtn：bold 蓝边框/普通灰边框；loading 转圈）。 */
@Composable
private fun MailCardBtn(
    text: String,
    bold: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val border = if (bold) BorderStroke(1.dp, Color(0xFF2878FF)) else BorderStroke(1.dp, Color(0xFFDCDCDC))
    Row(
        modifier
            .height(28.dp)
            .background(Colors.bgBase, RoundedCornerShape(4.dp))
            .border(border, RoundedCornerShape(4.dp))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp, color = Colors.primary)
        } else {
            Text(text, fontSize = 14.sp, color = if (bold) Colors.primary else Color(0x99000000))
        }
    }
}

/** 消息卡片（RN MailMessageCard：标题+标签 / From/Time 行 / 按钮排）。 */
@Composable
fun MailMessageCard(
    message: MailMessage,
    onOpenDetail: (String) -> Unit,
    onReply: (String) -> Unit,
    onReplyAll: (String) -> Unit,
    onViewThread: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasThread = message.threadId != null || message.conversationId != null || (message.replyCount ?: 0) > 0
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Colors.bgBase, RoundedCornerShape(8.dp))
            .testTag("qa-mail-message-card"),
    ) {
        // 标题行：主题（空 → (No subject)）+ read/unread 标签
        Row(
            Modifier.fillMaxWidth().clickable { onOpenDetail(message.id) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("qa-mail-card-content"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message.subject.ifBlank { context.t("mail_no_subject") },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (message.read) context.t("mail_tag_read") else context.t("mail_tag_unread"),
                fontSize = 14.sp,
                color = if (message.read) MailTagReadColor else MailTagUnreadColor,
                modifier = Modifier.testTag("qa-mail-card-tag"),
            )
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
            Row {
                Text(context.t("mail_label_from"), fontSize = 14.sp, color = Colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMailAddress(message.from, message.fromName),
                    fontSize = 14.sp,
                    color = Colors.textPrimary,
                )
            }
            Row {
                Text(context.t("mail_label_time"), fontSize = 14.sp, color = Colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                Text(formatMailDateTime(message.receivedAt), fontSize = 14.sp, color = Colors.textPrimary)
            }
        }
        // 按钮排（详情 bold / 回复 / 全部回复 / 线程）
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            HorizontalDivider(color = Colors.borderLight)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MailCardBtn(
                    text = context.t("mail_btn_viewdetail"),
                    bold = true,
                    loading = false,
                    modifier = Modifier.weight(1f).testTag("qa-mail-card-btn"),
                ) { onOpenDetail(message.id) }
                MailCardBtn(text = context.t("mail_btn_reply"), bold = false, loading = false, modifier = Modifier.weight(1f)) {
                    onReply(message.id)
                }
                MailCardBtn(text = context.t("mail_btn_replyall"), bold = false, loading = false, modifier = Modifier.weight(1f)) {
                    onReplyAll(message.id)
                }
                if (hasThread && onViewThread != null) {
                    MailCardBtn(text = context.t("mail_view_thread"), bold = false, loading = false, modifier = Modifier.weight(1f)) {
                        onViewThread(message.id)
                    }
                }
            }
        }
    }
}

/** 草稿卡片（RN MailDraftCard + buildDraftCardData.ts：标题+Draft 橙标 / To/Time 行 / 编辑+删除）。 */
@Composable
fun MailDraftCard(
    draft: MailDraft,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Colors.bgBase, RoundedCornerShape(8.dp))
            .testTag("qa-mail-draft-card"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                draft.subject.ifBlank { context.t("mail_no_subject") },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Colors.textPrimary,
                modifier = Modifier.weight(1f).testTag("qa-mail-draft-card-header"),
            )
            Spacer(Modifier.width(10.dp))
            Text(context.t("mail_tag_draft"), fontSize = 14.sp, color = MailTagDraftColor)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
            Row {
                Text(context.t("mail_label_to"), fontSize = 14.sp, color = Colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    draft.to.joinToString(", ").ifEmpty { context.t("mail_empty_recipients") },
                    fontSize = 14.sp,
                    color = Colors.textPrimary,
                )
            }
            Row {
                Text(context.t("mail_label_time"), fontSize = 14.sp, color = Colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                Text(formatMailDateTime(draft.updatedAt), fontSize = 14.sp, color = Colors.textPrimary)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            HorizontalDivider(color = Colors.borderLight)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MailCardBtn(
                    text = context.t("mail_btn_editdraft"),
                    bold = true,
                    loading = false,
                    modifier = Modifier.weight(1f).testTag("qa-mail-draft-card-btn"),
                ) { onEdit(draft.id) }
                MailCardBtn(text = context.t("mail_delete"), bold = false, loading = false, modifier = Modifier.weight(1f)) {
                    onDelete(draft.id)
                }
            }
        }
    }
}
