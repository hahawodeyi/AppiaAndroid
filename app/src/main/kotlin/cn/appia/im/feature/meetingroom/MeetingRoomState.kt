package cn.appia.im.feature.meetingroom

import cn.appia.im.core.meeting.rtc.MeetingRtcEvent
import cn.appia.im.domain.meeting.MeetingPhase
import cn.appia.im.domain.meeting.MeetingSessionState

/**
 * 会议房间 UI 纯态层（M8-T7；绿地面板：视频宫格/时长/静音映射——全部可 JVM 直测）。
 * RN 规格源：meetingSessionStore 字段（title/meetingNumber/duration/participantCount/isMuted）+
 * 桥协议 meeting:kicked/meeting:ended 语义（research §1.3）；participant 宫格为原生 RTC 事件的新映射。
 */

/** 宫格参与者 tile（uid 域 = Agora Int uid；self = 本端）。 */
data class MeetingRoomParticipant(
    val uid: Int,
    val isSelf: Boolean = false,
    /** 麦克风开（本端 = !isMuted；远端 = RemoteAudioChanged STARTED）。 */
    val micOn: Boolean = true,
    /** 视频出帧中（远端 RemoteVideoChanged DECODING；本端 = 摄像头开关）。 */
    val videoDecoding: Boolean = false,
)

/** 引擎事件 → 宫格折叠（纯函数；self tile 由 Joined 插入、Left 摘除，重复事件幂等）。 */
fun foldMeetingRoomParticipants(
    current: List<MeetingRoomParticipant>,
    selfUid: Int,
    event: MeetingRtcEvent,
): List<MeetingRoomParticipant> = when (event) {
    is MeetingRtcEvent.Joined ->
        if (current.any { it.isSelf }) current else current + MeetingRoomParticipant(selfUid, isSelf = true)
    is MeetingRtcEvent.Left -> current.filterNot { it.isSelf }
    is MeetingRtcEvent.UserJoined ->
        if (event.uid == selfUid || current.any { !it.isSelf && it.uid == event.uid }) current
        else current + MeetingRoomParticipant(event.uid)
    is MeetingRtcEvent.UserOffline -> current.filterNot { !it.isSelf && it.uid == event.uid }
    is MeetingRtcEvent.RemoteAudioChanged -> current.map {
        if (!it.isSelf && it.uid == event.uid) it.copy(micOn = event.started) else it
    }
    is MeetingRtcEvent.RemoteVideoChanged -> current.map {
        if (!it.isSelf && it.uid == event.uid) it.copy(videoDecoding = event.decoding) else it
    }
    is MeetingRtcEvent.Stats, is MeetingRtcEvent.Error -> current
}

/** 本端静音回写宫格（控制条动作；与 machine.setMicMuted 同源翻转）。 */
fun applySelfMic(participants: List<MeetingRoomParticipant>, muted: Boolean): List<MeetingRoomParticipant> =
    participants.map { if (it.isSelf) it.copy(micOn = !muted) else it }

/** 本端摄像头回写宫格（控制条动作；camera 发布默认关——T6 ChannelMediaOptions）。 */
fun applySelfCamera(participants: List<MeetingRoomParticipant>, cameraOn: Boolean): List<MeetingRoomParticipant> =
    participants.map { if (it.isSelf) it.copy(videoDecoding = cameraOn) else it }

/** 本端 uid：userId 稳定 hash（joinChannel 要求 Int；0 为 SDK 保留值故避开；空 id 兜底常量）。 */
fun uidForMeeting(userId: String?): Int {
    val h = userId?.trim()?.takeIf { it.isNotEmpty() }.orEmpty().ifEmpty { "appia" }.hashCode()
    return if (h == 0) 1 else h
}

/** 会中时长格式化（RN duration 为 web 上报格式化串；原生 mm:ss / 逾时 h:mm:ss）。 */
fun formatMeetingDuration(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** 房间级信号（引擎事件的状态分叉；RN meeting:kicked / meeting:ended 的原生近似）。 */
enum class MeetingRoomSignal { NONE, KICKED, ENDED }

/**
 * 引擎事件 → 房间动作映射：inMeeting 中 Error → KICKED（原生面无显式被踢信号，SDK onError 为
 * 最近似——KDoc 边界如实）；非 idle 收 Left → ENDED（meeting:ended 对位）。idle 相位一律 NONE。
 */
fun meetingRoomSignalFor(state: MeetingSessionState, event: MeetingRtcEvent): MeetingRoomSignal = when {
    event is MeetingRtcEvent.Error && state.phase == MeetingPhase.InMeeting -> MeetingRoomSignal.KICKED
    event is MeetingRtcEvent.Left && state.phase != MeetingPhase.Idle -> MeetingRoomSignal.ENDED
    else -> MeetingRoomSignal.NONE
}
