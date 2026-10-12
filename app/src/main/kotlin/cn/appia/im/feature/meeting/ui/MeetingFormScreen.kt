package cn.appia.im.feature.meeting.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.database.DatabaseManager
import cn.appia.im.core.i18n.t
import cn.appia.im.core.meeting.Meeting
import cn.appia.im.core.meeting.MeetingFormState
import cn.appia.im.core.meeting.MeetingRoom
import cn.appia.im.core.meeting.MeetingValidationError
import cn.appia.im.core.meeting.MeetingUser
import cn.appia.im.core.meeting.MonthWeekly
import cn.appia.im.core.meeting.RepeatPreset
import cn.appia.im.core.meeting.buildMeetingPayload
import cn.appia.im.core.meeting.formatMeetingDateTime
import cn.appia.im.core.meeting.mapFederatedMembersToMeetingUsers
import cn.appia.im.core.meeting.meetingJson
import cn.appia.im.core.meeting.parseMeetingDateTime
import cn.appia.im.core.meeting.validateMeetingForm
import cn.appia.im.core.network.RocketSdk
import cn.appia.im.core.network.api.MeetingApi
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.meeting.MeetingEditMode
import cn.appia.im.feature.meeting.MeetingSubmitAction
import cn.appia.im.feature.meeting.defaultPeriodicEndDate
import cn.appia.im.feature.meeting.executeMeetingSubmit
import cn.appia.im.feature.meeting.formatMeetingAttendeeSummaryLine
import cn.appia.im.feature.meeting.formatPeriodicEndDate
import cn.appia.im.feature.meeting.initialMeetingFormState
import cn.appia.im.feature.meeting.resolveMeetingSubmitAction
import cn.appia.im.feature.meeting.snapToQuarterHour
import cn.appia.im.feature.todo.ui.TodoReminderPicker
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// RN MeetingFormScreen/styles.ts 硬编码色
private val LabelGray = Color(0xFF86909C)
private val PlaceholderGray = Color(0xFFC9CDD4)
private val SubmitBlue = Color(0xFF1677FF)
private val RowDivider = Color(0xFFE5E6EB)
private val InputDark = Color(0xFF1D2129)

/** 周期预设行（RN getRepeatPresetOptions 的 value/unit/interval + i18n 标签）。 */
private data class PresetRow(val preset: RepeatPreset, val label: String)

/** 选项弹层条目。 */
private data class SheetOption(val value: String, val label: String)

/** 表单态 Saver：meetingJson 编码整串（旋屏/回栈保态；kotlinx roundtrip 已测）。 */
private fun meetingFormStateSaver() = object : androidx.compose.runtime.saveable.Saver<MeetingFormState, String> {
    override fun SaverScope.save(value: MeetingFormState): String =
        meetingJson.encodeToString(MeetingFormState.serializer(), value)

    override fun restore(value: String): MeetingFormState? =
        runCatching { meetingJson.decodeFromString(MeetingFormState.serializer(), value) }.getOrNull()
}

/**
 * 会议预订表单（RN screens/MeetingFormScreen 全字段）：
 * 主题 / 与会人（新建默认拉全房间联邦成员 RN :162-181；点开内嵌选人浮层，确认回写）/ 起止时间
 * （M5-T9 TodoReminderPicker 复用 + 分钟向下取整 15 步进）/ 会议室（meetingRoomList 拉取，名称单选
 * 可空——时间轴占用可视化按 brief 简化）/ 周期预设 0-6（自定义频率/结束条件/月模式）/ createTxMeeting
 * 开关（新建默认 true，关 → meetCode+meetUrl 手动，坑 11）/ desc / meetingMaterials /
 * enableNotification / mediaSetType 私有网络。校验失败 → i18n toast（T2 枚举 rnKey 直查）；
 * 提交走 [executeMeetingSubmit]（create/update-single/update 分派）。
 * 单次编辑（edit-periodic-single）全字段锁定（RN fieldsLocked，仅起止/提交可用）。
 */
