package cn.appia.im.feature.meeting.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.previewTextFromMeetingItem
import cn.appia.im.core.i18n.t
import cn.appia.im.core.meeting.meetingBooking
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.meeting.MeetingBookingStatus
import cn.appia.im.feature.meeting.meetingBookingCardContent
import cn.appia.im.feature.meeting.meetingBookingStatus
import cn.appia.im.feature.meeting.resolveMeetingJoin
import cn.appia.im.feature.search.interpolate


/**
 * 公告流会议卡片（RN components/MeetingAnnouncementCard + MeetingMinutesCard 移植）。
 * RN 中两张卡只被 RoomMeetingScreen 消费（聊天流无内嵌会议卡渲染点，研究核对）——本层供面板使用。
 */

/** RN i18n+插值解析注入（RoomListTime 管线：context.t(key) + {{x}} 替换）。 */
@Composable
internal fun cardTextResolver(): (key: String, args: Map<String, String>) -> String {
    val context = LocalContext.current
    return { key, args -> interpolate(context.t(key), args) }
}

// RN styles 硬编码（tokens 数值同源）：FontSize xs11/sm12/base14/md15、Spacing xs4/sm8/md12/lg16、Radius xl10。
private val CardRadius = RoundedCornerShape(10.dp)
private val LabelColWidth = 80.dp

/** RN InfoRow（index.tsx:25-46）：定宽 label 列 + 值列。 */
@Composable
private fun InfoRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val colors = LocalAppiaColors.current
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            Modifier.width(LabelColWidth).padding(end = 8.dp),
            color = colors.auxiliaryText,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
        Text(
            value,
            Modifier.weight(1f),
            color = valueColor ?: colors.auxiliaryText,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            maxLines = if (valueColor != null) 2 else 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * RN MeetingAnnouncementCard（index.tsx:48-158）：标题 + 周期/状态 tag + 信息行（时间/与会人/地点/码/链接）+
 * 加入钮（仅 upcoming 且 join 解析非空）+ 取消/编辑操作条（showActions 门）。
 */
@Composable
fun MeetingAnnouncementCard(
    item: RoomAnnouncement,
    nowSec: Long,
    showActions: Boolean,
    serverUrl: String,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onJoin: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val resolve = cardTextResolver()
    val record = item.meetingBooking()
    val status = meetingBookingStatus(item, nowSec)
    val content = meetingBookingCardContent(
        item,
        LocalConfiguration.current.locales[0],
        record,
        resolve,
    )
    val join = resolveMeetingJoin(content.meetUrl, content.meetCode, record?.tencentMeetingId, serverUrl)
    val canJoin = status == MeetingBookingStatus.UPCOMING && join !is cn.appia.im.feature.meeting.MeetingJoinResolution.None
    val isUpcoming = status == MeetingBookingStatus.UPCOMING

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(CardRadius)
            .background(colors.backgroundColor)
            .border(BorderStroke(0.5.dp, colors.borderColor), CardRadius),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    content.title,
                    Modifier.weight(1f),
                    color = colors.titleText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (record?.periodic == true) {
                        CardTag(text = context.t("meeting_periodictag"), active = false)
                    }
                    CardTag(
                        text = context.t(if (isUpcoming) "meeting_statusupcoming" else "meeting_statuspast"),
                        active = isUpcoming,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content.timeLine?.let { InfoRow(context.t("meeting_starttime"), it) }
                content.attendeeLine?.let { InfoRow(context.t("meeting_attendees"), it) }
                when {
                    content.locationRoom != null -> InfoRow(
                        context.t("meeting_room"),
                        if (content.isPrivateNetwork) {
                            "${content.locationRoom} (${context.t("meeting_privatenetworktag")})"
                        } else {
                            content.locationRoom!!
                        },
                    )
                    content.isPrivateNetwork -> InfoRow(context.t("meeting_room"), context.t("meeting_privatenetworktag"))
                }
                content.meetCode?.let { InfoRow(context.t("meeting_code"), it) }
                content.meetUrl?.let { InfoRow(context.t("meeting_url"), it, valueColor = if (canJoin) colors.primary else null) }
            }
            if (canJoin) {
                Spacer(Modifier.height(12.dp))
                Text(
                    context.t("meeting_join"),
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.primary)
                        .clickable(onClick = onJoin)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("meeting-join-${item.id ?: "unknown"}"),
                    color = colors.backgroundColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (showActions) {
            HorizontalDivider(thickness = 0.5.dp, color = colors.borderColor)
            Row(Modifier.fillMaxWidth().height(40.dp)) {
                Text(
                    context.t("meeting_cancel"),
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = onDelete)
                        .testTag("meeting-cancel-${item.id ?: "unknown"}"),
                    color = colors.dangerColor,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    context.t("meeting_edit"),
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClick = onEdit)
                        .testTag("meeting-edit-${item.id ?: "unknown"}"),
                    color = colors.primary,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CardTag(text: String, active: Boolean) {
    val colors = LocalAppiaColors.current
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) colors.primary.copy(alpha = 0.12f) else colors.auxiliaryBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (active) colors.primary else colors.auxiliaryText,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** RN MeetingMinutesCard（index.tsx:14-29）：标题行 + chevron，点击进纪要详情。 */
@Composable
fun MeetingMinutesCard(item: RoomAnnouncement, onPress: () -> Unit) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(CardRadius)
            .background(colors.backgroundColor)
            .clickable(onClick = onPress)
            .padding(16.dp)
            .testTag("meeting-minutes-${item.id ?: "unknown"}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            previewTextFromMeetingItem(item),
            Modifier.weight(1f),
            color = colors.titleText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text("›", Modifier.padding(start = 8.dp), color = colors.auxiliaryText, fontSize = 20.sp)
    }
}
