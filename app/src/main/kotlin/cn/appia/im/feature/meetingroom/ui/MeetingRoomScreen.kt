package cn.appia.im.feature.meetingroom.ui

import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.i18n.t
import cn.appia.im.core.meeting.rtc.MeetingRtcEvent
import cn.appia.im.core.theme.LocalAppiaColors
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionController
import cn.appia.im.feature.chat.ui.RoomHeader
import cn.appia.im.feature.meetingroom.MeetingOrientation
import cn.appia.im.feature.meetingroom.MeetingRoomHandle
import cn.appia.im.feature.meetingroom.MeetingRoomParticipant
import cn.appia.im.feature.meetingroom.MeetingRoomSignal
import cn.appia.im.feature.meetingroom.meetingKeepScreenOn
import cn.appia.im.feature.meetingroom.applySelfCamera
import cn.appia.im.feature.meetingroom.applySelfMic
import cn.appia.im.feature.meetingroom.foldMeetingRoomParticipants
import cn.appia.im.feature.meetingroom.formatMeetingDuration
import cn.appia.im.feature.meetingroom.meetingRoomSignalFor
import cn.appia.im.feature.meetingroom.uidForMeeting

/**
 * 会议房间（M8-T7 greenfield——RN 无原生会议室，规格对照 meetingSessionStore + 桥协议语义）：
 * - 视频宫格：participants 由引擎事件折叠（[foldMeetingRoomParticipants]；self tile 由 Joined 插入），
 *   真实视频帧渲染需 RtcEngine canvas API（T6 命令面未含）——tile 呈现参与者态（静音/出帧高亮），
 *   帧上屏归用户域验收边界（模拟器无摄像头/麦克风）。
 * - 控制条：静音/摄像头（引擎+状态机+宫格三写）、共享（开关→横竖屏三触发源①）、成员 sheet、离开。
 * - 横屏三触发源（M8-T9，[cn.appia.im.feature.meetingroom.MeetingOrientationState] 纯逻辑）：
 *   ① 共享开→横屏、关→竖屏（RN MeetingWebView:109-117 一次性锁）；② 顶栏转屏按钮按实际方向
 *   取反直调（RN useOrientationToggle.toggle 不依赖 state）；③ 最小化强制竖屏（dispose 兜底
 *   恢复竖屏，RN 卸载恢复语义）。引擎/状态机 @Singleton + manifest configChanges → 旋转不重建。
 * - 共享态顶栏（RN MeetingMinimizeShell:122-177）：共享+横屏时顶栏悬浮压在视频上方（不挤压），
 *   点视频区显隐（hover 等价 tap）；否则常驻占位。
 * - 会中常亮（RN useKeepScreenOn）：InMeeting 期 FLAG_KEEP_SCREEN_ON，退出/最小化（屏卸载）即清。
 * - 坑 4：会中返回 = 离开确认弹层（[MeetingSessionStateMachine.consumesBack] 单源）；preheating
 *   返回直接出栈（join 协程随 composition 取消 → 控制器回 idle）。
 * - 信号分叉：Error(inMeeting) → kicked toast+离会+出栈（原生面无显式被踢信号，onError 最近似）；
 *   Left(非 idle) → meeting:ended toast+出栈。
 */
