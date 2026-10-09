package cn.appia.im.core.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 通知权限申请判定（RN 无对应——AA 故意分歧：Android 13+ 补 POST_NOTIFICATIONS 运行时申请，坑 4）。
 * 策略：SDK ≥ 33 且未授予且本会话未申请过（会话内至多一次，拒绝不缠磨）。
 */
class NotificationPermissionGateTest {

    @Test
    fun `below Android 13 never requests`() {
        assertFalse(NotificationPermissionGate.shouldRequest(sdkInt = 32, granted = false, requestedThisSession = false))
        assertFalse(NotificationPermissionGate.shouldRequest(sdkInt = 24, granted = false, requestedThisSession = false))
    }

    @Test
    fun `already granted never requests`() {
        assertFalse(NotificationPermissionGate.shouldRequest(sdkInt = 33, granted = true, requestedThisSession = false))
        assertFalse(NotificationPermissionGate.shouldRequest(sdkInt = 34, granted = true, requestedThisSession = true))
    }

    @Test
    fun `android 13 denied fresh session requests`() {
        assertTrue(NotificationPermissionGate.shouldRequest(sdkInt = 33, granted = false, requestedThisSession = false))
        assertTrue(NotificationPermissionGate.shouldRequest(sdkInt = 36, granted = false, requestedThisSession = false))
    }

    @Test
    fun `second request in same session is suppressed`() {
        assertFalse(NotificationPermissionGate.shouldRequest(sdkInt = 33, granted = false, requestedThisSession = true))
    }
}
