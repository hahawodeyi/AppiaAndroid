package cn.appia.im.core.voice.platform

import android.content.Context
import android.media.AudioManager
import cn.appia.im.core.voice.rtc.InCallAudioRoute

/**
 * 通话音频路由（RN react-native-incall-manager InCallManager.start/stop/setSpeakerphoneOn
 * 的 Android 原生对位）：
 * - start：MODE_IN_COMMUNICATION（Agora 语音通话音频焦点模式）+ 默认听筒（speakerphone off）。
 * - stop：MODE_NORMAL + speakerphone off（防泄漏到媒体播放路由）。
 * - setSpeakerphoneOn：免提切换（Controller speakerOn 状态 → UI 按钮）。
 */
class AndroidInCallAudioRoute(context: Context) : InCallAudioRoute {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun start() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
    }

    override fun stop() {
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    override fun setSpeakerphoneOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }
}
