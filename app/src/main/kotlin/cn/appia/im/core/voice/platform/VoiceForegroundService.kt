package cn.appia.im.core.voice.platform

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.appia.im.R
import cn.appia.im.core.push.PushNotificationChannels

/**
 * 语音前台服务（RN VoiceForegroundService.kt 72 行 verbatim 移植）：
 * - 通知渠道 `voice_active`：IMPORTANCE_LOW / 无声无振动——**单一定义在
 *   [PushNotificationChannels]**（MeetingForegroundService 同款：Application.onCreate 预建，
 *   服务只引用 ID，不重复 createNotificationChannel）。
 * - API 30+ 必带 FOREGROUND_SERVICE_TYPE_MICROPHONE（RN :31-40 分支同款），manifest 声明
 *   microphone 类型。
 * - CATEGORY_CALL + PRIORITY_LOW 常驻通知（通话后台保活）。
 */
class VoiceForegroundService : Service() {

    companion object {
        const val TAG = "VoiceForegroundService"
        const val NOTIFICATION_ID = 0x564F4943 // "VOIC"（RN 同值）
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, PushNotificationChannels.VOICE_ACTIVE_ID)
            // AA 无 mipmap/ic_launcher 资源（RN 原用）——平台通话图标代位（FGS 必须有 smallIcon，
            // MeetingForegroundService 同款）
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.voice_notification_active))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

/**
 * FGS 启停入口（RN VoiceForegroundModule.kt 桥方法的 Kotlin 直移植——Intent 语义 verbatim：
 * startForegroundService（O+）/startService、start 容 try-catch、stop 幂等）。
 * RN 由 VoiceCallHost phase ∈ {outgoingRinging, connecting, inCall} 驱动（index.tsx:46-48）；
 * AA 由 Task 12 装配按 phase 接线。
 *
 * **命名注记**：类名 `VoiceFloatServiceStarter` 为 M10 计划接口面锁定名（Task 12 装配引用）；
 * 实际启动的是前台服务（无悬浮窗——语音来电 overlay 提示是另一特性，RN voice_float_*
 * strings 属之）。
 */
object VoiceFloatServiceStarter {

    private const val TAG = "VoiceForeground"

    fun start(context: Context, callerName: String) {
        try {
            val intent = Intent(context, VoiceForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "start voice foreground service denied", e)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, VoiceForegroundService::class.java))
    }
}
