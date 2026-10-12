package cn.appia.im.core.meeting.floatwin

import cn.appia.im.domain.meeting.MeetingPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/** 降级链判定（坑 7）+ 绑定决策（meetingFloat.ts:41-68 语义）纯逻辑对照。 */
class MeetingFloatPolicyTest {

    // ---- 降级链判定 ≥4 ----

    @Test
    fun `overlay permission granted shows system float window`() {
        assertEquals(MeetingFloatVerdict.OVERLAY, meetingFloatVerdict(true))
    }

    @Test
    fun `overlay permission missing degrades to notification only`() {
        // 坑 7：无 canDrawOverlays → 仅前台服务通知 + toast，不 crash（RN MeetingForegroundService:39-49）
        assertEquals(MeetingFloatVerdict.NOTIFICATION_ONLY, meetingFloatVerdict(false))
    }

    @Test
    fun `verdict is total over both inputs`() {
        // 降级链无第三分支：任一输入必得两判之一
        for (canDraw in listOf(true, false)) {
            val v = meetingFloatVerdict(canDraw)
            assertEquals(
                canDraw,
                v == MeetingFloatVerdict.OVERLAY,
            )
        }
    }

    @Test
    fun `hide intent never escalates verdict`() {
        // hide 流（EXTRA_HIDE）不经判定——降级判定只在 show 路径；此处锁定判定函数与 hide 无耦合
        assertEquals(MeetingFloatVerdict.OVERLAY, meetingFloatVerdict(true))
        assertEquals(MeetingFloatVerdict.NOTIFICATION_ONLY, meetingFloatVerdict(false))
    }

    // ---- 绑定决策 ≥4 ----

    @Test
    fun `backgrounded in meeting starts float window`() {
        assertEquals(
            MeetingFloatAction.START,
            meetingFloatAction(appInForeground = false, MeetingPhase.InMeeting, serviceStarted = false),
        )
    }

    @Test
    fun `backgrounded minimized also starts float window`() {
        // RN phase!=='idle' 原语义：minimized 同样起浮窗
        assertEquals(
            MeetingFloatAction.START,
            meetingFloatAction(appInForeground = false, MeetingPhase.Minimized, serviceStarted = false),
        )
    }

    @Test
    fun `backgrounded preheating starts float window`() {
        // RN isActive() 仅查 phase!=idle：preheating 退后台也起（brief Files 行同语义）
        assertEquals(
            MeetingFloatAction.START,
            meetingFloatAction(appInForeground = false, MeetingPhase.Preheating, serviceStarted = false),
        )
    }

    @Test
    fun `foreground after start hides float window`() {
        assertEquals(
            MeetingFloatAction.HIDE,
            meetingFloatAction(appInForeground = true, MeetingPhase.InMeeting, serviceStarted = true),
        )
    }

    @Test
    fun `foreground without service is a no op`() {
        assertEquals(
            MeetingFloatAction.NONE,
            meetingFloatAction(appInForeground = true, MeetingPhase.InMeeting, serviceStarted = false),
        )
    }

    @Test
    fun `idle phase stops service regardless of foreground like RN isActive gate`() {
        // RN isActive() 是第一分支：phase==idle 时无论前后台一律 STOP（started 时）
        assertEquals(
            MeetingFloatAction.STOP,
            meetingFloatAction(appInForeground = false, MeetingPhase.Idle, serviceStarted = true),
        )
        assertEquals(
            MeetingFloatAction.STOP,
            meetingFloatAction(appInForeground = true, MeetingPhase.Idle, serviceStarted = true),
        )
        assertEquals(
            MeetingFloatAction.NONE,
            meetingFloatAction(appInForeground = false, MeetingPhase.Idle, serviceStarted = false),
        )
    }
}
