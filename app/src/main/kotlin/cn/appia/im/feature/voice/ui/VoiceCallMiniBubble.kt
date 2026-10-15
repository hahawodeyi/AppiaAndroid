package cn.appia.im.feature.voice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.domain.voice.VoiceSessionController
import kotlin.math.roundToInt

/**
 * 语音最小化气泡（M10-T12 / RN VoiceCallMiniBubble + VoiceCallMinimizeShell 气泡相位）：
 * 活跃相位且 uiMinimized 时浮现（VoiceCallHost 的 None 分支补位）；可拖拽（边界钳制 +
 * 释放贴边 + 位置 KvStore 持久化——MeetingMiniBubble 同构）；单击恢复全屏 overlay
 * （RN expand → setUiMinimized(false)）。
 *
 * **实现锁定（brief）**：应用内 Compose 气泡；出应用时 FGS 通知承载（VoiceRoomHandle
 * startPlatformWiring），系统级浮窗归 backlog-m10（协议第二十三节记录）。
 */

private const val BUBBLE_LEFT_KEY = "voice_bubble_left"
private const val BUBBLE_BOTTOM_KEY = "voice_bubble_bottom"

/** RN voiceMiniBubbleLayout.ts 常量。 */
const val VOICE_MINI_BUBBLE_SIZE_PX = 60f
const val VOICE_MINIMIZE_ANIM_MS = 300

/** RN VoiceBubblePosition（left/bottom 锚点，bottom 自屏底起算）。 */
data class VoiceBubblePosition(val left: Float, val bottom: Float)

/** RN BubbleBounds 四向极值。 */
data class VoiceBubbleBounds(
    val minLeft: Float,
    val maxLeft: Float,
    val minBottom: Float,
    val maxBottom: Float,
)

/**
 * RN getVoiceMiniBubbleBounds（:14-30 同式）。**全 px 域**（终审 M-1）：屏幕/insets/bubbleSize
 * 均为 px 实测值；RN 的 50/30 dp 常量由调用方转 px 后经 [minBottomPadPx]/[maxBottomPadPx] 传入
 * （密度 2.75 设备 60dp 气泡 = 165px——px/dp 混用会右缘溢出 ~105px）。
 */
fun getVoiceMiniBubbleBounds(
    screenWidth: Float,
    screenHeight: Float,
    topInset: Float,
    bottomInset: Float,
    bubbleSizePx: Float,
    minBottomPadPx: Float,
    maxBottomPadPx: Float,
): VoiceBubbleBounds = VoiceBubbleBounds(
    minLeft = 0f,
    maxLeft = screenWidth - bubbleSizePx,
    minBottom = bottomInset + minBottomPadPx,
    maxBottom = screenHeight - topInset - bubbleSizePx - maxBottomPadPx,
)

/** RN clampVoiceMiniBubblePosition（:32-41 同式）。 */
fun clampVoiceMiniBubblePosition(left: Float, bottom: Float, bounds: VoiceBubbleBounds): VoiceBubblePosition =
    VoiceBubblePosition(
        left = minOf(bounds.maxLeft, maxOf(bounds.minLeft, left)),
        bottom = minOf(bounds.maxBottom, maxOf(bounds.minBottom, bottom)),
    )

/** RN getDefaultVoiceMiniBubblePosition（:43-55）：left: 0 / bottom: 80。 */
fun getDefaultVoiceMiniBubblePosition(bounds: VoiceBubbleBounds): VoiceBubblePosition =
    clampVoiceMiniBubblePosition(0f, 80f, bounds)

/** RN persistBubblePosition 等价：KvStore 两 Long 键（px 取整）。 */
internal fun loadVoiceBubblePosition(kv: KvStore, bounds: VoiceBubbleBounds): VoiceBubblePosition {
    if (!kv.contains(BUBBLE_LEFT_KEY) || !kv.contains(BUBBLE_BOTTOM_KEY)) {
        return getDefaultVoiceMiniBubblePosition(bounds)
    }
    return clampVoiceMiniBubblePosition(
        kv.getLong(BUBBLE_LEFT_KEY, 0).toFloat(),
        kv.getLong(BUBBLE_BOTTOM_KEY, 0).toFloat(),
        bounds,
    )
}

