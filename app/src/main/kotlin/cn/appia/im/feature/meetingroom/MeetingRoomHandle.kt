package cn.appia.im.feature.meetingroom

import android.content.Context
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.meeting.fetchMeetingCredentials
import cn.appia.im.core.meeting.resolveMeetingApiBase
import cn.appia.im.core.meeting.rtc.AgoraMeetingEngine
import cn.appia.im.core.meeting.rtc.AgoraRtcImpl
import cn.appia.im.domain.meeting.MeetingSessionController
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 会议房间装配束（M8-T7；T6 KDoc "Hilt 接线归 T7" 的落点）。**@Singleton（app 域）**：
 * 引擎/状态机须跨 Activity 重建存活（manifest configChanges 已覆盖旋转/深色切换，T9；单例域
 * 为 FGS/进程域第二重保险——评审 I-2 定级依据）；测试自建 fake [AgoraMeetingEngine]
 * 注入（不经 Hilt）。
 */
class MeetingRoomHandle(
    val engine: AgoraMeetingEngine,
    val machine: MeetingSessionStateMachine,
    val controller: MeetingSessionController,
    /** 横屏三触发源（M8-T9）：app 域——横屏记忆跨最小化出栈存活；默认值保测试直构兼容。 */
    val orientation: MeetingOrientationState = MeetingOrientationState(),
)

@Module
@InstallIn(SingletonComponent::class)
object MeetingRoomModule {
    /** 生产 credentials lambda：会话 token + serverUrl 同源链（meetingApiOrigin 覆盖源 AA 未落库——
     *  resolveMeetingApiBase(null, serverUrl)）；uid 策略在屏内（uidForMeeting）。 */
    @Provides
    @Singleton
    fun provideMeetingRoomHandle(
        @ApplicationContext context: Context,
        authStore: AuthSessionStore,
    ): MeetingRoomHandle {
        val machine = MeetingSessionStateMachine()
        val engine = AgoraMeetingEngine(AgoraRtcImpl(context))
        return MeetingRoomHandle(
            engine = engine,
            machine = machine,
            controller = MeetingSessionController(engine, machine) { channelName, uid ->
                val auth = authStore.load() ?: return@MeetingSessionController null
                fetchMeetingCredentials(
                    base = resolveMeetingApiBase(null, auth.serverUrl),
                    channelName = channelName,
                    uid = uid,
                    token = auth.token,
                    userId = auth.user.id,
                )
            },
        )
    }
}
