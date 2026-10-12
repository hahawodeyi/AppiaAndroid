package cn.appia.im.feature.meetingroom

import cn.appia.im.core.meeting.floatwin.MeetingFloatAction
import cn.appia.im.domain.meeting.MeetingSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 浮窗绑定相位重评估（评审 I-1）：前台/后台结束会议（phase→idle）即 STOP——FGS+常驻通知
 * 不滞留到下次退后台（RN MeetingWebHost:49-63 cleanup 在相位翻转即 stop）。纯 JVM 直测。
 */
class MeetingFloatBindingTest {

    private class Recorder {
        val actions = mutableListOf<MeetingFloatAction>()
        lateinit var binding: MeetingFloatBinding
            private set
        fun bind(machine: MeetingSessionStateMachine, scope: CoroutineScope): MeetingFloatBinding =
            MeetingFloatBinding(machine, { actions += it }, scope).also { binding = it }
    }

    @Test
    fun `foreground session flips never touch an unstarted service`() {
        val machine = MeetingSessionStateMachine()
        val rec = Recorder()
        runBlocking {
            rec.bind(machine, CoroutineScope(Dispatchers.Unconfined))
            rec.binding.onForegroundChanged(true)
            // 前台正常入会/最小化/恢复/结束：相位翻转均 NONE（服务从未起过，无 START 也无 STOP）
            machine.openPreheat("Weekly Sync", "888888")
            machine.markInMeeting("Weekly Sync", "888888")
            machine.minimize()
            machine.restore()
            machine.end()
            repeat(3) { yield() }
            assertEquals(emptyList<MeetingFloatAction>(), rec.actions)
        }
    }

    @Test
    fun `background start foreground hide then stop on end`() {
        val machine = MeetingSessionStateMachine()
        val rec = Recorder()
        runBlocking {
            val binding = rec.bind(machine, CoroutineScope(Dispatchers.Unconfined))
            binding.onForegroundChanged(true)
            machine.openPreheat("Weekly Sync", "888888")
            machine.markInMeeting("Weekly Sync", "888888")
            assertEquals(emptyList<MeetingFloatAction>(), rec.actions)
            // 退后台起浮窗（RN phase!=idle → start）
            binding.onForegroundChanged(false)
            assertEquals(listOf(MeetingFloatAction.START), rec.actions)
            // 回前台 hide（服务常驻）
            binding.onForegroundChanged(true)
            assertEquals(listOf(MeetingFloatAction.START, MeetingFloatAction.HIDE), rec.actions)
            // 前台结束 → STOP（I-1）
            machine.end()
            repeat(3) { yield() }
            assertEquals(
                listOf(MeetingFloatAction.START, MeetingFloatAction.HIDE, MeetingFloatAction.STOP),
                rec.actions,
            )
        }
    }

    @Test
    fun `background meeting end stops without foreground round trip`() {
        val machine = MeetingSessionStateMachine()
        val rec = Recorder()
        runBlocking {
            val binding = rec.bind(machine, CoroutineScope(Dispatchers.Unconfined))
            binding.onForegroundChanged(true)
            machine.openPreheat("Weekly Sync", "888888")
            machine.markInMeeting("Weekly Sync", "888888")
            binding.onForegroundChanged(false)
            assertEquals(listOf(MeetingFloatAction.START), rec.actions)
            // 后台被踢/远端结束（phase→idle）→ 直接 STOP（评审 I-1 场景）
            machine.kicked()
            repeat(3) { yield() }
            assertEquals(listOf(MeetingFloatAction.START, MeetingFloatAction.STOP), rec.actions)
        }
    }

    @Test
    fun `minimize and restore in foreground do not touch service`() {
        val machine = MeetingSessionStateMachine()
        val rec = Recorder()
        runBlocking {
            val binding = rec.bind(machine, CoroutineScope(Dispatchers.Unconfined))
            binding.onForegroundChanged(true)
            machine.openPreheat("Weekly Sync", "888888")
            machine.markInMeeting("Weekly Sync", "888888")
            machine.minimize()
            machine.restore()
            repeat(3) { yield() }
            assertEquals(emptyList<MeetingFloatAction>(), rec.actions)
        }
    }
}
