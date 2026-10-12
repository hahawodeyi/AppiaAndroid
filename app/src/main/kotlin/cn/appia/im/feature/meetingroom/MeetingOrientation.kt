package cn.appia.im.feature.meetingroom

import cn.appia.im.domain.meeting.MeetingPhase

/** 请求方向（UI 层映射 ActivityInfo 常量；本层零 android 依赖，纯 JVM 可测）。 */
enum class MeetingOrientation { Portrait, Landscape }

/**
 * 横屏三触发源（M8-T9 / RN lib/orientation/useOrientationToggle.ts + features/meeting/
 * MeetingWebView/index.tsx:109-117 原生等价；存于 [MeetingRoomHandle]（app 域）——横屏记忆
 * 须跨最小化出栈存活）：
 * 1. **共享驱动**：共享开 → 横屏、关 → 竖屏（RN web meeting:orientation:landscape/portrait
 *    一次性锁不持状态；AA 原生引擎无共享 RTC 事件，以控制条共享开关为源）。
 * 2. **顶栏转屏按钮**：按**实际**方向取反直调（RN toggle:25-40 不依赖 state——共享一次性锁
 *    会让 state 与实际方向脱钩，按 state 切会撞同值 no-op 不切换；actualIsLandscape 由调用方
 *    读 configuration 实际方向传入）。
 * 3. **最小化强制竖屏**（RN forcePortrait）/气泡恢复回记忆方向（RN forcePortrait=false 时
 *    effect 重跑同义）。
 *
 * **互斥接缝（坑 6，M10 对接）**：会议侧阻塞查询 = [cn.appia.im.domain.meeting.
 * MeetingSessionStateMachine.blocksVoiceStart]（仅 InMeeting|Minimized，T6 已落地 + 已测）；
 * 语音侧阻塞会议 = [cn.appia.im.domain.meeting.meetingBlockedByVoice]（任意非 idle）。M10
 * 语音状态机落地后，在入会站点（MainActivity.openMeetingRoom 三处 seam）以 voice phase 调
 * meetingBlockedByVoice 收口即可——本域不新增语音状态。
 */
class MeetingOrientationState {

    /** 横屏记忆（共享开/手动转横屏置位；最小化保留——恢复依据）。 */
    var prefersLandscape: Boolean = false
        private set

    /** 触发①共享开关：开→横屏、关→竖屏（RN MeetingWebView:109-117 一次性锁语义）。 */
    fun onShareToggled(sharing: Boolean): MeetingOrientation {
        prefersLandscape = sharing
        return if (sharing) MeetingOrientation.Landscape else MeetingOrientation.Portrait
    }

    /** 触发②转屏按钮：按调用方读到的当前**实际**方向取反（useOrientationToggle.toggle 模式）。 */
    fun toggle(actualIsLandscape: Boolean): MeetingOrientation {
        prefersLandscape = !actualIsLandscape
        return if (actualIsLandscape) MeetingOrientation.Portrait else MeetingOrientation.Landscape
    }

    /** 触发③a 最小化：强制竖屏（RN forcePortrait）；记忆保留供恢复。 */
    fun onMinimized(): MeetingOrientation = MeetingOrientation.Portrait

    /** 触发③b 气泡恢复：回最小化前记忆方向。 */
    fun onRestored(): MeetingOrientation =
        if (prefersLandscape) MeetingOrientation.Landscape else MeetingOrientation.Portrait
}

/**
 * 会中常亮判定（RN lib/keepAwake useKeepScreenOn → FLAG_KEEP_SCREEN_ON）。**AA 定版与 RN
 * 分歧**：仅 InMeeting 亮屏，最小化气泡即清（RN 最小化仍亮，任务 9 scope 3）；退出由房间屏
 * 卸载 onDispose 清除。
 */
fun meetingKeepScreenOn(phase: MeetingPhase): Boolean = phase == MeetingPhase.InMeeting
