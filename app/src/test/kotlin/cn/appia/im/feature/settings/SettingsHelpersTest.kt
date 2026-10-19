package cn.appia.im.feature.settings

import cn.appia.im.core.update.AppReleaseRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M11 G10：设置页手动检查更新三分支纯函数（RN SettingsScreen/index.tsx:148-160 判定链）。
 */
class SettingsHelpersTest {

    private fun row(version: String) = AppReleaseRow(
        platform = "android",
        version = version,
        url = "https://static.appia.cn/Appia-$version.apk",
        is_force_update = false,
        file_hash = null,
        notes = "",
    )

    @Test
    fun `null row maps to failed`() {
        // RN :151 `!row` → appUpdate_checkFailed
        assertTrue(manualCheckOutcome(null, "1.2.3") is ManualCheckOutcome.Failed)
    }

    @Test
    fun `same version maps to latest`() {
        assertTrue(manualCheckOutcome(row("1.2.3"), "1.2.3") is ManualCheckOutcome.Latest)
    }

    @Test
    fun `older server version maps to latest`() {
        assertTrue(manualCheckOutcome(row("1.2.2"), "1.2.3") is ManualCheckOutcome.Latest)
    }

    @Test
    fun `newer server version maps to show carrying row`() {
        // RN :157 show(row, { bypassDismiss: true })
        val out = manualCheckOutcome(row("1.2.4"), "1.2.3")
        assertTrue(out is ManualCheckOutcome.Show)
        assertEquals("1.2.4", (out as ManualCheckOutcome.Show).row.version)
    }
}