@Composable
fun MeetingFormScreen(
    rid: String,
    roomType: String,
    editMeetingJson: String?,
    editMode: String,
    sdk: RocketSdk?,
    dbManager: DatabaseManager?,
    serverUrl: String,
    currentUserName: String?,
    currentUsername: String?,
    onBack: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val editMeeting = remember(editMeetingJson) {
        editMeetingJson?.let { raw -> runCatching { meetingJson.decodeFromString<Meeting>(raw) }.getOrNull() }
    }
    val isEdit = editMeeting != null
    val fieldsLocked = editMode == MeetingEditMode.PERIODIC_SINGLE
    val showPeriodicSection = !isEdit || editMeeting.periodic == true

    // 表单态（rememberSaveable：选人浮层/时间选择往返不丢已填字段，RN useState 存活同义）
    var form by rememberSaveable(
        rid, editMeetingJson, editMode,
        stateSaver = meetingFormStateSaver(),
    ) { mutableStateOf(initialMeetingFormState(rid, editMeeting)) }

    var submitting by remember { mutableStateOf(false) }
    var loadingMembers by remember { mutableStateOf(!isEdit && sdk != null) }
    var rooms by remember { mutableStateOf<List<MeetingRoom>>(emptyList()) }
    var roomPickerOpen by remember { mutableStateOf(false) }
    var memberPickerOpen by remember { mutableStateOf(false) }
    var sheetKind by remember { mutableStateOf<String?>(null) } // repeat/endType/frequencyCount/frequencyUnit
    var pickerKind by remember { mutableStateOf<String?>(null) } // start/end
    var endDatePickerOpen by remember { mutableStateOf(false) }

    val title = when {
        editMode == MeetingEditMode.PERIODIC_SINGLE -> context.t("meeting_editThisOccurrenceTitle")
        editMode == MeetingEditMode.PERIODIC_ALL -> context.t("meeting_editEntireSeriesTitle")
        isEdit -> context.t("meeting_editTitle")
        else -> context.t("meeting_createTitle")
    }

    // 新建默认与会人 = 全房间联邦成员（RN :162-181；失败保空 → 校验提示）
    LaunchedEffect(isEdit, rid, sdk) {
        if (isEdit || sdk == null) return@LaunchedEffect
        val enterprise = dbManager?.let { m ->
            runCatching { m.databaseFor(m.normalizeServer(serverUrl)) }
                .getOrNull()?.settingDao()?.getById("Enterprise_ID")?.value_as_string
        }?.trim()?.takeIf { it.isNotEmpty() } ?: "bitmain"
        loadingMembers = true
        runCatching { MeetingApi.getRoomMembers(sdk, rid) }
            .onSuccess { res -> form = form.copy(users = mapFederatedMembersToMeetingUsers(res.data, enterprise)) }
        loadingMembers = false
    }
    // 会议室列表（RN MeetingRoomSection meetingRoomList：随起止变化重拉）
    LaunchedEffect(sdk, form.startMs, form.endMs) {
        if (sdk == null) return@LaunchedEffect
        rooms = runCatching {
            MeetingApi.meetingRoomList(sdk, formatMeetingDateTime(form.startMs), formatMeetingDateTime(form.endMs))
        }.getOrDefault(emptyList())
    }

    fun resolve(key: String, args: Map<String, String>): String =
        args.entries.fold(context.t(key)) { acc, (k, v) -> acc.replace("{{$k}}", v) }

    val attendeeSummary = formatMeetingAttendeeSummaryLine(form.users, ::resolve)
        .ifEmpty { context.t("meeting_attendeesEmpty") }

    // 周期预设（RN getRepeatPresetOptions：weekday/日号入标签；value '6'=自定义）
    val startCal = Calendar.getInstance().apply { timeInMillis = form.startMs }
    val weekdayKeys = listOf(
        "meeting_weekdaySun", "meeting_weekdayMon", "meeting_weekdayTue", "meeting_weekdayWed",
        "meeting_weekdayThu", "meeting_weekdayFri", "meeting_weekdaySat",
    )
    val weekday = context.t(weekdayKeys[startCal.get(Calendar.DAY_OF_WEEK) - 1])
    val presetRows = listOf(
        PresetRow(RepeatPreset("0"), context.t("meeting_repeatNone")),
        PresetRow(RepeatPreset("1", "DAILY", 1), context.t("meeting_repeatDaily")),
        PresetRow(RepeatPreset("2", "WORKDAY", 1), context.t("meeting_repeatWorkday")),
        PresetRow(RepeatPreset("3", "WEEKLY", 1), context.t("meeting_repeatWeekly").replace("{{day}}", weekday)),
        PresetRow(RepeatPreset("4", "WEEKLY", 2), context.t("meeting_repeatBiWeekly").replace("{{day}}", weekday)),
        PresetRow(
            RepeatPreset("5", "MONTHLY", 1),
            context.t("meeting_repeatMonthly").replace("{{day}}", startCal.get(Calendar.DAY_OF_MONTH).toString()),
        ),
        PresetRow(RepeatPreset("6"), context.t("meeting_repeatCustom")),
    )
    val currentPresetLabel = presetRows.firstOrNull { it.preset.value == form.periodicPreset }?.label
        ?: context.t("meeting_repeatNone")

    fun handleChangePreset(preset: String, option: RepeatPreset) {
        if (preset == "0") {
            form = form.copy(periodicEnabled = false, periodicPreset = "0")
            return
        }
        val defaultEnd = defaultPeriodicEndDate(form.startMs)
        form = if (preset != "6" && option.unit != null && option.interval != null) {
            form.copy(
                periodicEnabled = true,
                periodicPreset = preset,
                periodicInfo = form.periodicInfo.copy(
                    periodicCountType = "TO_DATE",
                    periodicEndDate = defaultEnd,
                    periodicUnit = option.unit,
                    periodicInterval = option.interval,
                ),
            )
        } else {
            form.copy(
                periodicEnabled = true,
                periodicPreset = "6",
                periodicInfo = form.periodicInfo.copy(
                    periodicCountType = "TO_DATE",
                    periodicEndDate = defaultEnd,
                    periodicUnit = "DAILY",
                    periodicInterval = 2,
                ),
            )
        }
    }

    fun submit() {
        if (submitting || sdk == null) return
        val defaultSubject = context.t("meeting_defaultSubject")
            .replace("{{name}}", currentUserName?.takeIf { it.isNotEmpty() } ?: currentUsername.orEmpty())
        val payloadForm = form.copy(subject = form.subject.trim().ifEmpty { defaultSubject })
        val err = validateMeetingForm(payloadForm)
        if (err != null) {
            Toast.makeText(context, context.t(err.rnKey), Toast.LENGTH_SHORT).show()
            return
        }
        submitting = true
        scope.launch {
            try {
                val preset = presetRows.firstOrNull { it.preset.value == form.periodicPreset }?.preset
                val extra = editMeeting.takeIf { isEdit }
                val payload = buildMeetingPayload(payloadForm, extra, preset)
                val action = resolveMeetingSubmitAction(payload, extra, editMode)
                val res = executeMeetingSubmit(sdk, action)
                val msg = if (action is MeetingSubmitAction.Create) {
                    res.message?.takeIf { it.isNotEmpty() } ?: context.t("meeting_submitSuccess")
                } else {
                    context.t("meeting_saveSuccess")
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, context.t("meeting_submitFailed"), Toast.LENGTH_SHORT).show()
            } finally {
                submitting = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor)) {
        RoomHeader(
            title = title,
            onBack = onBack,
            headerAction = {
                if (submitting) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp).size(16.dp))
                } else {
                    Text(
                        if (isEdit) context.t("meeting_save") else context.t("meeting_submit"),
                        color = SubmitBlue,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable(enabled = !submitting) { submit() }
                            .testTag("qa-meeting-submit"),
                    )
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            FormLabel(context.t("meeting_subject"))
            FormInput(
                value = form.subject,
                placeholder = context.t("meeting_subjectPlaceholder"),
                enabled = !fieldsLocked,
                onValueChange = { form = form.copy(subject = it) },
                testTag = "qa-meeting-subject",
            )
            FormRow(
                label = context.t("meeting_attendees"),
                value = attendeeSummary,
                loading = loadingMembers,
                enabled = !fieldsLocked,
                addIcon = !fieldsLocked,
                onClick = { if (!fieldsLocked) memberPickerOpen = true },
                valueTag = "qa-meeting-attendees",
            )
            FormRow(
                label = context.t("meeting_startTime"),
                value = formatMeetingDateTime(form.startMs).take(16),
                onClick = { pickerKind = "start" },
                valueTag = "qa-meeting-start",
            )
            FormRow(
                label = context.t("meeting_endTime"),
                value = formatMeetingDateTime(form.endMs).take(16),
                onClick = { pickerKind = "end" },
                valueTag = "qa-meeting-end",
            )
            FormRow(
                label = context.t("meeting_room"),
                value = form.meetingRoomName?.takeIf { it.isNotEmpty() }
                    ?: form.extraMeetingRoom?.takeIf { it.isNotEmpty() }
                    ?: context.t("meeting_roomSelect"),
                enabled = !fieldsLocked,
                onClick = { if (!fieldsLocked) roomPickerOpen = true },
                valueTag = "qa-meeting-room",
            )
            if (showPeriodicSection) {
                FormRow(
                    label = context.t("meeting_repeat"),
                    value = currentPresetLabel,
                    enabled = !fieldsLocked,
                    onClick = { if (!fieldsLocked) sheetKind = "repeat" },
                    valueTag = "qa-meeting-repeat",
                )
                if (form.periodicPreset != "0") {
                    if (form.periodicPreset == "6") {
                        FormRow(
                            label = context.t("meeting_repeatFrequency"),
                            value = context.t("meeting_repeatEvery")
                                .replace("{{count}}", (form.periodicInfo.periodicInterval ?: 2).toString()),
                            enabled = !fieldsLocked,
                            onClick = { if (!fieldsLocked) sheetKind = "frequencyCount" },
                        )
                        FormRow(
                            label = context.t("meeting_repeatCycle"),
                            value = when (form.periodicInfo.periodicUnit) {
                                "WEEKLY" -> context.t("meeting_repeatCycleWeek")
                                "MONTHLY" -> context.t("meeting_repeatCycleMonth")
                                else -> context.t("meeting_repeatCycleDay")
                            },
                            enabled = !fieldsLocked,
                            onClick = { if (!fieldsLocked) sheetKind = "frequencyUnit" },
                        )
                        if (form.periodicInfo.periodicUnit == "MONTHLY") {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(context.t("meeting_repeatMonthMode"), color = colors.bodyText, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    context.t("meeting_repeatMonthByDate"),
                                    color = if (form.monthWeekly == MonthWeekly.MONTHDAILY) colors.primary else colors.auxiliaryText,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clickable(enabled = !fieldsLocked) {
                                            form = form.copy(monthWeekly = MonthWeekly.MONTHDAILY)
                                        }
                                        .padding(6.dp),
                                )
                                Text(
                                    context.t("meeting_repeatMonthByWeek"),
                                    color = if (form.monthWeekly == MonthWeekly.MONTHWEEKLY) colors.primary else colors.auxiliaryText,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .clickable(enabled = !fieldsLocked) {
                                            form = form.copy(monthWeekly = MonthWeekly.MONTHWEEKLY)
                                        }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                    FormRow(
                        label = context.t("meeting_repeatEnds"),
                        value = if ((form.periodicInfo.periodicCountType ?: "TO_DATE") == "COUNT") {
                            context.t("meeting_repeatEndAfterCount")
                        } else {
                            context.t("meeting_repeatEndOnDate")
                        },
                        enabled = !fieldsLocked,
                        onClick = { if (!fieldsLocked) sheetKind = "endType" },
                    )
                    if ((form.periodicInfo.periodicCountType ?: "TO_DATE") == "COUNT") {
                        FormLabel(context.t("meeting_repeatEndCount"))
                        FormInput(
                            value = (form.periodicInfo.periodicCount ?: 7).toString(),
                            placeholder = "",
                            enabled = !fieldsLocked,
                            numberKeyboard = true,
                            onValueChange = { raw ->
                                raw.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
                                    form = form.copy(periodicInfo = form.periodicInfo.copy(periodicCount = n))
                                }
                            },
                            testTag = "qa-meeting-periodic-count",
                        )
                    } else {
                        FormRow(
                            label = context.t("meeting_repeatEndDate"),
                            value = (form.periodicInfo.periodicEndDate
                                ?: defaultPeriodicEndDate(form.startMs)).take(10),
                            enabled = !fieldsLocked,
                            onClick = { if (!fieldsLocked) endDatePickerOpen = true },
                        )
                    }
                }
            }
            SwitchRow(context.t("meeting_autoTencent"), form.createTxMeeting, !fieldsLocked) {
                form = form.copy(createTxMeeting = it)
            }
            SwitchRow(context.t("meeting_sendNotification"), form.enableNotification, !fieldsLocked) {
                form = form.copy(enableNotification = it)
            }
            SwitchRow(context.t("meeting_privateNetwork"), form.mediaSetType == 1, !fieldsLocked) {
                form = form.copy(mediaSetType = if (it) 1 else 0)
            }
            if (!form.createTxMeeting) {
                FormLabel(context.t("meeting_code"))
                FormInput(
                    value = form.meetCode,
                    placeholder = "",
                    enabled = !fieldsLocked,
                    onValueChange = { form = form.copy(meetCode = it) },
                    testTag = "qa-meeting-code",
                )
                FormLabel(context.t("meeting_url"))
                FormInput(
                    value = form.meetUrl,
                    placeholder = "",
                    enabled = !fieldsLocked,
                    onValueChange = { form = form.copy(meetUrl = it) },
                    testTag = "qa-meeting-url",
                )
            }
            FormLabel(context.t("meeting_desc"))
            FormInput(
                value = form.desc,
                placeholder = "",
                enabled = !fieldsLocked,
                minLines = 3,
                onValueChange = { form = form.copy(desc = it) },
                testTag = "qa-meeting-desc",
            )
            FormLabel(context.t("meeting_materials"))
            FormInput(
                value = form.meetingMaterials,
                placeholder = "",
                enabled = !fieldsLocked,
                minLines = 3,
                onValueChange = { form = form.copy(meetingMaterials = it) },
                testTag = "qa-meeting-materials",
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 起止时间选择（RN TodoReminderPicker minuteInterval=15——Compose 无步进轮，确认时向下取整 15）
    TodoReminderPicker(
        open = pickerKind != null,
        initialMillis = if (pickerKind == "end") form.endMs else form.startMs,
        onConfirm = { picked ->
            val snapped = snapToQuarterHour(picked)
            form = if (pickerKind == "end") form.copy(endMs = snapped) else form.copy(startMs = snapped)
            pickerKind = null
        },
        onCancel = { pickerKind = null },
    )

    // 周期结束日期（RN TodoReminderPicker mode="date" 单步）
    if (endDatePickerOpen) {
        val initial = parseMeetingDateTime(form.periodicInfo.periodicEndDate.orEmpty())
            ?: parseMeetingDateTime(defaultPeriodicEndDate(form.startMs))
            ?: form.startMs
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { endDatePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        form = form.copy(
                            periodicInfo = form.periodicInfo.copy(periodicEndDate = formatPeriodicEndDate(it)),
                        )
                    }
                    endDatePickerOpen = false
                }) { Text(context.t("todo_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { endDatePickerOpen = false }) { Text(context.t("todo_cancel")) }
            },
        ) { DatePicker(state = dateState) }
    }

    OptionSheet(
        open = sheetKind == "repeat",
        title = context.t("meeting_repeat"),
        options = presetRows.map { SheetOption(it.preset.value, it.label) },
        selected = form.periodicPreset,
        onSelect = { value ->
            presetRows.firstOrNull { it.preset.value == value }?.let { handleChangePreset(it.preset.value, it.preset) }
        },
        onClose = { sheetKind = null },
    )
    OptionSheet(
        open = sheetKind == "endType",
        title = context.t("meeting_repeatEnds"),
        options = listOf(
            SheetOption("TO_DATE", context.t("meeting_repeatEndOnDate")),
            SheetOption("COUNT", context.t("meeting_repeatEndAfterCount")),
        ),
        selected = form.periodicInfo.periodicCountType ?: "TO_DATE",
        onSelect = { value ->
            form = form.copy(
                periodicInfo = form.periodicInfo.copy(
                    periodicCountType = value,
                    periodicCount = if (value == "COUNT") form.periodicInfo.periodicCount ?: 7 else null,
                ),
            )
        },
        onClose = { sheetKind = null },
    )
    OptionSheet(
        open = sheetKind == "frequencyCount",
        title = context.t("meeting_repeatFrequency"),
        options = (1 until 60).map { n ->
            SheetOption(n.toString(), context.t("meeting_repeatEvery").replace("{{count}}", n.toString()))
        },
        selected = (form.periodicInfo.periodicInterval ?: 2).toString(),
        onSelect = { value ->
            value.toIntOrNull()?.let { form = form.copy(periodicInfo = form.periodicInfo.copy(periodicInterval = it)) }
        },
        onClose = { sheetKind = null },
    )
    OptionSheet(
        open = sheetKind == "frequencyUnit",
        title = context.t("meeting_repeatCycle"),
        options = listOf(
            SheetOption("DAILY", context.t("meeting_repeatCycleDay")),
            SheetOption("WEEKLY", context.t("meeting_repeatCycleWeek")),
            SheetOption("MONTHLY", context.t("meeting_repeatCycleMonth")),
        ),
        selected = form.periodicInfo.periodicUnit ?: "DAILY",
        onSelect = { value -> form = form.copy(periodicInfo = form.periodicInfo.copy(periodicUnit = value)) },
        onClose = { sheetKind = null },
    )

    // 会议室单选（brief 简化：名称单选可空，时间轴占用可视化不移植）
    if (roomPickerOpen) {
        AlertDialog(
            onDismissRequest = { roomPickerOpen = false },
            title = { Text(context.t("meeting_roomPickerTitle")) },
            text = {
                if (rooms.isEmpty()) {
                    Text(context.t("meeting_roomEmpty"), color = colors.auxiliaryText)
                } else {
                    LazyColumn(Modifier.height(280.dp)) {
                        items(rooms, key = { it.id ?: it.name.orEmpty() }) { room ->
                            val selectedRoom = room.id != null && room.id == form.roomId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        form = if (selectedRoom) {
                                            form.copy(roomId = null, meetingRoomName = null)
                                        } else {
                                            form.copy(roomId = room.id, meetingRoomName = room.name, extraMeetingRoom = null)
                                        }
                                        roomPickerOpen = false
                                    }
                                    .padding(vertical = 10.dp)
                                    .testTag("qa-meeting-room-option-${room.id.orEmpty()}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(room.name.orEmpty(), color = colors.bodyText, fontSize = 15.sp)
                                if (selectedRoom) Text("✓", color = colors.primary, fontSize = 15.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { roomPickerOpen = false }) { Text(context.t("meeting_end_confirm")) }
            },
        )
    }

    // 与会人选择浮层（RN VoiceMemberPicker intent:'meeting' 语义：联邦成员多选 + 搜索 + 全选，
    // 确认回写表单并补当前用户；独立路由/草稿 store 不需要——本浮层即表单作用域）
    if (memberPickerOpen) {
        MeetingMemberPickerDialog(
            rid = rid,
            initialSelectedUsernames = form.users.mapNotNull { it.username?.takeIf { u -> u.isNotEmpty() } },
            sdk = sdk,
            dbManager = dbManager,
            serverUrl = serverUrl,
            currentUsername = currentUsername,
            authUserId = null,
            onConfirm = { users ->
                form = form.copy(users = users)
                memberPickerOpen = false
            },
            onDismiss = { memberPickerOpen = false },
        )
    }
}

// ── 表单原子件 ──

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        color = LabelGray,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun FormInput(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    numberKeyboard: Boolean = false,
    testTag: String? = null,
) {
    val colors = LocalAppiaColors.current
    var m = modifier
        .fillMaxWidth()
        .background(colors.chatComponentBackground)
        .padding(horizontal = 12.dp, vertical = 10.dp)
    if (testTag != null) m = m.testTag(testTag)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        minLines = minLines,
        maxLines = if (minLines > 1) 6 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numberKeyboard) KeyboardType.Number else KeyboardType.Text),
        textStyle = TextStyle(color = InputDark, fontSize = 15.sp),
        cursorBrush = SolidColor(colors.primary),
        modifier = m,
        decorationBox = { inner ->
            Box { // androidx.compose.foundation.layout.Box
                inner()
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = PlaceholderGray, fontSize = 15.sp)
                }
            }
        },
    )
}

@Composable
private fun FormRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    enabled: Boolean = true,
    addIcon: Boolean = false,
    valueTag: String? = null,
) {
    val colors = LocalAppiaColors.current
    var rowModifier = modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick)
        .padding(vertical = 12.dp)
    // testTag 挂 merge 根（clickable Column 会合并子孙语义，内层 Text 的 tag 在合并树中丢失）
    if (valueTag != null) rowModifier = rowModifier.testTag(valueTag)
    Column(rowModifier) {
        Text(label, color = colors.bodyText, fontSize = 15.sp)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    value,
                    color = if (value.isEmpty()) PlaceholderGray else colors.bodyText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (addIcon) {
                Text(
                    "+",
                    color = colors.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(24.dp)
                        .background(colors.chatComponentBackground, RoundedCornerShape(12.dp)),
                )
            }
        }
        Spacer(Modifier.height(1.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(RowDivider)) {}
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalAppiaColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.bodyText, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** RN OptionSheet（MeetingPeriodicSection）：选项列表弹层，命中即选并关闭。 */
@Composable
private fun OptionSheet(
    open: Boolean,
    title: String,
    options: List<SheetOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    if (!open) return
    val colors = LocalAppiaColors.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options, key = { it.value }) { option ->
                    val hit = option.value == selected
                    Text(
                        option.label,
                        color = if (hit) colors.primary else colors.bodyText,
                        fontSize = 15.sp,
                        fontWeight = if (hit) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option.value)
                                onClose()
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(LocalContext.current.t("cancel")) } },
    )
}

