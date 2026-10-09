package cn.appia.im.core.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import cn.appia.im.core.datastore.InMemoryKvStore
import cn.appia.im.feature.settings.SettingsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5-T11：LocaleController 三态/持久化往返/系统跟随解析。
 * AppCompatDelegate 为静态门面——apply 后可经 getApplicationLocales 回读断言。
 */
class LocaleControllerTest {

    @Test
    fun `resolveActiveLanguage prefers preference`() {
        assertEquals("en", resolveActiveLanguage("en", "zh-CN"))
        assertEquals("zh", resolveActiveLanguage("zh", "en-US"))
    }

    @Test
    fun `resolveActiveLanguage null follows system primary language`() {
        assertEquals("zh", resolveActiveLanguage(null, "zh-CN"))
        assertEquals("zh", resolveActiveLanguage("system", "zh_Hans"))
        assertEquals("en", resolveActiveLanguage(null, "en-US"))
        assertEquals("en", resolveActiveLanguage("garbage", "fr-FR"))
    }

    @Test
    fun `apply zh persists and sets applicationLocales`() {
        val kv = InMemoryKvStore()
        val applied = LocaleController.apply(kv, "zh")
        assertEquals("zh", applied)
        assertEquals("zh", kv.getString(SettingsConstants.LANGUAGE_KEY, ""))
        assertEquals(
            LocaleListCompat.forLanguageTags("zh"),
            AppCompatDelegate.getApplicationLocales(),
        )
    }

    @Test
    fun `apply en persists`() {
        val kv = InMemoryKvStore()
        LocaleController.apply(kv, "en")
        assertEquals("en", kv.getString(SettingsConstants.LANGUAGE_KEY, ""))
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }

    @Test
    fun `apply system clears persistence and locales`() {
        val kv = InMemoryKvStore()
        LocaleController.apply(kv, "zh")
        LocaleController.apply(kv, "system")
        assertEquals("", kv.getString(SettingsConstants.LANGUAGE_KEY, ""))
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    @Test
    fun `apply invalid value normalizes to system`() {
        val kv = InMemoryKvStore()
        assertEquals("system", LocaleController.apply(kv, "fr"))
        assertEquals("", kv.getString(SettingsConstants.LANGUAGE_KEY, ""))
    }

    @Test
    fun `load empty falls back to system`() {
        val kv = InMemoryKvStore()
        assertEquals("system", LocaleController.load(kv))
        kv.putString(SettingsConstants.LANGUAGE_KEY, "zh")
        assertEquals("zh", LocaleController.load(kv))
    }

    @Test
    fun `restore applies only when preference exists`() {
        val kv = InMemoryKvStore()
        // 无偏好：restore 不设置 applicationLocales（保持缺省空）
        LocaleController.apply(kv, "system")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        assertEquals("system", LocaleController.restore(kv))
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
        // 有偏好：restore 应用
        kv.putString(SettingsConstants.LANGUAGE_KEY, "en")
        assertEquals("en", LocaleController.restore(kv))
        assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
    }
}
