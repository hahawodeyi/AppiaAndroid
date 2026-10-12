package cn.appia.im.feature.meetingroom

import android.app.Activity
import android.app.Application
import android.os.Bundle
import cn.appia.im.core.meeting.floatwin.MeetingFloat
import cn.appia.im.core.meeting.floatwin.MeetingFloatAction
import cn.appia.im.core.meeting.floatwin.meetingFloatAction
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
/**
 * 会议浮窗绑定（RN meetingFloat.ts:41-68 bindMeetingFloatToAppState 原生等价）：
 * - 退后台 && 相位非 idle（preheating 也算）→ START(title, duration)
 * - 回前台 → HIDE（服务常驻，通知保留——RN serviceStarted 旗标语义 verbatim 于
 *   [meetingFloatAction]）
 * - idle → STOP。
 *
 * 触发源两路（评审 I-1）：**进程前后台变化**（ActivityLifecycleCallbacks 计数 = RN AppState
 * listener 对位）与**相位变化**（评审 I-1 修复：RN MeetingWebHost:49-63 cleanup 在相位翻转即
 * stop——前台结束会议不滞留 FGS+常驻通知；每次 tick 去重只对相位翻转重评估）。
 */
class MeetingFloatBinding(
    private val machine: MeetingSessionStateMachine,
    /** 动作出口（测试注入收集；生产 = MeetingFloat 启停）。 */
    private val dispatch: (MeetingFloatAction) -> Unit,
    scope: CoroutineScope,
) {
    private var serviceStarted = false
    private var foreground = false

    init {
        scope.launch {
            var lastPhase = machine.state.value.phase
            machine.state.collect { st ->
                if (st.phase != lastPhase) {
                    lastPhase = st.phase
                    sync()
                }
            }
        }
    }

    /** 前后台翻转（Activity 计数 0↔1）。 */
    fun onForegroundChanged(appInForeground: Boolean) {
        foreground = appInForeground
        sync()
    }

    private fun sync() {
        val state = machine.state.value
        when (meetingFloatAction(foreground, state.phase, serviceStarted)) {
            MeetingFloatAction.START -> {
                serviceStarted = true
                dispatch(MeetingFloatAction.START)
            }
            // RN hide 分支不动 serviceStarted（保持 true，供后续前台再 hide）
            MeetingFloatAction.HIDE -> dispatch(MeetingFloatAction.HIDE)
            MeetingFloatAction.STOP -> {
                serviceStarted = false
                dispatch(MeetingFloatAction.STOP)
            }
            MeetingFloatAction.NONE -> Unit
        }
    }
}

/**
 * 进程级挂载（MainActivity onCreate 调，单例机同寿只注册一次——Activity 重建幂等）。
 * **偏离注记**：duration 由原生 [formatMeetingDuration](state.durationSec) 现算（RN 为 web
 * 上报格式化串）；scope 为进程生命周期单例（随进程结束，无显式取消点）。
 */
object MeetingFloatAppStateWatcher {

    private val bound =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<MeetingSessionStateMachine, MeetingFloatBinding>())

    /** 幂等（Activity 重建不重复挂 callbacks/相位收集/引擎事件兜底）。 */
    fun ensureRegistered(app: Application, handle: MeetingRoomHandle?) {
        val machine = handle?.machine ?: return
        if (bound.containsKey(machine)) return
        var startedActivities = 0
        val binding = MeetingFloatBinding(machine, { action ->
            val state = machine.state.value
            when (action) {
                MeetingFloatAction.START ->
                    MeetingFloat.startFloatWindow(
                        app,
                        state.title.orEmpty(),
                        formatMeetingDuration(state.durationSec),
                    )
                MeetingFloatAction.HIDE -> MeetingFloat.hideFloatWindow(app)
                MeetingFloatAction.STOP -> MeetingFloat.stopFloatWindow(app)
                MeetingFloatAction.NONE -> Unit
            }
        }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) binding.onForegroundChanged(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) binding.onForegroundChanged(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        bound[machine] = binding
        // 终审 I-2：进程级引擎事件兜底（RN meeting:kicked 全局 store 对位）。房间屏的事件收集随
        // 出栈终止、SharedFlow 无 replay——**Minimized** 期间到达的 kicked(Error)/ended(Left)
        // 在此消费（判定与循环见 [collectRoomEndFallback]）。
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            collectRoomEndFallback(handle)
        }
    }
}
