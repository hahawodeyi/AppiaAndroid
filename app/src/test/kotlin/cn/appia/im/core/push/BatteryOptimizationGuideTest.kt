package cn.appia.im.core.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 电池优化引导判定（RN lib/batteryOptimization/guide.ts:17-42 + index.ts:30-38）。
 * 门控三层：仅 GMS 系机型（samsung/google/android）+ 未豁免 + 本会话未弹过；
 * 系统值即唯一真相源（用户在系统侧豁免后自然不再弹）。
 */
class BatteryOptimizationGuideTest {

    // ---- 机型门控（RN isBatteryGuideDevice :30-38） ----

    @Test
    fun `gms family devices are targeted`() {
        assertTrue(BatteryOptimizationGuide.isBatteryGuideDevice("samsung"))
        assertTrue(BatteryOptimizationGuide.isBatteryGuideDevice("google"))
        assertTrue(BatteryOptimizationGuide.isBatteryGuideDevice("android"))
    }

    @Test
    fun `manufacturer match is case insensitive`() {
        assertTrue(BatteryOptimizationGuide.isBatteryGuideDevice("SAMSUNG"))
        assertTrue(BatteryOptimizationGuide.isBatteryGuideDevice("Google"))
    }

    @Test
    fun `vendor channel devices are not targeted`() {
        // 华为/小米/OPPO/vivo/荣耀走厂商系统级通道，不依赖进程存活，弹窗无收益（RN index.ts:20-26）
        assertFalse(BatteryOptimizationGuide.isBatteryGuideDevice("HUAWEI"))
        assertFalse(BatteryOptimizationGuide.isBatteryGuideDevice("Xiaomi"))
        assertFalse(BatteryOptimizationGuide.isBatteryGuideDevice("OPPO"))
        assertFalse(BatteryOptimizationGuide.isBatteryGuideDevice("Honor"))
    }

    @Test
    fun `empty manufacturer is not targeted`() {
        // RN DeviceInfo.getManufacturerSync() ?? '' 的空回退不命中任何目标
        assertFalse(BatteryOptimizationGuide.isBatteryGuideDevice(""))
    }

    // ---- 弹窗判定（shouldShow = 机型 ∧ 未豁免 ∧ 会话未弹） ----

    @Test
    fun `target device restricted fresh session shows`() {
        assertTrue(
            BatteryOptimizationGuide.shouldShow(
                isTargetDevice = true, isIgnoring = false, shownThisSession = false,
            ),
        )
    }

    @Test
    fun `non target device never shows even if restricted`() {
        assertFalse(
            BatteryOptimizationGuide.shouldShow(
                isTargetDevice = false, isIgnoring = false, shownThisSession = false,
            ),
        )
    }

    @Test
    fun `already ignoring never shows`() {
        // 系统值即真相：用户允许后下一次检测自然不再弹（RN guide.ts:13-15 注释）
        assertFalse(
            BatteryOptimizationGuide.shouldShow(
                isTargetDevice = true, isIgnoring = true, shownThisSession = false,
            ),
        )
    }

    @Test
    fun `second alert in same session is suppressed`() {
        assertFalse(
            BatteryOptimizationGuide.shouldShow(
                isTargetDevice = true, isIgnoring = false, shownThisSession = true,
            ),
        )
    }

    @Test
    fun `shown flag does not override device gate`() {
        // 已弹过但机型不命中：仍不弹（门控先行，与 shown 无关）
        assertFalse(
            BatteryOptimizationGuide.shouldShow(
                isTargetDevice = false, isIgnoring = false, shownThisSession = true,
            ),
        )
    }
}
