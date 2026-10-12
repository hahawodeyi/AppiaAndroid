package cn.appia.im.feature.meetingroom

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cn.appia.im.core.meeting.floatwin.MeetingFloat
import cn.appia.im.core.meeting.floatwin.MeetingFloatAction
import cn.appia.im.core.meeting.floatwin.meetingFloatAction
import cn.appia.im.domain.meeting.MeetingSessionStateMachine

/**
 * 会议浮窗 × App 前后台绑定（RN src/services/meeting/meetingFloat.ts:41-68
 * bindMeetingFloatToAppState 的原生等价）：
 * - 退后台 && 相位非 idle（preheating 也算）→ startFloatWindow(title, duration)
 * - 回前台 → hide（服务常驻，通知保留——RN serviceStarted 旗标语义 verbatim 于
 *   [meetingFloatAction]）
 * - idle → stop。
 *
 * AppState 源 = 进程级 ActivityLifecycleCallbacks 计数（RN AppState.addEventListener 对位；
 * RN 的 bind 即时 sync 对应 onStart/onStop 各自触发）。相位变化本身不重评估——与 RN 一致
 * （仅 AppState 变化触发 sync）。
 * **偏离注记**：duration 由原生 [formatMeetingDuration](state.durationSec) 现算（RN 为 web 上报
 * 格式化串）。系统层启停委派 [MeetingFloat]，异常已在其中容错。
 */
object MeetingFloatAppStateWatcher {

    private var registered = false

    /** MainActivity onCreate 调；单例机（handle 单例同寿）只注册一次，Activity 重建不重复。 */
    fun ensureRegistered(app: Application, machine: MeetingSessionStateMachine?) {
        if (registered || machine == null) return
        registered = true
        var startedActivities = 0
        var serviceStarted = false
        val sync = { foreground: Boolean ->
            val state = machine.state.value
            when (meetingFloatAction(foreground, state.phase, serviceStarted)) {
                MeetingFloatAction.START -> {
                    serviceStarted = true
                    MeetingFloat.startFloatWindow(
                        app,
                        state.title.orEmpty(),
                        formatMeetingDuration(state.durationSec),
                    )
                }
                // RN hide 分支不动 serviceStarted（保持 true，供后续前台再 hide）
                MeetingFloatAction.HIDE -> MeetingFloat.hideFloatWindow(app)
                MeetingFloatAction.STOP -> {
                    serviceStarted = false
                    MeetingFloat.stopFloatWindow(app)
                }
                MeetingFloatAction.NONE -> Unit
            }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                sync(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) sync(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
