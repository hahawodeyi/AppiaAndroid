package cn.appia.im.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VoiceEnv（RN voiceEnv.test.ts + agoraVoiceConstants VOICE_HOSTER_AGORA_UID）。
 */
class VoiceEnvTest {

    // RN: uses QA host when rocket server contains .dev.
    @Test
    fun `uses QA host when rocket server contains dev marker`() {
        assertEquals("shengwang-server-qa.appia.vip", VoiceEnv.shengwangApiHost("https://foo.dev.appia.cn"))
    }

    // RN: uses prod host otherwise
    @Test
    fun `uses prod host otherwise`() {
        assertEquals("shengwang-server.appia.vip", VoiceEnv.shengwangApiHost("https://ssc.appia.cn"))
    }

    // RN: returns agora app id constant aligned with legacy Config
    @Test
    fun `agora app id constant`() {
        assertEquals("fbb72b90d7f34c3487bf65d15c2b40bb", VoiceEnv.AGORA_APP_ID)
    }

    // agoraVoiceConstants.ts:2 hoster uid
    @Test
    fun `voice hoster agora uid constant`() {
        assertEquals(8888L, VoiceEnv.VOICE_HOSTER_AGORA_UID)
    }

    // RN: defaults to bitmain when enterprise id is empty
    @Test
    fun `resolveVoiceOrg defaults to bitmain when enterprise id is empty`() {
        assertEquals("bitmain", VoiceEnv.resolveVoiceOrg(null))
        assertEquals("bitmain", VoiceEnv.resolveVoiceOrg(""))
        assertEquals("bitmain", VoiceEnv.resolveVoiceOrg("   "))
    }

    // RN: returns trimmed enterprise id
    @Test
    fun `resolveVoiceOrg returns trimmed enterprise id`() {
        assertEquals("acme", VoiceEnv.resolveVoiceOrg("  acme  "))
    }
}
