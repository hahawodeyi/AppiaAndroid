package cn.appia.im.core.meeting.floatwin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 浮窗控制入口（RN MeetingFloatModule.kt 桥方法的 Kotlin 直移植——RN 桥壳褪去，Intent 语义
 * verbatim：startForegroundService（O+）/startService、start/hide 容 try-catch、stop 幂等）。
 */
object MeetingFloat {

    private const val TAG = "MeetingFloatModule"

    /** RN startFloatWindow(title, duration)。 */
    fun startFloatWindow(context: Context, title: String, duration: String) {
        send(context, "start meeting foreground service denied") {
            putExtra(MeetingForegroundService.EXTRA_TITLE, title)
            putExtra(MeetingForegroundService.EXTRA_DURATION, duration)
        }
    }

    /** RN hideFloatWindow()：服务不退，仅撤系统悬浮窗（EXTRA_HIDE）。 */
    fun hideFloatWindow(context: Context) {
        send(context, "hide meeting foreground service denied") {
            putExtra(MeetingForegroundService.EXTRA_HIDE, true)
        }
    }

    /** RN stopFloatWindow()。 */
    fun stopFloatWindow(context: Context) {
        context.stopService(Intent(context, MeetingForegroundService::class.java))
    }

    private inline fun send(context: Context, denyLog: String, configure: Intent.() -> Unit) {
        val intent = Intent(context, MeetingForegroundService::class.java).apply(configure)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, denyLog, e)
        }
    }
}
