package cn.appia.im.core.push

import android.os.Handler
import android.os.Looper
import cn.appia.im.core.datastore.AuthSessionStore
import cn.appia.im.core.datastore.MmkvKvStore
import com.tencent.mmkv.MMKV

/**
 * 推送深链 drain 收口（RN pushNavigation.ts drainPendingRoomPushNavigations :225-232 +
 * MainNavigator.tsx:38-40/:62-72 + RootNavigator.tsx:53-56 三 drain 点移植）。
 *
 * 三个触发点（与 RN 一一对应，接线见 MainActivity/AppiaNavHost）：
 * 1. 导航就绪（RN NavigationContainer onReady）→ [onNavReady]；
 * 2. Main 挂载（RN MainNavigator mount useEffect）→ [onMainMounted]；
 * 3. 回前台（RN AppState 'active'）→ [onAppForeground]：即时 drain + 清理通知栏（坑 16：
 *    RN removeAllNotifications :64——进房已读后通知残留需清），再排 **400ms 延迟二次 drain**
 *    （RN setTimeout :67-72，竞态窗口补跳）；[onAppBackground]（RN else 分支）与再次回前台
 *    均取消在途延迟任务。400ms 延迟是 RN 原样移植（非 Android 惯例）。
 *
 * 门控 = `isAuthenticated && navReady`（RN isRoomPushNavigationReady :176-178）；
 * drain 幂等（队列清空），多触发点竞态天然无害；导航未接线（组合前窗口期）时不清队列不丢意图。
 *
 * 结构性简化 vs RN：RN 冷启动依赖桥层缓存点击事件待 JS 监听挂上补发；AA 单原生进程内
 * 阿里云 SDK 回调（AppiaAliyunPushReceiver）直接调 PushClickRouter.shared 入队同一 JVM
 * 实例（PendingPushNavigation.shared），无跨层缓存——队列即唯一缓冲。
 *
 * 时序依赖全部注入（[scheduleDelayed]），测试用 FakeScheduler 手动驱动。
 */
class PushNavigationDrainer(
    private val queue: PendingPushNavigation,
    private val isAuthenticated: () -> Boolean,
    /** RN setTimeout 等价：安排延迟任务，返回取消句柄（RN clearTimeout 语义）。 */
    private val scheduleDelayed: (delayMs: Long, block: () -> Unit) -> (() -> Unit),
) {

    /** 导航出口（AppiaNavHost 组合接线：drain 项 → navigateToRoomFromAppRoot(RoomRoute(...))）。 */
    var navigate: ((PendingPushNavigation.Intent) -> Unit)? = null

    /** 通知清理出口（坑 16 / RN removeAllNotifications :64 → NotificationManagerCompat.cancelAll）。 */
    var clearNotifications: (() -> Unit)? = null

    /** RN navRef 就绪标志：仅导航接线后置位（RN onReady 语义）。 */
    private var navReady = false

    private var cancelDelayed: (() -> Unit)? = null

    /** RN RootNavigator.tsx:53-56 onReady：导航就绪即冲刷（内部再验 auth）。 */
    fun onNavReady() {
        navReady = true
        drainNow()
    }

    /** RN MainNavigator.tsx:38-40 Main 挂载 useEffect。 */
    fun onMainMounted() {
        drainNow()
    }

    /** RN MainNavigator.tsx:62-72 AppState 'active' 分支（整体门控 isAuthenticated）。 */
    fun onAppForeground() {
        if (!isAuthenticated()) return
        drainNow()
        clearNotifications?.invoke()
        cancelDelayed?.invoke()
        cancelDelayed = scheduleDelayed(FOREGROUND_SECOND_DRAIN_DELAY_MS) { drainNow() }
    }

    /** RN else 分支：离前台取消在途延迟 drain。 */
    fun onAppBackground() {
        cancelDelayed?.invoke()
        cancelDelayed = null
    }

    /** RN drainPendingRoomPushNavigations :225-232（isRoomPushNavigationReady 门控 + drain）。 */
    private fun drainNow() {
        if (!navReady || !isAuthenticated()) return
        val handler = navigate ?: return // 组合前窗口期：不清队列，意图留给就绪后的触发点
        queue.drain().forEach(handler)
    }

    companion object {
        /** RN MainNavigator.tsx:71 `setTimeout(..., 400)`。 */
        const val FOREGROUND_SECOND_DRAIN_DELAY_MS = 400L

        /**
         * 进程级共享实例：AppiaNavHost 接线 navigate/clearNotifications 并触发 onNavReady/
         * onMainMounted，MainActivity.onResume/onPause 触发前台双沿。isAuthenticated 与
         * PushClickRouter.shared 同款读 default MMKV 登录态（receiver 反射实例化无 Hilt 注入点，
         * 单例共享同一套）。
         */
        val shared: PushNavigationDrainer by lazy {
            PushNavigationDrainer(
                queue = PendingPushNavigation.shared,
                isAuthenticated = {
                    AuthSessionStore(MmkvKvStore(MMKV.defaultMMKV())).isAuthenticated
                },
                scheduleDelayed = { delayMs, block ->
                    val handler = Handler(Looper.getMainLooper())
                    val runnable = Runnable { block() }
                    handler.postDelayed(runnable, delayMs)
                    val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
                    cancel
                },
            )
        }
    }
}
