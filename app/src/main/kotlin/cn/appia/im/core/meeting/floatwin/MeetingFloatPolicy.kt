package cn.appia.im.core.meeting.floatwin

import cn.appia.im.domain.meeting.MeetingPhase

/**
 * 浮窗纯策略（M8-T8；坑 7 降级链 + meetingFloat.ts:41-68 绑定决策的纯化——系统层
 * [MeetingForegroundService]/[MeetingFloatAppStateWatcher] 只做平台调用）。
 */

/** 坑 7 降级链判定：有 overlay 权限 → 系统悬浮窗；无 → 仅前台服务通知 + toast（绝不 crash）。 */
enum class MeetingFloatVerdict { OVERLAY, NOTIFICATION_ONLY }

fun meetingFloatVerdict(canDrawOverlays: Boolean): MeetingFloatVerdict =
    if (canDrawOverlays) MeetingFloatVerdict.OVERLAY else MeetingFloatVerdict.NOTIFICATION_ONLY

/** 绑定决策动作（RN meetingFloat.ts sync 分支的枚举化）。 */
enum class MeetingFloatAction { START, HIDE, STOP, NONE }

/**
 * RN meetingFloat.ts:46-59 sync verbatim：
 * - 相位 idle（RN !isActive）→ serviceStarted 清零 + STOP（未起过则 NONE）
 * - 前台 → 已起过 HIDE（serviceStarted 保持 true——RN 同款，供下次前台再 hide），否则 NONE
 * - 后台 && 相位非 idle（**preheating 也算**，RN `phase!=='idle'` 原语义）→ START
 */
fun meetingFloatAction(
    appInForeground: Boolean,
    phase: MeetingPhase,
    serviceStarted: Boolean,
): MeetingFloatAction = when {
    phase == MeetingPhase.Idle -> if (serviceStarted) MeetingFloatAction.STOP else MeetingFloatAction.NONE
    appInForeground -> if (serviceStarted) MeetingFloatAction.HIDE else MeetingFloatAction.NONE
    else -> MeetingFloatAction.START
}
