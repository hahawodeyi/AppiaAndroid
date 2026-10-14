package cn.appia.im.core.voice.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import cn.appia.im.domain.voice.VoiceRinger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程前后台跟踪（AppiaApplication.onCreate 调 [ensureRegistered]——注册先于任何 Activity
 * 启动，计数才不漏首帧；MeetingFloatAppStateWatcher 同款 ActivityLifecycleCallbacks 计数，
 * RN AppState listener 对位）。无 lifecycle-process 依赖（runtime-only 不可 import）。
 */
object VoiceForegroundTracker {

    @Volatile
    var foreground: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 按 Application 实例注册去重（MeetingFloatAppStateWatcher WeakHashMap 先例：测试中
     *  每方法重建 Application，进程级布尔会令新实例漏注册）。 */
    private val registered = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Application, Boolean>())

    fun ensureRegistered(app: Application) {
        if (registered.put(app, true) != null) return
        var startedActivities = 0
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) update(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) update(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun update(inForeground: Boolean) {
        foreground = inForeground
        listeners.forEach { it(inForeground) }
    }

    internal fun onForegroundChanged(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }
}

/**
 * 来电铃声/震动（RN services/voice/voiceRingtone.ts 原生等价）：
 * - **AppState-active 分流**（RN :15-21）：前台才自响——[VoiceForegroundTracker.foreground]。
 *   铃声归属（Fix round 1 裁定）：前台 = 应用内铃声+振动；后台可听告警由推送 voice_incoming
 *   通道（IMPORTANCE_HIGH+振动+系统默认铃声）承载；Telecom reportIncomingCall 承担音频
 *   焦点/系统通话元数据/降级链，**不承担铃声**——SELF_MANAGED 来电系统侧零铃声零振动
 *   （AOSP Ringer.java getRingerAttributes 对 isSelfManaged endEarly）。若未来改为系统
 *   托管（非 self-managed）连接，需重引入「已上报→不响」去重门。
 * - 震动 `[0,500,1000,500]` 循环（RN Vibration.vibrate 同参）+ RingtoneManager
 *   TYPE_RINGTONE 系统铃声循环播放（RN InCallManager.startRingtone('_BUNDLE_') 的 Android
 *   原生对位——AA 无打包 ringtone 资源，用系统默认来电铃声）。
 * - 响铃中前后台翻转：前台起播/后台停播（RN VoiceCallHost AppState listener :106-110 对位）。
 * - stop 幂等 + 容错（RN :24-33 try-catch ignore 同款）。
 */
class AndroidVoiceRinger(context: Context) : VoiceRinger {

    private val appContext = context.applicationContext
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var ringtone: Ringtone? = null
    private var vibrating = false

    /** RN incomingActive 模块旗标（start 重入短路）。 */
    private var ringing = false

    init {
        // 响铃中前后台翻转（ringer 为 Task 12 单例装配，listener 随进程存续）
        VoiceForegroundTracker.onForegroundChanged { inForeground ->
            if (ringing) {
                if (inForeground) startPlayback() else stopPlayback()
            }
        }
    }

    override fun start() {
        if (ringing) return
        ringing = true
        // RN :16-21 AppState.currentState === 'active' 才响
        if (VoiceForegroundTracker.foreground) startPlayback()
    }

    override fun stop() {
        if (!ringing) return
        ringing = false
        stopPlayback()
    }

    private fun startPlayback() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
                ?: return@runCatching
            ringtone = RingtoneManager.getRingtone(appContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "start ringtone failed", it) }
        runCatching {
            // RN Vibration.vibrate([0, 500, 1000, 500], true)——API 26+ VibrationEffect，
            // 以下 deprecated vibrate(long[], int) 为 minSdk 24 兜底
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(VIBRATION_PATTERN, 0)
            }
            vibrating = true
        }.onFailure { Log.w(TAG, "vibrate failed", it) }
    }

    private fun stopPlayback() {
        runCatching { ringtone?.stop() }.onFailure { /* RN :28-31 ignore 同款 */ }
        ringtone = null
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
    }

    private companion object {
        const val TAG = "AndroidVoiceRinger"
        val VIBRATION_PATTERN = longArrayOf(0, 500, 1000, 500)
    }
}
