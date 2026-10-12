package cn.appia.im.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import cn.appia.im.R

/**
 * 通知通道预建（Application.onCreate 最先调，先于推送 init）。
 *
 * `channel_01` 是阿里云 payload 的默认通知通道：不预建则系统按默认低重要性自动建，
 * 进程存活时前台无横幅/声音/震动（坑 3）；且已存在通道的重要性不可覆盖，必须首发即高。
 */
object PushNotificationChannels {
    const val ALIYUN_FALLBACK_ID = "channel_01"
    const val DEFAULT_IM_ID = "im_default"

    /**
     * 会议前台服务通道（M8-T8 / 坑 7 渠道单一定义：RN MeetingForegroundService CHANNEL_ID 同名，
     * IMPORTANCE_LOW 无声无振动；服务只引用本常量，不再自建）。voice_incoming 归 M10。
     */
    const val MEETING_ACTIVE_ID = "meeting_active"

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
            // RN VoiceNotificationChannels.kt:64 im_default 同为 IMPORTANCE_HIGH——
            // 通道重要性首建后不可覆盖；RN VoiceNotificationChannels.kt:64 同为 HIGH
            NotificationChannel(DEFAULT_IM_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
        // 会议进行中通道（RN MeetingForegroundService.ensureChannel :92-106 verbatim 语义——
        // IMPORTANCE_LOW、无声、无振动；文案挪用其原 strings 资源键）
        manager.createNotificationChannel(
            NotificationChannel(
                MEETING_ACTIVE_ID,
                context.getString(R.string.notification_channel_meeting_active),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_meeting_active_desc)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}
