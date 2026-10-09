package cn.appia.im.core.push

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 电池优化检测与引导（RN lib/batteryOptimization/guide.ts:17-42 + index.ts）。
 *
 * 系统值即唯一真相源：每次实时查询 isIgnoringBatteryOptimizations，未豁免且命中机型才引导；
 * 用户在系统侧允许后，下一次检测自然不再弹（RN guide.ts:13-15 注释同义）。
 * 去重：RN 冷启动触发自带 bootstrapped 每进程一次、登录触发每次登录重查；AA 两触发点
 * （冷启动 Main 挂载 + 登录成功 goMain 重挂 Main）收敛为同一挂载效果，按任务要求加
 * **会话内至多一弹**（进程级内存标志，不持久化——冷启动会话允许再弹一次）。
 */
object BatteryOptimizationGuide {

    private val shownThisSession = AtomicBoolean(false)

    /** 仅 GMS 系机型弹引导（RN index.ts:30-38）：厂商通道机型通知不依赖进程存活，弹窗无收益。 */
    fun isBatteryGuideDevice(manufacturer: String): Boolean =
        manufacturer.lowercase() in setOf("samsung", "google", "android")

    /** 纯判定（TDD 缝）：目标机型 ∧ 未豁免 ∧ 本会话未弹过。 */
    fun shouldShow(isTargetDevice: Boolean, isIgnoring: Boolean, shownThisSession: Boolean): Boolean =
        isTargetDevice && !isIgnoring && !shownThisSession

    /** 平台检测（RN isIgnoringBatteryOptimizations :50-60 同义：非目标机型直接视为无需引导）。 */
    fun shouldShowNow(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val show = shouldShow(
            isTargetDevice = isBatteryGuideDevice(Build.MANUFACTURER),
            isIgnoring = power.isIgnoringBatteryOptimizations(context.packageName),
            shownThisSession = shownThisSession.get(),
        )
        if (show) shownThisSession.set(true)
        return show
    }

    /**
     * 「去设置」（RN guide.ts:31-37）：系统弹窗申请豁免；
     * 弹窗不可用（ROM 封禁，等价 RN started=false）兜底开电池优化列表页。
     */
    fun requestIgnore(activity: Activity) {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        try {
            activity.startActivity(request)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
