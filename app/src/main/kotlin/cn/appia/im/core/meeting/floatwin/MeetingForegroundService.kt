package cn.appia.im.core.meeting.floatwin

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import cn.appia.im.R
import cn.appia.im.core.push.PushNotificationChannels

/**
 * 会议前台服务（RN MeetingForegroundService.kt verbatim 移植）：
 * - 通知渠道 `meeting_active`：IMPORTANCE_LOW / 无声无振动——**单一定义在
 *   [PushNotificationChannels]**（坑 7 渠道对齐：Application.onCreate 预建，服务只引用 ID，
 *   不重复 createNotificationChannel）。
 * - API 34+ 必带 FOREGROUND_SERVICE_TYPE_SPECIAL_USE（:60-65 同款），manifest 声明
 *   specialUse + PROPERTY_SPECIAL_USE_FGS_SUBTYPE。
 * - 降级链（坑 7，[meetingFloatVerdict] 判定）：无 canDrawOverlays → 仅通知 + toast，
 *   不 crash（RN :39-49 同款）。
 * **偏离注记**：RN 的 Handler 延迟探测（sendMessage 无 delay）在此内联——同帧序内 hide 的
 * removeMeetingFloat 效果等价（start/hide 两个 onStartCommand 同一主线程批次顺序执行）。
 */
class MeetingForegroundService : Service() {

    companion object {
        const val TAG = "MeetingForegroundService"
        const val NOTIFICATION_ID = 0x4D454554
        const val EXTRA_TITLE = "title"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_HIDE = "hide"
    }

    private var floatWindow: MeetingFloatWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val duration = intent?.getStringExtra(EXTRA_DURATION).orEmpty()
        val notification = buildNotification(title, duration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.getBooleanExtra(EXTRA_HIDE, false) == true) {
            removeMeetingFloat()
            return START_STICKY
        }

        // 坑 7 降级链：无 overlay 权限 → 仅通知 + toast（RN :39-49 语义 verbatim）
        when (meetingFloatVerdict(Settings.canDrawOverlays(applicationContext))) {
            MeetingFloatVerdict.OVERLAY -> showMeetingFloat(title, duration)
            MeetingFloatVerdict.NOTIFICATION_ONLY -> {
                Log.w(TAG, "overlay permission missing, notification only")
                Toast.makeText(this, R.string.meeting_overlay_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeMeetingFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    private fun buildNotification(title: String, duration: String): Notification {
        val content = if (duration.isNotEmpty()) duration else getString(R.string.meeting_notification_active)
        return NotificationCompat.Builder(this, PushNotificationChannels.MEETING_ACTIVE_ID)
            // AA 无 mipmap/ic_launcher 资源（RN 原用）——平台通话图标代位（FGS 必须有 smallIcon）
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(
                getString(R.string.meeting_notification_title, title.ifEmpty { getString(R.string.meeting_notification_active) }),
            )
            .setContentText(content)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showMeetingFloat(title: String, duration: String) {
        removeMeetingFloat()
        floatWindow = MeetingFloatWindow.getInstance(applicationContext, title, duration)
        floatWindow?.show()
    }

    private fun removeMeetingFloat() {
        floatWindow?.remove()
        floatWindow = null
    }
}
