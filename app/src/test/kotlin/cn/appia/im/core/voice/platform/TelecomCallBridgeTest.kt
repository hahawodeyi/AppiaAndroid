package cn.appia.im.core.voice.platform

import android.app.Application
import android.content.ComponentName
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TelecomCallBridge 桥逻辑（M10-T9）：Robolectric 只验证本类分支面（未注册 phone account
 * 降级 false / listener 路由 / 未知 callId no-op），不触达真实 Telecom bind。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TelecomCallBridgeTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val telecomManager = context.getSystemService(TelecomManager::class.java)

    private fun bridge(): TelecomCallBridge {
        TelecomCallBridge.clearIncomingReported()
        return TelecomCallBridge(context, telecomManager)
    }

    @Test
    fun `reportIncomingCall fails gracefully without phone account`() {
        val bridge = bridge()
        // phone account 未注册（无 ensurePhoneAccountRegistered）→ addNewIncomingCall 拒绝 →
        // false 不抛
        var answered = false
        var rejected = false
        val result = bridge.reportIncomingCall("call-1", "Alice", { answered = true }, { rejected = true })
        assertFalse(result)
        assertFalse(answered)
        assertFalse(rejected)
        assertFalse(TelecomCallBridge.hasActiveIncomingReport())
    }

    @Test
    fun `answer callback routes to registered listener`() {
        val bridge = bridge()
        TelecomCallBridge.ensurePhoneAccountRegistered(context)
        var answered = false
        val result = bridge.reportIncomingCall("call-2", "Alice", { answered = true }, {})
        assertTrue(result)
        assertTrue(TelecomCallBridge.hasActiveIncomingReport())
        // ConnectionService 侧 onAnswer → 静态桥 listener 投递
        TelecomCallBridge.dispatchAnswer("call-2")
        assertTrue(answered)
        // setDisconnected 清 reported 旗标
        bridge.setDisconnected("call-2")
        assertFalse(TelecomCallBridge.hasActiveIncomingReport())
    }

    @Test
    fun `reject callback routes to registered listener`() {
        val bridge = bridge()
        TelecomCallBridge.ensurePhoneAccountRegistered(context)
        var rejected = false
        assertTrue(bridge.reportIncomingCall("call-3", "Bob", {}, { rejected = true }))
        TelecomCallBridge.dispatchReject("call-3")
        assertTrue(rejected)
        bridge.setDisconnected("call-3")
        assertFalse(TelecomCallBridge.hasActiveIncomingReport())
    }

    @Test
    fun `setActive and setDisconnected are no-op for unknown callId`() {
        val bridge = bridge()
        // 未上报的 callId：不抛、不改全局 reported 旗标
        bridge.setActive("unknown")
        bridge.setDisconnected("unknown")
        assertFalse(TelecomCallBridge.hasActiveIncomingReport())
        // dispatch 对未知 callId 同样 no-op
        TelecomCallBridge.dispatchAnswer("unknown")
        TelecomCallBridge.dispatchReject("unknown")
    }
}
