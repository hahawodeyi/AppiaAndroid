package cn.appia.im.feature.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import cn.appia.im.core.i18n.t
import cn.appia.im.core.media.AttachmentUrlFormatter
import cn.appia.im.core.network.api.TodoAttachment
import cn.appia.im.core.network.api.TodoItem
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.DocPreviewParams
import cn.appia.im.feature.chat.ui.ViewerImage
import cn.appia.im.feature.chat.ui.buildDocPreviewParamsFromFileLink
import cn.appia.im.feature.chat.ui.buildViewerImage
import cn.appia.im.feature.chat.ui.getFileInfo
import cn.appia.im.feature.chat.ui.parseHexColor
import cn.appia.im.feature.todo.formatUtcYmdHm
import cn.appia.im.feature.todo.stripMarkdownLite
import cn.appia.im.feature.todo.todoAttachmentImage
import cn.appia.im.feature.todo.todoBadgeText

// RN TodoListScreen/styles.ts 硬编码色（不走主题色板，RN 同值）
private val TagHighBg = Color(0xFFFDECEE)
private val TagHighText = Color(0xFFD93143)
private val TagDefaultBg = Color(0xFFF2F3F5)
private val TagDefaultText = Color(0xFF4E5969)
private val OvertimeRed = Color(0xFFD93143)
private val ReminderLink = Color(0xFF2878FF)
private val FileIconText = Color.White

/** 会话行头像/房间头待办角标（RoomItemTodoBadge/index.tsx：红底白字，>99 显 `99+`，≤0 不渲染）。 */
@Composable
fun TodoBadge(todoCount: Int, small: Boolean = false, modifier: Modifier = Modifier) {
    val text = todoBadgeText(todoCount) ?: return
    val size = if (small) 16.dp else 18.dp
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE34D59))
            .testTag("qa-todo-badge"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = if (small) 10.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 待办卡片（RN TodoListScreen/TodoListCard.tsx 逐块）：高优/普通 tag → 标题（stripMarkdownLite，
 * 最多 2 行）→ 附件（图片缩略行 / 文件图标行，仅取第一条）→ 房间名 / 创建时间 / 提醒时间（超时红）
 * → 操作行（完成 + 去处理）。附件点击由屏侧给回调（参数已带鉴权，见 [todoCardViewerImage]/
 * [todoCardDocPreview]）。
 */
@Composable
fun TodoCard(
    item: TodoItem,
    roomDisplayName: String,
    disabled: Boolean,
    onComplete: () -> Unit,
    onGotoSession: () -> Unit,
    onOpenReminderPicker: () -> Unit,
    onOpenImage: (List<ViewerImage>, Int) -> Unit,
    onOpenDoc: (DocPreviewParams) -> Unit,
    userId: String,
    token: String,
    serverUrl: String,
) {
    val context = LocalContext.current
    val colors = LocalAppiaColors.current
    val isHighTodo = item.type == "h"
    val title = stripMarkdownLite(item.title.orEmpty())

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.chatComponentBackground)
            .padding(12.dp)
            .testTag("qa-todo-card"),
    ) {
        // 高优 tag（RN :78-84）
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isHighTodo) TagHighBg else TagDefaultBg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                context.t(if (isHighTodo) "todo_hightag" else "todo_defaulttag"),
                color = if (isHighTodo) TagHighText else TagDefaultText,
                fontSize = 12.sp,
            )
        }

        if (title.isNotEmpty()) {
            Text(
                title,
                color = colors.titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        TodoCardAttachment(item, disabled, userId, token, serverUrl, onOpenImage, onOpenDoc)

        TodoMetaRow(context.t("todo_roomname"), roomDisplayName, singleLine = true)
        TodoMetaRow(context.t("todo_createtime"), formatUtcYmdHm(item.createdAt, "/"))

        // 提醒时间（RN :108-135）：有值 → 时间（isOvertime 红）+ 修改链接；无值 → 设置提醒行
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(context.t("todo_remindertime"), color = colors.auxiliaryText, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            if (item.reminderTime != null) {
                Text(
                    formatUtcYmdHm(item.reminderTime, "/"),
                    color = if (item.isOvertime == true) OvertimeRed else colors.bodyText,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    context.t("todo_changereminder"),
                    color = ReminderLink,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(enabled = !disabled, onClick = onOpenReminderPicker)
                        .testTag("qa-todo-change-reminder"),
                )
            } else {
                Text(
                    "⏰ ${context.t("todo_setreminder")}",
                    color = Color(0xFF86909C),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(enabled = !disabled, onClick = onOpenReminderPicker)
                        .testTag("qa-todo-set-reminder"),
                )
            }
        }

        // 操作行（RN :137-153）：完成 + 去处理
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TodoCardAction(context.t("todo_finish"), disabled, "qa-todo-complete", onComplete)
            Spacer(Modifier.width(12.dp))
            TodoCardAction(context.t("todo_gotosession"), disabled, "qa-todo-goto", onGotoSession)
        }
    }
}

@Composable
private fun TodoCardAction(label: String, disabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = LocalAppiaColors.current
    Text(
        label,
        color = if (disabled) colors.auxiliaryText else colors.primary,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.messageboxBackground)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag(tag),
    )
}

@Composable
private fun TodoMetaRow(label: String, value: String, singleLine: Boolean = false) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.auxiliaryText, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = colors.bodyText,
            fontSize = 13.sp,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

/**
 * 卡片附件（RN :43-74 只取第一条）：图片 → 缩略行（点击进图片预览）；有 title_link → 文件
 * 图标行（getFileInfo 色块 + 名称）；其余不渲染。
 */
