package cn.appia.im.feature.meetingroom.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput

import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import cn.appia.im.feature.meetingroom.MeetingBubblePosition
import cn.appia.im.feature.meetingroom.getMeetingMiniBubbleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset

/**
 * 最小化气泡 Compose 冒烟（M8-T8）：Minimized 淡入可见、单击恢复（相位回 InMeeting + 回调）、
 * 拖拽后释放贴边并持久化 KvStore。位置钳制纯逻辑归 MeetingMiniBubbleLayoutTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeetingMinimizeBubbleSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `minimized shows bubble tap restores to in meeting`() {
        val machine = MeetingSessionStateMachine()
        var restored = false
        machine.openPreheat("Weekly Sync", "888888")
        machine.markInMeeting("Weekly Sync", "888888")
        rule.setContent {
            AppiaTheme(isDark = false) {
                MeetingMinimizeBubbleOverlay(machine = machine, kv = InMemoryKvStore(), onRestore = {
                    restored = true
                    machine.restore()
                })
            }
        }
        // 前相位 InMeeting → 浮层隐藏
        rule.waitForIdle()
        rule.onNodeWithTag("qa-meeting-mini-bubble").assertDoesNotExist()

        machine.minimize()
        rule.mainClock.advanceTimeBy(300) // 淡入 250ms 完成
        rule.waitForIdle()
        rule.onNodeWithTag("qa-meeting-mini-bubble").assertExists()
        rule.onNodeWithText("In meeting").assertExists() // meeting_mini_default_title（en）

        rule.onNodeWithTag("qa-meeting-mini-bubble").performClick()
        rule.waitForIdle()
        assertTrue(restored)
        assertEquals(MeetingPhase.InMeeting, machine.phase)
        rule.waitForIdle()
        rule.onNodeWithTag("qa-meeting-mini-bubble").assertDoesNotExist() // 恢复即收起
    }

    @Test
    fun `drag accumulates per frame deltas and snaps on release`() {
        val machine = MeetingSessionStateMachine()
        machine.openPreheat("Weekly Sync", "888888")
        machine.markInMeeting("Weekly Sync", "888888")
        machine.minimize()
        val kv: KvStore = InMemoryKvStore()
        rule.setContent {
            AppiaTheme(isDark = false) {
                MeetingMinimizeBubbleOverlay(machine = machine, kv = kv, onRestore = {})
            }
        }
        rule.waitForIdle()

        val bubble = rule.onNodeWithTag("qa-meeting-mini-bubble")
        val node = bubble.fetchSemanticsNode()
        val bubbleH = node.size.height.toFloat()
        val screenH = rule.onRoot().fetchSemanticsNode().size.height.toFloat()
        // start.bottom = screenH - node.top - bubbleH（RN 位置锚 = left/bottom）
        val startPosBottom = screenH - node.boundsInRoot.top - bubbleH

        // C-2：amount 是逐帧增量——三段 -30 上拖必须累计为 +90 bottom（RN bottom = start - g.dy，
        // g.dy 为绝对位移；bug 版只认末帧 = +30）。上拖无下界钳制/贴边干扰，断言定分辨。
        bubble.performTouchInput {
            down(center)
            moveBy(Offset(0f, -30f), delayMillis = 50)
            moveBy(Offset(0f, -30f), delayMillis = 50)
            moveBy(Offset(0f, -30f), delayMillis = 50)
            up()
        }
        rule.waitForIdle()

        // 断言累积而非精确像素：首段被 touch slop 吃掉 ~16px，固定累积 >> 只认末帧（+30）
        val savedBottom = loadMeetingBubblePosition(kv, getMeetingMiniBubbleBounds(1080f, 1920f, 0f, 0f)).bottom
        assertTrue("bottom must accumulate past two frames: $savedBottom", savedBottom > startPosBottom + 60f)

        // 横拖过半屏左移 → 释放贴 minLeft（RN :81-84）+ 持久化 round-trip
        bubble.performTouchInput {
            down(center)
            moveBy(Offset(-width.toFloat(), 0f), delayMillis = 50)
            moveBy(Offset(-width.toFloat(), 0f), delayMillis = 50)
            up()
        }
        rule.waitForIdle()
        val bounds = getMeetingMiniBubbleBounds(1080f, 1920f, 0f, 0f)
        val saved = loadMeetingBubblePosition(kv, bounds)
        assertEquals(bounds.minLeft, saved.left, 1f)
        assertTrue(kv.contains("meeting_bubble_left"))
        assertEquals(saved.left.roundToInt().toLong(), kv.getLong("meeting_bubble_left", -1))
        assertEquals(saved.bottom.roundToInt().toLong(), kv.getLong("meeting_bubble_bottom", -1))
    }

    @Test
    fun `saved position clamps on load when screen shrinks`() {
        // 旋转/缩小屏后持久化位出界 → load 即钳制（RN shell effect :261-269 语义）
        val kv: KvStore = InMemoryKvStore()
        kv.putLong("meeting_bubble_left", 5000)
        kv.putLong("meeting_bubble_bottom", 5000)
        val tinyBounds = getMeetingMiniBubbleBounds(400f, 300f, 0f, 0f)
        val loaded = loadMeetingBubblePosition(kv, tinyBounds)
        assertEquals(tinyBounds.maxLeft, loaded.left, 0f)
        assertEquals(tinyBounds.maxBottom, loaded.bottom, 0f)
    }
}
