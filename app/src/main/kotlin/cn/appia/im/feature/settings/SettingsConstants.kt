package cn.appia.im.feature.settings

/**
 * RN SettingsScreen/i18n 常量对照（M5-T9）：
 * - 语言段值域（LanguageSegmentValue 'system' | 'en' | 'zh'——T11 LocaleController 接）
 * - 默认字体（DefaultFontValue，defaultFontRuntime.ts:9-11）与 MMKV key（:5）
 * - 默认浏览器（DefaultBrowserValue，defaultBrowserPref.ts:8-13）与 MMKV key（:5）
 * - 浏览器分段选项（browserSegmentOptions.ts——Android 无 iOS canOpenURL 探测，
 *   恒为 base 两段 inApp/systemDefault，detectExtraBrowsers 仅 iOS 生效故省略）
 * - 法律链接（constants/legalUrls.ts:4-7 逐字）
 */
object SettingsConstants {
    /** 语言化键（T11——'system'|'en'|'zh'；空/缺 = system）。 */
    const val LANGUAGE_KEY = "appia_language"

    /** RN DEFAULT_FONT_SETTING_KEY（defaultFontRuntime.ts:5）。 */
    const val DEFAULT_FONT_KEY = "default_font_setting"

    /** RN DefaultFontValue 两段（defaultFontRuntime.ts:9-11）。 */
    val FONT_OPTIONS = listOf("Default_Font_Standard", "Follow_System_Setting")
    const val DEFAULT_FONT = "Default_Font_Standard"

    /** RN DEFAULT_BROWSER_KEY（defaultBrowserPref.ts:5）。 */
    const val DEFAULT_BROWSER_KEY = "DEFAULT_BROWSER_KEY"

    /**
     * Android 浏览器分段（RN mergeBrowserSegmentOptions 的 Android 恒态：base 两段 +
     * selectedExtra 命中段——extraBrowsers 仅 iOS canOpenURL 探测可达，Android 平台不可达）。
     */
    val BROWSER_OPTIONS = listOf("inApp", "systemDefault:")
    const val DEFAULT_BROWSER = "inApp"

    /** RN LEGAL_URLS（constants/legalUrls.ts:4-7）。 */
    const val LEGAL_TERMS_OF_SERVICE = "https://appia.cn/service/services-terms.html"
    const val LEGAL_PRIVACY_POLICY = "https://appia.cn/service/privacy.html"
}
