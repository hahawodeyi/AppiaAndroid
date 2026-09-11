package cn.appia.im

import android.app.Application
import com.tencent.mmkv.MMKV
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AppiaApplication : Application() {
    override fun onCreate() {
        // MMKV 先于任何 Hilt 注入消费初始化（KvStoreModule 只负责取 defaultMMKV）
        MMKV.initialize(this)
        super.onCreate()
    }
}
