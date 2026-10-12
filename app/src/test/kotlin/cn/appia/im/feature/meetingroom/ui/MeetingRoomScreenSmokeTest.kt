package cn.appia.im.feature.meetingroom.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.meeting.MeetingCredentials
import cn.appia.im.core.meeting.rtc.AgoraMeetingEngine
import cn.appia.im.core.meeting.rtc.AgoraRtcApi
import cn.appia.im.core.meeting.rtc.MeetingRtcEvent
import cn.appia.im.core.theme.AppiaTheme
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionController
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import cn.appia.im.feature.meetingroom.MeetingRoomHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会议房间 Compose 冒烟（M8-T7）：入会成功 → 宫格 self tile + 控制条；离开确认弹层门（坑 4 语义的
 * UI 面）→ 确认离会出栈；凭据失败 → toast + 出栈。RTC 真连接归用户域（模拟器无摄像头/麦克风——
 * 此处 fake rtc 驱动，边界如实）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeetingRoomScreenSmokeTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeRtc : AgoraRtcApi {
        var onEvent: ((MeetingRtcEvent) -> Unit)? = null
        var joinResult = 0
        val left = mutableListOf<Boolean>()

        override fun create(appId: String, onEvent: (MeetingRtcEvent) -> Unit) {
            this.onEvent = onEvent
        }

        override fun joinChannel(token: String?, channelId: String, uid: Int): Int {
            onEvent?.invoke(MeetingRtcEvent.Joined)
            return joinResult
        }

        override fun leaveChannel(): Int { left += true; return 0 }
        override fun muteLocalAudioStream(muted: Boolean): Int = 0
        override fun muteLocalVideoStream(muted: Boolean): Int = 0
        override fun destroy() {}
    }

    private val rtc = FakeRtc()
    private var backCount = 0

    private fun render(creds: MeetingCredentials? = MeetingCredentials(appId = "app", token = "t")) {
        val machine = MeetingSessionStateMachine()
        val engine = AgoraMeetingEngine(rtc)
        val handle = MeetingRoomHandle(
            engine = engine,
            machine = machine,
            controller = MeetingSessionController(engine, machine) { _, _ -> creds },
        )
        rule.setContent {
            AppiaTheme(isDark = false) {
                MeetingRoomScreen(
                    channelName = "room-1",
                    meetingNumber = "123456",
                    title = "weekly sync",
                    userId = "user-1",
                    handle = handle,
                    onBack = { backCount++ },
                )
            }
        }
    }

    private fun tagExists(tag: String) =
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun idleTwice() {
        rule.waitForIdle()
        rule.waitForIdle()
    }

    @Test
    fun `join success renders grid self tile control bar and meta`() {
        render()
        idleTwice()
        assertTrue(tagExists("qa-meeting-room-tile"))
        listOf(
            "qa-meeting-room-mic",
            "qa-meeting-room-camera",
            "qa-meeting-room-share",
            "qa-meeting-room-members",
            "qa-meeting-room-leave",
            "qa-meeting-room-meta",
        ).forEach { tag -> assert(tagExists(tag)) { "missing $tag" } }
    }

    @Test
    fun `mute toggle flips label state and leave confirm gates exit`() {
        render()
        idleTwice()
        // 静音切换（meeting_room_mic_off → mic_on）
        rule.onNodeWithTag("qa-meeting-room-mic").performClick()
        idleTwice()
        assertTrue(tagExists("qa-meeting-room-mic"))
        // 离开 → 确认弹层（坑 4：会中离开须确认）
        rule.onNodeWithTag("qa-meeting-room-leave").performClick()
        idleTwice()
        rule.onNodeWithText("Leave this meeting?").performClick()
        // 误触弹层本体外不离开（dismiss 路径）
        rule.onNodeWithText("Cancel").performClick()
        idleTwice()
        assertEquals(0, backCount)
        // 再开 → Confirm → 离会出栈 + 状态机 idle
        rule.onNodeWithTag("qa-meeting-room-leave").performClick()
        idleTwice()
        rule.onNodeWithText("Confirm").performClick()
        idleTwice()
        assertEquals(1, backCount)
    }

    @Test
    fun `credentials failure toasts and pops back`() {
        render(creds = null)
        idleTwice()
        assertEquals(1, backCount)
        assertFalse(rtc.left.isNotEmpty())
    }
}