internal fun saveVoiceBubblePosition(kv: KvStore, position: VoiceBubblePosition) {
    kv.putLong(BUBBLE_LEFT_KEY, position.left.roundToInt().toLong())
    kv.putLong(BUBBLE_BOTTOM_KEY, position.bottom.roundToInt().toLong())
}

/**
 * 最小化气泡宿主（VoiceCallHost 根装配旁挂）：活跃相位且 uiMinimized 时浮现。
 * onExpand = setUiMinimized(false)（RN expandVoiceCallUi）。
 */
@Composable
fun VoiceCallMiniBubbleOverlay(
    controller: VoiceSessionController,
    kv: KvStore,
) {
    val state = controller.store.state.collectAsStateCompat().value
    val active = state.phase == cn.appia.im.core.voice.VoicePhase.OutgoingRinging ||
        state.phase == cn.appia.im.core.voice.VoicePhase.Connecting ||
        state.phase == cn.appia.im.core.voice.VoicePhase.InCall
    AnimatedVisibility(
        visible = active && state.uiMinimized,
        enter = fadeIn(tween(VOICE_MINIMIZE_ANIM_MS)),
        exit = fadeOut(tween(VOICE_MINIMIZE_ANIM_MS)),
    ) {
        BubbleContent(
            kv = kv,
            onExpand = { controller.setUiMinimized(false) },
        )
    }
}

@Composable
private fun BubbleContent(kv: KvStore, onExpand: () -> Unit) {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density).toFloat()
    val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
    val bubbleSize = with(density) { VOICE_MINI_BUBBLE_SIZE_PX.dp.toPx() }
    // RN 50/30 dp 常量的 px 域换算（M-1：bounds 全 px，dp 直混会边界错位）
    val minBottomPad = with(density) { 50.dp.toPx() }
    val maxBottomPad = with(density) { 30.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val bounds = remember(screenW, screenH, topInset, bottomInset, bubbleSize) {
            getVoiceMiniBubbleBounds(screenW, screenH, topInset, bottomInset, bubbleSize, minBottomPad, maxBottomPad)
        }
        var position by remember(bounds) { mutableStateOf(loadVoiceBubblePosition(kv, bounds)) }
        // RN shell effect（index.tsx:64-76）：屏幕/insets 变化即重钳制
        LaunchedEffect(bounds) {
            position = clampVoiceMiniBubblePosition(position.left, position.bottom, bounds)
        }
        var dragStart by remember { mutableStateOf(position) }
        // C-2：detectDragGestures 逐帧增量需累计（RN gesture.dx/dy 绝对位移语义）
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier
                .offset {
                    IntOffset(
                        position.left.roundToInt(),
                        (screenH - position.bottom - bubbleSize).roundToInt(),
                    )
                }
                .size(with(density) { bubbleSize.toDp() })
                .clip(RoundedCornerShape(30.dp))
                .background(Color(17, 20, 30, 235))
                .pointerInput(bounds) {
                    detectDragGestures(
                        onDragStart = {
                            dragStart = position
                            dragOffset = Offset.Zero
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                            position = clampVoiceMiniBubblePosition(
                                dragStart.left + dragOffset.x,
                                dragStart.bottom - dragOffset.y,
                                bounds,
                            )
                        },
                        onDragEnd = {
                            // RN :82-88：释放贴边（过半屏贴右，否则贴左）+ 持久化
                            val snapLeft =
                                if (position.left > screenW / 2) bounds.maxLeft else bounds.minLeft
                            val snapped = clampVoiceMiniBubblePosition(snapLeft, position.bottom, bounds)
                            position = snapped
                            saveVoiceBubblePosition(kv, snapped)
                        },
                    )
                }
                .clickable(
                    role = Role.Button,
                    onClickLabel = null,
                    onClick = onExpand,
                )
                .semantics { role = Role.Button }
                .testTag("qa-voice-mini-bubble"),
            contentAlignment = Alignment.Center,
        ) {
            // RN call.png 图标代位（AA 无语音图标资产——✆ 文字符号，VoiceCallHost 同先例）
            Text("✆", color = Color(0xFF2878FF), fontSize = 26.sp)
        }
    }
}
