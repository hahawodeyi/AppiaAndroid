package cn.appia.im.feature.meeting.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.parseMeetingPanelAnnouncements
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.meeting.MeetingBookingGroups
import cn.appia.im.feature.meeting.MeetingDeleteScope
import cn.appia.im.feature.meeting.MeetingJoinResolution
import cn.appia.im.feature.meeting.groupMeetingBookingsByStatus
import cn.appia.im.core.meeting.meetingBooking
import cn.appia.im.feature.meeting.resolveMeetingJoin
import cn.appia.im.feature.meeting.sortMeetingMinutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 房间会议面板（RN screens/RoomMeetingScreen/index.tsx 双 tab 全结构）：
 * - 预订 tab：endSec>now 分组（upcoming 升序节在前 / past 降序节在后，meetingBookingDisplay.ts:19,60,151），
 *   卡片 join/edit/delete。join 仅 upcoming 且解析非空（坑 9：meetUrl 优先，否则 meetCode??:tencentMeetingId；
 *   AntMeeting 形状 → [onJoinAntMeeting] seam（T7 原生会议室，未接前降级 toast）；腾讯记录 → 系统外跳
 *   wemeet:// 或 https）。delete 走 postSaveRoomSettings roomAnnouncementData type:delete（周期单次走
 *   cancelPeriodicSingleMeeting，[MeetingDeleteScope]）；周期记录弹单次/整串三钮确认（promptMeetingDelete.ts）。
 * - 纪要 tab：ymd/updateTime 倒序卡片 → [onOpenMinutes]（详情纯本地渲染）。
 * - 右上 +（canEdit 且预订 tab，RN :92-111）→ [onCreateMeeting]（T4 表单 seam，T5 接线）。
 * - 进页 [onRefresh]（RN useFocusEffect :52-56 → refreshRoomAnnouncementsFromApi rooms.info 刷新公告两列；
 *   AA 同链路 = M4 RoomInfoActions.refreshRoomAnnouncements，公告屏/信息页共用，面板数据随之新鲜）。
 */
enum class MeetingTab(val labelKey: String) { BOOKINGS("meeting_tabbookings"), MINUTES("meeting_tabminutes") }

