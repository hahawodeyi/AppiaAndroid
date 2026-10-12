package cn.appia.im.feature.meeting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import cn.appia.im.core.chat.RoomAnnouncement
import cn.appia.im.core.chat.RoomAnnouncementFile
import cn.appia.im.core.chat.parseRoomAnnouncements
import cn.appia.im.core.database.entity.ChatEntity
import cn.appia.im.core.i18n.t
import cn.appia.im.core.meeting.SummaryFile
import cn.appia.im.core.meeting.meetingSummary
import cn.appia.im.core.messaging.resolveMdFromMsgFields
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.InlineEnv
import cn.appia.im.feature.chat.ui.MessageBody
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.meeting.buildMeetingMinutesBody

/**
 * 会议纪要详情（RN screens/MeetingSummaryDetailScreen/index.tsx 全结构）——纯本地渲染：
 * 数据 = 房间公告 `meeting` 字段（IMeetingSummary：topic/timeBegin/place/host/participant/content
 * blocks/files，研究 §2.4），无独立接口。进页 onRefresh（RN useFocusEffect :40-53 rooms.info 刷新）+
 * hydrated 门（:60-65 刷新完仍找不到 → toast meeting_summaryNotFound + 返回）。
 * meta 行（时间/地点/主持/参与/缺席/列席/抄送）→ 纪要正文（buildMeetingMinutesBody → 既有 M3 markdown
 * 渲染链，MeetingMinutesMarkdown 同位）→ 附件行（summary.files + 公告嵌入 files）→ [onOpenFile]。
 */
@Composable
fun MeetingSummaryDetailScreen(
    rid: String,
    announcementId: String,
    chat: ChatEntity?,
    serverUrl: String,
    userId: String,
    token: String,
    onBack: () -> Unit,
    onRefresh: suspend () -> Unit = {},
    onOpenFile: (RoomAnnouncementFile) -> Unit = {},
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var hydrated by remember { mutableStateOf(false) }

    LaunchedEffect(rid) {
        runCatching { onRefresh() }
        hydrated = true
    }

    val item = remember(chat?.announcement, chat?.announcements, announcementId) {
        parseRoomAnnouncements(chat?.announcement, chat?.announcements).find { it.id == announcementId }
    }

    // RN :60-65：刷新完仍无此公告 → toast + goBack
    LaunchedEffect(hydrated, item) {
        if (hydrated && item == null) {
            Toast.makeText(context, context.t("meeting_summarynotfound"), Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(title = context.t("meeting_summarydetailtitle"), onBack = onBack)
        if (!hydrated || item == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                CircularProgressIndicator(color = colors.primary)
            }
            return@Column
        }
        renderDetail(item, onOpenFile, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun renderDetail(
    item: RoomAnnouncement,
    onOpenFile: (RoomAnnouncementFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val summary = item.meetingSummary()
    val body = remember(item) { buildMeetingMinutesBody(item) }

    // RN formatUsers :25-29：name || username join ', '
    fun formatUsers(users: List<cn.appia.im.core.meeting.SummaryUser>?): String =
        users.orEmpty().map { it.name?.takeIf { n -> n.isNotEmpty() } ?: it.username.orEmpty() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    val timeValue = when {
        !summary?.timeBegin.isNullOrEmpty() && !summary?.timeEnd.isNullOrEmpty() ->
            "${summary!!.timeBegin} — ${summary.timeEnd}"
        else -> summary?.timeBegin?.takeIf { it.isNotEmpty() } ?: summary?.timeEnd.orEmpty()
    }
    val metaRows = listOf(
        "meeting_summarytime" to timeValue,
        "meeting_summaryplace" to (summary?.place.orEmpty()),
        "meeting_summaryhost" to formatUsers(summary?.host),
        "meeting_summaryparticipants" to formatUsers(summary?.participant),
        "meeting_summaryabsentees" to formatUsers(summary?.absentee),
        "meeting_summaryleaders" to formatUsers(summary?.leader),
        "meeting_summarycc" to formatUsers(summary?.cc),
    ).filter { it.second.isNotBlank() }

    // RN :80-87：summary.files（fileName ?? name ?? 'file'）+ 公告嵌入 files，滤无 url
    val files = remember(item) {
        summary?.files.orEmpty().map { f: SummaryFile ->
            RoomAnnouncementFile(
                fileName = f.fileName ?: f.name ?: "file",
                fileUrl = f.fileUrl.orEmpty(),
                fileType = f.type,
            )
        } + item.files
    }.filter { it.fileUrl.isNotEmpty() }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        if (metaRows.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                metaRows.forEach { (key, value) ->
                    Text(context.t(key), color = colors.auxiliaryText, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(value, color = colors.titleText, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (body.isNotEmpty()) {
            // RN MeetingMinutesMarkdown :49-53 同位：M3 markdown 块级渲染链复用
            MessageBody(
                root = resolveMdFromMsgFields(md = null, msg = body),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("meeting-minutes-body"),
                env = InlineEnv(),
            )
        }
        files.forEachIndexed { index, file ->
            Text(
                file.fileName,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(colors.auxiliaryBackground)
                    .clickable { onOpenFile(file) }
                    .padding(12.dp)
                    .testTag("meeting-minutes-file-$index"),
                color = colors.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
