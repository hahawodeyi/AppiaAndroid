package cn.appia.im.core.realtime

import android.app.Application
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** RN mapNetInfoConnected（netInfoReachability.ts:1-8）的 Android 侧判定：飞行模式必须 false 不许 null。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkMonitorTest {

    @Test
    fun `no default network airplane mode maps to explicit offline false`() {
        // 评审 Critical 回归钉：飞行模式 activeNetwork==null → false（明确离线），不得写 null「未知」
        assertEquals(false, NetworkMonitor.mapNetworkOnline(activeNetwork = null, capabilities = null))
    }

    @Test
    fun `network without validated capability is offline`() {
        assertEquals(false, NetworkMonitor.mapNetworkOnline(ShadowNetwork.newInstance(1), NetworkCapabilities()))
    }

    @Test
    fun `validated default network is online`() {
        // SDK 36 桩对 unit test 隐藏 NetworkCapabilities.addCapability，走反射补 VALIDATED
        //（Robolectric 下为真实实现）
        val capabilities = NetworkCapabilities()
        NetworkCapabilities::class.java
            .getMethod("addCapability", Int::class.javaPrimitiveType)
            .invoke(capabilities, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertEquals(true, NetworkMonitor.mapNetworkOnline(ShadowNetwork.newInstance(1), capabilities))
    }

    @Test
    fun `existing network with unreadable capabilities counts offline`() {
        assertEquals(false, NetworkMonitor.mapNetworkOnline(ShadowNetwork.newInstance(1), capabilities = null))
    }
}
