package cn.appia.im.feature.settings

import cn.appia.im.core.update.AppReleaseRow
import cn.appia.im.core.update.isServerVersionNewer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自更新弹窗宿主状态机（RN AppUpdatePromptHost.tsx:53-103 + stores/appUpdateUiStore.ts 的纯逻辑收敛）：
 * - [onReleaseRow]：RN useEffect :77-94 逐行——`isServerVersionNewer` 门、强制直接弹、
 *   会话内静默（点过「暂不升级」的同版本不再弹，同会话出现更高版本仍立即弹）。
 * - [showManual]：设置页手动检查的 bypassDismiss 入口（RN SettingsScreen :157
 *   `show(row, { bypassDismiss: true })`）——T9 接线点；可见期间自动路径冻结（RN :80-81）。
 * - 静默仅记内存 [dismissedVersion]，**不持久化**（坑 11：冷启动重弹是 2026-09-15 有意修订，勿改）。
 * 下载/安装不在本类（RN 分层同：Host 层做），见 AppReleaseInstaller。
 */
class AppUpdatePromptController(
    private val localVersion: () -> String,
) {

    /** 当前可见弹窗的版本行（null = 隐藏）；RN store {visible, release} 合一。 */
    private val _current = MutableStateFlow<AppReleaseRow?>(null)
    val current: StateFlow<AppReleaseRow?> = _current.asStateFlow()

    /** 会话内静默版本（RN dismissedVersionRef；进程内存活，冷启动即忘）。 */
    var dismissedVersion: String? = null
        private set

    /** 手动检查（bypassDismiss）打开的弹窗处于可见态（RN store.bypassDismiss 读取口径）。 */
    private var manualVisible = false

    /** RN :77-94：T6 检查流的每次版本行到达时调用（null 安全直返 = RN `!release` 短路）。 */
    fun onReleaseRow(release: AppReleaseRow?) {
        if (release == null) return
        if (_current.value != null && manualVisible) return // RN :80-81 手动弹窗可见即冻结
        if (!isServerVersionNewer(release.version, localVersion())) return // RN :83
        if (release.is_force_update) { // RN :85-87 强制无视静默直接弹
            show(release, manual = false)
            return
        }
        val dismissed = dismissedVersion
        if (dismissed != null && !isServerVersionNewer(release.version, dismissed)) return // RN :90-91
        show(release, manual = false)
    }

    /**
     * T9 接线缝（RN SettingsScreen :149-160 的 show 段）：设置页自行 fetchLatestAppRelease +
     * 「无发布/已是最新」告警后，有新版才调本方法（bypassDismiss：无视静默与新旧门直接弹）。
     */
    fun showManual(release: AppReleaseRow) = show(release, manual = true)

    /** RN onLater :96-103：可选版本记入会话内静默后隐藏；强制版本只隐藏（记录器不写）。 */
    fun onLater() {
        val release = _current.value
        if (release != null && !release.is_force_update) {
            dismissedVersion = release.version
        }
        hide()
    }

    /** RN :62-66 `!enabled → hide()`（登出/会话失效即收弹窗）；Android 由 Main 卸载等价触发。 */
    fun onSessionInactive() = hide()

    private fun hide() {
        _current.value = null
        manualVisible = false
    }

    private fun show(release: AppReleaseRow, manual: Boolean) {
        _current.value = release
        manualVisible = manual
    }
}