/** MeetingOrientation → ActivityInfo 常量映射（UI 层唯一 android 触点）。 */
internal fun MeetingOrientation.toActivityInfo(): Int = when (this) {
    MeetingOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    MeetingOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingRoomScreen(
    channelName: String,
    meetingNumber: String,
    title: String,
    userId: String,
    handle: MeetingRoomHandle?,
    onBack: () -> Unit,
    /** 最小化气泡恢复重入（M8-T8）：会话存活免 join；普通重入（深链/openLink）仍走重入拒绝。 */
    resumed: Boolean = false,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    if (handle == null) {
        // UI 测试/未装配兜底（其余目的地同款）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(context.t("feature_not_implemented"))
        }
        return
    }

    val session by handle.machine.state.collectAsState()
    val selfUid = remember(userId) { uidForMeeting(userId) }
    var participants by remember(selfUid) { mutableStateOf(emptyList<MeetingRoomParticipant>()) }
    var cameraOn by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }

    val joining = session.phase == MeetingPhase.Preheating

    // ── 横屏三触发源 + 共享态顶栏 + 会中常亮（M8-T9）──
    // 横竖屏落 Activity（旋转不重建——manifest configChanges orientation|screenSize|smallestScreenSize）
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    // 共享态（触发①源）：AA 无共享 RTC 事件，以控制条共享开关为单源（brief 裁定）
    var sharing by remember { mutableStateOf(false) }
    // 共享态顶栏显隐：悬浮态下点视频区 toggle（web sharingHover 的 hover 等价 tap）
    var headerVisible by remember { mutableStateOf(true) }
    val headerOverlaysVideo = sharing && isLandscape
    fun applyOrientation(o: MeetingOrientation) {
        activity?.requestedOrientation = o.toActivityInfo()
    }

    // 触发③兜底 + RN useOrientationToggle:23 卸载恢复竖屏：房间屏出栈（离开/最小化）必回竖屏，
    // 恢复回横屏由气泡 onRestore（MainActivity）按记忆方向重设
    DisposableEffect(Unit) {
        onDispose { applyOrientation(MeetingOrientation.Portrait) }
    }

    // 会中常亮（RN useKeepScreenOn(meetingActive)）：仅 InMeeting 挂 FLAG_KEEP_SCREEN_ON，
    // 退出/最小化（屏卸载或相位翻转）onDispose 必清
    DisposableEffect(meetingKeepScreenOn(session.phase)) {
        val window = activity?.window
        if (meetingKeepScreenOn(session.phase)) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 入会（冷进/重进同路）：重入拒绝（坑 6）在控制器内——会议存活时再开房间路由（openLink 移交/
    // 深链）必 Failed("meeting_already_active") → toast + 出栈，绝不静默渲染错会话。
    // 恢复路径（M8-T8）：气泡点击 → restore() → 复路由，相位已 InMeeting/Minimized → 免 join，
    // self tile 种子回宫格（远端 tile 待引擎事件回流补齐——最小化期间屏未收集事件，如实边界）。
    LaunchedEffect(handle, channelName) {
        if (resumed && handle.machine.phase != MeetingPhase.Idle) {
            // 恢复路径：气泡点击 → restore → 复路由。免 join；self tile 种子回宫格
            //（远端 tile 待引擎事件回流补齐——最小化期间屏未收集事件，如实边界）
            participants = listOf(MeetingRoomParticipant(selfUid, isSelf = true))
            return@LaunchedEffect
        }
        when (val r = handle.controller.join(channelName, selfUid, title)) {
            is MeetingSessionController.JoinResult.Joined -> Unit
            is MeetingSessionController.JoinResult.Failed -> {
                val key = if (r.reason == "meeting_already_active") "meeting_already_active" else "meeting_room_joinfailed"
                Toast.makeText(context, context.t(key), Toast.LENGTH_SHORT).show()
                onBack()
            }
        }
    }

    // 引擎事件 → 宫格折叠 + Stats 喂状态机 + kicked/ended 信号分叉
    LaunchedEffect(handle, selfUid) {
        handle.engine.events.collect { e ->
            if (e is MeetingRtcEvent.Stats) {
                handle.machine.applyStats(e.durationSec.toLong(), e.userCount)
            } else {
                participants = foldMeetingRoomParticipants(participants, selfUid, e)
                when (meetingRoomSignalFor(handle.machine.state.value, e)) {
                    MeetingRoomSignal.KICKED -> {
                        handle.controller.leave()
                        Toast.makeText(context, context.t("meeting_end_host_title"), Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                    MeetingRoomSignal.ENDED -> {
                        handle.controller.leave()
                        Toast.makeText(context, context.t("meeting_end_meeting_ended_title"), Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                    MeetingRoomSignal.NONE -> Unit
                }
            }
        }
    }

    // 坑 4：会中返回 = 离开确认；preheating/minimized 不消费（交还导航）
    BackHandler(enabled = handle.machine.consumesBack()) { showLeaveConfirm = true }

    Column(Modifier.fillMaxSize().background(colors.backgroundColor).testTag("qa-meeting-room-screen")) {
        // 共享态横屏：顶栏从占位移除、悬浮压在视频上（RN MeetingMinimizeShell:168-177——不挤压
        // 视频，显隐不引起宫格尺寸跳动）；竖屏/非共享常驻占位（RN headerOverlaysVideo 同判）
        if (!headerOverlaysVideo) {
            MeetingRoomHeader(
                title = title,
                durationSec = session.durationSec,
                participantCount = session.participantCount,
                onBack = { if (handle.machine.consumesBack()) showLeaveConfirm = true else onBack() },
                onRotate = { applyOrientation(handle.orientation.toggle(isLandscape)) },
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // hover 等价 tap：悬浮态点视频区显隐顶栏（RN handleTap 横屏会中分支的原生对位）。
                // 条件加 clickable（enabled=false 会合并子语义并把 stage 标 Disabled，吞掉宫格
                // tile 的 testTag/可点性——非悬浮态必须完全不加修饰符）
                .let { if (headerOverlaysVideo) it.clickable { headerVisible = !headerVisible } else it }
                .testTag("qa-meeting-room-stage"),
        ) {
            when {
                joining -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(context.t("meeting_room_waiting"), color = colors.auxiliaryText, fontSize = 14.sp)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isLandscape) 4 else 2),
                    modifier = Modifier.fillMaxSize().padding(8.dp).testTag("qa-meeting-room-grid"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(participants, key = { it.uid }) { p -> ParticipantTile(p) }
                }
            }
            if (headerOverlaysVideo && headerVisible) {
                MeetingRoomHeader(
                    title = title,
                    durationSec = session.durationSec,
                    participantCount = session.participantCount,
                    onBack = { if (handle.machine.consumesBack()) showLeaveConfirm = true else onBack() },
                    onRotate = { applyOrientation(handle.orientation.toggle(isLandscape)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ControlBar(
            isMuted = session.isMuted,
            cameraOn = cameraOn,
            sharing = sharing,
            onToggleMic = {
                val muted = !session.isMuted
                handle.machine.setMicMuted(muted)
                handle.engine.setMicMuted(muted)
                participants = applySelfMic(participants, muted)
            },
            onToggleCamera = {
                cameraOn = !cameraOn
                handle.engine.setCameraMuted(!cameraOn)
                participants = applySelfCamera(participants, cameraOn)
            },
            // 触发①共享驱动：开→横屏、关→竖屏（RN MeetingWebView:109-117 一次性锁语义）
            onShare = {
                sharing = !sharing
                applyOrientation(handle.orientation.onShareToggled(sharing))
                if (sharing) headerVisible = true
            },
            onMembers = { showMembers = true },
            // M8-T8 最小化（RN MeetingWebHeader onMinimize）+ 触发③a 强制竖屏（RN forcePortrait）：
            // 相位 → minimized；房间路由出栈（引擎/状态机单例存活），气泡由 app 根浮层接管
            onMinimize = {
                applyOrientation(handle.orientation.onMinimized())
                handle.machine.minimize()
                onBack()
            },
            onLeave = { showLeaveConfirm = true },
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(context.t("meeting_room_leave_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    handle.controller.leave()
                    Toast.makeText(context, context.t("meeting_end_self_title"), Toast.LENGTH_SHORT).show()
                    onBack()
                }) { Text(context.t("meeting_cancel_confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text(context.t("cancel")) }
            },
        )
    }

    if (showMembers) {
        ModalBottomSheet(
            onDismissRequest = { showMembers = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Text(
                context.t("meeting_room_members"),
                color = colors.titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // uid→名字映射需 H5 会话侧数据（greenfield 无来源）——成员表以 uid 展示，边界如实
            participants.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (p.isSelf) context.t("meeting_member_self") else p.uid.toString(),
                        color = colors.bodyText,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (p.micOn) "" else context.t("meeting_mini_muted_suffix"),
                        color = colors.auxiliaryText,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 宫格 tile：头像圈 + 称谓 + 静音标记；视频出帧中描边高亮（帧渲染边界见屏 KDoc）。 */
@Composable
private fun ParticipantTile(p: MeetingRoomParticipant) {
    val colors = LocalAppiaColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.chatComponentBackground)
            .let { m ->
                if (p.videoDecoding) m.border(1.5.dp, colors.primary, RoundedCornerShape(10.dp)) else m
            }
            .padding(vertical = 20.dp)
            .testTag("qa-meeting-room-tile"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.auxiliaryBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text("${p.uid % 100}", color = colors.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (p.isSelf) LocalContext.current.t("meeting_member_self") else p.uid.toString(),
            color = colors.bodyText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!p.micOn) {
            Text(
                LocalContext.current.t("meeting_mini_muted_suffix"),
                color = colors.auxiliaryText,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 会议室顶栏（RN MeetingWebHeader：返回/标题/转屏/时长）：占位与悬浮两形态复用。
 * 转屏按钮 = 横屏触发②（RN useOrientationToggle.toggle 模式：直接调 API 不依赖 state）。
 */
@Composable
private fun MeetingRoomHeader(
    title: String,
    durationSec: Long,
    participantCount: Int,
    onBack: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    RoomHeader(
        title = title.trim().ifEmpty { context.t("drawer_meeting") },
        onBack = onBack,
        modifier = modifier,
        headerAction = {
            Text(
                context.t("meeting_rotate"),
                color = colors.primary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRotate)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("qa-meeting-room-rotate"),
            )
            Text(
                "${formatMeetingDuration(durationSec)} · $participantCount",
                color = colors.auxiliaryText,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 16.dp).testTag("qa-meeting-room-meta"),
            )
        },
    )
}

/** 控制条（静音/摄像头/共享/成员/最小化/离开——离开红色）。 */
@Composable
private fun ControlBar(
    isMuted: Boolean,
    cameraOn: Boolean,
    sharing: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onShare: () -> Unit,
    onMembers: () -> Unit,
    onMinimize: () -> Unit,
    onLeave: () -> Unit,
) {
    val colors = LocalAppiaColors.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(colors.chatComponentBackground).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarButton(context.t(if (isMuted) "meeting_room_mic_on" else "meeting_room_mic_off"), !isMuted, "qa-meeting-room-mic", onToggleMic)
        BarButton(context.t(if (cameraOn) "meeting_room_camera_off" else "meeting_room_camera_on"), cameraOn, "qa-meeting-room-camera", onToggleCamera)
        BarButton(
            context.t(if (sharing) "meeting_room_sharescreen_stop" else "meeting_room_sharescreen"),
            sharing,
            "qa-meeting-room-share",
            onShare,
        )
        BarButton(context.t("meeting_room_members"), false, "qa-meeting-room-members", onMembers)
        BarButton(context.t("meeting_minimize"), false, "qa-meeting-room-minimize", onMinimize)
        BarButton(context.t("meeting_room_leave"), true, "qa-meeting-room-leave", onLeave, tintLeave = true)
    }
}

@Composable
private fun BarButton(
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit,
    tintLeave: Boolean = false,
) {
    val colors = LocalAppiaColors.current
    Text(
        label,
        color = when {
            tintLeave -> colors.dangerColor
            active -> colors.primary
            else -> colors.auxiliaryText
        },
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(tag),
    )
}
