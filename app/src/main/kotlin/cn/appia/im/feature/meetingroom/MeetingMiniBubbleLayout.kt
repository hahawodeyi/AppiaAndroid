package cn.appia.im.feature.meetingroom

/**
 * 会议最小化气泡的纯布局层（RN src/features/meeting/meetingMiniBubbleLayout.ts verbatim 移植，
 * 单位统一为 px——RN 侧 ScaledSize/Style 数值同为 dp 语义，此处调用方以 density 换算后传入）：
 * 边界计算 + 钳制 + 默认位（右下角）。
 */

/** 气泡位置（RN MeetingBubblePosition：left/bottom 锚点，bottom 自屏底起算）。 */
data class MeetingBubblePosition(val left: Float, val bottom: Float)

/** 气泡活动边界（RN MeetingBubbleBounds 四向极值）。 */
data class MeetingBubbleBounds(
    val minLeft: Float,
    val maxLeft: Float,
    val minBottom: Float,
    val maxBottom: Float,
)

/** RN 常量：气泡 116×40、四周留 8px 边距（getMeetingMiniBubbleBounds 同式）。 */
const val MEETING_MINI_BUBBLE_WIDTH_PX = 116f
const val MEETING_MINI_BUBBLE_HEIGHT_PX = 40f
const val MEETING_MINI_BUBBLE_MARGIN_PX = 8f

/** RN MEETING_MINIMIZE_ANIM_MS。 */
const val MEETING_MINIMIZE_ANIM_MS = 250

/**
 * RN getMeetingMiniBubbleBounds（:16-28 同式）：maxLeft/maxBottom 的 Math.max(8, …) 兜底
 * 屏幕退化（极小窗口）时下界不倒挂。
 */
fun getMeetingMiniBubbleBounds(
    screenWidth: Float,
    screenHeight: Float,
    topInset: Float,
    bottomInset: Float,
    bubbleWidth: Float = MEETING_MINI_BUBBLE_WIDTH_PX,
    bubbleHeight: Float = MEETING_MINI_BUBBLE_HEIGHT_PX,
    margin: Float = MEETING_MINI_BUBBLE_MARGIN_PX,
): MeetingBubbleBounds = MeetingBubbleBounds(
    minLeft = margin,
    maxLeft = maxOf(margin, screenWidth - bubbleWidth - margin),
    minBottom = bottomInset + margin,
    maxBottom = maxOf(
        bottomInset + margin,
        screenHeight - topInset - bubbleHeight - margin,
    ),
)

/** RN clampMeetingMiniBubblePosition（:30-37 同式）：双向钳入边界。 */
fun clampMeetingMiniBubblePosition(
    left: Float,
    bottom: Float,
    bounds: MeetingBubbleBounds,
): MeetingBubblePosition = MeetingBubblePosition(
    left = minOf(bounds.maxLeft, maxOf(bounds.minLeft, left)),
    bottom = minOf(bounds.maxBottom, maxOf(bounds.minBottom, bottom)),
)

/** RN getDefaultMeetingMiniBubblePosition（:39-46 同式）：右下角（maxLeft, minBottom）。 */
fun getDefaultMeetingMiniBubblePosition(bounds: MeetingBubbleBounds): MeetingBubblePosition =
    MeetingBubblePosition(left = bounds.maxLeft, bottom = bounds.minBottom)

/** 拖拽释放后贴边（RN MeetingMiniBubble/index.tsx:81-84：过半屏贴右，否则贴左）。 */
fun snapMeetingMiniBubblePosition(
    position: MeetingBubblePosition,
    screenWidth: Float,
    bounds: MeetingBubbleBounds,
): MeetingBubblePosition {
    val snapLeft = if (position.left > screenWidth / 2) bounds.maxLeft else bounds.minLeft
    return clampMeetingMiniBubblePosition(snapLeft, position.bottom, bounds)
}
