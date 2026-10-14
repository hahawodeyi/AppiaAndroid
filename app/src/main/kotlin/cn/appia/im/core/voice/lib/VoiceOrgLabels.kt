package cn.appia.im.core.voice.lib

import cn.appia.im.core.datastore.LoginSwitchCandidate

/**
 * RN lib/voice/voiceOrgLabels.ts + findLoginCandidateForVoiceOrg.ts verbatim。
 * LoginSwitchCandidate 复用 core/datastore（字段逐一对齐 RN ILoginSwitchCandidate）。
 */

/** voiceOrgLabels.ts:1-9：跨组织语音 org 短名 i18n key（sophgo_im → voice_org_sophgo 合并键）。 */
private val VOICE_ORG_I18N_KEYS = mapOf(
    "bitmain" to "voice_org_bitmain",
    "sophgo" to "voice_org_sophgo",
    "sophgo_im" to "voice_org_sophgo",
    "ssc" to "voice_org_ssc",
    "antpool" to "voice_org_antpool",
    "antalpha" to "voice_org_antalpha",
)

/** voiceOrgLabels.ts:11-17。 */
fun getVoiceOrgShortName(org: String?, t: (String) -> String): String? {
    if (org?.trim().isNullOrEmpty()) {
        return null
    }
    val key = org.trim().lowercase()
    val i18nKey = VOICE_ORG_I18N_KEYS[key]
    return if (i18nKey != null) t(i18nKey) else org
}

/** voiceOrgLabels.ts:19-20。 */
fun getVoiceOrgLogoUrl(org: String): String = "https://static.appia.cn/logo/${org.lowercase()}.png"

/** findLoginCandidateForVoiceOrg.ts:4-17：按语音 callMsg.org 匹配可切换主体（对齐旧版 companies.find + loginWithToken）。 */
fun findLoginCandidateForVoiceOrg(
    candidates: List<LoginSwitchCandidate>,
    org: String?,
): LoginSwitchCandidate? {
    val key = org?.trim()?.lowercase()
    if (key.isNullOrEmpty()) {
        return null
    }
    return candidates.find {
        it.companyName.trim().lowercase() == key || it.companyNameCn.trim().lowercase() == key
    }
}