@Composable
private fun TodoCardAttachment(
    item: TodoItem,
    disabled: Boolean,
    userId: String,
    token: String,
    serverUrl: String,
    onOpenImage: (List<ViewerImage>, Int) -> Unit,
    onOpenDoc: (DocPreviewParams) -> Unit,
) {
    val att = item.attachments?.firstOrNull() ?: return
    val parsed = todoAttachmentImage(att)
    if (parsed != null) {
        val viewer = todoCardViewerImage(att, userId, token, serverUrl) ?: return
        AsyncImage(
            model = viewer.url,
            contentDescription = att.title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .padding(top = 8.dp)
                .size(width = 200.dp, height = 150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFE5E5E5))
                .clickable(enabled = !disabled) { onOpenImage(listOf(viewer), 0) }
                .testTag("qa-todo-attachment-image"),
        )
        return
    }
    if (att.titleLink.isNullOrEmpty()) return
    val fileInfo = getFileInfo(att.title?.ifEmpty { null } ?: att.titleLink.orEmpty())
    Row(
        Modifier
            .padding(top = 8.dp)
            .clickable(enabled = !disabled) { todoCardDocPreview(att, userId, token, serverUrl)?.let(onOpenDoc) }
            .testTag("qa-todo-attachment-file"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(parseHexColor(fileInfo.color) ?: Color(0xFFC7DADD)),
            contentAlignment = Alignment.Center,
        ) {
            Text(fileInfo.label, color = FileIconText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            att.title.orEmpty(),
            color = LocalAppiaColors.current.bodyText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 附件 → 图片预览条目（RN mapTodoAttachmentToIAttachment → attachmentToMediaItem 同链）：url 空 → null。 */
fun todoCardViewerImage(att: TodoAttachment, userId: String, token: String, server: String): ViewerImage? {
    val parsed = todoAttachmentImage(att) ?: return null
    return buildViewerImage(parsed, userId, token, server)
}

/** 文件附件 → 文档预览参数（RN openTodoAttachment → buildDocPreviewParamsFromFileLink 同参）：无链接 → null。 */
fun todoCardDocPreview(att: TodoAttachment, userId: String, token: String, server: String): DocPreviewParams? {
    if (att.titleLink.isNullOrEmpty()) return null
    return buildDocPreviewParamsFromFileLink(
        title = att.title?.trim().orEmpty(),
        fileLink = att.titleLink,
        fileUrl = null,
        userId = userId,
        token = token,
        server = server,
    )
}

/**
 * 提醒时间选择（RN TodoReminderPicker：react-native-date-picker modal datetime，minimumDate=now）。
 * Compose 无单步 datetime：M3 DatePicker → TimePicker 两步，确认合并成本地时区 epoch millis
 * （过去时间拒提在屏侧 onConfirm 判定 + TodoActions.setReminder 双保险，RN :79-82 同位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoReminderPicker(
    open: Boolean,
    initialMillis: Long,
    onConfirm: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    if (!open) return
    var pickedDate by remember { mutableStateOf<Long?>(null) }
    if (pickedDate == null) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = onCancel,
            confirmButton = {
                Text(
                    LocalContext.current.t("todo_confirm"),
                    color = LocalAppiaColors.current.primary,
                    modifier = Modifier
                        .clickable { pickedDate = dateState.selectedDateMillis }
                        .padding(8.dp)
                        .testTag("qa-todo-picker-date-confirm"),
                )
            },
            dismissButton = {
                Text(
                    LocalContext.current.t("todo_cancel"),
                    color = LocalAppiaColors.current.auxiliaryText,
                    modifier = Modifier.clickable(onClick = onCancel).padding(8.dp),
                )
            },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        // minSdk 24 无 java.time：本地日历拆/合时刻（TimeFormats.kt:17 同裁定）
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = pickedDate ?: return }
        val timeState = rememberTimePickerState(
            initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(java.util.Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(LocalContext.current.t("todo_setremindertitle"), textAlign = TextAlign.Center) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                Text(
                    LocalContext.current.t("todo_confirm"),
                    color = LocalAppiaColors.current.primary,
                    modifier = Modifier
                        .clickable {
                            val base = java.util.Calendar.getInstance().apply { timeInMillis = pickedDate ?: return@clickable }
                            val millis = (base.clone() as java.util.Calendar).apply {
                                set(java.util.Calendar.HOUR_OF_DAY, timeState.hour)
                                set(java.util.Calendar.MINUTE, timeState.minute)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            onConfirm(millis)
                        }
                        .padding(8.dp)
                        .testTag("qa-todo-picker-confirm"),
                )
            },
            dismissButton = {
                Text(
                    LocalContext.current.t("todo_cancel"),
                    color = LocalAppiaColors.current.auxiliaryText,
                    modifier = Modifier.clickable(onClick = onCancel).padding(8.dp),
                )
            },
        )
    }
}

/** 初始 picker 值：有提醒时间用之，否则当前（RN new Date(pickerItem.reminderTime) ?? new Date）。 */
fun todoPickerInitialMillis(item: TodoItem?, nowMs: Long = System.currentTimeMillis()): Long =
    item?.reminderTime?.let { cn.appia.im.domain.chat.ChatMerger.parseIsoMillis(it)?.toLong() } ?: nowMs

/** 屏侧拒提判定（RN :79-82 `date <= new Date()` → todo_reminderMustBeFuture）。 */
fun isReminderInFuture(millis: Long, nowMs: Long = System.currentTimeMillis()): Boolean = millis > nowMs