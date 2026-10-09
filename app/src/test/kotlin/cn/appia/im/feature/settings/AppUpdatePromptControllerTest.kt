package cn.appia.im.feature.settings

import cn.appia.im.core.update.AppReleaseRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹窗宿主状态机对照（RN AppUpdatePromptHost.tsx:53-103）：新版本门、强制直弹、会话内静默
 * （坑 11 内存态）、更高版本重弹、手动检查 bypass 与可见冻结（bypassDismiss）、登出收弹窗。
 */
class AppUpdatePromptControllerTest {

    private val localVersion = "1.2.3"

    private fun controller() = AppUpdatePromptController { localVersion }

    private fun row(version: String, force: Boolean = false) = AppReleaseRow(
        platform = "android",
        version = version,
        url = "https://static.appia.cn/Appia-$version.apk",
        is_force_update = force,
        file_hash = null,
        notes = "",
    )

    @Test
    fun `newer optional release shows modal`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4"))
        assertEquals("1.2.4", c.current.value?.version)
    }

    @Test
    fun `same or older release never shows`() {
        val c = controller()
        c.onReleaseRow(row("1.2.3"))
        assertNull(c.current.value)
        c.onReleaseRow(row("1.2.2"))
        assertNull(c.current.value)
        c.onReleaseRow(null) // RN `!release` 短路
        assertNull(c.current.value)
    }

    @Test
    fun `later silences same version for the rest of the session`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4"))
        c.onLater()
        assertNull(c.current.value)
        assertEquals("1.2.4", c.dismissedVersion) // 会话内静默（内存，非持久化——坑 11）
        c.onReleaseRow(row("1.2.4")) // 同版本再查（回前台等）
        assertNull(c.current.value)
    }

    @Test
    fun `newer version in same session re-prompts after later`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4"))
        c.onLater()
        c.onReleaseRow(row("1.2.5"))
        assertEquals("1.2.5", c.current.value?.version)
    }

    @Test
    fun `force release shows immediately and ignores silence`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4", force = true))
        assertTrue(c.current.value?.is_force_update == true)
        c.onLater()
        c.onReleaseRow(row("1.2.4", force = true)) // 强制无视静默重弹（RN :85-87）
        assertTrue(c.current.value?.is_force_update == true)
    }

    @Test
    fun `later on force does not record silence`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4", force = true))
        c.onLater()
        assertNull(c.dismissedVersion) // RN :97-99 强制不写静默记录
        c.onReleaseRow(row("1.2.4")) // 可选同版本仍可弹
        assertEquals("1.2.4", c.current.value?.version)
    }

    @Test
    fun `manual check bypasses session silence`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4"))
        c.onLater()
        c.showManual(row("1.2.4")) // T9 缝：设置页手动检查（RN show(row, {bypassDismiss:true})）
        assertEquals("1.2.4", c.current.value?.version)
    }

    @Test
    fun `manual modal visible freezes auto path`() {
        val c = controller()
        c.showManual(row("1.2.4"))
        c.onReleaseRow(row("1.2.5")) // RN :80-81 visible && bypassDismiss → 冻结不覆盖
        assertEquals("1.2.4", c.current.value?.version)
        assertFalse(c.current.value?.version == "1.2.5")
    }

    @Test
    fun `session inactive hides modal`() {
        val c = controller()
        c.onReleaseRow(row("1.2.4"))
        c.onSessionInactive() // RN :62-66 !enabled → hide
        assertNull(c.current.value)
    }
}
