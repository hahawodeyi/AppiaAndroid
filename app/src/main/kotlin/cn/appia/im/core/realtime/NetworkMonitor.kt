package cn.appia.im.core.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备网络可达性（RN NetInfo → networkStore.online 的等价，M2 T5）：ConnectivityManager
 * 回调驱动 StateFlow<Boolean?>，null = 尚未获知（RN networkStore 初始值）。
 *
 * 判定取默认网络的 VALIDATED 能力（NetInfo isInternetReachable 同源语义：captive portal 记为
 * 离线）；每次回调全量重读 activeNetwork，避免「WiFi onLost 但蜂窝仍在」的误报离线。
 *
 * 生命周期：start()/stop() 幂等，由挂载点（M2 T11 ChatListScreen）DisposableEffect 注册/注销，
 * 对应 RN startNetworkMonitoring.ts:23-32（先 fetch 一次再订阅）。
 * ponytail: RN 网络恢复触发的 checkAndReopen + syncInitial（startNetworkMonitoring.ts:19-22）未移植，
 * DdpClient 的 tryReopen 已兜底重连，恢复侧同步归 T11 挂载时按需接。
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow<Boolean?>(null)

    /** null = 未获知；true/false = 默认网络是否已验证联网。 */
    val online: StateFlow<Boolean?> = _online.asStateFlow()

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()

        override fun onLost(network: Network) = refresh()
    }

    fun start() {
        if (registered) return
        registered = true
        refresh()
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }.onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    /** activeNetwork 为 null（飞行模式）→ false；能力未知 → 保持 null（RN mapNetInfoConnected 的 null 透传）。 */
    private fun refresh() {
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        _online.value = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "network"
    }
}
