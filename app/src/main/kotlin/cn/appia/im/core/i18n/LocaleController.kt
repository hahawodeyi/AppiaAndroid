package cn.appia.im.core.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import cn.appia.im.core.datastore.KvStore
import cn.appia.im.feature.settings.SettingsConstants

/**
 * per-app locale 三态（M5-T11，对照 RN i18nStore.ts + resolveActiveLanguage.ts）：
 * - "system"/空（null 语义）→ 不设 applicationLocales，跟随系统
 * - "en"/"zh" → setApplicationLocales 单 locale
 * 持久化走 MMKV（SettingsConstants.LANGUAGE_KEY，T9 已定键）。
 * AppCompatDelegate 在 AppCompatActivity 上生效（MainActivity 已迁移），
 * API 33+ 直接走系统 per-app locale，以下由 AppCompat 兼容层处理。
 */

/** RN resolveActiveLanguage：偏好非 en/zh 时按系统语言解析（zh* → zh 否则 en）。 */
fun resolveActiveLanguage(preference: String?, systemLanguageTag: String): String {
    if (preference == "en" || preference == "zh") return preference
    return if (systemLanguageTag.substringBefore('-').lowercase().startsWith("zh")) "zh" else "en"
}

object LocaleController {

    /** 读偏好（缺/空 → "system"）。 */
    fun load(kv: KvStore): String =
        kv.getString(SettingsConstants.LANGUAGE_KEY, "").takeIf { it.isNotEmpty() } ?: "system"

    /**
     * 写偏好并应用：value ∈ {"system","en","zh"}；"system" → 清空 applicationLocales（跟随系统）。
     * 返回应用后的偏好值。
     */
    fun apply(kv: KvStore, value: String): String {
        val normalized = if (value == "en" || value == "zh") value else "system"
        if (normalized == "system") {
            kv.remove(SettingsConstants.LANGUAGE_KEY)
        } else {
            kv.putString(SettingsConstants.LANGUAGE_KEY, normalized)
        }
        if (normalized == "system") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
        }
        return normalized
    }

    /** 冷启动恢复：有偏好时应用（无偏好不动——系统态）。返回当前偏好。 */
    fun restore(kv: KvStore): String {
        val pref = load(kv)
        if (pref != "system") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(pref))
        }
        return pref
    }
}
