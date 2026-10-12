package cn.appia.im.feature.meetingroom.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
    fun `drag release snaps to edge and persists position`() {
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

        val bounds = getMeetingMiniBubbleBounds(1080f, 1920f, 0f, 0f)
        rule.onNodeWithTag("qa-meeting-mini-bubble").performTouchInput {
            // 默认位 = 右下角（maxLeft）；拖到左缘后释放 → 过半屏判定贴 minLeft（RN :81-84）
            down(center)
            moveBy(Offset(-width / 3f, 0f), delayMillis = 50)
            moveBy(Offset(-width / 3f, 0f), delayMillis = 50)
            moveBy(Offset(-width / 3f, 0f), delayMillis = 50)
            up()
        }
        rule.waitForIdle()

        val saved = loadMeetingBubblePosition(kv, bounds)
        assertEquals(bounds.minLeft, saved.left, 1f)
        // 持久化键可回读（RoundTrip）
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
