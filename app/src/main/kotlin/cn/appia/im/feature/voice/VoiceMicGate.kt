package cn.appia.im.feature.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cn.appia.im.core.i18n.t
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 麦克风运行时权限门（RN requestVoiceMicrophonePermission.ts 的 Android 原生等价；
 * [cn.appia.im.domain.voice.VoiceSessionDeps.requestMicPermission] 的生产装配体）。
 *
 * RN PermissionsAndroid.request 三态（granted/拒绝/异常→Alert voice_error_mic_denied）收敛为
 * Boolean——拒绝 Alert 由本类直接弹（RN Alert 的等价承载）。
 *
 * 装配：MainActivity onCreate 挂 [attach]（appContext 供 i18n/Alert）；权限结果回灌
 * [deliverResult]（onRequestPermissionsResult）。已授权（含历史 granted）同步短路 true，
 * 不触发系统弹窗。
 *
 * ponytail: runBlocking 等系统弹窗结果（requestMicPermission 为 suspend 缝，Controller 调用点
 * 全在 IO scope）；60s 兜底 false 防系统弹窗被系统吞掉后永久挂起。宿主 Activity 不可达
 * （后台来电接听等）→ 拒绝 + Alert——RN PermissionsAndroid 同为 Activity 弹窗，行为等价。
 */
object VoiceMicGate {

    private val pending = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private var appContext: Context? = null

    @Volatile
    private var currentActivity: Activity? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** MainActivity onResume/onPause 维度的宿主引用（后台来电接听时 null → 判拒同 RN Activity 弹窗不可达）。 */
    fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    fun onActivityPaused() {
        currentActivity = null
    }

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** suspend 缝主体（deps.requestMicPermission 生产装配）：已授权短路；无宿主/请求中被判拒。 */
    suspend fun request(): Boolean {
        val ctx = appContext ?: return false
        if (hasMic(ctx)) return true
        val activity = currentActivity ?: return false
        return requestWithActivity(activity)
    }

    /**
     * RN 主体：系统弹窗 + 等结果。并发请求后者直接判拒
     * （重复来电并发场景罕见，拒绝即用户重按）。
     */
    private fun requestWithActivity(activity: Activity): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        if (!pending.compareAndSet(null, deferred)) {
            showDeniedAlert(activity)
            return false
        }
        try {
            activity.runOnUiThread {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE,
                )
            }
            val granted = runBlocking {
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(60)) { deferred.await() }
            } == true
            if (!granted) showDeniedAlert(activity)
            return granted
        } finally {
            pending.compareAndSet(deferred, null)
        }
    }

    /** MainActivity onRequestPermissionsResult 回灌。 */
    fun deliverResult(granted: Boolean) {
        pending.getAndSet(null)?.complete(granted)
    }

    private fun showDeniedAlert(activity: Activity) {
        val ctx = appContext ?: return
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setMessage(ctx.t("voice_error_mic_denied"))
                .setPositiveButton(ctx.t("voice_ok"), null)
                .show()
        }
    }

    private const val REQUEST_CODE = 41012
}
