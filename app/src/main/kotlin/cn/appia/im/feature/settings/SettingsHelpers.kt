package cn.appia.im.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化引导（M5-T9，RN lib/batteryOptimization 对照——设置页通知分区状态行）：
 * - 机型门（RN isBatteryGuideDevice）：仅 GMS 系（samsung/google/android）需要引导——
 *   其它厂商走自有推送通道，电池豁免无收益（RN 注释原文语义）
 * - 状态（RN isIgnoringBatteryOptimizations）：非目标机型/模块不可用/异常 → true（已豁免）
 * - 申请（RN requestIgnoreBatteryOptimizations）：ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *   系统弹窗（需 manifest REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 普通权限）
 * - 兜底（RN openBatteryOptimizationSettings）：ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 列表页
 */
object BatteryOptimization {

    /** RN isBatteryGuideDevice（lib/batteryOptimization/index.ts:33-41）。 */
    fun isBatteryGuideDevice(manufacturer: String): Boolean =
        manufacturer.lowercase() in setOf("samsung", "google", "android")

    /** 当前 app 是否已豁免电池优化；false = 受限制（状态行值）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (!isBatteryGuideDevice(Build.MANUFACTURER)) return true
        return runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return@runCatching true
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
    }

    /** 申请豁免（系统弹窗）。返回是否成功发起；失败走 [openSettings] 兜底（RN 同序）。 */
    fun requestIgnore(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
        true
    }.getOrDefault(false)

    /** 打开系统电池优化列表页（申请弹窗不可用时的兜底入口）。 */
    fun openSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

/**
 * RN SettingsScreen serverHostLabel（index.tsx:47-55）：URL host，解析失败回退
 * 剥协议取首段（`new URL(t).host` / `t.replace(/(^\w+:|^)\/\//, '').split('/')[0] ?? t`）。
 */
fun serverHostLabel(serverUrl: String): String {
    val t = serverUrl.trim()
    if (t.isEmpty()) return ""
    // RN new URL(t).host——host 为空（如 '://nohost/'）即走 catch 回退，同语义
    val host = runCatching { java.net.URI(t).host }.getOrNull()
    if (!host.isNullOrEmpty()) return host
    return t.replace(Regex("^(\\w+:)?//"), "").split("/").firstOrNull() ?: t
}

/**
 * RN getReadableAppVersion（utils/deviceInfo.ts:8-9）：`getReadableVersion()` =
 * `{versionName}.{buildNumber}`，末段 `.{n}` 改 `-{n}`（如 `0.5.0.1` → `0.5.0-1`）。
 */
fun readableAppVersion(versionName: String, versionCode: Long): String =
    "$versionName.$versionCode".replace(Regex("\\.(\\w+)$"), "-$1")

/** RN guest 删号判定（SettingsScreen:296 / LaborScreen:28）：username 含 `appia.guest`。 */
fun isGuestUser(username: String): Boolean = username.contains("appia.guest")
