package cn.appia.im

import android.app.Application
import cn.appia.im.core.push.AliyunPushBootstrap
import cn.appia.im.core.push.PushNotificationChannels
import cn.appia.im.core.voice.platform.TelecomCallBridge
import cn.appia.im.core.voice.platform.VoiceForegroundTracker
import com.tencent.mmkv.MMKV
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AppiaApplication : Application() {
    override fun onCreate() {
        // MMKV 先于任何 Hilt 注入消费初始化（KvStoreModule 只负责取 defaultMMKV）
        MMKV.initialize(this)
        super.onCreate()
        // RN MainApplication 同序：先预建通知通道（channel_01 必须 IMPORTANCE_HIGH，坑 3），
        // 再初始化推送——推送冷拉起进程不经过界面，SDK 必须在此完成 init
        PushNotificationChannels.ensureCreated(this)
        AliyunPushBootstrap.init(this)
        // M10-T9：自管 PhoneAccount 注册（runCatching 兜底——注册失败不崩启动，
        // 后续 reportIncomingCall 的 hasAccountPermission 前置会降级 in-app 路径）
        runCatching { TelecomCallBridge.ensurePhoneAccountRegistered(this) }
        // M10-T9：进程前后台跟踪（铃声 AppState-active 分流）——先于任何 Activity 启动注册
        VoiceForegroundTracker.ensureRegistered(this)
    }
}
