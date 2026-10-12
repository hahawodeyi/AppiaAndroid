package cn.appia.im.feature.meeting.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import cn.appia.im.core.i18n.t
import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.formatMeetingDateTime
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.MeetingApi
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.meeting.MeetingJoinResolution
import cn.appia.im.feature.meeting.formatMeetingTimeRangeDisplay
import cn.appia.im.feature.meeting.meetingHomeBookings
import cn.appia.im.feature.meeting.resolveMeetingJoin
import kotlinx.coroutines.CancellationException

/**
 * 会议首页（M8-T5：RN 消息列表「+」菜单 / 我的抽屉「会议」行 → MEETING_HOME_URL H5 的原生最小替代）：
 * meetingRoomList 未来窗口（now → +30d）bookRecords 平铺为 upcoming 预订列表（[meetingHomeBookings]），
 * 右上 + → [onCreateMeeting]（MeetingFormRoute，rid 空 = 无房间会议——RN buildMeetingPayload 校验
 * 不含 rid，表单成员拉取失败静默为空，行为同 RN 会话外预订）。
 * 加入（坑 9 同面板）：resolveMeetingJoin 三分——AntMeeting → [onJoinAntMeeting] seam（M8-T7 原生
 * 会议室已接）、腾讯 → 系统外跳、None → toast。
 */
@Composable
fun MeetingHomeScreen(
    sdk: RocketSdk?,
    serverUrl: String,
    onBack: () -> Unit,
    onCreateMeeting: () -> Unit = {},
    onJoinAntMeeting: (url: String, subject: String?) -> Unit = { _, _ -> },
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val resolve = cardTextResolver()

    var loading by remember { mutableStateOf(true) }
    var bookings by remember { mutableStateOf<List<Meeting>>(emptyList()) }

    // meetingRoomList 未来窗口（RN H5 首页自管列表；AA 线上唯一列表端点，past 不重复取）
    LaunchedEffect(sdk) {
        if (sdk == null) {
            loading = false
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        runCatching {
            MeetingApi.meetingRoomList(sdk, formatMeetingDateTime(now), formatMeetingDateTime(now + 30L * 24 * 60 * 60 * 1000))
        }.onSuccess { rooms ->
            bookings = meetingHomeBookings(rooms, now / 1000)
        }
        loading = false
    }

    fun onJoin(record: Meeting) {
        when (val join = resolveMeetingJoin(record.meetUrl, record.meetCode, record.tencentMeetingId, serverUrl)) {
            is MeetingJoinResolution.AntMeeting -> onJoinAntMeeting(join.url, record.subject)
            is MeetingJoinResolution.External -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(join.uri)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, context.t("feature_not_implemented"), Toast.LENGTH_SHORT).show()
                }
            }
            MeetingJoinResolution.None ->
                Toast.makeText(context, context.t("feature_not_implemented"), Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-meeting-home-screen")) {
        RoomHeader(
            title = context.t("drawer_meeting"),
            onBack = onBack,
            headerAction = {
                // RN 首页创建入口（H5 内按钮；AA 等价放头部 +，同 RoomMeeting 面板样式）
                Text(
                    "+",
                    Modifier
                        .clickable(onClick = onCreateMeeting)
                        .padding(horizontal = 16.dp)
                        .testTag("qa-meeting-home-create"),
                    color = colors.titleText,
                    fontSize = 22.sp,
                )
            },
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.tintColor)
            }
            bookings.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t("meeting_listempty"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().testTag("qa-meeting-home-list")) {
                items(bookings, key = { it.meetingId ?: it.id ?: it.subject ?: it.hashCode().toString() }) { record ->
                    MeetingHomeRow(record, serverUrl, resolve) { onJoin(record) }
                }
            }
        }
    }
}

/** 首页预订行：标题 + 时间区间行 + 加入钮（解析非空才可点，坑 9 同面板门槛）。 */
@Composable
private fun MeetingHomeRow(
    record: Meeting,
    serverUrl: String,
    resolve: (key: String, args: Map<String, String>) -> String,
    onJoin: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val timeLine = formatMeetingTimeRangeDisplay(
        record.startTime,
        record.endTime,
        LocalConfiguration.current.locales[0],
        resolve,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.backgroundColor)
            .border(0.5.dp, colors.borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("qa-meeting-home-row"),
    ) {
        Text(
            record.subject?.trim()?.takeIf { it.isNotEmpty() } ?: resolve("meeting_createTitle", emptyMap()),
            color = colors.titleText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (timeLine.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(timeLine, color = colors.auxiliaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            resolve("meeting_join", emptyMap()),
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.primary)
                .clickable(onClick = onJoin)
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("qa-meeting-home-join"),
            color = colors.backgroundColor,
            fontSize = 13.sp,
        )
    }
}
