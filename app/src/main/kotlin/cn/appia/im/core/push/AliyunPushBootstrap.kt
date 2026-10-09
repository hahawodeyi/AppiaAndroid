package cn.appia.im.core.push

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.alibaba.sdk.android.push.CommonCallback
import com.alibaba.sdk.android.push.noonesdk.PushInitConfig
import com.alibaba.sdk.android.push.noonesdk.PushServiceFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阿里云推送原生初始化（Application.onCreate 直调）。
 *
 * 推送冷拉起的进程不经过任何界面，若 SDK 未在此完成 init，离线消息处理会因缺
 * appKey/appSecret 失败、通知无法展示。失败仅记日志不抛异常——推送不可用不得阻断 App。
 */
object AliyunPushBootstrap {
    private const val TAG = "push:init"

    /** 注册成功 */
    const val CODE_SUCCESS = "10000"

    /** manifest meta-data 键（与 EMAS 控制台 appKey/appSecret 一致） */
    const val META_APPKEY = "com.alibaba.app.appkey"
    const val META_APPSECRET = "com.alibaba.app.appsecret"

    /** 已注册（原生先行后的重复注册），语义上同为成功——坑 1：按失败处理会导致点击监听永不注册 */
    const val CODE_ALREADY_REGISTERED = "PUSH_20110"

    private val started = AtomicBoolean(false)

    /** 结果码谓词：10000 与 PUSH_20110 均视为成功（坑 1） */
    fun isSuccessCode(code: String?): Boolean =
        code == CODE_SUCCESS || code == CODE_ALREADY_REGISTERED

    /** 幂等闸门：仅第一次 init 生效；CAS 失败 = 已有先行调用 */
    internal fun tryStart(): Boolean = started.compareAndSet(false, true)

    /**
     * configLoader 可注入以便 JVM/Robolectric 测试替换 meta-data 来源。
     * 返回 null = meta-data 缺失，跳过初始化（允许后续调用重试）。
     */
    fun init(application: Application, configLoader: (Context) -> PushConfig? = ::readManifestPushConfig) {
        if (!tryStart()) {
            Log.w(TAG, "aliyun push init already started, skip")
            return
        }
        val config = configLoader(application)
        if (config == null) {
            Log.e(TAG, "aliyun push meta-data missing, skip native init")
            started.set(false)
            return
        }

        PushServiceFactory.init(
            PushInitConfig.Builder()
                .application(application)
                .appKey(config.appKey)
                .appSecret(config.appSecret)
                .build(),
        )

        val pushService = PushServiceFactory.getCloudPushService()
        pushService.register(
            application,
            object : CommonCallback {
                override fun onSuccess(response: String?) {
                    if (isSuccessCode(response)) {
                        Log.i(TAG, "push register success: $response")
                    } else {
                        Log.w(TAG, "push register unexpected success code: $response")
                    }
                }

                override fun onFailed(errorCode: String?, errorMessage: String?) {
                    Log.e(TAG, "push register failed: $errorCode $errorMessage")
                }
            },
        )
        pushService.turnOnPushChannel(null)
    }

    /** 测试钩子：仅测试使用，重置幂等闸门 */
    internal fun resetForTest() {
        started.set(false)
    }
}

/** manifest meta-data 中解析出的推送凭据 */
data class PushConfig(val appKey: String, val appSecret: String)

fun readManifestPushConfig(context: Context): PushConfig? {
    val metaData: Bundle = try {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?: return null
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    // appkey 是纯数字，manifest 合并后可能落为整型资源，两种形态都要接
    val appKey = metaData.getString(AliyunPushBootstrap.META_APPKEY)
        ?: metaData.getInt(AliyunPushBootstrap.META_APPKEY, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.toString()
    val appSecret = metaData.getString(AliyunPushBootstrap.META_APPSECRET)
    if (appKey.isNullOrBlank() || appSecret.isNullOrBlank()) return null
    return PushConfig(appKey, appSecret)
}