@Composable
fun RoomMeetingScreen(
    rid: String,
    roomType: String,
    chat: ChatEntity?,
    canEdit: Boolean,
    serverUrl: String,
    onBack: () -> Unit,
    onRefresh: suspend () -> Unit = {},
    onDeleteBooking: suspend (announcementId: String, scope: MeetingDeleteScope) -> Unit = { _, _ -> },
    onCreateMeeting: () -> Unit = {},
    onEditMeeting: (RoomAnnouncement) -> Unit = {},
    onJoinAntMeeting: (url: String) -> Unit = {},
    onOpenMinutes: (announcementId: String) -> Unit = {},
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // RN useFocusEffect（index.tsx:52-56）：进页 rooms.info 刷新公告两列（失败静默，本地数据先行）
    LaunchedEffect(rid) { runCatching { onRefresh() } }

    val panelItems = remember(chat?.announcement, chat?.announcements) {
        parseMeetingPanelAnnouncements(chat?.announcement, chat?.announcements)
    }
    val nowSec = remember(panelItems) { System.currentTimeMillis() / 1000 }
    val groups = remember(panelItems, nowSec) { groupMeetingBookingsByStatus(panelItems, nowSec) }
    val minutes = remember(panelItems) { sortMeetingMinutes(panelItems) }

    var tab by remember { mutableStateOf(MeetingTab.BOOKINGS) }
    var deleteTarget by remember { mutableStateOf<RoomAnnouncement?>(null) }

    fun onJoin(item: RoomAnnouncement) {
        val record = item.meetingBooking()
        when (val join = resolveMeetingJoin(record?.meetUrl, record?.meetCode, record?.tencentMeetingId, serverUrl)) {
            is MeetingJoinResolution.AntMeeting -> onJoinAntMeeting(join.url)
            is MeetingJoinResolution.External -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(join.uri)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, context.t("feature_not_implemented"), Toast.LENGTH_SHORT).show()
                }
            }
            MeetingJoinResolution.None ->
                // RN :150-154：resolve 失败 → feature_not_implemented toast
                Toast.makeText(context, context.t("feature_not_implemented"), Toast.LENGTH_SHORT).show()
        }
    }

    fun onDelete(item: RoomAnnouncement) {
        if (item.id == null) return
        deleteTarget = item
    }

    // RN handleDeleteMeeting :113-133：执行删除 → 分 scope toast → 刷新；失败 toast
    fun runDelete(target: RoomAnnouncement, scopeKind: MeetingDeleteScope) {
        val id = target.id ?: return
        deleteTarget = null
        scope.launch {
            try {
                onDeleteBooking(id, scopeKind)
                val key = when (scopeKind) {
                    MeetingDeleteScope.SINGLE -> "meeting_cancelsinglesuccess"
                    MeetingDeleteScope.SERIES -> "meeting_cancelseriessuccess"
                    MeetingDeleteScope.NORMAL -> "meeting_cancelsuccess"
                }
                Toast.makeText(context, context.t(key), Toast.LENGTH_SHORT).show()
                runCatching { onRefresh() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, context.t("meeting_cancelfailed"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(
            title = context.t("roominfo_meeting"),
            onBack = onBack,
            headerAction = if (canEdit && tab == MeetingTab.BOOKINGS) {
                {
                    // RN headerRight PlusIcon（:97-109，testID meeting-create）
                    Text(
                        "+",
                        Modifier
                            .clickable(onClick = onCreateMeeting)
                            .padding(horizontal = 16.dp)
                            .testTag("meeting-create"),
                        color = colors.titleText,
                        fontSize = 22.sp,
                    )
                }
            } else {
                null
            },
        )
        MeetingTabBar(tab) { tab = it }

        val bookingsEmpty = groups.upcoming.isEmpty() && groups.past.isEmpty()
        val minutesEmpty = minutes.isEmpty()
        val empty = if (tab == MeetingTab.BOOKINGS) bookingsEmpty else minutesEmpty
        if (empty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    context.t(if (tab == MeetingTab.BOOKINGS) "meeting_listempty" else "meeting_minutesempty"),
                    color = colors.auxiliaryText,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                if (tab == MeetingTab.BOOKINGS) {
                    if (groups.upcoming.isNotEmpty()) {
                        item(key = "header-upcoming") { SectionLabel(context.t("meeting_sectionupcoming")) }
                        itemsIndexed(groups.upcoming, key = { i, _ -> "u-$i" }) { _, item ->
                            BookingCard(item, nowSec, canEdit, serverUrl, onDelete = { onDelete(item) }, onEdit = { onEditMeeting(item) }, onJoin = { onJoin(item) })
                        }
                    }
                    if (groups.past.isNotEmpty()) {
                        item(key = "header-past") { SectionLabel(context.t("meeting_sectionpast")) }
                        itemsIndexed(groups.past, key = { i, _ -> "p-$i" }) { _, item ->
                            BookingCard(item, nowSec, canEdit, serverUrl, onDelete = { onDelete(item) }, onEdit = { onEditMeeting(item) }, onJoin = { onJoin(item) })
                        }
                    }
                } else {
                    itemsIndexed(minutes, key = { i, _ -> "m-$i" }) { _, item ->
                        MeetingMinutesCard(item) { item.id?.let(onOpenMinutes) }
                    }
                }
            }
        }
    }

    // RN promptMeetingDelete（promptMeetingDelete.ts:8-40）：周期三钮（单次/整串），普通两钮
    deleteTarget?.let { target ->
        val periodic = target.meetingBooking()?.periodic == true
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(context.t("meeting_canceltitle")) },
            text = { if (periodic) Text(context.t("meeting_cancelperiodichint")) },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(context.t("cancel")) }
            },
            confirmButton = {
                if (periodic) {
                    Row {
                        TextButton(onClick = { runDelete(target, MeetingDeleteScope.SINGLE) }) {
                            Text(context.t("meeting_cancelthisoccurrence"), color = colors.dangerColor)
                        }
                        TextButton(onClick = { runDelete(target, MeetingDeleteScope.SERIES) }) {
                            Text(context.t("meeting_cancelentireseries"), color = colors.dangerColor)
                        }
                    }
                } else {
                    TextButton(onClick = { runDelete(target, MeetingDeleteScope.NORMAL) }) {
                        Text(context.t("meeting_cancel"), color = colors.dangerColor)
                    }
                }
            },
        )
    }
}

/** RN MeetingTabBar（MeetingTabBar.tsx:18-40）：双 tab 平铺，active 主色加粗 + 底边条。 */
@Composable
private fun MeetingTabBar(value: MeetingTab, onChange: (MeetingTab) -> Unit) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.backgroundColor)
            .testTag("meeting-tabbar"),
    ) {
        MeetingTab.entries.forEach { option ->
            val active = option == value
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onChange(option) }
                    .padding(vertical = 11.dp)
                    .testTag("meeting-tab-${option.name.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    context.t(option.labelKey),
                    color = if (active) colors.primary else colors.auxiliaryText,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .background(if (active) colors.primary else Color.Transparent),
                )
            }
        }
    }
}

/** RN renderSectionHeader（index.tsx:179-184）：节标题小字。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 4.dp, bottom = 4.dp),
        color = LocalAppiaColors.current.auxiliaryText,
        fontSize = 12.sp,
    )
}

@Composable
private fun BookingCard(
    item: RoomAnnouncement,
    nowSec: Long,
    canEdit: Boolean,
    serverUrl: String,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onJoin: () -> Unit,
) {
    MeetingAnnouncementCard(
        item = item,
        nowSec = nowSec,
        showActions = canEdit,
        serverUrl = serverUrl,
        onDelete = onDelete,
        onEdit = onEdit,
        onJoin = onJoin,
    )
}
