package cn.appia.im.core.voice.platform

import android.app.Activity
import android.app.Application
import android.os.Vibrator
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AndroidVoiceRinger 铃声归属（M10-T9 Fix round 1 裁定回归）：SELF_MANAGED 来电系统侧
 * **零铃声零振动**（AOSP Ringer.java getRingerAttributes 对 isSelfManaged endEarly）——
 * Telecom 上报与否与应用内铃声无关（原「已上报→不响」门已废）。ShadowVibrator 承载
 * 断言面（Ringtone 无 shadow 断言）；前台门经 Robolectric ActivityController 走真
 * onStart/onStop（VoiceForegroundTracker 计数路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidVoiceRingerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val shadowVibrator = shadowOf(context.getSystemService(Vibrator::class.java))

    private val controller = Robolectric.buildActivity(Activity::class.java)
    private var activityStarted = false

    @Test
    fun `rings in foreground even when Telecom reported`() {
        // Fix round 1 回归钉死：Telecom 上报成功（系统侧零铃声）→ 前台应用内仍响
        TelecomCallBridge.clearIncomingReported()
        TelecomCallBridge.ensurePhoneAccountRegistered(context)
        val bridge = TelecomCallBridge(context, context.getSystemService(TelecomManager::class.java))
        assertTrue(bridge.reportIncomingCall("r-1", "Alice", {}, {}))
        assertTrue(TelecomCallBridge.hasActiveIncomingReport())

        setForeground(true)
        val ringer = AndroidVoiceRinger(context)
        ringer.start()
        assertTrue(shadowVibrator.isVibrating)
        ringer.stop()
        assertTrue(shadowVibrator.isCancelled)
        bridge.setDisconnected("r-1")
    }

    @Test
    fun `does not ring in background`() {
        setForeground(false)
        AndroidVoiceRinger(context).start()
        assertFalse(shadowVibrator.isVibrating)
    }

    @Test
    fun `start is idempotent while ringing`() {
        setForeground(true)
        val ringer = AndroidVoiceRinger(context)
        ringer.start()
        ringer.start() // ringing 旗标短路，不重复起播
        assertTrue(shadowVibrator.isVibrating)
        ringer.stop()
    }

    @Test
    fun `foreground flip stops and restarts playback while ringing`() {
        setForeground(true)
        val ringer = AndroidVoiceRinger(context)
        ringer.start()
        assertTrue(shadowVibrator.isVibrating)
        // 响铃中切后台 → 停（RN VoiceCallHost :110-125 AppState 分流）
        setForeground(false)
        assertTrue(shadowVibrator.isCancelled)
        assertFalse(shadowVibrator.isVibrating)
        // 切回前台 → 重启
        setForeground(true)
        assertTrue(shadowVibrator.isVibrating)
        ringer.stop()
    }

    /** 经真 Activity onStart/onStop 驱动 tracker 计数（不直改只读 foreground）。 */
    private fun setForeground(inForeground: Boolean) {
        VoiceForegroundTracker.ensureRegistered(context)
        if (VoiceForegroundTracker.foreground == inForeground) return
        if (inForeground) {
            if (activityStarted) controller.start() else controller.setup()
            activityStarted = true
        } else {
            controller.stop()
        }
    }
}
