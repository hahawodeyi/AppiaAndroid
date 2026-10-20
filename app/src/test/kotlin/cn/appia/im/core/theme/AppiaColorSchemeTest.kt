package cn.appia.im.core.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** M12-T1：M3 colorScheme 桥接映射断言（spec §3.1 表逐字段）。 */
class AppiaColorSchemeTest {

    @Test
    fun `light scheme bridges RN palette`() {
        val s = appiaColorScheme(false)
        assertEquals(AppiaColors.light.tintColor, s.primary)          // #1D74F5
        assertEquals(AppiaColors.light.backgroundColor, s.background) // #FFFFFF
        assertEquals(AppiaColors.light.titleText, s.onSurface)        // #0D0E12
        assertEquals(AppiaColors.light.auxiliaryText, s.onSurfaceVariant)
        assertEquals(AppiaColors.light.dangerColor, s.error)
        assertEquals(AppiaColors.light.borderColor, s.outline)
        assertEquals(AppiaColors.light.separatorColor, s.outlineVariant)
    }

    @Test
    fun `dark scheme bridges RN palette`() {
        val s = appiaColorScheme(true)
        assertEquals(AppiaColors.dark.tintColor, s.primary)
        assertEquals(AppiaColors.dark.backgroundColor, s.background)  // #030B1B
        assertEquals(AppiaColors.dark.titleText, s.onSurface)         // #F9F9F9
        assertEquals(AppiaColors.dark.auxiliaryText, s.onSurfaceVariant)
    }
}
