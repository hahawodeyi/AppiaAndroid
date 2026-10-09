package cn.appia.im.core.push

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通知权限运行时申请（**故意分歧：RN 从不申请**——manifest:46 仅声明、全库无 request，坑 4；
 * Android 13+ 默认拒绝时通知被静默抑制。AA 在 Main 首次挂载（登录成功/冷启动恢复会话都汇入）
 * 时申请，**每个进程会话至多一次**：用户拒绝不缠磨，下次冷启动会话再试一次）。
 */
object NotificationPermissionGate {
    private const val TAG = "push:perm"

    private val requestedThisSession = AtomicBoolean(false)

    /** 纯判定（TDD 缝）：Android 13+ ∧ 未授予 ∧ 本会话未申请过。 */
    fun shouldRequest(sdkInt: Int, granted: Boolean, requestedThisSession: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted && !requestedThisSession

    /**
     * Main 挂载效果调用：命中策略返回 true（并占住会话闸门），调用方随即经
     * ActivityResultContract 发起系统申请；<33 / 已授予 / 已申请过返回 false。
     */
    fun shouldRequestNow(activity: Activity): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val should = shouldRequest(Build.VERSION.SDK_INT, granted, requestedThisSession.get())
        if (should) {
            requestedThisSession.set(true)
            Log.i(TAG, "requesting POST_NOTIFICATIONS (Android 13+ divergence from RN)")
        }
        return should
    }
}
