package cn.appia.im.feature.meetingroom

import cn.appia.im.core.meeting.rtc.AgoraMeetingEngine
import cn.appia.im.domain.meeting.MeetingSessionController
import cn.appia.im.domain.meeting.MeetingSessionStateMachine

/**
 * 会议房间装配束（M8-T7；T6 KDoc "Hilt 接线归 T7" 的落点——**手动构造**：MainActivity 持
 * `by lazy` 单例（app 会话级，随 Activity 寿命；引擎/状态机须跨路由存活故不进 RouteDeps 每次装配），
 * 测试自建 fake [AgoraMeetingEngine] 注入。
 */
class MeetingRoomHandle(
    val engine: AgoraMeetingEngine,
    val machine: MeetingSessionStateMachine,
    val controller: MeetingSessionController,
)