/**
 * 与会人选择浮层（RN VoiceMemberPickerScreen intent:'meeting' 分支语义）：
 * appia/room/members 联邦成员（去当前用户）多选 + 本地搜索 + 全选；确认构造 IMeetingUser[]
 * （source=Enterprise_ID 缺省 bitmain）并补当前用户（RN :213-224）。
 */
@Composable
fun MeetingMemberPickerDialog(
    rid: String,
    initialSelectedUsernames: List<String>,
    sdk: RocketSdk?,
    dbManager: DatabaseManager?,
    serverUrl: String,
    currentUsername: String?,
    onConfirm: (List<MeetingUser>) -> Unit,
    onDismiss: () -> Unit,
    authUserId: String? = null,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var members by remember { mutableStateOf<List<cn.appia.im.core.network.api.ReadReceiptUser>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelectedUsernames.toSet()) }
    var enterpriseSource by remember { mutableStateOf("bitmain") }

    LaunchedEffect(rid, sdk) {
        if (sdk == null) return@LaunchedEffect
        enterpriseSource = dbManager?.let { m ->
            runCatching { m.databaseFor(m.normalizeServer(serverUrl)) }
                .getOrNull()?.settingDao()?.getById("Enterprise_ID")?.value_as_string
        }?.trim()?.takeIf { it.isNotEmpty() } ?: "bitmain"
        runCatching { MeetingApi.getRoomMembers(sdk, rid) }
            .onSuccess { res ->
                val seen = mutableSetOf<String>()
                members = res.data.flatMap { it.members }
                    .mapNotNull { m ->
                        val u = m.username?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        if (!seen.add(u)) null else m
                    }
            }
        loading = false
    }

    val me = currentUsername?.trim().orEmpty()
    // RN listMembers：去当前用户；搜索本地过滤 username/name（RN filterMembersByQuery）
    val listMembers = remember(members, me) { members.filter { it.username != me } }
    val displayed = remember(listMembers, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) listMembers
        else listMembers.filter {
            it.username?.lowercase()?.contains(q) == true || it.name?.lowercase()?.contains(q) == true
        }
    }
    val allSelected = displayed.isNotEmpty() && displayed.all { it.username in selected }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.t("meeting_attendees")) },
        text = {
            Column {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = InputDark, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner ->
                        Box {
                            inner()
                            if (query.isEmpty()) {
                                Text(context.t("roomlist_searchPlaceholder"), color = PlaceholderGray, fontSize = 15.sp)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.chatComponentBackground, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("qa-meeting-member-search"),
                )
                if (!loading && displayed.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (allSelected) {
                                    selected - displayed.mapNotNull { it.username }.toSet()
                                } else {
                                    selected + displayed.mapNotNull { it.username }
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            context.t("voice_memberPicker_selectAll"),
                            color = if (allSelected) colors.primary else colors.bodyText,
                            fontSize = 14.sp,
                        )
                    }
                }
                if (loading) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else if (displayed.isEmpty()) {
                    Text(
                        context.t("voice_memberPicker_empty"),
                        color = colors.auxiliaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(displayed, key = { it._id ?: it.username.orEmpty() }) { m ->
                            val u = m.username.orEmpty()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (u in selected) selected - u else selected + u
                                    }
                                    .padding(vertical = 8.dp)
                                    .testTag("qa-meeting-member-${u}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    m.name?.takeIf { it.isNotEmpty() } ?: u,
                                    color = colors.bodyText,
                                    fontSize = 15.sp,
                                )
                                if (u in selected) Text("✓", color = colors.primary, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                context.t("meeting_pickerConfirm").replace("{{count}}", selected.size.toString()),
                color = colors.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clickable {
                        val picked = listMembers.filter { it.username != null && it.username in selected }
                        val enterprise = enterpriseSource
                        val users = picked.map { m ->
                            val u = m.username.orEmpty()
                            MeetingUser(id = m._id ?: u, _id = m._id ?: u, username = u, name = m.name ?: u, source = enterprise)
                        }
                        // RN :213-224：确认名单不含当前用户则补入
                        val withMe = if (me.isNotEmpty() && users.none { it.username == me }) {
                            users + MeetingUser(
                                id = authUserId ?: me,
                                _id = authUserId ?: me,
                                username = me,
                                name = currentUsername ?: me,
                                source = enterprise,
                            )
                        } else {
                            users
                        }
                        onConfirm(withMe)
                    }
                    .testTag("qa-meeting-member-confirm"),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.t("cancel")) } },
    )
}
