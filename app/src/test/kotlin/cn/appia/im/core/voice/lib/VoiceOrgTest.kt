package cn.appia.im.core.voice.lib

import cn.appia.im.core.datastore.LoginSwitchCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * getVoiceOrgShortName / getVoiceOrgLogoUrl（voiceOrgLabels.ts，RN 无独立测试——映射逐行对照）
 * + findLoginCandidateForVoiceOrg（RN findLoginCandidateForVoiceOrg.test.ts 全用例）。
 */
class VoiceOrgTest {

    // ---- getVoiceOrgShortName ----

    @Test
    fun `maps known orgs to i18n keys`() {
        // voiceOrgLabels.ts:1-9 VOICE_ORG_I18N_KEYS（sophgo_im → voice_org_sophgo 合并键）
        val t: (String) -> String = { key -> "t($key)" }
        assertEquals("t(voice_org_bitmain)", getVoiceOrgShortName("bitmain", t))
        assertEquals("t(voice_org_sophgo)", getVoiceOrgShortName("sophgo", t))
        assertEquals("t(voice_org_sophgo)", getVoiceOrgShortName("sophgo_im", t))
        assertEquals("t(voice_org_ssc)", getVoiceOrgShortName("ssc", t))
        assertEquals("t(voice_org_antpool)", getVoiceOrgShortName("antpool", t))
        assertEquals("t(voice_org_antalpha)", getVoiceOrgShortName("antalpha", t))
    }

    @Test
    fun `org key is case-insensitive and trimmed`() {
        // voiceOrgLabels.ts:14-16 trim + toLowerCase
        val t: (String) -> String = { key -> "t($key)" }
        assertEquals("t(voice_org_bitmain)", getVoiceOrgShortName("  Bitmain ", t))
    }

    @Test
    fun `unknown org returns as-is and blank returns null`() {
        // voiceOrgLabels.ts:16-17
        val t: (String) -> String = { key -> "t($key)" }
        assertEquals("other-org", getVoiceOrgShortName("other-org", t))
        assertNull(getVoiceOrgShortName(null, t))
        assertNull(getVoiceOrgShortName("", t))
        assertNull(getVoiceOrgShortName("   ", t))
    }

    @Test
    fun `logo url lowercases org`() {
        // voiceOrgLabels.ts:19-20
        assertEquals("https://static.appia.cn/logo/bitmain.png", getVoiceOrgLogoUrl("Bitmain"))
        assertEquals("https://static.appia.cn/logo/ssc.png", getVoiceOrgLogoUrl("ssc"))
    }

    // ---- findLoginCandidateForVoiceOrg ----

    @Test
    fun `matches companyName case-insensitively`() {
        // RN 'matches companyName case-insensitively'
        val hit = findLoginCandidateForVoiceOrg(
            listOf(
                LoginSwitchCandidate(
                    userId = "1",
                    appiaUrl = "https://a.example",
                    phone = "",
                    companyName = "bitmain",
                    companyNameCn = "\u6bd4\u7279\u5927\u9646",
                    companyLogo = "",
                ),
            ),
            "Bitmain",
        )
        assertEquals("https://a.example", hit?.appiaUrl)
    }

    @Test
    fun `matches companyNameCn and blanks return null`() {
        // findLoginCandidateForVoiceOrg.ts:8-17
        val candidates = listOf(
            LoginSwitchCandidate(companyName = "sophgo", companyNameCn = "\u7b97\u80fd", appiaUrl = "https://s.example"),
        )
        assertEquals("https://s.example", findLoginCandidateForVoiceOrg(candidates, "\u7b97\u80fd")?.appiaUrl)
        assertNull(findLoginCandidateForVoiceOrg(candidates, null))
        assertNull(findLoginCandidateForVoiceOrg(candidates, "  "))
        assertNull(findLoginCandidateForVoiceOrg(emptyList(), "bitmain"))
    }
}
