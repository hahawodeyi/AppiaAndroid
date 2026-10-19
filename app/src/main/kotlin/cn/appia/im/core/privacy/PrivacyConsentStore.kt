package cn.appia.im.core.privacy

import cn.appia.im.core.datastore.KvStore

/**
 * 首启隐私同意门禁（M11-T5 Slot3 / RN PrivacyConsentStore.kt 对齐）：
 * RN 侧用 SharedPreferences `appia_privacy_consent` / `privacy_consent_agreed`，
 * AA 按 KV 惯例落 MMKV default instance（KvStore 注入），key 沿 RN 语义命名。
 * 默认 false（未同意）——pm clear / 重装后首启必弹同意屏。
 */
object PrivacyConsentStore {

    /** RN KEY_AGREED 同名（privacy_consent_agreed）。 */
    const val KEY_AGREED = "privacy_consent_agreed"

    fun hasAgreed(kv: KvStore): Boolean = kv.getBoolean(KEY_AGREED, false)

    /** 同意即写 true，无撤销路径（RN 同——不同意是退出而非撤销）。 */
    fun setAgreed(kv: KvStore) {
        kv.putBoolean(KEY_AGREED, true)
    }
}
