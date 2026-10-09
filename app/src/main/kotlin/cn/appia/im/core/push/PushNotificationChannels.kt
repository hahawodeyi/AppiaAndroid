package cn.appia.im.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 通知通道预建（Application.onCreate 最先调，先于推送 init）。
 *
 * `channel_01` 是阿里云 payload 的默认通知通道：不预建则系统按默认低重要性自动建，
 * 进程存活时前台无横幅/声音/震动（坑 3）；且已存在通道的重要性不可覆盖，必须首发即高。
 */
object PushNotificationChannels {
    const val ALIYUN_FALLBACK_ID = "channel_01"
    const val DEFAULT_IM_ID = "im_default"

    // voice_incoming（来电铃声 + 长震通道）归 M10 语音域，届时在 ensureCreated 补建。

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 阿里云默认通道：必须 IMPORTANCE_HIGH（坑 3）
        manager.createNotificationChannel(
            NotificationChannel(ALIYUN_FALLBACK_ID, "Push messages", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(DEFAULT_IM_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableVibration(true)
            },
        )
    }
}
