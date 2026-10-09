package cn.appia.im.core.update

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自更新检查时机控制（RN lib/query/appReleaseQuery.ts:14-30 + AppUpdatePromptHost.tsx:45,68-75 语义）：
 * - 仅已登录 enabled（RN `authHydrated && token && isAuthenticated`；Android 注入 [loggedIn] 流代表
 *   最终会话态——MMKV 同步读，无独立 hydration 门）。
 * - 回前台重查（RN AppState active → invalidateQueries；T7 在 Lifecycle 转前台时调 [onAppForeground]）。
 * - 3h stale 窗口：仅当上次**完成**（成功响应，含 success=false 的 null 行）距今超过 3h 才真发请求
 *   （RN staleTime）；gcTime 24h 只管 JS 内存缓存回收，服务端无对应物，不移植。
 * - 失败不清旧值、不动时间戳 → 保持 stale，下次触发即重试（RN error 态 data 不变）。
 *
 * 输出：[release] StateFlow 供 T7 弹窗 host 消费——RN 分层里 query 只回数据，`isServerVersionNewer(
 * release.version, local)` 的弹窗判定与「暂不升级」静默在 Host 层（AppUpdatePromptHost.tsx:77-94），
 * 同样留给 T7。装配：App 单例 scope + `{ BuildConfig.VERSION_NAME }` localVersion（T7 接线）。
 */
class AppReleaseCheckController(
    private val loggedIn: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val localVersion: () -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    internal val staleMs: Long = DEFAULT_STALE_MS,
    private val fetchRelease: suspend () -> AppReleaseRow? = {
        fetchLatestAppRelease(localVersion = localVersion())
    },
) {
    private val mutex = Mutex()
    private val _release = MutableStateFlow<AppReleaseRow?>(null)

    /** 最近一次成功响应的版本行（null = 未拉到/成功但服务端无发布）。失败保留旧值。 */
    val release: StateFlow<AppReleaseRow?> = _release

    private var lastSuccessAt = 0L

    /** RN useQuery enabled 翻转即发起：登录态变化触发一次检查（T7 单例创建后调一次）。 */
    fun start() {
        scope.launch {
            // StateFlow 值去重，无需 distinctUntilChanged
            loggedIn.collect { checkIfStale() }
        }
    }

    /** RN AppState active → queryClient.invalidateQueries（AppUpdatePromptHost.tsx:68-75）。 */
    fun onAppForeground() {
        scope.launch { checkIfStale() }
    }

    /** stale 窗口内的核心检查；并发触发经 [mutex] 单飞（RN query 去重）。 */
    suspend fun checkIfStale() {
        if (!loggedIn.value) return // RN enabled 门（AppUpdatePromptHost.tsx:45）
        if (localVersion().isEmpty()) return // RN enabled && localVersion.length > 0（appReleaseQuery.ts:26）
        mutex.withLock {
            if (lastSuccessAt != 0L && nowMs() - lastSuccessAt <= staleMs) return // staleTime 窗口内
            val row = try {
                fetchRelease()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("update", "app release check failed", e)
                return
            }
            _release.value = row
            lastSuccessAt = nowMs()
        }
    }

    companion object {
        /** RN appReleaseQuery.ts:14 `STALE_MS = 3 * 60 * 60 * 1000`。 */
        const val DEFAULT_STALE_MS: Long = 3 * 60 * 60 * 1000L
    }
}
