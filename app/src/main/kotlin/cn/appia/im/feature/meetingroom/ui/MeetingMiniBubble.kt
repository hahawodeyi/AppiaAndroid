package cn.appia.im.feature.meetingroom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.i18n.t
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import cn.appia.im.feature.meetingroom.MEETING_MINI_BUBBLE_HEIGHT_PX
import cn.appia.im.feature.meetingroom.MEETING_MINI_BUBBLE_WIDTH_PX
import cn.appia.im.feature.meetingroom.MEETING_MINIMIZE_ANIM_MS
import cn.appia.im.feature.meetingroom.MeetingBubbleBounds
import cn.appia.im.feature.meetingroom.MeetingBubblePosition
import cn.appia.im.feature.meetingroom.clampMeetingMiniBubblePosition
import cn.appia.im.feature.meetingroom.getDefaultMeetingMiniBubblePosition
import cn.appia.im.feature.meetingroom.getMeetingMiniBubbleBounds
import cn.appia.im.feature.meetingroom.snapMeetingMiniBubblePosition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import kotlin.math.roundToInt

/**
 * 会议最小化气泡（M8-T8 / RN MeetingMiniBubble + MeetingMinimizeShell:211-333 气泡相位）：
 * **Compose 全局浮层（Activity 内，非系统窗）**——phase=Minimized 时淡入，可拖拽（边界钳制 +
 * 释放贴边 + 位置持久化 KvStore），单击恢复（[onRestore]）。拖拽判定 slop 用平台默认
 * （RN DRAG_SLOP=5 与 ViewConfiguration touch slop 同数量级，语义一致：动过即拖，未动即点击）。
 */

private const val BUBBLE_LEFT_KEY = "meeting_bubble_left"
private const val BUBBLE_BOTTOM_KEY = "meeting_bubble_bottom"

/** RN persistBubblePosition：{left,bottom} 落 AsyncStorage；原生两 Long 键（px 取整）。 */
internal fun loadMeetingBubblePosition(kv: KvStore, bounds: MeetingBubbleBounds): MeetingBubblePosition {
    if (!kv.contains(BUBBLE_LEFT_KEY) || !kv.contains(BUBBLE_BOTTOM_KEY)) {
        return getDefaultMeetingMiniBubblePosition(bounds)
    }
    return clampMeetingMiniBubblePosition(
        kv.getLong(BUBBLE_LEFT_KEY, 0).toFloat(),
        kv.getLong(BUBBLE_BOTTOM_KEY, 0).toFloat(),
        bounds,
    )
}

internal fun saveMeetingBubblePosition(kv: KvStore, position: MeetingBubblePosition) {
    kv.putLong(BUBBLE_LEFT_KEY, position.left.roundToInt().toLong())
    kv.putLong(BUBBLE_BOTTOM_KEY, position.bottom.roundToInt().toLong())
}

@Composable
fun MeetingMinimizeBubbleOverlay(
    machine: MeetingSessionStateMachine,
    kv: KvStore,
    onRestore: () -> Unit,
) {
    val session by machine.state.collectAsState()
    AnimatedVisibility(
        visible = session.phase == MeetingPhase.Minimized,
        enter = fadeIn(tween(MEETING_MINIMIZE_ANIM_MS)),
        exit = fadeOut(tween(MEETING_MINIMIZE_ANIM_MS)),
    ) {
        BubbleContent(kv = kv, onRestore = onRestore)
    }
}

@Composable
private fun BubbleContent(kv: KvStore, onRestore: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // RN useStableTopInset + SafeAreaInsets.bottom 对位：状态栏/导航条 insets
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()
    val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
    val bubbleWidth = with(density) { MEETING_MINI_BUBBLE_WIDTH_PX.dp.toPx() }
    val bubbleHeight = with(density) { MEETING_MINI_BUBBLE_HEIGHT_PX.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val bounds = remember(screenW, screenH, topInset, bottomInset) {
            getMeetingMiniBubbleBounds(screenW, screenH, topInset, bottomInset, bubbleWidth, bubbleHeight)
        }
        var position by remember(bounds) { mutableStateOf(loadMeetingBubblePosition(kv, bounds)) }
        // RN shell effect（index.tsx:261-269）：屏幕/insets 变化即重钳制（旋转后不出界）
        LaunchedEffect(bounds) {
            position = clampMeetingMiniBubblePosition(position.left, position.bottom, bounds)
        }
        var dragStart by remember { mutableStateOf(position) }

        Box(
            Modifier
                .offset {
                    IntOffset(
                        position.left.roundToInt(),
                        (screenH - position.bottom - bubbleHeight).roundToInt(),
                    )
                }
                .size(with(density) { bubbleWidth.toDp() }, with(density) { bubbleHeight.toDp() })
                .clip(RoundedCornerShape(20.dp))
                .background(Color(17, 20, 30, 235)) // RN styles.bubble rgba(17,20,30,0.92)
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .pointerInput(bounds) {
                    detectDragGestures(
                        onDragStart = { dragStart = position },
                        onDrag = { change, amount ->
                            change.consume()
                            position = clampMeetingMiniBubblePosition(
                                dragStart.left + amount.x,
                                dragStart.bottom - amount.y,
                                bounds,
                            )
                        },
                        onDragEnd = {
                            // RN :81-91：释放贴边 + 持久化
                            val snapped = snapMeetingMiniBubblePosition(position, screenW, bounds)
                            position = snapped
                            saveMeetingBubblePosition(kv, snapped)
                        },
                    )
                }
                .clickable(role = Role.Button, onClickLabel = context.t("meeting_mini_default_title")) {
                    onRestore()
                }
                .testTag("qa-meeting-mini-bubble"),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(start = 10.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFF2878FF)),
                    contentAlignment = Alignment.Center,
                ) {
                    VideoGlyph(Modifier.size(13.dp), Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    context.t("meeting_mini_default_title"),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

/** RN VideoIcon 13px 占位画法（AA 无 icons 依赖——Canvas 两笔：机身圆角矩形 + 镜头三角）。 */
@Composable
private fun VideoGlyph(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color,
            topLeft = Offset(0f, h * 0.22f),
            size = Size(w * 0.62f, h * 0.56f),
            cornerRadius = CornerRadius(w * 0.14f),
        )
        val path = Path().apply {
            moveTo(w * 0.70f, h * 0.40f)
            lineTo(w, h * 0.22f)
            lineTo(w, h * 0.78f)
            lineTo(w * 0.70f, h * 0.60f)
            close()
        }
        drawPath(path, color)
    }
}
