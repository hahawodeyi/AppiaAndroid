package cn.appia.im.core.push

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import cn.appia.im.core.i18n.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会议通知渠道（M8-T8 / 坑 7 渠道单一定义）：meeting_active 只在 [PushNotificationChannels]
 * 预建（IMPORTANCE_LOW 无声无振动——RN MeetingForegroundService.ensureChannel :92-106 同语义），
 * 服务只引用 ID；i18n 冒烟覆盖浮窗通知文案键。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeetingNotificationChannelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `meeting_active channel created once with low importance silent`() {
        PushNotificationChannels.ensureCreated(context)
        PushNotificationChannels.ensureCreated(context) // 幂等（createNotificationChannel 同 ID 覆盖）
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(PushNotificationChannels.MEETING_ACTIVE_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(false, channel.shouldVibrate())
        assertEquals(null, channel.sound)
    }

    @Test
    fun `float notification text keys resolve`() {
        assertEquals("Meeting in progress", context.t("meeting_notification_active"))
        assertTrue(context.t("meeting_notification_title", "Weekly Sync").startsWith("Meeting in progress"))
        assertTrue(context.t("meeting_overlay_permission_denied").contains("Overlay permission"))
        assertEquals("Active meetings", context.t("notification_channel_meeting_active"))
        assertFalse(context.t("meeting_mini_default_title") == "meeting_mini_default_title")
    }
}
