package cn.appia.im.feature.meetingroom

import org.junit.Assert.assertEquals
import org.junit.Test

/** 气泡边界/钳制/默认位（RN meetingMiniBubbleLayout.ts 对照；px 域）。 */
class MeetingMiniBubbleLayoutTest {

    private val bounds =
        getMeetingMiniBubbleBounds(screenWidth = 1080f, screenHeight = 1920f, topInset = 100f, bottomInset = 60f)

    @Test
    fun `bounds mirror RN formulas`() {
        assertEquals(8f, bounds.minLeft, 0f)
        assertEquals(1080f - 116f - 8f, bounds.maxLeft, 0f)
        assertEquals(60f + 8f, bounds.minBottom, 0f)
        assertEquals(1920f - 100f - 40f - 8f, bounds.maxBottom, 0f)
    }

    @Test
    fun `clamp pins out of range position inside bounds`() {
        val clamped = clampMeetingMiniBubblePosition(left = -5f, bottom = 9999f, bounds)
        assertEquals(bounds.minLeft, clamped.left, 0f)
        assertEquals(bounds.maxBottom, clamped.bottom, 0f)
    }

    @Test
    fun `clamp keeps in range position unchanged`() {
        val clamped = clampMeetingMiniBubblePosition(left = 100f, bottom = 200f, bounds)
        assertEquals(100f, clamped.left, 0f)
        assertEquals(200f, clamped.bottom, 0f)
    }

    @Test
    fun `degenerate tiny screen guards lower bounds`() {
        // 极小屏：maxLeft/maxBottom 兜底 ≥ 下界（RN Math.max(8,…) 同款；60 宽 - 116 气泡必倒挂）
        val tiny = getMeetingMiniBubbleBounds(screenWidth = 60f, screenHeight = 44f, topInset = 0f, bottomInset = 0f)
        assertEquals(8f, tiny.maxLeft, 0f)
        assertEquals(tiny.minBottom, tiny.maxBottom, 0f)
        val clamped = clampMeetingMiniBubblePosition(-100f, -100f, tiny)
        assertEquals(8f, clamped.left, 0f)
        assertEquals(8f, clamped.bottom, 0f)
    }

    @Test
    fun `default position is bottom right per RN`() {
        val def = getDefaultMeetingMiniBubblePosition(bounds)
        assertEquals(bounds.maxLeft, def.left, 0f)
        assertEquals(bounds.minBottom, def.bottom, 0f)
    }

    @Test
    fun `snap goes to nearest edge like RN release handler`() {
        // RN :81-84：过半屏贴 maxLeft，否则 minLeft（bottom 保持）
        val right = snapMeetingMiniBubblePosition(MeetingBubblePosition(600f, 300f), 1080f, bounds)
        assertEquals(bounds.maxLeft, right.left, 0f)
        assertEquals(300f, right.bottom, 0f)
        val left = snapMeetingMiniBubblePosition(MeetingBubblePosition(540f, 300f), 1080f, bounds)
        assertEquals(bounds.minLeft, left.left, 0f)
        assertEquals(300f, left.bottom, 0f)
    }
}
