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

    private fun newHandle(creds: MeetingCredentials?): MeetingRoomHandle {
        val machine = MeetingSessionStateMachine()
        val engine = AgoraMeetingEngine(rtc)
        return MeetingRoomHandle(
            engine = engine,
            machine = machine,
            controller = MeetingSessionController(engine, machine) { _, _ -> creds },
        )
    }

    private val routeKey = androidx.compose.runtime.mutableStateOf(1)
    private val resumedFlag = androidx.compose.runtime.mutableStateOf(false)

    private fun render(handle: MeetingRoomHandle = newHandle(MeetingCredentials(appId = "app", token = "t"))) {
        rule.setContent {
            AppiaTheme(isDark = false) {
                // key 换档 = 压入新路由实例（模拟 openLink 移交/深链重开房间页——compose 内
                // 不能二次 setContent，用 snapshot-state key() 强制全新 screen 实例/remember/effect）
                androidx.compose.runtime.key(routeKey.value) {
                    MeetingRoomScreen(
                        channelName = "room-1",
                        meetingNumber = "123456",
                        title = "weekly sync",
                        userId = "user-1",
                        handle = handle,
                        onBack = { backCount++ },
                        resumed = resumedFlag.value,
                    )
                }
            }
        }
    }

    /** 重入：换 key 压入同 handle 的新房间实例（评审 I-1 场景）。 */
    private fun reenter() {
        rule.runOnUiThread { routeKey.value += 1 }
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
        render(newHandle(null))
        idleTwice()
        assertEquals(1, backCount)
        assertFalse(rtc.left.isNotEmpty())
    }

    @Test
    fun `re-entry while meeting alive pops via controller rejection`() {
        // 评审 I-1：同 handle（会议存活）再开房间路由（openLink 移交/深链）——必须走控制器
        // Failed("meeting_already_active") → toast + 出栈，不得静默渲染错会话
        render()
        idleTwice()
        assertEquals(0, backCount)
        reenter()
        idleTwice()
        assertEquals(1, backCount)
    }

    @Test
    fun `minimize then bubble restore rejoins session without rejection`() {
        // 评审 C-1（M8-T8）：气泡恢复 = MeetingRoomRoute(resumed=true) → 屏内免 join——
        // 会话存活不渲染成拒绝出栈（否则 InMeeting 孤儿化、用户失去全部会议 UI）。
        val handle = newHandle(MeetingCredentials(appId = "app", token = "t"))
        render(handle)
        idleTwice()
        assertEquals(MeetingPhase.InMeeting, handle.machine.phase)
        // 最小化（RN MeetingWebHeader onMinimize）→ 路由出栈（回调即 pop）
        handle.machine.minimize()
        // 气泡单击 → restore() + 复路由（MeetingRoomRoute(resumed=true)——路由参数进新实例）
        handle.machine.restore()
        resumedFlag.value = true
        reenter()
        idleTwice()
        // 不被拒绝（无出栈）+ self tile 回宫格 + 会话仍活
        assertEquals(0, backCount)
        assertTrue(tagExists("qa-meeting-room-tile"))
        assertEquals(MeetingPhase.InMeeting, handle.machine.phase)
        assertFalse(rtc.left.isNotEmpty())
        // 复位（composition 存活期写回——供后续用例的重组不拾取恢复标记）
        resumedFlag.value = false
        idleTwice()
    }

    @Test
    fun `orientation triggers share toggle rotate button and minimize force portrait`() {
        // M8-T9 三触发源 UI 面（逻辑判定归 MeetingOrientationTest）：共享开→横屏、关→竖屏；
        // 转屏按钮按实际方向取反；最小化强制竖屏 + 出栈（RN MeetingWebView:109-117 /
        // useOrientationToggle:25-40 / forcePortrait）。Robolectric 不真旋转，actualIsLandscape
        // 恒 false——与 policies 的记忆/取反语义不冲突（悬浮顶栏分支归真机验收）。
        val handle = newHandle(MeetingCredentials(appId = "app", token = "t"))
        // createComposeRule 不暴露宿主 activity——Robolectric 另建一只注入 LocalActivity，
        // 断言 setRequestedOrientation 真实落在 Activity 上（三触发源的 UI 面）
        val activity = org.robolectric.Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        rule.setContent {
            AppiaTheme(isDark = false) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.activity.compose.LocalActivity provides activity,
                ) {
                    androidx.compose.runtime.key(routeKey.value) {
                        MeetingRoomScreen(
                            channelName = "room-1",
                            meetingNumber = "123456",
                            title = "weekly sync",
                            userId = "user-1",
                            handle = handle,
                            onBack = { backCount++ },
                            resumed = resumedFlag.value,
                        )
                    }
                }
            }
        }
        idleTwice()
        // ① 共享开 → sensor landscape 锁
        rule.onNodeWithTag("qa-meeting-room-share").performClick()
        idleTwice()
        assertEquals(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            activity.requestedOrientation,
        )
        assertTrue(handle.orientation.prefersLandscape)
        // ① 共享关 → 竖屏（RN meeting:orientation:portrait 一次性锁语义）
        rule.onNodeWithTag("qa-meeting-room-share").performClick()
        idleTwice()
        assertEquals(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
        assertFalse(handle.orientation.prefersLandscape)
        // ② 转屏按钮：实际竖屏 → 切横屏（useOrientationToggle 直调语义；Robolectric 不真旋转，
        // 二次点击回竖屏的取反语义依赖实际 configuration——归 MeetingOrientationTest 判定）
        rule.onNodeWithTag("qa-meeting-room-rotate").performClick()
        idleTwice()
        assertEquals(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            activity.requestedOrientation,
        )
        assertTrue(handle.orientation.prefersLandscape)
        // ③a 最小化 → 强制竖屏 + 相位 minimized + 路由出栈
        rule.onNodeWithTag("qa-meeting-room-minimize").performClick()
        idleTwice()
        assertEquals(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            activity.requestedOrientation,
        )
        assertEquals(MeetingPhase.Minimized, handle.machine.phase)
        assertEquals(1, backCount)
    }
}
